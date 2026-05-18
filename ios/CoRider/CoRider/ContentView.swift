import SwiftUI

struct ContentView: View {
    @EnvironmentObject private var store: RideStore

    var body: some View {
        TabView(selection: $store.selectedTab) {
            LiveMapScreen()
                .tabItem { Label("Map", systemImage: "map") }
                .tag(0)

            GroupsScreen()
                .tabItem { Label("Group", systemImage: "person.3.fill") }
                .tag(1)

            ProfileScreen()
                .tabItem { Label("Profile", systemImage: "person.fill") }
                .tag(2)
        }
        .tint(.blue)
    }
}

extension Color {
    static let appBg = Color(red: 0.03, green: 0.04, blue: 0.07)
    static let panel = Color(red: 0.07, green: 0.09, blue: 0.12)
    static let card = Color(red: 0.09, green: 0.11, blue: 0.15)
    static let muted = Color(red: 0.62, green: 0.66, blue: 0.72)
    static let appGreen = Color(red: 0.20, green: 0.83, blue: 0.60)
    static let appRed = Color(red: 0.95, green: 0.22, blue: 0.28)
}

struct Panel<Content: View>: View {
    let content: Content

    init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    var body: some View {
        content
            .padding(16)
            .background(
                RoundedRectangle(cornerRadius: 14)
                    .fill(Color.panel)
                    .overlay(RoundedRectangle(cornerRadius: 14).stroke(Color.white.opacity(0.12)))
            )
    }
}
