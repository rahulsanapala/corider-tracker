# Windows Setup

Use Android Studio for this project. It bundles the IDE, SDK Manager, emulator tooling, and a JDK, which is the least painful path on Windows.

## Recommended Versions

- Android Studio: latest stable release.
- Android Gradle Plugin: `9.2.0`.
- Gradle: `9.4.1`.
- JDK: `17`, preferably Android Studio's bundled JDK.
- Android SDK Platform: `Android 16.0 / API 36`.
- Android SDK Build-Tools: `36.0.0`.
- Android SDK Platform-Tools: latest installed by SDK Manager.
- Kotlin: AGP built-in Kotlin; no separate `org.jetbrains.kotlin.android` plugin.

## Install Android Studio

1. Download Android Studio from <https://developer.android.com/studio>.
2. Run the `.exe` installer.
3. Keep the default components selected:
   - Android Studio
   - Android SDK
   - Android Virtual Device
   - Android Emulator
4. Finish the Setup Wizard and let it install the recommended SDK packages.

## Install The SDK Pieces

In Android Studio:

1. Open `File > Settings > Languages & Frameworks > Android SDK`.
2. In `SDK Platforms`, install:
   - `Android 16.0 / API 36`
3. In `SDK Tools`, enable `Show Package Details`, then install:
   - `Android SDK Build-Tools 36.0.0`
   - `Android SDK Platform-Tools`
   - `Android SDK Command-line Tools`
   - `Android Emulator`
4. Click `Apply`.

## Set Environment Variables

Android Studio can build without these, but they make PowerShell commands work.

Typical SDK path:

```powershell
C:\Users\RahulSanapala\AppData\Local\Android\Sdk
```

Set user variables in PowerShell:

```powershell
[Environment]::SetEnvironmentVariable("ANDROID_HOME", "$env:LOCALAPPDATA\Android\Sdk", "User")
[Environment]::SetEnvironmentVariable("ANDROID_SDK_ROOT", "$env:LOCALAPPDATA\Android\Sdk", "User")
```

Add these to your user `Path`:

```text
%LOCALAPPDATA%\Android\Sdk\platform-tools
%LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest\bin
```

Close and reopen PowerShell, then check:

```powershell
adb version
sdkmanager --list
```

## Open This Project

1. Start Android Studio.
2. Choose `Open`.
3. Select `C:\Users\RahulSanapala\Documents\and`.
4. Let Gradle sync finish.
5. If Android Studio asks for Gradle JDK, choose `Embedded JDK` or `jbr-17`.

## Run Locally

Start the relay:

```powershell
py relay\ride_relay.py --host 0.0.0.0 --port 8080
```

For an emulator, keep the app relay URL as:

```text
http://10.0.2.2:8080
```

For a physical phone, use your Windows LAN IP:

```powershell
ipconfig
```

Example app relay URL:

```text
http://192.168.1.25:8080
```

If Windows Firewall prompts for Python, allow private network access.

## Quick Troubleshooting

- `adb` not found: reopen PowerShell after adding `platform-tools` to Path.
- Gradle asks for JDK: use Android Studio's embedded JDK 17.
- SDK not found: confirm `ANDROID_HOME` points to `%LOCALAPPDATA%\Android\Sdk`.
- Emulator cannot reach relay: use `http://10.0.2.2:8080`, not `localhost`.
- Phone cannot reach relay: make sure phone and laptop are on the same Wi-Fi and Windows Firewall allows Python.
