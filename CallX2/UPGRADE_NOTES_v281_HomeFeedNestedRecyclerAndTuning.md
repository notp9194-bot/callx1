# v281 — Home Tab Feed: Ultra-Advanced / Instagram-Level Optimization Pass

Follow-up to v243 (RecyclerView migration), v260 (inline "Suggested reels"
nested recycling), and v280 (list-cap windowing). This pass closes the last
gap between the Home feed's two inline suggestion strips and brings the
`RecyclerView` itself in line with Instagram-grade tuning.

## 1. "Suggested for you" creators strip — real ViewHolder recycling

**Before:** `bindSuggestedCreatorsRowContent()` built the horizontal creator
chip row with a plain `HorizontalScrollView` + `LinearLayout`, allocating a
fresh `CircleImageView`/`TextView`/`LinearLayout` tree per candidate and
issuing a fresh Glide load — every single time the row was bound *or
rebound* (this row is itself one `FrameLayout` that `FeedAdapter` rebinds
from scratch whenever it scrolls back into view). Nothing was ever reused,
either across rebinds of the same strip or across the multiple "Suggested
for you" strips mixed periodically into a long infinite-scroll session.

This was the one inline row left on the old pattern — the sibling
"Suggested reels" row (see `UPGRADE_NOTES_v260_InlineSuggestedReelsRow.md`)
already got the nested-`RecyclerView` treatment.

**After:** `bindSuggestedCreatorsRowContent()` now hosts a horizontal
`RecyclerView` (`SuggestedCreatorsTileAdapter`) backed by a new **shared**
`RecycledViewPool` (`SUGGESTED_CREATORS_TILE_POOL`), mirroring
`SuggestedReelsTileAdapter` exactly:

- Only chips actually on/near screen are built or bound.
- A chip `ViewHolder` scrolled out of one strip is hand-off-ready for the
  next strip (or this strip's own next rebind) — no re-inflation, no
  re-decode.
- `onViewRecycled()` clears the in-flight Glide request before the holder
  returns to the shared pool, so a slow load from strip A can never land a
  bitmap into an avatar strip B has since reused for a different creator
  (same defensive pattern as the reels tile adapter).
- Visual output (136dp chip, 90dp avatar, name below) is unchanged — this
  is a drop-in perf swap, not a redesign.

## 2. `recycler_home` tuning

- `setHasFixedSize(true)` — `fragment_home.xml` pins `recycler_home` to
  `match_parent`/`match_parent` inside a `CoordinatorLayout`, so the
  RecyclerView's own on-screen size never depends on adapter content. This
  was never declared, so every adapter change paid for an extra parent
  re-measure it didn't need.
- `LinearLayoutManager.setInitialPrefetchItemCount(2)` — `item_home_feed_post`
  is a heavy inflate (`PlayerView` + ~28 children), so the prefetch thread
  now gets a two-row head start during a fling instead of the default one.

## Net effect

Both inline suggestion strips (reels + creators) now follow the identical
Instagram-style nested-recycling pattern, and the top-level feed
`RecyclerView` is fully declared for its actual layout contract. No public
API, click-target, or visual change — pure recycling/measure-pass wins on
top of the existing v243/v260/v280 architecture.
