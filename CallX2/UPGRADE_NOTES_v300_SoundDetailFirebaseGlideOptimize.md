# v300 — Sound Detail: Firebase read batching + Glide override fixes

## Firebase (new: core/.../cache/SoundDetailCache.java)

SoundDetailFragment previously fired 10+ independent
`addListenerForSingleValueEvent` calls on every screen open (sound node,
musicLibrary fallback, creator profile x2, follow status, saved status,
related sounds, pinned reel id) — and since `loadRelatedSounds()`'s onClick
replaces the fragment with a brand-new instance for the next sound, hopping
through a few related sounds re-ran the *entire* cascade from zero each
time, including re-resolving the same creator/follow state repeatedly.

`SoundDetailCache` (singleton, same short-TTL/in-flight-collapsing pattern
as the existing `MutualFollowersCache`) now owns:
- `getSoundData()` — sounds/ → musicLibrary/ fallback, 45s TTL
- `getCreatorProfile()` — reelUsers/ → users/ fallback, 5 min TTL
- `getSavedStatus()` / `setSavedStatus()` — 60s TTL, write-through on toggle
- `getFollowStatus()` / `setFollowStatus()` — 2 min TTL, write-through on toggle
- `getPinnedReelId()` / `setPinnedReelId()` — 3 min TTL, write-through on pin/unpin
- `getRelatedSounds()` — cached by genre (not soundId), 3 min TTL

`SoundDetailFragment` is rewired to call these instead of firing its own
listeners. Revisiting an already-resolved sound/creator/related-genre this
session now costs zero additional Firebase reads.

Deliberately left untouched: `fetchViewCountsForPage()`'s per-reel
`viewsCount` reads — those are one genuinely distinct, frequently-changing
value per grid item, not something a shared TTL cache helps with.

## Glide `.override()` — oversized bitmap decode fix

Found in `SoundDetailActivity.java` / `SoundDetailBottomSheet.java`
(not `SoundDetailFragment.java`, which already had overrides everywhere):

- `ReelThumbAdapter.onBindViewHolder` (reels-with-this-sound grid) — was
  loading the CDN-resized thumb with no `.override()`, so Glide decoded
  whatever raw size `CloudinaryUploader.deriveThumbUrl()` returned instead
  of the actual grid cell size. Now locked to `resolveCellWidthPx()` /
  `resolveCellHeightPx()` — the same numbers already used for the cell's
  `LayoutParams`.
- `RelatedAdapter.onBindViewHolder` (related sounds row, 80dp cover) — no
  `.override()` at all. Now locked to 80dp in px.
- `SoundDetailBottomSheet.populateData()` (72dp cover) — no `.override()`
  at all. Now locked to 72dp in px.
