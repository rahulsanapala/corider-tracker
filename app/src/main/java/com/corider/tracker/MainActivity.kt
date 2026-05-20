package com.corider.tracker

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialManagerCallback
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialException
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.corider.tracker.location.LiveLocationService
import com.corider.tracker.navigation.MapNavigationClient
import com.corider.tracker.navigation.PlaceSearchResult
import com.corider.tracker.navigation.RouteResult
import com.corider.tracker.ui.LiveMapView
import com.corider.tracker.voice.AgoraWalkieTalkie
import com.corider.tracker.voice.WalkieForegroundService
import com.corider.tracker.voice.WalkieTalkieSession
import com.google.android.material.button.MaterialButton
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.roundToInt
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView as OsmMapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker

class MainActivity : Activity(), RideBus.Listener {
    private lateinit var rideCodeInput: EditText
    private lateinit var profileNameInput: EditText
    private lateinit var profileContactInput: EditText
    private lateinit var profileDobInput: EditText
    private lateinit var profileBloodInput: Spinner
    private lateinit var profileBikeInput: EditText
    private lateinit var profileEmergencyInput: EditText
    private lateinit var profileTitleView: TextView
    private lateinit var profileSubtitleView: TextView
    private lateinit var googleAuthStatusView: TextView
    private lateinit var googleAuthButton: Button
    private lateinit var batteryStatusView: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var statusView: TextView
    private lateinit var topBar: LinearLayout
    private lateinit var topBarBackButton: ImageButton
    private lateinit var pageTitle: TextView
    private lateinit var setupPanel: LinearLayout

    private lateinit var sosButton: Button
    private lateinit var regroupButton: Button
    private lateinit var sosSafeButton: ImageButton
    private lateinit var mapSearchPanel: LinearLayout
    private lateinit var destinationSearchInput: EditText
    private lateinit var routeInfoPill: TextView
    private lateinit var clearRouteButton: ImageButton
    private lateinit var modeEcoButton: TextView
    private lateinit var modeNormalButton: TextView
    private lateinit var modeFastButton: TextView
    private lateinit var walkieButton: Button
    private lateinit var walkieEndButton: ImageButton
    private lateinit var walkieStatusView: TextView

    private lateinit var livePill: TextView
    private lateinit var onlinePill: TextView
    private lateinit var ridersMiniView: TextView
    private lateinit var riderDetailCard: FrameLayout
    private lateinit var riderDetailText: TextView
    private lateinit var mapView: LiveMapView
    private lateinit var mapPage: FrameLayout
    private lateinit var groupPage: SwipeRefreshLayout
    private lateinit var groupScroll: ScrollView
    private lateinit var groupContent: LinearLayout
    private lateinit var groupResultsContent: LinearLayout
    private lateinit var eventsPage: SwipeRefreshLayout
    private lateinit var eventsScroll: ScrollView
    private lateinit var eventsContent: LinearLayout
    private lateinit var eventNameInput: EditText
    private lateinit var eventTimeInput: EditText
    private lateinit var eventLocationInput: EditText
    private lateinit var eventDescriptionInput: EditText
    private lateinit var profilePage: ScrollView

    private lateinit var totalRidersCard: TextView
    private lateinit var movingRidersCard: TextView
    private lateinit var staleRidersCard: TextView
    private lateinit var maxGapCard: TextView
    private lateinit var riderRankingView: TextView
    private lateinit var sosEventCard: TextView
    private lateinit var regroupEventCard: TextView
    private lateinit var safetyEventCard: TextView

    private lateinit var bottomNav: BottomNavigationView

