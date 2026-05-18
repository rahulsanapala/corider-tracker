import FirebaseCore
import SwiftUI

@main
struct CoRiderApp: App {
    @StateObject private var store = RideStore()
    @StateObject private var voice = VoiceManager.shared

    init() {
        FirebaseApp.configure()
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
