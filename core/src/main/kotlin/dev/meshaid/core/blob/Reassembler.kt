package dev.meshaid.core.blob

/**
 * Receives chunks in any order over an unreliable link, verifies each against the
 * manifest, tracks what's missing (resume), and only assembles a blob whose full
 * hash matches its content address.
 */
class Reassembler(val manifest: ChunkManifest) {

    enum class Offer { ACCEPTED, DUPLICATE, REJECTED_HASH, INVALID_INDEX }

    private val received = arrayOfNulls<ByteArray>(manifest.chunkCount)

    @Synchronized
    fun offer(index: Int, chunk: ByteArray): Offer {
        if (index !in 0 until manifest.chunkCount) return Offer.INVALID_INDEX
        if (received[index] != null) return Offer.DUPLICATE
        if (!BlobStore.sha256(chunk).contentEquals(manifest.chunkHashes[index])) return Offer.REJECTED_HASH
        received[index] = chunk
        return Offer.ACCEPTED
    }

    @Synchronized
    fun missing(): List<Int> = received.indices.filter { received[it] == null }

    @Synchronized
    fun isComplete(): Boolean = received.all { it != null }

    /** Assemble and verify the whole blob against its content address. */
    @Synchronized
    fun assemble(): ByteArray {
        check(isComplete()) { "missing ${missing().size} chunks" }
        val out = ByteArray(manifest.totalSize)
        var offset = 0
        for (chunk in received) {
            chunk!!.copyInto(out, offset)
            offset += chunk.size
        }
        check(offset == manifest.totalSize) { "assembled size $offset != ${manifest.totalSize}" }
        val hash = BlobStore.sha256Hex(out)
        check(hash == manifest.blobHashHex) { "blob hash mismatch after reassembly" }
        return out
    }
}
