<p align="center">
  <img src="docs/logo.svg" width="180" alt="Syncplay Droid logo">
</p>

# Syncplay Droid

Syncplay Droid is a native Android client for watching videos in sync with people using
[Syncplay](https://syncplay.pl/) on Windows, macOS, or Linux. It is written in Kotlin with Jetpack
Compose, Material 3 Expressive, and [AndroidX Media3](https://github.com/androidx/media) as its
internal player.

The app speaks the standard Syncplay TCP/JSON protocol directly. Desktop participants can keep
using mpv, VLC, MPC-HC, MPC-BE, Noir Player, or another supported player integration: every client
controls its own player while the Syncplay server coordinates play, pause, and position.

[Download Syncplay Droid 0.3.0](https://github.com/neura-neura/syncplay-droid/releases/tag/v0.3.0)

## Features

- Join standard Syncplay rooms, including password-protected and managed rooms.
- Require STARTTLS by default, with certificate and hostname validation. Plain TCP remains an
  explicit compatibility option for older servers.
- Remember the server password using an AES-GCM key stored in Android Keystore. The secret is
  excluded from Android backup and device transfer.
- Play local files and HTTP(S) URLs through Media3, including supported HLS and DASH streams.
- Choose the file manager used to open videos and subtitles. MiX Explorer and other Storage Access
  Framework providers can return files from SMB locations without exposing SMB credentials to this
  app.
- Load SRT, ASS/SSA, WebVTT, and TTML subtitles. Selecting a new subtitle atomically replaces the
  previous external track, and changing videos clears the old track.
- Use Media3 playback controls, fullscreen landscape playback, immersive mode, background playback,
  a media notification, and Android system media controls.
- Synchronize play, pause, and seek in both directions, with speed-based drift correction for small
  offsets and a direct seek for larger offsets.
- Use room chat, readiness, participant/file status, and shared playlist views when the server
  supports them.
- Adapt the Material 3 Expressive interface across portrait phones, short landscape windows, and
  tablet-sized layouts.

## Requirements

- Android 6.0 (API 23) or newer.
- Access to a standard Syncplay server.
- A local copy of the same media, or a URL that each participant can open.

Syncplay coordinates playback; it does not send the video between participants. Desktop file paths
from a shared playlist are visible on Android, but they cannot be opened automatically unless the
same path is available through an Android document provider.

## Quick start

1. Install the APK from the [v0.3.0 release](https://github.com/neura-neura/syncplay-droid/releases/tag/v0.3.0).
2. Enter the same server and exact room name as the desktop participants.
3. Join the room and open your local copy of the video or a Media3-compatible URL.
4. Open the **Room** tab and confirm that the file name, duration, and size match.
5. Mark yourself **Ready**, then use the normal playback controls.

The quick-port buttons only replace the port and preserve the host or IP address already entered.
When using MiX Explorer, choose it in the app picker, browse to the SMB location, and return the
selected file to Syncplay Droid.

## Build from source

Install JDK 17 and Android SDK 37, then run on Windows:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

The installable debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Instrumented tests require a connected device or emulator:

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

## Compatibility and security notes

- Available containers, codecs, and subtitle rendering behavior depend on the Android version and
  device decoder support exposed through Media3.
- Plain `http://` media URLs are supported for local and legacy servers, but their traffic is not
  encrypted. Prefer `https://` whenever possible.
- Document providers may grant persistent or temporary access. When only temporary access is
  available, the playback service retains it for the active playback session.
- The downloadable 0.3.0 APK is debug-signed and debuggable for direct testing. It is not a
  production-signed or Google Play build.

## Acknowledgements and license

Protocol behavior was implemented from the compatible behavior of
[syncplay-noir](https://github.com/neura-neura/syncplay-noir) and
[Syncplay](https://github.com/Syncplay/syncplay).

Syncplay Droid is distributed under the [Apache License 2.0](LICENSE). Attribution and dependency
information is available in [NOTICE](NOTICE).
