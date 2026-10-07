# v472 - Reel comments: refresh coalescing, throttled cache write, reply row pool

## #5 applyFilterAndSort + disk cache
- `applyFilterAndSort()` now only QUEUES one run on the next looper turn (N calls in a turn = 1 run). User-driven paths
  (sort chips, search typing/close, posting a comment, Undo) call `applyFilterAndSortNow()` and stay instant.
- `applyFilterAndSort` no longer makes a 2nd list copy in `ReelCommentsAdapter.setComments` (callers pass fresh lists).
- Disk cache: trailing throttle 500 ms + allocation-free signature of the persisted fields; identical data = no DB write.
  Pending write is flushed in `onDestroyView`.

## #6 Reply rows
- Reply rows go to a 16-row pool (`obtainReplyRow` / `releaseReplyRow`) when a thread is collapsed, refreshed, or a row is
  deleted, instead of re-inflating `item_reel_reply` each time. `releaseReplyRow` resets click/long-click, alpha, time colour,
  heart tag/scale, avatar Glide request.
- Not done: nested RecyclerView for very long threads (server paging from v471 already caps visible rows).
Files: ReelCommentFragment, ReelCommentsAdapter, version.properties (130).
