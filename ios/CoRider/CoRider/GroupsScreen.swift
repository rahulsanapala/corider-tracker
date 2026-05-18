import SwiftUI

struct GroupsScreen: View {
    @EnvironmentObject private var store: RideStore
    @State private var selectedGroup: LocalGroup?
    @State private var search = ""
    @State private var createName = ""
    @State private var joinCode = ""

    private var filteredGroups: [LocalGroup] {
        let query = search.trimmingCharacters(in: .whitespacesAndNewlines)
        let groups = store.groups.sorted {
            if $0.code == store.activeGroupCode { return true }
            if $1.code == store.activeGroupCode { return false }
            return $0.displayName.localizedCaseInsensitiveCompare($1.displayName) == .orderedAscending
        }
        guard !query.isEmpty else { return groups }
        return groups.filter { $0.displayName.localizedCaseInsensitiveContains(query) || $0.code.localizedCaseInsensitiveContains(query) }
    }

    var body: some View {
        NavigationStack {
            ZStack {
                Color.appBg.ignoresSafeArea()
                if let group = selectedGroup {
                    GroupDetailScreen(group: group) {
                        selectedGroup = nil
                    }
                } else {
                    groupList
                }
            }
            .navigationTitle(selectedGroup == nil ? "Groups" : "Group")
            .navigationBarTitleDisplayMode(.inline)
        }
    }

    private var groupList: some View {
        ScrollView {
            VStack(spacing: 14) {
                searchBar
                ForEach(filteredGroups) { group in
                    groupRow(group)
                }
                createJoinPanel
            }
            .padding(18)
        }
    }

    private var searchBar: some View {
        HStack {
            Image(systemName: "magnifyingglass").foregroundStyle(Color.muted)
            TextField("Search groups", text: $search)
                .textInputAutocapitalization(.characters)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(Capsule().fill(Color.card))
        .overlay(Capsule().stroke(Color.white.opacity(0.12)))
    }

    private func groupRow(_ group: LocalGroup) -> some View {
        let active = group.code == store.activeGroupCode
        return Button {
            selectedGroup = group
        } label: {
            HStack {
                VStack(alignment: .leading, spacing: 5) {
                    HStack(spacing: 7) {
                        Text(group.displayName).font(.headline)
                        if active {
                            Circle().fill(Color.appGreen).frame(width: 8, height: 8)
                        }
                    }
                    Text(active ? "\(store.activeRiderCount) active members" : "Tap to view group")
                        .font(.caption)
                        .foregroundStyle(Color.muted)
                }
                Spacer()
                Button(role: .destructive) {
                    store.deleteGroup(group)
                } label: {
                    Image(systemName: "trash")
                        .foregroundStyle(.white)
                }
                .buttonStyle(.plain)
            }
            .padding(16)
            .background(RoundedRectangle(cornerRadius: 12).fill(Color.card))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(active ? Color.appGreen.opacity(0.55) : Color.white.opacity(0.12)))
        }
        .buttonStyle(.plain)
    }

    private var createJoinPanel: some View {
        Panel {
            VStack(alignment: .leading, spacing: 12) {
                Text("CREATE GROUP").font(.subheadline.bold())
                TextField("Group name", text: $createName)
                    .textFieldStyle(.roundedBorder)
                    .foregroundStyle(.black)
                Button("CREATE") {
                    store.createGroup(name: createName)
                    createName = ""
                }
                .buttonStyle(.borderedProminent)

                Divider().background(Color.white.opacity(0.2)).padding(.vertical, 4)

                Text("JOIN GROUP").font(.subheadline.bold())
                TextField("Enter group code", text: $joinCode)
                    .textInputAutocapitalization(.characters)
                    .textFieldStyle(.roundedBorder)
                    .foregroundStyle(.black)
                Button("JOIN") {
                    store.joinGroup(code: joinCode)
                    joinCode = ""
                }
                .buttonStyle(.borderedProminent)
                .tint(.green)
            }
        }
    }
}

struct GroupDetailScreen: View {
    @EnvironmentObject private var store: RideStore
    @EnvironmentObject private var voice: VoiceManager
    let group: LocalGroup
    let onBack: () -> Void
    @State private var actionRider: RiderSnapshot?

