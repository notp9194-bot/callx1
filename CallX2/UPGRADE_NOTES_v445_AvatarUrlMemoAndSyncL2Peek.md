# v445 — Avatar rebind cost: resolved-URL memo + lambda-free synchronous L2 peek

Scope: every 24dp inline avatar in group chat — run-tail sender avatar (#2), seen-by strip (#4), reactor / poll-voter strips (#6).

## Remaining cost after v442–v444
On each (re)bind of a recycled view — strips' bitmaps are cleared on recycle, so they must be re-requested — every avatar slot still paid:
1. `AvatarUrlBuilder.buildResponsive()` → `indexOf`, ~8 String concats, 2 `substring`s, network-bucket + density lookups — only to derive the L2 cache KEY.
2. A capturing lambda + `BitmapCallback` allocation, then the L2 hit invoked it synchronously anyway.

## Fix (ChatAvatarBinder)
- `resolveInlineUrl(ctx, photo)`: memo `photo → resolved URL`, used by `bindBitmap()` when `tier == TIER_INLINE && version == 0`
  (all strips + sender avatar). Each entry carries a signature = (AvatarNetworkQuality bucket, display density); a stale entry is just
  recomputed on its next lookup — so a network-bucket flip (`q_auto` → `q_auto:eco/low`) or density change can never serve a stale URL,
  with no clear-races and no listeners. Low-RAM flag / image format are process-constant. Bounded (512, clear-all on overflow; correctness never depends on it).
- `peekInline(ctx, photo)`: synchronous L2 lookup (same L2 cache + same analytics record as `bindBitmap`'s own hit branch). Returns the bitmap or null.

## Adapter
Sender avatar, seen-by strip and `applyMiniStrip` (reactions / poll voters) try `peekInline()` first and set the bitmap inline;
only an L2 miss falls back to `bindBitmap()` (lambda + Glide). Result: an L2-hit rebind = 1 memo lookup + 1 L2 lookup, zero allocation.

## Deliberately NOT done
- Holding strip bitmaps on the view across recycle (low hit rate — RecyclerView hands views to other messages; pins memory in the pool).
- A separate L1 cache (L2 hit is already synchronous; another layer = more invalidation, no gain).

## Files
feature-chat/.../cache/ChatAvatarBinder.java, feature-chat/.../conversation/MessagePagingAdapter.java
Not compile-tested (no Android SDK/Gradle here) — brace balance checked only; run a normal build.
