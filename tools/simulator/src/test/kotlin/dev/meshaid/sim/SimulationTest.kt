package dev.meshaid.sim

import dev.meshaid.core.protocol.PacketType
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SimulationTest {

    @Test
    fun `three node chain relays beyond direct radio range`() {
        // A(0) - B(25) - C(50), range 30: C can only hear A through B.
        val net = SimNetwork(seed = 1, rangeM = 30.0)
        val a = net.addNode(1, 0.0, 0.0)
        net.addNode(2, 25.0, 0.0)
        val c = net.addNode(3, 50.0, 0.0)
        net.tick() // establish adjacency

        a.meshNode.send(PacketType.CHAT, "help needed at the river".toByteArray())
        net.run(10)

        assertEquals(1, c.received.size, "C must receive A's message via B's relay")
        assertEquals("help needed at the river", String(c.received[0].payload))
    }

    @Test
    fun `seven hop chain delivers at ttl 7 but eighth node is beyond ttl`() {
        // 9 nodes in a line, 25m apart, range 30 → strictly linear relay chain.
        val net = SimNetwork(seed = 2, rangeM = 30.0)
        val nodes = (0..8).map { net.addNode(it + 1L, it * 25.0, 0.0) }
        net.tick()

        nodes[0].meshNode.send(PacketType.CHAT, "chain test".toByteArray())
        net.run(15)

        // TTL 7: origin transmit reaches node1 (hop 1) ... node7 (hop 7). Node 8 is hop 8: unreachable.
        assertEquals(1, nodes[7].received.size, "node at hop 7 must be reached")
        assertEquals(0, nodes[8].received.size, "node at hop 8 must NOT be reached (ttl exhausted)")
    }

    @Test
    fun `dense crowd does not packet storm - FireChat regression`() {
        // 30 nodes all within radio range of each other.
        val net = SimNetwork(seed = 3, rangeM = 100.0)
        val placer = Random(3)
        val nodes = (0 until 30).map { net.addNode(it + 1L, placer.nextDouble(10.0), placer.nextDouble(10.0)) }
        net.tick()

        nodes[0].meshNode.send(PacketType.CHAT, "crowded square".toByteArray())
        net.run(10)

        val delivered = nodes.drop(1).count { it.received.isNotEmpty() }
        assertEquals(29, delivered, "everyone in range must receive the broadcast")
        // Dedup means each node transmits the message at most once: 1 send + ≤29 relays.
        assertTrue(
            net.totalTransmissions <= nodes.size,
            "packet storm: ${net.totalTransmissions} transmissions for ${nodes.size} nodes",
        )
    }

    @Test
    fun `twenty node mesh with churn and lossy links still delivers`() {
        val net = SimNetwork(seed = 4, rangeM = 35.0, lossProb = 0.10)
        val placer = Random(4)
        val nodes = (0 until 20).map {
            net.addNode(it + 1L, placer.nextDouble(80.0), placer.nextDouble(80.0))
        }
        net.tick()

        val mover = Random(5)
        var deliveredTotal = 0
        var possibleTotal = 0
        val messages = 10
        repeat(messages) { m ->
            val sender = nodes[m % nodes.size]
            sender.meshNode.send(PacketType.CHAT, "msg-$m".toByteArray())
            // Nodes drift while the message floods.
            repeat(12) {
                net.moveAll(2.0, mover)
                net.tick()
            }
            val got = nodes.count { n ->
                n !== sender && n.received.any { String(it.payload) == "msg-$m" }
            }
            deliveredTotal += got
            possibleTotal += nodes.size - 1
        }

        val ratio = deliveredTotal.toDouble() / possibleTotal
        println("churn delivery ratio: ${"%.2f".format(ratio)} ($deliveredTotal/$possibleTotal), transmissions=${net.totalTransmissions}")
        assertTrue(ratio >= 0.75, "delivery ratio $ratio below threshold under churn+loss")
    }

    @Test
    fun `replayed frames are never delivered twice`() {
        val net = SimNetwork(seed = 6, rangeM = 30.0)
        val a = net.addNode(1, 0.0, 0.0)
        val b = net.addNode(2, 10.0, 0.0)
        net.tick()

        val packet = a.meshNode.send(PacketType.CHAT, "once only".toByteArray())
        net.run(5)
        assertEquals(1, b.received.size)

        // Adversary replays the exact frame three times.
        val frame = dev.meshaid.core.protocol.PacketCodec.encode(packet)
        repeat(3) { b.transport.deliver(frame) }
        assertEquals(1, b.received.size, "replay must be swallowed by dedup")
    }
}
