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
import android.os.Bundle
import android.os.IBinder
import com.corider.tracker.MainActivity
import com.corider.tracker.R
import com.corider.tracker.RideBus
import com.corider.tracker.RiderSnapshot
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class LiveLocationService : Service(), LocationListener {
    private lateinit var locationManager: LocationManager
    private var rideId: String = ""
    private var riderId: String = ""
    private var riderName: String = ""
    private var rideRefPath: String = ""
    private var running = false
    private var publishExecutor: ExecutorService? = null
    private val gate = LocationGate()
    private var riderEventsListener: ChildEventListener? = null

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
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopTracking(sendLeave = true)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onLocationChanged(location: Location) {
        val now = System.currentTimeMillis()
        val snapshot = RiderSnapshot.fromLocation(riderId, riderName, location, now)
        RideBus.updateOwnLocation(snapshot)

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
        rideId = intent.getStringExtra(EXTRA_RIDE_ID).orEmpty()
        riderId = intent.getStringExtra(EXTRA_RIDER_ID).orEmpty()
        riderName = intent.getStringExtra(EXTRA_RIDER_NAME).orEmpty()
        rideRefPath = "rides/$rideId/riders"

        if (rideId.isBlank() || riderId.isBlank()) {
            RideBus.setStatus("Missing ride details")
            stopSelf()
            return
        }

        stopTracking(sendLeave = false)
        running = true
        publishExecutor = Executors.newSingleThreadExecutor()

        startForeground(NOTIFICATION_ID, buildNotification())
        RideBus.setRide(rideId, riderId, riderName)
        startFirebaseListener()
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
                readRiderSnapshot(snapshot)?.let { if (it.id != riderId) RideBus.updateRider(it) }
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                readRiderSnapshot(snapshot)?.let { if (it.id != riderId) RideBus.updateRider(it) }
            }

            override fun onChildRemoved(snapshot: DataSnapshot) {
                val id = snapshot.key ?: return
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
        val ridersRef = if (rideRefPath.isNotBlank()) FirebaseDatabase.getInstance().getReference(rideRefPath) else null
        riderEventsListener?.let { listener ->
            ridersRef?.removeEventListener(listener)
        }
        riderEventsListener = null

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

    companion object {
        const val ACTION_START = "com.corider.tracker.START"
        const val ACTION_STOP = "com.corider.tracker.STOP"
        const val EXTRA_RIDE_ID = "ride_id"
        const val EXTRA_RIDER_ID = "rider_id"
        const val EXTRA_RIDER_NAME = "rider_name"

        private const val CHANNEL_ID = "ride_tracking"
        private const val NOTIFICATION_ID = 701
        private const val SAMPLE_INTERVAL_MS = 2_000L
        private const val SAMPLE_DISTANCE_M = 3f
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
            "updatedAtMs" to snapshot.updatedAtMs
        )
        FirebaseDatabase.getInstance()
            .getReference(rideRefPath)
            .child(snapshot.id)
            .setValue(payload)
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
        return RiderSnapshot(id, name, latE7, lonE7, speed, bearing, accuracy, updatedAt)
    }
}
