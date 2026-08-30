# v291 — Reel Comments Avatar Pipeline Parity

## Problem

`ReelCommentsAdapter` (the reel comment sheet's RecyclerView) already had the
tier-aware, density-bucketed, WebP/AVIF, versioned CDN URL — its
`bindAvatar()`/`loadAvatarInto()` routed through
`AvatarUrlBuilder.buildResponsive()` and bucketed to the shared `SMALL` tier
— plus its own bounded `LruCache<uid, photoUrl>` to avoid repeated Firebase
reads for the "`ownerPhoto` missing, fall back to `reels/users/{uid}`" case.

What it never had was anything below the URL:
- No L2/L3 **bitmap** reuse — `avatarCache` only cached the URL *string*, not
  a decoded bitmap; every scroll-back to a comment re-decoded from Glide's
  own cache at best, network at worst.
- No thumbnail blur-up — a bare `ic_person` placeholder until the full 36dp
  decode landed, instead of an instant tiny blurred frame.
- No prefetch — every row's avatar loaded strictly on bind, nothing warmed
  ahead of a scroll.
- No isVisible gate — `onViewRecycled()` didn't exist on the adapter at all,
  so a request for a row that scrolled off (or got recycled to a different
  comment by a live Firebase child-event re-diff) kept running with nothing
  to cancel it.
- No `avatarVersion` on `ReelComment` at all, so no `?v=` cache-bust.

## Fix

New `ReelCommentAvatarBinder` (`feature-reels/cache`) — same shape as
`FollowAvatarBinder`/`ChatAvatarBinder`, and (like `FollowAvatarBinder`)
deliberately **reuses `ReelsAvatarL2Cache`** rather than standing up a fourth
parallel cache, since comments live in the same `feature-reels` module as
the reel player and follow lists and already get that cache's per-module
`TRIM_MEMORY_MODERATE` survival for free:

- `url()` — `AvatarUrlBuilder.buildResponsive()` at the existing `SMALL` tier,
  version-tagged.
- `bind()` — `ReelsAvatarL2Cache` fast path → L3 disk race (same
  tag-guarded "stale hit dropped if Glide already won" pattern
  `ReelUiController#loadOwnerAvatarNow` uses) → a real Glide decode chained
  with a `TINY`-tier `.thumbnail()` blur-up, writing back into L2 + L3 on
  success. Preserves the original `loadAvatarInto()`'s "skip if this exact
  URL is already bound" short-circuit.
- `cancel()` — new `ReelCommentsAdapter#onViewRecycled` override calls this;
  stops an in-flight request for a recycled row and clears the URL tag so a
  late L3 callback can never paint into a row now showing a different
  comment — the per-row isVisible gate.
- `prefetch()` — velocity-based depth (same thresholds as `AvatarPrefetcher`/
  `FollowAvatarBinder`/`ChatAvatarBinder`/`SearchAvatarBinder`), using
  `DiskCacheStrategy.DATA` so a flung-past comment never pays a speculative
  decode.

Also:
- `ReelComment` gains an `avatarVersion` field (Firebase POJO mapping — no
  extra wiring needed to read it; defaults to `0`, which is already
  `AvatarUrlBuilder`'s documented "omit the `?v=` param" value, so old
  comments written before this field existed keep working unchanged).
- `ReelCommentsAdapter` gains a companion `avatarVersionCache` (uid →
  version) alongside the existing `avatarCache` (uid → url), filled from the
  same `reels/users/{uid}` fallback snapshot, for the one case `ReelComment`
  itself can't cover (owner photo missing at comment-post time).
- `ReelCommentFragment`'s `rvComments` gets a second, separate
  `OnScrollListener` (same `elapsedRealtime()` px/ms technique as
  `FollowersListActivity`) wired to `ReelCommentsAdapter#prefetchAvatarsFrom`
  — kept independent from the existing pagination scroll listener so a
  change to either can never break the other.

ETag/Last-Modified conditional requests are not re-implemented here either —
every Glide request app-wide already gets that for free via
`CallxGlideModule` routing through `AvatarHttpCache`'s shared `OkHttpClient`.
