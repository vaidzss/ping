package dev.meshaid.core.media

import dev.meshaid.core.blob.BlobStore

/**
 * Reassembles a mesh media transfer announced by a MediaOffer. Chunks arrive unverified
 * (the offer is too small to carry per-chunk hashes on the control lane); integrity is
 * enforced by verifying the full content hash before the blob is accepted.
 */
class IncomingTransfer(val offer: MediaOffer) {
    private val chunks = arrayOfNulls<ByteArray>(offer.chunkCount)

    @Synchronized
    fun accept(index: Int, data: ByteArray): Boolean {
        if (index !in 0 until offer.chunkCount) return false
        if (chunks[index] != null) return false
        chunks[index] = data
        return true
    }

    @Synchronized
    fun missing(): List<Int> = chunks.indices.filter { chunks[it] == null }

    @Synchronized
    fun isComplete(): Boolean = chunks.all { it != null }

    /** Returns the verified blob, or null if the content hash does not match the offer. */
    @Synchronized
    fun assembleVerified(): ByteArray? {
        if (!isComplete()) return null
        val out = ByteArray(chunks.sumOf { it!!.size })
        var offset = 0
        for (c in chunks) {
            c!!.copyInto(out, offset)
            offset += c.size
        }
        if (out.size != offer.totalSize) return null
        if (!BlobStore.sha256(out).contentEquals(offer.blobHash)) return null
        return out
    }
}
