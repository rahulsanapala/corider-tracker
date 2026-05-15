# CoRider Tracker

Native Android starter for joining a shared ride and tracking nearby co-riders with low network usage.

The starter uses:

- Android Kotlin.
- Google Maps SDK for Android for the live rider map.
- A foreground location service so tracking can continue while the app is not in front.
- Adaptive publishing: location is sent only after meaningful movement, speed changes, or a heartbeat.
- A tiny Python relay server that keeps one Server-Sent Events stream open per rider and accepts compact location POSTs.

## Project Layout

- `app/` - Android app source.
- `relay/ride_relay.py` - local development relay server.
- `docs/NETWORK_STRATEGY.md` - protocol and bandwidth strategy.
- `docs/WINDOWS_SETUP.md` - full Android Studio setup for Windows.
- `docs/LIGHTWEIGHT_BUILD.md` - command-line and GitHub Actions build flow.
- `docs/DEPLOY_PUBLIC_RELAY.md` - public HTTPS relay deployment.
- `docs/GOOGLE_MAPS_SETUP.md` - Google Maps API key setup.

## Run The Relay

From this folder:

```powershell
python relay\ride_relay.py --host 0.0.0.0 --port 8080
```

If Windows has the Python launcher but not the `python` alias:

```powershell
py relay\ride_relay.py --host 0.0.0.0 --port 8080
```

Android emulator relay URL:

```text
http://10.0.2.2:8080
```

Physical phone relay URL:

```text
http://YOUR_LAPTOP_LAN_IP:8080
```

For production, put the relay behind TLS and replace the in-memory room store with authenticated ride sessions.

For outside-your-Wi-Fi use, deploy the relay publicly and use its HTTPS URL in the app. See `docs/DEPLOY_PUBLIC_RELAY.md`.

## Open The Android App

Open this folder in Android Studio and sync Gradle. The app is configured with Android Gradle Plugin `9.2.0`, AGP built-in Kotlin, `compileSdk 36`, and `minSdk 26`.

For a lighter setup without full Android Studio, use the Gradle Wrapper:

```powershell
.\gradlew.bat assembleDebug
```

Or push to GitHub and let `.github/workflows/android-debug-apk.yml` build the debug APK in the cloud.

After installing:

1. Start the relay server.
2. Launch the app on two phones or emulators.
3. Enter the same ride code.
4. Enter each rider name.
5. Tap `Start`.

## Why This Is Low Network

The app does not poll for co-riders. Each rider keeps one event stream open for incoming updates and sends their own location only when the `LocationGate` decides the location has changed enough:

- Fast movement: about every 3 seconds or 25 meters.
- Normal movement: about every 5 seconds or 12 meters.
- Slow/stopped: about every 15 seconds or 8 meters.
- Heartbeat: at least once per minute.

The location payload uses integer coordinates (`latitude * 1e7`, `longitude * 1e7`) and short JSON keys. That keeps the MVP readable while still avoiding large payloads.

## Production Next Steps

- Swap the relay transport to MQTT over TLS for even lower overhead at scale.
- Add ride authentication, invite codes, and short-lived membership tokens.
- Store only the last known location with a TTL; avoid keeping ride history unless the user asks for it.
- Add a real map layer after the tracking protocol is stable.
- Add battery safeguards such as auto-stop when the ride has been idle too long.
