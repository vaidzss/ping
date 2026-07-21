package dev.meshaid.core.protocol

import dev.meshaid.core.crypto.Identity
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PacketCodecTest {

    private fun samplePacket(
        type: PacketType = PacketType.CHAT,
        ttl: Int = 7,
        recipient: NodeId? = null,
        payload: ByteArray = "hello mesh".toByteArray(),
        signature: ByteArray? = null,
    ) = Packet(
        type = type,
        ttl = ttl,
        timestampMs = 1_752_000_000_000,
        senderId = NodeId(0x0123456789ABCDEFL),
        recipientId = recipient,
        payload = payload,
        signature = signature,
    )

    @Test
    fun `round trips every packet type`() {
        for (type in PacketType.entries) {
            val packet = samplePacket(type = type)
            val decoded = PacketCodec.decode(PacketCodec.encode(packet))
            assertEquals(type, decoded.type)
            assertEquals(packet.ttl, decoded.ttl)
            assertEquals(packet.timestampMs, decoded.timestampMs)
            assertEquals(packet.senderId, decoded.senderId)
            assertNull(decoded.recipientId)
            assertContentEquals(packet.payload, decoded.payload)
        }
    }

    @Test
    fun `round trips recipient and signature`() {
        val packet = samplePacket(recipient = NodeId(42L), signature = ByteArray(64) { it.toByte() })
        val decoded = PacketCodec.decode(PacketCodec.encode(packet))
        assertEquals(NodeId(42L), decoded.recipientId)
        assertContentEquals(packet.signature, decoded.signature)
    }

    @Test
    fun `pads to uniform size buckets`() {
        val small = PacketCodec.encode(samplePacket(payload = ByteArray(10)))
        val medium = PacketCodec.encode(samplePacket(payload = ByteArray(200)))
        assertEquals(320, small.size)
        assertEquals(320, medium.size)
        val larger = PacketCodec.encode(samplePacket(payload = ByteArray(400)))
        assertEquals(640, larger.size)
        assertTrue(PacketCodec.encode(samplePacket(payload = ByteArray(5000))).size >= 5000)
    }

    @Test
    fun `rejects malformed input`() {
        assertFailsWith<ProtocolException> { PacketCodec.decode(ByteArray(3)) } // truncated
        val good = PacketCodec.encode(samplePacket())

        val badVersion = good.copyOf().also { it[0] = 9 }
        assertFailsWith<ProtocolException> { PacketCodec.decode(badVersion) }

        val badType = good.copyOf().also { it[1] = 0x7F }
        assertFailsWith<ProtocolException> { PacketCodec.decode(badType) }

        val badTtl = good.copyOf().also { it[2] = 99 }
        assertFailsWith<ProtocolException> { PacketCodec.decode(badTtl) }

        // Declared payload length larger than the actual buffer must be rejected,
        // never read out of bounds (Bridgefy-style parser abuse defense).
        val lyingLength = good.copyOf().also {
            it[20] = 0xFF.toByte()
            it[21] = 0xFF.toByte()
        }
        assertFailsWith<ProtocolException> { PacketCodec.decode(lyingLength) }
    }

    @Test
    fun `message id is stable across ttl mutation but distinct per content`() {
        val packet = samplePacket(ttl = 7)
        val relayed = packet.withTtl(3)
        assertEquals(PacketCodec.messageId(packet), PacketCodec.messageId(relayed))

        val other = samplePacket(payload = "different".toByteArray())
        assertTrue(PacketCodec.messageId(packet) != PacketCodec.messageId(other))
    }

    @Test
    fun `signature survives ttl decrement at relay hops`() {
        val identity = Identity.generate()
        var packet = samplePacket()
        packet = packet.withSignature(identity.sign(PacketCodec.signingBytes(packet)))

        // Simulate two relay hops mutating TTL.
        val afterHops = PacketCodec.decode(PacketCodec.encode(packet)).withTtl(5)
        assertTrue(
            Identity.verify(
                identity.signingPublic.encoded,
                PacketCodec.signingBytes(afterHops),
                afterHops.signature!!,
            ),
        )
    }

    @Test
    fun `gps beacon round trips`() {
        val beacon = GpsBeacon.of(lat = 26.9124, lon = 75.7873, accuracyM = 12, batteryPct = 67)
        val decoded = GpsBeacon.decode(beacon.encode())
        assertEquals(26.9124, decoded.lat, 1e-6)
        assertEquals(75.7873, decoded.lon, 1e-6)
        assertEquals(12, decoded.accuracyM)
        assertEquals(67, decoded.batteryPct)
    }
}
