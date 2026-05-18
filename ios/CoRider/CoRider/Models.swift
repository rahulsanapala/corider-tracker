import CoreLocation
import Foundation

enum UpdateMode: String, CaseIterable, Identifiable {
    case eco = "ECO"
    case normal = "NORMAL"
    case fast = "FAST"

    var id: String { rawValue }

    var label: String {
        switch self {
        case .eco: return "Eco"
        case .normal: return "Normal"
        case .fast: return "Fast"
        }
    }

    var subtitle: String {
        switch self {
        case .eco: return "~60s"
        case .normal: return "~30s"
        case .fast: return "~15s"
        }
    }

    var intervalMs: Int64 {
        switch self {
        case .eco: return 60_000
        case .normal: return 30_000
        case .fast: return 15_000
        }
    }
}

struct LocalGroup: Identifiable, Codable, Equatable {
    var code: String
    var name: String
    var id: String { code }

    var displayName: String {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty || trimmed == code || trimmed == "Ride \(code)" {
            return code
        }
        return trimmed
    }
}

struct RiderProfile: Codable {
    var name = "Rider"
    var contact = ""
    var bloodGroup = ""
    var bike = ""
    var emergencyContact = ""

    static func load() -> RiderProfile {
        guard let data = UserDefaults.standard.data(forKey: "profile"),
              let value = try? JSONDecoder().decode(RiderProfile.self, from: data) else {
            return RiderProfile()
        }
        return value
    }

    func save() {
        guard let data = try? JSONEncoder().encode(self) else { return }
        UserDefaults.standard.set(data, forKey: "profile")
    }
}

struct RiderSnapshot: Identifiable, Equatable {
    let id: String
    var name: String
    var latE7: Int
    var lonE7: Int
    var speedCentiMps: Int
    var bearingDeg: Int
    var accuracyM: Int
    var updatedAtMs: Int64
    var stationarySinceMs: Int64

    var label: String {
        name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? String(id.prefix(6)) : name
    }

    var coordinate: CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: Double(latE7) / 10_000_000.0, longitude: Double(lonE7) / 10_000_000.0)
    }

    var location: CLLocation {
        CLLocation(latitude: coordinate.latitude, longitude: coordinate.longitude)
    }

    func ageSeconds(nowMs: Int64 = Date.nowMs) -> Int64 {
        max(0, nowMs - updatedAtMs) / 1000
    }

    func isStale(nowMs: Int64 = Date.nowMs) -> Bool {
        nowMs - updatedAtMs > 180_000
    }

    func distance(to other: RiderSnapshot) -> CLLocationDistance {
        location.distance(from: other.location)
    }

    var firebasePayload: [String: Any] {
        [
            "id": id,
            "name": name,
            "latE7": latE7,
            "lonE7": lonE7,
            "speedCentiMps": speedCentiMps,
            "bearingDeg": bearingDeg,
            "accuracyM": accuracyM,
            "updatedAtMs": updatedAtMs,
            "stationarySinceMs": stationarySinceMs
        ]
    }

    init(
        id: String,
        name: String,
        latE7: Int,
        lonE7: Int,
        speedCentiMps: Int,
        bearingDeg: Int,
        accuracyM: Int,
        updatedAtMs: Int64,
        stationarySinceMs: Int64 = 0
    ) {
        self.id = id
        self.name = name
        self.latE7 = latE7
        self.lonE7 = lonE7
        self.speedCentiMps = speedCentiMps
        self.bearingDeg = bearingDeg
        self.accuracyM = accuracyM
        self.updatedAtMs = updatedAtMs
        self.stationarySinceMs = stationarySinceMs
    }

    init?(id fallbackId: String, value: Any?) {
        guard let map = value as? [String: Any] else { return nil }
        let id = map["id"] as? String ?? fallbackId
        guard let lat = map.intValue("latE7"), let lon = map.intValue("lonE7") else { return nil }
        self.init(
            id: id,
            name: map["name"] as? String ?? "",
            latE7: lat,
            lonE7: lon,
            speedCentiMps: map.intValue("speedCentiMps") ?? 0,
            bearingDeg: map.intValue("bearingDeg") ?? -1,
            accuracyM: map.intValue("accuracyM") ?? -1,
            updatedAtMs: map.int64Value("updatedAtMs") ?? Date.nowMs,
            stationarySinceMs: map.int64Value("stationarySinceMs") ?? 0
        )
    }

    static func from(location: CLLocation, riderId: String, riderName: String, stationarySinceMs: Int64) -> RiderSnapshot {
        RiderSnapshot(
            id: riderId,
            name: riderName,
            latE7: Int((location.coordinate.latitude * 10_000_000).rounded()),
            lonE7: Int((location.coordinate.longitude * 10_000_000).rounded()),
            speedCentiMps: Int(max(0, location.speed) * 100),
            bearingDeg: location.course >= 0 ? Int(location.course.rounded()) : -1,
            accuracyM: Int(location.horizontalAccuracy.rounded()),
            updatedAtMs: Date.nowMs,
            stationarySinceMs: stationarySinceMs
        )
    }
}

