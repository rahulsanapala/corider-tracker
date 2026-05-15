package com.corider.tracker.ui

import android.content.Context
import android.widget.FrameLayout
import com.corider.tracker.RideState
import com.corider.tracker.RiderSnapshot
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon

class LiveMapView(context: Context) : FrameLayout(context) {
    private val mapView: MapView
    private val riderMarkers = LinkedHashMap<String, Marker>()
    private var ownMarker: Marker? = null
    private var accuracyCircle: Polygon? = null
    private var state = RideState()
    private var followOwnLocation = true

    init {
        Configuration.getInstance().userAgentValue = context.packageName
        mapView = MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(16.0)
            minZoomLevel = 3.0
            maxZoomLevel = 20.0
        }
        addView(mapView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun onCreate() = Unit
    fun onResume() = mapView.onResume()
    fun onPause() = mapView.onPause()
    fun onDestroy() = mapView.onDetach()
    fun onLowMemory() = Unit

    fun setState(next: RideState) {
        state = next
        render()
    }

    fun centerOnMe() {
        followOwnLocation = true
        state.ownLocation?.let { moveCamera(it) }
    }

    private fun render() {
        val own = state.ownLocation
        val now = System.currentTimeMillis()

        if (own != null) {
            ownMarker = ownMarker.updateOrCreate(
                map = mapView,
                position = own.toGeoPoint(),
                title = "You",
                snippet = own.snippet(now)
            )
            updateAccuracyCircle(own)
            if (followOwnLocation) moveCamera(own)
        }

        val visible = state.riders.values.filter { !it.isStale(now) }.associateBy { it.id }
        val removedIds = riderMarkers.keys - visible.keys
        removedIds.forEach { id ->
            riderMarkers.remove(id)?.let { marker ->
                mapView.overlays.remove(marker)
            }
        }

        visible.values.forEach { rider ->
            riderMarkers[rider.id] = riderMarkers[rider.id].updateOrCreate(
                map = mapView,
                position = rider.toGeoPoint(),
                title = rider.label,
                snippet = rider.snippet(now)
            )
        }
        mapView.invalidate()
    }

    private fun updateAccuracyCircle(own: RiderSnapshot) {
        val accuracy = own.accuracyM.takeIf { it > 0 } ?: return
        accuracyCircle?.let { mapView.overlays.remove(it) }
        accuracyCircle = Polygon().apply {
            points = Polygon.pointsAsCircle(own.toGeoPoint(), accuracy.toDouble())
            fillColor = 0x182563EB
            strokeColor = 0x552563EB
            strokeWidth = 2f
        }
        mapView.overlays.add(accuracyCircle)
    }

    private fun moveCamera(snapshot: RiderSnapshot) {
        mapView.controller.animateTo(snapshot.toGeoPoint())
    }

    private fun Marker?.updateOrCreate(
        map: MapView,
        position: GeoPoint,
        title: String,
        snippet: String
    ): Marker {
        val existing = this
        if (existing != null) {
            existing.position = position
            existing.title = title
            existing.subDescription = snippet
            return existing
        }
        return Marker(map).apply {
            this.position = position
            this.title = title
            this.subDescription = snippet
            map.overlays.add(this)
        }
    }

    private fun RiderSnapshot.toGeoPoint(): GeoPoint = GeoPoint(latitude, longitude)

    private fun RiderSnapshot.snippet(nowMs: Long): String {
        val speedKmh = (speedMps * 3.6).toInt()
        return "${ageSeconds(nowMs)}s ago - $speedKmh km/h - $accuracyM m accuracy"
    }
}
