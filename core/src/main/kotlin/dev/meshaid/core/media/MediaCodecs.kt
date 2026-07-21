package dev.meshaid.core.media

import dev.meshaid.core.protocol.ProtocolException
import java.nio.ByteBuffer

object MimeTag {
    const val OCTET = 0
    const val JPEG = 1
    const val PNG = 2
    const val MP4 = 3
}

/** MEDIA_OFFER payload: blobHash(32) | totalSize(u32) | mimeTag(u8) | chunkSize(u16). */
class MediaOffer(
    val blobHash: ByteArray,
    val totalSize: Int,
    val mimeTag: Int,
    val chunkSize: Int,
) {
    init {
        require(blobHash.size == 32)
        require(totalSize >= 0)
        require(chunkSize > 0)
    }

    val hashHex: String get() = blobHash.joinToString("") { "%02x".format(it) }
    val chunkCount: Int get() = if (totalSize == 0) 1 else (totalSize + chunkSize - 1) / chunkSize
}

object MediaCodecs {
    /** Sized so a chunk packet stays under 255 BLE fragments even at the default 23-byte MTU. */
    const val DEFAULT_CHUNK_SIZE = 2048
    private const val OFFER_SIZE = 32 + 4 + 1 + 2

    fun encodeOffer(offer: MediaOffer): ByteArray =
        ByteBuffer.allocate(OFFER_SIZE)
            .put(offer.blobHash)
            .putInt(offer.totalSize)
            .put(offer.mimeTag.toByte())
            .putShort(offer.chunkSize.toShort())
            .array()

    fun decodeOffer(payload: ByteArray): MediaOffer {
        if (payload.size < OFFER_SIZE) throw ProtocolException("media offer truncated")
        val buf = ByteBuffer.wrap(payload)
        val hash = ByteArray(32).also { buf.get(it) }
        return MediaOffer(
            blobHash = hash,
            totalSize = buf.int,
            mimeTag = buf.get().toInt() and 0xFF,
            chunkSize = buf.short.toInt() and 0xFFFF,
        )
    }

    /** MEDIA_REQUEST payload: blobHash(32). */
    fun encodeRequest(blobHash: ByteArray): ByteArray = blobHash.copyOf()

    fun decodeRequest(payload: ByteArray): ByteArray {
        if (payload.size < 32) throw ProtocolException("media request truncated")
        return payload.copyOf(32)
    }

    /** MEDIA_CHUNK payload: blobHash(32) | index(u32) | data. */
    fun encodeChunk(blobHash: ByteArray, index: Int, data: ByteArray): ByteArray =
        ByteBuffer.allocate(36 + data.size)
            .put(blobHash)
            .putInt(index)
            .put(data)
            .array()

    class Chunk(val blobHash: ByteArray, val index: Int, val data: ByteArray)

    fun decodeChunk(payload: ByteArray): Chunk {
        if (payload.size < 36) throw ProtocolException("media chunk truncated")
        val buf = ByteBuffer.wrap(payload)
        val hash = ByteArray(32).also { buf.get(it) }
        val index = buf.int
        val data = ByteArray(buf.remaining()).also { buf.get(it) }
        return Chunk(hash, index, data)
    }
}
