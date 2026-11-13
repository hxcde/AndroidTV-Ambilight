package io.github.hxcde.ambilight.mqtt

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream

class MqttPacketsTest {

    @Test
    fun remainingLength() {
        assertArrayEquals(byteArrayOf(0), MqttPackets.encodeRemainingLength(0))
        assertArrayEquals(byteArrayOf(127), MqttPackets.encodeRemainingLength(127))
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0x01), MqttPackets.encodeRemainingLength(128))
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0x7F), MqttPackets.encodeRemainingLength(16_383))
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0x80.toByte(), 0x01), MqttPackets.encodeRemainingLength(16_384))
    }

    @Test
    fun connectWithCredentialsAndWill() {
        val will = MqttPackets.Will("tv/status", "offline".toByteArray(), retain = true)
        val p = MqttPackets.connect("cid", "user", "pw", 30, will)
        assertEquals(0x10, p[0].toInt() and 0xFF)
        val body = p.copyOfRange(2, p.size)
        // Protokollname "MQTT", Level 4
        assertArrayEquals(byteArrayOf(0, 4, 'M'.code.toByte(), 'Q'.code.toByte(), 'T'.code.toByte(), 'T'.code.toByte(), 4), body.copyOfRange(0, 7))
        // Flags: Benutzer, Passwort, Will-Retain, Will, Clean Session
        assertEquals(0x80 or 0x40 or 0x20 or 0x04 or 0x02, body[7].toInt() and 0xFF)
        assertEquals(30, ((body[8].toInt() and 0xFF) shl 8) or (body[9].toInt() and 0xFF))
        assertEquals(body.size, p[1].toInt() and 0xFF)
    }

    @Test
    fun connectWithoutCredentials() {
        val p = MqttPackets.connect("cid", null, null, 30, null)
        assertEquals(0x02, p[2 + 7].toInt() and 0xFF)
    }

    @Test
    fun publishRoundTrip() {
        val packet = MqttPackets.publish("zigbee2mqtt/Playbar links/set", "{\"state\":\"ON\"}".toByteArray(), retain = true)
        val read = MqttPackets.read(ByteArrayInputStream(packet))
        assertEquals(MqttPackets.PUBLISH, read.type)
        assertEquals(1, read.header and 0x01)
        val msg = MqttPackets.parsePublish(read)
        assertEquals("zigbee2mqtt/Playbar links/set", msg.topic)
        assertEquals("{\"state\":\"ON\"}", String(msg.payload))
        assertEquals(0, msg.qos)
    }

    @Test
    fun parseQos1Publish() {
        // Topic "a/b", Paket-ID 0x1234, Payload "ON"
        val body = byteArrayOf(0, 3, 'a'.code.toByte(), '/'.code.toByte(), 'b'.code.toByte(), 0x12, 0x34, 'O'.code.toByte(), 'N'.code.toByte())
        val raw = MqttPackets.frame(0x32, body)
        val msg = MqttPackets.parsePublish(MqttPackets.read(ByteArrayInputStream(raw)))
        assertEquals("a/b", msg.topic)
        assertEquals(1, msg.qos)
        assertEquals(0x1234, msg.packetId)
        assertEquals("ON", String(msg.payload))
    }

    @Test
    fun largePayloadLength() {
        val payload = ByteArray(300) { 'x'.code.toByte() }
        val packet = MqttPackets.publish("t", payload, retain = false)
        val read = MqttPackets.read(ByteArrayInputStream(packet))
        assertEquals(300, MqttPackets.parsePublish(read).payload.size)
    }

    @Test
    fun subscribePacket() {
        val p = MqttPackets.subscribe(7, listOf("tv/set"))
        assertEquals(0x82, p[0].toInt() and 0xFF)
        assertEquals(2 + 2 + 6 + 1, p[1].toInt())
        assertEquals(7, p[3].toInt())
        assertEquals(0, p.last().toInt())
    }
}
