package dev.meshaid.core.crypto

import dev.meshaid.core.protocol.NodeId
import dev.meshaid.core.protocol.PresencePayload

/**
 * Registry of peers' announced public keys, learned from presence beacons.
 *
 * Registration only succeeds when the announced signing key actually hashes to the
 * sender's NodeId — an attacker cannot claim someone else's id with their own keys
 * (they would need a 64-bit truncated-hash preimage; acceptable for v1, revisit for
 * production with full-hash ids in the contact-verification flow).
 */
class PeerDirectory {

    class PeerKeys(
        val name: String,
        val signingPublic: ByteArray,
        val dhPublic: ByteArray,
    )

    private val peers = HashMap<NodeId, PeerKeys>()

    /** Returns true if the announcement was accepted (id/key binding verified). */
    @Synchronized
    fun register(sender: NodeId, presence: PresencePayload): Boolean {
        val signing = presence.signingPublic ?: return false
        val dh = presence.dhPublic ?: return false
        if (Identity.nodeIdOf(signing) != sender) return false // spoofed announcement
        peers[sender] = PeerKeys(presence.name, signing, dh)
        return true
    }

    /**
     * Same id/key binding check as [register], for keys learned out of band (an in-person
     * QR contact exchange, [ContactCard]) rather than from a presence packet heard over the
     * mesh. Stronger trust than presence-only registration: the name came with the scan, not
     * from an unauthenticated broadcast anyone in range could claim.
     */
    @Synchronized
    fun registerVerified(name: String, signingPublic: ByteArray, dhPublic: ByteArray): NodeId? {
        val nodeId = Identity.nodeIdOf(signingPublic)
        peers[nodeId] = PeerKeys(name, signingPublic, dhPublic)
        return nodeId
    }

    @Synchronized
    fun get(id: NodeId): PeerKeys? = peers[id]

    @Synchronized
    fun byName(name: String): Pair<NodeId, PeerKeys>? =
        peers.entries.firstOrNull { it.value.name.equals(name, ignoreCase = true) }
            ?.let { it.key to it.value }

    @Synchronized
    fun knownNames(): Map<NodeId, String> = peers.mapValues { it.value.name }
}
