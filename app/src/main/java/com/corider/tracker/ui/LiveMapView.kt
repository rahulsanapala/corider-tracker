package com.corider.tracker.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.corider.tracker.BikeEvent
import com.corider.tracker.GroupAlert
import com.corider.tracker.R
import com.corider.tracker.RegroupPoint
import com.corider.tracker.RideState
import com.corider.tracker.RiderSnapshot
import com.corider.tracker.navigation.NavigationPoint
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

class LiveMapView(context: Context) : FrameLayout(context) {
    private val mapView: MapView
    private val sosArrow: ImageView
    private val riderIconCache = LinkedHashMap<String, BitmapDrawable>()
    private val riderMarkers = LinkedHashMap<String, Marker>()
    private val riderTrails = LinkedHashMap<String, Polyline>()
    private val trailPoints = LinkedHashMap<String, ArrayDeque<GeoPoint>>()
    private var ownMarker: Marker? = null
    private var regroupMarker: Marker? = null
    private var sosMarker: Marker? = null
    private var destinationMarker: Marker? = null
    private var routeLine: Polyline? = null
    private var temporaryRiderMarker: Marker? = null
    private val eventMarkers = LinkedHashMap<String, Marker>()
    private var temporaryRiderSnapshot: RiderSnapshot? = null
    private var accuracyCircle: Polygon? = null
    private var state = RideState()
    private var followOwnLocation = true
    private val interpolator = android.view.animation.AccelerateDecelerateInterpolator()
    var onSosMarkerClick: ((GroupAlert) -> Unit)? = null
    var onEventMarkerClick: ((BikeEvent) -> Unit)? = null

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
        sosArrow = ImageView(context).apply {
            setImageResource(R.drawable.ic_sos_direction_arrow)
            visibility = View.GONE
            elevation = 16f
            isClickable = true
            setOnClickListener {
                focusOnSos()
                state.groupAlert?.let { onSosMarkerClick?.invoke(it) }
            }
        }
        addView(sosArrow, LayoutParams(ARROW_SIZE_DP.dp(), ARROW_SIZE_DP.dp()))
        mapView.overlays.add(
            MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(point: GeoPoint?): Boolean {
                    closeEventInfoWindows()
                    return false
                }

                override fun longPressHelper(point: GeoPoint?): Boolean {
                    closeEventInfoWindows()
                    return false
                }
            })
        )
        mapView.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                updateSosArrow()
                return false
            }

            override fun onZoom(event: ZoomEvent?): Boolean {
                updateSosArrow()
                return false
            }
        })
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
        state.ownLocation?.let { moveCamera(it, zoomToTrackingLevel = true) }
    }

    fun focusOnRider(riderId: String): Boolean {
        val rider = state.riders[riderId] ?: return false
        followOwnLocation = false
        moveCamera(rider, zoomToTrackingLevel = true)
        return true
    }

    fun showTemporaryRider(snapshot: RiderSnapshot) {
        temporaryRiderSnapshot = snapshot
        followOwnLocation = false
        updateTemporaryRiderMarker(System.currentTimeMillis())
        moveCamera(snapshot, zoomToTrackingLevel = true)
        mapView.invalidate()
    }

    fun clearTemporaryRider() {
        temporaryRiderSnapshot = null
        temporaryRiderMarker?.let { mapView.overlays.remove(it) }
        temporaryRiderMarker = null
        mapView.invalidate()
    }

    fun showDestination(name: String, latitude: Double, longitude: Double) {
        val point = GeoPoint(latitude, longitude)
        destinationMarker = destinationMarker ?: Marker(mapView).also {
            it.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            it.icon = destinationPinDrawable()
            mapView.overlays.add(it)
        }
        destinationMarker?.apply {
            position = point
            title = name
            subDescription = "Destination"
        }
        followOwnLocation = false
        if (mapView.zoomLevelDouble < TRACKING_ZOOM) {
            mapView.controller.setZoom(TRACKING_ZOOM)
        }
        mapView.controller.animateTo(point)
        mapView.invalidate()
    }

    fun showRoute(points: List<NavigationPoint>) {
        if (points.isEmpty()) return
        val geoPoints = points.map { GeoPoint(it.latitude, it.longitude) }
        val line = routeLine ?: Polyline().also {
            it.outlinePaint.color = Color.rgb(37, 99, 235)
            it.outlinePaint.strokeWidth = 8f
            it.outlinePaint.strokeCap = Paint.Cap.ROUND
            it.outlinePaint.strokeJoin = Paint.Join.ROUND
            routeLine = it
            mapView.overlays.add(0, it)
        }
        line.setPoints(geoPoints)
        mapView.invalidate()
    }

    fun clearNavigation() {
        destinationMarker?.let { mapView.overlays.remove(it) }
        routeLine?.let { mapView.overlays.remove(it) }
        destinationMarker = null
        routeLine = null
        mapView.invalidate()
    }

    fun setGlobalEvents(events: Collection<BikeEvent>, show: Boolean) {
        if (!show) {
            clearEventMarkers()
            return
        }

        val visibleEvents = events.associateBy { it.id }
        val removedIds = eventMarkers.keys - visibleEvents.keys
        removedIds.forEach { id ->
            eventMarkers.remove(id)?.let { mapView.overlays.remove(it) }
        }

        visibleEvents.values.forEach { event ->
            val marker = eventMarkers[event.id] ?: Marker(mapView).also {
                it.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                it.icon = eventPinDrawable(event.id)
                mapView.overlays.add(it)
                eventMarkers[event.id] = it
            }
            marker.position = GeoPoint(event.latitude, event.longitude)
            marker.icon = eventPinDrawable(event.id)
            marker.title = event.name
            marker.subDescription = listOf(event.eventTime, event.locationName)
                .filter { it.isNotBlank() }
                .joinToString(" - ")
            marker.setOnMarkerClickListener { _, _ ->
                onEventMarkerClick?.invoke(event)
                true
            }
        }
        mapView.invalidate()
    }

    fun focusOnEvent(eventId: String): Boolean {
        val marker = eventMarkers[eventId] ?: return false
        followOwnLocation = false
        if (mapView.zoomLevelDouble < TRACKING_ZOOM) {
            mapView.controller.setZoom(TRACKING_ZOOM)
        }
        mapView.controller.animateTo(marker.position)
        marker.showInfoWindow()
        return true
    }

    fun closeEventInfoWindows() {
        eventMarkers.values.forEach { marker ->
            if (marker.isInfoWindowShown) marker.closeInfoWindow()
        }
    }

    fun focusOnSos(): Boolean {
        val point = state.groupAlert?.let { sosGeoPoint(it) } ?: return false
        followOwnLocation = false
        if (mapView.zoomLevelDouble < TRACKING_ZOOM) {
            mapView.controller.setZoom(TRACKING_ZOOM)
        }
        mapView.controller.animateTo(point)
        post { updateSosArrow() }
        return true
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
                riderId = own.id,
                riderName = own.label,
                ownRider = true,
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
                riderId = rider.id,
                riderName = rider.label,
                ownRider = false,
                bearingDeg = rider.bearingDeg
            )
            updateTrail(rider.id, rider.toGeoPoint())
        }
        if (temporaryRiderSnapshot?.id in visible.keys) {
            clearTemporaryRider()
        } else {
            updateTemporaryRiderMarker(now)
        }
        updateRegroupMarker(state.regroupPoint)
        updateSosMarker(state.groupAlert)
        post { updateSosArrow() }
        mapView.invalidate()
    }

    private fun updateTemporaryRiderMarker(nowMs: Long) {
        val snapshot = temporaryRiderSnapshot
        if (snapshot == null) {
            temporaryRiderMarker?.let { mapView.overlays.remove(it) }
            temporaryRiderMarker = null
            return
        }
        temporaryRiderMarker = temporaryRiderMarker.updateOrCreate(
            map = mapView,
            position = snapshot.toGeoPoint(),
            title = "${snapshot.label} last known",
            snippet = snapshot.snippet(nowMs),
            riderId = snapshot.id,
            riderName = snapshot.label,
            ownRider = false,
            bearingDeg = snapshot.bearingDeg
        )
    }

    private fun updateSosMarker(alert: GroupAlert?) {
        val point = alert?.let { sosGeoPoint(it) }
        if (alert == null || point == null) {
            sosMarker?.let { mapView.overlays.remove(it) }
            sosMarker = null
            sosArrow.visibility = View.GONE
            return
        }

        val marker = sosMarker ?: Marker(mapView).also {
            it.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            it.icon = sosPinDrawable()
            mapView.overlays.add(it)
            sosMarker = it
        }
        marker.position = point
        marker.title = "SOS"
        marker.subDescription = "${alert.riderName}: ${alert.message}"
        marker.setOnMarkerClickListener { _, _ ->
            onSosMarkerClick?.invoke(alert)
            true
        }
    }

    private fun updateRegroupMarker(point: RegroupPoint?) {
        if (point == null) {
            regroupMarker?.let { mapView.overlays.remove(it) }
            regroupMarker = null
            return
        }

        val position = GeoPoint(point.latitude, point.longitude)
        val marker = regroupMarker ?: Marker(mapView).also {
            it.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            mapView.overlays.add(it)
            regroupMarker = it
        }
        marker.position = position
        marker.title = "Regroup point"
        marker.subDescription = "Set by ${point.riderName}"
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

    private fun moveCamera(snapshot: RiderSnapshot, zoomToTrackingLevel: Boolean = false) {
        if (zoomToTrackingLevel) {
            mapView.controller.setZoom(TRACKING_ZOOM)
        }
        mapView.controller.animateTo(snapshot.toGeoPoint())
    }

    private fun Marker?.updateOrCreate(
        map: MapView,
        position: GeoPoint,
        title: String,
        snippet: String,
        riderId: String,
        riderName: String,
        ownRider: Boolean,
        bearingDeg: Int
    ): Marker {
        val icon = riderPinDrawable(riderId, riderName, ownRider)
        val existing = this
        if (existing != null) {
            animateMarker(existing, position, animationDurationMs(bearingDeg))
            existing.rotation = 0f
            existing.icon = icon
            existing.title = title
            existing.subDescription = snippet
            return existing
        }
        return Marker(map).apply {
            this.position = position
            this.title = title
            this.subDescription = snippet
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            this.icon = icon
            rotation = 0f
            map.overlays.add(this)
        }
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

    private fun sosGeoPoint(alert: GroupAlert): GeoPoint? {
        val lat = alert.latE7
        val lon = alert.lonE7
        if (lat != null && lon != null) return GeoPoint(lat / 10_000_000.0, lon / 10_000_000.0)
        state.ownLocation?.takeIf { it.id == alert.riderId }?.let { return it.toGeoPoint() }
        return state.riders[alert.riderId]?.toGeoPoint()
    }

    private fun RiderSnapshot.snippet(nowMs: Long): String {
        val speedKmh = (speedMps * 3.6).toInt()
        return "${ageSeconds(nowMs)}s ago - $speedKmh km/h - $accuracyM m accuracy"
    }

    private fun updateSosArrow() {
        val point = state.groupAlert?.let { sosGeoPoint(it) }
        val parentWidth = width
        val parentHeight = height
        if (point == null || parentWidth <= 0 || parentHeight <= 0) {
            sosArrow.visibility = View.GONE
            return
        }

        val screen = mapView.projection.toPixels(point, Point())
        val margin = EDGE_ARROW_MARGIN_DP.dp()
        val onScreen = screen.x in margin..(parentWidth - margin) && screen.y in margin..(parentHeight - margin)
        if (onScreen) {
            sosArrow.visibility = View.GONE
            return
        }

        val centerX = parentWidth / 2f
        val centerY = parentHeight / 2f
        val dx = screen.x - centerX
        val dy = screen.y - centerY
        val angle = atan2(dy, dx)
        val size = ARROW_SIZE_DP.dp()
        val minX = margin / 2
        val minY = margin / 2
        val maxX = parentWidth - size - margin / 2
        val maxY = parentHeight - size - margin / 2
        if (maxX < minX || maxY < minY) {
            sosArrow.visibility = View.GONE
            return
        }

        val edgeX = centerX + cos(angle) * (parentWidth / 2f - margin)
        val edgeY = centerY + sin(angle) * (parentHeight / 2f - margin)
        val params = (sosArrow.layoutParams as LayoutParams).apply {
            leftMargin = (edgeX.roundToInt() - size / 2).coerceIn(minX, maxX)
            topMargin = (edgeY.roundToInt() - size / 2).coerceIn(minY, maxY)
        }
        sosArrow.layoutParams = params
        sosArrow.rotation = Math.toDegrees(angle.toDouble()).toFloat() + 90f
        sosArrow.visibility = View.VISIBLE
    }

    private fun sosPinDrawable(): BitmapDrawable {
        val size = SOS_PIN_SIZE_DP.dp()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val cx = size / 2f
        val headCy = size * 0.34f
        val headR = size * 0.29f

        paint.color = Color.rgb(220, 38, 38)
        val pin = Path().apply {
            moveTo(cx, size - 3f)
            cubicTo(size * 0.17f, size * 0.58f, size * 0.12f, size * 0.2f, cx, size * 0.05f)
            cubicTo(size * 0.88f, size * 0.2f, size * 0.83f, size * 0.58f, cx, size - 3f)
            close()
        }
        canvas.drawPath(pin, paint)

        paint.color = Color.WHITE
        canvas.drawCircle(cx, headCy, headR, paint)
        paint.color = Color.rgb(220, 38, 38)
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = size * 0.18f
        val textY = headCy - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText("SOS", cx, textY, paint)
        return BitmapDrawable(resources, bitmap)
    }

    private fun eventPinDrawable(eventId: String): BitmapDrawable {
        val size = EVENT_PIN_SIZE_DP.dp()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val cx = size / 2f
        val headCy = size * 0.34f
        val headR = size * 0.28f
        val color = EVENT_COLORS[(eventId.hashCode() and Int.MAX_VALUE) % EVENT_COLORS.size]

        paint.color = Color.argb(70, 0, 0, 0)
        canvas.drawOval(size * 0.34f, size * 0.87f, size * 0.66f, size * 0.96f, paint)

        paint.color = color
        val pin = Path().apply {
            moveTo(cx, size - 3f)
            cubicTo(size * 0.18f, size * 0.58f, size * 0.12f, size * 0.2f, cx, size * 0.05f)
            cubicTo(size * 0.88f, size * 0.2f, size * 0.82f, size * 0.58f, cx, size - 3f)
            close()
        }
        canvas.drawPath(pin, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * 0.035f
        paint.color = Color.BLACK
        canvas.drawPath(pin, paint)
        paint.style = Paint.Style.FILL

        paint.color = Color.WHITE
        canvas.drawCircle(cx, headCy, headR, paint)

        paint.color = Color.rgb(17, 24, 39)
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeWidth = size * 0.045f

        val wheelR = headR * 0.22f
        val leftWheelX = cx - headR * 0.42f
        val rightWheelX = cx + headR * 0.42f
        val wheelY = headCy + headR * 0.28f
        canvas.drawCircle(leftWheelX, wheelY, wheelR, paint)
        canvas.drawCircle(rightWheelX, wheelY, wheelR, paint)

        val crankX = cx - headR * 0.05f
        val crankY = headCy + headR * 0.05f
        canvas.drawLine(leftWheelX, wheelY, crankX, crankY, paint)
        canvas.drawLine(crankX, crankY, rightWheelX, wheelY, paint)
        canvas.drawLine(crankX, crankY, cx + headR * 0.18f, headCy - headR * 0.22f, paint)
        canvas.drawLine(cx + headR * 0.18f, headCy - headR * 0.22f, rightWheelX, wheelY, paint)
        canvas.drawLine(cx - headR * 0.22f, headCy - headR * 0.18f, cx + headR * 0.08f, headCy - headR * 0.18f, paint)
        canvas.drawLine(cx + headR * 0.18f, headCy - headR * 0.22f, cx + headR * 0.46f, headCy - headR * 0.34f, paint)
        paint.style = Paint.Style.FILL

        return BitmapDrawable(resources, bitmap)
    }

    private fun destinationPinDrawable(): BitmapDrawable {
        val size = DESTINATION_PIN_SIZE_DP.dp()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val cx = size / 2f
        val headCy = size * 0.34f
        val headR = size * 0.28f

        paint.color = Color.rgb(245, 158, 11)
        val pin = Path().apply {
            moveTo(cx, size - 3f)
            cubicTo(size * 0.18f, size * 0.58f, size * 0.12f, size * 0.2f, cx, size * 0.05f)
            cubicTo(size * 0.88f, size * 0.2f, size * 0.82f, size * 0.58f, cx, size - 3f)
            close()
        }
        canvas.drawPath(pin, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * 0.035f
        paint.color = Color.BLACK
        canvas.drawPath(pin, paint)
        paint.style = Paint.Style.FILL

        paint.color = Color.WHITE
        canvas.drawCircle(cx, headCy, headR, paint)
        paint.color = Color.rgb(17, 24, 39)
        canvas.drawCircle(cx, headCy, headR * 0.46f, paint)
        return BitmapDrawable(resources, bitmap)
    }

    private fun riderPinDrawable(riderId: String, riderName: String, ownRider: Boolean): BitmapDrawable {
        val initials = riderInitials(riderName, riderId)
        val color = riderColor(riderId, ownRider)
        val cacheKey = "$riderId|$initials|$color|$ownRider"
        riderIconCache[cacheKey]?.let { return it }

        val size = RIDER_PIN_SIZE_DP.dp()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val cx = size / 2f
        val headCy = size * 0.35f
        val headR = size * 0.31f

        paint.color = Color.argb(90, 0, 0, 0)
        canvas.drawOval(
            size * 0.31f,
            size * 0.88f,
            size * 0.69f,
            size * 0.97f,
            paint
        )

        paint.color = color
        val pin = Path().apply {
            moveTo(cx, size - 4f)
            cubicTo(size * 0.14f, size * 0.62f, size * 0.1f, size * 0.18f, cx, size * 0.04f)
            cubicTo(size * 0.9f, size * 0.18f, size * 0.86f, size * 0.62f, cx, size - 4f)
            close()
        }
        canvas.drawPath(pin, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * 0.035f
        paint.color = Color.argb(190, 0, 0, 0)
        canvas.drawPath(pin, paint)
        paint.style = Paint.Style.FILL

        paint.color = Color.WHITE
        canvas.drawCircle(cx, headCy, headR, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * 0.028f
        paint.color = Color.argb(210, 0, 0, 0)
        canvas.drawCircle(cx, headCy, headR, paint)
        paint.style = Paint.Style.FILL

        paint.color = Color.rgb(17, 24, 39)
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = size * 0.23f
        val textY = headCy - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(initials, cx, textY, paint)

        return BitmapDrawable(resources, bitmap).also {
            if (riderIconCache.size > MAX_RIDER_ICON_CACHE) {
                val firstKey = riderIconCache.keys.firstOrNull()
                if (firstKey != null) riderIconCache.remove(firstKey)
            }
            riderIconCache[cacheKey] = it
        }
    }

    private fun riderInitials(name: String, fallback: String): String {
        val source = name.trim().ifBlank { fallback.take(6) }
        val token = source.split(Regex("\\s+")).firstOrNull().orEmpty()
        val letters = token.filter { it.isLetterOrDigit() }.take(2)
            .ifBlank { source.filter { it.isLetterOrDigit() }.take(2) }
            .ifBlank { "R" }
        return letters.lowercase(Locale.US).replaceFirstChar { it.uppercase(Locale.US) }
    }

    private fun riderColor(riderId: String, ownRider: Boolean): Int {
        if (ownRider) return Color.rgb(37, 99, 235)
        return RIDER_COLORS[(riderId.hashCode() and Int.MAX_VALUE) % RIDER_COLORS.size]
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

    private fun clearEventMarkers() {
        eventMarkers.values.forEach { mapView.overlays.remove(it) }
        eventMarkers.clear()
        mapView.invalidate()
    }

    companion object {
        private const val MAX_TRAIL_POINTS = 30
        private const val MIN_TRAIL_POINT_DISTANCE_M = 4.0
        private const val TRACKING_ZOOM = 16.0
        private const val SOS_PIN_SIZE_DP = 58
        private const val RIDER_PIN_SIZE_DP = 58
        private const val DESTINATION_PIN_SIZE_DP = 58
        private const val EVENT_PIN_SIZE_DP = 46
        private const val ARROW_SIZE_DP = 42
        private const val EDGE_ARROW_MARGIN_DP = 34
        private const val MAX_RIDER_ICON_CACHE = 80
        private val RIDER_COLORS = intArrayOf(
            Color.rgb(239, 68, 68),
            Color.rgb(34, 197, 94),
            Color.rgb(14, 165, 233),
            Color.rgb(168, 85, 247),
            Color.rgb(245, 158, 11),
            Color.rgb(236, 72, 153),
            Color.rgb(20, 184, 166),
            Color.rgb(234, 179, 8),
            Color.rgb(249, 115, 22),
            Color.rgb(100, 116, 139)
        )
        private val EVENT_COLORS = intArrayOf(
            Color.rgb(245, 158, 11),
            Color.rgb(168, 85, 247),
            Color.rgb(14, 165, 233),
            Color.rgb(236, 72, 153),
            Color.rgb(34, 197, 94)
        )
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).roundToInt()
}
