# Syncplay Droid 0.4.0

Version 0.4.0 is MPV-only and expands subtitle control around the app's single playback engine.

## Highlights

- Play local, document-provider, SMB, and HTTP(S) media through MPV.
- Select internal MPV subtitle tracks or open external SRT, VTT, ASS, and SSA sidecars.
- Tune subtitle appearance, including colors, size, position, spacing, background, shadow, and
  font family.
- Use Noir's GothamPro CSS stack with an offline fallback, choose local Android fonts, or load
  TTF/OTF faces from an HTTP(S) CSS `@font-face` stylesheet.
- Load Noir-compatible ZIP sidecars (first SRT/VTT/ASS/SSA entry) and export their adjusted text.
- Set and optionally remember a subtitle offset, align the selected text track to the previous or
  next cue, and export an offset-adjusted copy. VTT remains VTT; SRT, ASS, and SSA export as SRT.
- Keep synchronized playback available through the Android media notification and system controls.

Android 6.0 (API 23) or newer is required. Subtitle rendering and media compatibility still depend
on the bundled MPV build and the Android device.