    private val prefs by lazy { getSharedPreferences("ride", Context.MODE_PRIVATE) }
    private val riderId: String get() = currentRiderIdentityId()
    private val walkieTalkie by lazy { WalkieTalkieSession.get(this) }
    private var pendingStart = false
    private var pendingWalkieGroup: String? = null
    private var selectedTab = "map"
    private var selectedGroupCode: String? = null
    private var selectedMapRiderId: String? = null
    private var profileEditMode = false
    private var groupSearchQuery = ""
    private val groupMemberCounts = mutableMapOf<String, Int>()
    private val groupMemberCountLoading = mutableSetOf<String>()
    private val hideRiderDetailRunnable = Runnable { hideRiderDetail() }
    private val previewLocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            showPreviewLocation(location)
        }
    }
    private var updatingBottomNav = false
    private var currentState = RideState()
    private var rideActive = false
    private var hasRegroupPoint = false
    private var previewLocationPermissionAsked = false
    private var previewSnapshot: RiderSnapshot? = null
    private var visibleSosAlertTimestampMs = 0L
    private var dismissedSosAlertTimestampMs = 0L
    private val adminGroups = mutableSetOf<String>()
    private val adminRidersByGroup = mutableMapOf<String, MutableSet<String>>()
    private val adminRoleLoading = mutableSetOf<String>()
    private val adminRoleFetchedAtMs = mutableMapOf<String, Long>()
    private var selectedRiderActionId: String? = null
    private val mapNavigationClient = MapNavigationClient()
    private val mapNavigationExecutor = Executors.newSingleThreadExecutor()
    private var selectedDestination: PlaceSearchResult? = null
    private var pendingRouteDestination: PlaceSearchResult? = null
    private var mapNavigationRequestId = 0
    private var routeInProgress = false
    private var globalEventsListener: ValueEventListener? = null
    private val globalBikeEvents = LinkedHashMap<String, BikeEvent>()
    private var eventCreateExpanded = false
    private var selectedEventStartAtMs = 0L
    private var selectedEventLocationName = ""
    private var selectedEventLatE7: Int? = null
    private var selectedEventLonE7: Int? = null
    private var eventLocationRequestId = 0
    private lateinit var credentialManager: CredentialManager
    private val mainThreadExecutor = Executor { command -> runOnUiThread(command) }

    override fun onCreate(savedInstanceState: Bundle?) {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        super.onCreate(savedInstanceState)
        credentialManager = CredentialManager.create(this)
        resetLegacyIdentityDataIfNeeded()
        adminGroups.addAll(loadLocalAdminGroups())
        walkieTalkie.onStateChanged = { state ->
            syncWalkieForeground(state)
            runOnUiThread { renderWalkieState(state) }
        }
        buildUi()
        startGlobalEventsListener()
        handleJoinIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleJoinIntent(intent)
    }

    override fun onBackPressed() {
        if (selectedTab == "group" && selectedGroupCode != null) {
            renderGroupList()
            return
        }
        super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        if (::mapView.isInitialized) mapView.onResume()
        if (selectedTab == "profile") updateBatteryStatus()
        refreshActiveTracking()
        requestPreviewLocationIfNeeded()
    }

    override fun onStart() {
        super.onStart()
        RideBus.addListener(this)
    }

    override fun onStop() {
        RideBus.removeListener(this)
        super.onStop()
    }

    override fun onPause() {
        stopPreviewLocation()
        if (::mapView.isInitialized) mapView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        stopPreviewLocation()
        stopGlobalEventsListener()
        if (::mapPage.isInitialized) mapPage.removeCallbacks(hideRiderDetailRunnable)
        walkieTalkie.onStateChanged = null
        WalkieTalkieSession.releaseIfIdle(this)
        if (::mapView.isInitialized) mapView.onDestroy()
        mapNavigationExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (::mapView.isInitialized) mapView.onLowMemory()
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
        } else if (requestCode == REQUEST_AUDIO) {
            val groupCode = pendingWalkieGroup
            pendingWalkieGroup = null
            if (groupCode != null && hasAudioPermission()) {
                joinWalkieTalkie(groupCode)
            } else if (groupCode != null) {
                statusView.text = "Microphone permission is needed for walkie talkie."
            }
        } else if (requestCode == REQUEST_PREVIEW_LOCATION && hasLocationPermission()) {
            requestPreviewLocationIfNeeded()
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(SURFACE)
        }
        applySystemBars(root)

        topBar = buildTopBar()
        root.addView(topBar, LinearLayout.LayoutParams.MATCH_PARENT, dp(64))

        val contentFrame = FrameLayout(this).apply {
            setBackgroundColor(SURFACE)
        }
        mapPage = buildMapPage()
        groupPage = buildGroupPage()
        eventsPage = buildEventsPage()
        profilePage = buildProfilePage()
        contentFrame.addView(mapPage, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        contentFrame.addView(groupPage, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        contentFrame.addView(eventsPage, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        contentFrame.addView(profilePage, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        root.addView(contentFrame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(buildBottomNav(), LinearLayout.LayoutParams.MATCH_PARENT, dp(64))

        setContentView(root)
        switchTab("map")
    }

    private fun buildTopBar(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
            setBackgroundColor(TOP_BAR)
            elevation = dp(6).toFloat()

            val leftSlot = FrameLayout(this@MainActivity)
            topBarBackButton = ImageButton(this@MainActivity).apply {
                contentDescription = "Back to groups"
                setImageResource(R.drawable.ic_back)
                setColorFilter(Color.WHITE)
                scaleType = ImageView.ScaleType.CENTER
                setPadding(dp(9), dp(9), dp(9), dp(9))
                background = oval(Color.argb(42, 255, 255, 255), stroke = Color.argb(90, 255, 255, 255), strokeWidth = 1)
                visibility = View.GONE
                isClickable = true
                isFocusable = true
                setOnClickListener { renderGroupList() }
            }
            leftSlot.addView(
                topBarBackButton,
                FrameLayout.LayoutParams(dp(38), dp(38)).apply {
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                }
            )
            pageTitle = TextView(this@MainActivity).apply {
                text = "Map"
                textSize = 19f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
            }
            val alert = TextView(this@MainActivity)

            addView(leftSlot, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            addView(pageTitle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f))
            addView(alert, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        }
    }

    private fun buildMapPage(): FrameLayout {
        mapView = LiveMapView(this).apply {
            onCreate()
            onSosMarkerClick = { alert ->
                showSosAlertDetail(currentState, alert, System.currentTimeMillis(), force = true)
                focusOnSos()
            }
            onEventMarkerClick = { event ->
                openEventDetailsFromMap(event)
            }
        }

        return FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(mapView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            mapSearchPanel = buildMapSearchPanel()
            addView(
                mapSearchPanel,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.TOP
                    leftMargin = dp(18)
                    rightMargin = dp(18)
                    topMargin = dp(22)
                }
            )

            livePill = TextView(this@MainActivity).apply {
                text = "Live  Ready"
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                setPadding(dp(14), dp(9), dp(14), dp(9))
                background = statusPill(active = false)
                visibility = View.GONE
                setOnClickListener { openActiveGroupFromMap() }
            }
            addView(livePill, overlayParams(Gravity.TOP or Gravity.START, left = 18, top = 136))

            sosSafeButton = ImageButton(this@MainActivity).apply {
                contentDescription = "Mark safe and clear SOS"
                setImageResource(R.drawable.ic_safe_shield)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(dp(4), dp(4), dp(4), dp(4))
                setBackgroundColor(Color.TRANSPARENT)
                elevation = dp(8).toFloat()
                visibility = View.GONE
                setOnClickListener {
                    dispatchServiceAction(LiveLocationService.ACTION_CLEAR_SOS)
                    dismissedSosAlertTimestampMs = currentState.groupAlert?.timestampMs ?: dismissedSosAlertTimestampMs
                    visibleSosAlertTimestampMs = 0L
                    hideRiderDetail()
                    statusView.text = "Marked safe. SOS cleared."
                }
            }
            val safeParams = overlayParams(Gravity.TOP or Gravity.END, right = 22, top = 160, width = 58, height = 58)
            addView(sosSafeButton, safeParams)
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                val top = (height / 4).coerceAtLeast(dp(96))
                (sosSafeButton.layoutParams as FrameLayout.LayoutParams).apply {
                    topMargin = top
                    sosSafeButton.layoutParams = this
                }
            }

            setupPanel = buildSetupPanel()
            setupPanel.visibility = View.GONE
            addView(
                setupPanel,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.TOP
                    leftMargin = dp(16)
                    rightMargin = dp(16)
                    topMargin = dp(78)
                }
            )

            val actions = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(dp(22), 0, dp(22), 0)
            }
            sosButton = mapActionButton("SOS", R.drawable.ic_sos_alert).apply {
                setOnClickListener {
                    if (!rideActive) {
                        statusView.text = "Make a group active before sending SOS."
                    } else {
                        dispatchServiceAction(LiveLocationService.ACTION_SOS)
                    }
                }
            }
            styleSosButton(active = false)
            regroupButton = mapActionButton("REGROUP", R.drawable.ic_riders).apply {
                setOnClickListener {
                    if (!rideActive) {
                        statusView.text = "Make a group active before regroup."
                        return@setOnClickListener
                    }
                    if (!isAdminForGroup(currentState.rideId)) {
                        statusView.text = "Only group admin can regroup riders."
                        return@setOnClickListener
                    }
                    if (currentState.ownLocation == null && !hasRegroupPoint) {
                        statusView.text = "Waiting for your location before regroup."
                        return@setOnClickListener
                    }
                    if (hasRegroupPoint) {
                        dispatchServiceAction(LiveLocationService.ACTION_CLEAR_REGROUP)
                    } else {
                        dispatchServiceAction(LiveLocationService.ACTION_REGROUP)
                    }
                }
            }
            styleRegroupButton(active = false, hasPoint = false)
            actions.addView(sosButton, LinearLayout.LayoutParams(0, dp(76), 1f))
            actions.addView(regroupButton, LinearLayout.LayoutParams(0, dp(76), 1f).apply { leftMargin = dp(12) })
            addView(actions, overlayParams(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, bottom = 96))

            onlinePill = TextView(this@MainActivity).apply {
                text = "Online\n0 riders online"
                textSize = 14f
                setTextColor(Color.WHITE)
                setPadding(dp(14), dp(10), dp(16), dp(10))
                background = rounded(PILL, dp(8), stroke = CARD_STROKE)
                elevation = dp(8).toFloat()
            }
            addView(onlinePill, overlayParams(Gravity.BOTTOM or Gravity.START, left = 22, bottom = 18))

            val center = ImageButton(this@MainActivity).apply {
                contentDescription = "Center map"
                setImageResource(R.drawable.ic_center_location)
                setColorFilter(Color.WHITE)
                scaleType = ImageView.ScaleType.CENTER
                setPadding(dp(14), dp(14), dp(14), dp(14))
                background = oval(PILL, stroke = CARD_STROKE)
                elevation = dp(8).toFloat()
                setOnClickListener { mapView.centerOnMe() }
            }
            addView(center, overlayParams(Gravity.BOTTOM or Gravity.END, right = 22, bottom = 22, width = 58, height = 58))

            ridersMiniView = TextView(this@MainActivity).apply {
                text = ""
                textSize = 13f
                setTextColor(Color.rgb(226, 232, 240))
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = rounded(PILL, dp(8), stroke = CARD_STROKE)
                elevation = dp(8).toFloat()
            }
            addView(ridersMiniView, overlayParams(Gravity.TOP or Gravity.START, left = 22, top = 72))

            riderDetailCard = FrameLayout(this@MainActivity).apply {
                background = rounded(Color.rgb(219, 239, 255), dp(8), stroke = Color.BLACK, strokeWidth = 1)
                elevation = dp(10).toFloat()
                visibility = View.GONE
                clipChildren = false
                clipToPadding = false
            }
            riderDetailText = TextView(this@MainActivity).apply {
                text = ""
                textSize = 14f
                setTextColor(Color.rgb(15, 23, 42))
                setPadding(dp(14), dp(13), dp(46), dp(13))
                setLineSpacing(dp(2).toFloat(), 1.0f)
                maxWidth = resources.displayMetrics.widthPixels - dp(72)
            }
            riderDetailCard.addView(
                riderDetailText,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            )
            riderDetailCard.addView(
                TextView(this@MainActivity).apply {
                    text = "x"
                    textSize = 16f
                    typeface = Typeface.DEFAULT
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    background = oval(Color.rgb(190, 190, 190), stroke = Color.WHITE, strokeWidth = 1)
                    elevation = dp(4).toFloat()
                    setOnClickListener { hideRiderDetail() }
                },
                FrameLayout.LayoutParams(dp(30), dp(30)).apply {
                    gravity = Gravity.TOP or Gravity.END
                    topMargin = -dp(8)
                    rightMargin = -dp(8)
                }
            )
            addView(
                riderDetailCard,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.TOP or Gravity.START
                    leftMargin = dp(22)
                    topMargin = dp(188)
                }
            )
            mapSearchPanel.bringToFront()
        }
    }

    private fun buildMapSearchPanel(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            val searchRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, dp(8), 0)
                background = rounded(Color.rgb(255, 255, 255), dp(26), stroke = Color.rgb(15, 23, 42))
                elevation = dp(12).toFloat()
            }

            searchRow.addView(
                ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_search)
                    setColorFilter(Color.rgb(71, 85, 105))
                    scaleType = ImageView.ScaleType.CENTER
                },
                LinearLayout.LayoutParams(dp(24), dp(52))
            )

            destinationSearchInput = EditText(this@MainActivity).apply {
                hint = "Search destination"
                setHintTextColor(Color.rgb(100, 116, 139))
                setTextColor(Color.rgb(15, 23, 42))
                textSize = 15f
                setSingleLine(true)
                background = null
                inputType = InputType.TYPE_CLASS_TEXT
                imeOptions = EditorInfo.IME_ACTION_SEARCH
                setPadding(dp(10), 0, dp(8), 0)
                setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                        searchMapDestination()
                        true
                    } else {
                        false
                    }
                }
            }
            searchRow.addView(destinationSearchInput, LinearLayout.LayoutParams(0, dp(52), 1f))

            clearRouteButton = ImageButton(this@MainActivity).apply {
                contentDescription = "Clear directions"
                setImageResource(R.drawable.ic_close)
                setColorFilter(Color.rgb(71, 85, 105))
                scaleType = ImageView.ScaleType.CENTER
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = oval(Color.TRANSPARENT)
                visibility = View.GONE
                setOnClickListener { clearMapDirections() }
            }
            searchRow.addView(clearRouteButton, LinearLayout.LayoutParams(dp(44), dp(52)))

            searchRow.addView(
                ImageButton(this@MainActivity).apply {
                    contentDescription = "Search destination"
                    setImageResource(R.drawable.ic_directions)
                    setColorFilter(Color.WHITE)
                    scaleType = ImageView.ScaleType.CENTER
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                    background = oval(BLUE)
                    setOnClickListener { searchMapDestination() }
                },
                LinearLayout.LayoutParams(dp(42), dp(42))
            )

            addView(searchRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)))

            routeInfoPill = TextView(this@MainActivity).apply {
                text = ""
                textSize = 13f
                setTextColor(Color.rgb(15, 23, 42))
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = rounded(Color.rgb(239, 246, 255), dp(12), stroke = Color.rgb(37, 99, 235))
                elevation = dp(8).toFloat()
                visibility = View.GONE
                setOnClickListener {
                    selectedDestination?.let { requestRouteToDestination(it) }
                }
            }
            addView(routeInfoPill, matchWrap(top = 8))
        }
    }

    private fun buildSetupPanel(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = rounded(PANEL, dp(8), stroke = CARD_STROKE)
            elevation = dp(10).toFloat()

            addView(sectionTitle("Ride Status"), matchWrapNoMargin())

            rideCodeInput = input("Ride code", prefs.getString(KEY_RIDE_CODE, "MORNING-RIDE").orEmpty()).apply {
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                visibility = View.GONE
            }
            addView(rideCodeInput, matchWrapNoMargin())

            startButton = smallCommand("START", BLUE).apply {
                visibility = View.GONE
                setOnClickListener { requestStart() }
            }
            stopButton = smallCommand("STOP", PILL).apply {
                visibility = View.GONE
                isEnabled = false
                setOnClickListener { stopRide() }
            }
            addView(startButton, matchWrapNoMargin())
            addView(stopButton, matchWrapNoMargin())

            statusView = TextView(this@MainActivity).apply {
                text = "Ready"
                textSize = 13f
                setTextColor(MUTED)
                setPadding(0, dp(8), 0, 0)
            }
            addView(statusView, matchWrapNoMargin())
        }
    }

    private fun buildGroupPage(): SwipeRefreshLayout {
        groupContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(24))
            setBackgroundColor(SURFACE)
        }
        renderGroupList()

        groupScroll = ScrollView(this).apply {
            setBackgroundColor(SURFACE)
            clipToPadding = false
            isFillViewport = true
            attachGroupSwipeBack(this)
            addView(groupContent)
        }

        return SwipeRefreshLayout(this).apply {
            setColorSchemeColors(BLUE, GREEN, AMBER)
            setProgressBackgroundColorSchemeColor(PANEL)
            setOnRefreshListener { refreshGroupPage() }
            addView(groupScroll, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }

    private fun attachGroupSwipeBack(view: View) {
        var downX = 0f
        var downY = 0f
        view.setOnTouchListener { touchedView, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                }
                MotionEvent.ACTION_UP -> {
                    touchedView.performClick()
                    val deltaX = event.x - downX
                    val deltaY = kotlin.math.abs(event.y - downY)
                    if (selectedGroupCode != null && deltaX > dp(84) && deltaY < dp(56)) {
                        renderGroupList()
                    }
                }
            }
            false
        }
    }

    private fun refreshGroupPage() {
        syncActiveRiderName()
        selectedGroupCode?.let { code ->
            refreshGroupAdmins(code, force = true)
            if (loadGroups().any { it.code == code }) {
                renderGroupDetail(code)
            } else {
                renderGroupList()
            }
        } ?: renderGroupList()
        statusView.text = "Group refreshed."
        if (::groupPage.isInitialized) {
            groupPage.post { groupPage.isRefreshing = false }
        }
    }

    private fun buildEventsPage(): SwipeRefreshLayout {
        eventsContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(24))
            setBackgroundColor(SURFACE)
        }
        renderEventsPage()

        eventsScroll = ScrollView(this).apply {
            setBackgroundColor(SURFACE)
            clipToPadding = false
            isFillViewport = true
            addView(eventsContent)
        }

        return SwipeRefreshLayout(this).apply {
            setColorSchemeColors(BLUE, GREEN, AMBER)
            setProgressBackgroundColorSchemeColor(PANEL)
            setOnRefreshListener { refreshEventsPage() }
            addView(eventsScroll, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }

    private fun refreshEventsPage() {
        renderEventsPage()
        withFirebaseAuth {
            globalEventsRef().get()
                .addOnSuccessListener { snapshot ->
                    applyGlobalEventsSnapshot(snapshot)
                    statusView.text = "Events refreshed."
                }
                .addOnFailureListener { error ->
                    statusView.text = "Events refresh failed: ${error.message ?: "network error"}"
                }
                .addOnCompleteListener {
                    if (::eventsPage.isInitialized) eventsPage.post { eventsPage.isRefreshing = false }
                }
        }
    }

    private fun renderEventsPage() {
        if (!::eventsContent.isInitialized) return
        eventsContent.removeAllViews()

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = gradientRounded(Color.rgb(88, 28, 135), Color.rgb(14, 116, 144), dp(18), stroke = Color.rgb(147, 197, 253))
            elevation = dp(5).toFloat()
        }
        hero.addView(TextView(this).apply {
            text = "Global Bike Events"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }, matchWrapNoMargin())
        hero.addView(TextView(this).apply {
            text = "Create rides, meetups, and bike events for everyone using CoRider."
            textSize = 13f
            setTextColor(Color.rgb(219, 234, 254))
            setPadding(0, dp(6), 0, 0)
            setLineSpacing(dp(2).toFloat(), 1.0f)
        }, matchWrapNoMargin())
        eventsContent.addView(hero, matchWrapNoMargin())
        eventsContent.addView(createEventPanel(), matchWrap(top = 16))

        val events = globalBikeEvents.values.sortedWith(
            compareBy<BikeEvent> { event -> event.startAtMs.takeIf { it > 0L } ?: Long.MAX_VALUE }
                .thenByDescending { it.createdAtMs }
        )
        if (events.isEmpty()) {
            val empty = panel()
            empty.addView(sectionTitle("NO EVENTS YET"), matchWrapNoMargin())
            empty.addView(bodyText("Create the first ride or meetup. Event pins will appear on the map in Preview / Only you mode."), matchWrap(top = 8))
            eventsContent.addView(empty, matchWrap(top = 16))
            return
        }

        eventsContent.addView(sectionHeaderWithIcon(R.drawable.ic_nav_events, AMBER, "UPCOMING & COMMUNITY EVENTS"), matchWrap(top = 18))
        events.forEach { event ->
            eventsContent.addView(bikeEventCard(event), matchWrap(top = 12))
        }
    }

    private fun createEventPanel(): LinearLayout {
        val panel = panel()
        if (!eventCreateExpanded) {
            panel.addView(smallCommand("CREATE EVENT", BLUE).apply {
                setOnClickListener {
                    eventCreateExpanded = true
                    renderEventsPage()
                    if (::eventsScroll.isInitialized) eventsScroll.post { eventsScroll.smoothScrollTo(0, 0) }
                }
            }, matchWrapNoMargin())
            return panel
        }

        panel.addView(sectionTitle("CREATE EVENT DETAILS"), matchWrapNoMargin())
        eventNameInput = input("Event name", "").apply {
            filters = arrayOf(InputFilter.LengthFilter(60))
        }
        eventTimeInput = input("Start date & time", "").apply {
            inputType = InputType.TYPE_NULL
            keyListener = null
            isFocusable = false
            isCursorVisible = false
            isClickable = true
            setOnClickListener { showEventStartPicker() }
        }
        eventLocationInput = input("Add location", selectedEventLocationName).apply {
            inputType = InputType.TYPE_NULL
            keyListener = null
            isFocusable = false
            isCursorVisible = false
            isClickable = true
            setOnClickListener { showEventLocationPicker() }
        }
        eventDescriptionInput = EditText(this).apply {
            hint = "Description"
            setHintTextColor(MUTED)
            setTextColor(Color.WHITE)
            textSize = 14f
            minLines = 3
            maxLines = 5
            gravity = Gravity.TOP
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(INPUT, dp(8), stroke = CARD_STROKE)
            filters = arrayOf(InputFilter.LengthFilter(240))
        }
        panel.addView(eventNameInput, matchWrap(top = 12))
        panel.addView(eventTimeInput, matchWrap(top = 10))
        panel.addView(eventLocationInput, matchWrap(top = 10))
        panel.addView(eventDescriptionInput, matchWrap(top = 10))
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        actions.addView(smallCommand("CANCEL", PILL).apply {
            setOnClickListener {
                resetEventCreateForm()
                eventCreateExpanded = false
                renderEventsPage()
            }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        actions.addView(smallCommand("PUBLISH", BLUE).apply {
            setOnClickListener { createGlobalEvent() }
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(10) })
        panel.addView(actions, matchWrap(top = 12))
        return panel
    }

    private fun bikeEventCard(event: BikeEvent): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = gradientRounded(eventGradientStart(event.id), eventGradientEnd(event.id), dp(16), stroke = Color.argb(120, 255, 255, 255))
            elevation = dp(6).toFloat()
            isClickable = true
            setOnClickListener { showEventDetailsDialog(event) }

            val topRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val titleColumn = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = event.name
                    textSize = 20f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    maxLines = 2
                }, matchWrapNoMargin())
                addView(TextView(this@MainActivity).apply {
                    text = event.locationName.ifBlank { event.creatorName.ifBlank { "Event location" } }
                    textSize = 12f
                    setTextColor(Color.rgb(226, 232, 240))
                    setPadding(0, dp(3), 0, 0)
                    maxLines = 2
                }, matchWrapNoMargin())
            }
            topRow.addView(titleColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            topRow.addView(TextView(this@MainActivity).apply {
                text = event.eventTime.ifBlank { "TIME" }
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(15, 23, 42))
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = rounded(Color.WHITE, dp(10))
                maxLines = 2
            }, LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(12) })
            addView(topRow, matchWrapNoMargin())

            addView(TextView(this@MainActivity).apply {
                text = event.description.ifBlank { "No description added." }
                textSize = 14f
                setTextColor(Color.rgb(248, 250, 252))
                setPadding(0, dp(14), 0, 0)
                setLineSpacing(dp(2).toFloat(), 1.0f)
                maxLines = 4
            }, matchWrapNoMargin())

            addView(TextView(this@MainActivity).apply {
                text = "VIEW DETAILS"
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(15, 23, 42))
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = rounded(Color.WHITE, dp(12))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
        }
    }

    private fun createGlobalEvent() {
        if (!requireGoogleIdentity("create events")) return
        val name = eventNameInput.text.toString().trim()
        val startAtMs = selectedEventStartAtMs
        val time = if (startAtMs > 0L) formatEventStartTime(startAtMs) else eventTimeInput.text.toString().trim()
        val description = eventDescriptionInput.text.toString().trim()
        val latE7 = selectedEventLatE7
        val lonE7 = selectedEventLonE7

        if (name.isBlank()) {
            eventNameInput.error = "Event name is required"
            statusView.text = "Enter event name."
            return
        }
        if (startAtMs <= 0L || time.isBlank()) {
            eventTimeInput.error = "Select start date and time"
            statusView.text = "Select event start date and time."
            return
        }
        if (startAtMs < System.currentTimeMillis() - 60_000L) {
            eventTimeInput.error = "Start time cannot be in the past"
            statusView.text = "Select a future event start time."
            return
        }
        if (selectedEventLocationName.isBlank() || latE7 == null || lonE7 == null) {
            eventLocationInput.error = "Add event location"
            statusView.text = "Add event location."
            return
        }
        if (description.isBlank()) {
            eventDescriptionInput.error = "Description is required"
            statusView.text = "Enter event description."
            return
        }

        withFirebaseAuth {
            val authUser = FirebaseAuth.getInstance().currentUser
            val creatorId = currentGoogleUid().orEmpty()
            val creatorEmail = authUser?.email.orEmpty().trim().lowercase(Locale.US)
            val id = "EVT-${UUID.randomUUID().toString().take(8).uppercase(Locale.US)}"
            val event = BikeEvent(
                id = id,
                name = name,
                eventTime = time,
                startAtMs = startAtMs,
                locationName = selectedEventLocationName,
                description = description,
                latE7 = latE7,
                lonE7 = lonE7,
                creatorName = profileName(),
                createdAtMs = System.currentTimeMillis(),
                creatorId = creatorId,
                creatorEmail = creatorEmail
            )
            globalEventsRef().child(id).setValue(
                mapOf(
                    "id" to event.id,
                    "name" to event.name,
                    "eventTime" to event.eventTime,
                    "startAtMs" to event.startAtMs,
                    "locationName" to event.locationName,
                    "description" to event.description,
                    "latE7" to event.latE7,
                    "lonE7" to event.lonE7,
                    "creatorName" to event.creatorName,
                    "creatorId" to event.creatorId,
                    "creatorUid" to event.creatorId,
                    "localRiderId" to riderId,
                    "creatorEmail" to event.creatorEmail,
                    "createdAtMs" to event.createdAtMs
                )
            ).addOnSuccessListener {
                eventNameInput.text?.clear()
                eventTimeInput.text?.clear()
                eventLocationInput.text?.clear()
                eventDescriptionInput.text?.clear()
                resetEventCreateForm()
                eventCreateExpanded = false
                renderEventsPage()
                hideKeyboard(eventDescriptionInput)
                statusView.text = "Event created."
            }.addOnFailureListener { error ->
                statusView.text = "Event create failed: ${error.message ?: "network error"}"
            }
        }
    }

    private fun showEventStartPicker() {
        hideKeyboard(eventTimeInput)
        val dateBase = Calendar.getInstance().apply {
            if (selectedEventStartAtMs > 0L) {
                timeInMillis = selectedEventStartAtMs
            } else {
                add(Calendar.HOUR_OF_DAY, 1)
            }
        }
        val dialog = DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                val timeBase = Calendar.getInstance().apply {
                    if (selectedEventStartAtMs > 0L) {
                        timeInMillis = selectedEventStartAtMs
                    } else {
                        add(Calendar.HOUR_OF_DAY, 1)
                    }
                }
                TimePickerDialog(
                    this,
                    { _, hourOfDay, minute ->
                        val selected = Calendar.getInstance().apply {
                            set(Calendar.YEAR, year)
                            set(Calendar.MONTH, month)
                            set(Calendar.DAY_OF_MONTH, dayOfMonth)
                            set(Calendar.HOUR_OF_DAY, hourOfDay)
                            set(Calendar.MINUTE, minute)
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        selectedEventStartAtMs = selected.timeInMillis
                        eventTimeInput.setText(formatEventStartTime(selectedEventStartAtMs))
                        eventTimeInput.error = null
                    },
                    timeBase.get(Calendar.HOUR_OF_DAY),
                    timeBase.get(Calendar.MINUTE),
                    false
                ).show()
            },
            dateBase.get(Calendar.YEAR),
            dateBase.get(Calendar.MONTH),
            dateBase.get(Calendar.DAY_OF_MONTH)
        )
        dialog.datePicker.minDate = System.currentTimeMillis() - 1000L
        dialog.show()
    }

    private fun showEventLocationPicker() {
        hideKeyboard(eventLocationInput)
        showEventLocationMapDialog()
    }

    private fun showEventLocationMapDialog() {
        Configuration.getInstance().userAgentValue = packageName
        val initialPoint = selectedEventMapPoint()
        var selectedPoint = initialPoint

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), 0)
        }

        val selectedLabel = TextView(this).apply {
            text = "Tap the map to move event location"
            textSize = 13f
            setTextColor(Color.rgb(31, 41, 55))
            setPadding(0, 0, 0, dp(8))
        }
        container.addView(selectedLabel, matchWrapNoMargin())

        val pickerMap = OsmMapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            minZoomLevel = 3.0
            maxZoomLevel = 20.0
            controller.setZoom(if (hasEventOrUserLocation()) 16.0 else 5.0)
            controller.setCenter(initialPoint)
        }
        val marker = Marker(pickerMap).apply {
            title = "Event location"
            position = initialPoint
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
        pickerMap.overlays.add(marker)
        val updateSelection = { point: GeoPoint ->
            selectedPoint = point
            marker.position = point
            selectedLabel.text = eventMapLocationLabel(point)
            pickerMap.invalidate()
        }
        pickerMap.overlays.add(
            MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(point: GeoPoint?): Boolean {
                    point?.let { updateSelection(it) }
                    return true
                }

                override fun longPressHelper(point: GeoPoint?): Boolean {
                    point?.let { updateSelection(it) }
                    return true
                }
            })
        )
        selectedLabel.text = eventMapLocationLabel(initialPoint)

        container.addView(
            pickerMap,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360)).apply {
                topMargin = dp(4)
            }
        )
        container.addView(smallCommand("USE MY CURRENT LOCATION", GREEN).apply {
            setOnClickListener {
                val location = currentState.ownLocation ?: previewSnapshot
                if (location == null) {
                    requestPreviewLocationIfNeeded()
                    statusView.text = "Waiting for your location."
                } else {
                    val point = GeoPoint(location.latitude, location.longitude)
                    pickerMap.controller.animateTo(point)
                    updateSelection(point)
                }
            }
        }, matchWrap(top = 10))

        val dialog = AlertDialog.Builder(this)
            .setTitle("Select event location")
            .setView(container)
            .setPositiveButton("Use selected", null)
            .setNeutralButton("Search", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            pickerMap.onResume()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                chooseEventMapPoint(selectedPoint)
                pickerMap.onPause()
                pickerMap.onDetach()
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                pickerMap.onPause()
                pickerMap.onDetach()
                dialog.dismiss()
                showEventLocationSearchDialog()
            }
        }
        dialog.setOnDismissListener {
            runCatching { pickerMap.onPause() }
            runCatching { pickerMap.onDetach() }
        }
        dialog.show()
    }

    private fun selectedEventMapPoint(): GeoPoint {
        val selectedLat = selectedEventLatE7
        val selectedLon = selectedEventLonE7
        if (selectedLat != null && selectedLon != null) {
            return GeoPoint(selectedLat / 10_000_000.0, selectedLon / 10_000_000.0)
        }
        currentState.ownLocation?.let { return GeoPoint(it.latitude, it.longitude) }
        previewSnapshot?.let { return GeoPoint(it.latitude, it.longitude) }
        requestPreviewLocationIfNeeded()
        return GeoPoint(DEFAULT_MAP_PICKER_LATITUDE, DEFAULT_MAP_PICKER_LONGITUDE)
    }

    private fun hasEventOrUserLocation(): Boolean {
        return (selectedEventLatE7 != null && selectedEventLonE7 != null) ||
            currentState.ownLocation != null ||
            previewSnapshot != null
    }

    private fun eventMapLocationLabel(point: GeoPoint): String {
        return String.format(Locale.US, "Selected %.5f, %.5f", point.latitude, point.longitude)
    }

    private fun chooseEventMapPoint(point: GeoPoint) {
        selectedEventLocationName = eventMapLocationLabel(point)
        selectedEventLatE7 = (point.latitude * 10_000_000).roundToInt()
        selectedEventLonE7 = (point.longitude * 10_000_000).roundToInt()
        eventLocationInput.setText(selectedEventLocationName)
        eventLocationInput.error = null
        statusView.text = "Event location marked on map."
    }

    private fun showEventLocationSearchDialog() {
        val searchInput = EditText(this).apply {
            hint = "Search place or area"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(dp(14), 0, dp(14), 0)
        }
        AlertDialog.Builder(this)
            .setTitle("Search location")
            .setView(searchInput)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Search") { _, _ ->
                val query = searchInput.text.toString().trim()
                if (query.isBlank()) {
                    statusView.text = "Enter location to search."
                } else {
                    searchEventLocation(query)
                }
            }
            .show()
    }

    private fun searchEventLocation(query: String) {
        val requestId = ++eventLocationRequestId
        val origin = currentState.ownLocation ?: previewSnapshot
        statusView.text = "Searching event location..."
        mapNavigationExecutor.execute {
            runCatching { mapNavigationClient.search(query, origin?.latitude, origin?.longitude) }
                .onSuccess { results ->
                    runOnUiThread {
                        if (requestId != eventLocationRequestId) return@runOnUiThread
                        val ranked = if (origin == null) {
                            results
                        } else {
                            results.sortedBy { result ->
                                approximateDistanceMeters(origin.latitude, origin.longitude, result.latitude, result.longitude)
                            }
                        }
                        if (ranked.isEmpty()) {
                            statusView.text = "No location found. Try area or city name."
                        } else if (ranked.size == 1) {
                            chooseEventLocation(ranked.first())
                        } else {
                            showEventLocationResults(ranked)
                        }
                    }
                }
                .onFailure {
                    runOnUiThread {
                        if (requestId != eventLocationRequestId) return@runOnUiThread
                        statusView.text = "Location search failed. Check internet and try again."
                    }
                }
        }
    }

    private fun showEventLocationResults(results: List<PlaceSearchResult>) {
        val labels = results.map { result ->
            val area = result.address
                .split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() && it != result.name }
                .take(3)
                .joinToString(", ")
            if (area.isBlank()) result.label else "${result.label}\n$area"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Choose event location")
            .setItems(labels) { _, which -> chooseEventLocation(results[which]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun chooseEventLocation(result: PlaceSearchResult) {
        selectedEventLocationName = result.label
        selectedEventLatE7 = (result.latitude * 10_000_000).toInt()
        selectedEventLonE7 = (result.longitude * 10_000_000).toInt()
        eventLocationInput.setText(selectedEventLocationName)
        eventLocationInput.error = null
        statusView.text = "Event location added."
    }

    private fun resetEventCreateForm() {
        selectedEventStartAtMs = 0L
        selectedEventLocationName = ""
        selectedEventLatE7 = null
        selectedEventLonE7 = null
    }

    private fun formatEventStartTime(timestampMs: Long): String {
        return SimpleDateFormat("dd MMM yyyy, h:mm a", Locale.US).format(java.util.Date(timestampMs))
    }

    private fun openEventDetailsFromMap(event: BikeEvent) {
        switchTab("events")
        if (::eventsPage.isInitialized) {
            eventsPage.post {
                if (::eventsScroll.isInitialized) eventsScroll.smoothScrollTo(0, 0)
                showEventDetailsDialog(event)
            }
        } else {
            showEventDetailsDialog(event)
        }
    }

    private fun showEventDetailsDialog(event: BikeEvent) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = gradientRounded(eventGradientStart(event.id), eventGradientEnd(event.id), dp(18), stroke = Color.argb(130, 255, 255, 255))
        }
        header.addView(TextView(this).apply {
            text = event.name
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setLineSpacing(dp(2).toFloat(), 1.0f)
        }, matchWrapNoMargin())
        header.addView(TextView(this).apply {
            text = event.eventTime.ifBlank { "Time not set" }
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(254, 243, 199))
            setPadding(0, dp(8), 0, 0)
        }, matchWrapNoMargin())
        content.addView(header, matchWrapNoMargin())

        content.addView(dialogDetailRow("Location", event.locationName.ifBlank { eventMapLocationLabel(GeoPoint(event.latitude, event.longitude)) }), matchWrap(top = 12))
        content.addView(dialogDetailRow("Organizer", event.creatorName.ifBlank { "CoRider rider" }), matchWrap(top = 10))
        content.addView(dialogDetailRow("Description", event.description.ifBlank { "No description added." }), matchWrap(top = 10))

        val builder = AlertDialog.Builder(this)
            .setTitle("Event details")
            .setView(content)
            .setPositiveButton("Close", null)
        if (canDeleteEvent(event)) {
            builder.setNegativeButton("Delete", null)
        }

        val dialog = builder.create()
        dialog.setOnShowListener {
            val deleteButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            if (deleteButton != null) {
                deleteButton.setTextColor(RED)
                deleteButton.setOnClickListener {
                    confirmDeleteEvent(event, dialog)
                }
            }
        }
        dialog.show()
    }

    private fun canDeleteEvent(event: BikeEvent): Boolean {
        val user = FirebaseAuth.getInstance().currentUser
        val email = user?.email.orEmpty().trim().lowercase(Locale.US)
        val uid = currentGoogleUid().orEmpty()
        if (uid.isBlank() && email != EVENT_ADMIN_EMAIL) return false
        return email == EVENT_ADMIN_EMAIL ||
            (event.creatorEmail.isNotBlank() && event.creatorEmail.equals(email, ignoreCase = true)) ||
            (event.creatorId.isNotBlank() && event.creatorId == uid)
    }

    private fun confirmDeleteEvent(event: BikeEvent, eventDialog: AlertDialog) {
        AlertDialog.Builder(this)
            .setTitle("Delete event?")
            .setMessage("This will remove ${event.name} from Events and map pins for everyone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                eventDialog.dismiss()
                deleteGlobalEvent(event)
            }
            .show()
    }

    private fun deleteGlobalEvent(event: BikeEvent) {
        withFirebaseAuth {
            if (!canDeleteEvent(event)) {
                statusView.text = "Only the event creator or event admin can delete this event."
                return@withFirebaseAuth
            }
            globalEventsRef().child(event.id).removeValue()
                .addOnSuccessListener {
                    globalBikeEvents.remove(event.id)
                    renderEventsPage()
                    if (::mapView.isInitialized) {
                        mapView.setGlobalEvents(globalBikeEvents.values, show = !rideActive)
                    }
                    statusView.text = "Event deleted."
                }
                .addOnFailureListener { error ->
                    statusView.text = "Delete failed: ${error.message ?: "network error"}"
                }
        }
    }

    private fun dialogDetailRow(label: String, value: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(Color.rgb(248, 250, 252), dp(12), stroke = Color.rgb(203, 213, 225))
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.04f
                setTextColor(Color.rgb(100, 116, 139))
            }, matchWrapNoMargin())
            addView(TextView(this@MainActivity).apply {
                text = value
                textSize = 14f
                setTextColor(Color.rgb(15, 23, 42))
                setPadding(0, dp(4), 0, 0)
                setLineSpacing(dp(2).toFloat(), 1.0f)
            }, matchWrapNoMargin())
        }
    }

    private fun startGlobalEventsListener() {
        if (globalEventsListener != null) return
        withFirebaseAuth {
            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    applyGlobalEventsSnapshot(snapshot)
                }

                override fun onCancelled(error: DatabaseError) {
                    statusView.text = "Events sync failed: ${error.message}"
                }
            }
            globalEventsListener = listener
            globalEventsRef().addValueEventListener(listener)
        }
    }

    private fun stopGlobalEventsListener() {
        globalEventsListener?.let { globalEventsRef().removeEventListener(it) }
        globalEventsListener = null
    }

    private fun applyGlobalEventsSnapshot(snapshot: DataSnapshot) {
        globalBikeEvents.clear()
        snapshot.children.mapNotNull { readBikeEvent(it) }
            .sortedByDescending { it.createdAtMs }
            .forEach { globalBikeEvents[it.id] = it }
        renderEventsPage()
        if (::mapView.isInitialized) {
            mapView.setGlobalEvents(globalBikeEvents.values, show = !rideActive)
        }
    }

    private fun readBikeEvent(snapshot: DataSnapshot): BikeEvent? {
        val id = snapshot.child("id").getValue(String::class.java) ?: snapshot.key ?: return null
        val name = snapshot.child("name").getValue(String::class.java)?.trim().orEmpty()
        val time = snapshot.child("eventTime").getValue(String::class.java)?.trim().orEmpty()
        val createdAt = snapshot.child("createdAtMs").getValue(Long::class.java) ?: 0L
        val startAt = snapshot.child("startAtMs").getValue(Long::class.java) ?: createdAt
        val eventTime = time.ifBlank { if (startAt > 0L) formatEventStartTime(startAt) else "" }
        val locationName = snapshot.child("locationName").getValue(String::class.java)?.trim().orEmpty()
        val description = snapshot.child("description").getValue(String::class.java)?.trim().orEmpty()
        val lat = snapshot.child("latE7").getValue(Int::class.java) ?: return null
        val lon = snapshot.child("lonE7").getValue(Int::class.java) ?: return null
        val creatorName = snapshot.child("creatorName").getValue(String::class.java).orEmpty()
        val creatorId = snapshot.child("creatorUid").getValue(String::class.java)
            ?: snapshot.child("creatorId").getValue(String::class.java)
            ?: snapshot.child("localRiderId").getValue(String::class.java)
            ?: ""
        val creatorEmail = snapshot.child("creatorEmail").getValue(String::class.java).orEmpty().trim().lowercase(Locale.US)
        if (name.isBlank()) return null
        return BikeEvent(id, name, eventTime, startAt, locationName, description, lat, lon, creatorName, createdAt, creatorId, creatorEmail)
    }

    private fun globalEventsRef(): DatabaseReference {
        return FirebaseDatabase.getInstance().getReference(GLOBAL_EVENTS_PATH)
    }

    private fun eventGradientStart(seed: String): Int {
        return EVENT_GRADIENTS[(seed.hashCode() and Int.MAX_VALUE) % EVENT_GRADIENTS.size].first
    }

    private fun eventGradientEnd(seed: String): Int {
        return EVENT_GRADIENTS[(seed.hashCode() and Int.MAX_VALUE) % EVENT_GRADIENTS.size].second
    }

    private fun buildProfilePage(): ScrollView {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(24))
            setBackgroundColor(SURFACE)
        }

        val profilePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = gradientRounded(Color.rgb(96, 34, 197), Color.rgb(37, 99, 235), dp(18))
            elevation = dp(5).toFloat()
        }
        val initialProfileName = prefs.getString(KEY_RIDER_NAME, "").orEmpty().ifBlank { "Rider" }
        profileTitleView = TextView(this).apply {
            text = initialProfileName
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            maxLines = 1
        }
        profileSubtitleView = TextView(this).apply {
            text = "Rider details"
            textSize = 12f
            setTextColor(Color.rgb(219, 234, 254))
            gravity = Gravity.CENTER
            setPadding(0, dp(3), 0, dp(12))
        }
        val personalCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(Color.WHITE, dp(13), stroke = Color.rgb(226, 232, 240))
            elevation = dp(4).toFloat()
        }
        personalCard.addView(TextView(this).apply {
            text = "Personal Info"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(15, 23, 42))
        }, matchWrapNoMargin())

        googleAuthStatusView = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.rgb(71, 85, 105))
            setPadding(0, dp(8), 0, 0)
            setLineSpacing(dp(2).toFloat(), 1.0f)
        }
        personalCard.addView(googleAuthStatusView, matchWrapNoMargin())

        googleAuthButton = smallCommand("SIGN IN WITH GOOGLE", BLUE).apply {
            setOnClickListener {
                if (isGoogleSignedIn()) {
                    signOutGoogle()
                } else {
                    signInWithGoogle()
                }
            }
        }
        personalCard.addView(googleAuthButton, matchWrap(top = 12))

        profileNameInput = profileInput("Full name", prefs.getString(KEY_RIDER_NAME, "").orEmpty()).apply {
            filters = arrayOf(lettersAndSpacesFilter(), InputFilter.LengthFilter(40))
        }
        profileContactInput = profileInput("Contact number", prefs.getString(KEY_CONTACT, "").orEmpty()).apply {
            inputType = InputType.TYPE_CLASS_PHONE
            filters = arrayOf(digitsOnlyFilter(), InputFilter.LengthFilter(10))
        }
        profileDobInput = profileInput("Date of birth", prefs.getString(KEY_DOB, "").orEmpty()).apply {
            inputType = InputType.TYPE_NULL
            keyListener = null
            isFocusable = false
            isCursorVisible = false
            setOnClickListener {
                if (profileEditMode) showDobPicker()
            }
        }
        profileBloodInput = bloodGroupDropdown(prefs.getString(KEY_BLOOD_GROUP, "").orEmpty())
        profileBikeInput = profileInput("Bike / vehicle", prefs.getString(KEY_BIKE, "").orEmpty())
        profileEmergencyInput = profileInput("Emergency contact", prefs.getString(KEY_EMERGENCY_CONTACT, "").orEmpty()).apply {
            inputType = InputType.TYPE_CLASS_PHONE
            filters = arrayOf(digitsOnlyFilter(), InputFilter.LengthFilter(10))
        }

        personalCard.addView(profileNameInput, matchWrap(top = 12))
        personalCard.addView(profileContactInput, matchWrap(top = 10))
        personalCard.addView(profileDobInput, matchWrap(top = 10))
        personalCard.addView(profileBloodInput, matchWrap(top = 10))
        personalCard.addView(profileBikeInput, matchWrap(top = 10))
        personalCard.addView(profileEmergencyInput, matchWrap(top = 10))

        val saveButton = smallCommand("EDIT PROFILE", BLUE).apply {
            setOnClickListener {
                if (!profileEditMode) {
                    setProfileEditMode(true, this)
                    statusView.text = "Edit profile details."
                } else if (validateProfileInputs()) {
                    saveProfile()
                    hideKeyboard(this)
                    setProfileEditMode(false, this)
                    profileTitleView.text = profileName()
                    statusView.text = "Details updated. Your map name is ${profileName()}."
                }
            }
        }
        personalCard.addView(saveButton, matchWrap(top = 14))
        setProfileEditMode(false, saveButton)
        updateGoogleAuthUi()
        profilePanel.addView(profileTitleView, matchWrapNoMargin())
        profilePanel.addView(profileSubtitleView, matchWrapNoMargin())
        profilePanel.addView(personalCard, matchWrapNoMargin())
        content.addView(profilePanel, matchWrapNoMargin())

        val batteryPanel = panel()
        batteryPanel.addView(sectionTitle("BATTERY & BACKGROUND"), matchWrapNoMargin())
        batteryStatusView = bodyText(batteryOptimizationStatus()).apply {
            setTextColor(if (isBatteryOptimizationIgnored()) GREEN else AMBER)
            typeface = Typeface.DEFAULT_BOLD
        }
        batteryPanel.addView(batteryStatusView, matchWrap(top = 8))
        batteryPanel.addView(
            bodyText("For Redmi: set Battery saver to No restrictions, enable Auto start, and keep CoRider locked in recent apps."),
            matchWrap(top = 8)
        )

        val batteryButton = smallCommand("BATTERY SETTINGS", BLUE).apply {
            setOnClickListener { openBatterySettings() }
            styleProfileSettingsButton()
        }
        val appButton = smallCommand("APP SETTINGS", PILL).apply {
            setOnClickListener { openAppSettings() }
            styleProfileSettingsButton()
        }
        batteryPanel.addView(batteryButton, matchWrap(top = 12))
        batteryPanel.addView(appButton, matchWrap(top = 10))

        val redmiButton = smallCommand("REDMI AUTOSTART", GREEN).apply {
            setOnClickListener { openRedmiAutostartSettings() }
            styleProfileSettingsButton()
        }
        batteryPanel.addView(redmiButton, matchWrap(top = 10))
        content.addView(batteryPanel, matchWrap(top = 18))

        return ScrollView(this).apply {
            setBackgroundColor(SURFACE)
            clipToPadding = false
            isFillViewport = true
            addView(content)
        }
    }

    private fun renderGroupList() {
        if (!::groupContent.isInitialized) return
        groupContent.removeAllViews()
        selectedGroupCode = null
        selectedRiderActionId = null
        updateTopBarBackButton()
        if (::groupScroll.isInitialized) groupScroll.post { groupScroll.scrollTo(0, 0) }

        val groups = loadGroups()
        if (groups.isEmpty()) {
            val emptyPanel = panel()
            emptyPanel.addView(sectionTitle("NO GROUPS YET"), matchWrapNoMargin())
            emptyPanel.addView(bodyText("Create a new riding group or join one using an invite code."), matchWrap(top = 8))
            groupContent.addView(emptyPanel, matchWrapNoMargin())
            groupContent.addView(createGroupPanel(), matchWrap(top = 18))
            groupContent.addView(joinGroupPanel(collapsed = true), matchWrap(top = 18))
            return
        }

        refreshGroupMemberCounts(groups)

        val search = groupSearchBar(groups)
        groupContent.addView(search, matchWrapNoMargin())
        groupResultsContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        groupContent.addView(groupResultsContent, matchWrap(top = 4))
        renderGroupSearchResults(groups)
    }

    private fun renderGroupSearchResults(groups: List<LocalGroup> = loadGroups()) {
        if (!::groupResultsContent.isInitialized) return
        groupResultsContent.removeAllViews()
        val activeCode = prefs.getString(KEY_ACTIVE_GROUP_CODE, "").orEmpty()
        val filteredGroups = groups.filter { group ->
            val query = groupSearchQuery.trim()
            query.isBlank() ||
                group.name.contains(query, ignoreCase = true) ||
                group.code.contains(query, ignoreCase = true)
        }.sortedWith(
            compareByDescending<LocalGroup> { it.code == activeCode && rideActive }
                .thenBy { it.name.lowercase(Locale.US) }
        )

        if (filteredGroups.isEmpty()) {
            val emptyPanel = panel()
            emptyPanel.addView(sectionTitle("NO MATCHES"), matchWrapNoMargin())
            emptyPanel.addView(bodyText("Try another group name or code."), matchWrap(top = 8))
            groupResultsContent.addView(emptyPanel, matchWrap(top = 8))
        }

        filteredGroups.forEach { group ->
            groupResultsContent.addView(groupListItem(group, activeCode), matchWrap(top = 8))
        }

        groupResultsContent.addView(createGroupPanel(), matchWrap(top = 18))
        groupResultsContent.addView(joinGroupPanel(collapsed = true), matchWrap(top = 18))
    }

    private fun groupSearchBar(groups: List<LocalGroup>): FrameLayout {
        val searchBox = FrameLayout(this).apply {
            background = rounded(INPUT, dp(26), stroke = Color.rgb(71, 85, 105))
            isClickable = true
            isFocusable = false
        }
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_search)
            setColorFilter(Color.rgb(148, 163, 184))
            alpha = 0.85f
            visibility = if (groupSearchQuery.isBlank()) View.VISIBLE else View.GONE
        }
        val editText = EditText(this).apply {
            hint = "Search groups"
            setHintTextColor(Color.rgb(148, 163, 184))
            setSingleLine(true)
            setText(groupSearchQuery)
            textSize = 14f
            setTextColor(Color.WHITE)
            minHeight = dp(52)
            background = null
            setPadding(if (groupSearchQuery.isBlank()) dp(44) else dp(14), 0, dp(14), 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    groupSearchQuery = s?.toString().orEmpty()
                    icon.visibility = View.GONE
                    setPadding(dp(14), 0, dp(14), 0)
                    renderGroupSearchResults(groups)
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
            setOnFocusChangeListener { _, hasFocus ->
                val showIcon = !hasFocus && text.isNullOrBlank()
                icon.visibility = if (showIcon) View.VISIBLE else View.GONE
                setPadding(if (showIcon) dp(44) else dp(14), 0, dp(14), 0)
            }
        }
        searchBox.setOnClickListener {
            icon.visibility = View.GONE
            editText.setPadding(dp(14), 0, dp(14), 0)
            editText.requestFocus()
        }
        searchBox.addView(editText, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(52)))
        searchBox.addView(
            icon,
            FrameLayout.LayoutParams(dp(20), dp(20)).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                leftMargin = dp(16)
            }
        )
        return searchBox
    }

    private fun groupListItem(group: LocalGroup, activeCode: String): LinearLayout {
        val isActive = group.code == activeCode && rideActive
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(12), dp(12))
            background = rounded(
                CARD,
                dp(8),
                stroke = CARD_STROKE,
                strokeWidth = 1
            )
            setOnClickListener { renderGroupDetail(group.code) }

            val details = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                val titleRow = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    val title = TextView(this@MainActivity).apply {
                        text = groupListTitle(group)
                        textSize = 16f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(Color.WHITE)
                        maxLines = 1
                    }
                    addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    if (isActive) {
                        addView(
                            TextView(this@MainActivity).apply {
                                text = ""
                                contentDescription = "Active group"
                                background = oval(GREEN, stroke = Color.argb(160, 209, 250, 229), strokeWidth = 1)
                                elevation = dp(2).toFloat()
                            },
                            LinearLayout.LayoutParams(dp(9), dp(9)).apply { leftMargin = dp(7) }
                        )
                    }
                }
                val members = TextView(this@MainActivity).apply {
                    text = groupMemberText(group, isActive)
                    textSize = 12f
                    setTextColor(Color.rgb(148, 163, 184))
                    setPadding(0, dp(4), 0, 0)
                    maxLines = 1
                }
                addView(titleRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(members, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            val delete = ImageButton(this@MainActivity).apply {
                contentDescription = "Delete group"
                setImageResource(R.drawable.ic_delete)
                setColorFilter(Color.WHITE)
                scaleType = ImageView.ScaleType.CENTER
                setPadding(dp(10), dp(10), dp(10), dp(10))
                setBackgroundColor(Color.TRANSPARENT)
                isClickable = true
                isFocusable = true
                setOnClickListener { confirmDeleteGroup(group) }
            }

            addView(details, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(delete, LinearLayout.LayoutParams(dp(42), dp(42)).apply { leftMargin = dp(12) })
        }
    }
    private fun renderGroupDetail(groupCode: String) {
        if (!::groupContent.isInitialized) return
        val group = loadGroups().firstOrNull { it.code == groupCode } ?: return
        if (selectedGroupCode != group.code) selectedRiderActionId = null
        selectedGroupCode = group.code
        refreshGroupAdminsIfNeeded(group.code)
        updateTopBarBackButton()
        groupContent.removeAllViews()
        if (::groupScroll.isInitialized) groupScroll.post { groupScroll.scrollTo(0, 0) }

        val isActiveGroup = rideActive && currentState.rideId == group.code
        groupContent.addView(
            groupHeaderCard(group, isActiveGroup),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(104))
        )

        val walkiePanel = detailPanel()
        walkiePanel.addView(sectionHeaderWithIcon(R.drawable.ic_walkie, GREEN, "WALKIE TALKIE"), matchWrapNoMargin())
        walkieStatusView = bodyText(
            walkieStatusText(
                walkieTalkie.currentState(),
                isActiveGroup,
                activeRiderCount(group.code) >= MIN_WALKIE_RIDERS
            )
        ).apply {
            textSize = 14f
            setTextColor(Color.rgb(186, 194, 208))
        }
        walkiePanel.addView(walkieStatusView, matchWrap(top = 10))
        walkieButton = smallCommand("START WALKIE", GREEN).apply {
            minHeight = dp(54)
            textSize = 14f
            setOnClickListener { handleWalkieClick(group.code) }
        }
        walkieEndButton = ImageButton(this).apply {
            contentDescription = "End walkie talkie"
            setImageResource(R.drawable.ic_call_end)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(dp(13), dp(13), dp(13), dp(13))
            background = rounded(Color.rgb(127, 29, 29), dp(10), stroke = RED, strokeWidth = 2)
            elevation = dp(5).toFloat()
            visibility = View.GONE
            setOnClickListener { endWalkieTalkie() }
        }
        val walkieControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        walkieControls.addView(walkieButton, LinearLayout.LayoutParams(0, dp(54), 1f))
        walkieControls.addView(walkieEndButton, LinearLayout.LayoutParams(dp(54), dp(54)).apply { leftMargin = dp(10) })
        walkiePanel.addView(walkieControls, matchWrap(top = 14))
        groupContent.addView(walkiePanel, matchWrap(top = 12))
        renderWalkieState(walkieTalkie.currentState())

        val ridersPanel = detailPanel()
        ridersPanel.addView(sectionHeaderWithIcon(R.drawable.ic_riders, BLUE, "RIDERS"), matchWrapNoMargin())
        ridersPanel.addView(groupRiderListView(group.code), matchWrap(top = 12))
        groupContent.addView(ridersPanel, matchWrap(top = 12))

        val modePanel = detailPanel()
        modePanel.addView(sectionHeaderWithIcon(R.drawable.ic_update_mode, Color.rgb(168, 85, 247), "UPDATE MODE"), matchWrapNoMargin())
        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        modeEcoButton = modeButton("Eco\n~60s").apply { setOnClickListener { setMode(UpdateMode.ECO) } }
        modeNormalButton = modeButton("Normal\n~30s").apply { setOnClickListener { setMode(UpdateMode.NORMAL) } }
        modeFastButton = modeButton("Fast\n~15s").apply { setOnClickListener { setMode(UpdateMode.FAST) } }
        modeRow.addView(modeEcoButton, LinearLayout.LayoutParams(0, dp(76), 1f))
        modeRow.addView(modeNormalButton, LinearLayout.LayoutParams(0, dp(76), 1f).apply { leftMargin = dp(8) })
        modeRow.addView(modeFastButton, LinearLayout.LayoutParams(0, dp(76), 1f).apply { leftMargin = dp(8) })
        modePanel.addView(modeRow, matchWrap(top = 10))
        groupContent.addView(modePanel, matchWrap(top = 12))
        highlightMode(currentState.updateMode)
    }

    private fun createGroupPanel(): LinearLayout {
        val panel = panel()
        panel.addView(sectionTitle("CREATE GROUP"), matchWrapNoMargin())
        val nameInput = input("Group name", "")
        panel.addView(nameInput, matchWrap(top = 12))
        val create = smallCommand("CREATE", BLUE).apply {
            setOnClickListener {
                if (!requireGoogleIdentity("create groups")) return@setOnClickListener
                val name = nameInput.text.toString().trim()
                if (name.isBlank()) {
                    nameInput.error = "Group name is required"
                    statusView.text = "Enter a group name."
                    return@setOnClickListener
                }
                val code = nextGroupCode()
                saveGroup(LocalGroup(code, name))
                markGroupAdmin(code)
                writeGroupMetadata(code, name)
                renderGroupDetail(code)
            }
        }
        panel.addView(create, matchWrap(top = 12))
        return panel
    }

    private fun joinGroupPanel(collapsed: Boolean): LinearLayout {
        val panel = panel()
        panel.addView(sectionTitle("JOIN GROUP"), matchWrapNoMargin())
        panel.addView(bodyText("Use the group code shared by another rider."), matchWrap(top = 8))

        if (collapsed) {
            val showJoin = smallCommand("JOIN GROUP", GREEN).apply {
                setOnClickListener { renderJoinGroupForm() }
            }
            panel.addView(showJoin, matchWrap(top = 12))
            return panel
        }

        val codeInput = input("Enter group code", "").apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        panel.addView(codeInput, matchWrap(top = 12))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val join = smallCommand("JOIN", BLUE).apply {
            setOnClickListener {
                if (!requireGoogleIdentity("join groups")) return@setOnClickListener
                val code = codeInput.text.toString().trim().uppercase(Locale.US)
                if (code.isBlank()) {
                    statusView.text = "Enter a group code to join."
                    return@setOnClickListener
                }
                saveGroup(LocalGroup(code, groupNameForCode(code)))
                refreshGroupAdmins(code, force = true)
                renderGroupDetail(code)
            }
        }
        val cancel = smallCommand("CANCEL", PILL).apply {
            setOnClickListener { renderGroupList() }
        }
        row.addView(join, LinearLayout.LayoutParams(0, dp(48), 1f))
        row.addView(cancel, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(10) })
        panel.addView(row, matchWrap(top = 12))
        return panel
    }

    private fun renderJoinGroupForm() {
        if (!::groupContent.isInitialized) return
        groupContent.removeAllViews()
        groupContent.addView(joinGroupPanel(collapsed = false), matchWrapNoMargin())
    }

    private fun groupRiderListView(groupCode: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (!rideActive || currentState.rideId != groupCode) {
                addView(riderMessageItemView("Group inactive", "Toggle ACTIVE to join this group's live location.", MUTED), matchWrapNoMargin())
                return@apply
            }

            val canManageRiders = isAdminForGroup(groupCode)
            val adminIds = adminRidersByGroup[groupCode].orEmpty()
            val now = System.currentTimeMillis()
            val ownSnapshot = currentState.ownLocation
            val ownName = ownSnapshot?.label ?: profileName().ifBlank { "You" }
            addView(
                riderStatusItemView(
                    name = ownName,
                    secondsText = ownSnapshot?.ageSeconds(now)?.let { "${it}s" } ?: "--",
                    active = ownSnapshot?.let { !it.isStale(now) } ?: rideActive,
                    movementText = riderMovementText(ownSnapshot, now),
                    tagText = "You",
                    admin = canManageRiders || riderId in adminIds,
                    onClick = { openRiderOnMap(null) }
                ),
                matchWrapNoMargin()
            )

            currentState.riders.values
                .sortedBy { it.label.lowercase(Locale.US) }
                .forEachIndexed { index, rider ->
                    addView(
                        riderStatusItemView(
                            name = rider.label,
                            secondsText = "${rider.ageSeconds(now)}s",
                            active = !rider.isStale(now),
                            movementText = riderMovementText(rider, now),
                            tagText = null,
                            admin = rider.id in adminIds,
                            onClick = {
                                if (canManageRiders) {
                                    selectedRiderActionId = if (selectedRiderActionId == rider.id) null else rider.id
                                    renderGroupDetail(groupCode)
                                } else {
                                    openRiderOnMap(rider.id)
                                }
                            }
                        ),
                        matchWrap(top = if (index == 0) 10 else 10)
                    )
                    if (canManageRiders && selectedRiderActionId == rider.id) {
                        addView(riderAdminActionsCard(groupCode, rider, rider.id in adminIds), matchWrap(top = 8))
                    }
                }

            if (currentState.riders.isEmpty()) {
                addView(riderMessageItemView("No other riders yet", "Share invite code and ask them to make this group active.", MUTED), matchWrap(top = 10))
            }
        }
    }

    private fun riderStatusItemView(
        name: String,
        secondsText: String,
        active: Boolean,
        movementText: String,
        tagText: String?,
        admin: Boolean,
        onClick: () -> Unit
    ): LinearLayout {
        val accent = if (active) GREEN else RED
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            setPadding(dp(14), dp(11), dp(12), dp(11))
            background = rounded(Color.rgb(23, 28, 35), dp(10), stroke = Color.argb(120, Color.red(accent), Color.green(accent), Color.blue(accent)))
            elevation = dp(2).toFloat()

            val textColumn = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }

            val title = TextView(context).apply {
                text = riderTitleText(name, secondsText, admin)
                textSize = 15f
                setTextColor(Color.WHITE)
                maxLines = 1
            }
            textColumn.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            val subtitle = TextView(context).apply {
                text = listOfNotNull(tagText, movementText).joinToString(" / ")
                textSize = 13f
                setTextColor(if (active) Color.rgb(209, 250, 229) else Color.rgb(254, 202, 202))
                setPadding(0, dp(3), 0, 0)
                maxLines = 1
            }
            textColumn.addView(subtitle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            addView(textColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(View(context).apply {
                background = oval(accent, stroke = Color.argb(180, 255, 255, 255), strokeWidth = 1)
                contentDescription = if (active) "Active rider" else "Inactive rider"
            }, LinearLayout.LayoutParams(dp(9), dp(9)).apply {
                leftMargin = dp(12)
                topMargin = dp(1)
            })
        }
    }

    private fun riderAdminActionsCard(groupCode: String, rider: RiderSnapshot, alreadyAdmin: Boolean): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = rounded(Color.rgb(15, 18, 25), dp(10), stroke = Color.rgb(71, 85, 105))

            val viewMap = miniActionButton("VIEW MAP", BLUE).apply {
                setOnClickListener {
                    selectedRiderActionId = null
                    openRiderOnMap(rider.id)
                }
            }
            val remove = miniActionButton("REMOVE", RED).apply {
                setOnClickListener { confirmRemoveRider(groupCode, rider) }
            }
            val makeAdmin = miniActionButton(if (alreadyAdmin) "ADMIN" else "MAKE ADMIN", GREEN).apply {
                isEnabled = !alreadyAdmin
                alpha = if (alreadyAdmin) 0.55f else 1f
                setOnClickListener {
                    if (!alreadyAdmin) makeRiderAdmin(groupCode, rider)
                }
            }

            addView(viewMap, LinearLayout.LayoutParams(0, dp(42), 1f))
            addView(remove, LinearLayout.LayoutParams(0, dp(42), 1f).apply { leftMargin = dp(8) })
            addView(makeAdmin, LinearLayout.LayoutParams(0, dp(42), 1f).apply { leftMargin = dp(8) })
        }
    }

    private fun miniActionButton(textValue: String, accent: Int): Button {
        return MaterialButton(this).apply {
            text = textValue
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            includeFontPadding = false
            minHeight = dp(40)
            gravity = Gravity.CENTER
            background = rounded(Color.argb(48, Color.red(accent), Color.green(accent), Color.blue(accent)), dp(8), stroke = accent, strokeWidth = 1)
            isAllCaps = false
        }
    }

    private fun confirmRemoveRider(groupCode: String, rider: RiderSnapshot) {
        AlertDialog.Builder(this)
            .setTitle("Remove rider")
            .setMessage("Remove ${rider.label} from this group?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remove") { _, _ -> removeRiderFromGroup(groupCode, rider) }
            .show()
    }

    private fun removeRiderFromGroup(groupCode: String, rider: RiderSnapshot) {
        if (!isAdminForGroup(groupCode)) {
            statusView.text = "Only group admin can remove riders."
            return
        }
        withFirebaseAuth {
            val root = FirebaseDatabase.getInstance().getReference("rides/$groupCode")
            root.child("removed").child(rider.id).setValue(true)
            clearRemovedRiderSharedEvents(root, rider.id)
            root.child("riders").child(rider.id).removeValue()
            root.child("admins").child(rider.id).removeValue()
                .addOnSuccessListener {
                    adminRidersByGroup[groupCode]?.remove(rider.id)
                    selectedRiderActionId = null
                    clearRemovedRiderLocalMapState(rider.id)
                    RideBus.removeRider(rider.id)
                    statusView.text = "${rider.label} removed from group."
                    if (selectedTab == "group" && selectedGroupCode == groupCode) renderGroupDetail(groupCode)
                }
                .addOnFailureListener { error ->
                    statusView.text = "Remove failed: ${error.message ?: "network error"}"
                }
        }
    }

    private fun makeRiderAdmin(groupCode: String, rider: RiderSnapshot) {
        if (!isAdminForGroup(groupCode)) {
            statusView.text = "Only group admin can make another admin."
            return
        }
        withFirebaseAuth {
            FirebaseDatabase.getInstance()
                .getReference("rides/$groupCode/admins/${rider.id}")
                .setValue(true)
                .addOnSuccessListener {
                    adminRidersByGroup.getOrPut(groupCode) { mutableSetOf() }.add(rider.id)
                    selectedRiderActionId = null
                    statusView.text = "${rider.label} is now admin."
                    renderGroupDetail(groupCode)
                }
                .addOnFailureListener { error ->
                    statusView.text = "Admin update failed: ${error.message ?: "network error"}"
                }
        }
    }

    private fun clearRemovedRiderSharedEvents(root: DatabaseReference, removedRiderId: String) {
        root.child("events").child("sos").get().addOnSuccessListener { snapshot ->
            if (snapshot.child("riderId").getValue(String::class.java) == removedRiderId) {
                snapshot.ref.removeValue()
            }
        }
        root.child("events").child("regroup").get().addOnSuccessListener { snapshot ->
            if (snapshot.child("riderId").getValue(String::class.java) == removedRiderId) {
                snapshot.ref.removeValue()
            }
        }
        root.child("events").child("safetyCheck").get().addOnSuccessListener { snapshot ->
            val target = snapshot.child("targetRiderId").getValue(String::class.java)
            val first = snapshot.child("firstRiderId").getValue(String::class.java)
            if (target == removedRiderId || first == removedRiderId) {
                snapshot.ref.removeValue()
            }
        }
    }

    private fun clearRemovedRiderLocalMapState(removedRiderId: String) {
        if (selectedMapRiderId == removedRiderId) {
            if (::mapView.isInitialized) mapView.clearTemporaryRider()
            hideRiderDetail()
        }
        if (currentState.groupAlert?.riderId == removedRiderId) {
            visibleSosAlertTimestampMs = 0L
            if (::riderDetailCard.isInitialized) riderDetailCard.visibility = View.GONE
        }
    }

    private fun riderMessageItemView(name: String, status: String, accent: Int): TextView {
        return TextView(this).apply {
            text = "$name\n$status"
            textSize = 14f
            setTextColor(Color.WHITE)
            setLineSpacing(dp(2).toFloat(), 1.0f)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(Color.rgb(23, 28, 35), dp(10), stroke = Color.argb(100, Color.red(accent), Color.green(accent), Color.blue(accent)))
            elevation = dp(2).toFloat()
        }
    }

    private fun riderMovementText(snapshot: RiderSnapshot?, nowMs: Long): String {
        if (snapshot == null) return "No GPS yet"
        if (snapshot.isStale(nowMs)) return riderFreshnessText(snapshot, nowMs)
        if (snapshot.speedMps > UI_MOVING_SPEED_MPS) return "Moving"
        val stationarySeconds = snapshot.stationarySinceMs
            .takeIf { it > 0L }
            ?.let { ((nowMs - it).coerceAtLeast(0L)) / 1000L }
            ?: 0L
        if (stationarySeconds >= STOPPED_LABEL_AFTER_SECONDS) {
            return "Stopped ${formatDurationShort(stationarySeconds)}"
        }
        return "Active"
    }

    private fun riderFreshnessText(snapshot: RiderSnapshot, nowMs: Long): String {
        return if (snapshot.isStale(nowMs)) {
            "Inactive - no update for ${formatDurationShort(snapshot.ageSeconds(nowMs))}"
        } else {
            "Active"
        }
    }

    private fun inviteRiderButton(groupCode: String): ImageButton {
        return ImageButton(this).apply {
            contentDescription = "Invite rider"
            setImageResource(R.drawable.ic_invite_rider)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = oval(BLUE, stroke = Color.rgb(147, 197, 253), strokeWidth = 2)
            elevation = dp(8).toFloat()
            setOnClickListener { shareInvite(groupCode) }
        }
    }

    private fun buildBottomNav(): BottomNavigationView {
        bottomNav = BottomNavigationView(this).apply {
            inflateMenu(R.menu.bottom_nav)
            setBackgroundColor(TOP_BAR)
            elevation = dp(12).toFloat()
            minimumHeight = dp(64)
            itemIconSize = dp(25)
            itemIconTintList = navTint()
            itemTextColor = navTint()
            itemRippleColor = ColorStateList.valueOf(Color.TRANSPARENT)
            setItemActiveIndicatorEnabled(false)
            labelVisibilityMode = com.google.android.material.navigation.NavigationBarView.LABEL_VISIBILITY_UNLABELED
            for (index in 0 until menu.size()) {
                menu.getItem(index).tooltipText = null
            }
            setOnItemSelectedListener { item ->
                if (updatingBottomNav) return@setOnItemSelectedListener true
                when (item.itemId) {
                    R.id.nav_map -> switchTab("map")
                    R.id.nav_group -> {
                        selectedGroupCode = null
                        renderGroupList()
                        switchTab("group")
                    }
                    R.id.nav_events -> switchTab("events")
                    R.id.nav_profile -> switchTab("profile")
                }
                true
            }
        }
        return bottomNav
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
        if (!requireGoogleIdentity("start live tracking")) return
        val rideCode = rideCodeInput.text.toString().trim().uppercase(Locale.US)
        val riderName = profileName()

        if (rideCode.isBlank()) {
            statusView.text = "Enter a ride code."
            return
        }

        saveProfile()
        prefs.edit()
            .putString(KEY_RIDE_CODE, rideCode)
            .putString(KEY_ACTIVE_GROUP_CODE, rideCode)
            .putString(KEY_RIDER_NAME, riderName)
            .apply()

        withFirebaseAuth {
                if (isLocalAdminGroup(rideCode)) {
                    writeAdminRole(rideCode, riderId)
                }
                val intent = Intent(this, LiveLocationService::class.java)
                    .setAction(LiveLocationService.ACTION_START)
                    .putExtra(LiveLocationService.EXTRA_RIDE_ID, rideCode)
                    .putExtra(LiveLocationService.EXTRA_RIDER_ID, riderId)
                    .putExtra(LiveLocationService.EXTRA_RIDER_NAME, riderName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                setupPanel.visibility = View.GONE
            }
    }

    private fun activateGroup(group: LocalGroup) {
        if (!requireGoogleIdentity("activate groups")) return
        leaveWalkieIfDifferentGroup(group.code)
        if (isLocalAdminGroup(group.code)) {
            writeAdminRole(group.code, riderId)
        } else {
            refreshGroupAdmins(group.code, force = true)
        }
        rideCodeInput.setText(group.code)
        prefs.edit()
            .putString(KEY_ACTIVE_GROUP_CODE, group.code)
            .putString(KEY_RIDE_CODE, group.code)
            .apply()
        requestStart()
    }

    private fun deactivateActiveGroup() {
        prefs.edit().remove(KEY_ACTIVE_GROUP_CODE).apply()
        stopRide()
    }

    private fun openActiveGroupFromMap() {
        val activeCode = currentState.rideId.takeIf { it.isNotBlank() }
            ?: prefs.getString(KEY_ACTIVE_GROUP_CODE, null)
            ?: return
        val group = loadGroups().firstOrNull { it.code == activeCode } ?: LocalGroup(activeCode, groupNameForCode(activeCode)).also {
            saveGroup(it)
        }
        switchTab("group")
        renderGroupDetail(group.code)
    }

    private fun stopRide() {
        val intent = Intent(this, LiveLocationService::class.java)
            .setAction(LiveLocationService.ACTION_STOP)
        startService(intent)
        endWalkieTalkie()
    }

    private fun handleWalkieClick(groupCode: String) {
        if (!requireGoogleIdentity("use walkie talkie")) return
        val voice = walkieTalkie.currentState()
        Log.i(TAG, "Walkie clicked group=$groupCode joined=${voice.joined} voiceGroup=${voice.groupCode} talking=${voice.talking}")
        if (!rideActive || currentState.rideId != groupCode) {
            loadGroups().firstOrNull { it.code == groupCode }?.let { group ->
                activateGroup(group)
                return
            }
        }
        if (!voice.joined || voice.groupCode != groupCode) {
            requestWalkieTalkie(groupCode)
            return
        }
        if (voice.onHold) {
            statusView.text = "Walkie talkie is on hold during phone call."
            if (::walkieStatusView.isInitialized) {
                walkieStatusView.text = "Walkie talkie is on hold during phone call."
            }
            return
        }
        walkieTalkie.setTalking(!voice.talking)
        syncWalkieForeground(walkieTalkie.currentState())
    }

    private fun requestWalkieTalkie(groupCode: String) {
        if (!rideActive || currentState.rideId != groupCode) {
            statusView.text = "Make this group ACTIVE before using walkie talkie."
            if (::walkieStatusView.isInitialized) {
                walkieStatusView.text = "Make this group ACTIVE before using walkie talkie."
            }
            Log.w(TAG, "Walkie blocked because group is not active. rideActive=$rideActive currentRide=${currentState.rideId} group=$groupCode")
            return
        }
        if (activeRiderCount(groupCode) < MIN_WALKIE_RIDERS) {
            showWalkieWaitingPopup()
            return
        }
        if (!hasAudioPermission()) {
            pendingWalkieGroup = groupCode
            Log.i(TAG, "Requesting microphone permission for walkie")
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_AUDIO)
            return
        }
        joinWalkieTalkie(groupCode)
    }

    private fun joinWalkieTalkie(groupCode: String) {
        Log.i(TAG, "Joining walkie group=$groupCode")
        if (walkieTalkie.join(groupCode, riderId)) {
            statusView.text = "Walkie talkie connected for $groupCode."
            syncWalkieForeground(walkieTalkie.currentState())
        } else {
            statusView.text = walkieTalkie.currentState().message
        }
    }

    private fun endWalkieTalkie() {
        walkieTalkie.leave()
        stopWalkieForeground()
        statusView.text = "Walkie talkie ended."
        if (::walkieStatusView.isInitialized) {
            walkieStatusView.text = "Start walkie talkie to listen. Tap again to talk."
        }
        renderWalkieState(walkieTalkie.currentState())
    }

    private fun renderWalkieState(voice: AgoraWalkieTalkie.State) {
        if (!::walkieButton.isInitialized || !::walkieStatusView.isInitialized) return
        val activeGroup = selectedGroupCode != null && rideActive && currentState.rideId == selectedGroupCode
        val enoughRiders = selectedGroupCode != null && activeRiderCount(selectedGroupCode.orEmpty()) >= MIN_WALKIE_RIDERS
        walkieButton.isEnabled = selectedGroupCode != null
        walkieButton.text = when {
            !activeGroup -> "MAKE GROUP ACTIVE"
            !enoughRiders && !voice.joined -> "WAITING FOR RIDERS"
            voice.onHold -> "ON HOLD"
            !voice.joined || voice.groupCode != selectedGroupCode -> "START WALKIE"
            voice.talking -> "STOP TALKING"
            else -> "TAP TO TALK"
        }
        walkieButton.background = when {
            voice.onHold -> rounded(Color.rgb(71, 85, 105), dp(10), stroke = Color.rgb(148, 163, 184), strokeWidth = 2)
            voice.talking -> gradientRounded(Color.rgb(127, 29, 29), DANGER, dp(10), stroke = RED, strokeWidth = 2)
            voice.joined && voice.groupCode == selectedGroupCode -> gradientRounded(Color.rgb(7, 91, 50), GREEN, dp(10))
            activeGroup && !enoughRiders -> rounded(PILL, dp(10), stroke = CARD_STROKE)
            activeGroup -> gradientRounded(Color.rgb(7, 91, 50), GREEN, dp(10), stroke = Color.rgb(34, 197, 94))
            else -> gradientRounded(Color.rgb(8, 83, 45), Color.rgb(22, 163, 74), dp(10), stroke = Color.rgb(34, 197, 94))
        }
        if (::walkieEndButton.isInitialized) {
            val showEnd = selectedGroupCode != null &&
                voice.groupCode == selectedGroupCode &&
                (voice.joined || voice.groupCode.isNotBlank())
            walkieEndButton.visibility = if (showEnd) View.VISIBLE else View.GONE
            walkieEndButton.isEnabled = showEnd
        }
        walkieStatusView.text = walkieStatusText(voice, activeGroup, enoughRiders)
    }

    private fun enforceWalkieActiveGroup(state: RideState) {
        val voice = walkieTalkie.currentState()
        if (!voice.joined) return
        if (!state.active || state.rideId.isBlank() || voice.groupCode != state.rideId) {
            Log.i(TAG, "Leaving walkie because active group changed. voiceGroup=${voice.groupCode} activeGroup=${state.rideId} active=${state.active}")
            walkieTalkie.leave()
            stopWalkieForeground()
        }
    }

    private fun leaveWalkieIfDifferentGroup(nextGroupCode: String) {
        val voice = walkieTalkie.currentState()
        if (voice.joined && voice.groupCode != nextGroupCode) {
            Log.i(TAG, "Leaving walkie before switching active group from ${voice.groupCode} to $nextGroupCode")
            walkieTalkie.leave()
            stopWalkieForeground()
        }
    }

    private fun walkieStatusText(voice: AgoraWalkieTalkie.State, activeGroup: Boolean, enoughRiders: Boolean): String {
        if (!activeGroup) return "Make this group ACTIVE to start rider voice communication."
        return when {
            voice.message.contains("AGORA_APP_ID") -> voice.message
            voice.onHold -> "On hold during phone call. Walkie talkie will resume automatically when the call ends."
            !enoughRiders && !voice.joined -> "Waiting for at least 2 active riders before walkie talkie can start."
            voice.talking -> "Your microphone is live. Tap STOP TALKING when done."
            voice.joined -> "Listening to group voice. ${voice.speakerCount} rider(s) connected."
            else -> "Start walkie talkie to listen. Tap again to talk."
        }
    }

    private fun activeRiderCount(groupCode: String): Int {
        if (!rideActive || currentState.rideId != groupCode) return 0
        val self = 1
        val others = currentState.riders.size
        return self + others
    }

    private fun showWalkieWaitingPopup() {
        val message = "Wait until at least 2 riders join and activate this group."
        statusView.text = message
        if (::walkieStatusView.isInitialized) walkieStatusView.text = message
        AlertDialog.Builder(this)
            .setTitle("Walkie talkie waiting")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun render(state: RideState) {
        currentState = state
        clearMissingSelectedRider(state)
        rideActive = state.active
        hasRegroupPoint = state.regroupPoint != null
        if (state.active) {
            stopPreviewLocation()
        } else {
            requestPreviewLocationIfNeeded()
        }
        enforceWalkieActiveGroup(state)
        startButton.isEnabled = !state.active
        stopButton.isEnabled = state.active
        sosButton.isEnabled = true
        styleSosButton(active = state.active)
        regroupButton.isEnabled = true
        styleRegroupButton(active = state.active, hasPoint = hasRegroupPoint)
        statusView.text = locationStatus(state)
        val mapState = previewMapState(state)
        mapView.setState(mapState)
        updateMapOverlays(mapState)
        routePendingDestinationIfPossible()
        updateGroupTab(state)
        highlightMode(state.updateMode)
    }

    private fun clearMissingSelectedRider(state: RideState) {
        val selectedId = selectedMapRiderId ?: return
        if (selectedId == SELF_RIDER_ID || state.riders.containsKey(selectedId)) return
        if (::mapView.isInitialized) mapView.clearTemporaryRider()
        selectedMapRiderId = null
        if (::riderDetailCard.isInitialized) riderDetailCard.visibility = View.GONE
    }

    private fun updateMapOverlays(state: RideState) {
        val now = System.currentTimeMillis()
        val own = state.ownLocation
        val riders = state.riders.values
            .filter { !it.isStale(now) }
            .sortedBy { if (own == null) 0f else own.distanceTo(it) }

        livePill.visibility = if (state.active && state.rideId.isNotBlank()) View.VISIBLE else View.GONE
        livePill.text = "Live  ${state.rideId}"
        livePill.background = statusPill(state.active)
        mapSearchPanel.visibility = View.VISIBLE
        mapSearchPanel.bringToFront()
        mapView.setGlobalEvents(globalBikeEvents.values, show = !state.active)
        sosButton.visibility = if (state.active) View.VISIBLE else View.GONE
        regroupButton.visibility = if (state.active) View.VISIBLE else View.GONE
        onlinePill.text = if (state.active) {
            "Online\n${riders.size} riders online"
        } else {
            "Preview\nOnly you"
        }
        styleRegroupButton(active = state.active, hasPoint = state.regroupPoint != null)
        regroupButton.alpha = if (state.active && !isAdminForGroup(state.rideId)) 0.42f else 1f
        sosSafeButton.visibility = if (state.groupAlert?.riderId == state.riderId && state.active) {
            View.VISIBLE
        } else {
            View.GONE
        }
        updateSelectedRiderDetail(state, now)
        updateSosAlertDetail(state, now)
        ridersMiniView.visibility = if (riders.isEmpty()) View.GONE else View.VISIBLE
        ridersMiniView.text = riders.take(3).joinToString(separator = "\n") { rider ->
            val distance = if (own == null) "" else "  ${own.distanceTo(rider).toInt()} m"
            "${rider.label}$distance"
        }
    }

    private fun previewMapState(state: RideState): RideState {
        if (state.active || state.ownLocation != null) return state
        val preview = previewSnapshot ?: return state
        return state.copy(
            ownLocation = preview,
            status = "Previewing your location"
        )
    }

    private fun requestPreviewLocationIfNeeded() {
        if (!::mapView.isInitialized || rideActive) return
        if (!hasLocationPermission()) {
            if (!previewLocationPermissionAsked) {
                previewLocationPermissionAsked = true
                requestPermissions(
                    arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
                    REQUEST_PREVIEW_LOCATION
                )
            }
            return
        }

        val manager = getSystemService(LocationManager::class.java)
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { manager.isProviderEnabled(it) }
        providers
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.let { showPreviewLocation(it) }

        providers.forEach { provider ->
            runCatching {
                manager.requestLocationUpdates(
                    provider,
                    PREVIEW_LOCATION_INTERVAL_MS,
                    PREVIEW_LOCATION_DISTANCE_M,
                    previewLocationListener
                )
            }
        }
    }

    private fun stopPreviewLocation() {
        if (!hasLocationPermission()) return
        runCatching {
            getSystemService(LocationManager::class.java).removeUpdates(previewLocationListener)
        }
    }

    private fun showPreviewLocation(location: Location) {
        if (rideActive) return
        val now = System.currentTimeMillis()
        val snapshot = RiderSnapshot.fromLocation(
            id = riderId,
            name = profileName(),
            location = location,
            nowMs = now
        )
        previewSnapshot = snapshot
        val mapState = previewMapState(currentState)
        mapView.setState(mapState)
        updateMapOverlays(mapState)
        routePendingDestinationIfPossible()
    }

    private fun searchMapDestination() {
        val query = destinationSearchInput.text.toString().trim()
        if (query.isBlank()) {
            statusView.text = "Enter a destination."
            return
        }
        hideKeyboard(destinationSearchInput)
        destinationSearchInput.clearFocus()
        showMapSearchMessage("Searching destination...")
        clearRouteButton.visibility = View.VISIBLE

        val requestId = ++mapNavigationRequestId
        val origin = currentState.ownLocation ?: previewSnapshot
        mapNavigationExecutor.execute {
            runCatching { mapNavigationClient.search(query, origin?.latitude, origin?.longitude) }
                .onSuccess { results ->
                    runOnUiThread {
                        if (requestId != mapNavigationRequestId) return@runOnUiThread
                        val rankedResults = if (origin == null) {
                            results
                        } else {
                            results.sortedBy { result ->
                                approximateDistanceMeters(origin.latitude, origin.longitude, result.latitude, result.longitude)
                            }
                        }
                        if (rankedResults.isEmpty()) {
                            showMapSearchMessage("No destination found. Try adding area or city name.")
                            return@runOnUiThread
                        }
                        if (rankedResults.size == 1) {
                            chooseSearchResult(rankedResults.first())
                        } else {
                            showSearchResults(rankedResults)
                        }
                    }
                }
                .onFailure { error ->
                    runOnUiThread {
                        if (requestId != mapNavigationRequestId) return@runOnUiThread
                        showMapSearchMessage("Search failed. Check internet and try again.")
                    }
                }
        }
    }

    private fun showSearchResults(results: List<PlaceSearchResult>) {
        val labels = results.map { result ->
            val shortAddress = result.address
                .split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() && it != result.name }
                .take(3)
                .joinToString(", ")
            if (shortAddress.isBlank()) result.label else "${result.label}\n$shortAddress"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Choose destination")
            .setItems(labels) { _, which ->
                chooseSearchResult(results[which])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun chooseSearchResult(result: PlaceSearchResult) {
        selectedDestination = result
        pendingRouteDestination = result
        destinationSearchInput.setText(result.label)
        destinationSearchInput.setSelection(destinationSearchInput.text.length)
        mapView.showDestination(result.label, result.latitude, result.longitude)
        requestRouteToDestination(result)
    }

    private fun requestRouteToDestination(destination: PlaceSearchResult) {
        val origin = currentState.ownLocation ?: previewSnapshot
        if (origin == null) {
            pendingRouteDestination = destination
            routeInfoPill.text = "${destination.label}\nWaiting for your location"
            routeInfoPill.visibility = View.VISIBLE
            clearRouteButton.visibility = View.VISIBLE
            showMapSearchMessage("${destination.label}\nWaiting for your location")
            return
        }

        pendingRouteDestination = null
        routeInProgress = true
        routeInfoPill.text = "${destination.label}\nGetting directions..."
        routeInfoPill.visibility = View.VISIBLE
        clearRouteButton.visibility = View.VISIBLE
        showMapSearchMessage("${destination.label}\nGetting directions...")

        val requestId = ++mapNavigationRequestId
        mapNavigationExecutor.execute {
            runCatching {
                mapNavigationClient.route(
                    origin.latitude,
                    origin.longitude,
                    destination.latitude,
                    destination.longitude
                )
            }.onSuccess { route ->
                runOnUiThread {
                    if (requestId != mapNavigationRequestId) return@runOnUiThread
                    routeInProgress = false
                    if (route == null) {
                        statusView.text = "Directions unavailable for this destination."
                        routeInfoPill.text = "${destination.label}\nRoute unavailable"
                        return@runOnUiThread
                    }
                    showRoute(destination, route)
                }
            }.onFailure { error ->
                runOnUiThread {
                    if (requestId != mapNavigationRequestId) return@runOnUiThread
                    routeInProgress = false
                    statusView.text = "Directions failed: ${error.message ?: "network error"}"
                    routeInfoPill.text = "${destination.label}\nDirections failed"
                }
            }
        }
    }

    private fun routePendingDestinationIfPossible() {
        val destination = pendingRouteDestination ?: return
        if (routeInProgress) return
        if ((currentState.ownLocation ?: previewSnapshot) == null) return
        requestRouteToDestination(destination)
    }

    private fun showRoute(destination: PlaceSearchResult, route: RouteResult) {
        mapView.showRoute(route.points)
        routeInfoPill.text = "${destination.label}\n${formatRouteDistance(route.distanceMeters)} - ${formatRouteDuration(route.durationSeconds)}"
        routeInfoPill.visibility = View.VISIBLE
        clearRouteButton.visibility = View.VISIBLE
        statusView.text = "Directions ready."
    }

    private fun showMapSearchMessage(message: String) {
        routeInfoPill.text = message
        routeInfoPill.visibility = View.VISIBLE
    }

    private fun clearMapDirections() {
        selectedDestination = null
        pendingRouteDestination = null
        routeInProgress = false
        mapNavigationRequestId++
        destinationSearchInput.text?.clear()
        routeInfoPill.visibility = View.GONE
        clearRouteButton.visibility = View.GONE
        mapView.clearNavigation()
        statusView.text = locationStatus(currentState)
    }

    private fun formatRouteDistance(distanceMeters: Double): String {
        return if (distanceMeters >= 1000.0) {
            String.format(Locale.US, "%.1f km", distanceMeters / 1000.0)
        } else {
            "${distanceMeters.toInt().coerceAtLeast(0)} m"
        }
    }

    private fun formatRouteDuration(durationSeconds: Double): String {
        val minutes = ((durationSeconds + 30.0) / 60.0).toInt().coerceAtLeast(1)
        return if (minutes >= 60) {
            val hours = minutes / 60
            val remaining = minutes % 60
            if (remaining == 0) "${hours} hr" else "${hours} hr ${remaining} min"
        } else {
            "$minutes min"
        }
    }

    private fun approximateDistanceMeters(
        fromLatitude: Double,
        fromLongitude: Double,
        toLatitude: Double,
        toLongitude: Double
    ): Float {
        val result = FloatArray(1)
        Location.distanceBetween(fromLatitude, fromLongitude, toLatitude, toLongitude, result)
        return result[0]
    }

    private fun openRiderOnMap(riderId: String?) {
        selectedMapRiderId = riderId ?: SELF_RIDER_ID
        switchTab("map")
        val now = System.currentTimeMillis()
        if (riderId == null) {
            mapView.clearTemporaryRider()
            mapView.centerOnMe()
        } else {
            val snapshot = currentState.riders[riderId]
            if (snapshot != null && snapshot.isStale(now)) {
                mapView.showTemporaryRider(snapshot)
                statusView.text = "Showing ${snapshot.label}'s last known location."
            } else {
                mapView.clearTemporaryRider()
                mapView.focusOnRider(riderId)
            }
        }
        updateSelectedRiderDetail(currentState, now)
        scheduleRiderDetailTimeout()
    }

    private fun updateSelectedRiderDetail(state: RideState, nowMs: Long) {
        if (!::riderDetailCard.isInitialized || !::riderDetailText.isInitialized) return
        val selectedId = selectedMapRiderId
        if (selectedId == null) {
            riderDetailCard.visibility = View.GONE
            return
        }

        val snapshot = if (selectedId == SELF_RIDER_ID) state.ownLocation else state.riders[selectedId]
        if (snapshot == null) {
            visibleSosAlertTimestampMs = 0L
            selectedMapRiderId = null
            riderDetailCard.visibility = View.GONE
            return
        }

        visibleSosAlertTimestampMs = 0L
        styleRiderDetailCard()
        val distance = if (selectedId == SELF_RIDER_ID) {
            "You"
        } else {
            state.ownLocation?.let { "${formatDistance(it.distanceTo(snapshot))} away" } ?: "Distance unavailable"
        }
        val statusLine = if (snapshot.isStale(nowMs)) {
            riderFreshnessText(snapshot, nowMs)
        } else {
            "Active - ${riderMovementText(snapshot, nowMs)}"
        }
        riderDetailText.text = buildRiderDetailText(snapshot.label, snapshot.ageSeconds(nowMs), distance, statusLine)
        riderDetailCard.visibility = View.VISIBLE
    }

    private fun updateSosAlertDetail(state: RideState, nowMs: Long) {
        val alert = state.groupAlert
        if (alert == null) {
            if (visibleSosAlertTimestampMs > 0L) {
                visibleSosAlertTimestampMs = 0L
                if (::riderDetailCard.isInitialized) riderDetailCard.visibility = View.GONE
            }
            return
        }
        if (alert.timestampMs <= dismissedSosAlertTimestampMs) return
        if (selectedMapRiderId != null && visibleSosAlertTimestampMs != alert.timestampMs) return
        showSosAlertDetail(state, alert, nowMs, force = visibleSosAlertTimestampMs == alert.timestampMs)
    }

    private fun showSosAlertDetail(state: RideState, alert: GroupAlert, nowMs: Long, force: Boolean) {
        if (!force && alert.timestampMs <= dismissedSosAlertTimestampMs) return
        if (!::riderDetailCard.isInitialized || !::riderDetailText.isInitialized) return
        selectedMapRiderId = null
        visibleSosAlertTimestampMs = alert.timestampMs
        styleSosDetailCard()
        riderDetailText.text = buildSosDetailText(state, alert, nowMs)
        riderDetailCard.visibility = View.VISIBLE
        scheduleRiderDetailTimeout()
    }

    private fun styleRiderDetailCard() {
        riderDetailCard.background = rounded(Color.rgb(219, 239, 255), dp(8), stroke = Color.BLACK, strokeWidth = 1)
        riderDetailText.setTextColor(Color.rgb(15, 23, 42))
    }

    private fun styleSosDetailCard() {
        riderDetailCard.background = rounded(Color.argb(210, 255, 255, 255), dp(8), stroke = RED, strokeWidth = 2)
        riderDetailText.setTextColor(Color.rgb(31, 41, 55))
    }

    private fun buildSosDetailText(state: RideState, alert: GroupAlert, nowMs: Long): SpannableString {
        val ageSeconds = ((nowMs - alert.timestampMs).coerceAtLeast(0L)) / 1000L
        val distance = sosAlertSnapshot(state, alert)?.let { snapshot ->
            state.ownLocation?.let { "${formatDistance(it.distanceTo(snapshot))} away" }
        } ?: "Location marked on map"
        val header = "SOS  ${alert.riderName}"
        val body = "${alert.message} - ${formatDurationShort(ageSeconds)} ago\n$distance"
        val text = "$header\n$body"
        return SpannableString(text).apply {
            val bodyStart = header.length + 1
            setSpan(StyleSpan(Typeface.BOLD), 0, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(RelativeSizeSpan(1.18f), 0, header.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(RED), 0, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(Color.rgb(15, 23, 42)), 5, header.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(RelativeSizeSpan(0.96f), bodyStart, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(Color.rgb(31, 41, 55)), bodyStart, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun sosAlertSnapshot(state: RideState, alert: GroupAlert): RiderSnapshot? {
        val lat = alert.latE7
        val lon = alert.lonE7
        if (lat != null && lon != null) {
            return RiderSnapshot(
                id = alert.riderId,
                name = alert.riderName,
                latE7 = lat,
                lonE7 = lon,
                speedCentiMps = 0,
                bearingDeg = -1,
                accuracyM = -1,
                updatedAtMs = alert.timestampMs
            )
        }
        state.ownLocation?.takeIf { it.id == alert.riderId }?.let { return it }
        return state.riders[alert.riderId]
    }

    private fun riderTitleText(name: String, secondsText: String, admin: Boolean): SpannableString {
        val updateText = if (secondsText == "--") "updated --" else "updated $secondsText ago"
        val adminText = if (admin) "  ADMIN" else ""
        val text = "$name$adminText  $updateText"
        return SpannableString(text).apply {
            val adminStart = name.length + 2
            val updateStart = name.length + adminText.length + 2
            setSpan(StyleSpan(Typeface.BOLD), 0, name.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(Color.WHITE), 0, name.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (admin) {
                setSpan(StyleSpan(Typeface.BOLD), adminStart, name.length + adminText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(RelativeSizeSpan(0.72f), adminStart, name.length + adminText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(GREEN), adminStart, name.length + adminText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            setSpan(RelativeSizeSpan(0.78f), updateStart, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(Color.rgb(148, 163, 184)), updateStart, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun buildRiderDetailText(name: String, ageSeconds: Long, distance: String, statusLine: String): SpannableString {
        val updateText = "updated ${formatDurationShort(ageSeconds)} ago"
        val header = "$name  $updateText"
        val body = "$distance\n$statusLine"
        val text = "$header\n$body"
        return SpannableString(text).apply {
            val updateStart = name.length + 2
            val bodyStart = header.length + 1
            setSpan(StyleSpan(Typeface.BOLD), 0, name.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(RelativeSizeSpan(1.12f), 0, name.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(Color.rgb(15, 23, 42)), 0, name.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(RelativeSizeSpan(0.82f), updateStart, header.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(Color.rgb(100, 116, 139)), updateStart, header.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(RelativeSizeSpan(0.96f), bodyStart, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(Color.rgb(30, 41, 59)), bodyStart, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun hideRiderDetail() {
        selectedMapRiderId = null
        if (visibleSosAlertTimestampMs > 0L) {
            dismissedSosAlertTimestampMs = visibleSosAlertTimestampMs
            visibleSosAlertTimestampMs = 0L
        }
        if (::mapPage.isInitialized) mapPage.removeCallbacks(hideRiderDetailRunnable)
        if (::riderDetailCard.isInitialized) riderDetailCard.visibility = View.GONE
    }

    private fun scheduleRiderDetailTimeout() {
        if (!::mapPage.isInitialized) return
        mapPage.removeCallbacks(hideRiderDetailRunnable)
        mapPage.postDelayed(hideRiderDetailRunnable, RIDER_DETAIL_TIMEOUT_MS)
    }

    private fun formatDistance(distanceM: Float): String {
        return if (distanceM >= 1000f) {
            String.format(Locale.US, "%.1f km", distanceM / 1000f)
        } else {
            "${distanceM.toInt()} m"
        }
    }

    private fun updateGroupTab(state: RideState) {
        if (!::groupContent.isInitialized) return
        val detailCode = selectedGroupCode
        if (detailCode == null) {
            renderGroupList()
        } else {
            renderGroupDetail(detailCode)
        }
    }

    private fun switchTab(tab: String) {
        if (selectedTab == "map" && tab != "map" && ::mapView.isInitialized) {
            mapView.clearTemporaryRider()
            if (selectedMapRiderId != null) {
                hideRiderDetail()
            }
        }
        selectedTab = tab
        mapPage.visibility = if (tab == "map") View.VISIBLE else View.GONE
        groupPage.visibility = if (tab == "group") View.VISIBLE else View.GONE
        eventsPage.visibility = if (tab == "events") View.VISIBLE else View.GONE
        profilePage.visibility = if (tab == "profile") View.VISIBLE else View.GONE
        topBar.visibility = if (tab == "map") View.GONE else View.VISIBLE
        pageTitle.text = when (tab) {
            "group" -> "Group"
            "events" -> "Events"
            "profile" -> "Profile"
            else -> "Map"
        }
        updateTopBarBackButton()
        if (tab == "events") renderEventsPage()
        if (tab == "profile") updateBatteryStatus()
        setupPanel.visibility = View.GONE
        styleBottomTabs()
    }

    private fun updateTopBarBackButton() {
        if (!::topBarBackButton.isInitialized) return
        topBarBackButton.visibility = if (selectedTab == "group" && selectedGroupCode != null) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun setMode(mode: UpdateMode) {
        dispatchServiceAction(LiveLocationService.ACTION_SET_MODE, LiveLocationService.EXTRA_MODE, mode.name)
    }

    private fun dispatchServiceAction(action: String, extraKey: String? = null, extraValue: String? = null) {
        val intent = Intent(this, LiveLocationService::class.java).setAction(action)
        if (extraKey != null && extraValue != null) intent.putExtra(extraKey, extraValue)
        startService(intent)
    }

    private fun refreshActiveTracking() {
        if (!rideActive || currentState.rideId.isBlank()) return
        startService(Intent(this, LiveLocationService::class.java).setAction(LiveLocationService.ACTION_REFRESH))
    }

    private fun syncWalkieForeground(state: AgoraWalkieTalkie.State) {
        val action = if (state.groupCode.isBlank() && !state.joined) {
            WalkieForegroundService.ACTION_DISMISS
        } else {
            WalkieForegroundService.ACTION_UPDATE
        }
        val intent = Intent(this, WalkieForegroundService::class.java).setAction(action)
        if (action == WalkieForegroundService.ACTION_UPDATE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopWalkieForeground() {
        startService(Intent(this, WalkieForegroundService::class.java).setAction(WalkieForegroundService.ACTION_DISMISS))
    }

    private fun highlightMode(mode: UpdateMode) {
        if (!::modeEcoButton.isInitialized || !::modeNormalButton.isInitialized || !::modeFastButton.isInitialized) return
        val inactive = rounded(Color.rgb(13, 15, 20), dp(10), stroke = Color.rgb(45, 52, 62))
        val active = gradientRounded(Color.rgb(20, 53, 98), Color.rgb(14, 39, 76), dp(10), stroke = BLUE, strokeWidth = 2)
        modeEcoButton.background = if (mode == UpdateMode.ECO) active else inactive
        modeNormalButton.background = if (mode == UpdateMode.NORMAL) active else inactive
        modeFastButton.background = if (mode == UpdateMode.FAST) active else inactive
        modeEcoButton.setTextColor(if (mode == UpdateMode.ECO) Color.WHITE else MUTED)
        modeNormalButton.setTextColor(if (mode == UpdateMode.NORMAL) Color.WHITE else MUTED)
        modeFastButton.setTextColor(if (mode == UpdateMode.FAST) Color.WHITE else MUTED)
    }

    private fun styleBottomTabs() {
        if (!::bottomNav.isInitialized) return
        val targetItemId = when (selectedTab) {
            "group" -> R.id.nav_group
            "events" -> R.id.nav_events
            "profile" -> R.id.nav_profile
            else -> R.id.nav_map
        }
        if (bottomNav.selectedItemId != targetItemId) {
            updatingBottomNav = true
            bottomNav.selectedItemId = targetItemId
            updatingBottomNav = false
        }
    }

    private fun saveProfile() {
        prefs.edit()
            .putString(KEY_RIDER_NAME, profileNameInput.text.toString().trim())
            .putString(KEY_CONTACT, profileContactInput.text.toString().trim())
            .putString(KEY_DOB, profileDobInput.text.toString().trim())
            .putString(KEY_BLOOD_GROUP, selectedBloodGroup())
            .putString(KEY_BIKE, profileBikeInput.text.toString().trim())
            .putString(KEY_EMERGENCY_CONTACT, profileEmergencyInput.text.toString().trim())
            .apply()
        syncActiveRiderName()
    }

    private fun setProfileEditMode(enabled: Boolean, button: Button? = null) {
        profileEditMode = enabled
        listOf(profileNameInput, profileContactInput, profileBikeInput, profileEmergencyInput).forEach { field ->
            field.isEnabled = enabled
            field.isFocusable = enabled
            field.isFocusableInTouchMode = enabled
            field.isCursorVisible = enabled
            field.alpha = 1f
            field.setTextColor(Color.rgb(15, 23, 42))
            field.setHintTextColor(Color.rgb(100, 116, 139))
            field.background = profileFieldBackground()
        }
        profileDobInput.isEnabled = enabled
        profileDobInput.isClickable = enabled
        profileDobInput.isFocusable = false
        profileDobInput.isCursorVisible = false
        profileDobInput.alpha = 1f
        profileDobInput.setTextColor(Color.rgb(15, 23, 42))
        profileDobInput.setHintTextColor(Color.rgb(100, 116, 139))
        profileDobInput.background = profileFieldBackground()
        profileBloodInput.isEnabled = enabled
        profileBloodInput.alpha = 1f
        profileBloodInput.background = profileFieldBackground()
        button?.text = if (enabled) "SAVE CHANGES" else "EDIT PROFILE"
    }

    private fun validateProfileInputs(): Boolean {
        val name = profileNameInput.text.toString().trim()
        val contact = profileContactInput.text.toString().trim()
        val blood = selectedBloodGroup()
        val emergency = profileEmergencyInput.text.toString().trim()
        var valid = true

        profileNameInput.error = null
        profileContactInput.error = null
        profileDobInput.error = null
        profileEmergencyInput.error = null
        profileBloodInput.background = profileFieldBackground()

        if (!name.matches(Regex("^[A-Za-z][A-Za-z ]{1,39}$"))) {
            profileNameInput.error = "Use letters only"
            valid = false
        }
        if (!contact.matches(Regex("^[0-9]{10}$"))) {
            profileContactInput.error = "Enter 10 digit mobile number"
            valid = false
        }
        if (blood.isBlank()) {
            profileBloodInput.background = profileFieldBackground(error = true)
            valid = false
        }
        if (!emergency.matches(Regex("^[0-9]{10}$"))) {
            profileEmergencyInput.error = "Enter 10 digit emergency number"
            valid = false
        }
        if (!valid) statusView.text = "Please fix profile details."
        return valid
    }

    private fun syncActiveRiderName() {
        if (!rideActive || currentState.rideId.isBlank()) return
        val name = profileName()
        dispatchServiceAction(
            LiveLocationService.ACTION_UPDATE_RIDER_NAME,
            LiveLocationService.EXTRA_RIDER_NAME,
            name
        )
    }

    private fun profileName(): String {
        return profileNameInput.text.toString().trim()
            .ifBlank { prefs.getString(KEY_RIDER_NAME, null).orEmpty() }
            .ifBlank { "Rider" }
    }

    private fun shareInvite(groupCode: String? = null) {
        saveProfile()
        val rideCode = groupCode?.trim()?.uppercase(Locale.US)
            ?: selectedGroupCode
            ?: prefs.getString(KEY_ACTIVE_GROUP_CODE, null)
            ?: rideCodeInput.text.toString().trim().uppercase(Locale.US).ifBlank { "MORNING-RIDE" }
        val joinLink = inviteLink(rideCode)
        prefs.edit()
            .putString(KEY_RIDE_CODE, rideCode)
            .apply()

        val message = """
            Join my CoRider group.

            Ride code: $rideCode
            $joinLink

            If the link opens in browser, tap Open CoRider. If needed, open CoRider > Group > Join Group and enter the ride code.
        """.trimIndent()

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Join my CoRider group")
            putExtra(Intent.EXTRA_TEXT, message)
        }
        startActivity(Intent.createChooser(intent, "Invite riders"))
    }

    private fun handleJoinIntent(source: Intent?) {
        val data = source?.data ?: return
        val rideCode = extractRideCode(data) ?: return
        val groupName = data.getQueryParameter("name")?.trim()?.takeIf { it.isNotBlank() } ?: "Ride $rideCode"
        saveGroup(LocalGroup(rideCode, groupName))
        rideCodeInput.setText(rideCode)
        prefs.edit().putString(KEY_RIDE_CODE, rideCode).apply()
        switchTab("group")
        renderGroupDetail(rideCode)
        statusView.text = "Invite loaded. Open the group and toggle ACTIVE to share."
    }

    private fun extractRideCode(uri: Uri): String? {
        val code = uri.getQueryParameter("ride") ?: uri.getQueryParameter("code")
        return code?.trim()?.uppercase(Locale.US)?.takeIf { it.isNotBlank() }
    }

    private fun deepLinkUri(rideCode: String): Uri {
        return Uri.Builder()
            .scheme("corider")
            .authority("join")
            .appendQueryParameter("ride", rideCode)
            .appendQueryParameter("name", groupNameForCode(rideCode))
            .build()
    }

    private fun inviteLink(rideCode: String): String {
        return Uri.Builder()
            .scheme("https")
            .authority(INVITE_HOST)
            .path(INVITE_PATH)
            .appendQueryParameter("ride", rideCode)
            .appendQueryParameter("name", groupNameForCode(rideCode))
            .build()
            .toString()
    }

    private fun loadGroups(): List<LocalGroup> {
        return prefs.getStringSet(KEY_GROUPS, emptySet()).orEmpty()
            .mapNotNull { encoded ->
                val parts = encoded.split("|", limit = 2)
                val code = parts.getOrNull(0)?.trim().orEmpty()
                val name = parts.getOrNull(1)?.trim().orEmpty()
                if (code.isBlank()) null else LocalGroup(code, name.ifBlank { "Ride $code" })
            }
            .distinctBy { it.code }
            .sortedBy { it.name.lowercase(Locale.US) }
    }

    private fun saveGroup(group: LocalGroup) {
        val groups = LinkedHashMap<String, LocalGroup>()
        loadGroups().forEach { groups[it.code] = it }
        groups[group.code] = group
        prefs.edit()
            .putStringSet(KEY_GROUPS, groups.values.map { "${it.code}|${it.name}" }.toSet())
            .apply()
    }

    private fun confirmDeleteGroup(group: LocalGroup) {
        AlertDialog.Builder(this)
            .setTitle("Delete group")
            .setMessage("Delete ${groupListTitle(group)} from this phone?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ -> deleteGroup(group) }
            .show()
    }

    private fun deleteGroup(group: LocalGroup) {
        if (!requireGoogleIdentity("delete groups")) return
        if (isAdminForGroup(group.code) || isLocalAdminGroup(group.code)) {
            FirebaseDatabase.getInstance()
                .getReference("rides/${group.code}")
                .removeValue()
                .addOnSuccessListener {
                    removeLocalGroup(group)
                    statusView.text = "${groupListTitle(group)} deleted for everyone."
                }
                .addOnFailureListener { error ->
                    statusView.text = "Group delete failed: ${error.message ?: "network error"}"
                }
            return
        }
        removeLocalGroup(group)
        statusView.text = "${groupListTitle(group)} removed from this phone."
    }

    private fun removeLocalGroup(group: LocalGroup) {
        val remaining = loadGroups().filterNot { it.code == group.code }
        val editor = prefs.edit()
            .putStringSet(KEY_GROUPS, remaining.map { "${it.code}|${it.name}" }.toSet())
        if (prefs.getString(KEY_ACTIVE_GROUP_CODE, "").orEmpty() == group.code || currentState.rideId == group.code) {
            editor.remove(KEY_ACTIVE_GROUP_CODE)
            stopRide()
        }
        editor.apply()
        if (selectedGroupCode == group.code) selectedGroupCode = null
        renderGroupList()
    }

    private fun nextGroupCode(): String {
        return "RIDE-${UUID.randomUUID().toString().take(4).uppercase(Locale.US)}"
    }

    private fun groupNameForCode(code: String): String {
        return loadGroups().firstOrNull { it.code == code }?.name ?: "Ride $code"
    }

    private fun refreshGroupMemberCounts(groups: List<LocalGroup>) {
        groups.forEach { group ->
            if (groupMemberCounts.containsKey(group.code) || !groupMemberCountLoading.add(group.code)) return@forEach
            FirebaseDatabase.getInstance()
                .getReference("rides/${group.code}/riders")
                .get()
                .addOnSuccessListener { snapshot ->
                    groupMemberCountLoading.remove(group.code)
                    val count = snapshot.childrenCount.toInt()
                    if (groupMemberCounts[group.code] != count) {
                        groupMemberCounts[group.code] = count
                        if (selectedTab == "group" && selectedGroupCode == null) {
                            renderGroupList()
                        }
                    }
                }
                .addOnFailureListener {
                    groupMemberCountLoading.remove(group.code)
                }
        }
    }

    private fun groupMemberText(group: LocalGroup, isActive: Boolean): String {
        val count = if (isActive && currentState.rideId == group.code) {
            1 + currentState.riders.size
        } else {
            groupMemberCounts[group.code]
        }
        return if (count == null) "Members updating..." else "$count ${if (count == 1) "member" else "members"}"
    }

    private fun loadLocalAdminGroups(): Set<String> {
        return prefs.getStringSet(KEY_ADMIN_GROUPS, emptySet()).orEmpty()
    }

    private fun saveLocalAdminGroups() {
        prefs.edit().putStringSet(KEY_ADMIN_GROUPS, adminGroups.toSet()).apply()
    }

    private fun isLocalAdminGroup(groupCode: String): Boolean {
        return groupCode.isNotBlank() && (groupCode in adminGroups || groupCode in loadLocalAdminGroups())
    }

    private fun isAdminForGroup(groupCode: String): Boolean {
        if (groupCode.isBlank()) return false
        return isLocalAdminGroup(groupCode) || riderId in adminRidersByGroup[groupCode].orEmpty()
    }

    private fun markGroupAdmin(groupCode: String) {
        if (groupCode.isBlank()) return
        adminGroups.add(groupCode)
        saveLocalAdminGroups()
        adminRidersByGroup.getOrPut(groupCode) { mutableSetOf() }.add(riderId)
        writeAdminRole(groupCode, riderId)
    }

    private fun writeAdminRole(groupCode: String, adminRiderId: String) {
        if (groupCode.isBlank() || adminRiderId.isBlank()) return
        withFirebaseAuth {
            val root = FirebaseDatabase.getInstance().getReference("rides/$groupCode")
            root.child("admins").child(adminRiderId).setValue(true)
        }
    }

    private fun refreshGroupAdminsIfNeeded(groupCode: String) {
        val last = adminRoleFetchedAtMs[groupCode] ?: 0L
        if (System.currentTimeMillis() - last < ADMIN_ROLE_REFRESH_MS) return
        refreshGroupAdmins(groupCode, force = false)
    }

    private fun refreshGroupAdmins(groupCode: String, force: Boolean) {
        if (groupCode.isBlank()) return
        if (!force && !adminRoleLoading.add(groupCode)) return
        if (force && groupCode in adminRoleLoading) return
        if (force) adminRoleLoading.add(groupCode)

        withFirebaseAuth {
            FirebaseDatabase.getInstance()
                .getReference("rides/$groupCode/admins")
                .get()
                .addOnSuccessListener { snapshot ->
                    val admins = mutableSetOf<String>()
                    snapshot.children.forEach { child ->
                        if (child.getValue(Boolean::class.java) == true) {
                            child.key?.let { admins.add(it) }
                        }
                    }
                    if (admins.isEmpty() && isLocalAdminGroup(groupCode)) {
                        admins.add(riderId)
                        writeAdminRole(groupCode, riderId)
                    }
                    adminRidersByGroup[groupCode] = admins
                    if (riderId in admins) {
                        adminGroups.add(groupCode)
                        saveLocalAdminGroups()
                    }
                    adminRoleFetchedAtMs[groupCode] = System.currentTimeMillis()
                    if (selectedTab == "group" && selectedGroupCode == groupCode) {
                        renderGroupDetail(groupCode)
                    }
                    if (currentState.rideId == groupCode) {
                        updateMapOverlays(currentState)
                    }
                }
                .addOnFailureListener { error ->
                    statusView.text = "Admin role sync failed: ${error.message ?: "network error"}"
                }
                .addOnCompleteListener {
                    adminRoleLoading.remove(groupCode)
                }
        }
    }

    private fun currentRiderIdentityId(): String {
        return currentGoogleUid() ?: getOrCreateRiderId()
    }

    private fun currentGoogleUid(): String? {
        val user = FirebaseAuth.getInstance().currentUser ?: return null
        return if (isGoogleSignedIn(user) && !user.isAnonymous) user.uid else null
    }

    private fun requireGoogleIdentity(action: String): Boolean {
        if (currentGoogleUid() != null) return true
        statusView.text = "Sign in with Google to $action."
        if (selectedTab != "profile") switchTab("profile")
        return false
    }

    private fun writeGroupMetadata(groupCode: String, groupName: String) {
        val uid = currentGoogleUid() ?: return
        val user = FirebaseAuth.getInstance().currentUser
        FirebaseDatabase.getInstance()
            .getReference("rides/$groupCode/meta")
            .updateChildren(
                mapOf(
                    "code" to groupCode,
                    "name" to groupName,
                    "createdByUid" to uid,
                    "createdByEmail" to user?.email.orEmpty().trim().lowercase(Locale.US),
                    "createdByName" to profileName(),
                    "createdAtMs" to System.currentTimeMillis()
                )
            )
    }

    private fun resetLegacyIdentityDataIfNeeded() {
        if (prefs.getBoolean(KEY_GOOGLE_IDENTITY_RESET_DONE, false)) return
        FirebaseAuth.getInstance().signOut()
        prefs.edit()
            .remove(KEY_RIDE_CODE)
            .remove(KEY_RIDER_NAME)
            .remove(KEY_RIDER_ID)
            .remove(KEY_GROUPS)
            .remove(KEY_ADMIN_GROUPS)
            .remove(KEY_ACTIVE_GROUP_CODE)
            .remove(KEY_CONTACT)
            .remove(KEY_DOB)
            .remove(KEY_BLOOD_GROUP)
            .remove(KEY_BIKE)
            .remove(KEY_EMERGENCY_CONTACT)
            .remove(KEY_GOOGLE_EMAIL)
            .remove(KEY_GOOGLE_UID)
            .putBoolean(KEY_GOOGLE_IDENTITY_RESET_DONE, true)
            .apply()
    }

    private fun withFirebaseAuth(block: () -> Unit) {
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser != null) {
            block()
            return
        }
        auth.signInAnonymously()
            .addOnSuccessListener { block() }
            .addOnFailureListener { error ->
                statusView.text = "Firebase auth failed: ${error.message ?: "unknown"}"
            }
    }

    private fun signInWithGoogle() {
        val clientId = googleWebClientId()
        if (clientId.isBlank()) {
            showGoogleSetupDialog()
            return
        }

        googleAuthButton.isEnabled = false
        googleAuthButton.text = "SIGNING IN..."
        statusView.text = "Opening Google sign-in..."

        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(clientId)
            .setAutoSelectEnabled(false)
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        credentialManager.getCredentialAsync(
            this,
            request,
            CancellationSignal(),
            mainThreadExecutor,
            object : CredentialManagerCallback<GetCredentialResponse, GetCredentialException> {
                override fun onResult(result: GetCredentialResponse) {
                    handleGoogleCredentialResponse(result)
                }

                override fun onError(e: GetCredentialException) {
                    googleAuthButton.isEnabled = true
                    updateGoogleAuthUi()
                    statusView.text = "Google sign-in cancelled or failed."
                }
            }
        )
    }

    private fun handleGoogleCredentialResponse(result: GetCredentialResponse) {
        val credential = result.credential
        if (credential !is CustomCredential ||
            credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            googleAuthButton.isEnabled = true
            updateGoogleAuthUi()
            statusView.text = "Google sign-in returned an unsupported credential."
            return
        }

        val googleCredential = runCatching {
            GoogleIdTokenCredential.createFrom(credential.data)
        }.getOrElse { error ->
            googleAuthButton.isEnabled = true
            updateGoogleAuthUi()
            statusView.text = "Could not read Google account: ${error.message ?: "credential error"}"
            return
        }

        val firebaseCredential = GoogleAuthProvider.getCredential(googleCredential.idToken, null)
        FirebaseAuth.getInstance().signInWithCredential(firebaseCredential)
            .addOnSuccessListener { authResult ->
                applyGoogleUserToProfile(authResult.user)
            }
            .addOnFailureListener { error ->
                statusView.text = "Google auth failed: ${error.message ?: "unknown"}"
            }
            .addOnCompleteListener {
                googleAuthButton.isEnabled = true
                updateGoogleAuthUi()
            }
    }

    private fun applyGoogleUserToProfile(user: FirebaseUser?) {
        if (user == null) return
        val googleName = sanitizeGoogleName(user.displayName.orEmpty())
        val googlePhone = normalizedPhoneNumber(user.phoneNumber.orEmpty())

        if (googleName.isNotBlank()) {
            profileNameInput.setText(googleName)
            profileTitleView.text = googleName
        }
        if (googlePhone.isNotBlank()) {
            profileContactInput.setText(googlePhone)
        }

        prefs.edit()
            .putString(KEY_GOOGLE_EMAIL, user.email.orEmpty())
            .putString(KEY_GOOGLE_UID, user.uid)
            .apply()
        saveProfile()

        val missing = mutableListOf<String>()
        if (googlePhone.isBlank()) missing.add("mobile number")
        if (profileDobInput.text.toString().isBlank()) missing.add("DOB")
        statusView.text = if (missing.isEmpty()) {
            "Google connected. Profile updated."
        } else {
            "Google connected. ${missing.joinToString(" and ")} must be added manually."
        }
    }

    private fun signOutGoogle() {
        FirebaseAuth.getInstance().signOut()
        prefs.edit()
            .remove(KEY_GOOGLE_EMAIL)
            .remove(KEY_GOOGLE_UID)
            .apply()
        updateGoogleAuthUi()
        statusView.text = "Google signed out. Guest Firebase auth will continue."
        withFirebaseAuth {}
    }

    private fun updateGoogleAuthUi() {
        if (!::googleAuthButton.isInitialized || !::googleAuthStatusView.isInitialized) return
        val user = FirebaseAuth.getInstance().currentUser
        val signedIn = isGoogleSignedIn(user)
        googleAuthButton.isEnabled = true
        googleAuthButton.text = if (signedIn) "SIGN OUT GOOGLE" else "SIGN IN WITH GOOGLE"
        googleAuthButton.background = rounded(if (signedIn) PILL else BLUE, dp(8), stroke = CARD_STROKE)
        val email = user?.email?.takeIf { it.isNotBlank() } ?: prefs.getString(KEY_GOOGLE_EMAIL, "").orEmpty()
        googleAuthStatusView.text = if (signedIn) {
            "Google connected: $email"
        } else {
            "Sign in with Google to fill your name. Mobile number and DOB may still need manual entry."
        }
        if (::profileSubtitleView.isInitialized) {
            profileSubtitleView.text = if (signedIn && email.isNotBlank()) email else "Rider details"
        }
    }

    private fun isGoogleSignedIn(user: FirebaseUser? = FirebaseAuth.getInstance().currentUser): Boolean {
        return user?.providerData?.any { it.providerId == GoogleAuthProvider.PROVIDER_ID } == true
    }

    private fun googleWebClientId(): String {
        val configured = BuildConfig.GOOGLE_WEB_CLIENT_ID.trim().trim('"')
        if (configured.isNotBlank()) return configured
        val resourceId = resources.getIdentifier("default_web_client_id", "string", packageName)
        return if (resourceId != 0) getString(resourceId).trim() else ""
    }

    private fun showGoogleSetupDialog() {
        AlertDialog.Builder(this)
            .setTitle("Google sign-in setup needed")
            .setMessage("Add GOOGLE_WEB_CLIENT_ID to local.properties, or update app/google-services.json after enabling Google sign-in in Firebase. Then rebuild the APK.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun sanitizeGoogleName(name: String): String {
        return name
            .filter { it.isLetter() || it == ' ' }
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(40)
    }

    private fun normalizedPhoneNumber(phone: String): String {
        val digits = phone.filter { it.isDigit() }
        return if (digits.length >= 10) digits.takeLast(10) else ""
    }

    private fun showDobPicker() {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.YEAR, -18)
        val dialog = DatePickerDialog(
            this,
            { _, year, month, day ->
                calendar.set(year, month, day, 0, 0, 0)
                profileDobInput.setText(SimpleDateFormat("dd MMM yyyy", Locale.US).format(calendar.time))
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        )
        dialog.datePicker.maxDate = System.currentTimeMillis()
        dialog.show()
    }

    private fun riderListText(groupCode: String): String {
        if (!rideActive || currentState.rideId != groupCode) {
            return "Group inactive\nToggle ACTIVE to join this group's live location."
        }

        val now = System.currentTimeMillis()
        val rows = mutableListOf<String>()
        currentState.ownLocation?.let { rows.add("${it.label}  â€¢  Active  â€¢  You") }
        currentState.riders.values
            .sortedBy { it.label.lowercase(Locale.US) }
            .forEach { rider ->
                val status = if (rider.isStale(now)) "Inactive" else "Active"
                rows.add("${rider.label}  â€¢  $status  â€¢  ${rider.ageSeconds(now)}s ago")
            }
        return if (rows.isEmpty()) {
            "No live riders yet."
        } else {
            rows.joinToString(separator = "\n")
        }
    }

    private fun hasRequiredPermissions(): Boolean = hasLocationPermission()

    private fun locationStatus(state: RideState): String {
        val own = state.ownLocation
        val now = System.currentTimeMillis()
        val gpsText = if (own == null) {
            "No local GPS fix yet"
        } else {
            "GPS ${own.ageSeconds(now)}s ago, ${own.accuracyM} m accuracy"
        }
        return "${state.status}\n$gpsText"
    }

    private fun permissionsToRequest(): Array<String> {
        val permissions = mutableListOf<String>()
        if (!hasLocationPermission()) {
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return permissions.toTypedArray()
    }

    private fun hasLocationPermission(): Boolean {
        return checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasAudioPermission(): Boolean {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun isBatteryOptimizationIgnored(): Boolean {
        val powerManager = getSystemService(PowerManager::class.java)
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun batteryOptimizationStatus(): String {
        return if (isBatteryOptimizationIgnored()) {
            "Status: unrestricted for background tracking"
        } else {
            "Status: battery optimized - background tracking may stop"
        }
    }

    private fun updateBatteryStatus() {
        if (!::batteryStatusView.isInitialized) return
        batteryStatusView.text = batteryOptimizationStatus()
        batteryStatusView.setTextColor(if (isBatteryOptimizationIgnored()) GREEN else AMBER)
    }

    private fun openBatterySettings() {
        val packageUri = Uri.parse("package:$packageName")
        val opened = if (!isBatteryOptimizationIgnored()) {
            runCatching {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri))
            }.isSuccess
        } else {
            false
        }
        if (!opened) {
            runCatching {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }.onFailure {
                openAppSettings()
            }
        }
    }

    private fun openAppSettings() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        }
    }

    private fun openRedmiAutostartSettings() {
        val opened = runCatching {
            startActivity(
                Intent().apply {
                    setClassName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"
                    )
                }
            )
        }.isSuccess
        if (!opened) openAppSettings()
    }

    private fun getOrCreateRiderId(): String {
        prefs.getString(KEY_RIDER_ID, null)?.let { return it }
        val id = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_RIDER_ID, id).apply()
        return id
    }

    private fun applySystemBars(root: View) {
        window.statusBarColor = TOP_BAR
        window.navigationBarColor = TOP_BAR
        root.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(
                view.paddingLeft,
                insets.systemWindowInsetTop,
                view.paddingRight,
                0
            )
            insets
        }
    }

    private fun navIcon(textValue: String): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(Color.rgb(226, 232, 240))
        }
    }

    private fun circleButton(textValue: String): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(PILL, dp(29), stroke = CARD_STROKE)
            elevation = dp(8).toFloat()
        }
    }

    private fun bigActionButton(textValue: String, fill: Int, stroke: Int): Button {
        return MaterialButton(this).apply {
            text = textValue
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setSingleLine(true)
            maxLines = 1
            includeFontPadding = false
            setPadding(0, 0, 0, 0)
            minHeight = dp(56)
            cornerRadius = dp(12)
            rippleColor = ColorStateList.valueOf(Color.argb(60, 255, 255, 255))
            background = rounded(fill, dp(8), stroke = stroke, strokeWidth = 2)
            elevation = dp(8).toFloat()
            isAllCaps = false
        }
    }

    private fun mapActionButton(textValue: String, iconRes: Int): Button {
        return MaterialButton(this).apply {
            text = textValue
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setSingleLine(false)
            maxLines = 2
            includeFontPadding = false
            minHeight = dp(76)
            minWidth = 0
            setPadding(0, dp(11), 0, dp(9))
            cornerRadius = dp(12)
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            strokeColor = ColorStateList.valueOf(Color.BLACK)
            strokeWidth = dp(2)
            rippleColor = ColorStateList.valueOf(Color.argb(50, 0, 0, 0))
            setIconResource(iconRes)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_TOP
            iconPadding = dp(3)
            iconSize = dp(23)
            iconTint = ColorStateList.valueOf(Color.BLACK)
            isAllCaps = false
        }
    }

    private fun styleSosButton(active: Boolean) {
        if (!::sosButton.isInitialized) return
        val iconColor = if (active) Color.rgb(220, 38, 38) else Color.BLACK
        (sosButton as? MaterialButton)?.apply {
            text = "SOS"
            textSize = 10.5f
            setTextColor(iconColor)
            iconTint = ColorStateList.valueOf(iconColor)
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            strokeColor = ColorStateList.valueOf(Color.BLACK)
            strokeWidth = dp(2)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_TOP
            iconPadding = dp(3)
            iconSize = dp(23)
            alpha = 1f
        }
    }

    private fun styleRegroupButton(active: Boolean, hasPoint: Boolean) {
        if (!::regroupButton.isInitialized) return
        val iconColor = if (active) Color.rgb(220, 38, 38) else Color.BLACK
        (regroupButton as? MaterialButton)?.apply {
            text = if (hasPoint) "UNGROUP" else "REGROUP"
            textSize = 10.5f
            setTextColor(iconColor)
            iconTint = ColorStateList.valueOf(iconColor)
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            strokeColor = ColorStateList.valueOf(Color.BLACK)
            strokeWidth = dp(2)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_TOP
            iconPadding = dp(3)
            iconSize = dp(23)
            alpha = 1f
        }
    }

    private fun smallCommand(textValue: String, fill: Int): Button {
        return MaterialButton(this).apply {
            text = textValue
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            minHeight = dp(46)
            includeFontPadding = false
            cornerRadius = dp(12)
            rippleColor = ColorStateList.valueOf(Color.argb(55, 255, 255, 255))
            background = rounded(fill, dp(8), stroke = CARD_STROKE)
            elevation = dp(3).toFloat()
            isAllCaps = false
        }
    }

    private fun Button.styleProfileSettingsButton() {
        textSize = 13f
        gravity = Gravity.CENTER
        setSingleLine(true)
        maxLines = 1
        includeFontPadding = false
        minHeight = dp(48)
        setPadding(dp(12), 0, dp(12), 0)
    }

    private fun input(hintText: String, value: String): EditText {
        return EditText(this).apply {
            hint = hintText
            setHintTextColor(MUTED)
            setSingleLine(true)
            setText(value)
            textSize = 14f
            setTextColor(Color.WHITE)
            minHeight = dp(52)
            setPadding(dp(14), 0, dp(14), 0)
            background = rounded(INPUT, dp(8), stroke = CARD_STROKE)
        }
    }

    private fun profileInput(hintText: String, value: String): EditText {
        return EditText(this).apply {
            hint = hintText
            setHintTextColor(Color.rgb(100, 116, 139))
            setSingleLine(true)
            setText(value)
            textSize = 14f
            setTextColor(Color.rgb(15, 23, 42))
            minHeight = dp(50)
            setPadding(dp(14), 0, dp(14), 0)
            background = profileFieldBackground()
        }
    }

    private fun profileFieldBackground(error: Boolean = false): GradientDrawable {
        return rounded(
            Color.rgb(248, 250, 252),
            dp(10),
            stroke = if (error) RED else Color.rgb(203, 213, 225),
            strokeWidth = if (error) 2 else 1
        )
    }

    private fun lettersAndSpacesFilter(): InputFilter {
        return InputFilter { source, start, end, _, _, _ ->
            val filtered = source.subSequence(start, end).filter { it.isLetter() || it == ' ' }
            if (filtered.length == end - start) null else filtered
        }
    }

    private fun digitsOnlyFilter(): InputFilter {
        return InputFilter { source, start, end, _, _, _ ->
            val filtered = source.subSequence(start, end).filter { it.isDigit() }
            if (filtered.length == end - start) null else filtered
        }
    }

    private fun bloodGroupDropdown(value: String): Spinner {
        val options = listOf("Select blood group", "A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-")
        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, options) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                return (super.getView(position, convertView, parent) as TextView).apply {
                    textSize = 14f
                    setTextColor(if (position == 0) Color.rgb(100, 116, 139) else Color.rgb(15, 23, 42))
                    setBackgroundColor(Color.rgb(248, 250, 252))
                    setPadding(dp(14), 0, dp(14), 0)
                }
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                return (super.getDropDownView(position, convertView, parent) as TextView).apply {
                    textSize = 16f
                    setTextColor(if (position == 0) Color.rgb(100, 116, 139) else Color.rgb(15, 23, 42))
                    setBackgroundColor(Color.WHITE)
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                }
            }
        }.apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val selectedIndex = options.indexOf(value.trim().uppercase(Locale.US)).takeIf { it > 0 } ?: 0
        return Spinner(this).apply {
            this.adapter = adapter
            setSelection(selectedIndex)
            minimumHeight = dp(52)
            background = profileFieldBackground()
            setPopupBackgroundDrawable(rounded(Color.WHITE, dp(8), stroke = Color.rgb(203, 213, 225)))
            setPadding(0, 0, 0, 0)
            setOnTouchListener { view, _ ->
                hideKeyboard(view)
                false
            }
        }
    }

    private fun selectedBloodGroup(): String {
        val value = profileBloodInput.selectedItem?.toString().orEmpty()
        return if (value == "Select blood group") "" else value
    }

    private fun hideKeyboard(view: View) {
        getSystemService(InputMethodManager::class.java)
            .hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun panel(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = rounded(PANEL, dp(8), stroke = CARD_STROKE)
            elevation = dp(4).toFloat()
        }
    }

    private fun detailPanel(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = gradientRounded(Color.rgb(18, 23, 30), Color.rgb(13, 15, 20), dp(12), stroke = CARD_STROKE)
            elevation = dp(5).toFloat()
        }
    }

    private fun groupHeaderCard(group: LocalGroup, isActiveGroup: Boolean): FrameLayout {
        val headerHeight = dp(104)
        val card = FrameLayout(this).apply {
            minimumHeight = headerHeight
            background = gradientRounded(Color.rgb(20, 27, 36), Color.rgb(14, 17, 23), dp(12), stroke = CARD_STROKE)
            elevation = dp(6).toFloat()
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(14), dp(14), dp(14))
        }
        val titleColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val title = TextView(this@MainActivity).apply {
                text = groupDisplayTitle(group)
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                maxLines = 1
            }
            val statusLabel = TextView(this@MainActivity).apply {
                text = "Group ID: ${group.code}"
                textSize = 11f
                setTextColor(Color.rgb(148, 163, 184))
                setPadding(0, dp(4), 0, 0)
            }
            addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(statusLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val activeToggle = TextView(this).apply {
            text = if (isActiveGroup) "ACTIVE" else "OFFLINE"
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setSingleLine(true)
            includeFontPadding = false
            isClickable = true
            isFocusable = true
            background = if (isActiveGroup) {
                rounded(Color.rgb(7, 74, 43), dp(8), stroke = GREEN, strokeWidth = 1)
            } else {
                rounded(Color.rgb(74, 22, 30), dp(8), stroke = RED, strokeWidth = 1)
            }
            setOnClickListener {
                if (rideActive && currentState.rideId == group.code) {
                    deactivateActiveGroup()
                } else {
                    activateGroup(group)
                }
            }
        }
        row.addView(titleColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(activeToggle, LinearLayout.LayoutParams(dp(74), dp(34)).apply { leftMargin = dp(8) })
        row.addView(inviteRiderButton(group.code), LinearLayout.LayoutParams(dp(48), dp(48)).apply { leftMargin = dp(10) })
        card.addView(row, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, headerHeight))
        return card
    }

    private fun groupDisplayTitle(group: LocalGroup): String {
        val name = group.name.trim()
        return if (name.isBlank() || name.equals(group.code, ignoreCase = true) || name.equals("Ride ${group.code}", ignoreCase = true)) {
            group.code
        } else {
            name
        }
    }

    private fun groupListTitle(group: LocalGroup): String {
        val name = group.name.trim()
        return if (name.isBlank() || name.equals(group.code, ignoreCase = true) || name.equals("Ride ${group.code}", ignoreCase = true)) {
            group.code
        } else {
            name
        }
    }

    private fun sectionHeaderWithIcon(iconRes: Int, accent: Int, title: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val icon = ImageView(this@MainActivity).apply {
                setImageResource(iconRes)
                setColorFilter(accent)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = oval(Color.argb(45, Color.red(accent), Color.green(accent), Color.blue(accent)), stroke = Color.argb(130, Color.red(accent), Color.green(accent), Color.blue(accent)), strokeWidth = 2)
            }
            val label = sectionTitle(title).apply {
                textSize = 15f
            }
            addView(icon, LinearLayout.LayoutParams(dp(46), dp(46)))
            addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(12) })
        }
    }

    private fun sectionTitle(textValue: String): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            letterSpacing = 0.04f
        }
    }

    private fun bodyText(textValue: String): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = 14f
            setTextColor(Color.rgb(203, 213, 225))
            setLineSpacing(dp(2).toFloat(), 1.0f)
        }
    }

    private fun statCard(label: String, accent: Int): TextView {
        return TextView(this).apply {
            text = "0\n$label"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(CARD, dp(8), stroke = Color.argb(90, Color.red(accent), Color.green(accent), Color.blue(accent)))
            elevation = dp(3).toFloat()
        }
    }

    private fun eventCard(title: String, rider: String, accent: Int): TextView {
        return TextView(this).apply {
            text = "$title\n$rider"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = rounded(CARD, dp(8), stroke = Color.argb(110, Color.red(accent), Color.green(accent), Color.blue(accent)))
            elevation = dp(3).toFloat()
        }
    }

    private fun modeButton(textValue: String): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(MUTED)
            setPadding(dp(6), dp(8), dp(6), dp(8))
            minHeight = dp(72)
            isClickable = true
            isFocusable = true
            background = rounded(Color.rgb(13, 15, 20), dp(10), stroke = Color.rgb(45, 52, 62))
        }
    }

    private fun infoBox(textValue: String): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = 14f
            setTextColor(Color.rgb(147, 197, 253))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(Color.rgb(16, 35, 62), dp(8), stroke = Color.rgb(37, 99, 235))
            elevation = dp(2).toFloat()
        }
    }

    private fun navTint(): ColorStateList {
        return ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf()
            ),
            intArrayOf(BLUE, MUTED)
        )
    }

    private fun overlayParams(
        gravityValue: Int,
        left: Int = 0,
        top: Int = 0,
        right: Int = 0,
        bottom: Int = 0,
        width: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
        height: Int = ViewGroup.LayoutParams.WRAP_CONTENT
    ): FrameLayout.LayoutParams {
        return FrameLayout.LayoutParams(
            if (width == ViewGroup.LayoutParams.WRAP_CONTENT) width else dp(width),
            if (height == ViewGroup.LayoutParams.WRAP_CONTENT) height else dp(height)
        ).apply {
            gravity = gravityValue
            leftMargin = dp(left)
            topMargin = dp(top)
            rightMargin = dp(right)
            bottomMargin = dp(bottom)
        }
    }

    private fun rounded(color: Int, radius: Int, stroke: Int? = null, strokeWidth: Int = 1): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            if (stroke != null) setStroke(dp(strokeWidth), stroke)
        }
    }

    private fun gradientRounded(startColor: Int, endColor: Int, radius: Int, stroke: Int? = null, strokeWidth: Int = 1): GradientDrawable {
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(startColor, endColor)).apply {
            cornerRadius = radius.toFloat()
            if (stroke != null) setStroke(dp(strokeWidth), stroke)
        }
    }

    private fun oval(color: Int, stroke: Int? = null, strokeWidth: Int = 1): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            if (stroke != null) setStroke(dp(strokeWidth), stroke)
        }
    }

    private fun statusPill(active: Boolean): GradientDrawable {
        return if (active) {
            rounded(Color.rgb(8, 61, 38), dp(18), stroke = GREEN, strokeWidth = 2)
        } else {
            rounded(Color.rgb(74, 22, 30), dp(18), stroke = RED, strokeWidth = 2)
        }
    }

    private fun matchWrap(top: Int = 8): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(top)
        }
    }

    private fun matchWrapNoMargin(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun ageText(timestampMs: Long, nowMs: Long): String {
        val ageSec = ((nowMs - timestampMs).coerceAtLeast(0L) / 1000L)
        return if (ageSec < 60) "${ageSec}s ago" else "${ageSec / 60} min ago"
    }

    private fun formatDurationShort(seconds: Long): String {
        return when {
            seconds < 60L -> "${seconds}s"
            seconds < 3600L -> "${seconds / 60L} min"
            else -> "${seconds / 3600L} hr"
        }
    }

    private fun formatDistance(meters: Int): String {
        return if (meters >= 1000) String.format(Locale.US, "%.1f km", meters / 1000.0) else "$meters m"
    }

    private fun riderRankingText(state: RideState, nowMs: Long): String {
        val riders = mutableListOf<RiderSnapshot>()
        state.ownLocation?.let { riders.add(it) }
        riders.addAll(state.riders.values.filter { !it.isStale(nowMs) })
        if (riders.isEmpty()) return "Rider ranking will appear after location updates."

        val origin = state.ownLocation ?: riders.first()
        return riders.sortedByDescending { rider ->
            val (_, northM) = rider.offsetMetersFrom(origin)
            northM
        }.mapIndexed { index, rider ->
            "${index + 1}. ${rider.label}"
        }.joinToString(separator = "\n")
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class LocalGroup(
        val code: String,
        val name: String
    )

    companion object {
        private const val REQUEST_PERMISSIONS = 41
        private const val REQUEST_AUDIO = 42
        private const val REQUEST_PREVIEW_LOCATION = 43
        private const val MIN_WALKIE_RIDERS = 2
        private const val UI_MOVING_SPEED_MPS = 0.8
        private const val STOPPED_LABEL_AFTER_SECONDS = 10 * 60L
        private const val RIDER_DETAIL_TIMEOUT_MS = 60_000L
        private const val PREVIEW_LOCATION_INTERVAL_MS = 10_000L
        private const val PREVIEW_LOCATION_DISTANCE_M = 5f
        private const val SELF_RIDER_ID = "__self__"
        private const val TAG = "CoRider"
        private const val KEY_RIDE_CODE = "ride_code"
        private const val KEY_RIDER_NAME = "rider_name"
        private const val KEY_RIDER_ID = "rider_id"
        private const val KEY_GROUPS = "groups"
        private const val KEY_ADMIN_GROUPS = "admin_groups"
        private const val KEY_ACTIVE_GROUP_CODE = "active_group_code"
        private const val KEY_CONTACT = "contact"
        private const val KEY_DOB = "dob"
        private const val KEY_BLOOD_GROUP = "blood_group"
        private const val KEY_BIKE = "bike"
        private const val KEY_EMERGENCY_CONTACT = "emergency_contact"
        private const val KEY_GOOGLE_EMAIL = "google_email"
        private const val KEY_GOOGLE_UID = "google_uid"
        private const val KEY_GOOGLE_IDENTITY_RESET_DONE = "google_identity_reset_done_v1"
        private const val INVITE_HOST = "rahulsanapala.github.io"
        private const val INVITE_PATH = "/corider-tracker/join.html"
        private const val ADMIN_ROLE_REFRESH_MS = 60_000L
        private const val GLOBAL_EVENTS_PATH = "globalBikeEvents"
        private const val EVENT_ADMIN_EMAIL = "sanapala.rahul02@gmail.com"
        private const val DEFAULT_MAP_PICKER_LATITUDE = 20.5937
        private const val DEFAULT_MAP_PICKER_LONGITUDE = 78.9629

        private val SURFACE = Color.rgb(7, 9, 13)
        private val TOP_BAR = Color.rgb(5, 7, 12)
        private val PANEL = Color.rgb(18, 20, 24)
        private val CARD = Color.rgb(23, 26, 31)
        private val PILL = Color.rgb(12, 14, 20)
        private val INPUT = Color.rgb(17, 20, 27)
        private val CARD_STROKE = Color.rgb(55, 62, 72)
        private val MUTED = Color.rgb(156, 163, 175)
        private val BLUE = Color.rgb(76, 141, 255)
        private val GREEN = Color.rgb(52, 211, 153)
        private val RED = Color.rgb(248, 91, 91)
        private val DANGER = Color.rgb(126, 38, 50)
        private val AMBER = Color.rgb(245, 173, 66)
        private val EVENT_GRADIENTS = arrayOf(
            Color.rgb(249, 115, 22) to Color.rgb(88, 28, 135),
            Color.rgb(14, 165, 233) to Color.rgb(126, 34, 206),
            Color.rgb(16, 185, 129) to Color.rgb(20, 83, 45),
            Color.rgb(236, 72, 153) to Color.rgb(79, 70, 229),
            Color.rgb(245, 158, 11) to Color.rgb(153, 27, 27)
        )
    }
}

