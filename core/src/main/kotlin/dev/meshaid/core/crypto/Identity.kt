package dev.meshaid.core.crypto

import dev.meshaid.core.protocol.NodeId
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Device identity: Ed25519 for signing, X25519 for key agreement.
 * Generated once on first run; private halves belong in Keystore/Keychain on device.
 *
 * Bouncy Castle *lightweight* API only (org.bouncycastle.crypto.*): no JCA provider
 * registration, so no clash with Android's bundled conscrypt/BC.
 */
class Identity(
    val signingPrivate: Ed25519PrivateKeyParameters,
    val dhPrivate: X25519PrivateKeyParameters,
) {
    val signingPublic: Ed25519PublicKeyParameters = signingPrivate.generatePublicKey()
    val dhPublic: X25519PublicKeyParameters = dhPrivate.generatePublicKey()

    val nodeId: NodeId = nodeIdOf(signingPublic.encoded)

    fun sign(data: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, signingPrivate)
        signer.update(data, 0, data.size)
        return signer.generateSignature()
    }

    /** Serialized private material (64 bytes: ed25519 seed || x25519 scalar) for secure storage. */
    fun exportPrivate(): ByteArray = signingPrivate.encoded + dhPrivate.encoded

    companion object {
        const val KEY_SIZE = 32

        fun generate(random: SecureRandom = SecureRandom()): Identity {
            val edGen = Ed25519KeyPairGenerator().apply { init(Ed25519KeyGenerationParameters(random)) }
            val xGen = X25519KeyPairGenerator().apply { init(X25519KeyGenerationParameters(random)) }
            return Identity(
                edGen.generateKeyPair().private as Ed25519PrivateKeyParameters,
                xGen.generateKeyPair().private as X25519PrivateKeyParameters,
            )
        }

        fun importPrivate(bytes: ByteArray): Identity {
            require(bytes.size == KEY_SIZE * 2) { "expected 64 bytes of private key material" }
            return Identity(
                Ed25519PrivateKeyParameters(bytes, 0),
                X25519PrivateKeyParameters(bytes, KEY_SIZE),
            )
        }

        fun verify(signingPublicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean {
            if (signingPublicKey.size != KEY_SIZE || signature.size != 64) return false
            return try {
                val verifier = Ed25519Signer()
                verifier.init(false, Ed25519PublicKeyParameters(signingPublicKey, 0))
                verifier.update(data, 0, data.size)
                verifier.verifySignature(signature)
            } catch (_: Exception) {
                false
            }
        }

        fun nodeIdOf(signingPublicKey: ByteArray): NodeId {
            val digest = MessageDigest.getInstance("SHA-256").digest(signingPublicKey)
            return NodeId.fromBytes(digest)
        }
    }
}
