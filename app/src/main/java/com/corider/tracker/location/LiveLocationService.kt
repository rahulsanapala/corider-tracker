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
import com.corider.tracker.net.CompactLocationPayload
import com.corider.tracker.net.RideApi
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.min

class LiveLocationService : Service(), LocationListener {
    private lateinit var locationManager: LocationManager
    private var rideId: String = ""
    private var riderId: String = ""
    private var riderName: String = ""
    private var relayUrl: String = ""
    private var api: RideApi? = null
    private var running = false
    private var streamThread: Thread? = null
    private var publishExecutor: ExecutorService? = null
    private val gate = LocationGate()

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

        val payload = CompactLocationPayload.fromSnapshot(snapshot)
        publishExecutor?.execute {
            val client = api ?: return@execute
            try {
                client.publishLocation(rideId, payload)
                RideBus.setStatus("Shared location with ${payload.accuracyM} m accuracy")
            } catch (error: Exception) {
                RideBus.setStatus("Waiting for relay: ${error.message ?: "network error"}")
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
        relayUrl = intent.getStringExtra(EXTRA_RELAY_URL).orEmpty()

        if (rideId.isBlank() || riderId.isBlank() || relayUrl.isBlank()) {
            RideBus.setStatus("Missing ride details")
            stopSelf()
            return
        }

        stopTracking(sendLeave = false)
        running = true
        api = RideApi(relayUrl)
        publishExecutor = Executors.newSingleThreadExecutor()

        startForeground(NOTIFICATION_ID, buildNotification())
        RideBus.setRide(rideId, riderId, riderName)
        startEventStream()
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
        RideBus.setStatus("Listening for GPS and relay updates")
    }

    private fun startEventStream() {
        streamThread = Thread {
            var backoffMs = 1_000L
            while (running) {
                try {
                    api?.streamRide(
                        rideId = rideId,
                        riderId = riderId,
                        onLocation = { payload -> RideBus.updateRider(payload.toSnapshot()) },
                        onLeft = { id -> RideBus.removeRider(id) },
                        shouldContinue = { running }
                    )
                    backoffMs = 1_000L
                } catch (error: Exception) {
                    if (running) {
                        RideBus.setStatus("Relay reconnecting: ${error.message ?: "stream closed"}")
                        Thread.sleep(backoffMs)
                        backoffMs = min(backoffMs * 2, 15_000L)
                    }
                }
            }
        }.apply {
            name = "ride-event-stream"
            isDaemon = true
            start()
        }
    }

    private fun stopTracking(sendLeave: Boolean) {
        val wasRunning = running
        running = false
        try {
            locationManager.removeUpdates(this)
        } catch (_: Exception) {
        }
        streamThread?.interrupt()
        streamThread = null

        val client = api
        val leavingRide = rideId
        val leavingRider = riderId
        val executor = publishExecutor
        executor?.execute {
            if (sendLeave && wasRunning && client != null && leavingRide.isNotBlank()) {
                runCatching { client.leaveRide(leavingRide, leavingRider) }
            }
        }
        executor?.shutdown()
        publishExecutor = null
        api = null
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
        const val EXTRA_RELAY_URL = "relay_url"

        private const val CHANNEL_ID = "ride_tracking"
        private const val NOTIFICATION_ID = 701
        private const val SAMPLE_INTERVAL_MS = 2_000L
        private const val SAMPLE_DISTANCE_M = 3f
    }
}
