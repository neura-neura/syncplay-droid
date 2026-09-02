<p align="center">
  <img src="docs/logo.svg" width="180" alt="Syncplay Droid logo">
</p>

# Syncplay Droid

Syncplay Droid is a native Android client for watching videos in sync with people using
[Syncplay](https://syncplay.pl/) on Windows, macOS, or Linux. It is written in Kotlin with Jetpack
Compose, Material 3 Expressive, and [AndroidX Media3](https://github.com/androidx/media) as its
playback/session foundation. A LibVLC compatibility backend is available for demanding Matroska,
HEVC Main10, multichannel-audio, and device-decoder cases.

The app speaks the standard Syncplay TCP/JSON protocol directly. Desktop participants can keep
using mpv, VLC, MPC-HC, MPC-BE, Noir Player, or another supported player integration: every client
controls its own player while the Syncplay server coordinates play, pause, and position.

[Download Syncplay Droid 0.3.2](https://github.com/neura-neura/syncplay-droid/releases/tag/v0.3.2)

## Features

- Join standard Syncplay rooms, including password-protected and managed rooms.
- Require STARTTLS by default, with certificate and hostname validation. Plain TCP remains an
  explicit compatibility option for older servers.
- Remember the server password using an AES-GCM key stored in Android Keystore. The secret is
  excluded from Android backup and device transfer.
- Play local files and HTTP(S) URLs through Media3, including supported HLS and DASH streams.
- Automatically route Matroska files to a LibVLC-backed Media3 `Player` compatibility mode, or
  choose Media3/VLC manually from the room menu without disconnecting the media controller.
- Browse SMB2/SMB3 shares directly inside the app. Positional reads, a 2 MiB read-ahead window,
  reconnect-once handling, and bounded playback buffers make large remote MKVs seekable without
  depending on a file manager's sequential `content://` pipe.
- Choose the file manager used to open videos and subtitles. MiX Explorer and other Storage Access
  Framework providers can return files from SMB locations without exposing SMB credentials to this
  app.
- List embedded subtitle tracks exposed by the active player, including PGS tracks in Matroska. The
  subtitle picker can switch between embedded tracks, disable subtitles, or open an external file.
- Load external SRT, ASS/SSA, WebVTT, and TTML subtitles. SAF/SMB sidecars are handed to LibVLC
  through retained descriptors or credential-free SMB locations. The selected sidecar is explicitly
  preferred over an embedded default track, selecting another sidecar replaces it, and changing
  videos clears it.
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

1. Install the APK from the [v0.3.2 release](https://github.com/neura-neura/syncplay-droid/releases/tag/v0.3.2).
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

- Available containers, codecs, and subtitle rendering behavior depend on Android and device
  capabilities. Automatic mode keeps AndroidX Media3 as the session foundation and chooses LibVLC
  for Matroska compatibility; the room menu provides a manual override.
- Plain `http://` media URLs are supported for local and legacy servers, but their traffic is not
  encrypted. Prefer `https://` whenever possible.
- Document providers may grant persistent or temporary access. When only temporary access is
  available, the playback service retains it for the active playback session.
- External file managers can still return SMB files as `content://` URIs. If their provider exposes
  only a sequential pipe, the app warns that seeking is unreliable and offers **SMB direct**.
- Direct SMB credentials live only in process memory and are never written to the generated URI,
  logs, saved state, or preferences.
- The downloadable 0.3.2 APK is debug-signed and debuggable for direct testing. It is not a
  production-signed or Google Play build.

## Acknowledgements and license

Protocol behavior was implemented from the compatible behavior of
[syncplay-noir](https://github.com/neura-neura/syncplay-noir) and
[Syncplay](https://github.com/Syncplay/syncplay).

Syncplay Droid is distributed under the [Apache License 2.0](LICENSE). Attribution and dependency
information is available in [NOTICE](NOTICE).
