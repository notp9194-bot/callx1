# v306 — SoundDetailFragment: explicit single-flight guard for soundReelsLiveListener

## What changed
`SoundDetailFragment.attachSoundReelsLiveListener()` previously guarded
against double-attach only via `soundReelsLiveListener != null`. That's an
instance field check — correct as long as the assignment
(`soundReelsLiveListener = new ChildEventListener() {...}`) always
completes before the method can be re-entered. Normally true, but not
guaranteed if `loadMoreReelsForSound()` → `finishAppendingPage()` →
`attachSoundReelsLiveListener()` is ever re-triggered mid-flight for the
*same* Fragment instance (a queued debounced pagination `Runnable`, a
second Firebase page callback landing back-to-back, etc.) — that could in
theory attach two `ChildEventListener`s to the same query.

Added `reelsLiveListenerAttached` (`AtomicBoolean`, per-instance field):
- `attachSoundReelsLiveListener()` now claims it via
  `compareAndSet(false, true)` **before** building/assigning the listener.
  A losing caller bails immediately instead of racing the query.
- `detachLiveListener()` always resets it to `false`, even if there was no
  listener to remove — so a partial/failed attach, or a call with
  `soundId` already empty, can't leave it stuck `true` and permanently
  block re-attachment.
- `onDestroyView()` already called `detachLiveListener()`, so rotation /
  fragment-recreate still gets a clean guard: the field is per-instance
  (not static), and the new Fragment instance starts with
  `reelsLiveListenerAttached = false` regardless of what happened on the
  old one.

The existing `soundReelsLiveListener != null` check is kept as a
defensive no-op fallback (unreachable in practice now that the claim
happens first).

## pinnedReelId — no change needed
Checked `ensurePinnedReelIdLoaded()` → `SoundDetailCache.getPinnedReelId()`:
this is a one-shot `addListenerForSingleValueEvent` (not a persistent
`ChildEventListener`), and `SoundDetailCache` already collapses concurrent
identical in-flight requests per-uid via its `inFlightPinned` waiters map.
There's no live listener there to double-attach, so nothing to guard.

## Files touched
- `feature-reels/src/main/java/com/callx/app/music/SoundDetailFragment.java`

---

# v307 — SoundWaveformView: opt-in hardware layer for the animation window

## What changed
Added `setHardwareLayerEnabled(boolean)` to `SoundWaveformView`. **Off by
default.**

- When enabled AND the view is actively animating (`playing && !forceStatic`),
  `applyLayerTypeForCurrentState()` sets `LAYER_TYPE_HARDWARE`.
- The moment animation stops (playback pause, thermal HOT kicking in via
  `setForceStatic(true)`, or the view detaching/`release()`), the layer type
  is dropped back to `LAYER_TYPE_NONE` — never left resident while idle.
- Wired into the existing `refreshDriverState()` path (same place
  `startDriver()`/`stopDriver()` already live), plus a belt-and-braces reset
  inside `stopDriver()` itself so `onDetachedFromWindow()`/`release()` also
  release it.

