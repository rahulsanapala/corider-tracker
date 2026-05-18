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

## GitHub iOS Build

The repo includes `.github/workflows/ios-build.yml` so GitHub can build the iOS app on a hosted macOS runner.

### Build without Apple signing

Push the code or run **Actions > iOS Build > Run workflow** with `Build signed iPhone IPA` left unchecked. The `Build iOS simulator app` job creates a simulator artifact named `corider-ios-simulator-app`.

This proves the iOS code compiles, but it cannot be installed on a physical iPhone.

### Build signed IPA for iPhone

To produce an installable `.ipa`, add these GitHub repository secrets:

- `IOS_AGORA_APP_ID`: same Agora App ID used by Android.
- `IOS_AGORA_TOKEN`: blank is okay while Agora token auth is disabled for testing.
- `IOS_GOOGLE_SERVICE_INFO_PLIST_BASE64`: base64 of `GoogleService-Info.plist`.
- `IOS_CERTIFICATE_BASE64`: base64 of Apple development `.p12` certificate.
- `IOS_CERTIFICATE_PASSWORD`: password used when exporting the `.p12`.
- `IOS_PROVISIONING_PROFILE_BASE64`: base64 of the `.mobileprovision` file.
- `IOS_PROVISIONING_PROFILE_NAME`: provisioning profile display name.
- `IOS_TEAM_ID`: Apple Developer Team ID.
- `IOS_KEYCHAIN_PASSWORD`: any temporary password for the CI keychain.

Then run **Actions > iOS Build > Run workflow** and check `Build signed iPhone IPA`. The `Build signed iPhone IPA` job uploads `corider-ios-signed-ipa`.

On macOS, create the base64 values with:

```bash
base64 -i GoogleService-Info.plist | pbcopy
base64 -i certificate.p12 | pbcopy
base64 -i profile.mobileprovision | pbcopy
```

After the workflow finishes, open the completed run and download the artifact from the **Artifacts** section at the bottom of the run summary.

Official setup references:

- Firebase Apple setup: https://firebase.google.com/docs/ios/setup
- Firebase Realtime Database for Apple: https://firebase.google.com/docs/database/ios/start
- Agora iOS SDK package: https://github.com/AgoraIO/AgoraRtcEngine_iOS

## Cross Platform Sync

Android and iOS riders are compatible when they use the same Firebase project and Agora App ID. Group code, rider locations, SOS, regroup, admins, removed riders, safety checks, and voice channel naming all match the Android implementation.

Reliable background push alerts on iOS need APNs/FCM server-side push. This scaffold supports live Firebase listeners while the app is active and background location/audio modes while tracking/walkie is running.
