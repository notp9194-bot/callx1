# v265 — Status's Reel Edit screen: dead tools now actually work

## Bug
`NewStatusActivity`'s video-capture flow opens `ReelEditorActivity` (the same
"Edit Reel" screen used for Reels) with `target_status=true`. In the editor
UI every tool looked like it worked — badges lit up, "applied ✓" toasts fired
— but `finishForStatusResult()` only ever forwarded video/caption/sound/
filter/plain-sticker/speed/thumbnail-path back to Status. **Subtitles,
Transitions, Voice Effects, and Audio Mixer picks were silently dropped** and
had zero effect on the posted status.

## Root cause
1. `finishForStatusResult()` never packaged subtitles/transitions/voice/mix
   state into its result Intent (Reels' own `ReelUploadActivity` path forwards
   all of these; the Status shortcut path skipped them entirely).
2. Audio Mixer / Voice Effects have a real processing engine (`AudioMixHelper`,
   already used on the chat "Advance Editing" exit path) but it was never
   invoked before returning to Status — so picked music/voiceover/pitch never
   got baked into the actual audio track.
3. Subtitles have no time-synced burn-in anywhere in the app.
4. Transitions are a between-clips effect with nothing to apply to on a
   single status video.
5. A custom Thumbnail pick was accepted in the editor but Status always
   regenerated its own first-frame thumbnail on upload anyway.

## Fix
- **Audio Mixer + Voice Effects**: `proceedToUploadInternal()` now runs the
  same `AudioMixHelper` bake (new `runAudioMixThenFinishForStatus()`, mirrors
  `runAudioMixThenGoToMediaEdit()`) before finishing to Status whenever a
  track/voiceover/pitch/fade/normalize was set. Voice Effects' 0.5–2.0x pitch
  ratio is converted to semitones and combined with the Audio Mixer's own
  semitone slider so both tools' pitch picks are honoured together.
- **Subtitles**: `mergeSubtitleCaptionIntoOverlay()` burns the first caption
  line into the video as a bottom-anchored text overlay, riding along on the
  existing filter/sticker hard-bake (`ReelVideoExportEngine`) — no new export
  pipeline needed.
- **Transitions**: hidden for `targetStatus` sessions instead of left as a
  dead control, since there's no second clip for it to transition into/out of.
- **Thumbnail**: `thumbnail_path`/`thumbnail_frame_ms` are now forwarded, and
  `NewStatusActivity` uses the person's picked frame (`pendingCustomThumbnailFile`)
  as the status cover instead of always overwriting it with an auto-grabbed
  frame from `VideoCompressor`.

## Files touched
- `feature-reels/.../editor/ReelEditorActivity.java`
- `feature-status/.../compose/NewStatusActivity.java`
