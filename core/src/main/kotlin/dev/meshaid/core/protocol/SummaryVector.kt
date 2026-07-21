package dev.meshaid.core.protocol

import java.nio.ByteBuffer

/**
 * Payload codec for SUMMARY_VECTOR and BUNDLE_PULL: count(u8) followed by 16-byte
 * message ids. Capped at 255 ids per packet (4096-byte control-lane cap); callers
 * chunk larger vectors across packets.
 */
object SummaryVector {
    const val MAX_IDS = 255

    fun encode(ids: List<MessageId>): ByteArray {
        require(ids.size <= MAX_IDS) { "chunk summary vectors at $MAX_IDS ids" }
        val buf = ByteBuffer.allocate(1 + ids.size * 16)
        buf.put(ids.size.toByte())
        ids.forEach { buf.put(it.bytes) }
        return buf.array()
    }

    fun decode(payload: ByteArray): List<MessageId> {
        if (payload.isEmpty()) throw ProtocolException("empty summary vector")
        val count = payload[0].toInt() and 0xFF
        if (payload.size < 1 + count * 16) throw ProtocolException("summary vector truncated")
        return (0 until count).map {
            MessageId(payload.copyOfRange(1 + it * 16, 1 + (it + 1) * 16))
        }
    }
}
