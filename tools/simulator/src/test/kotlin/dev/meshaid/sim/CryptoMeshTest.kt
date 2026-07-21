package dev.meshaid.sim

import dev.meshaid.core.crypto.Identity
import dev.meshaid.core.protocol.NodeId
import dev.meshaid.core.protocol.Packet
import dev.meshaid.core.protocol.PacketCodec
import dev.meshaid.core.protocol.PacketType
import dev.meshaid.core.protocol.PresencePayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CryptoMeshTest {

    @Test
    fun `encrypted DM routes through a relay and only the recipient can read it`() {
        val net = SimNetwork(seed = 20, rangeM = 30.0)
        val idA = Identity.generate()
        val idC = Identity.generate()
        val a = net.addNode(0, 0.0, 0.0, idA)
        val b = net.addNode(2, 25.0, 0.0) // plain relay, no identity
        val c = net.addNode(0, 10.0, 0.0, idC) // starts near A: they exchange keys in person
        net.tick()

        a.meshNode.sendPresence("Alpha")
        c.meshNode.sendPresence("Charlie")
        net.run(5)
        assertTrue(a.meshNode.directory.get(idC.nodeId) != null, "A must learn C's keys")

        // C walks away: now only reachable through B.
        c.x = 50.0
        net.run(3)

        a.meshNode.sendDirectChat(idC.nodeId, "coordinates for the med drop")
        net.run(10)

        val dm = c.received.single { it.direct }
        assertEquals("coordinates for the med drop", String(dm.payload))
        assertTrue(dm.verified, "DM is signed and C knows A's keys")
        assertTrue(
            b.received.none { it.packet.type == PacketType.CHAT },
            "relay B must never see the DM as a delivered message",
        )
    }

    @Test
    fun `multi-hop announce spreads keys beyond direct range`() {
        val net = SimNetwork(seed = 21, rangeM = 30.0)
        val idA = Identity.generate()
        val a = net.addNode(0, 0.0, 0.0, idA)
        net.addNode(2, 25.0, 0.0)
        val c = net.addNode(3, 50.0, 0.0) // two hops from A
        net.tick()

        a.meshNode.sendAnnounce("Alpha")
        net.run(5)

        assertTrue(c.meshNode.directory.get(idA.nodeId) != null, "announce must reach C via relay")
        // C is two hops away: the announce must NOT have registered A as a direct neighbor.
        assertEquals(1, c.meshNode.router.neighborCount(), "C's only direct neighbor is B")
    }

    @Test
    fun `presence with keys not matching the sender id is rejected`() {
        val net = SimNetwork(seed = 22, rangeM = 30.0)
        val attackerKeys = Identity.generate() // real keys, but announced under a fake id
        val m = net.addNode(99, 0.0, 0.0) // node id 99 != hash(attackerKeys)
        val b = net.addNode(2, 25.0, 0.0)
        net.tick()

        val forged = PresencePayload(
            name = "Trusted Rescuer",
            signingPublic = attackerKeys.signingPublicBytes,
            dhPublic = attackerKeys.dhPublicBytes,
        ).encode()
        m.meshNode.send(PacketType.PRESENCE, forged, ttl = 1)
        net.run(5)

        assertNull(b.meshNode.directory.get(NodeId(99)), "spoofed key announcement must be rejected")
    }

    @Test
    fun `forged signature from a known sender is dropped before delivery`() {
        val net = SimNetwork(seed = 23, rangeM = 30.0)
        val idA = Identity.generate()
        val a = net.addNode(0, 0.0, 0.0, idA)
        val b = net.addNode(2, 25.0, 0.0)
        net.tick()

        a.meshNode.sendPresence("Alpha")
        net.run(3)
        assertTrue(b.meshNode.directory.get(idA.nodeId) != null)

        // Attacker forges a packet claiming to be A, with a garbage signature.
        val forged = Packet(
            type = PacketType.CHAT,
            ttl = 3,
            timestampMs = net.nowMs,
            senderId = idA.nodeId,
            payload = "evacuate east NOW (fake)".toByteArray(),
            signature = ByteArray(64) { 0x42 },
        )
        b.transport.deliver(PacketCodec.encode(forged))
        net.run(2)

        assertTrue(
            b.received.none { String(it.payload).contains("fake") },
            "forged-signature packet must be dropped, not delivered",
        )

        // A genuine signed message from A still gets through, marked verified.
        a.meshNode.send(PacketType.CHAT, "real message".toByteArray())
        net.run(3)
        val real = b.received.single { String(it.payload) == "real message" }
        assertTrue(real.verified)
    }
}
