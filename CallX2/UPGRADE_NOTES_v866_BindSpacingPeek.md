# v866 — bind.spacing fix (peek) + per-type bind timing

Based on v865 report (vivo V2338, DEBUG, 60 s): records 863 (294 ms total = ~0.08 ms/frame), binds 880 (1309 ms).
bind.spacing = 437 ms (avg 561 us) = 33% of all bind time, from `getItem(position-1)` firing a Paging load hint on every bind.

## Changes
1. `MessagePagingAdapter.isGroupedWithPrevious`: `getItem(position-1)` -> `peek(position-1)` (+ `position<=0` guard). No behaviour change (the row at `position` already hinted).
2. `MessagePagingAdapter.applyGroupedSpacing`: margins are still updated, but `setLayoutParams()` (requestLayout) only when the view already has a parent; a holder being bound for attach is measured by RV with these LayoutParams anyway.
3. Bubble Perf report: `typeBind by message type` (n/total/avg/max per text/image/multi_media/...) to find the 74 ms typeBind spike.

## Findings from v865 report (no code change)
- `miss reason: dirty=761` ~= binds(780): a recycled view binding new content must re-record once. The remaining 110 "invalidate on CLEAN" are real content updates (67 late media bitmaps, 36 reply thumbs, 7 group bitmaps), all already same-value guarded.
- `preRecord sched=0`: the bind-side gate (`!isComputingLayout()`) is false for every bind — binds happen inside RV layout/scroll fill (custom LLM with extraLayoutSpace), not in GapWorker prefetch, so record-ahead never applies. Left as is.
- Bubble main-thread total (bind+measure+record) ~2.0 s / 60 s = ~3.4%. Frame time is dominated by RenderThread: cmdIssue 6.1 ms, gpu 5.8 ms, unknownDelay 3.2 ms avg (slowest stage in 582/1238 over-budget frames = cmdIssue).
