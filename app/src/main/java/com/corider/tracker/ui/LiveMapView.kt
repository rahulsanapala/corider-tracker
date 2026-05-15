package com.corider.tracker.ui

import android.content.Context
import android.os.SystemClock
import android.widget.FrameLayout
import com.corider.tracker.RideState
import com.corider.tracker.RiderSnapshot
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import java.util.ArrayDeque

class LiveMapView(context: Context) : FrameLayout(context) {
    private val mapView: MapView
    private val riderMarkers = LinkedHashMap<String, Marker>()
    private val riderTrails = LinkedHashMap<String, Polyline>()
    private val trailPoints = LinkedHashMap<String, ArrayDeque<GeoPoint>>()
    private var ownMarker: Marker? = null
    private var accuracyCircle: Polygon? = null
    private var state = RideState()
    private var followOwnLocation = true
    private val interpolator = android.view.animation.AccelerateDecelerateInterpolator()

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
                snippet = own.snippet(now),
                bearingDeg = own.bearingDeg
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
            riderTrails.remove(id)?.let { trail ->
                mapView.overlays.remove(trail)
            }
            trailPoints.remove(id)
        }

        visible.values.forEach { rider ->
            riderMarkers[rider.id] = riderMarkers[rider.id].updateOrCreate(
                map = mapView,
                position = rider.toGeoPoint(),
                title = rider.label,
                snippet = rider.snippet(now),
                bearingDeg = rider.bearingDeg
            )
            updateTrail(rider.id, rider.toGeoPoint())
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
        snippet: String,
        bearingDeg: Int
    ): Marker {
        val existing = this
        if (existing != null) {
            animateMarker(existing, position, animationDurationMs(bearingDeg))
            updateHeading(existing, bearingDeg)
            existing.title = title
            existing.subDescription = snippet
            return existing
        }
        return Marker(map).apply {
            this.position = position
            this.title = title
            this.subDescription = snippet
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            updateHeading(this, bearingDeg)
            map.overlays.add(this)
        }
    }

    private fun updateHeading(marker: Marker, bearingDeg: Int) {
        marker.rotation = if (bearingDeg in 0..359) bearingDeg.toFloat() else 0f
    }

    private fun animationDurationMs(bearingDeg: Int): Long {
        return if (bearingDeg in 0..359) 1000L else 1300L
    }

    private fun animateMarker(marker: Marker, target: GeoPoint, durationMs: Long) {
        val start = marker.position
        val startLat = start.latitude
        val startLon = start.longitude
        val deltaLat = target.latitude - startLat
        val deltaLon = target.longitude - startLon
        val startMs = SystemClock.uptimeMillis()

        val step = object : Runnable {
            override fun run() {
                val elapsed = (SystemClock.uptimeMillis() - startMs).coerceAtLeast(0L)
                val t = (elapsed.toFloat() / durationMs).coerceIn(0f, 1f)
                val eased = interpolator.getInterpolation(t)
                marker.position = GeoPoint(
                    startLat + deltaLat * eased,
                    startLon + deltaLon * eased
                )
                mapView.invalidate()
                if (t < 1f) {
                    mapView.postOnAnimation(this)
                }
            }
        }
        mapView.post(step)
    }

    private fun RiderSnapshot.toGeoPoint(): GeoPoint = GeoPoint(latitude, longitude)

    private fun RiderSnapshot.snippet(nowMs: Long): String {
        val speedKmh = (speedMps * 3.6).toInt()
        return "${ageSeconds(nowMs)}s ago - $speedKmh km/h - $accuracyM m accuracy"
    }

    private fun updateTrail(riderId: String, point: GeoPoint) {
        val points = trailPoints.getOrPut(riderId) { ArrayDeque() }
        val last = points.lastOrNull()
        if (last == null || distanceMeters(last, point) >= MIN_TRAIL_POINT_DISTANCE_M) {
            points.addLast(point)
            while (points.size > MAX_TRAIL_POINTS) {
                points.removeFirst()
            }
        }

        val trail = riderTrails[riderId] ?: Polyline().also { poly ->
            poly.outlinePaint.color = 0xAA10B981.toInt()
            poly.outlinePaint.strokeWidth = 5f
            riderTrails[riderId] = poly
            mapView.overlays.add(poly)
        }
        trail.setPoints(points.toList())
    }

    private fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        return a.distanceToAsDouble(b)
    }

    companion object {
        private const val MAX_TRAIL_POINTS = 30
        private const val MIN_TRAIL_POINT_DISTANCE_M = 4.0
    }
}
