package dev.meshaid.core.crypto

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.security.SecureRandom

/**
 * Password-derived encryption for the identity private key at rest — the Briar/Signal-Desktop
 * model: no server account, no dependency on an OS keystore. The password is the only thing
 * standing between the device and the private key, so it never leaves the device and there is
 * nothing for a "forgot password" flow to recover — losing it means generating a new identity.
 *
 * Wire format: salt(16) || ChaCha20-Poly1305(ciphertext || tag(16)), nonce = zero. Safe because
 * a fresh random salt (and therefore a fresh derived key) is generated on every seal.
 */
object PasswordVault {
    private const val SALT_SIZE = 16
    private const val KEY_SIZE = 32
    private const val TAG_BITS = 128

    /** OWASP-recommended floor for PBKDF2-HMAC-SHA256 as of 2023. */
    private const val ITERATIONS = 210_000

    class WrongPasswordException : Exception("incorrect password")

    fun seal(plaintext: ByteArray, password: String, random: SecureRandom = SecureRandom()): ByteArray {
        val salt = ByteArray(SALT_SIZE).also { random.nextBytes(it) }
        val key = deriveKey(password, salt)
        return salt + aead(encrypt = true, key = key, input = plaintext)
    }

    fun open(sealed: ByteArray, password: String): ByteArray {
        if (sealed.size < SALT_SIZE + TAG_BITS / 8) throw WrongPasswordException()
        val salt = sealed.copyOf(SALT_SIZE)
        val ciphertext = sealed.copyOfRange(SALT_SIZE, sealed.size)
        val key = deriveKey(password, salt)
        return try {
            aead(encrypt = false, key = key, input = ciphertext)
        } catch (e: Exception) {
            throw WrongPasswordException()
        }
    }

    private fun deriveKey(password: String, salt: ByteArray): ByteArray {
        val generator = PKCS5S2ParametersGenerator(SHA256Digest())
        generator.init(password.toByteArray(Charsets.UTF_8), salt, ITERATIONS)
        return (generator.generateDerivedParameters(KEY_SIZE * 8) as KeyParameter).key
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
