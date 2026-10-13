# v626 — Status: Mix Audio (steps 1–3)

Flow: song pick → Adjust Music → Done → **Mix Audio (new)** → Done → music sticker.

## 1. ReelAudioMixerActivity (shared with Reels)
- New extras: `EXTRA_MUSIC_START_MS` / `EXTRA_MUSIC_END_MS` (preview now starts at START and loops
  back at END instead of always 0:00 → end), `EXTRA_STATUS_MODE` (hides Voiceover + Normalize;
  hides Original row when there is no video, i.e. photo status).
- Preview applies Fade In / Fade Out live per loop; mute/volume share one `applyMusicVolume()`.
- New results: `RESULT_MUSIC_START_MS` / `RESULT_MUSIC_END_MS` (0/0 if track swapped via Change).
- ReelUploadActivity now passes its trim range to the mixer (fixes the same 0:00 preview bug in Reels)
  and resets the range if the track is changed inside the mixer.

## 2. StatusStickerPickerSheet
- Adjust → Done launches the mixer by class name (`REQ_MUSIC_MIX`), status mode on.
- Mixer Done → sticker with mix. Mixer Back → sticker still added with default mix (range was already
  confirmed on Adjust). If mixer can't launch → old direct-add path.

## 3. Sticker JSON + parser
- New optional keys on the music sticker: `musicVol`, `origVol` (video only), `fadeInMs`, `fadeOutMs`, `pitch`.
  `musicVol`/`origVol` are written whenever Mix Done is tapped; fade/pitch only when non-zero.
- `StatusStickerOverlayView` parses them (`hasMusicMix()`, `getMusicOrigVol/MixVol/FadeInMs/FadeOutMs/Pitch`).
  Absent → -1 / 0, so old stickers and old viewers behave exactly as before.

## Not yet applied (next round)
Composer preview (NewStatusActivity) and Viewer (StatusViewerActivity + StatusMusicClip) still ignore
these fields, so the mix is saved but not audible in Status playback until steps 4–5.
