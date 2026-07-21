package dev.meshaid.app.service

import android.content.Context
import android.util.Base64
import dev.meshaid.core.crypto.Identity

/**
 * Loads or creates the device identity.
 *
 * TODO(security, pre-release): wrap the private material with an Android Keystore key
 * (EncryptedFile / Keystore AES-GCM). Plain SharedPreferences is acceptable only for the
 * Phase 0/1 spike.
 */
object IdentityStore {
    private const val PREFS = "meshaid_identity"
    private const val KEY_PRIVATE = "private_b64"

    fun loadOrCreate(context: Context): Identity {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_PRIVATE, null)
        if (existing != null) {
            runCatching { return Identity.importPrivate(Base64.decode(existing, Base64.NO_WRAP)) }
        }
        val identity = Identity.generate()
        prefs.edit()
            .putString(KEY_PRIVATE, Base64.encodeToString(identity.exportPrivate(), Base64.NO_WRAP))
            .apply()
        return identity
    }
}
