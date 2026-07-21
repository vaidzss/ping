package dev.meshaid.app.ble

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/**
 * GATT writes/notifications carry at most (MTU - 3) bytes, but wire packets are padded to
 * 320+ byte buckets — so frames are fragmented per connection with a tiny 4-byte header:
 * frameId(u16) | chunkIndex(u8) | chunkCount(u8).
 */
object BleFraming {
    const val HEADER_SIZE = 4
    private val nextFrameId = AtomicInteger(1)

    fun fragment(frame: ByteArray, maxWrite: Int): List<ByteArray> {
        val chunkData = (maxWrite - HEADER_SIZE).coerceAtLeast(1)
        val count = (frame.size + chunkData - 1) / chunkData
        require(count <= 255) { "frame too large for BLE framing: ${frame.size} bytes" }
        val frameId = (nextFrameId.getAndIncrement() and 0xFFFF)
        return (0 until count).map { idx ->
            val start = idx * chunkData
            val end = minOf(start + chunkData, frame.size)
            ByteBuffer.allocate(HEADER_SIZE + (end - start))
                .putShort(frameId.toShort())
                .put(idx.toByte())
                .put(count.toByte())
                .put(frame, start, end - start)
                .array()
        }
    }

    /** One per connection. Not thread-safe; call from the transport's single executor. */
    class Reassembler {
        private var frameId = -1
        private var chunks: Array<ByteArray?> = emptyArray()

        /** Returns the completed frame when the last chunk arrives, else null. */
        fun accept(chunk: ByteArray): ByteArray? {
            if (chunk.size <= HEADER_SIZE) return null
            val buf = ByteBuffer.wrap(chunk)
            val id = buf.short.toInt() and 0xFFFF
            val idx = buf.get().toInt() and 0xFF
            val count = buf.get().toInt() and 0xFF
            if (count == 0 || idx >= count) return null
            if (id != frameId || chunks.size != count) {
                frameId = id
                chunks = arrayOfNulls(count)
            }
            chunks[idx] = ByteArray(buf.remaining()).also { buf.get(it) }
            if (chunks.any { it == null }) return null
            val out = ByteArray(chunks.sumOf { it!!.size })
            var offset = 0
            for (c in chunks) {
                c!!.copyInto(out, offset)
                offset += c.size
            }
            frameId = -1
            chunks = emptyArray()
            return out
        }
    }
}
