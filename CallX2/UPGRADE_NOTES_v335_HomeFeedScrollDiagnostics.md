# v335 — Home Feed Scroll Diagnostics

## What changed

- Added real Android `FrameMetrics` instrumentation to the Home feed while its
  RecyclerView is actively scrolling.
- Over-budget frames are grouped into short bursts instead of flooding the
  report with the same frame repeated hundreds of times.
- Each burst records the exact wall-clock time, frame duration vs display
  budget, scroll offset, visible adapter range, active reel/card, player
  state, thumbnail alpha, and the expensive render phase when Android reports
  one.
- Added **Home Feed Scroll Diagnostics** to `UserReelsActivity`'s 3-dot menu.
  It opens a dialog with the newest measured bursts and a plain-language
  root-cause signal. The dialog also has **Copy report** so the captured text
  can be pasted into a bug report or chat.

## Important behavior

This is diagnostic-only. It does not change playback, scrolling, buffering,
or preload behavior, and it does not show an interrupting dialog during a
scroll. Records remain in memory for the current app process so the user can
reproduce the issue in Home and inspect it from the profile menu afterward.
No build or automated test was run for this upgrade.