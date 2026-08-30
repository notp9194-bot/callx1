# v301 — SoundDetail: viewsCount denormalization (kills per-page read fan-out)

## The gap

`SoundDetailFragment.loadMoreReelsForSound()` reads a page of
`REELS_PAGE_SIZE` (12) reels from `sounds/{soundId}/reels`, then called
`fetchViewCountsForPage()`, which fired **one separate
`addListenerForSingleValueEvent` per reel** against
`reels/{reelId}/viewsCount` just to learn a number for the grid.

Cost: every single pagination page (initial load + every scroll-triggered
page) = 12 extra one-shot reads, forever, on every open of every sound.

## The fix

`viewsCount` is now denormalized directly onto
`sounds/{soundId}/reels/{reelId}/viewsCount` — the exact node
`loadMoreReelsForSound()` already reads for thumbnail/video/owner. It reads
`viewsCount` straight off that same snapshot now; `fetchViewCountsForPage()`
is gone.

Kept in sync at every write site that touches a reel's real view count:

- **`ReelUploadActivity#registerOrLinkSound`** — seeds `viewsCount: 0` on
  the `sounds/{soundId}/reels/{reelId}` node at creation time (whether the
  reel used an existing sound or became a new "original audio" sound).
- **`ReelSocialController#recordView`** (full-screen swipe player) — after
  the existing `reels/{reelId}/viewsCount` transaction, mirrors the same
  +1 onto `sounds/{musicId}/reels/{reelId}/viewsCount` via its own
  transaction (so concurrent viewers never clobber each other).
- **`HomeFeedWatchTracker#recordView`** (inline Home-feed playback) — same
  mirror. This path only carries `reelId`, not `musicId`, so it does one
  `reels/{reelId}/musicId` lookup first — but this whole method only ever
  runs **once per viewer per reel, ever** (guarded by the pre-existing
  `reelViews/{reelId}/{uid}` marker), so it doesn't scale with page loads
  the way the old per-page fan-out did.

## Legacy data

Reels linked to a sound *before* this shipped won't have `viewsCount` on
the sound-side node yet. `loadMoreReelsForSound()` detects that with an
`exists()` check per item and — only for those stragglers — does a
one-time real read of `reels/{reelId}/viewsCount`, patches it back onto
`sounds/{soundId}/reels/{reelId}/viewsCount`, and updates the grid cell in
place (`backfillLegacyViewCounts()`, the old `fetchViewCountsForPage()`
repurposed for this narrow case). Once patched, that reel never hits this
path again for any user. Freshly created or freshly viewed reels never
reach this method at all.

## Net effect

Steady state (any reel created or viewed after this change): **0** extra
reads per pagination page, down from 12. Only shrinking, one-time-per-reel
legacy reads remain, and each of those heals itself.

## Bonus: double thumbnail preload removed

`loadMoreReelsForSound()` also had an explicit `Glide...preload()` call
per item on every page — duplicating `setupGlidePreloaderForReels()`,
which already wires a `RecyclerViewPreloader` to the same adapter and
scroll-ahead preloads each thumbnail once, right before it enters the
viewport. That explicit loop is gone; preloading now happens exactly
once per thumbnail, via the `RecyclerViewPreloader` only.

## Bonus: DiffUtil off the main thread

`sortAndApplyReelItems()` (reorders "Original creator" reels to the front
whenever creator info resolves) ran `DiffUtil.calculateDiff()` — O(N) —
synchronously on the main thread. Fine at a page or two, but once several
pagination pages plus live adds (`attachSoundReelsLiveListener`) have
grown `reelItems`, that call alone can drop a frame.

The sort and the diff calculation now both run on a dedicated single-thread
`diffExecutor`; only `dispatchUpdatesTo()` — which must touch the adapter —
comes back to the main thread via `mainHandler.post()`. Guarded against the
list changing shape (a pagination page or live add/delete landing) while
the diff is mid-calculation: if `reelItems.size()` no longer matches the
snapshot the diff was computed against, the stale diff is dropped instead
of applied (the `isOriginalCreator` flags were already updated on the live
item objects before dispatch either way, so nothing is lost — just that
one reorder animation). `diffExecutor.shutdownNow()` added to
`onDestroyView()` so the thread doesn't outlive the fragment.

## Bonus: pooled ExoPlayer now plays through a disk cache

