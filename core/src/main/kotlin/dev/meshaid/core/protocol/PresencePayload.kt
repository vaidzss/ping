package dev.meshaid.core.protocol

import java.nio.ByteBuffer

/**
 * PRESENCE payload v2: `nameLen(u8) | name(utf8) | ed25519Pub(32) | x25519Pub(32)`.
 * Carrying the public keys lets neighbors verify signatures and seal DMs without any
 * server or QR scan. The binding is self-authenticating: a receiver MUST check that
 * the sender's NodeId equals the hash of the announced signing key before trusting it.
 * Keys are optional (name-only presence is legal) — nodes without them just can't be
 * verified or DM'd.
 */
class PresencePayload(
    val name: String,
    val signingPublic: ByteArray? = null,
    val dhPublic: ByteArray? = null,
) {
    init {
        require(name.toByteArray().size in 1..64) { "name must encode to 1..64 bytes" }
        require((signingPublic == null) == (dhPublic == null)) { "announce both keys or neither" }
        signingPublic?.let { require(it.size == 32) }
        dhPublic?.let { require(it.size == 32) }
    }

    val hasKeys: Boolean get() = signingPublic != null

    fun encode(): ByteArray {
        val nameBytes = name.toByteArray()
        val size = 1 + nameBytes.size + if (hasKeys) 64 else 0
        val buf = ByteBuffer.allocate(size)
        buf.put(nameBytes.size.toByte())
        buf.put(nameBytes)
        signingPublic?.let { buf.put(it) }
        dhPublic?.let { buf.put(it) }
        return buf.array()
    }

    companion object {
        fun decode(payload: ByteArray): PresencePayload {
            if (payload.isEmpty()) throw ProtocolException("empty presence")
            val nameLen = payload[0].toInt() and 0xFF
            if (nameLen == 0 || payload.size < 1 + nameLen) throw ProtocolException("presence truncated")
            val name = String(payload, 1, nameLen)
            val rest = payload.size - 1 - nameLen
            return when {
                rest >= 64 -> PresencePayload(
                    name = name,
                    signingPublic = payload.copyOfRange(1 + nameLen, 1 + nameLen + 32),
                    dhPublic = payload.copyOfRange(1 + nameLen + 32, 1 + nameLen + 64),
                )
                else -> PresencePayload(name)
            }
        }
    }
}
