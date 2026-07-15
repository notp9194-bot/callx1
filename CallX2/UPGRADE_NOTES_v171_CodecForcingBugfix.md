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

---

# v172 — Auto-play on open (Profile → reel required a tap every time)

## Root cause

`ReelPlayerFragment.setUserVisibleHint(true)` (called by the host
Activity/ViewPager2 to say "this reel is now on screen, play it") can
arrive **before** `onCreateView()` has run. This happens reliably when
opening a reel from Profile via `SingleReelPlayerActivity`: ViewPager2's
`FragmentStateAdapter` attaches the fragment to the `FragmentManager`
first, and only creates its view on a later pass.

When that race happens, `ReelPlayerController.startPlayback()` finds
`playerView` still null and silently returns without doing anything. The
player ends up prepared (buffered, ready) but never told to actually
`play()`. The reel sits there paused until the user taps it once —
`togglePlayPause()` calls `startPlayback()` again, and this time the view
exists, so it works. Hence: every single reel opened from Profile needed
one manual tap.

## Fix

`ReelPlayerFragment.onCreateView()` now re-applies the pending visible
state (`applyVisibleState(true)`) right after the view is fully bound and
the player is prepared, if `isVisible` was already set to `true` by an
earlier (premature) `setUserVisibleHint(true)` call. This closes the race
without touching `SingleReelPlayerActivity`'s or `ReelsFragment`'s
existing page-change logic.
