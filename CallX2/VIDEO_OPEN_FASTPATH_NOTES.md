# v903 → Video open fast path (chat bubble → MediaViewerActivity → close)

Filter logcat by tag `VideoOpenPerf`:
`tap→firstFrame=NNNms | tap→onCreate=NNNms | mode=single|gallery | primed=true|false`

1. Tap-time prepare — `core/.../VideoOpenPrimer` (new). `MessagePagingAdapter.openChatMediaViewer` (video only) primes a pooled
   player (local file / MediaCache only, never network) and passes a token; viewer + `GalleryPagerAdapter` claim it. Unclaimed → back to pool (8s expiry / onDestroy).
2. No file I/O on UI thread — `MediaViewerActivity.playVideo` uses the chat's LOCAL_AVAIL_CACHE verdict (Intent extra `localAvail`) or a background thread; gallery start page skips `resolveSource`; `LocalMediaAvailability` verdicts memoised per viewer session.
3. Thumbnail shutter — `app/.../VideoShutter` (new): bubble thumbnail (Glide cache only) drawn as PlayerView background until `onRenderedFirstFrame`. Video call sites now pass the real `thumbnailUrl`.
4. `ExoPlayerPool.MAX_POOL_SIZE` 2 → 3 (prev + current + next video pages stay paused+prepared).
5. `core/.../LocalVideoSource` (new): ProgressiveMediaSource + FileDataSource (DefaultDataSource for content://), MP4-only extractors for mp4/m4v/mov/3gp, default extractors otherwise.
core/build.gradle: explicit media3-extractor 1.2.1 dep (was transitive).
Not done (needs media3 upgrade): DefaultPreloadManager, setPlaybackLooper.

## v904 — round 2
1. Bubble's decoded bitmap handed to the viewer (`VideoOpenPrimer.offerShutter/takeShutter`, copy; legacy + canvas bubbles) → shutter on frame 1, no decode.
2. Close: pause immediately in onDestroy (all finish paths); stop/clearMediaItems/pool hand-back run on main-idle (1.5s fallback).
3. Presence (Firebase setValue/onDisconnect) delayed 450ms after play; stop is immediate.
4. Neighbour video pages attach a player but `prepare()` only after the ACTIVE page's first frame (or swipe / error / 1.5s).
5. A/B flag `VideoOpenSettings` (default OFF = old behaviour): viewer ⋮ menu → "Video open GPU layer" drops LAYER_TYPE_HARDWARE for video dock animations.
