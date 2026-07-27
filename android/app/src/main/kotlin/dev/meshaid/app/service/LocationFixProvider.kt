package dev.meshaid.app.service

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * Actively requests a fresh fix instead of only reading whatever's cached —
 * `getLastKnownLocation()` alone returns null forever on a phone where no other app has
 * recently asked for GPS, which is exactly what made SOS ship with no coordinates and the
 * mesh map never show a beacon. Resolves with the first fix any enabled provider produces,
 * or the stale cache as a last resort if nothing answers within [timeoutMs].
 */
@SuppressLint("MissingPermission") // only ever constructed once ACCESS_FINE_LOCATION is granted
class LocationFixProvider(context: Context) {
    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Suppress("DEPRECATION") // requestSingleUpdate is the only option below API 30
    fun requestFix(timeoutMs: Long, onResult: (Location?) -> Unit) {
        val providers = runCatching { manager.getProviders(true) }.getOrDefault(emptyList())
        if (providers.isEmpty()) {
            onResult(cachedFallback())
            return
        }

        var settled = false
        val signal = CancellationSignal()
        val legacyListeners = mutableListOf<LocationListener>()

        fun settle(location: Location?) {
            if (settled) return
            settled = true
            signal.cancel()
            legacyListeners.forEach { runCatching { manager.removeUpdates(it) } }
            onResult(location ?: cachedFallback())
        }

        providers.forEach { provider ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching {
                    manager.getCurrentLocation(provider, signal, executor) { location ->
                        mainHandler.post { settle(location) }
                    }
                }
            } else {
                val listener = LocationListener { location -> settle(location) }
                legacyListeners.add(listener)
                runCatching { manager.requestSingleUpdate(provider, listener, Looper.getMainLooper()) }
            }
        }

        mainHandler.postDelayed({ settle(null) }, timeoutMs)
    }

    private fun cachedFallback(): Location? =
        runCatching {
            manager.getProviders(true).asSequence()
                .mapNotNull { manager.getLastKnownLocation(it) }
                .maxByOrNull { it.time }
        }.getOrNull()
}
