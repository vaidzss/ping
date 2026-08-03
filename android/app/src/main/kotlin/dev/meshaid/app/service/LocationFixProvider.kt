package dev.meshaid.app.service

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper

/**
 * Keeps one long-lived location session running for the app's whole lifetime, rather than
 * issuing a fresh bounded request per caller.
 *
 * The first version of this class did the latter (a one-shot `getCurrentLocation`/
 * `requestSingleUpdate` per call, cancelled at its own timeout) and it was worse than the
 * passive `getLastKnownLocation()`-only approach it replaced: with no Wi-Fi or mobile data —
 * exactly the disaster scenario this app is for — `NETWORK_PROVIDER` can't resolve at all, so
 * the only source left is a raw GPS cold fix, which can easily take 30s+ indoors. A per-call
 * timeout of even 10-12s cancels that acquisition before it ever completes, and the *next*
 * call starts the same cold acquisition over from scratch — it can never finish. A session
 * that's never cancelled lets a slow fix land whenever it lands, and every caller afterward —
 * this beacon cycle, the next one, an SOS three minutes from now — benefits from it.
 */
class LocationFixProvider(context: Context) {
    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var bestFix: Location? = null
    private val activeListeners = mutableListOf<LocationListener>()
    @Volatile private var started = false

    /** Idempotent — call once at service startup so acquisition has as long as possible to land. */
    @SuppressLint("MissingPermission") // only ever constructed once ACCESS_FINE_LOCATION is granted
    @Synchronized
    fun startContinuousUpdates() {
        if (started) return
        val providers = runCatching { manager.getProviders(true) }.getOrDefault(emptyList())
        if (providers.isEmpty()) return
        started = true
        providers.forEach { provider ->
            val listener = LocationListener { location -> bestFix = preferFix(bestFix, location) }
            activeListeners.add(listener)
            runCatching {
                manager.requestLocationUpdates(provider, /* minTimeMs = */ 0L, /* minDistanceM = */ 0f, listener, Looper.getMainLooper())
            }
        }
    }

    @Synchronized
    fun stopContinuousUpdates() {
        activeListeners.forEach { runCatching { manager.removeUpdates(it) } }
        activeListeners.clear()
        started = false
    }

    /** Whatever the continuous session has produced so far, or the OS's own cache — no wait. */
    fun currentFix(): Location? = bestFix ?: cachedFallback()

    /**
     * Polls the continuous session for up to [timeoutMs] before falling back to whatever's
     * available. Never cancels the underlying session — a timeout here just means this
     * particular caller stopped waiting, not that acquisition stopped.
     */
    fun requestFix(timeoutMs: Long, onResult: (Location?) -> Unit) {
        startContinuousUpdates()
        currentFix()?.let {
            onResult(it)
            return
        }
        val deadlineMs = System.currentTimeMillis() + timeoutMs
        pollUntil(deadlineMs, onResult)
    }

    private fun pollUntil(deadlineMs: Long, onResult: (Location?) -> Unit) {
        val fix = bestFix
        if (fix != null || System.currentTimeMillis() >= deadlineMs) {
            onResult(fix ?: cachedFallback())
        } else {
            mainHandler.postDelayed({ pollUntil(deadlineMs, onResult) }, POLL_INTERVAL_MS)
        }
    }

    private fun cachedFallback(): Location? =
        runCatching {
            manager.getProviders(true).asSequence()
                .mapNotNull { manager.getLastKnownLocation(it) }
                .maxByOrNull { it.time }
        }.getOrNull()

    /**
     * Naively keeping "whatever arrived last" means one noisy reading (a real, common GPS
     * artifact — an indoor multipath bounce, a fix taken before enough satellites locked)
     * overwrites a perfectly good one, so the next beacon or SOS uses the worse of the two for
     * no reason. Prefer a materially more accurate fix even if it's older; otherwise take the
     * newer one — and always take the newer one once the current fix is stale enough that
     * "accurate but two minutes old" stops being an improvement over "fresh."
     */
    private fun preferFix(current: Location?, incoming: Location): Location {
        if (current == null) return incoming
        val currentAgeMs = incoming.time - current.time
        if (currentAgeMs > STALE_FIX_MS) return incoming
        return if (incoming.accuracy <= current.accuracy) incoming else current
    }

    companion object {
        private const val POLL_INTERVAL_MS = 1_000L
        private const val STALE_FIX_MS = 2 * 60 * 1000L
    }
}
