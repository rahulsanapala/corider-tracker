import AudioToolbox
import CoreLocation
import FirebaseAuth
import FirebaseDatabase
import Foundation
import UserNotifications

final class RideStore: NSObject, ObservableObject, CLLocationManagerDelegate {
    @Published var groups: [LocalGroup] = []
    @Published var activeGroupCode = ""
    @Published var status = "Ready"
    @Published var profile = RiderProfile.load()
    @Published var ownLocation: RiderSnapshot?
    @Published var riders: [String: RiderSnapshot] = [:]
    @Published var groupAlert: GroupAlert?
    @Published var regroupPoint: RegroupPoint?
    @Published var safetyCheck: SafetyCheck?
    @Published var updateMode: UpdateMode = .normal
    @Published var adminsByGroup: [String: Set<String>] = [:]
    @Published var selectedRider: RiderSnapshot?
    @Published var selectedTab = 0

    let riderId: String

    private let database = Database.database().reference()
    private let locationManager = CLLocationManager()
    private var rootRef: DatabaseReference?
    private var ridersRef: DatabaseReference?
    private var eventRefs: [DatabaseReference] = []
    private var lastPublishedLocation: CLLocation?
    private var lastPublishedAtMs: Int64 = 0
    private var localStationarySinceMs: Int64 = 0
    private var lastSafetyCheckId = ""
    private var lastSosToneMs: Int64 = 0

    override init() {
        if let saved = UserDefaults.standard.string(forKey: "rider_id") {
            riderId = saved
        } else {
            let next = UUID().uuidString
            UserDefaults.standard.set(next, forKey: "rider_id")
            riderId = next
        }
        super.init()
        groups = Self.loadGroups()
        locationManager.delegate = self
        locationManager.desiredAccuracy = kCLLocationAccuracyBest
        locationManager.distanceFilter = 5
        locationManager.allowsBackgroundLocationUpdates = true
        locationManager.pausesLocationUpdatesAutomatically = false
        requestNotifications()
    }

    var activeGroup: LocalGroup? {
        groups.first { $0.code == activeGroupCode }
    }

    var isActive: Bool {
        !activeGroupCode.isEmpty
    }

    var liveRiders: [RiderSnapshot] {
        riders.values.sorted { $0.label.localizedCaseInsensitiveCompare($1.label) == .orderedAscending }
    }

    var activeRiderCount: Int {
        isActive ? 1 + riders.count : 0
    }

    func createGroup(name: String) {
        let clean = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty else {
            status = "Group name is required."
            return
        }
        let code = "RIDE-\(UUID().uuidString.prefix(4).uppercased())"
        let group = LocalGroup(code: code, name: clean)
        saveGroup(group)
        markAdmin(groupCode: code, adminId: riderId)
        status = "Created \(clean)"
    }

    func joinGroup(code rawCode: String) {
        let code = rawCode.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        guard !code.isEmpty else {
            status = "Enter group code."
            return
        }
        saveGroup(LocalGroup(code: code, name: "Ride \(code)"))
        refreshAdmins(groupCode: code)
        status = "Joined \(code). Make it active to share."
    }

    func deleteGroup(_ group: LocalGroup) {
        if activeGroupCode == group.code {
            stopTracking()
        }
        groups.removeAll { $0.code == group.code }
        persistGroups()
    }

    func makeActive(_ group: LocalGroup) {
        if activeGroupCode == group.code {
            stopTracking()
        } else {
            startTracking(group: group)
        }
    }

    func startTracking(group: LocalGroup) {
        signInIfNeeded { [weak self] in
            guard let self else { return }
            if self.activeGroupCode != group.code {
                VoiceManager.shared.leave()
            }
            self.stopTracking(removeSelf: false)
            self.activeGroupCode = group.code
            UserDefaults.standard.set(group.code, forKey: "active_group_code")
            self.rootRef = self.database.child("rides").child(group.code)
            self.ridersRef = self.rootRef?.child("riders")
            if self.isLocalAdmin(group.code) {
                self.markAdmin(groupCode: group.code, adminId: self.riderId)
            }
            self.observeGroup(group.code)
            self.locationManager.requestAlwaysAuthorization()
            self.locationManager.startUpdatingLocation()
            self.status = "Sharing live location in \(group.displayName)"
        }
    }

    func stopTracking(removeSelf: Bool = true) {
        VoiceManager.shared.leave()
        locationManager.stopUpdatingLocation()
        eventRefs.forEach { $0.removeAllObservers() }
        eventRefs.removeAll()
        if removeSelf, let ridersRef {
            ridersRef.child(riderId).removeValue()
        }
        rootRef = nil
        ridersRef = nil
        activeGroupCode = ""
        UserDefaults.standard.removeObject(forKey: "active_group_code")
        ownLocation = nil
        riders.removeAll()
        groupAlert = nil
        regroupPoint = nil
        safetyCheck = nil
        selectedRider = nil
        status = "Stopped"
    }

