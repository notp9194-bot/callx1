# v55 — Instagram-level Story Ring: Live Update + Seen-State Fix

## What was broken

**1. Home tab's top story row never refreshed after viewing a story.**
`HomeFragment` built the row once in `onCreateView()` (and again only on
pull-to-refresh). `onResume()` only handled the video player — it never
reloaded the story row. So: viewer taps a story → watches it → presses
back to Home → the ring is still the old gradient. It only updated if the
user manually pulled to refresh or force-killed/reopened the app.

**2. Gradient ring stuck forever on `reel_story`-type stories.**
```java
if (entry.hasUnseen || entry.hasReelStory) { /* show gradient */ }
```
Any story tagged `type == "reel_story"` forced the gradient ring
regardless of whether it had actually been seen. This is the direct cause
of "dekhne ke baad bhi gradient ring rehta hai" — Instagram's ring is
driven purely by seen/unseen state, never by story type.

**3. Home's story-seen logic was its own separate, one-time path.**
Every other screen with a story ring (`ChatListAdapter`,
`CallHistoryAdapter`, `ReelCommentsAdapter`, `StatusFragment` /
`StatusListAdapter`) already shares ONE live, real-time cache —
`StatusCacheManager` (core module) — that keeps a Firebase
`ValueEventListener` on both the status data and the `statusSeen/{myUid}`
node, and pushes `onStatusDataUpdated()` to every registered observer the
instant either changes. `HomeFragment`'s story row was the one place in
the app that didn't participate in this — hence "properly update" being
inconsistent depending on which screen you were on.

## Fix

**`feature-reels/.../feed/HomeFragment.java`**
- Removed the `hasReelStory` gradient-forcing bug — ring state is now
  purely `entry.hasUnseen` for every story type, matching Instagram.
- New `refreshStoryRow()` helper (clears the row, then rebuilds) — added
  so repeated refreshes never duplicate avatars.
- `onResume()`: now calls `refreshStoryRow()` on every resume after the
  first (guarded by `isFirstResume` so it doesn't redundantly redo the
  work `onCreateView()`'s initial `loadAllSections()` already did) — so
  coming back from viewing a story anywhere always shows the current
  seen/unseen state immediately.
- `onResume()` / `onPause()`: registers/unregisters a
  `StatusCacheManager.StatusDataObserver` (`storyRingObserver`) — the
  same live-cache pattern already used by `StatusFragment`,
  `ChatListAdapter`, `CallHistoryAdapter`. This means the ring updates
  **instantly** the moment any screen marks a story seen while Home is
  still on-screen (e.g. split-screen, or a background tab) — not just on
  resume.

## Not touched (already correct / already Instagram-level)
- `feature-chat/.../ChatListAdapter.java` + `ChatListStoryRingView` —
  already reads live `StatusCacheManager.hasUnseen(uid)` per bind.
- `feature-status/.../StatusFragment.java` + `StatusListAdapter.java` —
  already registers/unregisters a `StatusCacheManager` observer in
  `onStart()`/`onStop()` and rebuilds from the live cache.
- `feature-calls/.../CallHistoryAdapter.java`,
  `feature-reels/.../comments/ReelCommentsAdapter.java` — same, already
  using the shared cache correctly.
- `StatusCacheManager` itself (core module) — this is the existing
  "single source of truth" the whole app should use; no changes needed,
  `HomeFragment` just now participates in it properly.

## Files changed
- `feature-reels/src/main/java/com/callx/app/feed/HomeFragment.java`
