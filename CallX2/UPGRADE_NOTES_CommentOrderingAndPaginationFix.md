# Reel Comments — Ordering, Pagination Direction & Sheet-Height Fixes

Three related bugs reported in the comment system, all stemming from the
same root cause: parts of the code still assumed the old "oldest-top,
newest-bottom" chat-style layout after the list was switched to display
newest-first.

## Bug 1 — comments flash oldest-on-top then "snap" to newest-on-top

**Root cause:** `applyFilterAndSort()` called `adapter.setComments(filtered)`
(insertion order — oldest first, since Firebase's initial burst arrives
ascending by key) and then, as a **separate** call, `adapter.sortByNewest()`
/ `adapter.sortByTop()`. Each is its own `AsyncListDiffer.submitList()` —
two independent async diffs — so the RecyclerView could paint the
unsorted list for a frame before the second diff landed and re-sorted it.
That's the visible "oldest-top → newest-top" flip on open.

**Fix:** sort the filtered list *before* submitting it, in a single pass —
`ReelCommentFragment.applyFilterAndSort()` now does
`Collections.sort(filtered, ...)` then one `adapter.setComments(filtered)`
call. Only one diff ever runs, so the first paint is already correct.
The two comparators used are now exposed as public statics on the adapter
(`ReelCommentsAdapter.NEWEST_FIRST` / `TOP_FIRST`) instead of being
private lambdas duplicated per sort method.

## Bug 2 — input box sits right under the last loaded comment, not docked to the screen bottom

**Root cause:** in the bottom-sheet host (`ReelCommentSheetFragment`),
`BottomSheetBehavior.setFitToContents(false)` controls the *behavior's*
target height, but Material's own `design_bottom_sheet` container is
still inflated with a `WRAP_CONTENT` `LayoutParams.height`. With only the
first ~12 comments loaded, the sheet could size itself to
"12 rows + input bar" instead of the intended full expanded height —
so the input row visually ended up right after however many comments
happened to be loaded, instead of pinned to the bottom of the sheet.

**Fix:** in `onStart()`, explicitly force
`sheet.getLayoutParams().height = MATCH_PARENT` on the
`design_bottom_sheet` view right after it's found. This is the standard
fix for this exact Material Components gotcha and guarantees the sheet
(and therefore the input bar docked at the bottom of our fragment's own
layout) always fills the full expanded/half-expanded height, regardless
of comment count. The fullscreen host (`ReelCommentActivity`) was never
affected — it doesn't go through `BottomSheetBehavior`.

## Bug 3 — scrolling doesn't load the next (older) batch

**Root cause:** `maybeLoadOlderComments()` itself was fine, but the
scroll-listener trigger in `setupAdapter()` fired on scrolling **up**
toward the top (`dy < 0`, `findFirstVisibleItemPosition() <= 4`) — correct
only for the old oldest-top/newest-bottom ordering, where older comments
live above. Once comments render newest-first (top), older ones live
further **down** the list, so scrolling up near the top does nothing —
there's nothing older to page in up there anymore.

**Fix:** the trigger now fires on scrolling **down** toward the bottom
(`dy > 0`, `findLastVisibleItemPosition() >= itemCount - 5`), which is
where the older comments actually are now.

### Ripple fixes from the same ordering flip
A few other pieces of logic assumed "newest = bottom" and needed to flip
along with it, or the app would auto-scroll/prompt in the wrong direction
after Bug 1/3 were fixed:
- `isNearBottom()` → `isNearTop()` (checks `findFirstVisibleItemPosition()`
  instead of last) — used to decide whether to keep the user pinned to the
  newest comment as more arrive live.
- `autoScrollIfAtBottom()` → `autoScrollIfAtTop()` — scrolls to position 0
  (newest) instead of the last position.
- The "↓ New comments" pill → "↑ New comments", and tapping it now scrolls
  to position 0 instead of the last position — new comments insert at the
  top now, not the bottom.
- `tv_loading_older`'s "loading earlier comments…" indicator moved from
  the top of the list to the bottom (`activity_reel_comment.xml`) — it
  now appears where older comments actually load in.

## Files touched
- `ReelCommentFragment.java` — sort-before-submit, scroll-direction flip,
  `isNearTop`/`autoScrollIfAtTop`, pill direction/behavior.
- `ReelCommentsAdapter.java` — `NEWEST_FIRST`/`TOP_FIRST` exposed as
  public static `Comparator`s, reused by both the adapter's own
  `sortByNewest()/sortByTop()` and the fragment's pre-sort.
- `ReelCommentSheetFragment.java` — force `MATCH_PARENT` height on
  `design_bottom_sheet`.
- `activity_reel_comment.xml` — pill text arrow, loading-older indicator
  repositioned to the bottom.

No Firebase schema or query changes — `maybeLoadOlderComments()`'s
`orderByKey().endBefore(oldestLoadedKey).limitToLast(PAGE_SIZE)` query was
already correct; only the UI trigger direction was wrong.
