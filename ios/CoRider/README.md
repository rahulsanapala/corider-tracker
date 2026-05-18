# CoRider iOS

Native SwiftUI companion app for the existing Android CoRider app.

It uses the same shared services and keys:

- Firebase Realtime Database paths: `rides/{groupCode}/...`
- Firebase Auth: anonymous auth
- Agora voice channel: `corider_<GROUP_CODE>`
- Map: Apple MapKit on iOS

## Mac Setup

1. Install Xcode on macOS.
2. Install XcodeGen:
   `brew install xcodegen`
3. Copy `Config.xcconfig.example` to `Config.xcconfig` and put the same Agora App ID used by Android.
4. In Firebase Console, add an iOS app with bundle id `com.corider.tracker.ios`.
5. Download `GoogleService-Info.plist` and place it in `ios/CoRider/CoRider/`.
6. Generate and open the project:
   `xcodegen generate`
   `open CoRider.xcodeproj`
7. In Xcode, set your Apple Team, enable Background Modes for `Location updates` and `Audio`, then run on a real iPhone.

Official setup references:

- Firebase Apple setup: https://firebase.google.com/docs/ios/setup
- Firebase Realtime Database for Apple: https://firebase.google.com/docs/database/ios/start
- Agora iOS SDK package: https://github.com/AgoraIO/AgoraRtcEngine_iOS

## Cross Platform Sync

Android and iOS riders are compatible when they use the same Firebase project and Agora App ID. Group code, rider locations, SOS, regroup, admins, removed riders, safety checks, and voice channel naming all match the Android implementation.

Reliable background push alerts on iOS need APNs/FCM server-side push. This scaffold supports live Firebase listeners while the app is active and background location/audio modes while tracking/walkie is running.
