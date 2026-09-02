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
- Stabilized the playback handoff path after diagnostics showed 61–183 ms
  stalls: player selection now happens only after RecyclerView becomes idle,
  active playback pauses without a Surface detach during drag/fling, and an
  off-screen recycled card cannot keep its player running.
- Standby-player callbacks are ignored until that player is promoted, so a
  neighbour's first decoded frame cannot reveal the active card's thumbnail.
- Removed the dynamic full-feed hardware-layer toggle; RecyclerView and
  PlayerView retain their normal hardware-accelerated rendering instead of
  rebuilding a parent GPU texture during scrolling.
- During drag/fling, the active media is paused in place and its opaque
  thumbnail remains above the Surface. Thumbnail reveal is deferred until the
  scroll is idle, preventing a mid-scroll first-frame crossfade.

## Important behavior

This is diagnostic-only. It does not change playback, scrolling, buffering,
or preload behavior, and it does not show an interrupting dialog during a
scroll. Records remain in memory for the current app process so the user can
reproduce the issue in Home and inspect it from the profile menu afterward.
No build or automated test was run for this upgrade.