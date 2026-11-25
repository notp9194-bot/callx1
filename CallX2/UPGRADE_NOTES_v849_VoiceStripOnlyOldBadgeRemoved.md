# v849 — Photo+voice: only the new strip UI, old overlay pill removed

Target look = screenshot 2: photo (no overlay) + strip below [play | static waveform | m:ss | time+ticks].

## Root cause of the old UI still showing
`bindMedia()` empties `voiceStripRect`. When the SAME voice clip was rebound (v425 re-arm path in
`setVoiceCaption()`), no relayout was requested if the photo size was unchanged, so `onMeasure`
never refilled the strip rect -> `MediaRenderer.draw()` fell into the old `drawVoiceBadge()` branch
(dark pill: play / m:ss / 1x / download + time pill on the photo).

## Changes
- `MessageBubbleCanvasView.setVoiceCaption()` — re-arm path now calls `requestLayout()`.
- `MediaRenderer` — old fallback branch + `drawVoiceBadge()` + its geometry caches/paints deleted.
  If the strip rect is empty nothing old is drawn any more.
- `MessagePagingAdapter` — legacy `bindVoiceOnImage()`, its click listener, play-icon branches,
  `resetVoiceOnImageBottomMargin()` and VH fields (`flVoiceOnImage`, `ivVoicePlayOnImage`,
  `tvVoiceDurationOnImage`) removed. Legacy caption scrim stays (non-voice images).
- `layout_msg_voice_on_image.xml` — voice pill block removed.

## Perf (unchanged / confirmed)
- Waveform = cached ALPHA_8 mask bitmap (static), no per-draw generation.
- Photo has no overlay; time+ticks live in the strip.
- Animation (progress/elapsed ticks) only runs while the clip plays; strip is drawn live only then.
- Same RecyclerView ViewHolder / canvas view reuse, no new views per bind.

Not touched: `MediaViewerActivity` full-screen voice pill (different screen).
Built without Gradle/SDK — please run a normal build.
