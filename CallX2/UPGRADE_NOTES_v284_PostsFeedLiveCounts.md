# v284 — Post Feed: Live Likes/Comments/Reposts

`PostsFeedActivity` (Instagram-style photo-post feed opened from a
profile's Posts tab) loaded likes/comments/reposts once via
`addListenerForSingleValueEvent` in `loadPosts()` and never again — if
someone else liked/commented/reposted while the screen stayed open, the
numbers stayed stale until the user backed out and reopened it.

## Fix
Added a real-time `addValueEventListener` per row, scoped to exactly
what's on screen:
- `onBindViewHolder` calls `attachCountListener(r, h)`, which listens on
  `reels/{reelId}` and pushes fresh `likesCount`/`commentsCount`/
  `repostCount` straight into the bound TextViews whenever they change.
- `onViewRecycled` calls `detachCountListener(h)` so a row that's
  scrolled off doesn't keep a listener running.
- `onDestroy` sweeps `activeCountListeners` (a tracked map of every
  currently-attached listener) as a safety net for any row that never
  got an `onViewRecycled` call.
- Each callback checks `r.reelId.equals(h.boundReelId)` before touching
  views, since the Holder may have already been recycled onto a
  different row by the time an async update lands.

The initial `loadPosts()` one-time fetch is unchanged — it's still the
right tool for "load these N reelIds once on open"; this only adds
what happens *after* that.

## File touched
`feature-reels/src/main/java/com/callx/app/profile/PostsFeedActivity.java`
