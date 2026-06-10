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

## GitHub iOS Build Without Apple Signing

The repo includes `.github/workflows/ios-build.yml` so GitHub can compile the iOS app on a hosted macOS runner without Apple signing or an Apple Developer Program account.

Push the code or run **Actions > iOS Build > Run workflow**. The workflow uploads:

- `corider-ios-simulator-app`: a zipped simulator `.app` from the Debug build.
- `corider-ios-simulator-smoke-test`: launch output, simulator screenshot, simulator logs, and crash reports if the app crashes on launch.
- `corider-ios-unsigned-ipa`: an unsigned `.ipa` from the Release iPhone build.

The unsigned IPA does not require an Apple Developer account, but it is not installable on a normal physical iPhone because iOS requires apps to be signed with a trusted certificate and provisioning profile. Use it as a build artifact only, or sign it later if you get signing credentials.

### Test without an iPhone

Use the GitHub Actions simulator smoke test:

1. Push iOS changes or run **Actions > iOS Build > Run workflow**.
2. Open the completed workflow run.
3. Download `corider-ios-simulator-smoke-test`.
4. Open `CoRider-simulator-smoke.png` to see the launched app screen.
5. If the workflow fails, check `CoRider-simulator-launch.txt`, `CoRider-simulator.log`, and any files under `crash-reports/`.

This is the closest no-device test path available from Windows. Apple's iOS Simulator itself only runs on macOS/Xcode, so GitHub Actions provides the hosted Mac that boots the simulator for you.

Optional GitHub repository secrets:

- `IOS_AGORA_APP_ID`: same Agora App ID used by Android.
- `IOS_AGORA_TOKEN`: blank is okay while Agora token auth is disabled for testing.
- `IOS_GOOGLE_SERVICE_INFO_PLIST_BASE64`: base64 of `GoogleService-Info.plist`, needed for a runnable Firebase-backed simulator build.

On macOS, create the Firebase base64 value with:

```bash
base64 -i GoogleService-Info.plist | pbcopy
```

Official setup references:

- Firebase Apple setup: https://firebase.google.com/docs/ios/setup
- Firebase Realtime Database for Apple: https://firebase.google.com/docs/database/ios/start
- Agora iOS SDK package: https://github.com/AgoraIO/AgoraRtcEngine_iOS

## Cross Platform Sync

Android and iOS riders are compatible when they use the same Firebase project and Agora App ID. Group code, rider locations, SOS, regroup, admins, removed riders, safety checks, and voice channel naming all match the Android implementation.

Reliable background push alerts on iOS need APNs/FCM server-side push. This scaffold supports live Firebase listeners while the app is active and background location/audio modes while tracking/walkie is running.
