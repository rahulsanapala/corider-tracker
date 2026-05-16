package com.corider.tracker

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.corider.tracker.location.LiveLocationService
import com.corider.tracker.ui.LiveMapView
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
    private lateinit var modeEcoButton: Button
    private lateinit var modeNormalButton: Button
    private lateinit var modeFastButton: Button

    private lateinit var livePill: TextView
    private lateinit var onlinePill: TextView
    private lateinit var ridersMiniView: TextView
    private lateinit var mapView: LiveMapView
    private lateinit var mapPage: FrameLayout
    private lateinit var groupPage: ScrollView
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

    private lateinit var bottomMapTab: TextView
    private lateinit var bottomGroupTab: TextView
    private lateinit var bottomProfileTab: TextView

    private val prefs by lazy { getSharedPreferences("ride", Context.MODE_PRIVATE) }
    private val riderId by lazy { getOrCreateRiderId() }
    private var pendingStart = false
    private var selectedTab = "map"
    private var selectedGroupCode: String? = null
    private var currentState = RideState()
    private var rideActive = false
    private var hasRegroupPoint = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        handleJoinIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleJoinIntent(intent)
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
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(SURFACE)
        }

        topBar = buildTopBar()
        root.addView(topBar, LinearLayout.LayoutParams.MATCH_PARENT, dp(70))

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

        root.addView(buildBottomNav(), LinearLayout.LayoutParams.MATCH_PARENT, dp(78))

        setContentView(root)
        switchTab("map")
    }

    private fun buildTopBar(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), 0, dp(18), 0)
            setBackgroundColor(TOP_BAR)

            val menu = navIcon("☰").apply {
                setOnClickListener {
                    setupPanel.visibility = if (setupPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                }
            }
            pageTitle = TextView(this@MainActivity).apply {
                text = "Map"
                textSize = 20f
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
                text = "●  Ready"
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                setPadding(dp(14), dp(9), dp(14), dp(9))
                background = statusPill(active = false)
                visibility = View.GONE
                setOnClickListener { openActiveGroupFromMap() }
            }
            addView(livePill, overlayParams(Gravity.TOP or Gravity.START, left = 18, top = 150))

            setupPanel = buildSetupPanel()
            setupPanel.visibility = View.GONE
            addView(
                setupPanel,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.TOP
                    leftMargin = dp(16)
                    rightMargin = dp(16)
                    topMargin = dp(82)
                }
            )

            val actions = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(dp(24), 0, dp(24), 0)
            }
            sosButton = bigActionButton("SOS", DANGER, Color.rgb(255, 116, 124)).apply {
                setOnClickListener { dispatchServiceAction(LiveLocationService.ACTION_SOS) }
            }
            regroupButton = bigActionButton("REGROUP", PILL, Color.rgb(226, 232, 240)).apply {
                setOnClickListener {
                    if (hasRegroupPoint) {
                        dispatchServiceAction(LiveLocationService.ACTION_CLEAR_REGROUP)
                    } else {
                        dispatchServiceAction(LiveLocationService.ACTION_REGROUP)
                    }
                }
            }
            actions.addView(sosButton, LinearLayout.LayoutParams(0, dp(68), 1f))
            actions.addView(regroupButton, LinearLayout.LayoutParams(0, dp(68), 1f).apply { leftMargin = dp(12) })
            addView(actions, overlayParams(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, bottom = 94))

            onlinePill = TextView(this@MainActivity).apply {
                text = "●  Online\n0 riders online"
                textSize = 14f
                setTextColor(Color.WHITE)
                setPadding(dp(14), dp(10), dp(16), dp(10))
                background = rounded(PILL, dp(12), stroke = CARD_STROKE)
            }
            addView(onlinePill, overlayParams(Gravity.BOTTOM or Gravity.START, left = 22, bottom = 18))

            val center = circleButton("◎").apply {
                textSize = 25f
                setOnClickListener { mapView.centerOnMe() }
            }
            addView(center, overlayParams(Gravity.BOTTOM or Gravity.END, right = 22, bottom = 22, width = 58, height = 58))

            ridersMiniView = TextView(this@MainActivity).apply {
                text = ""
                textSize = 13f
                setTextColor(Color.rgb(226, 232, 240))
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = rounded(PILL, dp(12), stroke = CARD_STROKE)
            }
            addView(ridersMiniView, overlayParams(Gravity.TOP or Gravity.START, left = 22, top = 72))
        }
    }

    private fun buildSetupPanel(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(PANEL, dp(14), stroke = CARD_STROKE)

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

    private fun buildGroupPage(): ScrollView {
        groupContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(18))
            setBackgroundColor(SURFACE)
        }
        renderGroupList()

        return ScrollView(this).apply {
            setBackgroundColor(SURFACE)
            addView(groupContent)
        }
    }

    private fun buildProfilePage(): ScrollView {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(18))
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
            addView(content)
        }
    }

    private fun renderGroupList() {
        if (!::groupContent.isInitialized) return
        groupContent.removeAllViews()
        selectedGroupCode = null

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
            val card = TextView(this).apply {
                val activeText = if (group.code == activeCode && rideActive) "ACTIVE" else "Inactive"
                text = "${group.name}\n${group.code}  •  $activeText"
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = rounded(
                    CARD,
                    dp(10),
                    stroke = if (group.code == activeCode && rideActive) GREEN else CARD_STROKE,
                    strokeWidth = if (group.code == activeCode && rideActive) 2 else 1
                )
                setOnClickListener { renderGroupDetail(group.code) }
            }
            groupContent.addView(card, matchWrap(top = 12))
        }

        groupContent.addView(createGroupPanel(), matchWrap(top = 18))
        groupContent.addView(joinGroupPanel(collapsed = true), matchWrap(top = 18))
    }

    private fun renderGroupDetail(groupCode: String) {
        if (!::groupContent.isInitialized) return
        val group = loadGroups().firstOrNull { it.code == groupCode } ?: return
        selectedGroupCode = group.code
        groupContent.removeAllViews()

        val header = panel()
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = smallCommand("BACK", PILL).apply { setOnClickListener { renderGroupList() } }
        val title = TextView(this).apply {
            text = "${group.name}\n${group.code}"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        val isActiveGroup = rideActive && currentState.rideId == group.code
        val activeToggle = smallCommand(if (isActiveGroup) "ACTIVE" else "INACTIVE", if (isActiveGroup) GREEN else PILL).apply {
            setOnClickListener {
                if (rideActive && currentState.rideId == group.code) {
                    deactivateActiveGroup()
                } else {
                    activateGroup(group)
                }
            }
        }
        topRow.addView(back, LinearLayout.LayoutParams(dp(86), dp(48)))
        topRow.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(12) })
        topRow.addView(activeToggle, LinearLayout.LayoutParams(dp(116), dp(48)))
        header.addView(topRow, matchWrapNoMargin())
        groupContent.addView(header, matchWrapNoMargin())

        val ridersPanel = panel()
        ridersPanel.addView(sectionTitle("RIDERS"), matchWrapNoMargin())
        ridersPanel.addView(bodyText(if (isActiveGroup) "Live riders in this active group." else "Make this group active to see live riders on the map."), matchWrap(top = 8))
        ridersPanel.addView(groupRiderListView(group.code), matchWrap(top = 12))
        groupContent.addView(ridersPanel, matchWrap(top = 18))

        val invitePanel = panel()
        invitePanel.addView(sectionTitle("ADD RIDER"), matchWrapNoMargin())
        invitePanel.addView(bodyText("Invite a rider with this group's Firebase join link and code."), matchWrap(top = 8))
        val invite = smallCommand("INVITE RIDER", GREEN).apply { setOnClickListener { shareInvite(group.code) } }
        invitePanel.addView(invite, matchWrap(top = 14))
        groupContent.addView(invitePanel, matchWrap(top = 18))

        val modePanel = panel()
        modePanel.addView(sectionTitle("UPDATE MODE"), matchWrapNoMargin())
        modePanel.addView(bodyText("Applies to the currently active group."), matchWrap(top = 6))
        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        modeEcoButton = modeButton("Eco\nLow Power\n~60s", GREEN).apply { setOnClickListener { setMode(UpdateMode.ECO) } }
        modeNormalButton = modeButton("Normal\nBalanced\n~30s", BLUE).apply { setOnClickListener { setMode(UpdateMode.NORMAL) } }
        modeFastButton = modeButton("Fast\nHigh Accuracy\n~15s", AMBER).apply { setOnClickListener { setMode(UpdateMode.FAST) } }
        modeRow.addView(modeEcoButton, LinearLayout.LayoutParams(0, dp(128), 1f))
        modeRow.addView(modeNormalButton, LinearLayout.LayoutParams(0, dp(128), 1f).apply { leftMargin = dp(10) })
        modeRow.addView(modeFastButton, LinearLayout.LayoutParams(0, dp(128), 1f).apply { leftMargin = dp(10) })
        modePanel.addView(modeRow, matchWrap(top = 18))
        modePanel.addView(infoBox("Battery saver is active. Moving riders update faster; stopped riders use heartbeat updates."), matchWrap(top = 14))
        groupContent.addView(modePanel, matchWrap(top = 18))
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

    private fun groupRiderListView(groupCode: String): TextView {
        return TextView(this).apply {
            text = riderListText(groupCode)
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(CARD, dp(10), stroke = CARD_STROKE)
        }
    }

    private fun buildBottomNav(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundColor(TOP_BAR)
            bottomMapTab = bottomTab("▮\nMap").apply { setOnClickListener { switchTab("map") } }
            bottomGroupTab = bottomTab("●\nGroup").apply {
                setOnClickListener {
                    selectedGroupCode = null
                    renderGroupList()
                    switchTab("group")
                }
            }
            bottomProfileTab = bottomTab("●\nProfile").apply { setOnClickListener { switchTab("profile") } }
            addView(bottomMapTab, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            addView(bottomGroupTab, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            addView(bottomProfileTab, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        }
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
    }

    private fun render(state: RideState) {
        currentState = state
        rideActive = state.active
        hasRegroupPoint = state.regroupPoint != null
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
        livePill.text = "●  ${state.rideId}"
        livePill.background = statusPill(state.active)
        onlinePill.text = "●  Online\n${riders.size} riders online"
        regroupButton.text = if (state.regroupPoint == null) "REGROUP" else "UNGROUP"
        regroupButton.background = if (state.regroupPoint == null) {
            rounded(PILL, dp(13), stroke = Color.rgb(226, 232, 240), strokeWidth = 2)
        } else {
            rounded(Color.rgb(74, 22, 30), dp(13), stroke = RED, strokeWidth = 2)
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
        val inactive = rounded(PILL, dp(10), stroke = CARD_STROKE)
        val active = rounded(Color.rgb(17, 38, 70), dp(10), stroke = BLUE, strokeWidth = 2)
        modeEcoButton.background = if (mode == UpdateMode.ECO) active else inactive
        modeNormalButton.background = if (mode == UpdateMode.NORMAL) active else inactive
        modeFastButton.background = if (mode == UpdateMode.FAST) active else inactive
    }

    private fun styleBottomTabs() {
        bottomMapTab.setTextColor(if (selectedTab == "map") BLUE else MUTED)
        bottomGroupTab.setTextColor(if (selectedTab == "group") BLUE else MUTED)
        bottomProfileTab.setTextColor(if (selectedTab == "profile") BLUE else MUTED)
    }

    private fun saveProfile() {
        prefs.edit()
            .putString(KEY_RIDER_NAME, profileNameInput.text.toString().trim())
            .putString(KEY_CONTACT, profileContactInput.text.toString().trim())
            .putString(KEY_BLOOD_GROUP, profileBloodInput.text.toString().trim())
            .putString(KEY_BIKE, profileBikeInput.text.toString().trim())
            .putString(KEY_EMERGENCY_CONTACT, profileEmergencyInput.text.toString().trim())
            .apply()
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
        currentState.ownLocation?.let { rows.add("${it.label}  •  Active  •  You") }
        currentState.riders.values
            .sortedBy { it.label.lowercase(Locale.US) }
            .forEach { rider ->
                val status = if (rider.isStale(now)) "Inactive" else "Active"
                rows.add("${rider.label}  •  $status  •  ${rider.ageSeconds(now)}s ago")
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

    private fun getOrCreateRiderId(): String {
        prefs.getString(KEY_RIDER_ID, null)?.let { return it }
        val id = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_RIDER_ID, id).apply()
        return id
    }

    private fun navIcon(textValue: String): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = 26f
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
        }
    }

    private fun bigActionButton(textValue: String, fill: Int, stroke: Int): Button {
        return Button(this).apply {
            text = textValue
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setSingleLine(true)
            maxLines = 1
            includeFontPadding = false
            setPadding(0, 0, 0, 0)
            background = rounded(fill, dp(13), stroke = stroke, strokeWidth = 2)
            isAllCaps = false
        }
    }

    private fun smallCommand(textValue: String, fill: Int): Button {
        return Button(this).apply {
            text = textValue
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = rounded(fill, dp(8), stroke = CARD_STROKE)
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
            setPadding(dp(12), 0, dp(12), 0)
            background = rounded(Color.rgb(13, 24, 41), dp(8), stroke = CARD_STROKE)
        }
    }

    private fun panel(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(PANEL, dp(12), stroke = CARD_STROKE)
        }
    }

    private fun sectionTitle(textValue: String): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
    }

    private fun bodyText(textValue: String): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = 14f
            setTextColor(Color.rgb(203, 213, 225))
        }
    }

    private fun statCard(label: String, accent: Int): TextView {
        return TextView(this).apply {
            text = "0\n$label"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(CARD, dp(10), stroke = Color.argb(90, Color.red(accent), Color.green(accent), Color.blue(accent)))
        }
    }

    private fun eventCard(title: String, rider: String, accent: Int): TextView {
        return TextView(this).apply {
            text = "$title\n$rider"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = rounded(CARD, dp(10), stroke = Color.argb(110, Color.red(accent), Color.green(accent), Color.blue(accent)))
        }
    }

    private fun modeButton(textValue: String, accent: Int): Button {
        return Button(this).apply {
            text = textValue
            textSize = 13f
            setTextColor(Color.WHITE)
            background = rounded(PILL, dp(10), stroke = Color.argb(110, Color.red(accent), Color.green(accent), Color.blue(accent)))
            isAllCaps = false
        }
    }

    private fun infoBox(textValue: String): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = 14f
            setTextColor(Color.rgb(147, 197, 253))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(Color.rgb(16, 35, 62), dp(8), stroke = Color.rgb(37, 99, 235))
        }
    }

    private fun bottomTab(textValue: String): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = 13f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(MUTED)
        }
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

        private val SURFACE = Color.rgb(4, 9, 16)
        private val TOP_BAR = Color.rgb(5, 11, 20)
        private val PANEL = Color.rgb(12, 22, 35)
        private val CARD = Color.rgb(14, 25, 39)
        private val PILL = Color.rgb(10, 17, 29)
        private val CARD_STROKE = Color.rgb(36, 49, 66)
        private val MUTED = Color.rgb(148, 163, 184)
        private val BLUE = Color.rgb(74, 146, 255)
        private val GREEN = Color.rgb(64, 214, 119)
        private val RED = Color.rgb(255, 88, 92)
        private val DANGER = Color.rgb(109, 31, 39)
        private val AMBER = Color.rgb(245, 169, 56)
    }
}