struct GroupAlert: Equatable {
    var riderId: String
    var riderName: String
    var message: String
    var timestampMs: Int64
    var latE7: Int?
    var lonE7: Int?

    var coordinate: CLLocationCoordinate2D? {
        guard let latE7, let lonE7 else { return nil }
        return CLLocationCoordinate2D(latitude: Double(latE7) / 10_000_000.0, longitude: Double(lonE7) / 10_000_000.0)
    }

    init?(value: Any?) {
        guard let map = value as? [String: Any],
              let riderId = map["riderId"] as? String,
              let message = map["message"] as? String,
              let timestamp = map.int64Value("timestampMs") else { return nil }
        self.riderId = riderId
        self.riderName = map["riderName"] as? String ?? "Rider"
        self.message = message
        self.timestampMs = timestamp
        self.latE7 = map.intValue("latE7")
        self.lonE7 = map.intValue("lonE7")
    }
}

struct RegroupPoint: Equatable {
    var riderId: String
    var riderName: String
    var latE7: Int
    var lonE7: Int
    var timestampMs: Int64

    var coordinate: CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: Double(latE7) / 10_000_000.0, longitude: Double(lonE7) / 10_000_000.0)
    }

    init?(value: Any?) {
        guard let map = value as? [String: Any],
              let riderId = map["riderId"] as? String,
              let lat = map.intValue("latE7"),
              let lon = map.intValue("lonE7") else { return nil }
        self.riderId = riderId
        self.riderName = map["riderName"] as? String ?? "Rider"
        self.latE7 = lat
        self.lonE7 = lon
        self.timestampMs = map.int64Value("timestampMs") ?? Date.nowMs
    }
}

struct SafetyCheck: Equatable {
    var id: String
    var targetRiderId: String
    var targetRiderName: String
    var gapM: Int
    var createdAtMs: Int64
    var dueAtMs: Int64
    var status: String

    init?(value: Any?) {
        guard let map = value as? [String: Any],
              let id = map["id"] as? String,
              let target = map["targetRiderId"] as? String else { return nil }
        self.id = id
        self.targetRiderId = target
        self.targetRiderName = map["targetRiderName"] as? String ?? "Rider"
        self.gapM = map.intValue("gapM") ?? 0
        self.createdAtMs = map.int64Value("createdAtMs") ?? 0
        self.dueAtMs = map.int64Value("dueAtMs") ?? 0
        self.status = map["status"] as? String ?? "pending"
    }
}

extension Date {
    static var nowMs: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
}

extension Dictionary where Key == String, Value == Any {
    func intValue(_ key: String) -> Int? {
        if let value = self[key] as? Int { return value }
        if let value = self[key] as? Int64 { return Int(value) }
        if let value = self[key] as? Double { return Int(value) }
        if let value = self[key] as? String { return Int(value) }
        return nil
    }

    func int64Value(_ key: String) -> Int64? {
        if let value = self[key] as? Int64 { return value }
        if let value = self[key] as? Int { return Int64(value) }
        if let value = self[key] as? Double { return Int64(value) }
        if let value = self[key] as? String { return Int64(value) }
        return nil
    }
}
