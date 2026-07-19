# v178 — Home Tab Predictive Preload (same feature as Reels tab)

Home tab had no preloading at all — a card's video only started
downloading the moment it became the "most visible" card and got
attached to `feedPlayer`. Reels tab, by contrast, has always preloaded
the next several reels' video + thumbnails ahead of time. Ported that
exact feature into `HomeFragment`.

## What's new

Three fields added, same classes Reels tab already uses:

```java
ReelVideoPreloader       videoPreloader;
ReelThumbnailPreloader   thumbPreloader;
ReelPredictivePreloader  predictivePreloader;
```

Initialized in `onCreateView` (same as `ReelsFragment`).

**Trigger point:** `attachPlayerToCard(index)` — the Home-feed equivalent
of Reels' `onPageSelected` (it's the one place that fires every time the
"currently playing" card changes, whether from scroll or from a fresh
feed render). On every call:

```java
videoPreloader.preloadFrom(currentFeedPosts, index);       // next videos
thumbPreloader.preloadFrom(currentFeedPosts, index);        // next thumbnails
predictivePreloader.preloadSmartFrom(currentFeedPosts, index); // learned/affinity-based
```

`currentFeedPosts` is the exact post list backing `feedCards`
(index-aligned), captured in `renderFeedPostsWithState`.

**Cleanup**, mirroring `ReelsFragment`:
- `onPause()` → `videoPreloader.cancelAll()` (don't burn data preloading
  cards the user isn't currently looking at)
- `onDestroyView()` → `videoPreloader.shutdown()`, null out all three

## Why this compounds with v177

These preloaders write into the exact same
`UnifiedVideoCacheManager.Module.REELS` cache that `buildCachedMediaSource`
(v177) reads from for actual playback. So now:

- Scroll down in Home → next 2-3 cards' video is already mid-download in
  the background by the time you reach them → instant play, no spinner.
- Anything preloaded here is *also* available instantly if you then open
  that same reel from the Reels tab, and vice versa — one cache, fed
  from both tabs, read by both tabs.
