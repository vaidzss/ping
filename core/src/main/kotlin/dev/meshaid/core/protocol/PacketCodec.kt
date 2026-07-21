package dev.meshaid.core.protocol

import java.nio.ByteBuffer
import java.security.MessageDigest

class ProtocolException(message: String) : Exception(message)

/**
 * Binary wire codec for the control lane. See protocol/SPEC.md.
 *
 * Encoded packets are padded with zeros to fixed size buckets so that packet length leaks
 * as little as possible about content (traffic-analysis resistance).
 */
object PacketCodec {
    const val PROTOCOL_VERSION = 1
    const val MAX_TTL = 7
    const val MAX_PAYLOAD = 65535
    const val SIGNATURE_SIZE = 64
    val SIZE_BUCKETS = intArrayOf(320, 640, 1280, 4096)

    private const val FIXED_HEADER = 1 + 1 + 1 + 1 + 8 + 8 // version..flags + timestamp + sender
    private const val PAYLOAD_LEN_FIELD = 2

    fun encode(packet: Packet): ByteArray {
        val exact = exactSize(packet)
        val padded = SIZE_BUCKETS.firstOrNull { it >= exact } ?: exact
        val buf = ByteBuffer.allocate(padded)
        writeBody(buf, packet, packet.ttl)
        packet.signature?.let { buf.put(it) }
        // Remaining bytes stay zero: that's the padding.
        return buf.array()
    }

    fun decode(bytes: ByteArray): Packet {
        if (bytes.size < FIXED_HEADER + PAYLOAD_LEN_FIELD) throw ProtocolException("packet truncated: ${bytes.size} bytes")
        val buf = ByteBuffer.wrap(bytes)
        val version = buf.get().toInt() and 0xFF
        if (version != PROTOCOL_VERSION) throw ProtocolException("unsupported version $version")
        val typeWire = buf.get().toInt() and 0xFF
        val type = PacketType.fromWire(typeWire) ?: throw ProtocolException("unknown packet type 0x%02x".format(typeWire))
        val ttl = buf.get().toInt() and 0xFF
        if (ttl > MAX_TTL) throw ProtocolException("ttl out of range: $ttl")
        val flags = buf.get().toInt() and 0xFF
        val timestampMs = buf.long
        val senderId = NodeId(buf.long)
        val recipientId = if (flags and PacketFlags.HAS_RECIPIENT != 0) {
            if (buf.remaining() < 8 + PAYLOAD_LEN_FIELD) throw ProtocolException("truncated before recipient")
            NodeId(buf.long)
        } else null
        if (buf.remaining() < PAYLOAD_LEN_FIELD) throw ProtocolException("truncated before payload length")
        val payloadLen = buf.short.toInt() and 0xFFFF
        val signed = flags and PacketFlags.SIGNED != 0
        val needed = payloadLen + if (signed) SIGNATURE_SIZE else 0
        if (buf.remaining() < needed) {
            throw ProtocolException("declared payload $payloadLen exceeds packet (${buf.remaining()} remaining)")
        }
        val payload = ByteArray(payloadLen).also { buf.get(it) }
        val signature = if (signed) ByteArray(SIGNATURE_SIZE).also { buf.get(it) } else null
        return Packet(
            version = version,
            type = type,
            ttl = ttl,
            timestampMs = timestampMs,
            senderId = senderId,
            recipientId = recipientId,
            payload = payload,
            signature = signature,
            encrypted = flags and PacketFlags.ENCRYPTED != 0,
        )
    }

    /**
     * Bytes covered by the Ed25519 signature: the packet body with TTL zeroed, because TTL
     * mutates at every relay hop and must not invalidate the signature.
     */
    fun signingBytes(packet: Packet): ByteArray {
        // Sign the unsigned form: the SIGNED flag and the signature itself are excluded,
        // and TTL is zeroed, so relay hops don't invalidate the signature.
        val unsigned = packet.copyWithoutSignature()
        val buf = ByteBuffer.allocate(exactSize(unsigned))
        writeBody(buf, unsigned, ttl = 0)
        return buf.array().copyOf(buf.position())
    }

    /** Dedup key. TTL and signature excluded so relayed copies map to the same id. */
    fun messageId(packet: Packet): MessageId {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(packet.senderId.toBytes())
        digest.update(ByteBuffer.allocate(8).putLong(packet.timestampMs).array())
        digest.update(packet.type.wire.toByte())
        digest.update(packet.payload)
        return MessageId(digest.digest().copyOf(16))
    }

    private fun exactSize(packet: Packet): Int =
        FIXED_HEADER +
            (if (packet.recipientId != null) 8 else 0) +
            PAYLOAD_LEN_FIELD +
            packet.payload.size +
            (if (packet.signature != null) SIGNATURE_SIZE else 0)

    private fun writeBody(buf: ByteBuffer, packet: Packet, ttl: Int) {
        var flags = 0
        if (packet.recipientId != null) flags = flags or PacketFlags.HAS_RECIPIENT
        if (packet.signature != null) flags = flags or PacketFlags.SIGNED
        if (packet.encrypted) flags = flags or PacketFlags.ENCRYPTED
        buf.put(packet.version.toByte())
        buf.put(packet.type.wire.toByte())
        buf.put(ttl.toByte())
        buf.put(flags.toByte())
        buf.putLong(packet.timestampMs)
        buf.putLong(packet.senderId.raw)
        packet.recipientId?.let { buf.putLong(it.raw) }
        buf.putShort(packet.payload.size.toShort())
        buf.put(packet.payload)
    }

    private fun Packet.copyWithoutSignature(): Packet =
        if (signature == null) this
        else Packet(version, type, ttl, timestampMs, senderId, recipientId, payload, null, encrypted)
}