    func saveProfile(_ next: RiderProfile) {
        profile = next
        profile.save()
        if let ownLocation {
            let updated = RiderSnapshot(
                id: ownLocation.id,
                name: next.name,
                latE7: ownLocation.latE7,
                lonE7: ownLocation.lonE7,
                speedCentiMps: ownLocation.speedCentiMps,
                bearingDeg: ownLocation.bearingDeg,
                accuracyM: ownLocation.accuracyM,
                updatedAtMs: Date.nowMs,
                stationarySinceMs: ownLocation.stationarySinceMs
            )
            self.ownLocation = updated
            ridersRef?.child(riderId).updateChildValues(["name": next.name, "updatedAtMs": updated.updatedAtMs])
        }
    }

    func sendSos() {
        guard isActive, let rootRef else {
            status = "Make a group active before SOS."
            return
        }
        var payload: [String: Any] = [
            "riderId": riderId,
            "riderName": profile.name,
            "message": "SOS",
            "timestampMs": Date.nowMs
        ]
        if let ownLocation {
            payload["latE7"] = ownLocation.latE7
            payload["lonE7"] = ownLocation.lonE7
        }
        rootRef.child("events").child("sos").setValue(payload)
    }

    func clearSos() {
        rootRef?.child("events").child("sos").removeValue()
        groupAlert = nil
        status = "SOS cleared"
    }

    func toggleRegroup() {
        guard isAdmin(activeGroupCode) else {
            status = "Only group admin can regroup riders."
            return
        }
        guard let rootRef else { return }
        if regroupPoint != nil {
            rootRef.child("events").child("regroup").removeValue()
            regroupPoint = nil
            return
        }
        guard let ownLocation else {
            status = "Waiting for your location before regroup."
            return
        }
        rootRef.child("events").child("regroup").setValue([
            "riderId": riderId,
            "riderName": profile.name,
            "latE7": ownLocation.latE7,
            "lonE7": ownLocation.lonE7,
            "timestampMs": Date.nowMs
        ])
    }

    func setMode(_ mode: UpdateMode) {
        updateMode = mode
        rootRef?.child("settings").child("mode").setValue(mode.rawValue)
    }

    func removeRider(_ rider: RiderSnapshot) {
        guard isAdmin(activeGroupCode), let rootRef else {
            status = "Only group admin can remove riders."
            return
        }
        rootRef.child("removed").child(rider.id).setValue(true)
        clearSharedEventsForRider(rider.id)
        rootRef.child("riders").child(rider.id).removeValue()
        rootRef.child("admins").child(rider.id).removeValue()
        riders.removeValue(forKey: rider.id)
        if selectedRider?.id == rider.id {
            selectedRider = nil
        }
        adminsByGroup[activeGroupCode]?.remove(rider.id)
        status = "\(rider.label) removed from group."
    }

    func makeAdmin(_ rider: RiderSnapshot) {
        guard isAdmin(activeGroupCode) else {
            status = "Only group admin can make another admin."
            return
        }
        markAdmin(groupCode: activeGroupCode, adminId: rider.id)
        status = "\(rider.label) is now admin."
    }

    func isAdmin(_ groupCode: String) -> Bool {
        guard !groupCode.isEmpty else { return false }
        return isLocalAdmin(groupCode) || (adminsByGroup[groupCode]?.contains(riderId) == true)
    }

    func admins(for groupCode: String) -> Set<String> {
        adminsByGroup[groupCode] ?? []
    }

    func focusOnMap(_ rider: RiderSnapshot?) {
        selectedRider = rider
        selectedTab = 0
    }

