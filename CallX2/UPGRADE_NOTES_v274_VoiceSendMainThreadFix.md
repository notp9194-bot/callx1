# v274 — Voice message SEND: main-thread freeze fix

## Problem (real bottleneck found)
`ChatMediaController#finishAndSend()` — called when user taps Send on a
recorded voice note — was doing ALL of this **synchronously on the main
thread**:
1. `recorder.stopToFile()` → `MediaRecorder#stop()` (finalizes MP4 header,
   can block noticeably, worse on some OEM encoders) + segment merge if
   the user paused/resumed more than once.
2. `VoiceTrimmer.trim()` → full `MediaExtractor → MediaMuxer` stream-copy
   of the recorded clip if the user dragged the trim handles.

Result: tapping Send visibly froze the chat screen for the duration of
stop+trim — worse the longer the voice note.

## Fix
- `resetRecordingUi()` now runs **immediately** on tap (pure View work,
  no dependency on the recorder) — the mic bar snaps back instantly.
- `recorder.stopToFile()` + `VoiceTrimmer.trim()` now run on
  `AppBgExecutor` (the same shared background pool already used
  elsewhere in this file for media work — no new thread pool).
- Only hops back to the main thread once the final `Uri` is ready, to
  call `uploadAndSend()`.

## Before / After
- Before: Send tap → UI frozen for stop+trim duration → bar resets →
  upload starts.
- After: Send tap → bar resets instantly → stop+trim happen in
  background → upload starts the moment they finish. Zero main-thread
  blocking, same final behavior (trim-fail still falls back to
  untrimmed send).

## Scope
Targeted fix, no behavior change to trimming logic itself — only where
it executes.
