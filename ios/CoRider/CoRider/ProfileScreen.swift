import SwiftUI
import UIKit

struct ProfileScreen: View {
    @EnvironmentObject private var store: RideStore
    @State private var draft = RiderProfile.load()
    @State private var editing = false
    @State private var error = ""

    private let bloodGroups = ["", "A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-"]

    var body: some View {
        NavigationStack {
            ZStack {
                Color.appBg.ignoresSafeArea()
                ScrollView {
                    VStack(spacing: 16) {
                        profileCard
                        batteryPanel
                    }
                    .padding(18)
                }
            }
            .navigationBarTitleDisplayMode(.inline)
        }
        .onAppear { draft = store.profile }
    }

    private var profileCard: some View {
        VStack(spacing: 0) {
            ZStack(alignment: .bottomLeading) {
                LinearGradient(colors: [.purple, .blue], startPoint: .topLeading, endPoint: .bottomTrailing)
                    .frame(height: 150)
                VStack(alignment: .leading, spacing: 5) {
                    Text(store.profile.name.isEmpty ? "Rider" : store.profile.name)
                        .font(.title2.bold())
                    Text("Rider details")
                        .font(.caption)
                        .foregroundStyle(.white.opacity(0.82))
                }
                .padding(18)
            }

            VStack(spacing: 12) {
                field("Full name", text: $draft.name, keyboard: .default, filter: lettersOnly)
                field("Mobile number", text: $draft.contact, keyboard: .numberPad, filter: digitsOnly)
                Picker("Blood group", selection: $draft.bloodGroup) {
                    Text("Select blood group").tag("")
                    ForEach(bloodGroups.dropFirst(), id: \.self) { Text($0).tag($0) }
                }
                .pickerStyle(.menu)
                .disabled(!editing)
                .frame(maxWidth: .infinity, alignment: .leading)
                field("Bike / vehicle", text: $draft.bike, keyboard: .default)
                field("Emergency contact", text: $draft.emergencyContact, keyboard: .numberPad, filter: digitsOnly)

                if !error.isEmpty {
                    Text(error).font(.caption).foregroundStyle(Color.appRed)
                }

                Button(editing ? "SAVE CHANGES" : "EDIT PROFILE") {
                    if editing {
                        save()
                    } else {
                        draft = store.profile
                        editing = true
                    }
                }
                .buttonStyle(.borderedProminent)
                .frame(maxWidth: .infinity)
            }
            .padding(16)
            .background(Color.white)
            .foregroundStyle(.black)
        }
        .clipShape(RoundedRectangle(cornerRadius: 18))
        .shadow(color: .black.opacity(0.25), radius: 18, y: 10)
    }

    private var batteryPanel: some View {
        Panel {
            VStack(alignment: .leading, spacing: 10) {
                Label("BACKGROUND TRACKING", systemImage: "battery.100")
                    .font(.headline)
                Text("For live tracking while locked, keep Location permission on Always and allow Background App Refresh for CoRider.")
                    .font(.subheadline)
                    .foregroundStyle(Color.muted)
                Button("OPEN SETTINGS") {
                    if let url = URL(string: UIApplication.openSettingsURLString) {
                        UIApplication.shared.open(url)
                    }
                }
                .buttonStyle(.bordered)
            }
        }
    }

    private func field(_ title: String, text: Binding<String>, keyboard: UIKeyboardType, filter: ((String) -> String)? = nil) -> some View {
        TextField(title, text: Binding(
            get: { text.wrappedValue },
            set: { text.wrappedValue = filter?($0) ?? $0 }
        ))
        .textFieldStyle(.roundedBorder)
        .keyboardType(keyboard)
        .disabled(!editing)
    }

    private func save() {
        let name = draft.name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty else {
            error = "Name is required."
            return
        }
        guard draft.contact.count >= 10 else {
            error = "Enter valid mobile number."
            return
        }
        guard draft.emergencyContact.count >= 10 else {
            error = "Enter valid emergency contact."
            return
        }
        guard !draft.bloodGroup.isEmpty else {
            error = "Select blood group."
            return
        }
        draft.name = name
        store.saveProfile(draft)
        editing = false
        error = ""
    }

    private func lettersOnly(_ value: String) -> String {
        String(String(value.filter { $0.isLetter || $0.isWhitespace }).prefix(40))
    }

    private func digitsOnly(_ value: String) -> String {
        String(value.filter(\.isNumber).prefix(15))
    }
}
