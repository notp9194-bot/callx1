# v337 — Home Feed: shrink item-view cache during fast fling

## Problem
`recyclerHome.setItemViewCacheSize(6)` was a fixed value. This cache is
separate from the `RecycledViewPool` (unbound, ready-to-rebind instances,
already sized up for post rows) — it holds fully-**bound** ViewHolders that
scrolled off screen, so scrolling back to one skips `onBindViewHolder()`
entirely. During a fast fling, cards are racing past and scrolling back off
again within a frame or two, so a size-6 cache meant more of those
fast-flung cards got the *full* bind treatment (and stayed resident in
memory) before being discarded anyway — extra CPU work stacked on top of
what the v335/v336 Glide gating already targets, for cards the user never
actually paused on.

## Fix
Reuses the same `RecyclerView.OnScrollListener.onScrollStateChanged()` gate
added in v335 for the Glide pause:

- `SCROLL_STATE_SETTLING` (fast fling coasting) →
  `setItemViewCacheSize(FLING_ITEM_VIEW_CACHE_SIZE)` (2) — still keeps the
  couple of cards right around the viewport instant-cached.
- `DRAGGING` / `IDLE` → restored to `DEFAULT_ITEM_VIEW_CACHE_SIZE` (6, the
  original value) immediately.

The `RecycledViewPool.setMaxRecycledViews(ROW_POST, 10)` sizing right above
it is untouched — that pool of unbound instances is still what a fling
needs to avoid falling back to `LayoutInflater.inflate()`, this change only
affects how many already-bound instances get held onto in the interim.

## Scope
~10 lines: two named constants (replacing the old inline `6` at
`recyclerHome.setItemViewCacheSize(6)`) and one `setItemViewCacheSize()`
call added into the existing scroll-state listener, right next to the v335
Glide gate it shares timing with. No adapter, ViewHolder, or bind logic
changed. Playback selection is untouched, same as v335/v336 —
`attachPlayerToCard`, `playMostVisibleCard`, `currentPlayingIndex`, and
`HomeFeedAutoplayPolicy` are unaffected.
