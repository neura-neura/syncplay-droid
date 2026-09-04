<p align="center">
  <img src="docs/logo.svg" width="180" alt="Syncplay Droid logo">
</p>

# Syncplay Droid 0.4.0

Syncplay Droid is a native Android client for watching videos in sync with people using
[Syncplay](https://syncplay.pl/) on Windows, macOS, or Linux. It is written in Kotlin with Jetpack
Compose and Material 3 Expressive. Version 0.4.0 is MPV-only: the bundled libmpv engine handles
all media playback, while AndroidX libraries provide the user interface and Android platform
integration.

The app speaks the standard Syncplay TCP/JSON protocol directly. Other Syncplay clients can keep
using their own players: each client controls its local playback while the Syncplay server
coordinates play, pause, and position.

[Download Syncplay Droid 0.4.0](https://github.com/neura-neura/syncplay-droid/releases/tag/v0.4.0)

## Features

- Join standard Syncplay rooms, including password-protected and managed rooms.
- Require STARTTLS by default, with certificate and hostname validation. Plain TCP remains an
  explicit compatibility option for older servers.
- Remember the server password using an AES-GCM key stored in Android Keystore. The secret is
  excluded from Android backup and device transfer.
- Play local files, Android document-provider files, direct SMB2/SMB3 sources, and HTTP(S) URLs
  through MPV.
- Keep playback running in the background with fullscreen landscape playback, immersive mode, an
  Android media notification, and system media controls.
- Browse SMB2/SMB3 shares directly inside the app. MPV uses a bounded loopback range bridge with
  positional reads and reconnect-once handling, so large remote MKVs remain seekable without
  depending on a file manager's sequential `content://` pipe.
- Choose the file manager used to open videos and subtitles. MiX Explorer and other Storage Access
  Framework providers can return files from SMB locations without exposing SMB credentials to this
  app.
- Select internal subtitle tracks exposed by MPV, including text and bitmap tracks when the media
  provides them. The subtitle picker can switch tracks, disable subtitles, or open an external
  sidecar.
- Open external SRT, VTT, ASS, SSA, TTML, SUP/PGS, and Noir-compatible ZIP subtitles from a
  document provider or SMB. A selected sidecar replaces the previous external sidecar, and
  changing videos clears it.
- Customize subtitle appearance for text captions: font size, text and background colors,
  background opacity, position, maximum width, padding, weight, line height, letter spacing,
  corner radius, and text shadow. Appearance and timing preferences are stored locally.
- Start with Noir Player's GothamPro CSS font stack (downloaded and cached from its public CDN,
  with Android sans-serif as the offline fallback), choose local Android font families, or load
  TTF/OTF faces declared by another HTTP(S) CSS `@font-face` stylesheet.
- Set a subtitle offset from -120 to +120 seconds, optionally remember it, and use previous/next
  cue alignment for the selected text track.
- Export an offset-adjusted copy of the selected external text subtitle, including the first
  supported track in a ZIP. VTT stays VTT; SRT, ASS, and SSA are written as SRT. Internal and
  bitmap tracks are not exportable.
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

1. Install the APK from the [v0.4.0 release](https://github.com/neura-neura/syncplay-droid/releases/tag/v0.4.0).
2. Enter the same server and exact room name as the desktop participants.
3. Join the room and open your local copy, an HTTP(S) URL, or **SMB direct**.
4. Open the **Room** tab and confirm that the file name, duration, and size match.
5. Mark yourself **Ready**, then use the normal playback controls.

The quick-port buttons only replace the port and preserve the host or IP address already entered.
When using MiX Explorer, Solid Explorer, or another SMB-capable file manager, choose it in the app
picker, browse to the remote location, and return the selected file to Syncplay Droid.

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

- Available containers, codecs, and subtitle rendering behavior depend on the bundled MPV build,
  Android, and the device. Bitmap subtitle rendering remains native to MPV; text captions use the
  MPV subtitle pipeline and the app's appearance controls.
- Plain `http://` media and CSS URLs are accepted for local or legacy servers, but their traffic is
  not encrypted. Prefer `https://` whenever possible.
- Document providers may grant persistent or temporary access. When only temporary access is
  available, the playback service retains it for the active playback session.
- External file managers can still return SMB files as `content://` URIs. If their provider exposes
  only a sequential pipe, the app warns that seeking is unreliable and offers **SMB direct**.
- Direct SMB credentials live only in process memory and are never written to the generated URI,
  logs, saved state, or preferences.
- The built-in **SMB direct** browser connects MPV to a positional SMB source. Selecting the same
  SMB file through Solid Explorer or another seekable document provider also keeps the MPV path.
- A locally built debug APK is debuggable and is not a production-signed or Google Play build.

## Acknowledgements and license

Protocol behavior was implemented from the compatible behavior of
[syncplay-noir](https://github.com/neura-neura/syncplay-noir) and
[Syncplay](https://github.com/Syncplay/syncplay).

Syncplay Droid 0.4.0 is distributed under the [GNU GPL v3 or later](LICENSE). Attribution, native
component licenses, and corresponding-source locations are documented in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
