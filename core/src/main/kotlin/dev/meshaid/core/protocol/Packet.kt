package dev.meshaid.core.protocol

import java.nio.ByteBuffer

/**
 * 8-byte node identifier: the first 8 bytes of SHA-256 of the node's Ed25519 public key.
 */
@JvmInline
value class NodeId(val raw: Long) {
    fun toBytes(): ByteArray = ByteBuffer.allocate(8).putLong(raw).array()

    override fun toString(): String = "%016x".format(raw)

    companion object {
        fun fromBytes(bytes: ByteArray, offset: Int = 0): NodeId {
            require(bytes.size - offset >= 8) { "need 8 bytes for NodeId" }
            return NodeId(ByteBuffer.wrap(bytes, offset, 8).long)
        }

        /** Inverse of [toString] — round-trips a NodeId through UI state/persistence as hex. */
        fun parse(hex: String): NodeId = NodeId(java.lang.Long.parseUnsignedLong(hex, 16))
    }
}

/**
 * 16-byte dedup key: first 16 bytes of SHA-256(senderId || timestampMs || type || payload).
 */
class MessageId(val bytes: ByteArray) {
    init {
        require(bytes.size == 16) { "MessageId must be 16 bytes, got ${bytes.size}" }
    }

    override fun equals(other: Any?): Boolean = other is MessageId && bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = bytes.contentHashCode()
    override fun toString(): String = bytes.joinToString("") { "%02x".format(it) }
}

enum class PacketType(val wire: Int) {
    CHAT(0x01),
    GPS_BEACON(0x02),
    SOS(0x03),
    PRESENCE(0x04),
    ACK(0x05),
    MEDIA_OFFER(0x10),
    MEDIA_REQUEST(0x11),
    MEDIA_CHUNK(0x12),
    SUMMARY_VECTOR(0x20),
    BUNDLE_PULL(0x21),
    HANDSHAKE(0x30);

    companion object {
        private val byWire = entries.associateBy { it.wire }
        fun fromWire(value: Int): PacketType? = byWire[value]
    }
}

object PacketFlags {
    const val HAS_RECIPIENT = 0x01
    const val SIGNED = 0x02
    const val ENCRYPTED = 0x04
}

class Packet(
    val version: Int = PacketCodec.PROTOCOL_VERSION,
    val type: PacketType,
    val ttl: Int,
    val timestampMs: Long,
    val senderId: NodeId,
    val recipientId: NodeId? = null,
    val payload: ByteArray,
    val signature: ByteArray? = null,
    val encrypted: Boolean = false,
) {
    init {
        require(ttl in 0..PacketCodec.MAX_TTL) { "ttl out of range: $ttl" }
        require(payload.size <= PacketCodec.MAX_PAYLOAD) { "payload too large: ${payload.size}" }
        signature?.let { require(it.size == PacketCodec.SIGNATURE_SIZE) { "signature must be 64 bytes" } }
    }

    val isBroadcast: Boolean get() = recipientId == null

    fun withTtl(newTtl: Int): Packet =
        Packet(version, type, newTtl, timestampMs, senderId, recipientId, payload, signature, encrypted)

    fun withSignature(sig: ByteArray): Packet =
        Packet(version, type, ttl, timestampMs, senderId, recipientId, payload, sig, encrypted)
}
