package dev.meshaid.core.crypto

import dev.meshaid.core.protocol.NodeId
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64

/**
 * Offline contact exchange payload, rendered as a QR code and scanned in person:
 * `meshaid://contact?v=1&name=...&ed=<base64url>&x=<base64url>`
 */
class ContactCard(
    val name: String,
    val signingPublic: ByteArray,
    val dhPublic: ByteArray,
) {
    init {
        require(signingPublic.size == Identity.KEY_SIZE) { "ed25519 key must be 32 bytes" }
        require(dhPublic.size == Identity.KEY_SIZE) { "x25519 key must be 32 bytes" }
        require(name.length in 1..64) { "name must be 1..64 chars" }
    }

    val nodeId: NodeId get() = Identity.nodeIdOf(signingPublic)

    fun toUri(): String {
        val b64 = Base64.getUrlEncoder().withoutPadding()
        val encodedName = URLEncoder.encode(name, Charsets.UTF_8)
        return "$SCHEME://contact?v=1&name=$encodedName&ed=${b64.encodeToString(signingPublic)}&x=${b64.encodeToString(dhPublic)}"
    }

    companion object {
        const val SCHEME = "meshaid"

        fun of(identity: Identity, name: String): ContactCard =
            ContactCard(name, identity.signingPublic.encoded, identity.dhPublic.encoded)

        fun parse(uri: String): ContactCard {
            val prefix = "$SCHEME://contact?"
            require(uri.startsWith(prefix)) { "not a $SCHEME contact uri" }
            val params = uri.removePrefix(prefix).split('&').mapNotNull {
                val i = it.indexOf('=')
                if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
            }.toMap()
            require(params["v"] == "1") { "unsupported contact card version" }
            val name = URLDecoder.decode(params["name"] ?: error("missing name"), Charsets.UTF_8)
            val b64 = Base64.getUrlDecoder()
            return ContactCard(
                name = name,
                signingPublic = b64.decode(params["ed"] ?: error("missing ed key")),
                dhPublic = b64.decode(params["x"] ?: error("missing x key")),
            )
        }
    }
}
