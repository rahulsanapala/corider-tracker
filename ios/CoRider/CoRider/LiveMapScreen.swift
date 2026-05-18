import MapKit
import SwiftUI

private struct MapItem: Identifiable {
    enum Kind { case me, rider, regroup, sos }
    let id: String
    let title: String
    let coordinate: CLLocationCoordinate2D
    let kind: Kind
    let rider: RiderSnapshot?
}

struct LiveMapScreen: View {
    @EnvironmentObject private var store: RideStore
    @State private var region = MKCoordinateRegion(
        center: CLLocationCoordinate2D(latitude: 17.3850, longitude: 78.4867),
        span: MKCoordinateSpan(latitudeDelta: 0.02, longitudeDelta: 0.02)
    )
    @State private var followMe = true

    private var items: [MapItem] {
        var result: [MapItem] = []
        if let own = store.ownLocation {
            result.append(MapItem(id: "me", title: "You", coordinate: own.coordinate, kind: .me, rider: own))
        }
        result += store.liveRiders.map {
            MapItem(id: $0.id, title: $0.label, coordinate: $0.coordinate, kind: .rider, rider: $0)
        }
        if let point = store.regroupPoint {
            result.append(MapItem(id: "regroup", title: "Regroup", coordinate: point.coordinate, kind: .regroup, rider: nil))
        }
        if let alert = store.groupAlert, let coordinate = alert.coordinate {
            result.append(MapItem(id: "sos", title: "SOS", coordinate: coordinate, kind: .sos, rider: nil))
        }
        return result
    }

    var body: some View {
        ZStack {
            Map(coordinateRegion: $region, annotationItems: items) { item in
                MapAnnotation(coordinate: item.coordinate) {
                    marker(item)
                }
            }
            .ignoresSafeArea()

            VStack {
                topOverlay
                Spacer()
                if let alert = store.groupAlert {
                    sosCard(alert)
                } else if let rider = store.selectedRider {
                    riderCard(rider)
                }
                mapActions
            }
            .padding(.horizontal, 18)
            .padding(.top, 18)
            .padding(.bottom, 12)

            if let arrow = sosArrowAngle {
                Button {
                    if let coordinate = store.groupAlert?.coordinate {
                        focus(coordinate)
                    }
                } label: {
                    Image(systemName: "location.north.fill")
                        .font(.system(size: 26, weight: .bold))
                        .foregroundStyle(.red)
                        .rotationEffect(.degrees(arrow))
                        .padding(14)
                        .background(Circle().fill(.white.opacity(0.85)))
                }
                .offset(y: -120)
            }
        }
        .onAppear { centerOnBestLocation() }
        .onChange(of: store.ownLocation) { _ in
            if followMe { centerOnBestLocation() }
        }
    }

    private var topOverlay: some View {
        HStack {
            if store.isActive {
                Button {
                    store.selectedTab = 1
                } label: {
                    HStack(spacing: 8) {
                        Circle().fill(Color.appGreen).frame(width: 9, height: 9)
                        Text(store.activeGroupCode).font(.caption.bold())
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 9)
                    .background(Capsule().fill(Color.black.opacity(0.78)))
                }
            }
            Spacer()
            if store.groupAlert?.riderId == store.riderId {
                Button {
                    store.clearSos()
                } label: {
                    Image(systemName: "checkmark.shield.fill")
                        .font(.system(size: 34))
                        .foregroundStyle(.green, .black)
                }
                .padding(.top, 90)
            }
        }
    }

    private var mapActions: some View {
        VStack(spacing: 14) {
            HStack(spacing: 16) {
                Button(action: store.sendSos) {
                    actionLabel("sos.circle.fill", "SOS", active: store.isActive, red: true)
                }
                Button(action: store.toggleRegroup) {
                    actionLabel("person.3.sequence.fill", store.regroupPoint == nil ? "REGROUP" : "UNGROUP", active: store.isActive && store.isAdmin(store.activeGroupCode), red: store.regroupPoint != nil)
                }
                .opacity(store.isActive && store.isAdmin(store.activeGroupCode) ? 1 : 0.45)
            }
            HStack {
                Text(store.isActive ? "Online\n\(store.liveRiders.count) riders online" : "Preview\nOnly you")
                    .font(.subheadline)
                    .foregroundStyle(.white)
                    .padding(12)
                    .background(RoundedRectangle(cornerRadius: 10).fill(Color.black.opacity(0.82)))
                Spacer()
                Button {
                    followMe = true
                    centerOnBestLocation()
                } label: {
                    Image(systemName: "scope")
                        .font(.title2.bold())
                        .foregroundStyle(.white)
                        .padding(16)
                        .background(Circle().fill(Color.black.opacity(0.85)))
                }
            }
        }
    }

