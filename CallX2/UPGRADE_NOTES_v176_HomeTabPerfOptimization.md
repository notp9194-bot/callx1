# v176 — Home Tab Advanced Performance Optimization

Two real bottlenecks found and fixed in `HomeFragment` (Reels → Home tab).

## 1. N+1 Firebase reads per feed render (network) — the big one

**Before:** in "For You" mode, `loadFeed()` never fetched the current
user's follow-set at all. Instead, **every single card** independently
fired its own network round trip:

```java
FirebaseUtils.getReelFollowsRef(myUid).child(ownerUidRef)
    .addListenerForSingleValueEvent(...)   // ← one per card
```

With up to 10 cards rendered per feed load, that's **10 separate Firebase
round trips** just to decide whether to show a "Follow" button — on every
tab switch (Following ↔ For You) and every pull-to-refresh.

**After:** the follow-set is fetched **once** (`getReelFollowsRef(myUid)`,
no `.child()`) before rendering, exactly like `likedIds`/`savedIds` already
were. It's threaded through `renderFeedPosts` → `renderFeedPostsWithState`
→ `addFeedPostCard` as a `Set<String> followedUids`, and each card now
does a plain in-memory `followedUids.contains(ownerUidRef)` — **zero**
network calls per card. Following-mode already had this set available
(it needs it to filter posts) — it's now reused instead of duplicated.

**Impact:** 10 Firebase round trips → 1, on every single Home feed
load/refresh/tab-switch. This is the dominant cost on anything but a
fast connection.

## 2. Main-thread jank from rendering all cards in one frame

**Before:** `renderFeedPostsWithState` inflated all (up to 10) feed cards
and dispatched all their Glide avatar/thumbnail requests inside a single
`runOnUiThread` block — real CPU work for 10 full card inflates +
image-load dispatches all competing for the same ~16ms frame budget.
Visible as a stutter whenever Home opens or you switch Following/For You.

**After:** the first 3 cards (≈ one screen) still render immediately so
content appears instantly; cards 4–10 are each added one-per-frame via
`postDelayed(..., 16ms increments)`, spreading the inflate + Glide-dispatch
cost across multiple frames instead of blocking one. No visual or
functional change — same cards, same order, same auto-play — just spread
out so the frame that opens Home doesn't have to do 3x the normal work.

## Not changed (checked, already fine)

- Video playback already uses a **single shared `ExoPlayer`** across all
  feed cards (`feedPlayer`), not one per card — good design already, no
  duplicate players to fix.
- Glide calls already use `.override()`/`.centerCrop()` for
  avatar/thumbnail downsampling.
