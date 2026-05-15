package com.corider.tracker.ui

import android.content.Context
import android.os.Bundle
import android.widget.FrameLayout
import com.corider.tracker.RideState
import com.corider.tracker.RiderSnapshot
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.Circle
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions

class LiveMapView(context: Context) : FrameLayout(context), OnMapReadyCallback {
    private val mapView = MapView(context)
    private val riderMarkers = LinkedHashMap<String, Marker>()
    private var ownMarker: Marker? = null
    private var accuracyCircle: Circle? = null
    private var map: GoogleMap? = null
    private var state = RideState()
    private var followOwnLocation = true
    private var mapRequested = false

    init {
        addView(
            mapView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
    }

    fun onCreate(savedInstanceState: Bundle?) {
        mapView.onCreate(savedInstanceState)
        if (!mapRequested) {
            mapRequested = true
            mapView.getMapAsync(this)
        }
    }

    fun onResume() {
        mapView.onResume()
    }

    fun onPause() {
        mapView.onPause()
    }

    fun onDestroy() {
        mapView.onDestroy()
    }

    fun onLowMemory() {
        mapView.onLowMemory()
    }

    fun setState(next: RideState) {
        state = next
        render()
    }

    fun centerOnMe() {
        followOwnLocation = true
        state.ownLocation?.let { moveCamera(it, animate = true) }
    }

    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap.apply {
            mapType = GoogleMap.MAP_TYPE_NORMAL
            uiSettings.isCompassEnabled = true
            uiSettings.isMapToolbarEnabled = false
            uiSettings.isMyLocationButtonEnabled = false
            uiSettings.isZoomControlsEnabled = true
            setOnCameraMoveStartedListener { reason ->
                if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) {
                    followOwnLocation = false
                }
            }
        }
        render()
    }

    private fun render() {
        val googleMap = map ?: return
        val own = state.ownLocation
        val now = System.currentTimeMillis()

        if (own != null) {
            ownMarker = ownMarker.updateOrCreate(
                map = googleMap,
                position = own.toLatLng(),
                title = "You",
                snippet = own.snippet(now),
                hue = BitmapDescriptorFactory.HUE_AZURE
            )
            updateAccuracyCircle(googleMap, own)
            if (followOwnLocation) {
                moveCamera(own, animate = ownMarker != null)
            }
        }

        val visibleRiders = state.riders.values
            .filter { !it.isStale(now) }
            .associateBy { it.id }

        val removedIds = riderMarkers.keys - visibleRiders.keys
        removedIds.forEach { id ->
            riderMarkers.remove(id)?.remove()
        }

        visibleRiders.values.forEach { rider ->
            riderMarkers[rider.id] = riderMarkers[rider.id].updateOrCreate(
                map = googleMap,
                position = rider.toLatLng(),
                title = rider.label,
                snippet = rider.snippet(now),
                hue = BitmapDescriptorFactory.HUE_GREEN
            )
        }
    }

    private fun updateAccuracyCircle(googleMap: GoogleMap, own: RiderSnapshot) {
        val accuracy = own.accuracyM.takeIf { it > 0 } ?: return
        val existing = accuracyCircle
        if (existing == null) {
            accuracyCircle = googleMap.addCircle(
                CircleOptions()
                    .center(own.toLatLng())
                    .radius(accuracy.toDouble())
                    .strokeColor(0x552563EB)
                    .fillColor(0x182563EB)
                    .strokeWidth(2f)
            )
        } else {
            existing.center = own.toLatLng()
            existing.radius = accuracy.toDouble()
        }
    }

    private fun moveCamera(snapshot: RiderSnapshot, animate: Boolean) {
        val update = CameraUpdateFactory.newLatLngZoom(snapshot.toLatLng(), FOLLOW_ZOOM)
        if (animate) {
            map?.animateCamera(update)
        } else {
            map?.moveCamera(update)
        }
    }

    private fun Marker?.updateOrCreate(
        map: GoogleMap,
        position: LatLng,
        title: String,
        snippet: String,
        hue: Float
    ): Marker {
        val existing = this
        if (existing != null) {
            existing.position = position
            existing.title = title
            existing.snippet = snippet
            return existing
        }
        return map.addMarker(
            MarkerOptions()
                .position(position)
                .title(title)
                .snippet(snippet)
                .icon(BitmapDescriptorFactory.defaultMarker(hue))
        ) ?: error("GoogleMap failed to create marker")
    }

    private fun RiderSnapshot.toLatLng(): LatLng = LatLng(latitude, longitude)

    private fun RiderSnapshot.snippet(nowMs: Long): String {
        val speedKmh = (speedMps * 3.6).toInt()
        return "${ageSeconds(nowMs)}s ago - $speedKmh km/h - $accuracyM m accuracy"
    }

    companion object {
        private const val FOLLOW_ZOOM = 16f
    }
}
