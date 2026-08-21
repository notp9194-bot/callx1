# v215 — Music Ticker Ultra Optimization

## Problem
`MusicTickerView` (bio + collab song-name rows) re-ran full text
shaping/rasterization via `canvas.drawText()` on **every animation frame**
(~60/sec) via an infinite `ValueAnimator`, and that animator kept running
for as long as the view was attached — including reels that were paused
(long-press) or off-screen (ViewPager2 offscreen fragments) but still
attached to the window. That's wasted CPU + battery on invisible/paused
ticker text.

## Fix

**`core/src/main/java/com/callx/app/views/MusicTickerView.java`**
1. **Glyph bitmap cache** — text is shaped + rasterized to an offscreen
   `Bitmap` once per `setText()`/size/color change (`rebuildBitmap()`).
   Every frame after that is a cheap `canvas.drawBitmap()` blit instead of
   re-running text layout each tick.
2. **Hardware layer while scrolling only** — `LAYER_TYPE_HARDWARE` applied
   only while the scroll animator runs, dropped to `NONE` the instant it
   stops. Same pattern already used for the music-disc rotation.
3. **`pause()` / `resume()`** — new public API so the ticker's animator can
   be frozen/resumed explicitly, independent of attach/detach.
4. **Bug fix**: previously, if a new reel bind kept `needsScroll == true`
   but changed the actual song text, the running animator was never
   restarted — it kept the OLD text's duration/cycle length, so the new
   (different-width) text would scroll at the wrong speed. Now every
   content/size change always restarts the animator with fresh timing.

**`feature-reels/.../controllers/ReelUiController.java`**
- `startDiscAnimation()` / `stopDiscAnimation()` — already the exact hook
  fired whenever a reel becomes the active/playing one vs. gets paused or
  scrolled away (see `ReelPlayerFragment#applyVisibleState` and
  `ReelPlayerController#pausePlayback`) — now also call
  `tvBioSongName.resume()/pause()` and `tvCollabSongName.resume()/pause()`.
  The ticker now genuinely stops animating the moment its reel isn't the
  one on screen/playing, instead of running in the background.
- `tvCollabSongName` promoted from a local variable to a field so these
  hooks can reach it (it's lazily bound the first time a collab reel
  inflates `stub_collab_row`).
- `release()` now also calls `.release()` on both ticker views to free
  their cached bitmaps on fragment teardown.

## Untouched
`.setText()` call sites, XML layouts, and all other logic are unchanged —
drop-in optimization only.
