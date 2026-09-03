# v339 — Home Feed Diagnostics + Instagram-Level Scroll Playback

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
  stalls: edge-only cards do not trigger a handoff, the incumbent keeps
  playing while visible, and an off-screen recycled card cannot keep its
  player running.
- Standby-player callbacks are ignored until that player is promoted, so a
  neighbour's first decoded frame cannot reveal the active card's thumbnail.
- Removed the dynamic full-feed hardware-layer toggle; RecyclerView and
  PlayerView retain their normal hardware-accelerated rendering instead of
  rebuilding a parent GPU texture during scrolling.
- During drag/fling, the active media keeps its Surface attached while it
  remains visible. Any reel that becomes the dominant visible card can take
  over immediately—even during a fast fling—once it crosses the 50% floor;
  edge-only cards do not trigger decoder churn.
- Replaced the Home feed's three-decoder active/next/previous promotion path
  with one active ExoPlayer. Neighbour videos are still cache-prefetched, but
  they no longer enter READY/BUFFERING or compete for decoder/network time
  while the visible card is settling.
- If RecyclerView keeps the old holder cached just outside the viewport, Home
  now detaches that player once at the exit boundary instead of leaving a
  hidden active card alive through the whole fling.
- Replaced the thumbnail alpha animation with a single reveal after the real
  Surface frame arrives. This removes the measured 47–73 ms animation stalls
  while preserving the opaque-cover protection against black frames.
- Removed the blanket pause at the start of every drag/fling. The incumbent
  video now keeps playing while visible, and a newly dominant card can take
  over immediately during both gentle scrolling and fast flings. An actually
  off-screen incumbent is detached by the viewport guard.

## Important behavior

The diagnostics recorder is non-interrupting and keeps records in memory for
the current app process so the user can reproduce the issue in Home and
inspect it from the profile menu afterward. The scroll-stability changes above
intentionally alter playback handoff, thumbnail reveal, and preloading
coordination to remove the measured stalls. No build or automated test was run
for this upgrade.