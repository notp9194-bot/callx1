# v171 — Codec-forcing bugfix (reels not playing + high data usage)

## Root cause

Advance #1 (AV1/HEVC codec forcing) added `applyPreferredCodec()` inside
`ReelPlayerController.pickQualityUrl()`, which appends a Cloudinary
`vc_h265`/`vc_av01` transform to the video URL right before it's handed to
ExoPlayer. Two other places in the codebase pick a quality URL for the
*same* reels but were never updated to match:

- `ReelVideoPreloader.pickQualityUrl()`
- `ReelPredictivePreloader.pickBestUrl()`

Both preloaders were still caching bytes under the **plain, untransformed**
URL. Since `CacheDataSource` keys its cache by URL, the actual player's
codec-transformed URL never matched anything in cache — every reel
downloaded twice: once wasted by the preloader, once again for real
playback. That's the "bahut data use ho raha hai" symptom.

Separately, if the Cloudinary account/plan can't actually produce the
requested `vc_av01`/`vc_h265` transform for a given asset (AV1 transcoding
in particular is not universally available), the transform request fails
server-side and ExoPlayer surfaces a playback error. The old error handler
just logged it and left the thumbnail frozen forever — no fallback. That's
the "reel play nahi ho rahi" symptom.

## Fix

- `CodecSupport.applyToUrl(url)` — new single source of truth for turning a
  chosen quality URL into the codec-transformed playback URL. All three
  call sites (`ReelPlayerController`, `ReelVideoPreloader`,
  `ReelPredictivePreloader`) now go through it, so preload cache keys
  always match what the player requests.
- `CodecSupport.disableForSession()` / `isDisabledForSession()` — if
  playback errors on a codec-transformed URL, the player now retries once
  with the plain URL and disables codec-forcing for the rest of the app
  session, so a broken transform doesn't strand every subsequent reel too.
- `ReelPlayerController.tryCodecFallback()` — wired into both
  `onPlayerError` (ExoPlayer) and the ABR callback's `onError`.

## Not changed

Advances #2 (predictive prefetch-on-open), #3 (BlurHash backfill worker),
#4 (adaptive grid thumb size), #5 (Fragment-scoped Glide), #6 (Room grid
cache) were already correctly implemented and wired in — no changes made
there.
