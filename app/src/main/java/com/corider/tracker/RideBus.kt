package com.corider.tracker

import java.util.concurrent.CopyOnWriteArraySet

object RideBus {
    interface Listener {
        fun onRideStateChanged(state: RideState)
    }

    private val listeners = CopyOnWriteArraySet<Listener>()

    @Volatile
    private var state = RideState()

    fun addListener(listener: Listener) {
        listeners.add(listener)
        listener.onRideStateChanged(state)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun setRide(rideId: String, riderId: String, riderName: String) {
        update {
            RideState(
                active = true,
                rideId = rideId,
                riderId = riderId,
                riderName = riderName,
                status = "Joined ride $rideId",
                ownLocation = null,
                riders = emptyMap()
            )
        }
    }

    fun stopRide() {
        update { RideState(status = "Stopped") }
    }

    fun setStatus(status: String) {
        update { it.copy(status = status) }
    }

    fun updateOwnLocation(snapshot: RiderSnapshot) {
        update { it.copy(ownLocation = snapshot) }
    }

    fun updateRider(snapshot: RiderSnapshot) {
        update { current ->
            if (snapshot.id == current.riderId) {
                current
            } else {
                val riders = LinkedHashMap(current.riders)
                riders[snapshot.id] = snapshot
                current.copy(riders = riders)
            }
        }
    }

    fun removeRider(riderId: String) {
        update { current ->
            val riders = LinkedHashMap(current.riders)
            riders.remove(riderId)
            current.copy(riders = riders)
        }
    }

    fun setGroupAlert(alert: GroupAlert?) {
        update { it.copy(groupAlert = alert) }
    }

    fun setRegroupPoint(point: RegroupPoint?) {
        update { it.copy(regroupPoint = point) }
    }

    fun setUpdateMode(mode: UpdateMode) {
        update { it.copy(updateMode = mode) }
    }

    private fun update(block: (RideState) -> RideState) {
        val next = synchronized(this) {
            state = block(state).dropStaleRiders()
            state
        }
        listeners.forEach { it.onRideStateChanged(next) }
    }

    private fun RideState.dropStaleRiders(): RideState {
        val now = System.currentTimeMillis()
        val fresh = riders.filterValues { !it.isStale(now) }
        return if (fresh.size == riders.size) this else copy(riders = fresh)
    }
}
