# Third-party notices

Syncplay Droid 0.4.0 uses `io.github.abdallahmehiz:mpv-android-lib:0.1.12` as its sole media
playback engine. The Android wrapper is licensed under the MIT License. Its AAR contains native
mpv, FFmpeg, libass, and related libraries; the distributed native build enables GPL and version-3
components. Those components remain under their respective upstream licenses.

- mpv-android-lib source (exact tag):
  https://github.com/abdallahmehiz/mpv-android/tree/v0.1.12
- mpv source and copyright/license information:
  https://github.com/mpv-player/mpv
- FFmpeg source and legal information:
  https://ffmpeg.org/download.html and https://ffmpeg.org/legal.html
- libass source and license:
  https://github.com/libass/libass

The corresponding source and build scripts for the exact embedded binaries are available from the
mpv-android-lib tag above. Syncplay Droid's complete corresponding source is the source attached to
the matching GitHub release/tag. No warranty is provided. See `LICENSE` for the GNU GPL version 3
terms governing this distribution.

Other principal dependencies retain their own licenses:

- AndroidX libraries used for Compose UI and Android platform integration: Apache License 2.0.
- SMBJ: Apache License 2.0.
- Gson: Apache License 2.0.
- Kotlin and kotlinx.coroutines: Apache License 2.0.

Protocol behavior was implemented from Syncplay and syncplay-noir, both distributed under the
Apache License 2.0. See `NOTICE` for attribution.
