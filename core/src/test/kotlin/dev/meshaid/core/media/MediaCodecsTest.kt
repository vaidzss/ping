package dev.meshaid.core.media

import dev.meshaid.core.protocol.MessageId
import dev.meshaid.core.protocol.ProtocolException
import dev.meshaid.core.protocol.SummaryVector
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaCodecsTest {

    @Test
    fun `offer round trips`() {
        val offer = MediaOffer(Random(1).nextBytes(32), 150_000, MimeTag.JPEG, 2048)
        val decoded = MediaCodecs.decodeOffer(MediaCodecs.encodeOffer(offer))
        assertContentEquals(offer.blobHash, decoded.blobHash)
        assertEquals(offer.totalSize, decoded.totalSize)
        assertEquals(offer.mimeTag, decoded.mimeTag)
        assertEquals(offer.chunkSize, decoded.chunkSize)
        assertEquals(74, decoded.chunkCount)
    }

    @Test
    fun `chunk round trips and truncation is rejected`() {
        val hash = Random(2).nextBytes(32)
        val data = Random(3).nextBytes(2048)
        val decoded = MediaCodecs.decodeChunk(MediaCodecs.encodeChunk(hash, 7, data))
        assertContentEquals(hash, decoded.blobHash)
        assertEquals(7, decoded.index)
        assertContentEquals(data, decoded.data)
        assertFailsWith<ProtocolException> { MediaCodecs.decodeChunk(ByteArray(10)) }
        assertFailsWith<ProtocolException> { MediaCodecs.decodeOffer(ByteArray(5)) }
        assertFailsWith<ProtocolException> { MediaCodecs.decodeRequest(ByteArray(3)) }
    }

    @Test
    fun `media request bitmap round trips arbitrary index sets`() {
        val hash = Random(6).nextBytes(32)

        // Every chunk requested (a fresh, non-resumed request).
        val all = MediaCodecs.decodeRequest(MediaCodecs.encodeRequest(hash, 20, 0 until 20))
        assertContentEquals(hash, all.blobHash)
        assertEquals((0 until 20).toList(), all.requestedIndices)

        // A resume request for just the gaps left by a stalled transfer.
        val sparse = MediaCodecs.decodeRequest(MediaCodecs.encodeRequest(hash, 20, listOf(0, 5, 19)))
        assertEquals(listOf(0, 5, 19), sparse.requestedIndices)

        // Nothing missing — a valid, if useless, all-zero bitmap.
        val none = MediaCodecs.decodeRequest(MediaCodecs.encodeRequest(hash, 20, emptyList()))
        assertTrue(none.requestedIndices.isEmpty())

        // Chunk counts that don't land on a byte boundary still round-trip cleanly.
        val odd = MediaCodecs.decodeRequest(MediaCodecs.encodeRequest(hash, 1, listOf(0)))
        assertEquals(listOf(0), odd.requestedIndices)
    }

    @Test
    fun `summary vector round trips and enforces its cap`() {
        val ids = (0 until 200).map { MessageId(Random(it).nextBytes(16)) }
        val decoded = SummaryVector.decode(SummaryVector.encode(ids))
        assertEquals(ids, decoded)
        assertFailsWith<IllegalArgumentException> {
            SummaryVector.encode((0 until 256).map { MessageId(Random(it).nextBytes(16)) })
        }
        assertFailsWith<ProtocolException> { SummaryVector.decode(ByteArray(0)) }
        assertFailsWith<ProtocolException> { SummaryVector.decode(byteArrayOf(5, 0, 0)) }
    }

    @Test
    fun `incoming transfer verifies the full content hash`() {
        val data = Random(4).nextBytes(10_000)
        val offer = MediaOffer(
            blobHash = dev.meshaid.core.blob.BlobStore.sha256(data),
            totalSize = data.size,
            mimeTag = MimeTag.JPEG,
            chunkSize = 2048,
        )
        val transfer = IncomingTransfer(offer)
        var offset = 0
        var index = 0
        while (offset < data.size) {
            val end = minOf(offset + 2048, data.size)
            assertTrue(transfer.accept(index, data.copyOfRange(offset, end)))
            index++
            offset = end
        }
        assertContentEquals(data, transfer.assembleVerified())

        // Same chunks against a lying hash must yield null.
        val forged = IncomingTransfer(
            MediaOffer(Random(5).nextBytes(32), data.size, MimeTag.JPEG, 2048),
        )
        offset = 0; index = 0
        while (offset < data.size) {
            val end = minOf(offset + 2048, data.size)
            forged.accept(index, data.copyOfRange(offset, end))
            index++
            offset = end
        }
        assertNull(forged.assembleVerified())
    }
}
