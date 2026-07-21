package dev.meshaid.core.blob

import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BlobTest {

    private val dir = Files.createTempDirectory("meshaid-blob-test")
    private val store = BlobStore(dir)

    @Test
    fun `put then read returns identical bytes and dedups`() {
        val data = Random(7).nextBytes(100_000)
        val ref = store.put(data)
        assertTrue(store.has(ref.hashHex))
        assertContentEquals(data, store.read(ref.hashHex))
        // Same content → same address, no duplicate stored.
        assertEquals(ref.hashHex, store.put(data).hashHex)
    }

    @Test
    fun `chunked transfer round trips out of order`() {
        val data = Random(42).nextBytes(200_000) // ~7 chunks at 32 KiB
        val manifest = ChunkManifest.of(data)
        val rx = Reassembler(manifest)

        val order = (0 until manifest.chunkCount).shuffled(Random(1))
        for (i in order) {
            assertEquals(Reassembler.Offer.ACCEPTED, rx.offer(i, manifest.chunkOf(data, i)))
        }
        assertTrue(rx.isComplete())
        assertContentEquals(data, rx.assemble())
    }

    @Test
    fun `resume after link drop only needs missing chunks`() {
        val data = Random(3).nextBytes(150_000)
        val manifest = ChunkManifest.of(data)
        val rx = Reassembler(manifest)

        // Link drops after two chunks.
        rx.offer(0, manifest.chunkOf(data, 0))
        rx.offer(1, manifest.chunkOf(data, 1))
        val missing = rx.missing()
        assertEquals(manifest.chunkCount - 2, missing.size)

        // Peer reconnects and sends only what's missing.
        missing.forEach { rx.offer(it, manifest.chunkOf(data, it)) }
        assertContentEquals(data, rx.assemble())
    }

    @Test
    fun `corrupted chunk is rejected on arrival`() {
        val data = Random(9).nextBytes(100_000)
        val manifest = ChunkManifest.of(data)
        val rx = Reassembler(manifest)

        val evil = manifest.chunkOf(data, 0).also { it[5] = (it[5].toInt() xor 0xFF).toByte() }
        assertEquals(Reassembler.Offer.REJECTED_HASH, rx.offer(0, evil))
        // The honest chunk still goes through afterwards.
        assertEquals(Reassembler.Offer.ACCEPTED, rx.offer(0, manifest.chunkOf(data, 0)))
    }

    @Test
    fun `duplicate and out-of-range chunks are flagged`() {
        val data = Random(11).nextBytes(50_000)
        val manifest = ChunkManifest.of(data)
        val rx = Reassembler(manifest)
        rx.offer(0, manifest.chunkOf(data, 0))
        assertEquals(Reassembler.Offer.DUPLICATE, rx.offer(0, manifest.chunkOf(data, 0)))
        assertEquals(Reassembler.Offer.INVALID_INDEX, rx.offer(999, ByteArray(1)))
    }

    @Test
    fun `assemble refuses an incomplete blob`() {
        val data = Random(5).nextBytes(80_000)
        val rx = Reassembler(ChunkManifest.of(data))
        assertFailsWith<IllegalStateException> { rx.assemble() }
    }

    @Test
    fun `invalid hash is rejected by the store`() {
        assertFailsWith<IllegalArgumentException> { store.read("not-a-hash") }
        assertFailsWith<IllegalArgumentException> { store.read("..\\..\\etc\\passwd") }
    }
}
