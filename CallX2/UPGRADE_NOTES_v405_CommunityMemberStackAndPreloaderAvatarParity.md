# v405 — Community member stack + preloader: avatar pipeline parity

## Problem
`CommunityMemberAvatarStackView` and `CommunityAvatarPreloader` (feature-chat)
were the last two avatar surfaces still bypassing the shared pipeline
(`AvatarBinderCore` / `CommunityAvatarBinder` / `AvatarSizeTier` /
`AvatarUrlBuilder`):

- **CommunityMemberAvatarStackView** cached bitmaps keyed by the *raw* photo
  URL and decoded at a hardcoded `override(96, 96)` regardless of the view's
  real ~26dp size. Result: the same user's photo shown here vs. as a
  `CommunityAvatarBinder`-bound post author / member row never shared an L2/L3
  cache entry (different key) and always over-decoded to 96px.
- **CommunityAvatarPreloader** (`attachAvatar`, used by
  `CommunityMembersFragment`, `CommunityFeedFragment`,
  `CommunityJoinRequestsActivity`) preloaded the raw URL straight into
  Glide's own internal cache. `CommunityAvatarBinder.bindBitmap()` later
  requests a completely different, tier-bucketed/CDN-responsive URL — so the
  fling-ahead preload never warmed anything the real bind could hit. The row
  still paid a fresh decode when it scrolled into view.

## Fix
- Added `CommunityAvatarBinder.TIER_STACK` (26dp → `TINY`) and a new
  `CommunityAvatarBinder.warmCache(ctx, rawUrl, tier, bitmap)` write-through
  helper.
- `CommunityMemberAvatarStackView.bind()` now builds its Glide/L2/L3 cache
  key via `CommunityAvatarBinder.url(ctx, rawUrl, TIER_STACK)`, decodes at
  `AvatarUrlBuilder.tierPx(TIER_STACK)` (not a hardcoded 96px), uses
  `CommunityAvatarBinder.AVATAR_FORMAT` + `DiskCacheStrategy.RESOURCE`, and
  records through `AvatarCacheAnalytics` on both the L2-hit and Glide-decode
  paths. The existing L3-async-race-vs-Glide and `mBindGeneration`
  recycle-guard logic is unchanged.
- `CommunityAvatarPreloader.attachAvatar()`'s circle-crop path now derives an
  `AvatarSizeTier` from the caller's `sizeDp` (same bucketing
  `CommunityAvatarBinder` uses), builds the identical
  `CommunityAvatarBinder.url()` for the preload request, and on a successful
  decode calls `CommunityAvatarBinder.warmCache()` so the bitmap lands in
  `ChatAvatarL2Cache`/L3 under the exact key `bindBitmap()` will look up next.
  `attachCover()` (Events full-width covers) is untouched — covers aren't
  square avatars and have no tier/bind counterpart to share a key with.

## Files changed
- `feature-chat/.../cache/CommunityAvatarBinder.java` — `TIER_STACK`, `warmCache()`.
- `feature-chat/.../community/CommunityMemberAvatarStackView.java` — tiered URL/cache key, real decode size, analytics.
- `feature-chat/.../community/canvas/CommunityAvatarPreloader.java` — avatar path rebuilt to share cache with the real bind; cover path split out unchanged (`attachCoverInternal`).

## Not changed
- `attachCover()` / Events cover-image preload — intentionally left on flat Glide caching (no avatar tier concept applies).
- No new Room/Firebase migrations; no XML/layout changes.

## Remaining avatar-pipeline gaps (for a future pass)
- `GroupAvatarBinder` / `CommunityAvatarBinder.bindIcon()`/`bindBitmap()` still
  hand-roll their own L2-check → Glide → L2/L3-write logic instead of
  delegating to `AvatarBinderCore.bind()` the way `ChatAvatarBinder` does.
- `AvatarBatchPrefetcher`, `AvatarColdStartQueue`, `AvatarVersionSyncManager`,
  `AvatarHttpCache` are wired up in feature-reels only — chat/community
  avatars get none of that cold-start/version-sync benefit yet.
