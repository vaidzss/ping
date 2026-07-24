package dev.meshaid.core.crypto

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.security.SecureRandom

/**
 * At-rest encryption for local storage that gets sealed constantly — chat history, one seal
 * per message; media, one seal per file. Unlike [PasswordVault] (one seal at signup/login) or
 * [SealedBox] (one seal per DM under a fresh ephemeral key), this key is reused across many
 * seals, so — unlike those two — a zero nonce is not safe here; every seal draws a fresh
 * random 12-byte nonce and stores it alongside the ciphertext.
 *
 * The key is derived from the unlocked identity's private key material via HKDF rather than
 * from the password directly: deriving from an already-in-memory [Identity] avoids paying
 * [PasswordVault]'s ~210k PBKDF2 iterations on every message, while still tying storage
 * encryption to "the user has unlocked their identity this session" — the same trust boundary
 * PasswordVault already establishes for the identity key itself.
 */
object StorageVault {
    private const val KEY_SIZE = 32
    private const val NONCE_SIZE = 12
    private const val TAG_BITS = 128
    private val INFO = "ping-storage-v1".toByteArray()

    fun deriveKey(identity: Identity): ByteArray {
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(identity.exportPrivate(), null, INFO))
        val key = ByteArray(KEY_SIZE)
        hkdf.generateBytes(key, 0, KEY_SIZE)
        return key
    }

    fun seal(plaintext: ByteArray, key: ByteArray, random: SecureRandom = SecureRandom()): ByteArray {
        val nonce = ByteArray(NONCE_SIZE).also { random.nextBytes(it) }
        return nonce + aead(encrypt = true, key = key, nonce = nonce, input = plaintext)
    }

    fun open(sealed: ByteArray, key: ByteArray): ByteArray {
        require(sealed.size >= NONCE_SIZE + TAG_BITS / 8) { "sealed data truncated" }
        val nonce = sealed.copyOf(NONCE_SIZE)
        val ciphertext = sealed.copyOfRange(NONCE_SIZE, sealed.size)
        return try {
            aead(encrypt = false, key = key, nonce = nonce, input = ciphertext)
        } catch (e: Exception) {
            throw CryptoException("storage vault authentication failed", e)
        }
    }

    private fun aead(encrypt: Boolean, key: ByteArray, nonce: ByteArray, input: ByteArray): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(encrypt, AEADParameters(KeyParameter(key), TAG_BITS, nonce))
        val out = ByteArray(cipher.getOutputSize(input.size))
        val n = cipher.processBytes(input, 0, input.size, out, 0)
        val total = n + cipher.doFinal(out, n)
        return if (total == out.size) out else out.copyOf(total)
    }
}
