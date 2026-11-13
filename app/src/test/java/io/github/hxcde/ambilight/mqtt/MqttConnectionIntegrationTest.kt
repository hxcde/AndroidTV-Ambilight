package io.github.hxcde.ambilight.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Test gegen einen echten Broker. Läuft nur, wenn MQTT_IT_PORT gesetzt ist, z. B.:
 *   MQTT_IT_PORT=18830 MQTT_IT_USER=test MQTT_IT_PASSWORD=test ./gradlew testDebugUnitTest
 */
class MqttConnectionIntegrationTest {
    private val port = System.getenv("MQTT_IT_PORT")?.toIntOrNull()
    private val user = System.getenv("MQTT_IT_USER")
    private val password = System.getenv("MQTT_IT_PASSWORD")

    private fun config(clientId: String, pw: String? = password, will: MqttPackets.Will? = null) =
        MqttConfig("127.0.0.1", port!!, false, user, pw, clientId, keepAliveSec = 10, will = will)

    @Test
    fun publishSubscribeRoundTrip() {
        assumeTrue(port != null)
        val connected = CountDownLatch(1)
        val received = LinkedBlockingQueue<Pair<String, String>>()
        val conn = MqttConnection(config("it-roundtrip"), object : MqttListener {
            override fun onConnected() = connected.countDown()
            override fun onMessage(topic: String, payload: String) {
                received.add(topic to payload)
            }
        })
        conn.start(listOf("it/#"))
        assertTrue("keine Verbindung", connected.await(5, TimeUnit.SECONDS))
        Thread.sleep(300) // SUBACK abwarten

        conn.publish("it/a", "eins")
        repeat(20) { conn.publishLatest("it/latest", "wert-$it") }
        val first = received.poll(3, TimeUnit.SECONDS)
        assertEquals("it/a" to "eins", first)
        // publishLatest fasst zusammen: der letzte Wert muss ankommen
        var last: Pair<String, String>? = null
        while (true) {
            val m = received.poll(1, TimeUnit.SECONDS) ?: break
            last = m
        }
        assertEquals("it/latest" to "wert-19", last)
        conn.stop()
    }

    @Test
    fun willIsPublishedOnConnectionLoss() {
        assumeTrue(port != null)
        val received = LinkedBlockingQueue<String>()
        val watcherUp = CountDownLatch(1)
        val watcher = MqttConnection(config("it-watcher"), object : MqttListener {
            override fun onConnected() = watcherUp.countDown()
            override fun onMessage(topic: String, payload: String) {
                if (topic == "it/will") received.add(payload)
            }
        })
        watcher.start(listOf("it/will"))
        assertTrue(watcherUp.await(5, TimeUnit.SECONDS))
        Thread.sleep(300)

        // Verbindung mit Will aufbauen und hart trennen (Socket schließen ohne DISCONNECT)
        val s = MqttConnection.openSocket(config("it-will"))
        val will = MqttPackets.Will("it/will", "offline".toByteArray(), retain = false)
        s.getOutputStream().write(MqttPackets.connect("it-will", user, password, 10, will))
        s.getOutputStream().flush()
        MqttPackets.read(s.getInputStream())
        s.close()

        assertEquals("offline", received.poll(5, TimeUnit.SECONDS))
        watcher.stop()
    }

    @Test
    fun testOnceReportsBadPassword() {
        assumeTrue(port != null && password != null)
        assertNull(MqttConnection.testOnce(config("it-test"), listOf("it/x" to "y")))
        val error = MqttConnection.testOnce(config("it-test", pw = "falsch"), emptyList())
        assertNotNull(error)
        assertTrue(error!!, error.contains("Passwort") || error.contains("autorisiert"))
    }
}
