package dev.meshaid.app.service

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.meshaid.core.crypto.Identity
import dev.meshaid.core.crypto.PasswordVault
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Account storage: a username plus a password-sealed identity private key
 * ([PasswordVault] — PBKDF2-SHA256 + ChaCha20-Poly1305), matching the Briar/Signal-Desktop
 * model. There is no server and no password recovery — the password is the only thing
 * standing between the device and the key, by design.
 */
object IdentityStore {
    private const val PREFS = "meshaid_identity"
    private const val KEY_USERNAME = "username"
    private const val KEY_SEALED = "identity_sealed_b64"

    // Legacy (pre-login) storage: Keystore-wrapped or plaintext, no password at all.
    // Adopted into the new password-sealed record the first time someone signs up on a
    // device that already has one, so an in-progress test identity isn't orphaned.
    private const val KEY_LEGACY_WRAPPED = "private_wrapped_b64"
    private const val KEY_LEGACY_IV = "private_iv_b64"
    private const val KEY_LEGACY_PLAINTEXT = "private_b64"
    private const val KEYSTORE_ALIAS = "meshaid_identity_wrap"
    private const val GCM_TAG_BITS = 128

    fun hasAccount(context: Context): Boolean {
        val prefs = prefs(context)
        return prefs.contains(KEY_SEALED) && prefs.contains(KEY_USERNAME)
    }

    fun username(context: Context): String? = prefs(context).getString(KEY_USERNAME, null)

    /** Creates the on-device account. Fails if one already exists — call [login] instead. */
    fun signUp(context: Context, username: String, password: String): Identity {
        val name = username.trim()
        require(name.isNotEmpty()) { "Choose a callsign." }
        require(password.length >= 8) { "Password must be at least 8 characters." }
        val prefs = prefs(context)
        check(!hasAccount(context)) { "An account already exists on this device." }

        val identity = recoverLegacyIdentity(prefs) ?: Identity.generate()
        persist(prefs, identity, name, password)
        clearLegacy(prefs)
        return identity
    }

    /** Unlocks the existing account. Throws [PasswordVault.WrongPasswordException] on a bad password. */
    fun login(context: Context, password: String): Identity {
        val prefs = prefs(context)
        val sealedB64 = prefs.getString(KEY_SEALED, null)
            ?: error("No account on this device yet.")
        val sealed = Base64.decode(sealedB64, Base64.NO_WRAP)
        return Identity.importPrivate(PasswordVault.open(sealed, password))
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun persist(prefs: SharedPreferences, identity: Identity, username: String, password: String) {
        val sealed = PasswordVault.seal(identity.exportPrivate(), password)
        prefs.edit()
            .putString(KEY_USERNAME, username)
            .putString(KEY_SEALED, Base64.encodeToString(sealed, Base64.NO_WRAP))
            .apply()
    }

    private fun recoverLegacyIdentity(prefs: SharedPreferences): Identity? {
        prefs.getString(KEY_LEGACY_WRAPPED, null)?.let { wrapped ->
            prefs.getString(KEY_LEGACY_IV, null)?.let { iv ->
                runCatching {
                    return Identity.importPrivate(
                        unwrap(Base64.decode(wrapped, Base64.NO_WRAP), Base64.decode(iv, Base64.NO_WRAP)),
                    )
                }
            }
        }
        prefs.getString(KEY_LEGACY_PLAINTEXT, null)?.let { legacy ->
            runCatching { return Identity.importPrivate(Base64.decode(legacy, Base64.NO_WRAP)) }
        }
        return null
    }

    private fun clearLegacy(prefs: SharedPreferences) {
        prefs.edit()
            .remove(KEY_LEGACY_WRAPPED)
            .remove(KEY_LEGACY_IV)
            .remove(KEY_LEGACY_PLAINTEXT)
            .apply()
    }

    private fun wrapKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private fun unwrap(ciphertext: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }
}