    private var isActive: Bool { store.activeGroupCode == group.code }
    private var isAdmin: Bool { store.isAdmin(group.code) }

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                header
                walkiePanel
                ridersPanel
                modePanel
            }
            .padding(18)
        }
    }

    private var header: some View {
        Panel {
            HStack(spacing: 14) {
                Button(action: onBack) {
                    Image(systemName: "chevron.left")
                        .font(.title3.bold())
                        .foregroundStyle(.white)
                        .padding(12)
                        .background(Circle().fill(.white.opacity(0.12)))
                }
                VStack(alignment: .leading, spacing: 4) {
                    Text(group.displayName).font(.headline)
                    Text("Group ID: \(group.code)").font(.caption).foregroundStyle(Color.muted)
                }
                Spacer()
                Button(isActive ? "ACTIVE" : "OFFLINE") {
                    store.makeActive(group)
                }
                .font(.caption.bold())
                .buttonStyle(.borderedProminent)
                .tint(isActive ? .green : .red)
            }
        }
    }

    private var walkiePanel: some View {
        Panel {
            VStack(alignment: .leading, spacing: 13) {
                HStack {
                    Label("WALKIE TALKIE", systemImage: "walkie.talkie")
                        .font(.headline)
                    Spacer()
                    if voice.joined && voice.groupCode == group.code {
                        Button {
                            voice.leave()
                        } label: {
                            Image(systemName: "phone.down.fill")
                                .foregroundStyle(.white)
                                .padding(10)
                                .background(Circle().fill(Color.appRed))
                        }
                    }
                }
                Text(walkieText).font(.subheadline).foregroundStyle(Color.muted)
                Button(walkieButtonTitle) {
                    handleWalkie()
                }
                .buttonStyle(.borderedProminent)
                .tint(voice.talking ? .red : .green)
                .frame(maxWidth: .infinity)
            }
        }
    }

    private var ridersPanel: some View {
        Panel {
            VStack(alignment: .leading, spacing: 12) {
                Label("RIDERS", systemImage: "person.3.fill").font(.headline)
                riderRow(store.ownLocation, fallbackName: store.profile.name, isSelf: true)
                ForEach(store.liveRiders) { rider in
                    VStack(spacing: 8) {
                        riderRow(rider, fallbackName: rider.label, isSelf: false)
                        if actionRider?.id == rider.id && isAdmin {
                            adminActions(rider)
                        }
                    }
                }
                if store.liveRiders.isEmpty {
                    Text("No other riders yet. Share this group code and ask them to make it active.")
                        .font(.subheadline)
                        .foregroundStyle(Color.muted)
                }
            }
        }
    }

    private func riderRow(_ rider: RiderSnapshot?, fallbackName: String, isSelf: Bool) -> some View {
        let snapshot = rider
        let active = snapshot.map { !$0.isStale() } ?? isActive
        let admin = (isSelf && isAdmin) || snapshot.map { store.admins(for: group.code).contains($0.id) } == true
        return Button {
            guard let snapshot else { return }
            if isAdmin && !isSelf {
                actionRider = actionRider?.id == snapshot.id ? nil : snapshot
            } else {
                store.focusOnMap(snapshot)
            }
        } label: {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Text(snapshot?.label ?? fallbackName).font(.headline)
                        if admin {
                            Text("ADMIN").font(.caption2.bold()).foregroundStyle(Color.appGreen)
                        }
                        Text("updated \(snapshot?.ageSeconds() ?? 0)s ago")
                            .font(.caption)
                            .foregroundStyle(Color.muted)
                    }
                    Text(isSelf ? "You / \(movement(snapshot))" : movement(snapshot))
                        .font(.subheadline)
                        .foregroundStyle(active ? Color.appGreen.opacity(0.9) : Color.appRed.opacity(0.9))
                }
                Spacer()
                Circle().fill(active ? Color.appGreen : Color.appRed).frame(width: 9, height: 9)
            }
            .padding(12)
            .background(RoundedRectangle(cornerRadius: 12).fill(Color.card))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(active ? Color.appGreen.opacity(0.35) : Color.appRed.opacity(0.35)))
        }
        .buttonStyle(.plain)
    }

    private func adminActions(_ rider: RiderSnapshot) -> some View {
        HStack {
            Button("VIEW MAP") { store.focusOnMap(rider) }
            Button("REMOVE", role: .destructive) { store.removeRider(rider) }
            Button(store.admins(for: group.code).contains(rider.id) ? "ADMIN" : "MAKE ADMIN") { store.makeAdmin(rider) }
                .disabled(store.admins(for: group.code).contains(rider.id))
        }
        .font(.caption.bold())
        .buttonStyle(.bordered)
    }

    private var modePanel: some View {
        Panel {
            VStack(alignment: .leading, spacing: 12) {
                Label("UPDATE MODE", systemImage: "gearshape").font(.headline)
                HStack {
                    ForEach(UpdateMode.allCases) { mode in
                        Button {
                            store.setMode(mode)
                        } label: {
                            VStack {
                                Text(mode.label).font(.subheadline.bold())
                                Text(mode.subtitle).font(.caption).foregroundStyle(Color.muted)
                            }
                            .frame(maxWidth: .infinity, minHeight: 64)
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(store.updateMode == mode ? .blue : .gray.opacity(0.25))
                    }
                }
            }
        }
    }

    private var walkieText: String {
        if !isActive { return "Make this group ACTIVE to start rider voice communication." }
        if store.activeRiderCount < 2 && !voice.joined { return "Waiting for at least 2 active riders." }
        if voice.talking { return "Your microphone is live." }
        if voice.joined { return "Listening to group voice. \(voice.speakerCount) rider(s) connected." }
        return "Start walkie talkie to listen. Tap again to talk."
    }

    private var walkieButtonTitle: String {
        if !isActive { return "MAKE GROUP ACTIVE" }
        if store.activeRiderCount < 2 && !voice.joined { return "WAITING FOR RIDERS" }
        if !voice.joined || voice.groupCode != group.code { return "START WALKIE" }
        return voice.talking ? "STOP TALKING" : "TAP TO TALK"
    }

    private func handleWalkie() {
        if !isActive {
            store.makeActive(group)
        } else if store.activeRiderCount < 2 && !voice.joined {
            store.status = "Wait until at least 2 riders activate this group."
        } else if !voice.joined || voice.groupCode != group.code {
            voice.join(groupCode: group.code, riderId: store.riderId)
        } else {
            voice.toggleTalk()
        }
    }

    private func movement(_ snapshot: RiderSnapshot?) -> String {
        guard let snapshot else { return "No GPS yet" }
        if snapshot.isStale() { return "Inactive - no update for \(snapshot.ageSeconds())s" }
        if snapshot.speedCentiMps > 80 { return "Moving" }
        let stopped = snapshot.stationarySinceMs > 0 ? (Date.nowMs - snapshot.stationarySinceMs) / 1000 : 0
        return stopped >= 600 ? "Stopped \(stopped / 60) min" : "Active"
    }
}
