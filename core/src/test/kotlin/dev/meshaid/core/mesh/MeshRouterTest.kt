package dev.meshaid.core.mesh

import dev.meshaid.core.protocol.NodeId
import dev.meshaid.core.protocol.Packet
import dev.meshaid.core.protocol.PacketType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakeClock(var now: Long = 1_000_000L) {
    fun advance(ms: Long) {
        now += ms
    }
}

class MeshRouterTest {

    private val clock = FakeClock()
    private val self = NodeId(1L)
    private val router = MeshRouter(self, { clock.now })

    private var seq = 0L
    private fun incoming(
        sender: NodeId = NodeId(2L),
        ttl: Int = 7,
        recipient: NodeId? = null,
        type: PacketType = PacketType.CHAT,
        payload: ByteArray = "m${seq++}".toByteArray(),
    ) = Packet(
        type = type,
        ttl = ttl,
        timestampMs = clock.now,
        senderId = sender,
        recipientId = recipient,
        payload = payload,
    )

    @Test
    fun `delivers and relays a fresh broadcast`() {
        val result = router.onReceive(incoming(ttl = 7))
        assertTrue(result.deliver)
        assertNotNull(result.relay)
        assertEquals(6, result.relay!!.ttl)
    }

    @Test
    fun `replayed packet is dropped entirely`() {
        val packet = incoming()
        router.onReceive(packet)
        val replay = router.onReceive(packet)
        assertFalse(replay.deliver)
        assertNull(replay.relay)
        assertEquals("duplicate", replay.reason)
    }

    @Test
    fun `ttl 1 delivers but does not relay`() {
        val result = router.onReceive(incoming(ttl = 1))
        assertTrue(result.deliver)
        assertNull(result.relay)
    }

    @Test
    fun `packet addressed to us is terminal`() {
        val result = router.onReceive(incoming(recipient = self))
        assertTrue(result.deliver)
        assertNull(result.relay)
    }

    @Test
    fun `packet addressed to someone else relays without local delivery`() {
        val result = router.onReceive(incoming(recipient = NodeId(99L)))
        assertFalse(result.deliver)
        assertNotNull(result.relay)
    }

    @Test
    fun `dense neighborhood clamps relayed ttl`() {
        repeat(6) { router.neighborUp(NodeId(100L + it)) }
        val result = router.onReceive(incoming(ttl = 7))
        assertEquals(5, result.relay!!.ttl)
    }

    @Test
    fun `own echoed packet is ignored`() {
        val result = router.onReceive(incoming(sender = self))
        assertFalse(result.deliver)
        assertNull(result.relay)
    }

    @Test
    fun `relay rate limit kicks in per origin but SOS is exempt`() {
        val spammer = NodeId(66L)
        var relayed = 0
        repeat(30) {
            if (router.onReceive(incoming(sender = spammer)).relay != null) relayed++
        }
        assertTrue(relayed < 30, "rate limiter never engaged")

        // SOS from the same exhausted origin still relays.
        val sos = router.onReceive(incoming(sender = spammer, type = PacketType.SOS))
        assertNotNull(sos.relay)

        // Budget refills over time.
        clock.advance(10_000)
        assertNotNull(router.onReceive(incoming(sender = spammer)).relay)
    }

    @Test
    fun `oversized control payload is dropped`() {
        val result = router.onReceive(incoming(payload = ByteArray(5000)))
        assertFalse(result.deliver)
        assertNull(result.relay)
    }

    @Test
    fun `dedup cache expires after five minutes`() {
        val cache = DedupCache({ clock.now })
        val packet = incoming()
        val id = dev.meshaid.core.protocol.PacketCodec.messageId(packet)
        assertTrue(cache.checkAndRecord(id))
        assertFalse(cache.checkAndRecord(id))
        clock.advance(5 * 60 * 1000L + 1)
        assertTrue(cache.checkAndRecord(id))
    }

    @Test
    fun `dedup cache evicts past capacity`() {
        val cache = DedupCache({ clock.now }, maxEntries = 10)
        val ids = (0 until 12).map {
            dev.meshaid.core.protocol.PacketCodec.messageId(incoming())
        }
        ids.forEach { cache.checkAndRecord(it) }
        assertEquals(10, cache.size())
        // Oldest two were evicted, so they read as new again.
        assertTrue(cache.checkAndRecord(ids[0]))
    }
}
