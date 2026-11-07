# v731 — Status tab: carousel no longer "builds again" on every visit

Cause (read from code): ViewPager2 FragmentStateAdapter destroys StatusFragment 2+ tabs away and creates a NEW instance on return. All state
(statusMap / myStatuses / seenMap / adapter / carousel scroll) lived in the fragment -> skeleton cards, cards appearing from Room/Firebase,
seen/unseen re-sort when the statusSeen listener answered, avatar/name swaps, scroll back at the start.

1. **State outlives the fragment:** new `StatusTabState` (static snapshot: tray, my statuses, seenMap, blocked uids, carousel scroll). Saved in `onStop`,
   restored in `onCreateView` (before the adapter is built, so no skeleton), painted in `onStart` via `rebuildStatusAdapterNow()`.
   Carousel position restored (`StatusListAdapter.setPendingCarouselScroll / captureCarouselScroll`). Different uid = discarded; expired items are dropped by the normal isLive filter.
2. **Seen/unseen right on first paint:** `seedFromStatusCache` now also copies `StatusCacheManager.getSeenMap()`; the card's unseenCount uses the same
   local "seen at" mark (`StorySeenState`) as the ring (`hasUnseen`), so a card no longer jumps from unseen to seen later.
3. **Fewer rebuilds:** `rebuildStatusAdapterNow` builds a signature of what the adapter would get (entries, my status, search-empty text, next label tick)
   and skips `update()` when unchanged. Debounce 80 -> 150 ms.

Files: `StatusFragment.java`, `StatusListAdapter.java`, new `StatusTabState.java`. Not built / not run on a device.
Cold start with an empty cache still shows the skeleton until Room answers (nothing to restore yet) — that is expected.

Check: open Status tab, scroll the carousel, go to Chats (2 tabs away), come back -> same cards, same scroll position, no skeleton.
Mark a story seen in the viewer -> card moves to "seen" once, immediately.
