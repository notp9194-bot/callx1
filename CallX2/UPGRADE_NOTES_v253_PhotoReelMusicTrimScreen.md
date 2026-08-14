# v253 — Photo Reel: Music Preview + Trim Screen

## What changed
After selecting photos → "+ Add Music/Sound" → picking a track on the
Trending Audio screen, photo-slideshow reels now open a new intermediate
screen instead of immediately applying the full track from 0:00:

**ReelPhotoMusicTrimActivity**
- Top: live reel-style photo preview — cycles through the selected photos
  at the reel's per-photo slide duration while the user taps preview/play.
- Bottom: the picked track (cover, title, artist) + the same dual-handle
  trim controls (waveform, Start/End seekbars, 15s/30s/60s presets) used
  elsewhere in the app.
- "Preview" plays the trimmed audio range on loop and cycles the photos in
  sync, so the user hears/sees roughly what the final reel will do.
- "Use" (checkmark, top-right) returns the trimmed `startMs`/`endMs` plus
  the track info to the upload screen.

## Why only the trimmed part plays after upload
This didn't need new playback logic — `reel.musicStartMs` / `reel.musicEndMs`
were already read by `ReelPlayerController` / `ReelPlayerFragment` at view
time (explicitly for photo-reel audio, per an existing `✅ FIX: ms-precision
trim for photo audio` comment). The gap was only on the **upload** side:
photo mode never gave the user a way to set a non-zero trim range — it was
always reset to `0,0` (full track from the start) right after picking a
sound. This screen is what lets the uploader actually choose the range.

## New files
- `feature-reels/src/main/java/com/callx/app/editor/ReelPhotoMusicTrimActivity.java`
- `feature-reels/src/main/res/layout/activity_reel_photo_music_trim.xml`

## Changed files
- `feature-reels/src/main/AndroidManifest.xml` — registered the new activity.
- `feature-reels/src/main/java/com/callx/app/upload/ReelUploadActivity.java`
  - Added `REQ_PHOTO_MUSIC_TRIM` request code + import.
  - `onActivityResult` for `REQ_TRENDING_AUDIO`: if `isPhotoMode` and photos
    are already selected, launches `ReelPhotoMusicTrimActivity` (passing the
    selected photo URIs + `selectedDurationMs` for preview pacing) instead of
    applying the track directly. Video-mode behavior is unchanged.
  - New `onActivityResult` branch for `REQ_PHOTO_MUSIC_TRIM` applies the
    returned track + `musicStartMs`/`musicEndMs` and refreshes the audio card
    UI, same as the existing direct-apply path did.

## Behavior preserved
- Video-mode "Add Music" flow is untouched (still applies immediately; video
  reels use the existing `ReelAudioMixerActivity` mixer for adjustments).
- "Change" button in photo mode goes through the same `REQ_TRENDING_AUDIO`
  path, so re-picking a track also re-opens the trim screen.
