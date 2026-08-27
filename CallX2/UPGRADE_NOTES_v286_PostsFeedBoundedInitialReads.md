# v286 — Post Feed: Bounded Initial Parallel Reads

Follow-up to v285's windowing. The initial open still fired every id in
the "after" batch (and, once backgrounded, every id in "before") as
parallel Firebase reads in one shot — on a 100+ photo profile, tapping a
post near the top still meant ~100 simultaneous reads, just most of them
now backgrounded instead of blocking first paint.

## Fix
Both initial batches are now capped to `POST_INITIAL_BATCH` (=
`POST_WINDOW_BEHIND` + `POST_WINDOW_AHEAD` = 45, same size as the
steady-state window from v285):
- `afterIds` — only the first 45 ids after the tapped post are fetched
  up front.
- `beforeIds` — only the 45 ids closest to the tapped post (contiguous
  with the window, so `windowStartOffset` still lines up exactly after
  `prependBeforeBatch`) are fetched, not the full range back to the
  start of the grid.

Anything past that isn't read at all on open — it streams in lazily via
v285's `reloadPostsAfter()` / `reloadPostsBefore()` only once the user
actually scrolls that far. Net effect: parallel-read count on open is now
bounded at ~45 regardless of profile size, instead of scaling with
however many photos are after (or before) the tapped one.

## File touched
`feature-reels/src/main/java/com/callx/app/profile/PostsFeedActivity.java`
