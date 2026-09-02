# v313 — Home feed "Suggested for you" creators: mutual-followers row

## The feature

The inline "Suggested for you" creators strip mixed into the Reels home-tab
feed (`HomeFragment#bindSuggestedCreatorsRowContent` →
`SuggestedCreatorsTileAdapter`) now shows an Instagram-style "N mutual" line
— a 12dp mini avatar + "1 mutual" / "N mutual" text — directly below the
candidate's name in each chip, matching the reference screenshot's
"Suggested for you" card style.

## No duplicate mutual-followers logic

Resolved entirely through the already-existing shared
`com.callx.app.cache.MutualFollowersCache` (core/cache) — the exact same
cache the reel player's bio "Followed by X, Y and N others" row
(`ReelSocialController#loadReelMutualFollowers`) already uses:

- `MutualFollowersCache.getInstance().getMutualFollowers(myUid, candidateUid, callback)`
  returns `(uids, names, photos)` for that candidate.
- Since "my network" is cached ~3 min and per-target mutual results ~2 min
  session-wide, a candidate already resolved for the reel bio row (or by an
  earlier suggested-creators strip) costs zero extra Firebase reads here.
- No new Firebase queries, no new mutual-computation code written.

## Implementation

- `TileHolder` (inside `SuggestedCreatorsTileAdapter`) gained 3 new views —
  `llMutual` (row, hidden by default), `ivMutualAvatar` (12dp circle),
  `tvMutual` (small gray label) — built in `onCreateViewHolder()`.
- `onBindViewHolder()`: hides the row up front (so a recycled chip never
  briefly shows the previous candidate's mutual count), then calls
  `getMutualFollowers()`; on a real result (count > 0) sets the mini avatar
  (first mutual friend's photo) + "1 mutual"/"N mutual" text and reveals the
  row. Count 0 or self keeps it hidden — same "no row rather than fake
  empty state" as the reel bio row.
- `bindToken` (int, bumped on every bind and in `onViewRecycled`) guards
  the async callback the same way `ReelSocialController`'s
  `mutualFetchGeneration` guards its own — since chips are pulled from a
  **shared** RecyclerView pool across every "Suggested for you" strip in
  the feed, a slow lookup for one candidate must never land on a
  ViewHolder the pool has since reused for a different one.
- `onViewRecycled()` now also clears the mutual-avatar's in-flight Glide
  load, same reasoning as the existing main-avatar clear right above it.

No layout XML changes (chip is built entirely in code, matching the
existing pattern), no gradle changes (`feature-reels` already depends on
`:core`, where `MutualFollowersCache` lives).

Files touched: `HomeFragment.java`.
