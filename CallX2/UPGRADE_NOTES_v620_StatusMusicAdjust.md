# v620 — Status music: Reels ki "Adjust Music" screen reuse

## Flow
Add Status → media (photo/video) → Sticker/Music → song pick (Trending Audio)
→ **Adjust Music** (ReelPhotoMusicTrimActivity, same screen as Reels: waveform, Start/End,
15/30/60s presets, live preview over the status's own photo/video)
→ "Use" → Music sticker banta hai with `startMs`/`endMs`.
Composer preview (StatusMusicClip) aur StatusViewerActivity dono wahi range loop karte hain.

## Changes
- `core/.../stickers/StatusStickerPickerSheet.java`
  - New `MusicPreviewMedia` + `show(host, media, listener)` overload (opt-in; Reels editors unchanged).
  - After Trending Audio pick -> launches Adjust Music by class name (no feature-reels dependency).
  - FULL track url is sent to the trim screen and stored in the sticker when a range is set
    (range is relative to the full song; short preview url would be shorter than startMs/endMs).
  - If Adjust Music can't launch / no media (text-only, web gif) -> old direct-add path.
  - Back/cancel from Adjust Music -> no sticker.
  - "Change" inside Adjust Music is honoured (track info is read from the result).
- `feature-status/.../compose/NewStatusActivity.java`: `openStickerPicker()` passes current picked photo/video.

## v621 — Adjust Music polish (shared by Reels + Status)
- Waveform drag tooltip was clipped (drawn outside view bounds): parent `clipChildren=false` + 36dp top gap (both trim layouts).
- Preset chips: selecting a chip from code no longer snaps/overwrites a resumed custom range; chips use click (re-tap re-applies preset); chip auto-deselects when the range is dragged to a custom value.
- `onPause` stops preview; AudioFocus requested/abandoned (calls etc. stop preview).
- Done/Preview disabled until audio is prepared; load/playback error keeps them disabled; range clamped to real duration before returning.

## v622 — Adjust Music: window drag + auto-preview
- Waveform: drag anywhere between/outside the handles to slide the whole selection (fixed length, clamped to the track); touching outside the window re-centres it under the finger (tap-to-seek). Tooltip shows the start time while sliding.
- On finger-up (handle or window) the preview auto-plays from the new start; preview now loops the selected range (audio + slideshow/video) until the user pauses.
