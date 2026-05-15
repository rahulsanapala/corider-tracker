# Firebase + OSM Setup

This app now uses:

- OpenStreetMap tiles via `osmdroid`
- Firebase Realtime Database for rider sync
- Firebase Anonymous Auth for rider identity

## 1. Create Firebase Project

1. Open Firebase Console.
2. Create a project.
3. Add Android app with package name:

```text
com.corider.tracker
```

4. Download `google-services.json`.
5. Place it here:

```text
app/google-services.json
```

## 2. Enable Services

In Firebase Console:

1. Authentication -> Sign-in method -> enable `Anonymous`.
2. Realtime Database -> Create database.

For MVP testing, use permissive rules temporarily:

```json
{
  "rules": {
    ".read": "auth != null",
    ".write": "auth != null"
  }
}
```

## 3. Build and Install

```powershell
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## 4. App Flow

1. Both riders enter the same ride code.
2. Tap `Start`.
3. App signs in anonymously and syncs locations under:

```text
rides/{RIDE_CODE}/riders/{RIDER_ID}
```

## 5. Important Notes

- Keep internet and location enabled on all phones.
- For production, replace anonymous-only rules with stronger auth and per-ride authorization.
- OSM tiles are free but should respect tile usage policy for high traffic.

