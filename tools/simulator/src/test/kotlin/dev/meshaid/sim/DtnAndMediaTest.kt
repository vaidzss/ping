package dev.meshaid.sim

import dev.meshaid.core.blob.BlobStore
import dev.meshaid.core.protocol.PacketType
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DtnAndMediaTest {

    @Test
    fun `partitioned node receives an earlier message via data-mule sync`() {
        val net = SimNetwork(seed = 10, rangeM = 30.0)
        val a = net.addNode(1, 0.0, 0.0)
        val b = net.addNode(2, 25.0, 0.0)
        val c = net.addNode(3, 500.0, 0.0) // far away — offline when the message is sent
        net.tick()

        a.meshNode.send(PacketType.CHAT, "sent while C was unreachable".toByteArray())
        net.run(5)
        assertEquals(0, c.received.size, "C is partitioned and must not have the message yet")
        assertEquals(1, b.bundleStore.size(), "B must carry the message as a bundle")

        // C walks into B's range: contact triggers summary-vector sync and a bundle pull.
        c.x = 50.0
        net.run(10)

        assertEquals(1, c.received.size, "C must receive the carried message after contact")
        assertEquals("sent while C was unreachable", String(c.received[0].payload))
    }

    @Test
    fun `photo transfers across the mesh through a relay and verifies`() {
        val net = SimNetwork(seed = 11, rangeM = 30.0)
        val a = net.addNode(1, 0.0, 0.0)
        val b = net.addNode(2, 25.0, 0.0)
        val c = net.addNode(3, 50.0, 0.0) // out of A's direct range
        net.tick()

        val photo = Random(99).nextBytes(150_000)
        val hash = a.meshNode.offerMedia(photo, dev.meshaid.core.media.MimeTag.JPEG)
        net.run(25)

        assertTrue(c.mediaReceived.contains(hash), "C must fetch the photo through B's relay")
        assertTrue(c.blobStore.has(hash))
        assertTrue(c.blobStore.read(hash).contentEquals(photo), "photo must arrive byte-identical")
        assertTrue(b.mediaReceived.contains(hash), "direct neighbor B fetches too")
        println("photo transfer: ${photo.size} bytes, total transmissions=${net.totalTransmissions}")
    }

    @Test
    fun `corrupted media transfer is rejected by content hash`() {
        val net = SimNetwork(seed = 12, rangeM = 30.0)
        val a = net.addNode(1, 0.0, 0.0)
        val b = net.addNode(2, 25.0, 0.0)
        net.tick()

        // A advertises a blob but its store holds different content than the advertised hash
        // (simulates a malicious or corrupted offerer).
        val real = Random(1).nextBytes(10_000)
        val lie = BlobStore.sha256(Random(2).nextBytes(10_000))
        a.blobStore.put(real)
        val forgedOffer = dev.meshaid.core.media.MediaOffer(
            blobHash = lie,
            totalSize = real.size,
            mimeTag = dev.meshaid.core.media.MimeTag.JPEG,
            chunkSize = dev.meshaid.core.media.MediaCodecs.DEFAULT_CHUNK_SIZE,
        )
        a.meshNode.send(
            PacketType.MEDIA_OFFER,
            dev.meshaid.core.media.MediaCodecs.encodeOffer(forgedOffer),
        )
        net.run(20)

        assertTrue(b.mediaReceived.isEmpty(), "forged transfer must never surface as received media")
    }

    @Test
    fun `presence announces name to direct neighbors only`() {
        val net = SimNetwork(seed = 13, rangeM = 30.0)
        val a = net.addNode(1, 0.0, 0.0)
        val b = net.addNode(2, 25.0, 0.0)
        val c = net.addNode(3, 50.0, 0.0)
        net.tick()

        a.meshNode.sendPresence("Aid Worker A")
        net.run(5)

        assertTrue(b.presences.any { it.second == "Aid Worker A" }, "direct neighbor hears presence")
        assertTrue(c.presences.none { it.second == "Aid Worker A" }, "TTL-1 presence must not be relayed")
    }
}
