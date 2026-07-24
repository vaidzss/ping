package dev.meshaid.core.media

import dev.meshaid.core.MeshNode
import dev.meshaid.core.blob.BlobStore
import dev.meshaid.core.crypto.Identity
import dev.meshaid.core.protocol.NodeId
import dev.meshaid.core.protocol.PacketCodec
import dev.meshaid.core.protocol.PacketType
import dev.meshaid.core.transport.MeshTransport
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exercises the actual resume path (MeshNode.tick() -> re-request missing chunks), not just
 * the MediaCodecs bitmap round trip. Uses a controllable in-memory transport pair rather than
 * SimNetwork's probabilistic loss or a real socket — both make it hard to drop *exactly* one
 * chunk on the *first* attempt only, which is the scenario this feature exists for.
 */
class ResumableTransferTest {

    /** A direct pair link that can drop a chosen set of outbound MEDIA_CHUNK indices once. */
    private class LossyLink : MeshTransport {
        override var onFrame: ((ByteArray) -> Unit)? = null
        override var onPeerConnected: ((NodeId) -> Unit)? = null
        override var onPeerDisconnected: ((NodeId) -> Unit)? = null
        var peer: LossyLink? = null
        val dropChunkIndices = mutableSetOf<Int>()

        override fun start() {}
        override fun stop() {}

        override fun broadcast(frame: ByteArray) {
            val chunkIndex = runCatching { PacketCodec.decode(frame) }.getOrNull()
                ?.takeIf { it.type == PacketType.MEDIA_CHUNK }
                ?.let { MediaCodecs.decodeChunk(it.payload).index }
            if (chunkIndex != null && chunkIndex in dropChunkIndices) return // simulated loss
            peer?.onFrame?.invoke(frame)
        }
    }

    @Test
    fun `a chunk dropped on first attempt is recovered by the tick-driven resume`() {
        var now = 0L
        val clock = { now }

        val offererLink = LossyLink()
        val requesterLink = LossyLink()
        offererLink.peer = requesterLink
        requesterLink.peer = offererLink

        val offerer = MeshNode(
            Identity.generate().nodeId, clock, offererLink,
            blobStore = BlobStore(Files.createTempDirectory("resume-offerer")),
        )
        val requester = MeshNode(
            Identity.generate().nodeId, clock, requesterLink,
            blobStore = BlobStore(Files.createTempDirectory("resume-requester")),
        )
        offerer.start()
        requester.start()

        val received = mutableListOf<String>()
        requester.onMediaOffer = { _, _ -> true }
        requester.onMediaReceived = { hash, _, _ -> received.add(hash) }

        val data = Random(1).nextBytes(20_000) // ~10 chunks at the 2048-byte default
        offererLink.dropChunkIndices.add(3) // one chunk lost on the only attempt so far
        val hash = offerer.offerMedia(data, MimeTag.JPEG)

        assertTrue(received.isEmpty(), "must not report received while chunk 3 is still missing")

        // The link recovers — but nothing retries on its own until the stall timeout fires.
        offererLink.dropChunkIndices.clear()
        now += MeshNode.MEDIA_RESUME_TIMEOUT_MS - 1
        requester.tick()
        assertTrue(received.isEmpty(), "must not resume before the stall timeout elapses")

        now += 2 // now past MEDIA_RESUME_TIMEOUT_MS since the last accepted chunk
        requester.tick() // detects the stall, re-requests only the missing index

        assertTrue(received.contains(hash), "must complete once the missing chunk is resumed")
    }

    @Test
    fun `a transfer with no gaps never triggers a resume request`() {
        var now = 0L
        val clock = { now }

        val offererLink = LossyLink()
        val requesterLink = LossyLink()
        offererLink.peer = requesterLink
        requesterLink.peer = offererLink

        val offerer = MeshNode(
            Identity.generate().nodeId, clock, offererLink,
            blobStore = BlobStore(Files.createTempDirectory("resume-clean-offerer")),
        )
        val requester = MeshNode(
            Identity.generate().nodeId, clock, requesterLink,
            blobStore = BlobStore(Files.createTempDirectory("resume-clean-requester")),
        )
        offerer.start()
        requester.start()

        requester.onMediaOffer = { _, _ -> true }

        // Count every MEDIA_REQUEST the offerer receives — a clean transfer should see exactly one.
        var requestCount = 0
        val offererOnFrame = offererLink.onFrame
        offererLink.onFrame = { frame ->
            runCatching { PacketCodec.decode(frame) }.getOrNull()?.let {
                if (it.type == PacketType.MEDIA_REQUEST) requestCount++
            }
            offererOnFrame?.invoke(frame)
        }

        val received = mutableListOf<String>()
        requester.onMediaReceived = { hash, _, _ -> received.add(hash) }
        val data = Random(2).nextBytes(10_000)
        val hash = offerer.offerMedia(data, MimeTag.JPEG)

        assertTrue(received.contains(hash))
        now += MeshNode.MEDIA_RESUME_TIMEOUT_MS + 10
        requester.tick()
        assertFalse(received.isEmpty())
        assertTrue(requestCount == 1, "a complete transfer must not generate a resume request ($requestCount seen)")
    }
}
