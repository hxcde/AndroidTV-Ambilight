package io.github.hxcde.ambilight.mqtt

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Kodierung/Dekodierung der wenigen MQTT-3.1.1-Pakete, die die App braucht.
 * Reines Kotlin ohne Android-Abhängigkeiten, damit es als JVM-Test prüfbar ist.
 */
object MqttPackets {
    const val CONNECT = 0x10
    const val CONNACK = 0x20
    const val PUBLISH = 0x30
    const val PUBACK = 0x40
    const val SUBSCRIBE = 0x82
    const val SUBACK = 0x90
    const val PINGREQ = 0xC0
    const val PINGRESP = 0xD0
    const val DISCONNECT = 0xE0

    class Will(val topic: String, val payload: ByteArray, val retain: Boolean)

    class Packet(val header: Int, val body: ByteArray) {
        val type: Int get() = header and 0xF0
    }

    class IncomingPublish(val topic: String, val payload: ByteArray, val qos: Int, val packetId: Int)

    fun connect(
        clientId: String,
        username: String?,
        password: String?,
        keepAliveSec: Int,
        will: Will?,
    ): ByteArray {
        val body = ByteArrayOutputStream()
        body.writeString("MQTT")
        body.write(4) // Protokoll-Level 3.1.1
        var flags = 0x02 // Clean Session
        if (will != null) {
            flags = flags or 0x04 // Will-Flag, QoS 0
            if (will.retain) flags = flags or 0x20
        }
        if (!username.isNullOrEmpty()) {
            flags = flags or 0x80
            if (password != null) flags = flags or 0x40
        }
        body.write(flags)
        body.write((keepAliveSec shr 8) and 0xFF)
        body.write(keepAliveSec and 0xFF)
        body.writeString(clientId)
        if (will != null) {
            body.writeString(will.topic)
            body.writeBinary(will.payload)
        }
        if (!username.isNullOrEmpty()) {
            body.writeString(username)
            if (password != null) body.writeBinary(password.toByteArray(Charsets.UTF_8))
        }
        return frame(CONNECT, body.toByteArray())
    }

    fun publish(topic: String, payload: ByteArray, retain: Boolean): ByteArray {
        val body = ByteArrayOutputStream()
        body.writeString(topic)
        body.write(payload)
        return frame(PUBLISH or (if (retain) 0x01 else 0x00), body.toByteArray())
    }

    fun subscribe(packetId: Int, topics: List<String>): ByteArray {
        val body = ByteArrayOutputStream()
        body.write((packetId shr 8) and 0xFF)
        body.write(packetId and 0xFF)
        for (t in topics) {
            body.writeString(t)
            body.write(0) // QoS 0
        }
        return frame(SUBSCRIBE, body.toByteArray())
    }

    fun puback(packetId: Int): ByteArray =
        byteArrayOf(PUBACK.toByte(), 2, ((packetId shr 8) and 0xFF).toByte(), (packetId and 0xFF).toByte())

    fun pingreq(): ByteArray = byteArrayOf(PINGREQ.toByte(), 0)

    fun disconnect(): ByteArray = byteArrayOf(DISCONNECT.toByte(), 0)

    fun frame(header: Int, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(body.size + 5)
        out.write(header)
        out.write(encodeRemainingLength(body.size))
        out.write(body)
        return out.toByteArray()
    }

    fun encodeRemainingLength(length: Int): ByteArray {
        require(length in 0..268_435_455) { "Paket zu groß: $length" }
        val out = ByteArrayOutputStream(4)
        var x = length
        do {
            var digit = x % 128
            x /= 128
            if (x > 0) digit = digit or 0x80
            out.write(digit)
        } while (x > 0)
        return out.toByteArray()
    }

    /** Liest ein vollständiges Paket. Wirft [IOException] bei Verbindungsende. */
    fun read(input: InputStream, maxSize: Int = 1 shl 20): Packet {
        val header = input.read()
        if (header < 0) throw IOException("Verbindung vom Broker geschlossen")
        var multiplier = 1
        var length = 0
        var count = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw IOException("Verbindung vom Broker geschlossen")
            length += (b and 0x7F) * multiplier
            if (b and 0x80 == 0) break
            multiplier *= 128
            if (++count >= 4) throw IOException("Ungültige Paketlänge")
        }
        if (length > maxSize) throw IOException("Paket zu groß: $length")
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) throw IOException("Verbindung vom Broker geschlossen")
            read += n
        }
        return Packet(header, body)
    }

    fun parsePublish(p: Packet): IncomingPublish {
        val qos = (p.header shr 1) and 0x03
        val b = p.body
        if (b.size < 2) throw IOException("Ungültiges PUBLISH")
        val topicLen = ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
        var pos = 2 + topicLen
        if (pos > b.size) throw IOException("Ungültiges PUBLISH")
        val topic = String(b, 2, topicLen, Charsets.UTF_8)
        var packetId = 0
        if (qos > 0) {
            if (pos + 2 > b.size) throw IOException("Ungültiges PUBLISH")
            packetId = ((b[pos].toInt() and 0xFF) shl 8) or (b[pos + 1].toInt() and 0xFF)
            pos += 2
        }
        return IncomingPublish(topic, b.copyOfRange(pos, b.size), qos, packetId)
    }

    fun connackMessage(code: Int): String = when (code) {
        0 -> "OK"
        1 -> "Protokollversion vom Broker abgelehnt"
        2 -> "Client-ID abgelehnt"
        3 -> "Broker nicht verfügbar"
        4 -> "Benutzername oder Passwort falsch"
        5 -> "Nicht autorisiert"
        else -> "Unbekannter Fehler ($code)"
    }

    private fun ByteArrayOutputStream.writeString(s: String) = writeBinary(s.toByteArray(Charsets.UTF_8))

    private fun ByteArrayOutputStream.writeBinary(data: ByteArray) {
        require(data.size <= 0xFFFF) { "Feld zu lang" }
        write((data.size shr 8) and 0xFF)
        write(data.size and 0xFF)
        write(data)
    }
}
