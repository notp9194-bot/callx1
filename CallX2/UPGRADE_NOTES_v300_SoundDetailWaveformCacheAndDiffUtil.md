# v300 — Sound Detail: waveform LruCache + DiffUtil for the reels grid

## Problem

**1. Waveform recomputed every open**
`SoundDetailFragment#buildStaticWaveform()` called `SoundWaveformView
#seedStatic(soundTitle.hashCode())` unconditionally, every time a
`SoundDetailFragment` instance was created — including reopening the exact
same sound's detail screen multiple times in one session (tap a sound →
back → tap it again, or navigating through "Similar Sounds"). The 36 bar
heights are fully determined by the sound, so this was pure repeat work:
a fresh `Random` walk of 36 `nextInt()` calls on the main thread, every
single open, for a value that never changes for that sound.

**2. `notifyDataSetChanged()` on a plain reorder**
`sortAndApplyReelItems()` re-sorts `reelItems` so the original creator's
reels float to the front once `creatorUid` arrives, then called
`reelThumbAdapter.notifyDataSetChanged()`. That's a full rebind of every
bound cell in the 3-column reels grid — including re-issuing Glide loads
for thumbnails that didn't move and didn't change — just to shuffle a
handful of rows. (Actual pagination was already fine:
`loadMoreReelsForSound()` / the live `ChildEventListener` use
`notifyItemRangeInserted` / `notifyItemInserted` / `notifyItemRemoved`
already — this reorder path was the one blanket-refresh left.)

**3. `rv_related_sounds` missing `setHasFixedSize(true)`**
`rvReels` already had `setHasFixedSize(true)` (see v36/UltraScrollOptimization),
but `rvRelated` didn't, even though every row it lays out is a fixed
80dp-tall cell (`RelatedAdapter.onCreateViewHolder()`), so the RecyclerView's
own `wrap_content` height never actually changes as items are set —
it was just skipping the same easy measure-pass win rvReels already gets.

## Fix

### Waveform — `SoundWaveformView.java` + `SoundDetailFragment.java`
- Added a static `LruCache<String, float[]>` (cap 64 sounds, ~9KB total)
  in `SoundDetailFragment`, keyed by `soundId` (falls back to a
  `title:<hash>` key on the rare sound with no id). Static + capped means
  it's shared across every `SoundDetailFragment` instance for the process
  lifetime and self-evicts the least-recently-opened sound once full —
  "frees" automatically rather than growing unbounded.
- `SoundWaveformView#seedStatic(int)` now returns the generated
  `float[36]` (a defensive clone) instead of `void`, so the fragment can
  cache what it just computed.
- Added `SoundWaveformView#setStaticHeights(float[])` — applies a cached
  array directly (`System.arraycopy` + `invalidate()`), skipping the
  `Random` generation entirely on a cache hit.
- `buildStaticWaveform()`: cache hit → `setStaticHeights(cached)`; miss →
  `seedStatic()` as before, then stash the result for next time.

### Reels grid reorder — DiffUtil
- `sortAndApplyReelItems()` now snapshots `reelItems` before sorting,
  sorts in place as before, then runs `DiffUtil.calculateDiff()` against
  the pre/post snapshots and dispatches the result to `reelThumbAdapter`
  instead of `notifyDataSetChanged()`. Only rows whose position or
  `isOriginalCreator`/`viewsCount`/`thumbnailUrl` actually changed get
  rebound; everything else (and its already-loaded Glide thumbnail) is
  left alone.
- Added `SoundDetailFragment.ReelThumbDiffCallback`, a small
  `DiffUtil.Callback`: identity by `reelId`, content equality by the
  fields `onBindViewHolder()` actually renders.

### `rvRelated` — `setHasFixedSize(true)`
- Added alongside its `LinearLayoutManager` setup in
  `SoundDetailFragment#onViewCreated()`, same reasoning already documented
  on `rvReels`.

## Files touched
- `feature-reels/src/main/java/com/callx/app/music/SoundWaveformView.java`
- `feature-reels/src/main/java/com/callx/app/music/SoundDetailFragment.java`

## Not changed (already fine)
- `SoundReelsAdapter` already uses `setHasStableIds(true)`.
- `ReelThumbAdapter`'s actual pagination path (`notifyItemRangeInserted`)
  and live-add/remove path (`notifyItemInserted`/`notifyItemRemoved`) were
  already fine-grained — only the sort path used
  `notifyDataSetChanged()`.
- `SoundDetailBottomSheet` / `SoundDetailSheetFragment` delegate to
  `SoundDetailFragment` for this logic and needed no changes.
