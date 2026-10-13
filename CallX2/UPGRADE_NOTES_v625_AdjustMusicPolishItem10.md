# v625 — Adjust Music: small polish items (polish item 10)

`ReelPhotoMusicTrimActivity` (Reels + Status) + `activity_reel_photo_music_trim.xml` + `AudioTrimWaveformView`.

- Haptics: `AudioTrimWaveformView` ticks (CLOCK_TICK) when a handle/window is grabbed and once each time the drag hits
  a limit (start 0, song end, 1s min gap). Preset chips tick on tap. Follows the system touch-feedback setting.
- Strings: all hardcoded text (title, subtitle, chips, buttons, content descriptions, toasts) moved to
  `values/strings_adjust_music.xml` (`adjust_music_*`). `onAudioLoadFailed` now takes a `@StringRes`.
- Dead code removed: Start/End seekbars + their sync (`setupSeekbars`, `setupSeekbarsRange`, `syncingRange`),
  unbound TextViews (`tvStartTime/EndTime/SelectedRange/StartValue/EndValue`), `msToTimeTenths`.
  `updateTimeLabels()` -> `syncRangeToWaveform()` (only pushes the range into the waveform).
- "Change": old cover art is cleared immediately (and stays cleared if the new track has no cover); same for the
  video-flow preview poster fallback. New cover fades in once loaded.
- Not compiled here (no Android SDK) — please run a Gradle build once.
