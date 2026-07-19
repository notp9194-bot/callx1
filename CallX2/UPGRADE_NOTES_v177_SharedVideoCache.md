# v177 — Home Tab ↔ Reels Tab Shared Video Cache

Checked what you asked: **was it actually sharing cache? No — video was not.**

## What was already shared (no fix needed)

**Image cache (Glide):** the whole app uses one `CallxGlideModule` with a
single app-wide disk cache. Every `Glide.with(...)` call — Home tab
avatars/thumbnails and Reels tab thumbnails alike — already hits the
same on-disk cache keyed by URL. A thumbnail cached by Reels was already
being reused instantly in Home, and vice versa. ✅ nothing to change.

## What was NOT shared (the actual bug)

**Video cache (ExoPlayer):** Reels tab video playback
(`AdaptiveStreamingManager.buildSource()`) builds its `MediaSource`
through `UnifiedVideoCacheManager.getFactory(Module.REELS)` — a
`CacheDataSource.Factory` backed by a shared on-disk `SimpleCache`.

Home tab's feed player did **not** do this. It called:

```java
feedPlayer.setMediaItem(MediaItem.fromUri(card.videoUrl));  // ❌
feedPlayer.prepare();
```

`setMediaItem(...)` builds ExoPlayer's *default* MediaSource — a plain
network `HttpDataSource` with **no cache wrapping at all**. Result: any
reel already fully cached by the Reels tab got **re-downloaded from
scratch** the moment it played in the Home feed (and vice versa — even
scrolling past the same video twice in Home re-downloaded it every time).

## The fix

Added `buildCachedMediaSource(url)` in `HomeFragment`, which builds a
`ProgressiveMediaSource` through the exact same
`UnifiedVideoCacheManager.getFactory(Module.REELS)` cache factory the
Reels tab uses:

```java
feedPlayer.setMediaSource(buildCachedMediaSource(card.videoUrl));  // ✅
feedPlayer.prepare();
```

Cache key = video URL, so:
- A reel already watched in Reels → **instant, cache-hit playback** in
  Home, no re-download.
- A reel already watched in Home → same, no re-download in Reels.
- Re-scrolling the same Home feed card also now hits cache instead of
  re-fetching.

Same cache, same eviction policy (`UnifiedVideoCacheManager`'s existing
500MB REELS quota), no new storage used — just correctly reused now.