## Why opt-in, not always-on
`onDraw()` recomputes all 36 bar heights fresh on every `invalidate()` —
there's no static content for a hardware layer to cache/reuse across frames
the way there would be for a view that's only translating or fading. So the
win here is narrower (draw commands move to RenderThread) and isn't free
(pins a GPU-backed bitmap sized to the view for as long as it's active).
Per the ask: this is a "next level" knob to flip on only if a profiler run
(GPU rendering profile / Layout Inspector / systrace) actually shows this
view's draw/invalidate cadence as a jank source — not applied unconditionally.

## Files touched
- `feature-reels/src/main/java/com/callx/app/music/SoundWaveformView.java`

---

# v308 — ReelThumbAdapter: setHasStableIds(true) + getItemId()

## What changed
`SoundDetailActivity.ReelThumbAdapter` (the "Reels with this sound" grid
adapter used by `SoundDetailFragment`'s `rvReels`) now calls
`setHasStableIds(true)` in both constructors and implements
`getItemId(position)`, returning `reelId.hashCode()` (falls back to
`position` only if `reelId` is somehow null).

This brings it in line with the sibling adapters in this codebase that
already do this — `SoundReelsAdapter`, `PostsFeedActivity`'s grid adapter,
`UserReelsActivity`'s grid adapter — all keyed the same way (reelId hash).

## Why it matters here specifically
`SoundDetailFragment#sortAndApplyReelItems()` already diffs this exact list
by `reelId` identity (see `ReelThumbDiffCallback#areItemsTheSame`) and
dispatches move/change ops via `DiffUtil.DiffResult#dispatchUpdatesTo()`
instead of `notifyDataSetChanged()`. Pagination inserts
(`finishAppendingPage()`), live adds/removes
(`attachSoundReelsLiveListener()`), and the "Original creator" reorder all
shift item positions in this same grid. Without stable IDs, RecyclerView
resolves a dispatched op by *position*, not by item identity — so a
ViewHolder can get rebound to a different item than the one it was
previously showing when positions shift, discarding an already-bound cell
(and re-triggering `onBindViewHolder()`'s Glide load, see
`resolveGridThumbSize()`/`.override()`) for content that hadn't actually
changed. Stable IDs let RecyclerView follow the item, not the slot.

## Files touched
- `feature-reels/src/main/java/com/callx/app/music/SoundDetailActivity.java`

---

# v309 — SoundDetailFragment: shared static RecycledViewPool for rvReels

## What changed
Added `SOUND_REELS_SHARED_POOL` — a `static final RecyclerView.RecycledViewPool`
on `SoundDetailFragment`, `setMaxRecycledViews(0, 18)` for view type 0
(`ReelThumbAdapter`'s only cell type). Wired via
`rvReels.setRecycledViewPool(SOUND_REELS_SHARED_POOL)` in `bindViews()`,
right after the existing `setItemViewCacheSize(12)`.

Same pattern already used elsewhere in this codebase —
`HomeFragment#SUGGESTED_CREATORS_TILE_POOL` / `#SUGGESTED_REELS_TILE_POOL`,
`UserReelsActivity#gridSharedViewPool`, `MessageInfoBottomSheet#SHEET_VIEW_POOL`.

## Why this specifically helps here
A related-sound click doesn't update the current screen in place —
`loadRelatedSounds()`'s `onClick` (see `bindViews()`) replaces this Fragment
with a brand-new `SoundDetailFragment` instance for the next sound. That
means a brand-new `rvReels` + a brand-new `ReelThumbAdapter` on every hop.
Without a shared pool, that also meant a brand-new *per-instance*
`RecycledViewPool` every hop — so every grid cell re-inflated
`item_sound_reel_thumb.xml` from scratch even though the previous
instance's now-discarded pool was sitting on a full set of already-inflated
ViewHolders nobody could reach anymore.

`RecyclerView.RecycledViewPool` keys purely by (adapter viewType), not by
adapter identity, so a new `ReelThumbAdapter` instance can freely draw
scrap from a pool a *previous* instance populated, as long as the view
type matches. `ReelThumbAdapter` never overrides `getItemViewType()`
(always type 0), so this is safe — every related-sound hop's grid now
reuses the same pool of pre-inflated cells instead of paying inflation
cost again per cell, per hop.

## Files touched
- `feature-reels/src/main/java/com/callx/app/music/SoundDetailFragment.java`

---

# v310 — SoundWaveformView.onDraw(): precompute per-bar constants

## What changed
`onDraw()` used to recompute, for all 36 bars, on every single frame:
`durMs = 400 + (i % 5) * 80` and
`targetPx = minHPx + (maxHPx - minHPx) * (0.4f + (i % 7) * 0.08f)`.
Both are pure functions of the bar index `i` alone — never of elapsed time —
so redoing them every tick bought nothing.

Added:
- `BAR_DUR_MS_PER_INDEX` / `BAR_CYCLE_MS_PER_INDEX` / `BAR_TARGET_FRACTION`
  — `static final` arrays, filled once in a static initializer (identical
  for every instance in the process, same sharing rationale as
  `SoundDetailFragment`'s `WAVEFORM_CACHE`).
- `targetPxByIndex` — per-instance `float[BAR_COUNT]`, since the final
  pixel value also depends on `minHPx`/`maxHPx` (device density). Built
  lazily via `ensureTargetPxCached()` the first time `onDraw()` runs, and
  only rebuilt if the cached density (`cachedDp`) doesn't match the
  current one (defensive — density can't change frame-to-frame, but this
  keeps a rare density change, e.g. a display swap, from serving a stale
  cache instead of silently ignoring it).

`onDraw()`'s per-frame loop now only computes the part that actually
changes tick to tick — `cyclePos`/`frac`/`eased` — and reads everything
else (`durMs`, `cycleMs`, `targetPx`) straight from the precomputed
arrays. Output is bit-for-bit the same animation; this is a pure
CPU-cost reduction, no visual or timing change.

## Files touched
- `feature-reels/src/main/java/com/callx/app/music/SoundWaveformView.java`
