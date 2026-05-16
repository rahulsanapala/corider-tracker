package com.corider.tracker.location

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.corider.tracker.MainActivity
import com.corider.tracker.R
import com.corider.tracker.RideBus
import com.corider.tracker.RiderSnapshot
import com.corider.tracker.GroupAlert
import com.corider.tracker.RegroupPoint
import com.corider.tracker.SafetyCheck
import com.corider.tracker.UpdateMode
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class LiveLocationService : Service(), LocationListener {
    private lateinit var locationManager: LocationManager
    private var rideId: String = ""
    private var riderId: String = ""
    private var riderName: String = ""
    private var rideRefPath: String = ""
    private var rideRootPath: String = ""
    private var running = false
    private var publishExecutor: ExecutorService? = null
    private val gate = LocationGate()
    private var lastOwnSnapshot: RiderSnapshot? = null
    private var riderEventsListener: ChildEventListener? = null
    private var alertListener: ValueEventListener? = null
    private var regroupListener: ValueEventListener? = null
    private var modeListener: ValueEventListener? = null
    private var safetyListener: ValueEventListener? = null
    private var safetyRiders = LinkedHashMap<String, RiderSnapshot>()
    private var localStationarySinceMs = 0L
    private var lastPublishedSafetyCheckId = ""
    private var lastNotifiedSafetyCheckId = ""
    private var lastServiceSosToneTimestampMs = 0L
    private var groupEventsStartedAtMs = 0L
    private var sosTone: ToneGenerator? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LocationManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTracking(intent)
            ACTION_STOP -> {
                stopTracking(sendLeave = true)
                stopSelf()
            }
            ACTION_SOS -> publishSos()
            ACTION_REGROUP -> publishRegroup()
            ACTION_CLEAR_REGROUP -> clearRegroup()
            ACTION_ACK_SAFETY -> acknowledgeSafety(intent.getStringExtra(EXTRA_SAFETY_CHECK_ID).orEmpty())
            ACTION_SET_MODE -> {
                val mode = intent.getStringExtra(EXTRA_MODE).orEmpty()
                setMode(mode)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopTracking(sendLeave = true)
        sosTone?.release()
        sosTone = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onLocationChanged(location: Location) {
        val now = System.currentTimeMillis()
        updateStationaryClock(location, now)
        val snapshot = RiderSnapshot.fromLocation(riderId, riderName, location, now, localStationarySinceMs)
        lastOwnSnapshot = snapshot
        safetyRiders[riderId] = snapshot
        RideBus.updateOwnLocation(snapshot)
        evaluateSafetyCheck(now)

        if (!gate.shouldPublish(location, now)) return
        gate.markPublished(location, now)

        publishExecutor?.execute {
            try {
                publishSnapshot(snapshot)
                RideBus.setStatus("Shared location with ${snapshot.accuracyM} m accuracy")
            } catch (error: Exception) {
                RideBus.setStatus("Waiting for Firebase: ${error.message ?: "network error"}")
            }
        }
    }

    override fun onProviderEnabled(provider: String) = Unit

    override fun onProviderDisabled(provider: String) {
        RideBus.setStatus("$provider provider is disabled")
    }

    @Deprecated("Deprecated in Android framework")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    private fun startTracking(intent: Intent) {
        val nextRideId = intent.getStringExtra(EXTRA_RIDE_ID).orEmpty()
        val nextRiderId = intent.getStringExtra(EXTRA_RIDER_ID).orEmpty()
        val nextRiderName = intent.getStringExtra(EXTRA_RIDER_NAME).orEmpty()

        if (nextRideId.isBlank() || nextRiderId.isBlank()) {
            RideBus.setStatus("Missing ride details")
            stopSelf()
            return
        }

        stopTracking(sendLeave = false)
        rideId = nextRideId
        riderId = nextRiderId
        riderName = nextRiderName
        rideRefPath = "rides/$rideId/riders"
        rideRootPath = "rides/$rideId"
        running = true
        publishExecutor = Executors.newSingleThreadExecutor()
        safetyRiders = LinkedHashMap()
        localStationarySinceMs = 0L
        lastPublishedSafetyCheckId = ""
        lastNotifiedSafetyCheckId = ""
        lastServiceSosToneTimestampMs = 0L
        groupEventsStartedAtMs = System.currentTimeMillis()

        startForeground(NOTIFICATION_ID, buildNotification())
        RideBus.setRide(rideId, riderId, riderName)
        startFirebaseListener()
        startGroupEventListeners()
        requestLocationUpdates()
    }

    private fun requestLocationUpdates() {
        if (!hasLocationPermission()) {
            RideBus.setStatus("Location permission is not granted")
            stopSelf()
            return
        }

        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { locationManager.isProviderEnabled(it) }

        if (providers.isEmpty()) {
            RideBus.setStatus("Enable location services to start sharing")
            return
        }

        providers.forEach { provider ->
            locationManager.requestLocationUpdates(provider, SAMPLE_INTERVAL_MS, SAMPLE_DISTANCE_M, this)
            locationManager.getLastKnownLocation(provider)?.let { onLocationChanged(it) }
        }
        RideBus.setStatus("Listening for GPS and Firebase updates")
    }

    private fun startFirebaseListener() {
        val listenersRef = FirebaseDatabase.getInstance().getReference(rideRefPath)
        riderEventsListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                readRiderSnapshot(snapshot)?.let {
                    safetyRiders[it.id] = it
                    if (it.id != riderId) RideBus.updateRider(it)
                    evaluateSafetyCheck(System.currentTimeMillis())
                }
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                readRiderSnapshot(snapshot)?.let {
                    safetyRiders[it.id] = it
                    if (it.id != riderId) RideBus.updateRider(it)
                    evaluateSafetyCheck(System.currentTimeMillis())
                }
            }

            override fun onChildRemoved(snapshot: DataSnapshot) {
                val id = snapshot.key ?: return
                safetyRiders.remove(id)
                RideBus.removeRider(id)
            }

            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) = Unit

            override fun onCancelled(error: DatabaseError) {
                if (running) {
                    RideBus.setStatus("Firebase listener error: ${error.message}")
                }
            }
        }
        listenersRef.addChildEventListener(riderEventsListener as ChildEventListener)
    }

    private fun stopTracking(sendLeave: Boolean) {
        val wasRunning = running
        running = false
        try {
            locationManager.removeUpdates(this)
        } catch (_: Exception) {
        }
        handler.removeCallbacksAndMessages(null)
        val ridersRef = if (rideRefPath.isNotBlank()) FirebaseDatabase.getInstance().getReference(rideRefPath) else null
        riderEventsListener?.let { listener ->
            ridersRef?.removeEventListener(listener)
        }
        riderEventsListener = null
        removeGroupEventListeners()

        val leavingPath = rideRefPath
        val leavingRider = riderId
        val executor = publishExecutor
        executor?.execute {
            if (sendLeave && wasRunning && leavingPath.isNotBlank()) {
                runCatching {
                    FirebaseDatabase.getInstance().getReference(leavingPath).child(leavingRider).removeValue()
                }
            }
        }
        executor?.shutdown()
        publishExecutor = null
        if (wasRunning) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            RideBus.stopRide()
        }
    }

    private fun hasLocationPermission(): Boolean {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Ride tracking",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
        ensureSafetyNotificationChannel(manager)

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ride_notification)
            .setContentTitle("CoRider is active")
            .setContentText("Sharing ride $rideId")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }

    private fun ensureSafetyNotificationChannel(manager: NotificationManager) {
        val channel = NotificationChannel(
            SAFETY_CHANNEL_ID,
            "Ride safety alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Group safety checks and automatic SOS escalation"
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "com.corider.tracker.START"
        const val ACTION_STOP = "com.corider.tracker.STOP"
        const val EXTRA_RIDE_ID = "ride_id"
        const val EXTRA_RIDER_ID = "rider_id"
        const val EXTRA_RIDER_NAME = "rider_name"
        const val ACTION_SOS = "com.corider.tracker.SOS"
        const val ACTION_REGROUP = "com.corider.tracker.REGROUP"
        const val ACTION_CLEAR_REGROUP = "com.corider.tracker.CLEAR_REGROUP"
        const val ACTION_ACK_SAFETY = "com.corider.tracker.ACK_SAFETY"
        const val ACTION_SET_MODE = "com.corider.tracker.SET_MODE"
        const val EXTRA_MODE = "update_mode"
        const val EXTRA_SAFETY_CHECK_ID = "safety_check_id"

        private const val CHANNEL_ID = "ride_tracking"
        private const val SAFETY_CHANNEL_ID = "ride_safety"
        private const val NOTIFICATION_ID = 701
        private const val SAFETY_NOTIFICATION_ID = 702
        private const val SOS_NOTIFICATION_ID = 703
        private const val SAMPLE_INTERVAL_MS = 3_000L
        private const val SAMPLE_DISTANCE_M = 5f
        private const val STATIONARY_SPEED_MPS = 0.8
        private const val STATIONARY_REQUIRED_MS = 10 * 60 * 1000L
        private const val ACK_TIMEOUT_MS = 5 * 60 * 1000L
        private const val GAP_REQUIRED_M = 2_000
    }

    private fun startGroupEventListeners() {
        val root = FirebaseDatabase.getInstance().getReference(rideRootPath)
        alertListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val msg = snapshot.child("message").getValue(String::class.java) ?: return
                val id = snapshot.child("riderId").getValue(String::class.java) ?: return
                val name = snapshot.child("riderName").getValue(String::class.java) ?: "Rider"
                val ts = snapshot.child("timestampMs").getValue(Long::class.java) ?: System.currentTimeMillis()
                val alert = GroupAlert(id, name, msg, ts)
                RideBus.setGroupAlert(alert)
                playServiceSosAlert(alert)
            }
            override fun onCancelled(error: DatabaseError) = Unit
        }
        regroupListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) {
                    RideBus.setRegroupPoint(null)
                    return
                }
                val id = snapshot.child("riderId").getValue(String::class.java) ?: return
                val name = snapshot.child("riderName").getValue(String::class.java) ?: "Rider"
                val lat = snapshot.child("latE7").getValue(Int::class.java) ?: return
                val lon = snapshot.child("lonE7").getValue(Int::class.java) ?: return
                val ts = snapshot.child("timestampMs").getValue(Long::class.java) ?: System.currentTimeMillis()
                RideBus.setRegroupPoint(RegroupPoint(id, name, lat, lon, ts))
            }
            override fun onCancelled(error: DatabaseError) = Unit
        }
        modeListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val value = snapshot.getValue(String::class.java) ?: "NORMAL"
                val mode = runCatching { UpdateMode.valueOf(value) }.getOrDefault(UpdateMode.NORMAL)
                gate.setMode(mode)
                RideBus.setUpdateMode(mode)
            }
            override fun onCancelled(error: DatabaseError) = Unit
        }
        safetyListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) {
                    RideBus.setSafetyCheck(null)
                    return
                }
                val check = readSafetyCheck(snapshot) ?: return
                RideBus.setSafetyCheck(check)
                notifySafetyCheck(check)
                scheduleSafetyEscalation(check)
            }
            override fun onCancelled(error: DatabaseError) = Unit
        }
        root.child("events").child("sos").addValueEventListener(alertListener as ValueEventListener)
        root.child("events").child("regroup").addValueEventListener(regroupListener as ValueEventListener)
        root.child("settings").child("mode").addValueEventListener(modeListener as ValueEventListener)
        root.child("events").child("safetyCheck").addValueEventListener(safetyListener as ValueEventListener)
    }

    private fun removeGroupEventListeners() {
        if (rideRootPath.isBlank()) return
        val root = FirebaseDatabase.getInstance().getReference(rideRootPath)
        alertListener?.let { root.child("events").child("sos").removeEventListener(it) }
        regroupListener?.let { root.child("events").child("regroup").removeEventListener(it) }
        modeListener?.let { root.child("settings").child("mode").removeEventListener(it) }
        safetyListener?.let { root.child("events").child("safetyCheck").removeEventListener(it) }
        alertListener = null
        regroupListener = null
        modeListener = null
        safetyListener = null
    }

    private fun publishSnapshot(snapshot: RiderSnapshot) {
        val payload = mapOf(
            "id" to snapshot.id,
            "name" to snapshot.name,
            "latE7" to snapshot.latE7,
            "lonE7" to snapshot.lonE7,
            "speedCentiMps" to snapshot.speedCentiMps,
            "bearingDeg" to snapshot.bearingDeg,
            "accuracyM" to snapshot.accuracyM,
            "updatedAtMs" to snapshot.updatedAtMs,
            "stationarySinceMs" to snapshot.stationarySinceMs
        )
        FirebaseDatabase.getInstance()
            .getReference(rideRefPath)
            .child(snapshot.id)
            .setValue(payload)
    }

    private fun publishSos() {
        if (!running || rideRootPath.isBlank()) return
        publishSosFor(riderId, riderName, "SOS")
    }

    private fun publishSosFor(alertRiderId: String, alertRiderName: String, message: String) {
        if (!running || rideRootPath.isBlank()) return
        val payload = mapOf(
            "riderId" to alertRiderId,
            "riderName" to alertRiderName,
            "message" to message,
            "timestampMs" to System.currentTimeMillis()
        )
        FirebaseDatabase.getInstance().getReference(rideRootPath).child("events").child("sos").setValue(payload)
    }

    private fun publishRegroup() {
        if (!running || rideRootPath.isBlank()) return
        val own = lastOwnSnapshot ?: return
        val payload = mapOf(
            "riderId" to riderId,
            "riderName" to riderName,
            "latE7" to own.latE7,
            "lonE7" to own.lonE7,
            "timestampMs" to System.currentTimeMillis()
        )
        FirebaseDatabase.getInstance().getReference(rideRootPath).child("events").child("regroup").setValue(payload)
    }

    private fun clearRegroup() {
        if (!running || rideRootPath.isBlank()) return
        FirebaseDatabase.getInstance().getReference(rideRootPath).child("events").child("regroup").removeValue()
        RideBus.setRegroupPoint(null)
    }

    private fun updateStationaryClock(location: Location, nowMs: Long) {
        val speedMps = if (location.hasSpeed()) location.speed.toDouble() else 0.0
        localStationarySinceMs = if (speedMps > STATIONARY_SPEED_MPS) {
            0L
        } else {
            localStationarySinceMs.takeIf { it > 0L } ?: nowMs
        }
    }

    private fun evaluateSafetyCheck(nowMs: Long) {
        if (!running || rideRootPath.isBlank()) return
        val ranked = rankedRiders(nowMs)
        if (ranked.size < 2) return

        val first = ranked.first()
        val last = ranked.last()
        val gapM = first.distanceTo(last).toInt()
        val stoppedLongEnough = last.stationarySinceMs > 0L &&
            nowMs - last.stationarySinceMs >= STATIONARY_REQUIRED_MS
        if (gapM < GAP_REQUIRED_M || !stoppedLongEnough) return

        val checkId = "${last.id}-${last.stationarySinceMs}"
        if (checkId == lastPublishedSafetyCheckId) return
        lastPublishedSafetyCheckId = checkId

        val payload = mapOf(
            "id" to checkId,
            "targetRiderId" to last.id,
            "targetRiderName" to last.label,
            "firstRiderId" to first.id,
            "firstRiderName" to first.label,
            "gapM" to gapM,
            "createdAtMs" to nowMs,
            "dueAtMs" to nowMs + ACK_TIMEOUT_MS,
            "status" to "pending",
            "acknowledgedAtMs" to 0L
        )
        FirebaseDatabase.getInstance()
            .getReference(rideRootPath)
            .child("events")
            .child("safetyCheck")
            .setValue(payload)
    }

    private fun rankedRiders(nowMs: Long): List<RiderSnapshot> {
        val riders = safetyRiders.values.filter { !it.isStale(nowMs) }
        if (riders.size < 2) return riders

        val movingBearing = riders
            .filter { it.bearingDeg in 0..359 && it.speedMps > STATIONARY_SPEED_MPS }
            .map { Math.toRadians(it.bearingDeg.toDouble()) }

        if (movingBearing.isEmpty()) {
            val pair = riders.flatMapIndexed { index, a ->
                riders.drop(index + 1).map { b -> Triple(a, b, a.distanceTo(b)) }
            }.maxByOrNull { it.third } ?: return riders
            return listOf(pair.first, pair.second)
        }

        val x = movingBearing.sumOf { kotlin.math.sin(it) } / movingBearing.size
        val y = movingBearing.sumOf { kotlin.math.cos(it) } / movingBearing.size
        val origin = riders.first()
        return riders.sortedByDescending { rider ->
            val (eastM, northM) = rider.offsetMetersFrom(origin)
            eastM * x + northM * y
        }
    }

    private fun readSafetyCheck(snapshot: DataSnapshot): SafetyCheck? {
        val id = snapshot.child("id").getValue(String::class.java) ?: return null
        val targetId = snapshot.child("targetRiderId").getValue(String::class.java) ?: return null
        val targetName = snapshot.child("targetRiderName").getValue(String::class.java) ?: "Rider"
        val gapM = snapshot.child("gapM").getValue(Int::class.java) ?: 0
        val createdAt = snapshot.child("createdAtMs").getValue(Long::class.java) ?: 0L
        val dueAt = snapshot.child("dueAtMs").getValue(Long::class.java) ?: 0L
        val status = snapshot.child("status").getValue(String::class.java) ?: "pending"
        val acknowledgedAt = snapshot.child("acknowledgedAtMs").getValue(Long::class.java) ?: 0L
        return SafetyCheck(id, targetId, targetName, gapM, createdAt, dueAt, status, acknowledgedAt)
    }

    private fun notifySafetyCheck(check: SafetyCheck) {
        if (check.status != "pending" || check.id == lastNotifiedSafetyCheckId) return
        lastNotifiedSafetyCheckId = check.id

        val manager = getSystemService(NotificationManager::class.java)
        ensureSafetyNotificationChannel(manager)

        val openIntent = PendingIntent.getActivity(
            this,
            SAFETY_NOTIFICATION_ID,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = Notification.Builder(this, SAFETY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ride_notification)
            .setContentTitle("Rider safety check")
            .setContentText("${check.targetRiderName} is ${check.gapM} m behind and not moving")
            .setContentIntent(openIntent)
            .setAutoCancel(true)

        if (check.targetRiderId == riderId) {
            val ackIntent = PendingIntent.getService(
                this,
                SAFETY_NOTIFICATION_ID + 1,
                Intent(this, LiveLocationService::class.java)
                    .setAction(ACTION_ACK_SAFETY)
                    .putExtra(EXTRA_SAFETY_CHECK_ID, check.id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(R.drawable.ic_ride_notification, "I am OK", ackIntent)
        }

        manager.notify(SAFETY_NOTIFICATION_ID, builder.build())
    }

    private fun playServiceSosAlert(alert: GroupAlert) {
        if (alert.timestampMs <= groupEventsStartedAtMs || alert.timestampMs <= lastServiceSosToneTimestampMs) return
        lastServiceSosToneTimestampMs = alert.timestampMs

        val tone = sosTone ?: ToneGenerator(AudioManager.STREAM_ALARM, 100).also { sosTone = it }
        tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 1200)
        handler.postDelayed({
            sosTone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 1200)
        }, 1400L)
        notifySosAlert(alert)
    }

    private fun notifySosAlert(alert: GroupAlert) {
        val manager = getSystemService(NotificationManager::class.java)
        ensureSafetyNotificationChannel(manager)

        val openIntent = PendingIntent.getActivity(
            this,
            SOS_NOTIFICATION_ID,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = Notification.Builder(this, SAFETY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ride_notification)
            .setContentTitle("SOS in $rideId")
            .setContentText("${alert.riderName}: ${alert.message}")
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .build()
        manager.notify(SOS_NOTIFICATION_ID, notification)
    }

    private fun scheduleSafetyEscalation(check: SafetyCheck) {
        if (check.status != "pending") return
        val delayMs = (check.dueAtMs - System.currentTimeMillis()).coerceAtLeast(0L)
        handler.postDelayed({ verifyAndEscalateSafety(check.id) }, delayMs)
    }

    private fun acknowledgeSafety(checkId: String) {
        if (rideRootPath.isBlank() || checkId.isBlank()) return
        FirebaseDatabase.getInstance()
            .getReference(rideRootPath)
            .child("events")
            .child("safetyCheck")
            .updateChildren(
                mapOf(
                    "status" to "acknowledged",
                    "acknowledgedAtMs" to System.currentTimeMillis(),
                    "acknowledgedByRiderId" to riderId,
                    "acknowledgedByRiderName" to riderName
                )
            )
        RideBus.setStatus("Safety check acknowledged")
    }

    private fun verifyAndEscalateSafety(checkId: String) {
        if (rideRootPath.isBlank() || checkId.isBlank()) return
        val checkRef = FirebaseDatabase.getInstance()
            .getReference(rideRootPath)
            .child("events")
            .child("safetyCheck")
        checkRef.get().addOnSuccessListener { snapshot ->
            val check = readSafetyCheck(snapshot) ?: return@addOnSuccessListener
            if (check.id != checkId || check.status != "pending" || System.currentTimeMillis() < check.dueAtMs) {
                return@addOnSuccessListener
            }
            checkRef.updateChildren(
                mapOf(
                    "status" to "escalated",
                    "escalatedAtMs" to System.currentTimeMillis()
                )
            )
            publishSosFor(check.targetRiderId, check.targetRiderName, "Auto SOS: no safety acknowledgement")
        }
    }

    private fun setMode(modeText: String) {
        val mode = runCatching { UpdateMode.valueOf(modeText) }.getOrDefault(UpdateMode.NORMAL)
        gate.setMode(mode)
        FirebaseDatabase.getInstance().getReference(rideRootPath).child("settings").child("mode").setValue(mode.name)
        RideBus.setUpdateMode(mode)
    }

    private fun readRiderSnapshot(snapshot: DataSnapshot): RiderSnapshot? {
        val id = snapshot.child("id").getValue(String::class.java) ?: snapshot.key ?: return null
        val name = snapshot.child("name").getValue(String::class.java) ?: ""
        val latE7 = snapshot.child("latE7").getValue(Int::class.java) ?: return null
        val lonE7 = snapshot.child("lonE7").getValue(Int::class.java) ?: return null
        val speed = snapshot.child("speedCentiMps").getValue(Int::class.java) ?: 0
        val bearing = snapshot.child("bearingDeg").getValue(Int::class.java) ?: -1
        val accuracy = snapshot.child("accuracyM").getValue(Int::class.java) ?: -1
        val updatedAt = snapshot.child("updatedAtMs").getValue(Long::class.java) ?: System.currentTimeMillis()
        val stationarySince = snapshot.child("stationarySinceMs").getValue(Long::class.java) ?: 0L
        return RiderSnapshot(id, name, latE7, lonE7, speed, bearing, accuracy, updatedAt, stationarySince)
    }
}