`SoundPreviewPlayerPool` already avoided rebuilding a whole ExoPlayer on
every Sound Detail open (renderer/decoder/thread setup reused app-wide).
What it didn't have: any disk cache underneath. `initAndStartPlayer()`
called `exoPlayer.setMediaItem(MediaItem.fromUri(url))` — a plain
`DefaultHttpDataSource`, so replaying the exact same preview URL (reopening
a sound, or the retry/300ms-fallback-URL path in `onPlayerError`)
re-downloaded it from the network every time, even seconds later.

- **`UnifiedVideoCacheManager`** gets a new `MUSIC` module, sharing the
  existing "other" 300MB disk cache + SQLite index with X/Status/Chat
  (audio files are small; a dedicated budget wasn't worth it).
- **`SoundPreviewPlayerPool#buildMediaSource(url)`** wraps that module's
  `CacheDataSource.Factory` in a `ProgressiveMediaSource`.
- **`SoundDetailFragment#initAndStartPlayer()`** now calls
  `exoPlayer.setMediaSource(pool.buildMediaSource(url))` instead of
  `setMediaItem(MediaItem.fromUri(url))`.

First play of a given URL still streams over the network (and caches to
disk as it plays); every play after that — this session or a future one,
since the cache index is SQLite-backed and survives app restarts — is
served straight off disk.

## Bonus: WAVEFORM_CACHE now trims under memory pressure

`WAVEFORM_CACHE` (the static `LruCache<String, float[]>` of pre-computed
waveform bars, capped at 64 sounds / ~9KB) only ever shrank via its own
count cap — nothing tied it to actual system memory pressure. Every other
process-wide cache in this codebase (`AvatarL2MemoryCache` and its
per-module wrappers) hooks `ComponentCallbacks2#onTrimMemory` instead of
relying solely on its own cap, so this now follows the same convention:

- **`TRIM_MEMORY_COMPLETE`** (or the legacy `onLowMemory()`) — `evictAll()`.
- **`TRIM_MEMORY_MODERATE`** — `trimToSize(maxSize() / 2)`, not a full wipe;
  `trimToSize()` doesn't touch `maxSize()`, so it grows back to 64 once
  pressure passes. MODERATE fires on routine backgrounding, and a cache
  this small isn't worth fully wiping there.
- Below `MODERATE` (`UI_HIDDEN`/`BACKGROUND`/`RUNNING_*`) — left alone,
  same reasoning `AvatarL2MemoryCache` documents.

Registered lazily via `ensureWaveformCacheTrimRegistered()`, called from
`onViewCreated()` — an `AtomicBoolean` guards it so the many
`SoundDetailFragment` instances that come and go in a session (bottom
sheet reopens, related-sound hops) only register the callback once per
process.

## Bonus: RelatedAdapter now inflates XML instead of building views in code

`SoundDetailActivity.RelatedAdapter#onCreateViewHolder()` used to hand-build
its row every time — `new LinearLayout()` + `new ImageView()` +
`new TextView()`, with manual `density`-multiplied math to get the 80dp
cover / 120dp cell sizes and `setPadding(4,2,4,2)` on the container.

That's now `item_sound_related_row.xml` (same 120x80 vertical
`LinearLayout` → 80x80 `ImageView` → single-line 11sp `TextView` tree,
same padding), inflated once per view-type via `LayoutInflater`. Minor —
this list is short and `setHasFixedSize(true)` already skips the extra
measure pass — but inflate is cheaper than three manual `View`
constructions per call, and the row can now be tweaked in the layout
editor instead of in Java. `RelatedAdapter.VH` takes the inflated root
`View` directly and finds `iv_related_row_cover` / `tv_related_row_title`
off it; `onBindViewHolder()`'s Glide sizing (`coverPx()`) and click-listener
wiring are unchanged since the pixel dimensions didn't move.

## Bonus: RelatedAdapter reused across loads (DiffUtil) instead of recreated

`SoundDetailFragment#loadRelatedSounds()` used to call `rvRelated.setAdapter(new
RelatedAdapter(...))` every time it ran — a fresh adapter (and lambda click
listener) thrown away and replaced, discarding the RecyclerView's existing
view-holder pool and forcing a full rebind of every visible row even when
nothing actually changed.

