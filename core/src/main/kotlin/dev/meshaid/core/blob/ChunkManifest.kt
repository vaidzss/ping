package dev.meshaid.core.blob

/**
 * Per-chunk hashes for a blob so a transfer can resume across link drops and each
 * chunk is verified independently — a corrupted or forged chunk is rejected on arrival,
 * not discovered after reassembling 50 MB.
 */
class ChunkManifest(
    val blobHashHex: String,
    val totalSize: Int,
    val chunkSize: Int,
    val chunkHashes: List<ByteArray>,
) {
    val chunkCount: Int get() = chunkHashes.size

    fun chunkOf(data: ByteArray, index: Int): ByteArray {
        require(index in 0 until chunkCount) { "chunk index $index out of range" }
        val start = index * chunkSize
        val end = minOf(start + chunkSize, data.size)
        return data.copyOfRange(start, end)
    }

    companion object {
        const val DEFAULT_CHUNK_SIZE = 32 * 1024

        fun of(data: ByteArray, chunkSize: Int = DEFAULT_CHUNK_SIZE): ChunkManifest {
            require(chunkSize > 0)
            val hashes = ArrayList<ByteArray>()
            var offset = 0
            while (offset < data.size) {
                val end = minOf(offset + chunkSize, data.size)
                hashes.add(BlobStore.sha256(data.copyOfRange(offset, end)))
                offset = end
            }
            if (data.isEmpty()) hashes.add(BlobStore.sha256(ByteArray(0)))
            return ChunkManifest(BlobStore.sha256Hex(data), data.size, chunkSize, hashes)
        }
    }
}
