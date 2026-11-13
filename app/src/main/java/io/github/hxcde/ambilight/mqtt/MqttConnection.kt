package io.github.hxcde.ambilight.mqtt

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

data class MqttConfig(
    val host: String,
    val port: Int,
    val tls: Boolean,
    val username: String?,
    val password: String?,
    val clientId: String,
    val keepAliveSec: Int = 30,
    val will: MqttPackets.Will? = null,
)

interface MqttListener {
    fun onConnected() {}
    fun onDisconnected(reason: String) {}
    fun onMessage(topic: String, payload: String) {}
}

/**
 * Minimaler MQTT-3.1.1-Client (nur QoS 0) mit automatischem Wiederverbinden.
 *
 * Alle Schreibzugriffe laufen auf einem einzigen Thread. [publishLatest] fasst Nachrichten
 * pro Topic zusammen: Ist der Broker langsam, wird nur der neueste Stand gesendet und es
 * staut sich nichts auf.
 */
class MqttConnection(
    private val config: MqttConfig,
    private val listener: MqttListener,
    private val log: (String) -> Unit = {},
) {
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "mqtt-writer").apply { isDaemon = true } }

    private val lock = Any()
    private val pendingLatest = LinkedHashMap<String, Pair<ByteArray, Boolean>>()
    private var drainScheduled = false

    @Volatile private var socket: Socket? = null
    private var output: OutputStream? = null
    private var pingTask: ScheduledFuture<*>? = null
    private var subscriptions: List<String> = emptyList()
    private var backoffStep = 0
    private var nextPacketId = 1

    @Volatile var isConnected: Boolean = false
        private set

    /** Anzahl Nachrichten, die durch eine neuere ersetzt wurden, bevor sie gesendet werden konnten. */
    val dropped = java.util.concurrent.atomic.AtomicLong()

    @Volatile private var stopped = false

    fun start(subscribeTopics: List<String>) {
        subscriptions = subscribeTopics
        executor.execute { connect() }
    }

    /** Sendet sofort (Reihenfolge bleibt erhalten). Ohne Verbindung wird verworfen. */
    fun publish(topic: String, payload: String, retain: Boolean = false) {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        runOnWriter { if (isConnected) write(MqttPackets.publish(topic, bytes, retain)) }
    }

    /** Sendet nur den jeweils neuesten Payload pro Topic. Ohne Verbindung wird verworfen. */
    fun publishLatest(topic: String, payload: String, retain: Boolean = false) {
        synchronized(lock) {
            if (pendingLatest.put(topic, payload.toByteArray(Charsets.UTF_8) to retain) != null) dropped.incrementAndGet()
            if (drainScheduled) return
            drainScheduled = true
        }
        runOnWriter { drain() }
    }

    /** Sendet die letzten Nachrichten, trennt sauber und beendet den Client. */
    fun stop(finalMessages: List<Triple<String, String, Boolean>> = emptyList()) {
        if (stopped) return
        stopped = true
        runOnWriter {
            if (isConnected) {
                try {
                    drain()
                    for ((t, p, r) in finalMessages) write(MqttPackets.publish(t, p.toByteArray(Charsets.UTF_8), r))
                    write(MqttPackets.disconnect())
                } catch (e: IOException) {
                    log("Fehler beim Trennen: ${e.message}")
                }
            }
            closeSocket()
            isConnected = false
        }
        executor.shutdown()
    }

    private fun runOnWriter(block: () -> Unit) {
        try {
            executor.execute {
                try {
                    block()
                } catch (e: IOException) {
                    connectionLost(e.message ?: "Schreibfehler")
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // bereits gestoppt
        }
    }

    private fun drain() {
        val batch: List<Map.Entry<String, Pair<ByteArray, Boolean>>>
        synchronized(lock) {
            batch = pendingLatest.entries.toList()
            pendingLatest.clear()
            drainScheduled = false
        }
        if (!isConnected) return
        for ((topic, value) in batch) write(MqttPackets.publish(topic, value.first, value.second))
    }

    private fun connect() {
        if (stopped) return
        try {
            val s = openSocket(config)
            s.soTimeout = 10_000
            val out = BufferedOutputStream(s.getOutputStream())
            val input = BufferedInputStream(s.getInputStream())
            out.write(MqttPackets.connect(config.clientId, config.username, config.password, config.keepAliveSec, config.will))
            out.flush()
            val ack = MqttPackets.read(input)
            if (ack.type != MqttPackets.CONNACK || ack.body.size < 2) throw IOException("Unerwartete Antwort vom Broker")
            val code = ack.body[1].toInt() and 0xFF
            if (code != 0) {
                s.close()
                throw IOException("Broker lehnt ab: ${MqttPackets.connackMessage(code)}")
            }
            s.soTimeout = config.keepAliveSec * 1500
            socket = s
            output = out
            isConnected = true
            backoffStep = 0
            log("Verbunden mit ${config.host}:${config.port}")

            if (subscriptions.isNotEmpty()) {
                val id = nextPacketId
                nextPacketId = if (nextPacketId >= 0xFFFF) 1 else nextPacketId + 1
                write(MqttPackets.subscribe(id, subscriptions))
            }
            val interval = (config.keepAliveSec / 2).coerceAtLeast(5).toLong()
            pingTask = executor.scheduleWithFixedDelay({
                try {
                    if (isConnected) write(MqttPackets.pingreq())
                } catch (e: IOException) {
                    connectionLost(e.message ?: "Ping fehlgeschlagen")
                }
            }, interval, interval, TimeUnit.SECONDS)

            Thread({ readLoop(s, input) }, "mqtt-reader").apply { isDaemon = true }.start()
            listener.onConnected()
        } catch (e: Exception) {
            closeSocket()
            isConnected = false
            val reason = e.message ?: e.javaClass.simpleName
            log("Verbindung fehlgeschlagen: $reason")
            listener.onDisconnected(reason)
            scheduleReconnect()
        }
    }

    private fun readLoop(s: Socket, input: java.io.InputStream) {
        try {
            while (!stopped && socket === s) {
                val p = MqttPackets.read(input)
                when (p.type) {
                    MqttPackets.PUBLISH -> {
                        val msg = MqttPackets.parsePublish(p)
                        if (msg.qos == 1) runOnWriter { if (socket === s) write(MqttPackets.puback(msg.packetId)) }
                        listener.onMessage(msg.topic, String(msg.payload, Charsets.UTF_8))
                    }
                    else -> Unit // CONNACK, SUBACK, PINGRESP: nichts zu tun
                }
            }
        } catch (e: SocketTimeoutException) {
            if (socket === s) executor.execute { if (socket === s) connectionLost("Keine Antwort vom Broker") }
        } catch (e: IOException) {
            if (socket === s && !stopped) executor.execute { if (socket === s) connectionLost(e.message ?: "Lesefehler") }
        }
    }

    /** Muss auf dem Writer-Thread laufen. */
    private fun connectionLost(reason: String) {
        if (!isConnected && socket == null) return
        log("Verbindung verloren: $reason")
        closeSocket()
        isConnected = false
        if (stopped) return
        listener.onDisconnected(reason)
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (stopped) return
        val delays = longArrayOf(1, 2, 5, 10, 30)
        val delay = delays[backoffStep.coerceAtMost(delays.size - 1)]
        backoffStep++
        try {
            executor.schedule({ connect() }, delay, TimeUnit.SECONDS)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
        }
    }

    private fun write(packet: ByteArray) {
        val out = output ?: return
        out.write(packet)
        out.flush()
    }

    private fun closeSocket() {
        pingTask?.cancel(false)
        pingTask = null
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        socket = null
        output = null
    }

    companion object {
        fun openSocket(config: MqttConfig): Socket {
            val plain = Socket()
            plain.tcpNoDelay = true
            try {
                plain.connect(InetSocketAddress(config.host, config.port), 5_000)
                if (!config.tls) return plain
                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                val ssl = factory.createSocket(plain, config.host, config.port, true) as SSLSocket
                ssl.soTimeout = 10_000
                ssl.startHandshake()
                if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(config.host, ssl.session)) {
                    ssl.close()
                    throw SSLPeerUnverifiedException("Zertifikat passt nicht zum Host ${config.host}")
                }
                return ssl
            } catch (e: Exception) {
                try {
                    plain.close()
                } catch (_: IOException) {
                }
                throw e
            }
        }

        /**
         * Einmaliger, blockierender Verbindungstest: verbinden, Nachrichten senden, trennen.
         * Gibt null bei Erfolg zurück, sonst eine Fehlermeldung.
         */
        fun testOnce(config: MqttConfig, messages: List<Pair<String, String>>): String? {
            var s: Socket? = null
            return try {
                s = openSocket(config)
                s.soTimeout = 5_000
                val out = BufferedOutputStream(s.getOutputStream())
                val input = BufferedInputStream(s.getInputStream())
                out.write(MqttPackets.connect(config.clientId + "-test", config.username, config.password, 10, null))
                out.flush()
                val ack = MqttPackets.read(input)
                if (ack.type != MqttPackets.CONNACK || ack.body.size < 2) return "Unerwartete Antwort vom Broker"
                val code = ack.body[1].toInt() and 0xFF
                if (code != 0) return MqttPackets.connackMessage(code)
                for ((t, p) in messages) out.write(MqttPackets.publish(t, p.toByteArray(Charsets.UTF_8), false))
                out.write(MqttPackets.disconnect())
                out.flush()
                null
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            } finally {
                try {
                    s?.close()
                } catch (_: IOException) {
                }
            }
        }
    }
}
