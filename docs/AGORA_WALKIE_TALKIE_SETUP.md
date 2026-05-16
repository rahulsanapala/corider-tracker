# Agora Walkie Talkie Setup

CoRider uses Agora voice calling for clear group communication. Each active riding group joins its own Agora channel.

## Create Agora App ID

1. Open the Agora Console.
2. Create a project for CoRider.
3. Copy the project App ID.
4. For quick local testing, keep the project in testing mode or disable token authentication.
5. For production, enable token authentication and add a small token server later.

## Add Local Config

Open `local.properties` in the project root and add:

```properties
AGORA_APP_ID=your_agora_app_id_here
AGORA_TOKEN=
```

`AGORA_TOKEN` can stay empty only for testing when token authentication is disabled in Agora. For production, this must be generated per channel and rider.

## Build

```powershell
.\gradlew.bat assembleDebug
```

The APK is created at:

```text
app\build\outputs\apk\debug\app-debug.apk
```

## How It Works In The App

1. Join or create a group.
2. Toggle the group to `ACTIVE`.
3. Open the group details.
4. Tap `START WALKIE` to listen to group voice.
5. Tap `TAP TO TALK` to speak.
6. Tap `STOP TALKING` to mute your microphone and keep listening.

## Common Errors

`Voice error 110` means Agora token authentication is enabled but the app has no valid token. For quick testing, create an Agora project with App ID authentication/testing mode, or generate a temporary RTC token for the exact channel.

For group code `RIDE-8034`, CoRider joins this Agora channel:

```text
corider_RIDE_8034
```

Use that exact channel name when generating a temporary Agora token.
