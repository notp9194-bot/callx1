# v288 — Home Feed: Bitmap Downsample+Reuse & Surface Pre-warm

Two Instagram/Reels-style perf advances added to the **Home tab feed**
(`feature-reels`'s `HomeFragment` — the inline-autoplay scrolling feed, not
the Reels swipe tab), matching the pattern already established by
`ExoPlayerPool` (player reuse), `HomeFeedCardPool` (idle inflate), and
`GpuDecodeWarmup` (codec/EGL warm-up).

## 1. Bitmap downsample + reuse (`inBitmap`)

**Problem**: `ReelFirstFrameCache.getCached()` — called on essentially every
`attachPlayerToCard()` to fetch a reel's pre-decoded first frame for the
thumbnail crossfade — fell back to a bare `BitmapFactory.decodeFile()` on
every mem-cache miss (LRU cap is only 12 entries). Each miss allocated a
fresh few-hundred-KB pixel buffer that got thrown away the moment the card
scrolled past — GC churn on every fast fling through the feed.

**Fix**: new `ReusableThumbBitmapPool` (package `com.callx.app.cache`) holds
up to 3 idle mutable `ARGB_8888` buffers (same "prev/current/next" sizing
rationale as `ExoPlayerPool.POOL_SIZE`). `ReelFirstFrameCache`'s disk-decode
path now probes bounds first, tries `BitmapFactory.Options.inBitmap` against
a same-shaped pooled buffer, and only falls back to a plain allocation on a
size/shape miss.

**Safety**: bitmaps only re-enter the pool from one call site —
`HomeFragment.FeedAdapter.onViewRecycled()`, once a card's ImageView is
provably done displaying that exact object (rebound to a different post).
`ReelFirstFrameCache.releaseIfEvicted()` double-checks:
1. the bitmap was actually produced by this cache (an identity-tracked
   `ownedBitmaps` set) — never touches a Glide-owned bitmap, and
2. it's no longer the live `memCache` entry for that key — so another card
   showing the same reel can't have the object pulled out from under it.

## 2. Surface pre-warm

**Problem**: `GpuDecodeWarmup` (existing) explicitly avoids touching any
real `Surface`/`SurfaceView` — it only warms MediaCodec + EGL. That leaves
the first `PlayerView`'s `SurfaceView` (item_home_feed_post.xml uses
`surface_type="surface_view"`) to pay the one-time per-process
SurfaceView-class-load + first SurfaceFlinger-binder-handshake cost inline,
on the very first card that plays — a source of unpredictable crossfade
timing in `revealCardThumbnailAfterFirstFrame()` on a cold start.

**Fix**: new `SurfacePrewarmer` (package `com.callx.app.player`) attaches a
1x1px, fully-visible throwaway `SurfaceView` to the host Activity's window
decor view, once per process, the moment `HomeFragment.onCreateView()` (or
`ReelsFragment`'s, if wired there too) runs — well off the first real
card's critical path. It's never torn down, mirroring `GpuDecodeWarmup`'s
lifetime. This does not — and cannot — pre-create the specific Surface a
future row will render into (every `PlayerView` owns its own instance); it
only pays the shared one-time platform setup cost early, so the real first
card's own `surfaceCreated()` fires sooner and more consistently.

## Wiring

```
HomeFragment.onCreateView()
  → GpuDecodeWarmup.warmUpOnce(context)      // existing — codec/EGL
  → SurfacePrewarmer.warmUpOnce(activity)    // new — Surface pipeline

HomeFragment.FeedAdapter.onViewRecycled()
  → ReelFirstFrameCache.releaseIfEvicted(reelId, bitmap)  // new
```

No changes to `ExoPlayerPool`, `HomeFeedCardPool`, or the crossfade/reveal
timing logic itself — both advances are additive and fail safe (a rejected
`inBitmap` reuse or a failed prewarm attempt just falls back to the
pre-v288 behavior).
