# v311 — SoundDetail: zero-reload rotation restore

## Root cause (gap #4)
`SoundDetailFragment#onViewCreated()` unconditionally called
`loadSoundData()/loadReelsForSound()/loadRelatedSounds()/loadCreatorProfile()`
on every single create — including a rotation, which recreates the
Fragment (and every plain instance field: `reelItems`, `lastReelKey`,
`hasMoreReels`, the adapter, etc.) from scratch. `SoundDetailCache`'s TTL
made the re-reads cheap (no network on a warm cache), but each reload still
meant: a Firebase/cache read, `reelItems` rebuilt starting from an EMPTY
list, and the grid rebound from scratch. Since that repopulation happens
inside an async callback, it always lost the race against
FragmentManager's own view-state restore (which runs synchronously right
after `onViewCreated()` returns) — so `rvReels`' scroll offset was always
restored against an empty adapter and silently discarded.

## Fix
New `SoundDetailViewModel` (fragment-scoped, plain state cache, no
LiveData) — the framework retains a Fragment's `ViewModelStore` across a
config-change recreate, so the same instance survives rotation.
`applySoundsNodeEntry()` / `applyMusicLibraryEntry()` / `bindCreatorRow()`
/ `finishAppendingPage()` / the live-listener add/remove callbacks /
`loadRelatedSounds()` now mirror their resolved state into it as they
land.

`onViewCreated()` checks `vm.hasAnyData()` for the current `soundId`
before doing anything: on a rotation it skips all four load calls
entirely and calls the new `restoreFromViewModel()` instead — which runs
**synchronously, inline in `onViewCreated()`**, repopulating `reelItems`
and rebuilding the adapter before returning, so `rvReels` already has its
items by the time the framework's saved-state restore runs right after.
Scroll offset is additionally captured explicitly
(`onDestroyView()` → `vm.savedScrollY = scrollSoundDetail.getScrollY()`)
and reapplied as a belt-and-suspenders measure on top of the framework's
own view-state restore.

`loadReelsForSound()` took a `fetchInitialPage` boolean: `true` on a
fresh open (fires `loadMoreReelsForSound()`, the actual Firebase page
read), `false` when restoring (adapter/scroll-listener/preloader setup
only, plus a live-listener reattach — no read).

Net effect: a rotation on an already-open Sound Detail screen is now a
pure field-copy pass — zero Firebase reads, zero grid rebuild-from-empty,
scroll position preserved.

## Gap #5 — Firebase disk persistence
Checked: already on, app-wide, in `CallxApp.onCreate()` (`PERF FIX v33`
block) — `FirebaseDatabase.setPersistenceEnabled(true)` +
`setPersistenceCacheSizeBytes(20MB)`, called synchronously as the very
first `FirebaseDatabase` touch in the process. Sound/reels nodes already
get the same on-disk cache as every other Realtime Database read in the
app; nothing to change here.

## Files touched
- `feature-reels/src/main/java/com/callx/app/music/SoundDetailViewModel.java` (new)
- `feature-reels/src/main/java/com/callx/app/music/SoundDetailFragment.java`
- `feature-reels/build.gradle` (added `androidx.lifecycle:lifecycle-viewmodel:2.7.0`)
