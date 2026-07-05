# v56 — Audio Canvas Bubbles

Extends the Phase-1 Canvas rendering path (`MessageBubbleCanvasView`) to cover
`"audio"` (voice message) bubbles — the last remaining big item from the
"link/text/image/multi_media/reel_share/video already on Canvas" list.

## What moved to Canvas

- `isCanvasEligible()` in `MessagePagingAdapter` now returns `true` for
  `"audio"` messages (previously always fell through to the old
  View/ViewStub path).
- Old View path (`ll_audio` / `AudioWaveformView` / `ImageButton`) is
  **untouched** and still exists as a fallback for any code path that still
  constructs `TYPE_SENT`/`TYPE_RECEIVED` view-holders directly.

## New in `MessageBubbleCanvasView`

- `bindAudio(seed, timeText, isSent, isRead, isDelivered)` — binds a
  play/pause circle button + waveform track + elapsed-time label, all drawn
  directly on the Canvas, inside the normal chat-bubble shape (no caption
  support, same as the legacy `ll_audio` row).
- `setAudioPlaying(boolean)` — swaps the button glyph between ▶ and ⏸.
- `setAudioProgress(float 0..1)` — cheap, draw-only, called every 250ms
  tick while playing (same cadence the old `AudioWaveformView.setProgress()`
  used).
- `setAudioElapsedText(String)` — "m:ss" elapsed label; empty when idle
  (mirrors the legacy `tv_audio_dur`, which never showed a total duration
  upfront either — only live elapsed time once playback starts).
- `resetAudioPlayback()` — snaps the bubble back to idle (icon → ▶,
  progress → 0, label cleared); called on completion/error/switching to a
  different bubble.
- Waveform bars are generated once per bind from a stable seed (the audio
  URL) — same placeholder-bar approach as `AudioWaveformView.generateLevels()`,
  just drawn straight onto this view's Canvas instead of a separate child
  View + cached Bitmaps.
- **Fix vs. the legacy waveform**: `AudioWaveformView` hardcoded
  white-based bar colors, which only read correctly on the sent (colored)
  side — a received bubble's light background would have made the bars
  nearly invisible. The Canvas version derives both idle/played colors from
  the bubble's own resolved text color, so it's readable on both sides.
- Two new `OnBubbleClickListener` callbacks: `onAudioPlayPauseClick()` and
  `onAudioSeek(float fraction)` (fired on drag/tap across the waveform,
  matching `AudioWaveformView`'s DOWN/MOVE scrub behavior, not just a
  tap-to-jump).

## Adapter wiring (`MessagePagingAdapter`)

- `bindCanvasMessage()` has a new `isAudio` branch: binds the bubble and
  kicks off the same `MediaStreamCache.preloadPartial()` warm-up the legacy
  path used, so tapping play starts instantly.
- `toggleAudio()` / `playAudioFromPath()` (the shared `MediaPlayer`
  controller — unchanged state machine) now branch on `h.canvasView != null`
  via two small helpers, `setPlayPauseIcon()` and `resetAudioUi()`, so the
  exact same playback code drives either the legacy `ImageButton`/
  `AudioWaveformView` pair or the new Canvas bubble depending on which kind
  of holder is currently bound.
- Canvas-side seeking (`onAudioSeek`) resolves `player.getDuration()`
  dynamically against `playingPos`/`h.getAdapterPosition()` rather than
  capturing `durationMs` in a listener, since the click callback lives
  outside `playAudioFromPath()`'s scope.

## Known simplifications (documented in code, same spirit as v51/v52/v55)

- No real PCM waveform extraction — bars are seeded placeholders, same as
  before.
- If a message that's currently playing gets recycled/rebound mid-scroll,
  the freshly-bound Canvas bubble resets to idle rather than resuming the
  live progress display — the legacy View path had the same gap (only
  `playingVH`, a single holder reference, tracks "the" playing bubble).
- GIF/file/poll/contact/location remain on the old View path — audio was
  the last item on the "still to do" list from v55's notes.
