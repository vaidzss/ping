package dev.meshaid.core.mesh

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BeaconThrottleTest {

    // Jaipur, roughly — real coordinates so the haversine math is exercised meaningfully.
    private val lat = 26.9124
    private val lon = 75.7873

    @Test
    fun `first fix is always sent`() {
        val throttle = BeaconThrottle()
        assertTrue(throttle.shouldSend(0L, lat, lon))
    }

    @Test
    fun `an unchanged fix is suppressed before the heartbeat`() {
        val throttle = BeaconThrottle(minMoveMeters = 15.0, heartbeatMs = 180_000L)
        assertTrue(throttle.shouldSend(0L, lat, lon))
        assertFalse(throttle.shouldSend(1_000L, lat, lon))
        assertFalse(throttle.shouldSend(60_000L, lat, lon))
    }

    @Test
    fun `an unchanged fix is resent once the heartbeat elapses`() {
        val throttle = BeaconThrottle(minMoveMeters = 15.0, heartbeatMs = 180_000L)
        assertTrue(throttle.shouldSend(0L, lat, lon))
        assertFalse(throttle.shouldSend(179_999L, lat, lon))
        assertTrue(throttle.shouldSend(180_000L, lat, lon))
    }

    @Test
    fun `a real move is sent immediately regardless of the heartbeat`() {
        val throttle = BeaconThrottle(minMoveMeters = 15.0, heartbeatMs = 180_000L)
        assertTrue(throttle.shouldSend(0L, lat, lon))
        // ~0.001 degrees of latitude is roughly 111m — comfortably past the 15m gate.
        assertTrue(throttle.shouldSend(1_000L, lat + 0.001, lon))
    }

    @Test
    fun `a tiny GPS-noise jitter below the move threshold is suppressed`() {
        val throttle = BeaconThrottle(minMoveMeters = 15.0, heartbeatMs = 180_000L)
        assertTrue(throttle.shouldSend(0L, lat, lon))
        // ~0.00001 degrees is roughly 1.1m — well under the 15m gate.
        assertFalse(throttle.shouldSend(1_000L, lat + 0.00001, lon))
    }

    @Test
    fun `sending resets the move baseline to the new fix`() {
        val throttle = BeaconThrottle(minMoveMeters = 15.0, heartbeatMs = 180_000L)
        assertTrue(throttle.shouldSend(0L, lat, lon))
        assertTrue(throttle.shouldSend(1_000L, lat + 0.001, lon))
        // Immediately after that move, the same new position must not re-trigger.
        assertFalse(throttle.shouldSend(2_000L, lat + 0.001, lon))
    }
}
