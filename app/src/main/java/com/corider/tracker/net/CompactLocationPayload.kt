package com.corider.tracker.net

import com.corider.tracker.RiderSnapshot
import org.json.JSONObject

data class CompactLocationPayload(
    val riderId: String,
    val riderName: String,
    val timestampMs: Long,
    val latE7: Int,
    val lonE7: Int,
    val speedCentiMps: Int,
    val bearingDeg: Int,
    val accuracyM: Int
) {
    fun toJson(): String {
        return JSONObject()
            .put(KEY_TYPE, TYPE_LOCATION)
            .put(KEY_RIDER_ID, riderId)
            .put(KEY_NAME, riderName)
            .put(KEY_TIME, timestampMs)
            .put(KEY_LAT_E7, latE7)
            .put(KEY_LON_E7, lonE7)
            .put(KEY_SPEED, speedCentiMps)
            .put(KEY_BEARING, bearingDeg)
            .put(KEY_ACCURACY, accuracyM)
            .toString()
    }

    fun toSnapshot(): RiderSnapshot {
        return RiderSnapshot(
            id = riderId,
            name = riderName,
            latE7 = latE7,
            lonE7 = lonE7,
            speedCentiMps = speedCentiMps,
            bearingDeg = bearingDeg,
            accuracyM = accuracyM,
            updatedAtMs = timestampMs
        )
    }

    companion object {
        const val TYPE_LOCATION = "loc"
        const val TYPE_LEFT = "left"
        const val KEY_TYPE = "y"
        private const val KEY_RIDER_ID = "u"
        private const val KEY_NAME = "n"
        private const val KEY_TIME = "t"
        private const val KEY_LAT_E7 = "a"
        private const val KEY_LON_E7 = "o"
        private const val KEY_SPEED = "s"
        private const val KEY_BEARING = "b"
        private const val KEY_ACCURACY = "c"

        fun fromSnapshot(snapshot: RiderSnapshot): CompactLocationPayload {
            return CompactLocationPayload(
                riderId = snapshot.id,
                riderName = snapshot.name,
                timestampMs = snapshot.updatedAtMs,
                latE7 = snapshot.latE7,
                lonE7 = snapshot.lonE7,
                speedCentiMps = snapshot.speedCentiMps,
                bearingDeg = snapshot.bearingDeg,
                accuracyM = snapshot.accuracyM
            )
        }

        fun fromJson(json: String): CompactLocationPayload {
            val obj = JSONObject(json)
            return CompactLocationPayload(
                riderId = obj.getString(KEY_RIDER_ID),
                riderName = obj.optString(KEY_NAME, ""),
                timestampMs = obj.optLong(KEY_TIME, System.currentTimeMillis()),
                latE7 = obj.getInt(KEY_LAT_E7),
                lonE7 = obj.getInt(KEY_LON_E7),
                speedCentiMps = obj.optInt(KEY_SPEED, 0),
                bearingDeg = obj.optInt(KEY_BEARING, -1),
                accuracyM = obj.optInt(KEY_ACCURACY, -1)
            )
        }

        fun eventType(json: String): String {
            return JSONObject(json).optString(KEY_TYPE, TYPE_LOCATION)
        }

        fun leftRiderId(json: String): String {
            return JSONObject(json).optString(KEY_RIDER_ID, "")
        }
    }
}

