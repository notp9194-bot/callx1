# v290 — Search Results Avatar Pipeline Parity

## Problem

Reels/Chat List/Group Members/Follow lists all carry the full deep avatar
pipeline (density-aware tiered `.override()` + WebP/AVIF CDN transform,
velocity-based prefetch depth, `DiskCacheStrategy.DATA` disk-only prefetch
gate, `?v=<avatarVersion>` cache-bust combined with the CDN's own
ETag/Last-Modified 304 handling, per-module `onTrimMemory` L2 cache).

`SearchResultAdapter` (`SearchActivity`'s live-as-you-type user search list)
was the one screen still missing it. It already had the shared-tier,
CDN-resized URL (`AvatarUrlBuilder.build()` — an earlier fix that stopped it
silently downloading full 800×800 photos), but nothing beyond that:
- No L2/L3 bitmap reuse — every re-scroll or re-search re-decoded from Glide's
  own resource cache at best, network at worst.
- No lifecycle-aware cancel — a request for a row scrolled off (or dropped by
  a fresh query's `notifyDataSetChanged()`) kept running and could land a
  bitmap into a reused VH showing a different user.
- No prefetch at all — every row's avatar loaded on-demand, on bind.
- No `avatarVersion` on `UserResult`, so no cache-bust param.

## Fix

New `SearchAvatarBinder` (`app/cache`) — same shape as `ChatAvatarBinder`
(feature-chat) / `GroupMemberAdapter`'s usage of it, since this is a plain
scrolled `RecyclerView` list exactly like those, not a single fixed-avatar
screen like `ProfileAvatarBinder`'s targets:

- `url()` — `AvatarUrlBuilder.buildResponsive()` at `AvatarSizeTier.forViewSizeDp(52)`
  (resolves to `MEDIUM`, same tier the adapter already bucketed to), version-tagged.
- `bind()` — new `SearchAvatarL2Cache` (module tag `"search"`, 64 entries,
  survives `TRIM_MEMORY_MODERATE`, own `registerComponentCallbacks` — trims
  independently of reels/chat/status/profile) fast path, then a real Glide
  decode that writes back into L2 + L3 disk on success.
- `cancel()` — called from `SearchResultAdapter#onViewRecycled` (new override).
- `prefetch()` — velocity-based depth, same thresholds as every other binder
  (fast fling ≥3.5 px/ms skips entirely, slow scroll ≤1.0 px/ms warms 4 rows
  ahead), using `DiskCacheStrategy.DATA` so a flung-past row never pays a
  speculative decode.

Also:
- `SearchResultAdapter.UserResult` gains an `avatarVersion` field (backward-
  compatible constructor overload defaults it to 0).
- `SearchActivity#snapToResult` now reads `avatarVersion` off the same
  `users/{uid}` Firebase snapshot every other screen already reads it from;
  the Room offline-fallback path (`searchInRoom`) passes `UserEntity`'s
  existing `avatarVersion` column through (no DB migration needed — the
  column already existed).
- `SearchActivity` gets a new `attachVelocityPrefetch()` scroll listener
  (same `elapsedRealtime()` px/ms technique as `FollowersListActivity`),
  wired to `SearchResultAdapter#prefetchAvatarsFrom` (new method).

ETag/Last-Modified conditional requests are not re-implemented per-screen
here either — every Glide request app-wide already gets that for free via
`CallxGlideModule` routing through `AvatarHttpCache`'s shared `OkHttpClient`.
