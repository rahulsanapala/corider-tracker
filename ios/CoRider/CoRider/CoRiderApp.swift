import FirebaseCore
import SwiftUI

@main
struct CoRiderApp: App {
    @StateObject private var store: RideStore
    @StateObject private var voice = VoiceManager.shared

    init() {
        if FirebaseApp.app() == nil,
           Bundle.main.path(forResource: "GoogleService-Info", ofType: "plist") != nil {
            FirebaseApp.configure()
        }
        _store = StateObject(wrappedValue: RideStore())
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(store)
                .environmentObject(voice)
                .preferredColorScheme(.dark)
        }
    }
}
