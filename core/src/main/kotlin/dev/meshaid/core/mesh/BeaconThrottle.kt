package dev.meshaid.core.mesh

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Decides whether a GPS_BEACON is worth sending. Broadcasting on a fixed timer regardless
 * of movement means every relay hop across the mesh re-forwards identical traffic for a
 * phone that hasn't moved — real battery/bandwidth cost paid by every carrying device, not
 * just the sender. A beacon only goes out once the fix has moved past [minMoveMeters], or
 * [heartbeatMs] has elapsed since the last send (so a genuinely still phone still refreshes
 * occasionally rather than going stale on peers).
 */
class BeaconThrottle(
    private val minMoveMeters: Double = 15.0,
    private val heartbeatMs: Long = 180_000L,
) {
    private var lastLat: Double? = null
    private var lastLon: Double? = null
    private var lastSentMs: Long? = null

    fun shouldSend(nowMs: Long, lat: Double, lon: Double): Boolean {
        val previousLat = lastLat
        val previousLon = lastLon
        val previousSent = lastSentMs
        val moved = previousLat == null || previousLon == null ||
            haversineMeters(previousLat, previousLon, lat, lon) >= minMoveMeters
        val heartbeatDue = previousSent == null || nowMs - previousSent >= heartbeatMs
        if (!moved && !heartbeatDue) return false
        lastLat = lat
        lastLon = lon
        lastSentMs = nowMs
        return true
    }
}

private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val earthRadiusM = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
        sin(dLon / 2) * sin(dLon / 2)
    return earthRadiusM * 2 * atan2(sqrt(a), sqrt(1 - a))
}
