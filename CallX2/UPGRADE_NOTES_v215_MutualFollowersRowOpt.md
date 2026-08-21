# v215 — Bio Mutual-Followers Row: Ultra Optimization

## Problem
`ReelSocialController#loadReelMutualFollowers()` ran on **every single reel
bind** — i.e. every swipe, including swiping back to a reel already seen
this session (`startFirebaseListeners()` → `fetchLikerAvatars()` +
`loadReelMutualFollowers()` fires from `applyVisibleState(true)`). Each
call did, from scratch, serially chained:

1. Full download of **my** followers list
2. Full download of **my** following list (chained after #1, not parallel)
3. Full download of the reel **owner's** followers list
4. Full download of the reel **owner's** following list (chained after #3)
5. Up to 3 more single-value reads for the mutual-friend name+photo

Up to **7 Firebase reads per swipe**, several of them full-node downloads —
even though "my network" (#1+#2) is identical across every reel in the
session, and a given creator's mutuals (#3-#5) barely change between two of
their reels shown minutes apart.

## Fix

**New: `core/src/main/java/com/callx/app/cache/MutualFollowersCache.java`**
App-wide singleton, same pattern as the existing `StatusCacheManager`:
- `getMyNetwork(myUid, cb)` — my followers ∪ following, cached ~3 min.
  Fetched in **parallel** now, not chained.
- `getMutualFollowers(myUid, targetUid, cb)` — resolved mutual-uid list per
  target, cached ~2 min. Target's followers + following also fetched in
  **parallel**.
- Per-uid name/photo mini-cache (~5 min TTL) — a mutual friend who shows up
  on multiple different creators' rows is only fetched once.
- In-flight request de-duplication — two reels from the same owner
  becoming visible in quick succession collapse into one Firebase round
  trip instead of two.
- `invalidateMyNetwork(myUid)` — clears the cached network + any mutual
  results derived from it; called from `toggleFollow()` after a
  follow/unfollow so the next lookup isn't stale.

**`feature-reels/.../controllers/ReelSocialController.java`**
- `loadReelMutualFollowers()` rewritten to a single call into the cache;
  the old 4-level nested serial Firebase chain + separate
  `fetchReelMutualProfiles()` helper is gone.
- `toggleFollow()` now calls `MutualFollowersCache.invalidateMyNetwork()`
  after a successful follow/unfollow.
- `mutualFetchGeneration` staleness guard kept as-is — still needed since
  cache misses resolve asynchronously and a fast reel swap can otherwise
  let a stale response land on the wrong reel's row.

## Net effect
- First time you see a given creator's reel this session: same 4
  underlying reads as before, but ~2x faster (parallel, not chained).
- Every reel after that (same creator, or scrolling back to any
  already-seen reel): **zero Firebase reads** for the mutual row until the
  cache expires — often resolves synchronously, no visible delay at all.
- Repeat mutual friends across different creators' rows: name/photo
  fetched once, reused everywhere.

## Untouched
XML layouts, `.setText()`/Glide call sites in `showReelMutualFollowers()`,
and the separate liker-avatar-row mutual-follower filter
(`filterLikersForMutualFollowers`/`checkMutualFollowerStatus`) are
unchanged — this pass is scoped to the bio "Followed by…" row only.