    private func marker(_ item: MapItem) -> some View {
        Button {
            if item.kind == .sos, let coordinate = store.groupAlert?.coordinate {
                focus(coordinate)
            } else if let rider = item.rider {
                store.selectedRider = rider
                focus(rider.coordinate)
            }
        } label: {
            VStack(spacing: 2) {
                ZStack {
                    Circle().fill(color(item).gradient).frame(width: 42, height: 42)
                    Circle().fill(.white.opacity(0.92)).frame(width: 26, height: 26)
                    Text(initials(item.title))
                        .font(.caption2.bold())
                        .foregroundStyle(.black)
                }
                Text(item.kind == .sos ? "SOS" : item.title)
                    .font(.caption2.bold())
                    .padding(.horizontal, 6)
                    .padding(.vertical, 3)
                    .background(Capsule().fill(.black.opacity(0.72)))
                    .foregroundStyle(.white)
            }
        }
    }

    private func actionLabel(_ icon: String, _ title: String, active: Bool, red: Bool) -> some View {
        VStack(spacing: 5) {
            Image(systemName: icon)
                .font(.system(size: 25, weight: .bold))
            Text(title)
                .font(.caption.bold())
        }
        .foregroundStyle(active ? (red ? Color.red : Color.black) : Color.black)
        .frame(width: 116, height: 72)
        .background(RoundedRectangle(cornerRadius: 14).fill(Color.white.opacity(0.18)))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(Color.black, lineWidth: 2))
    }

    private func riderCard(_ rider: RiderSnapshot) -> some View {
        HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: 4) {
                Text(rider.label).font(.headline).foregroundStyle(.black)
                Text("updated \(rider.ageSeconds())s ago").font(.caption).foregroundStyle(.gray)
                if let own = store.ownLocation, rider.id != store.riderId {
                    Text("\(Int(own.distance(to: rider))) m away").font(.subheadline).foregroundStyle(.black)
                }
            }
            Spacer()
            Button { store.selectedRider = nil } label: {
                Image(systemName: "xmark.circle.fill").foregroundStyle(.gray)
            }
        }
        .padding(14)
        .background(RoundedRectangle(cornerRadius: 10).fill(Color(red: 0.86, green: 0.94, blue: 1.0)))
        .overlay(RoundedRectangle(cornerRadius: 10).stroke(.black, lineWidth: 1))
    }

    private func sosCard(_ alert: GroupAlert) -> some View {
        HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: 4) {
                Text("SOS \(alert.riderName)").font(.headline).foregroundStyle(.red)
                Text(alert.message).font(.subheadline).foregroundStyle(.black)
            }
            Spacer()
            Button { store.groupAlert = nil } label: {
                Image(systemName: "xmark.circle.fill").foregroundStyle(.gray)
            }
        }
        .padding(14)
        .background(RoundedRectangle(cornerRadius: 10).fill(Color.white.opacity(0.82)))
        .overlay(RoundedRectangle(cornerRadius: 10).stroke(.red, lineWidth: 2))
    }

    private var sosArrowAngle: Double? {
        guard let coordinate = store.groupAlert?.coordinate else { return nil }
        let latOut = abs(coordinate.latitude - region.center.latitude) > region.span.latitudeDelta / 2
        let lonOut = abs(coordinate.longitude - region.center.longitude) > region.span.longitudeDelta / 2
        guard latOut || lonOut else { return nil }
        let dy = coordinate.latitude - region.center.latitude
        let dx = coordinate.longitude - region.center.longitude
        return atan2(dx, dy) * 180 / .pi
    }

    private func centerOnBestLocation() {
        if let selected = store.selectedRider {
            focus(selected.coordinate)
        } else if let own = store.ownLocation {
            focus(own.coordinate)
        }
    }

    private func focus(_ coordinate: CLLocationCoordinate2D) {
        region = MKCoordinateRegion(center: coordinate, span: MKCoordinateSpan(latitudeDelta: 0.02, longitudeDelta: 0.02))
    }

    private func color(_ item: MapItem) -> Color {
        switch item.kind {
        case .me: return .blue
        case .regroup: return .green
        case .sos: return .red
        case .rider:
            let colors: [Color] = [.orange, .purple, .pink, .cyan, .yellow, .mint]
            return colors[(item.id.hashValue & Int.max) % colors.count]
        }
    }

    private func initials(_ value: String) -> String {
        let parts = value.split(separator: " ")
        let first = parts.first?.prefix(1) ?? "R"
        let second = parts.dropFirst().first?.prefix(1) ?? ""
        return "\(first)\(second)".uppercased()
    }
}
