package dev.meshaid.core.crypto

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.SecureRandom

class CryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Sealed envelope for offline recipients (Noise X pattern trade-off: no forward secrecy,
 * but works when the recipient is unreachable — "sealed mail" a mesh can carry for hours).
 *
 * Wire format: ephemeralX25519Public(32) || ChaCha20-Poly1305(ciphertext || tag(16)).
 * Key = HKDF-SHA256(ecdh(ephemeral, recipient), salt = ephPub || recipientPub, info = "meshaid-seal-v1").
 * Nonce is all-zero: safe because every seal uses a fresh ephemeral key.
 */
object SealedBox {
    private const val KEY_SIZE = 32
    private const val TAG_BITS = 128
    private val INFO = "meshaid-seal-v1".toByteArray()

    fun seal(
        recipientDhPublic: ByteArray,
        plaintext: ByteArray,
        random: SecureRandom = SecureRandom(),
    ): ByteArray {
        require(recipientDhPublic.size == KEY_SIZE) { "recipient key must be 32 bytes" }
        val gen = X25519KeyPairGenerator().apply { init(X25519KeyGenerationParameters(random)) }
        val eph = gen.generateKeyPair()
        val ephPriv = eph.private as X25519PrivateKeyParameters
        val ephPub = (eph.public as X25519PublicKeyParameters).encoded
        val recipientPub = X25519PublicKeyParameters(recipientDhPublic, 0)

        val key = deriveKey(agree(ephPriv, recipientPub), ephPub, recipientDhPublic)
        val ciphertext = aead(encrypt = true, key = key, input = plaintext)
        return ephPub + ciphertext
    }

    fun open(recipientDhPrivate: X25519PrivateKeyParameters, sealed: ByteArray): ByteArray {
        if (sealed.size < KEY_SIZE + TAG_BITS / 8) throw CryptoException("sealed box truncated")
        val ephPub = sealed.copyOf(KEY_SIZE)
        val ciphertext = sealed.copyOfRange(KEY_SIZE, sealed.size)
        val recipientPub = recipientDhPrivate.generatePublicKey().encoded
        val key = deriveKey(agree(recipientDhPrivate, X25519PublicKeyParameters(ephPub, 0)), ephPub, recipientPub)
        return try {
            aead(encrypt = false, key = key, input = ciphertext)
        } catch (e: Exception) {
            throw CryptoException("sealed box authentication failed", e)
        }
    }

    fun open(identity: Identity, sealed: ByteArray): ByteArray = open(identity.dhPrivate, sealed)

    private fun agree(private: X25519PrivateKeyParameters, public: X25519PublicKeyParameters): ByteArray {
        val agreement = X25519Agreement()
        agreement.init(private)
        val shared = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(public, shared, 0)
        return shared
    }

    private fun deriveKey(shared: ByteArray, ephPub: ByteArray, recipientPub: ByteArray): ByteArray {
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(shared, ephPub + recipientPub, INFO))
        val key = ByteArray(KEY_SIZE)
        hkdf.generateBytes(key, 0, KEY_SIZE)
        return key
    }

    private fun aead(encrypt: Boolean, key: ByteArray, input: ByteArray): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(encrypt, AEADParameters(KeyParameter(key), TAG_BITS, ByteArray(12)))
        val out = ByteArray(cipher.getOutputSize(input.size))
        val n = cipher.processBytes(input, 0, input.size, out, 0)
        val total = n + cipher.doFinal(out, n)
        return if (total == out.size) out else out.copyOf(total)
    }
}
