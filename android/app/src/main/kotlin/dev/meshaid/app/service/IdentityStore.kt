package dev.meshaid.app.service

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.meshaid.core.crypto.Identity
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Loads or creates the device identity. Private key material is wrapped with a
 * non-exportable AES-GCM key held in the Android Keystore, so the raw identity never
 * touches disk in the clear. Migrates any legacy plaintext entry in place.
 */
object IdentityStore {
    private const val PREFS = "meshaid_identity"
    private const val KEY_LEGACY_PLAINTEXT = "private_b64"
    private const val KEY_WRAPPED = "private_wrapped_b64"
    private const val KEY_IV = "private_iv_b64"
    private const val KEYSTORE_ALIAS = "meshaid_identity_wrap"
    private const val GCM_TAG_BITS = 128

    fun loadOrCreate(context: Context): Identity {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        prefs.getString(KEY_WRAPPED, null)?.let { wrapped ->
            prefs.getString(KEY_IV, null)?.let { iv ->
                runCatching {
                    return Identity.importPrivate(
                        unwrap(Base64.decode(wrapped, Base64.NO_WRAP), Base64.decode(iv, Base64.NO_WRAP)),
                    )
                }
            }
        }

        // Legacy plaintext entry from the first spike build: adopt and re-store wrapped.
        prefs.getString(KEY_LEGACY_PLAINTEXT, null)?.let { legacy ->
            runCatching {
                val identity = Identity.importPrivate(Base64.decode(legacy, Base64.NO_WRAP))
                persist(prefs, identity)
                return identity
            }
        }

        val identity = Identity.generate()
        persist(prefs, identity)
        return identity
    }

    private fun persist(prefs: android.content.SharedPreferences, identity: Identity) {
        val (ciphertext, iv) = wrap(identity.exportPrivate())
        prefs.edit()
            .putString(KEY_WRAPPED, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
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

    private fun wrap(plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrapKey())
        return cipher.doFinal(plaintext) to cipher.iv
    }

    private fun unwrap(ciphertext: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }
}
