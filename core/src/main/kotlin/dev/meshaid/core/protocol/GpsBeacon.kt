package dev.meshaid.core.protocol

import java.nio.ByteBuffer

/**
 * Fixed 20-byte GPS beacon payload (GPS_BEACON and the head of SOS payloads).
 */
class GpsBeacon(
    val latE7: Int,
    val lonE7: Int,
    val accuracyM: Int,
    val batteryPct: Int,
    val altitudeM: Int = 0,
    val speedKmh: Int = 0,
    val headingDeg: Int = 0,
) {
    val lat: Double get() = latE7 / 1e7
    val lon: Double get() = lonE7 / 1e7

    fun encode(): ByteArray {
        val buf = ByteBuffer.allocate(SIZE)
        buf.putInt(latE7)
        buf.putInt(lonE7)
        buf.putShort(accuracyM.coerceIn(0, 65535).toShort())
        buf.put(batteryPct.coerceIn(0, 100).toByte())
        buf.put(0) // reserved
        buf.putShort(altitudeM.coerceIn(-32768, 32767).toShort())
        buf.putShort(speedKmh.coerceIn(0, 65535).toShort())
        buf.putShort(headingDeg.coerceIn(0, 359).toShort())
        buf.putShort(0) // reserved
        return buf.array()
    }

    companion object {
        const val SIZE = 20

        fun of(lat: Double, lon: Double, accuracyM: Int, batteryPct: Int): GpsBeacon =
            GpsBeacon((lat * 1e7).toInt(), (lon * 1e7).toInt(), accuracyM, batteryPct)

        fun decode(bytes: ByteArray, offset: Int = 0): GpsBeacon {
            if (bytes.size - offset < SIZE) throw ProtocolException("GPS beacon truncated")
            val buf = ByteBuffer.wrap(bytes, offset, SIZE)
            val lat = buf.int
            val lon = buf.int
            val acc = buf.short.toInt() and 0xFFFF
            val battery = buf.get().toInt() and 0xFF
            buf.get() // reserved
            val alt = buf.short.toInt()
            val speed = buf.short.toInt() and 0xFFFF
            val heading = buf.short.toInt() and 0xFFFF
            return GpsBeacon(lat, lon, acc, battery, alt, speed, heading)
        }
    }
}
