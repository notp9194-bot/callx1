# v867 — file-bind disk stat off main thread + empty-record cache fix

Based on v866 report (vivo V2338, DEBUG, 39 s): bind.spacing 561 -> 62 us (v866 fix confirmed), frames over 2x budget 15% -> 4%, P50 13.5 ms.
Remaining bind spike: `file` type = 3 binds, 98 ms total, max 74 ms. Also `miss reason noDisplayList=94` of 434 records.

## Changes
1. `MessagePagingAdapter` file bubble bind: `getCachedFileFast()` (MediaCache.getCached = path build + File.exists/length on the main thread) replaced by a memo-only lookup. On a memo miss a single low-priority background thread verifies and posts `cv.setFileCached(true)` (guarded by canvasBindToken). Trade-off: a file that IS downloaded but not memoized yet shows the download state for a few ms on its first bind, then flips.
2. `MessageBubbleCanvasView`: new `fullBubbleEmptyRecord`. When a recording produces no display list (nothing drawn in the cached layer), `hasDisplayList()` stays false and every draw looked like a cache miss -> re-record each frame. Now treated as a valid hit while not dirty/same size. Reset in `releaseRenderCaches()` and on every real record.

## Verify (next Bubble Perf report)
- `typeBind by message type`: `file` max should drop from ~74 ms.
- `miss reason ... noDisplayList` should drop towards 0; hit-rate should rise.
