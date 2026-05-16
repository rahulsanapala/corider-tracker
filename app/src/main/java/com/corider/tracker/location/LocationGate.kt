package com.corider.tracker.location

import android.location.Location
import com.corider.tracker.UpdateMode

class LocationGate {
    private var lastPublished: Location? = null
    private var lastPublishedAtMs: Long = 0L
    private var updateMode = UpdateMode.NORMAL

    fun setMode(mode: UpdateMode) {
        updateMode = mode
    }

    fun shouldPublish(location: Location, nowMs: Long): Boolean {
        val speed = if (location.hasSpeed()) location.speed else 0f
        val config = configFor(updateMode, speed)
        val accuracy = if (location.hasAccuracy()) location.accuracy else 0f
        if (accuracy > MAX_ACCEPTED_ACCURACY_M && nowMs - lastPublishedAtMs < config.heartbeatMs) {
            return false
        }

        val last = lastPublished ?: return true
        val elapsed = nowMs - lastPublishedAtMs

        if (elapsed >= config.heartbeatMs) return true
        return elapsed >= config.intervalMs && location.distanceTo(last) >= config.distanceM
    }

    fun markPublished(location: Location, nowMs: Long) {
        lastPublished = Location(location)
        lastPublishedAtMs = nowMs
    }

    companion object {
        private const val FAST_SPEED_MPS = 12f
        private const val MOVING_SPEED_MPS = 2f
        private const val MAX_ACCEPTED_ACCURACY_M = 120f

        private data class PublishConfig(
            val intervalMs: Long,
            val heartbeatMs: Long,
            val distanceM: Float
        )

        private fun configFor(mode: UpdateMode, speedMps: Float): PublishConfig {
            return when (mode) {
                UpdateMode.ECO -> when {
                    speedMps >= FAST_SPEED_MPS -> PublishConfig(10_000L, 60_000L, 50f)
                    speedMps >= MOVING_SPEED_MPS -> PublishConfig(20_000L, 90_000L, 30f)
                    else -> PublishConfig(60_000L, 120_000L, 15f)
                }
                UpdateMode.NORMAL -> when {
                    speedMps >= FAST_SPEED_MPS -> PublishConfig(5_000L, 45_000L, 25f)
                    speedMps >= MOVING_SPEED_MPS -> PublishConfig(10_000L, 60_000L, 12f)
                    else -> PublishConfig(30_000L, 90_000L, 8f)
                }
                UpdateMode.FAST -> when {
                    speedMps >= FAST_SPEED_MPS -> PublishConfig(3_000L, 30_000L, 15f)
                    speedMps >= MOVING_SPEED_MPS -> PublishConfig(5_000L, 45_000L, 8f)
                    else -> PublishConfig(15_000L, 60_000L, 5f)
                }
            }
        }
    }
}
