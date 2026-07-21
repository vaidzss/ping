package dev.meshaid.core.transport

import dev.meshaid.core.MeshNode
import dev.meshaid.core.blob.BlobStore
import dev.meshaid.core.dtn.BundleStore
import dev.meshaid.core.media.MimeTag
import dev.meshaid.core.protocol.NodeId
import dev.meshaid.core.protocol.Packet
import dev.meshaid.core.protocol.PacketType
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * End-to-end over real TCP sockets on localhost (discovery disabled for determinism):
 * the exact stack a phone + laptop pair runs on a hotspot.
 */
class LanMeshTransportTest {

    private fun await(timeoutMs: Long = 10_000, what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(25)
        }
        fail("timed out waiting for: $what")
    }

    private fun node(idRaw: Long): Triple<MeshNode, LanMeshTransport, BlobStore> {
        val transport = LanMeshTransport(NodeId(idRaw), enableDiscovery = false)
        val blobs = BlobStore(Files.createTempDirectory("lan-test-$idRaw"))
        val meshNode = MeshNode(
            NodeId(idRaw),
            System::currentTimeMillis,
            transport,
            bundleStore = BundleStore(System::currentTimeMillis),
            blobStore = blobs,
        )
        return Triple(meshNode, transport, blobs)
    }

    @Test
    fun `two nodes exchange chat and a photo over real sockets`() {
        val (nodeA, lanA, _) = node(1)
        val (nodeB, lanB, blobsB) = node(2)

        val receivedAtB = mutableListOf<Packet>()
        val mediaAtB = mutableListOf<String>()
        nodeB.onMessage = { receivedAtB.add(it) }
        nodeB.onMediaOffer = { _, _ -> true }
        nodeB.onMediaReceived = { hash, _, _ -> mediaAtB.add(hash) }

        var peersSeenByA = 0
        val baseConnected = { lanA.peerCount() == 1 && lanB.peerCount() == 1 }

        try {
            nodeA.start()
            nodeB.start()
            lanA.connectTo("127.0.0.1", lanB.port)
            await(what = "TCP link up") { baseConnected() }

            // Chat A -> B
            nodeA.send(PacketType.CHAT, "hello over the LAN lane".toByteArray())
            await(what = "chat delivery") { receivedAtB.any { String(it.payload) == "hello over the LAN lane" } }

            // Photo A -> B (offer -> request -> chunks -> verify)
            val photo = Random(7).nextBytes(120_000)
            val hash = nodeA.offerMedia(photo, MimeTag.JPEG)
            await(what = "photo transfer") { mediaAtB.contains(hash) }
            assertTrue(blobsB.read(hash).contentEquals(photo), "photo must arrive byte-identical")

            assertEquals(1, lanA.peerCount())
            peersSeenByA = lanA.peerCount()
        } finally {
            nodeA.stop()
            nodeB.stop()
        }
        assertEquals(1, peersSeenByA)
    }

    @Test
    fun `disconnect fires peer down and drops the link`() {
        val (nodeA, lanA, _) = node(3)
        val (nodeB, lanB, _) = node(4)
        var downSeen = false
        // MeshNode owns the transport callbacks; observe via router neighbor count instead.
        try {
            nodeA.start()
            nodeB.start()
            lanA.connectTo("127.0.0.1", lanB.port)
            await(what = "link up") { lanA.peerCount() == 1 }
            assertEquals(1, nodeA.router.neighborCount())

            nodeB.stop()
            await(what = "link down") { lanA.peerCount() == 0 }
            downSeen = nodeA.router.neighborCount() == 0
        } finally {
            nodeA.stop()
            nodeB.stop()
        }
        assertTrue(downSeen, "router must drop the neighbor when the socket dies")
    }
}

