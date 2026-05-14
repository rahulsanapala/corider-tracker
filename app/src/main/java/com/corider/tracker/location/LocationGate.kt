package com.corider.tracker.location

import android.location.Location

class LocationGate {
    private var lastPublished: Location? = null
    private var lastPublishedAtMs: Long = 0L

    fun shouldPublish(location: Location, nowMs: Long): Boolean {
        val accuracy = if (location.hasAccuracy()) location.accuracy else 0f
        if (accuracy > MAX_ACCEPTED_ACCURACY_M && nowMs - lastPublishedAtMs < HEARTBEAT_MS) {
            return false
        }

        val last = lastPublished ?: return true
        val elapsed = nowMs - lastPublishedAtMs
        val speed = if (location.hasSpeed()) location.speed else 0f
        val minInterval = when {
            speed >= FAST_SPEED_MPS -> FAST_INTERVAL_MS
            speed >= MOVING_SPEED_MPS -> MOVING_INTERVAL_MS
            else -> SLOW_INTERVAL_MS
        }
        val minDistance = when {
            speed >= FAST_SPEED_MPS -> FAST_DISTANCE_M
            speed >= MOVING_SPEED_MPS -> MOVING_DISTANCE_M
            else -> SLOW_DISTANCE_M
        }

        if (elapsed >= HEARTBEAT_MS) return true
        return elapsed >= minInterval && location.distanceTo(last) >= minDistance
    }

    fun markPublished(location: Location, nowMs: Long) {
        lastPublished = Location(location)
        lastPublishedAtMs = nowMs
    }

    companion object {
        private const val FAST_SPEED_MPS = 12f
        private const val MOVING_SPEED_MPS = 2f
        private const val FAST_INTERVAL_MS = 3_000L
        private const val MOVING_INTERVAL_MS = 5_000L
        private const val SLOW_INTERVAL_MS = 15_000L
        private const val HEARTBEAT_MS = 60_000L
        private const val FAST_DISTANCE_M = 25f
        private const val MOVING_DISTANCE_M = 12f
        private const val SLOW_DISTANCE_M = 8f
        private const val MAX_ACCEPTED_ACCURACY_M = 120f
    }
}

