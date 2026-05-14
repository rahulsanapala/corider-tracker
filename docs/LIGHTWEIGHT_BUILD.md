# Lightweight Build

This setup avoids installing full Android Studio. You still need the Android SDK somewhere because every Android APK is built with Android platform tools, but the SDK can live on GitHub Actions instead of your laptop.

## Local Minimal Setup

Install only:

- JDK 17.
- Android SDK command-line tools.
- Android SDK Platform `android-36`.
- Android SDK Build-Tools `36.0.0`.
- Android SDK Platform-Tools.

The project uses Android Gradle Plugin 9 built-in Kotlin, so there is no separate Kotlin Android plugin to install or configure.

Then build from PowerShell:

```powershell
.\gradlew.bat assembleDebug
```

The APK will be created at:

```text
app\build\outputs\apk\debug\app-debug.apk
```

## Cloud Build With GitHub Actions

This repo now includes:

```text
.github\workflows\android-debug-apk.yml
```

Push the project to GitHub. The workflow will:

1. Install JDK 17.
2. Install Android SDK Platform `android-36`.
3. Install Build-Tools `36.0.0`.
4. Run `./gradlew assembleDebug`.
5. Upload the debug APK as an artifact named `corider-debug-apk`.

## Lightweight Windows Flow

1. Edit code in VS Code.
2. Run the relay locally:

```powershell
py relay\ride_relay.py --host 0.0.0.0 --port 8080
```

3. Push changes to GitHub.
4. Download the APK from the GitHub Actions run.
5. Install it on your phone:

```powershell
adb install -r app-debug.apk
```

You only need `adb` locally for phone install and logs. If you download the APK directly on your phone, you can skip local `adb` too.
