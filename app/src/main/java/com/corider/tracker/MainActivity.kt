package com.corider.tracker

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.corider.tracker.location.LiveLocationService
import com.corider.tracker.ui.RadarView
import java.util.Locale
import java.util.UUID

class MainActivity : Activity(), RideBus.Listener {
    private lateinit var rideCodeInput: EditText
    private lateinit var riderNameInput: EditText
    private lateinit var relayUrlInput: EditText
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var statusView: TextView
    private lateinit var ridersView: TextView
    private lateinit var radarView: RadarView

    private val prefs by lazy { getSharedPreferences("ride", Context.MODE_PRIVATE) }
    private val riderId by lazy { getOrCreateRiderId() }
    private var pendingStart = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    override fun onStart() {
        super.onStart()
        RideBus.addListener(this)
    }

    override fun onStop() {
        RideBus.removeListener(this)
        super.onStop()
    }

    override fun onRideStateChanged(state: RideState) {
        runOnUiThread { render(state) }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS && pendingStart) {
            pendingStart = false
            if (hasRequiredPermissions()) {
                startRide()
            } else {
                statusView.text = "Location permission is needed to share your ride."
            }
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(247, 248, 250))
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        val title = TextView(this).apply {
            text = "CoRider"
            textSize = 28f
            setTextColor(Color.rgb(15, 23, 42))
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(title, LinearLayout.LayoutParams.MATCH_PARENT, dp(44))

        rideCodeInput = EditText(this).apply {
            hint = "Ride code"
            setSingleLine(true)
            setText(prefs.getString(KEY_RIDE_CODE, "MORNING-RIDE"))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        root.addView(rideCodeInput, matchWrap())

        riderNameInput = EditText(this).apply {
            hint = "Your name"
            setSingleLine(true)
            setText(prefs.getString(KEY_RIDER_NAME, ""))
        }
        root.addView(riderNameInput, matchWrap())

        relayUrlInput = EditText(this).apply {
            hint = "Relay URL (https://...)"
            setSingleLine(true)
            setText(prefs.getString(KEY_RELAY_URL, DEFAULT_RELAY_URL))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        root.addView(relayUrlInput, matchWrap())

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        startButton = Button(this).apply {
            text = "Start"
            setOnClickListener { requestStart() }
        }
        stopButton = Button(this).apply {
            text = "Stop"
            isEnabled = false
            setOnClickListener { stopRide() }
        }
        controls.addView(startButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        controls.addView(stopButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            leftMargin = dp(8)
        })
        root.addView(controls, matchWrap())

        statusView = TextView(this).apply {
            text = "Ready"
            textSize = 14f
            setTextColor(Color.rgb(51, 65, 85))
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(statusView, matchWrap())

        radarView = RadarView(this)
        root.addView(
            radarView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        ridersView = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.rgb(15, 23, 42))
            setPadding(0, dp(10), 0, 0)
        }
        val scroll = ScrollView(this)
        scroll.addView(ridersView)
        root.addView(scroll, LinearLayout.LayoutParams.MATCH_PARENT, dp(120))

        setContentView(root)
    }

    private fun requestStart() {
        val missingPermissions = permissionsToRequest()
        if (missingPermissions.isNotEmpty()) {
            pendingStart = true
            requestPermissions(missingPermissions, REQUEST_PERMISSIONS)
            return
        }
        startRide()
    }

    private fun startRide() {
        val rideCode = rideCodeInput.text.toString().trim().uppercase(Locale.US)
        val riderName = riderNameInput.text.toString().trim().ifBlank { "Rider" }
        val relayUrl = normalizeRelayUrl(relayUrlInput.text.toString())

        if (rideCode.isBlank()) {
            statusView.text = "Enter a ride code."
            return
        }
        if (!relayUrl.startsWith("https://") && !relayUrl.startsWith("http://")) {
            statusView.text = "Enter your public relay URL."
            return
        }
        relayUrlInput.setText(relayUrl)

        prefs.edit()
            .putString(KEY_RIDE_CODE, rideCode)
            .putString(KEY_RIDER_NAME, riderName)
            .putString(KEY_RELAY_URL, relayUrl)
            .apply()

        val intent = Intent(this, LiveLocationService::class.java)
            .setAction(LiveLocationService.ACTION_START)
            .putExtra(LiveLocationService.EXTRA_RIDE_ID, rideCode)
            .putExtra(LiveLocationService.EXTRA_RIDER_ID, riderId)
            .putExtra(LiveLocationService.EXTRA_RIDER_NAME, riderName)
            .putExtra(LiveLocationService.EXTRA_RELAY_URL, relayUrl)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopRide() {
        val intent = Intent(this, LiveLocationService::class.java)
            .setAction(LiveLocationService.ACTION_STOP)
        startService(intent)
    }

    private fun render(state: RideState) {
        startButton.isEnabled = !state.active
        stopButton.isEnabled = state.active
        statusView.text = locationStatus(state)
        radarView.setState(state)

        val now = System.currentTimeMillis()
        val own = state.ownLocation
        val riders = state.riders.values
            .filter { !it.isStale(now) }
            .sortedBy { if (own == null) 0f else own.distanceTo(it) }

        ridersView.text = if (riders.isEmpty()) {
            "No co-riders visible yet."
        } else {
            riders.joinToString(separator = "\n") { rider ->
                val distance = if (own == null) "" else " - ${own.distanceTo(rider).toInt()} m"
                "${rider.label}$distance - ${rider.ageSeconds(now)}s ago"
            }
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        return hasLocationPermission()
    }

    private fun locationStatus(state: RideState): String {
        val own = state.ownLocation
        val now = System.currentTimeMillis()
        val ownText = if (own == null) {
            "No local GPS fix yet"
        } else {
            "Your GPS ${own.ageSeconds(now)}s ago, ${own.accuracyM} m accuracy"
        }
        return "${state.status}\n$ownText - receiving ${state.riders.size} rider(s)"
    }

    private fun permissionsToRequest(): Array<String> {
        val permissions = mutableListOf<String>()
        if (!hasLocationPermission()) {
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        return permissions.toTypedArray()
    }

    private fun hasLocationPermission(): Boolean {
        return checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun getOrCreateRiderId(): String {
        prefs.getString(KEY_RIDER_ID, null)?.let { return it }
        val id = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_RIDER_ID, id).apply()
        return id
    }

    private fun normalizeRelayUrl(raw: String): String {
        return raw.trim()
            .trimEnd('/')
            .removeSuffix("/healthz")
            .removeSuffix("/")
    }

    private fun matchWrap(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(8)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_PERMISSIONS = 41
        private const val DEFAULT_RELAY_URL = ""
        private const val KEY_RIDE_CODE = "ride_code"
        private const val KEY_RIDER_NAME = "rider_name"
        private const val KEY_RELAY_URL = "relay_url"
        private const val KEY_RIDER_ID = "rider_id"
    }
}