    func acknowledgeSafety() {
        guard let safetyCheck, safetyCheck.targetRiderId == riderId else { return }
        rootRef?.child("events").child("safetyCheck").updateChildValues([
            "status": "acknowledged",
            "acknowledgedAtMs": Date.nowMs,
            "acknowledgedByRiderId": riderId,
            "acknowledgedByRiderName": profile.name
        ])
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        if manager.authorizationStatus == .authorizedAlways || manager.authorizationStatus == .authorizedWhenInUse {
            manager.startUpdatingLocation()
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last else { return }
        if !isActive {
            ownLocation = RiderSnapshot.from(location: location, riderId: riderId, riderName: profile.name, stationarySinceMs: 0)
            return
        }
        updateStationaryClock(location)
        let snapshot = RiderSnapshot.from(location: location, riderId: riderId, riderName: profile.name, stationarySinceMs: localStationarySinceMs)
        ownLocation = snapshot
        evaluateSafety()
        guard shouldPublish(location) else { return }
        lastPublishedLocation = location
        lastPublishedAtMs = Date.nowMs
        ridersRef?.child(riderId).setValue(snapshot.firebasePayload)
        status = "Shared location with \(snapshot.accuracyM) m accuracy"
    }

    private func observeGroup(_ groupCode: String) {
        guard let rootRef, let ridersRef else { return }

        let added = ridersRef.observe(.childAdded) { [weak self] snapshot in
            self?.readRider(snapshot)
        }
        let changed = ridersRef.observe(.childChanged) { [weak self] snapshot in
            self?.readRider(snapshot)
        }
        let removed = ridersRef.observe(.childRemoved) { [weak self] snapshot in
            let id = snapshot.key
            self?.riders.removeValue(forKey: id)
            if self?.selectedRider?.id == id { self?.selectedRider = nil }
        }
        eventRefs.append(ridersRef)
        _ = [added, changed, removed]

        let sosRef = rootRef.child("events").child("sos")
        sosRef.observe(.value) { [weak self] snapshot in
            guard let self else { return }
            guard snapshot.exists(), let alert = GroupAlert(value: snapshot.value) else {
                self.groupAlert = nil
                return
            }
            self.groupAlert = alert
            self.playSos(alert)
        }
        eventRefs.append(sosRef)

        let regroupRef = rootRef.child("events").child("regroup")
        regroupRef.observe(.value) { [weak self] snapshot in
            self?.regroupPoint = snapshot.exists() ? RegroupPoint(value: snapshot.value) : nil
        }
        eventRefs.append(regroupRef)

        let modeRef = rootRef.child("settings").child("mode")
        modeRef.observe(.value) { [weak self] snapshot in
            if let raw = snapshot.value as? String, let mode = UpdateMode(rawValue: raw) {
                self?.updateMode = mode
            }
        }
        eventRefs.append(modeRef)

        let safetyRef = rootRef.child("events").child("safetyCheck")
        safetyRef.observe(.value) { [weak self] snapshot in
            self?.safetyCheck = snapshot.exists() ? SafetyCheck(value: snapshot.value) : nil
        }
        eventRefs.append(safetyRef)

        let adminRef = rootRef.child("admins")
        adminRef.observe(.value) { [weak self] snapshot in
            var admins = Set<String>()
            for child in snapshot.children {
                guard let child = child as? DataSnapshot,
                      Self.boolValue(child.value) else { continue }
                admins.insert(child.key)
            }
            self?.adminsByGroup[groupCode] = admins
        }
        eventRefs.append(adminRef)

        let removedRef = rootRef.child("removed").child(riderId)
        removedRef.observe(.value) { [weak self] snapshot in
            if Self.boolValue(snapshot.value) {
                self?.handleRemovedFromGroup()
            }
        }
        eventRefs.append(removedRef)
    }

    private func readRider(_ snapshot: DataSnapshot) {
        guard let rider = RiderSnapshot(id: snapshot.key, value: snapshot.value) else { return }
        if rider.id == riderId {
            ownLocation = rider
        } else {
            riders[rider.id] = rider
        }
        evaluateSafety()
    }

    private func handleRemovedFromGroup() {
        VoiceManager.shared.leave()
        clearSharedEventsForRider(riderId)
        rootRef?.child("riders").child(riderId).removeValue()
        rootRef?.child("admins").child(riderId).removeValue()
        var localAdmins = Set(UserDefaults.standard.stringArray(forKey: "admin_groups") ?? [])
        localAdmins.remove(activeGroupCode)
        UserDefaults.standard.set(Array(localAdmins), forKey: "admin_groups")
        stopTracking(removeSelf: false)
        status = "Removed from group by admin"
    }

    private func clearSharedEventsForRider(_ removedId: String) {
        guard let rootRef else { return }
        rootRef.child("events").child("sos").observeSingleEvent(of: .value) { snapshot in
            if (snapshot.childSnapshot(forPath: "riderId").value as? String) == removedId {
                snapshot.ref.removeValue()
            }
        }
        rootRef.child("events").child("regroup").observeSingleEvent(of: .value) { snapshot in
            if (snapshot.childSnapshot(forPath: "riderId").value as? String) == removedId {
                snapshot.ref.removeValue()
            }
        }
        rootRef.child("events").child("safetyCheck").observeSingleEvent(of: .value) { snapshot in
            let target = snapshot.childSnapshot(forPath: "targetRiderId").value as? String
            let first = snapshot.childSnapshot(forPath: "firstRiderId").value as? String
            if target == removedId || first == removedId {
                snapshot.ref.removeValue()
            }
        }
    }

    private func shouldPublish(_ location: CLLocation) -> Bool {
        let now = Date.nowMs
        if now - lastPublishedAtMs >= updateMode.intervalMs { return true }
        guard let last = lastPublishedLocation else { return true }
        return location.distance(from: last) >= 10
    }

    private func updateStationaryClock(_ location: CLLocation) {
        let moving = location.speed > 0.8
        if moving {
            localStationarySinceMs = 0
        } else if localStationarySinceMs == 0 {
            localStationarySinceMs = Date.nowMs
        }
    }

    private func evaluateSafety() {
        guard let ownLocation, let rootRef else { return }
        let now = Date.nowMs
        var active = [ownLocation] + riders.values.filter { !$0.isStale(nowMs: now) }
        guard active.count >= 2 else { return }
        active.sort { $0.latE7 > $1.latE7 }
        guard let first = active.first, let last = active.last else { return }
        let gap = Int(first.distance(to: last))
        let stoppedLong = last.stationarySinceMs > 0 && now - last.stationarySinceMs >= 10 * 60 * 1000
        guard gap >= 2000, stoppedLong else { return }
        let checkId = "\(last.id)-\(last.stationarySinceMs)"
        guard checkId != lastSafetyCheckId else { return }
        lastSafetyCheckId = checkId
        rootRef.child("events").child("safetyCheck").setValue([
            "id": checkId,
            "targetRiderId": last.id,
            "targetRiderName": last.label,
            "firstRiderId": first.id,
            "firstRiderName": first.label,
            "gapM": gap,
            "createdAtMs": now,
            "dueAtMs": now + 5 * 60 * 1000,
            "status": "pending",
            "acknowledgedAtMs": 0
        ])
    }

    private func playSos(_ alert: GroupAlert) {
        guard alert.timestampMs > lastSosToneMs else { return }
        lastSosToneMs = alert.timestampMs
        AudioServicesPlaySystemSound(1005)
        notify(title: "SOS in \(activeGroupCode)", body: "\(alert.riderName): \(alert.message)")
    }

    private func requestNotifications() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }

