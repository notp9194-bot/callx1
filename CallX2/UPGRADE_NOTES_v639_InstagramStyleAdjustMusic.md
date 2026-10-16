# v639 — Instagram-style "Adjust Music" screen (Reels photo + Status)

Screen: `ReelPhotoMusicTrimActivity` (shared by Reels photo/video and Status) redesigned to match Instagram's music clip screen.

- Full-bleed dimmed photo/video, **Cancel** (left), track-art button (tap = change song), **Done** (right).
- Big centre label (track title + artist). **Style chips** 🚫 / AA / AI / Aa / A restyle it live and are carried into the
  Music sticker (`labelStyle` 0–4 in the sticker JSON; `StatusStickerOverlayView.buildMusic` renders it; old stickers = 0).
- Clip-length circle (tap: 15 → 30 → 60 s), thin progress line, white play/stop button. Clip auto-plays when the track is ready.
- New `core/.../views/ClipWindowWaveformView`: waveform scrolls under a fixed centred gradient window (window = clip length).
  Same API as `AudioTrimWaveformView` (range/duration/playhead/amplitudes/listener).
- All view ids kept; preset RadioGroup + total-duration TextView kept hidden so existing logic is unchanged.
- Reels camera photo → `ReelPhotoEditorActivity`: Music sticker now opens this screen (`MusicPreviewMedia(photoUri)`), like Status.
- Reel **video** editor (`ReelEditorActivity`) still uses the direct music path (not changed).
- Old layout saved as `activity_reel_photo_music_trim.OLD.xml` outside the project (not in zip).
- Not compiled/tested on device here — run a Gradle build and try Status + Reels photo once.
