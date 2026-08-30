# v289 — Chat List Avatar Pipeline Parity

## Problem

The 5 deep avatar-pipeline optimizations (density-aware `.override()` + WebP/AVIF
CDN transform, velocity-based prefetch depth, `DiskCacheStrategy.DATA` disk-only
gate, ETag/version combine, per-module `onTrimMemory` L2 cache) only ever landed
in the **Reels** module — `AvatarPrefetcher`, `ReelUiController`,
`ReelsAvatarL2Cache`, and (via `FollowAvatarBinder`) the Follow/Following lists.

The **Chat List** (`ChatListAdapter` / `ChatsFragment`) never got the upgrade.
It still had:
- A flat, un-tiered `50dp * density` avatar size — no `AvatarSizeTier` bucket,
  so it shared no CDN cache entries with any other avatar screen in the app.
- A raw `Glide.load(thumbUrl)` — no `AvatarUrlBuilder.buildResponsive()`, so no
  density-bucketed `dpr_` param and no WebP/AVIF `f_` format param.
- No `avatarVersion` on `User`/`ChatEntity` at all, so no `?v=` cache-busting —
  a stale cached `thumbUrl` string could mean a stale bitmap indefinitely.
- No L2/L3 reuse (`ChatAvatarL2Cache` existed but only
  `CommunityMemberAvatarStackView` used it — the actual chat list rows never did).
- A flat "always `AVATAR_PRELOAD_AHEAD` (12) rows ahead" scroll prefetch,
  regardless of scroll speed.

## Fix

New `ChatAvatarBinder` (feature-chat/cache) — mirrors `FollowAvatarBinder`
(feature-reels) exactly, so avatar behavior is consistent everywhere:

- `url()` — `AvatarUrlBuilder.buildResponsive()` with `AvatarSizeTier.forViewSizeDp(50)`
  (resolves to `MEDIUM`), version-tagged from the new `avatarVersion` field.
- `bind()` — `ChatAvatarL2Cache` fast path, then a real Glide decode that writes
  back into L2 + L3 disk on success.
- `cancel()` — called from `ChatListAdapter#onViewRecycled`.
- `prefetch()` — velocity-based depth (same thresholds as `AvatarPrefetcher`/
  `FollowAvatarBinder`: fast fling skips, slow scroll warms 4 rows ahead), using
  `DiskCacheStrategy.DATA` so a flung-past row never pays a speculative decode.

Also:
- `User`/`UserEntity`/`ChatEntity` gain `avatarVersion` (`MIGRATION_51_52`,
  DB v51→v52), wired through `ChatsFragment#buildChatEntity`/`entityToUser`.
- `ChatsFragment`'s scroll listener now measures velocity (same
  `elapsedRealtime()` px/ms technique as `FollowersListActivity`) and calls
  `ChatAvatarBinder.prefetch()` instead of the old flat-range
  `preloadAvatarsInRange()`.
- `preloadAvatarsForPage()` (first-paint page warm) now resolves the SAME
  `ChatAvatarBinder.url()` cache key the real bind uses.
- `ChatSnapshotCache` persists `avatarVersion` too, so the instant cold-start
  snapshot doesn't warm a mismatched cache key either.

ETag/Last-Modified conditional requests were not re-implemented per-screen —
same as reels, every Glide request app-wide already gets that for free via
`CallxGlideModule` routing through `AvatarHttpCache`'s shared `OkHttpClient`.