`RelatedAdapter` is now created once (in `onViewCreated`, alongside its
`LinearLayoutManager`/`setHasFixedSize` setup) and reused via a new
`submitList()` method that DiffUtils the adapter's own internal copy
against the incoming list (identity by `soundId`, content by `title` +
`coverUrl` — the two fields `onBindViewHolder()` actually renders) and
dispatches only the ops that changed. Fixed a latent bug in the same move:
the adapter's constructor used to hold the *same* `List` reference the
Fragment mutates in place (`relatedItems.clear()` / `.add()`), so any
future diff attempt against that reference would've been comparing a list
to itself; the adapter now takes a defensive copy on both construction and
every `submitList()` call.

Net effect today is small — `loadRelatedSounds()` only fires once per
screen-open — but the adapter is no longer thrown away for no reason, and
if the related-sounds list is ever wired to something live (matching the
existing `attachSoundReelsLiveListener` pattern for the reel grid), this
adapter is now ready for that without another refactor.

## Bonus: SoundDetailFragment converted to ViewBinding

`SoundDetailFragment` had 48 raw `findViewById()` calls in one `bindViews()`
method (plus one more, `getView().findViewById(R.id.layout_related_sounds_section)`,
buried in `loadRelatedSounds()`), inflating `fragment_sound_detail.xml` by
hand and looking up every field by id string.

`feature-reels/build.gradle` already had `viewBinding true` set — nothing
else in the module used it yet. `onCreateView()` now inflates via
`FragmentSoundDetailBinding.inflate(...)`, and `bindViews()` assigns every
field from `binding.*` instead of `v.findViewById(R.id....)`. Same field
set, same types — just the lookup path is a compiled/generated accessor
instead of a runtime id-string tree walk, and a renamed or deleted id in
the XML now fails the build instead of handing back a silent `null` at
runtime (one pre-existing case of exactly that: `tv_sound_duration` isn't
actually present in `fragment_sound_detail.xml` — it only exists in
`item_saved_sound.xml`/`bottom_sheet_sound_detail.xml` — so
`findViewById()` was already always returning `null` there; kept as an
explicit `tvDuration = null` so the `if (tvDuration != null)` guards
elsewhere keep working exactly as before, unrelated bug left alone).

`binding` is nulled at the end of `onDestroyView()`, after everything else
that touches the view fields has run, so the binding (and the view tree
under it) doesn't outlive the Fragment.

## Bonus: seek-bar ticker is now Choreographer-synced (ValueAnimator) instead of Handler-timer polling

`seekUpdateRunnable` was a `Handler.postDelayed(this, seekIntervalMs())`
self-rescheduling chain (300ms normally, 800ms thermal-throttled). Each
tick scheduled the *next* one relative to "now" — after the current tick
had already finished running — so under any main-thread jank (a GC pause,
a big layout/measure pass elsewhere on screen) the whole chain drifts
later and never re-syncs to an external clock.

Replaced with a `ValueAnimator` (`seekAnimator`, `INFINITE` repeat) whose
update listener is driven by `Choreographer` — re-synced to the display's
actual vsync signal every frame, so it can't accumulate drift, and it
coalesces with whatever else is already drawing that frame instead of
firing on its own independent timer. The thermal-aware cadence is
unchanged: the animator's listener still fires every frame cheaply (no
player calls there), but the real work — `exoPlayer.getCurrentPosition()`
+ seekbar/time update — only runs once `seekIntervalMs()` has actually
elapsed (checked via `SystemClock.elapsedRealtime()`), so CPU cost per
second is the same as before; only the scheduling primitive changed.

`startSeekTicker()`/`stopSeekTicker()` are drop-in replacements for the
old `seekHandler.post(seekUpdateRunnable)` / `seekHandler.removeCallbacks(...)`
calls at all four existing call sites (`onPlaybackStateChanged`,
`onPlayerError`, `resumePlayback`, `pausePlayback`) plus `onStop()` /
`onDestroyView()` — same lifecycle guarantees (`isResumed()` hard-stop,
`userSeeking` skip) preserved exactly.

## Files touched

- `feature-reels/.../music/SoundDetailFragment.java`
- `feature-reels/.../music/SoundDetailActivity.java`
- `feature-reels/.../res/layout/item_sound_related_row.xml` (new)
- `feature-reels/.../music/SoundPreviewPlayerPool.java`
- `feature-reels/.../upload/ReelUploadActivity.java`
- `feature-reels/.../feed/controllers/ReelSocialController.java`
- `feature-reels/.../feed/HomeFeedWatchTracker.java`
- `core/.../cache/UnifiedVideoCacheManager.java`
