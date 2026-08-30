# v299 — SoundDetail background seek-loop fix

## Problem
`SoundDetailFragment`'s `seekUpdateRunnable` (300ms polling loop that updates
the seek bar / current-time label) only rescheduled itself based on the
`isPlaying` flag. `onPause()` only called `pausePlayback()` when `isPlaying`
was true at that exact moment — fine in the common case, but it left no
second line of defense:

- If `isPlaying` flipped to `true` from an async ExoPlayer callback right
  around the pause edge (e.g. user hits Recents mid-buffer/mid-transition),
  the loop could keep firing on the main thread + ExoPlayer could keep
  playing in the background until the OS actually killed the process.
- Reels' player is already lifecycle-gated this way (`ReelsFragment#onStop`
  unconditionally tears down listeners/loops when the tab backgrounds).
  `SoundDetailFragment` had no equivalent `onStop()`.

## Fix (`SoundDetailFragment.java`)
1. **`seekUpdateRunnable` now checks `isResumed()` on every tick**, not just
   `isPlaying`. The loop hard-stops (no reschedule) the instant the fragment
   is no longer in the foreground, independent of whatever the `isPlaying`
   flag says.
2. **Added `onStop()`** — unconditionally removes the pending seek callback
   and pauses playback if `exoPlayer != null && isPlaying`, regardless of
   what `onPause()` already did. This mirrors the Reels feed's
   background-teardown pattern and closes the race window.

`onPause()` and `onDestroyView()` are untouched — this is additive
defense-in-depth, not a rewrite of the existing (already-mostly-correct)
pause path.

Scope: only `SoundDetailFragment.java`. `SoundDetailBottomSheet.java` (the
older, separate class) has its own `ExoPlayer` but no polling seek loop, so
it wasn't affected by this bug.

---

## Follow-up (same v299 pass) — player pooling + audio focus

**New file:** `feature-reels/src/main/java/com/callx/app/music/SoundPreviewPlayerPool.java`
- App-wide singleton, pool size 1 (only one sound preview plays at a time).
- `acquire()` lazily builds one `ExoPlayer` (video track disabled, fixed
  buffer window) and hands the same instance back out on every subsequent
  call — no renderer/decoder/thread rebuild on the 2nd+ Sound Detail open
  in a session.
- Audio focus handled entirely by Media3: the pooled instance calls
  `setAudioAttributes(attrs, handleAudioFocus=true)` once at construction,
  so it auto-requests focus on play and auto-pauses/ducks/resumes around
  calls or other apps' audio — no manual `AudioManager`/
  `AudioFocusRequest` code needed.
- `getExisting()` — context-free accessor so `releasePlayer()` can clean up
  safely even from an async `onPlayerError` callback that fires after the
  Fragment has detached.

**`SoundDetailFragment.java` changes:**
- `initAndStartPlayer()` now calls `SoundPreviewPlayerPool.get(...).acquire()`
  instead of `new ExoPlayer.Builder(...).build()`.
- `releasePlayer()` now returns the instance via `pool.release(this)`
  instead of `exoPlayer.release()`.
- Removed the now-dead `buildThermalAwareLoadControl()` (LoadControl is
  fixed once at pool construction, can't be swapped per-open) and the now-
  unused `DefaultLoadControl`/`DefaultTrackSelector`/`C` imports.
  `thermalManager` is untouched and still gates the waveform animation.

---

## Follow-up (same v299 pass) — thermal-throttled seek loop

`seekUpdateRunnable`'s polling interval was a fixed 300ms regardless of
device thermal state, even though the waveform right next to it already
freezes on HOT (`startWaveAnimation()` → `isThermalHot()`).

- Added `seekIntervalMs()`: returns 300ms normally, 800ms once
  `ReelThermalManager` reports HOT — same signal the waveform already uses.
- `seekUpdateRunnable` now calls `seekHandler.postDelayed(this, seekIntervalMs())`
  instead of a hardcoded `300`. Re-evaluated every tick, so a mid-playback
  thermal state change takes effect on the very next reschedule with no
  extra listener wiring.
