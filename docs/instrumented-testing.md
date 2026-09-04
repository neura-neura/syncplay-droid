# Instrumented testing

## Provisioning the MPV fixture

`MpvFixturePlaybackTest` and `MpvSurfacePlaybackTest` use a real Matroska file but intentionally
do not package a binary in the test APK. Without the fixture they are skipped. Keep the local
fixture outside the repository and provision it into the debug app's private `filesDir` with the
PowerShell helper:

```powershell
.\scripts\provision-android-test-fixture.ps1 `
  -FixturePath C:\fixtures\syncplay-mpv-fixture.mkv
```

The helper requires a debuggable `dev.neura.syncplay` APK and exactly one ready adb device. If
more than one device is connected, select one explicitly:

```powershell
.\scripts\provision-android-test-fixture.ps1 `
  -Serial emulator-5554 `
  -FixturePath C:\fixtures\syncplay-mpv-fixture.mkv
```

The script rejects empty/non-MKV input, pushes to a uniquely named temporary file under
`/data/local/tmp`, and uses `run-as dev.neura.syncplay` to copy into `filesDir`. It verifies the
byte count before atomically renaming the staged file to `files/mpv-fixture.mkv`, then removes the
temporary upload. The fixture remains app-private until the app is uninstalled or its data is
cleared; no media binary is checked into Git.

## Opt-in Gotham CSS/font check

`RemoteSubtitleFontLoaderInstrumentedTest` exercises the default Gotham CSS URL on a real Android
runtime. It downloads the pinned stylesheet, lets the production loader resolve its TrueType
sources, accepts the resulting font set into the registry, and verifies that a non-default
`Typeface` can be created from the downloaded `.ttf` files and that the `GothamPro` family is
present.

The test is opt-in because the CDN and device network are external state; running it in every CI
instrumentation pass would make an otherwise deterministic suite flaky. Run it only when network
availability is intentional:

```powershell
.\gradlew.bat connectedDebugAndroidTest `
  -Pandroid.testInstrumentationRunnerArguments.class=dev.neura.syncplay.ui.RemoteSubtitleFontLoaderInstrumentedTest `
  -Pandroid.testInstrumentationRunnerArguments.remoteFontNetwork=true
```

Without `remoteFontNetwork=true`, the test reports as skipped. A failure after opting in means the
device could not reach the pinned CDN or the remote font payload was not a valid Android TTF;
that should not be treated as an offline-suite regression.

## Opt-in Gotham overlay visual contract

`SubtitleTextOverlayFontContractTest` asks the production loader to retrieve/cache the default
GothamPro faces, renders the same probe caption through `SubtitleTextOverlay` once with that family
and once with the `sans-serif` fallback, then compares the captured 360 x 120 panes. The test only
requires that the panes differ by a small pixel threshold; it does not pin an exact screenshot, so
Android font rasterization and density can vary. This proves the downloaded family reaches the
overlay rather than stopping at `Typeface` parsing. The APK itself does not package Gotham files,
so the test is opt-in and needs device network access on its first run.

Run the single visual contract with:

```powershell
.\gradlew.bat connectedDebugAndroidTest `
  -Pandroid.testInstrumentationRunnerArguments.class=dev.neura.syncplay.ui.subtitle.SubtitleTextOverlayFontContractTest `
  -Pandroid.testInstrumentationRunnerArguments.remoteFontNetwork=true
```