    private func notify(title: String, body: String) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        let request = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request)
    }

    private func saveGroup(_ group: LocalGroup) {
        groups.removeAll { $0.code == group.code }
        groups.append(group)
        groups.sort { $0.displayName.localizedCaseInsensitiveCompare($1.displayName) == .orderedAscending }
        persistGroups()
    }

    private func persistGroups() {
        guard let data = try? JSONEncoder().encode(groups) else { return }
        UserDefaults.standard.set(data, forKey: "groups")
    }

    private static func loadGroups() -> [LocalGroup] {
        guard let data = UserDefaults.standard.data(forKey: "groups"),
              let groups = try? JSONDecoder().decode([LocalGroup].self, from: data) else { return [] }
        return groups
    }

    private func isLocalAdmin(_ groupCode: String) -> Bool {
        Set(UserDefaults.standard.stringArray(forKey: "admin_groups") ?? []).contains(groupCode)
    }

    private func markAdmin(groupCode: String, adminId: String) {
        guard !groupCode.isEmpty, !adminId.isEmpty else { return }
        if adminId == riderId {
            var local = Set(UserDefaults.standard.stringArray(forKey: "admin_groups") ?? [])
            local.insert(groupCode)
            UserDefaults.standard.set(Array(local), forKey: "admin_groups")
        }
        adminsByGroup[groupCode, default: []].insert(adminId)
        signInIfNeeded { [weak self] in
            self?.database.child("rides").child(groupCode).child("admins").child(adminId).setValue(true)
        }
    }

    private func refreshAdmins(groupCode: String) {
        signInIfNeeded { [weak self] in
            self?.database.child("rides").child(groupCode).child("admins").observeSingleEvent(of: .value) { snapshot in
                var admins = Set<String>()
                for child in snapshot.children {
                    guard let child = child as? DataSnapshot, Self.boolValue(child.value) else { continue }
                    admins.insert(child.key)
                }
                self?.adminsByGroup[groupCode] = admins
            }
        }
    }

    private func signInIfNeeded(_ completion: @escaping () -> Void) {
        if Auth.auth().currentUser != nil {
            completion()
            return
        }
        Auth.auth().signInAnonymously { [weak self] _, error in
            if let error {
                self?.status = "Firebase auth failed: \(error.localizedDescription)"
            } else {
                completion()
            }
        }
    }

    private static func boolValue(_ value: Any?) -> Bool {
        if let value = value as? Bool { return value }
        if let value = value as? NSNumber { return value.boolValue }
        if let value = value as? String { return value.lowercased() == "true" }
        return false
    }
}
