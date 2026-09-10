# v400 — Reel Comments Disk-Level Persistence

## Problem
Reel comments sheet had memory-level speed (LRU avatar cache +
AsyncListDiffer) and optimistic local writes (`ReelComment#sendState`),
but no disk persistence. Every reopen of the sheet re-fetched from
Firebase from scratch — shimmer every time, blank on offline open.

## What changed
New Room-backed cache, same pattern as `TrendingAudioCacheManager`
(`core/db` entity + DAO, feature-side manager class):

- `ReelCommentCacheEntity` / `ReelCommentCacheDao` (core, `AppDatabase`
  v64 → v65, `MIGRATION_64_65`)
- `ReelCommentCacheManager` (feature-reels/comments) — fire-and-forget
  Room writes on a single-thread IO executor, async reads posted back to
  main thread.

## How it's wired into `ReelCommentFragment`
- **Paint layer only.** `paintFromDiskCacheIfEmpty()` runs on open,
  reads the last cached window, and — only if the real Firebase burst
  hasn't landed anything into `allComments` yet — paints straight into
  the adapter (`adapter.setComments(...)`). It never touches
  `allComments` / `loadedCommentIds`, so the real `ChildEventListener`
  path in `loadComments()` is completely untouched and always wins once
  it settles.
- **Save.** `saveCommentsToDiskCache()` runs from the existing
  `refreshRunnable` debounce (i.e. only on real network-driven updates,
  never mid-search). Only confirmed comments (`sendState == null`) are
  persisted — pending/failed optimistic rows are skipped so a comment
  that never actually sent can't get resurrected on the next open.
- Cache is capped at 40 rows per reel and pruned after 3 days
  (comments churn fast, same TTL reasoning as the Trending Audio cache).

## Trade-off (documented, matches Trending Audio's cache)
Firebase is still always re-queried on every open — this table only
fills the gap before that response lands (and covers a fully-offline
cold open). It is not a source of truth; a brief re-diff when the real
data lands and replaces the cached paint is expected and harmless.
