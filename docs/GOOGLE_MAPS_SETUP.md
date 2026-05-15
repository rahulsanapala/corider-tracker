# Google Maps Setup

The Android app now uses Google Maps for the live rider view.

## Get An API Key

1. Open Google Cloud Console.
2. Create or select a project.
3. Enable `Maps SDK for Android`.
4. Create an API key.
5. Restrict the key to Android apps.
6. Add this package name:

```text
com.corider.tracker
```

7. Add your debug or release SHA-1 certificate fingerprint.

For a local debug build, get the SHA-1 with:

```powershell
.\gradlew.bat signingReport
```

Look for the `debug` variant and copy the `SHA1` value.

## Add The Key Locally

Create or edit `local.properties` in the repo root:

```properties
MAPS_API_KEY=your_google_maps_api_key_here
```

Do not commit `local.properties`; it is already ignored by Git.

Then rebuild:

```powershell
.\gradlew.bat assembleDebug
```

Install the APK:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## Add The Key For GitHub Actions

If you build APKs in GitHub Actions:

1. Open the GitHub repo.
2. Go to `Settings > Secrets and variables > Actions`.
3. Add a repository secret named:

```text
MAPS_API_KEY
```

The workflow passes this into Gradle during `assembleDebug`.

## Notes

- If the map shows a gray grid or API key error, the key is missing, restricted incorrectly, or Maps SDK for Android is not enabled.
- The app still needs the relay URL for live rider updates; Google Maps only renders the map and markers.
- Google Maps may require billing to be enabled on the Google Cloud project.
