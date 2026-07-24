package dev.meshaid.app.service

import android.content.Context

/**
 * The privacy boundary for the Roster: anyone nearby can broadcast presence and a name
 * (that's how the mesh works), but only NodeIds explicitly added here are ever shown as a
 * contact or get a chat thread. Everyone else stays in "nearby" until added — never
 * silently surfaced with their live location to a stranger who hasn't chosen to add them.
 *
 * Trust-on-first-use: adding a friend trusts the name they were broadcasting at the time.
 * The underlying key binding (signing key hashes to their NodeId) is still enforced by
 * `PeerDirectory` regardless — this only controls what the *UI* surfaces, not the mesh's
 * anti-spoofing guarantees.
 */
object FriendStore {
    private const val PREFS = "meshaid_friends"

    fun load(context: Context): Map<String, String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.all.mapNotNull { (id, name) -> (name as? String)?.let { id to it } }.toMap()
    }

    fun add(context: Context, id: String, name: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(id, name).apply()
    }

    fun remove(context: Context, id: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(id).apply()
    }
}
