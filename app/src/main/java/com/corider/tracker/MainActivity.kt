package com.corider.tracker

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.corider.tracker.location.LiveLocationService
import com.corider.tracker.ui.LiveMapView
import com.corider.tracker.voice.AgoraWalkieTalkie
import com.google.android.material.button.MaterialButton
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.firebase.auth.FirebaseAuth
import java.util.Locale
import java.util.UUID

class MainActivity : Activity(), RideBus.Listener {
    private lateinit var rideCodeInput: EditText
    private lateinit var profileNameInput: EditText
    private lateinit var profileContactInput: EditText
    private lateinit var profileBloodInput: EditText
    private lateinit var profileBikeInput: EditText
    private lateinit var profileEmergencyInput: EditText
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var statusView: TextView
    private lateinit var topBar: LinearLayout
    private lateinit var pageTitle: TextView
    private lateinit var setupPanel: LinearLayout

    private lateinit var sosButton: Button
    private lateinit var regroupButton: Button
    private lateinit var modeEcoButton: TextView
    private lateinit var modeNormalButton: TextView
    private lateinit var modeFastButton: TextView
    private lateinit var walkieButton: Button
    private lateinit var walkieStatusView: TextView

    private lateinit var livePill: TextView
    private lateinit var onlinePill: TextView
    private lateinit var ridersMiniView: TextView
    private lateinit var mapView: LiveMapView
    private lateinit var mapPage: FrameLayout
    private lateinit var groupPage: SwipeRefreshLayout
    private lateinit var groupScroll: ScrollView
    private lateinit var groupContent: LinearLayout
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
    private val riderId by lazy { getOrCreateRiderId() }
    private val walkieTalkie by lazy { AgoraWalkieTalkie(this) }
    private var pendingStart = false
    private var pendingWalkieGroup: String? = null
    private var selectedTab = "map"
    private var selectedGroupCode: String? = null
    private var updatingBottomNav = false
    private var currentState = RideState()
    private var rideActive = false
    private var hasRegroupPoint = false

