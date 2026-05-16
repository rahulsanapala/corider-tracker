package com.corider.tracker

import android.location.Location
import kotlin.math.cos
import kotlin.math.roundToInt

data class RideState(
    val active: Boolean = false,
    val rideId: String = "",
    val riderId: String = "",
    val riderName: String = "",
    val status: String = "Ready",
    val ownLocation: RiderSnapshot? = null,
    val riders: Map<String, RiderSnapshot> = emptyMap(),
    val groupAlert: GroupAlert? = null,
    val regroupPoint: RegroupPoint? = null,
    val safetyCheck: SafetyCheck? = null,
    val updateMode: UpdateMode = UpdateMode.NORMAL
)

data class GroupAlert(
    val riderId: String,
    val riderName: String,
    val message: String,
    val timestampMs: Long
)

data class RegroupPoint(
    val riderId: String,
    val riderName: String,
    val latE7: Int,
    val lonE7: Int,
    val timestampMs: Long
) {
    val latitude: Double get() = latE7 / 10_000_000.0
    val longitude: Double get() = lonE7 / 10_000_000.0
}

data class SafetyCheck(
    val id: String,
    val targetRiderId: String,
    val targetRiderName: String,
    val gapM: Int,
    val createdAtMs: Long,
    val dueAtMs: Long,
    val status: String,
    val acknowledgedAtMs: Long = 0L
)

enum class UpdateMode { ECO, NORMAL, FAST }

data class RiderSnapshot(
    val id: String,
    val name: String,
    val latE7: Int,
    val lonE7: Int,
    val speedCentiMps: Int,
    val bearingDeg: Int,
    val accuracyM: Int,
    val updatedAtMs: Long,
    val stationarySinceMs: Long = 0L
) {
    val latitude: Double get() = latE7 / 10_000_000.0
    val longitude: Double get() = lonE7 / 10_000_000.0
    val speedMps: Double get() = speedCentiMps / 100.0
    val label: String get() = name.ifBlank { id.take(6) }

    fun ageSeconds(nowMs: Long): Long = ((nowMs - updatedAtMs).coerceAtLeast(0L)) / 1000L

    fun isStale(nowMs: Long): Boolean = nowMs - updatedAtMs > STALE_AFTER_MS

    fun distanceTo(other: RiderSnapshot): Float {
        val result = FloatArray(1)
        Location.distanceBetween(latitude, longitude, other.latitude, other.longitude, result)
        return result[0]
    }

    fun offsetMetersFrom(origin: RiderSnapshot): Pair<Float, Float> {
        val latMeters = ((latitude - origin.latitude) * METERS_PER_LAT_DEGREE).toFloat()
        val lonMeters = (
            (longitude - origin.longitude) *
                METERS_PER_LON_DEGREE_AT_EQUATOR *
                cos(Math.toRadians(origin.latitude))
            ).toFloat()
        return lonMeters to latMeters
    }

    companion object {
        private const val STALE_AFTER_MS = 90_000L
        private const val METERS_PER_LAT_DEGREE = 110_540.0
        private const val METERS_PER_LON_DEGREE_AT_EQUATOR = 111_320.0

        fun fromLocation(
            id: String,
            name: String,
            location: Location,
            nowMs: Long,
            stationarySinceMs: Long = 0L
        ): RiderSnapshot {
            val lat = (location.latitude * 10_000_000).roundToInt()
            val lon = (location.longitude * 10_000_000).roundToInt()
            val speed = if (location.hasSpeed()) (location.speed * 100f).roundToInt() else 0
            val bearing = if (location.hasBearing()) location.bearing.roundToInt() else -1
            val accuracy = if (location.hasAccuracy()) location.accuracy.roundToInt() else -1
            return RiderSnapshot(id, name, lat, lon, speed, bearing, accuracy, nowMs, stationarySinceMs)
        }
    }
}