    override fun onCreate(savedInstanceState: Bundle?) {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        super.onCreate(savedInstanceState)
        walkieTalkie.onStateChanged = { state -> runOnUiThread { renderWalkieState(state) } }
        buildUi()
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
        if (::mapView.isInitialized) mapView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        walkieTalkie.release()
        if (::mapView.isInitialized) mapView.onDestroy()
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
        profilePage = buildProfilePage()
        contentFrame.addView(mapPage, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        contentFrame.addView(groupPage, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        contentFrame.addView(profilePage, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        root.addView(contentFrame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(buildBottomNav(), LinearLayout.LayoutParams.MATCH_PARENT, dp(76))

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

            val menu = navIcon("â˜°").apply {
                setOnClickListener {
                    setupPanel.visibility = if (setupPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                }
            }
            pageTitle = TextView(this@MainActivity).apply {
                text = "Map"
                textSize = 19f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
            }
            val alert = TextView(this@MainActivity)

            addView(menu, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            addView(pageTitle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f))
            addView(alert, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        }
    }

    private fun buildMapPage(): FrameLayout {
        mapView = LiveMapView(this).apply { onCreate() }

        return FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(mapView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

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
            sosButton = bigActionButton("SOS", DANGER, Color.BLACK).apply {
                setOnClickListener { dispatchServiceAction(LiveLocationService.ACTION_SOS) }
            }
            regroupButton = bigActionButton("REGROUP", PILL, Color.BLACK).apply {
                setOnClickListener {
                    if (hasRegroupPoint) {
                        dispatchServiceAction(LiveLocationService.ACTION_CLEAR_REGROUP)
                    } else {
                        dispatchServiceAction(LiveLocationService.ACTION_REGROUP)
                    }
                }
            }
            actions.addView(sosButton, LinearLayout.LayoutParams(0, dp(64), 1f))
            actions.addView(regroupButton, LinearLayout.LayoutParams(0, dp(64), 1f).apply { leftMargin = dp(12) })
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

    private fun buildProfilePage(): ScrollView {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(24))
            setBackgroundColor(SURFACE)
        }

        val profilePanel = panel()
        profilePanel.addView(sectionTitle("RIDER PROFILE"), matchWrapNoMargin())
        profilePanel.addView(bodyText("This name is used on the live map and group alerts."), matchWrap(top = 6))

        profileNameInput = input("Full name", prefs.getString(KEY_RIDER_NAME, "").orEmpty())
        profileContactInput = input("Contact number", prefs.getString(KEY_CONTACT, "").orEmpty())
        profileBloodInput = input("Blood group", prefs.getString(KEY_BLOOD_GROUP, "").orEmpty())
        profileBikeInput = input("Bike / vehicle", prefs.getString(KEY_BIKE, "").orEmpty())
        profileEmergencyInput = input("Emergency contact", prefs.getString(KEY_EMERGENCY_CONTACT, "").orEmpty())

        profilePanel.addView(profileNameInput, matchWrap(top = 14))
        profilePanel.addView(profileContactInput, matchWrap(top = 10))
        profilePanel.addView(profileBloodInput, matchWrap(top = 10))
        profilePanel.addView(profileBikeInput, matchWrap(top = 10))
        profilePanel.addView(profileEmergencyInput, matchWrap(top = 10))

        val saveButton = smallCommand("SAVE PROFILE", BLUE).apply {
            setOnClickListener {
                saveProfile()
                statusView.text = "Profile saved. Your map name is ${profileName()}."
            }
        }
        profilePanel.addView(saveButton, matchWrap(top = 14))
        content.addView(profilePanel, matchWrapNoMargin())

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

        val header = panel()
        header.addView(sectionTitle("GROUPS"), matchWrapNoMargin())
        header.addView(bodyText("Only one group can be active at a time. Tap a group to manage riders and live status."), matchWrap(top = 8))
        groupContent.addView(header, matchWrapNoMargin())

        val activeCode = prefs.getString(KEY_ACTIVE_GROUP_CODE, "").orEmpty()
        groups.forEach { group ->
            groupContent.addView(groupListItem(group, activeCode), matchWrap(top = 12))
        }

        groupContent.addView(createGroupPanel(), matchWrap(top = 18))
        groupContent.addView(joinGroupPanel(collapsed = true), matchWrap(top = 18))
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
                stroke = if (isActive) GREEN else CARD_STROKE,
                strokeWidth = if (isActive) 2 else 1
            )
            setOnClickListener { renderGroupDetail(group.code) }

            val details = TextView(this@MainActivity).apply {
                val activeText = if (isActive) "ACTIVE" else "Inactive"
                text = "${groupListTitle(group)}\n${group.code} / $activeText"
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                setLineSpacing(dp(2).toFloat(), 1.0f)
            }
            val delete = ImageButton(this@MainActivity).apply {
                contentDescription = "Delete group"
                setImageResource(R.drawable.ic_delete)
                setColorFilter(Color.WHITE)
                scaleType = ImageView.ScaleType.CENTER
                setPadding(dp(9), dp(9), dp(9), dp(9))
                background = rounded(Color.rgb(74, 22, 30), dp(8), stroke = RED, strokeWidth = 2)
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
        selectedGroupCode = group.code
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
        walkiePanel.addView(walkieButton, matchWrap(top = 14))
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
        val codeInput = input("Group code", nextGroupCode()).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        panel.addView(nameInput, matchWrap(top = 12))
        panel.addView(codeInput, matchWrap(top = 10))
        val create = smallCommand("CREATE", BLUE).apply {
            setOnClickListener {
                val code = codeInput.text.toString().trim().uppercase(Locale.US).ifBlank { nextGroupCode() }
                val name = nameInput.text.toString().trim().ifBlank { "Ride $code" }
                saveGroup(LocalGroup(code, name))
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
                val code = codeInput.text.toString().trim().uppercase(Locale.US)
                if (code.isBlank()) {
                    statusView.text = "Enter a group code to join."
                    return@setOnClickListener
                }
                saveGroup(LocalGroup(code, groupNameForCode(code)))
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
                addView(riderItemView("Group inactive", "Toggle ACTIVE to join this group's live location.", MUTED), matchWrapNoMargin())
                return@apply
            }

            val now = System.currentTimeMillis()
            val rows = mutableListOf<Triple<String, String, Int>>()
            val ownName = currentState.ownLocation?.label ?: profileName().ifBlank { "You" }
            rows.add(Triple(ownName, "Active / You", GREEN))
            currentState.riders.values
                .sortedBy { it.label.lowercase(Locale.US) }
                .forEach { rider ->
                    val active = !rider.isStale(now)
                    val status = if (active) "Active" else "Inactive"
                    rows.add(Triple(rider.label, "$status / ${rider.ageSeconds(now)}s ago", if (active) GREEN else MUTED))
                }

            if (rows.isEmpty()) {
                addView(riderItemView("No live riders yet", "Start tracking to publish your location.", MUTED), matchWrapNoMargin())
            } else {
                rows.forEachIndexed { index, row ->
                    addView(riderItemView(row.first, row.second, row.third), matchWrap(top = if (index == 0) 0 else 10))
                }
            }
        }
    }

    private fun riderItemView(name: String, status: String, accent: Int): TextView {
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
            itemIconTintList = navTint()
            itemTextColor = navTint()
            itemRippleColor = ColorStateList.valueOf(Color.argb(45, 76, 141, 255))
            labelVisibilityMode = com.google.android.material.navigation.NavigationBarView.LABEL_VISIBILITY_LABELED
            setOnItemSelectedListener { item ->
                if (updatingBottomNav) return@setOnItemSelectedListener true
                when (item.itemId) {
                    R.id.nav_map -> switchTab("map")
                    R.id.nav_group -> {
                        selectedGroupCode = null
                        renderGroupList()
                        switchTab("group")
                    }
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

        FirebaseAuth.getInstance().signInAnonymously()
            .addOnSuccessListener {
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
            .addOnFailureListener { error ->
                statusView.text = "Firebase auth failed: ${error.message ?: "unknown"}"
            }
    }

    private fun activateGroup(group: LocalGroup) {
        leaveWalkieIfDifferentGroup(group.code)
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
        walkieTalkie.leave()
    }

    private fun handleWalkieClick(groupCode: String) {
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
        walkieTalkie.setTalking(!voice.talking)
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
        } else {
            statusView.text = walkieTalkie.currentState().message
        }
    }

    private fun renderWalkieState(voice: AgoraWalkieTalkie.State) {
        if (!::walkieButton.isInitialized || !::walkieStatusView.isInitialized) return
        val activeGroup = selectedGroupCode != null && rideActive && currentState.rideId == selectedGroupCode
        val enoughRiders = selectedGroupCode != null && activeRiderCount(selectedGroupCode.orEmpty()) >= MIN_WALKIE_RIDERS
        walkieButton.isEnabled = selectedGroupCode != null
        walkieButton.text = when {
            !activeGroup -> "MAKE GROUP ACTIVE"
            !enoughRiders && !voice.joined -> "WAITING FOR RIDERS"
            !voice.joined || voice.groupCode != selectedGroupCode -> "START WALKIE"
            voice.talking -> "STOP TALKING"
            else -> "TAP TO TALK"
        }
        walkieButton.background = when {
            voice.talking -> gradientRounded(Color.rgb(127, 29, 29), DANGER, dp(10), stroke = RED, strokeWidth = 2)
            voice.joined && voice.groupCode == selectedGroupCode -> gradientRounded(Color.rgb(7, 91, 50), GREEN, dp(10))
            activeGroup && !enoughRiders -> rounded(PILL, dp(10), stroke = CARD_STROKE)
            activeGroup -> gradientRounded(Color.rgb(7, 91, 50), GREEN, dp(10), stroke = Color.rgb(34, 197, 94))
            else -> gradientRounded(Color.rgb(8, 83, 45), Color.rgb(22, 163, 74), dp(10), stroke = Color.rgb(34, 197, 94))
        }
        walkieStatusView.text = walkieStatusText(voice, activeGroup, enoughRiders)
    }

    private fun enforceWalkieActiveGroup(state: RideState) {
        val voice = walkieTalkie.currentState()
        if (!voice.joined) return
        if (!state.active || state.rideId.isBlank() || voice.groupCode != state.rideId) {
            Log.i(TAG, "Leaving walkie because active group changed. voiceGroup=${voice.groupCode} activeGroup=${state.rideId} active=${state.active}")
            walkieTalkie.leave()
        } else if (activeRiderCount(state.rideId) < MIN_WALKIE_RIDERS) {
            Log.i(TAG, "Leaving walkie because fewer than $MIN_WALKIE_RIDERS active riders remain in ${state.rideId}")
            walkieTalkie.leave()
        }
    }

    private fun leaveWalkieIfDifferentGroup(nextGroupCode: String) {
        val voice = walkieTalkie.currentState()
        if (voice.joined && voice.groupCode != nextGroupCode) {
            Log.i(TAG, "Leaving walkie before switching active group from ${voice.groupCode} to $nextGroupCode")
            walkieTalkie.leave()
        }
    }

    private fun walkieStatusText(voice: AgoraWalkieTalkie.State, activeGroup: Boolean, enoughRiders: Boolean): String {
        if (!activeGroup) return "Make this group ACTIVE to start rider voice communication."
        return when {
            voice.message.contains("AGORA_APP_ID") -> voice.message
            !enoughRiders && !voice.joined -> "Waiting for at least 2 active riders before walkie talkie can start."
            voice.talking -> "Your microphone is live. Tap STOP TALKING when done."
            voice.joined -> "Listening to group voice. ${voice.speakerCount} rider(s) connected."
            else -> "Start walkie talkie to listen. Tap again to talk."
        }
    }

    private fun activeRiderCount(groupCode: String): Int {
        if (!rideActive || currentState.rideId != groupCode) return 0
        val now = System.currentTimeMillis()
        val self = 1
        val others = currentState.riders.values.count { !it.isStale(now) }
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
        rideActive = state.active
        hasRegroupPoint = state.regroupPoint != null
        enforceWalkieActiveGroup(state)
        startButton.isEnabled = !state.active
        stopButton.isEnabled = state.active
        sosButton.isEnabled = state.active
        regroupButton.isEnabled = state.active && (state.ownLocation != null || hasRegroupPoint)
        statusView.text = locationStatus(state)
        mapView.setState(state)
        updateMapOverlays(state)
        updateGroupTab(state)
        highlightMode(state.updateMode)
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
        onlinePill.text = "Online\n${riders.size} riders online"
        regroupButton.text = if (state.regroupPoint == null) "REGROUP" else "UNGROUP"
        regroupButton.background = if (state.regroupPoint == null) {
            rounded(PILL, dp(13), stroke = Color.BLACK, strokeWidth = 2)
        } else {
            rounded(Color.rgb(74, 22, 30), dp(13), stroke = Color.BLACK, strokeWidth = 2)
        }
        ridersMiniView.visibility = if (riders.isEmpty()) View.GONE else View.VISIBLE
        ridersMiniView.text = riders.take(3).joinToString(separator = "\n") { rider ->
            val distance = if (own == null) "" else "  ${own.distanceTo(rider).toInt()} m"
            "${rider.label}$distance"
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
        selectedTab = tab
        mapPage.visibility = if (tab == "map") View.VISIBLE else View.GONE
        groupPage.visibility = if (tab == "group") View.VISIBLE else View.GONE
        profilePage.visibility = if (tab == "profile") View.VISIBLE else View.GONE
        topBar.visibility = if (tab == "map") View.GONE else View.VISIBLE
        pageTitle.text = when (tab) {
            "group" -> "Group"
            "profile" -> "Profile"
            else -> "Map"
        }
        setupPanel.visibility = View.GONE
        styleBottomTabs()
    }

    private fun setMode(mode: UpdateMode) {
        dispatchServiceAction(LiveLocationService.ACTION_SET_MODE, LiveLocationService.EXTRA_MODE, mode.name)
    }

    private fun dispatchServiceAction(action: String, extraKey: String? = null, extraValue: String? = null) {
        val intent = Intent(this, LiveLocationService::class.java).setAction(action)
        if (extraKey != null && extraValue != null) intent.putExtra(extraKey, extraValue)
        startService(intent)
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
            .putString(KEY_BLOOD_GROUP, profileBloodInput.text.toString().trim())
            .putString(KEY_BIKE, profileBikeInput.text.toString().trim())
            .putString(KEY_EMERGENCY_CONTACT, profileEmergencyInput.text.toString().trim())
            .apply()
        syncActiveRiderName()
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
                insets.systemWindowInsetBottom
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
        val stripe = View(this).apply {
            setBackgroundColor(if (isActiveGroup) GREEN else RED)
        }
        card.addView(
            stripe,
            FrameLayout.LayoutParams(dp(4), headerHeight).apply {
                gravity = Gravity.START
            }
        )

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(14), dp(18), dp(14))
        }
        val title = TextView(this).apply {
            text = groupDisplayTitle(group)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setLineSpacing(dp(3).toFloat(), 1.0f)
        }
        val activeToggle = TextView(this).apply {
            text = if (isActiveGroup) "ACTIVE" else "OFFLINE"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            isClickable = true
            isFocusable = true
            background = if (isActiveGroup) {
                rounded(Color.rgb(7, 74, 43), dp(8), stroke = GREEN, strokeWidth = 2)
            } else {
                rounded(Color.rgb(74, 22, 30), dp(8), stroke = RED, strokeWidth = 2)
            }
            setOnClickListener {
                if (rideActive && currentState.rideId == group.code) {
                    deactivateActiveGroup()
                } else {
                    activateGroup(group)
                }
            }
        }
        row.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(activeToggle, LinearLayout.LayoutParams(dp(96), dp(44)).apply { leftMargin = dp(10) })
        row.addView(inviteRiderButton(group.code), LinearLayout.LayoutParams(dp(52), dp(52)).apply { leftMargin = dp(12) })
        card.addView(row, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, headerHeight))
        return card
    }

    private fun groupDisplayTitle(group: LocalGroup): String {
        val name = group.name.trim()
        return if (name.isBlank() || name.equals(group.code, ignoreCase = true) || name.equals("Ride ${group.code}", ignoreCase = true)) {
            group.code
        } else {
            "$name\n${group.code}"
        }
    }

    private fun groupListTitle(group: LocalGroup): String {
        val name = group.name.trim()
        return if (name.isBlank() || name.equals(group.code, ignoreCase = true) || name.equals("Ride ${group.code}", ignoreCase = true)) {
            "Ride ${group.code}"
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
        private const val MIN_WALKIE_RIDERS = 2
        private const val TAG = "CoRider"
        private const val KEY_RIDE_CODE = "ride_code"
        private const val KEY_RIDER_NAME = "rider_name"
        private const val KEY_RIDER_ID = "rider_id"
        private const val KEY_GROUPS = "groups"
        private const val KEY_ACTIVE_GROUP_CODE = "active_group_code"
        private const val KEY_CONTACT = "contact"
        private const val KEY_BLOOD_GROUP = "blood_group"
        private const val KEY_BIKE = "bike"
        private const val KEY_EMERGENCY_CONTACT = "emergency_contact"
        private const val INVITE_HOST = "rahulsanapala.github.io"
        private const val INVITE_PATH = "/corider-tracker/join.html"

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
    }
}

