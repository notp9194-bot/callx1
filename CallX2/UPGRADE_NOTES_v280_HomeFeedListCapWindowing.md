# v280 — Home feed list cap / windowing

## Problem
`HomeFragment`'s Home tab feed (`currentFeedPosts` / `feedCards` / `feedItems`)
only ever grows: infinite scroll (`appendFeedPage`) appends every page ever
fetched and nothing ever shrinks the three backing lists, even though
`FeedAdapter`'s RecyclerView recycling already bounds the number of *inflated
views* on screen. A long scroll session accumulates an unbounded amount of
`ReelModel`/`FeedRow` bookkeeping for posts that will never be revisited.

## Change
- `FEED_WINDOW_BEHIND` (15) / `FEED_WINDOW_AHEAD` (30) define the window kept
  around the current visible position (`currentPlayingIndex`).
- `maybeTrimFeedWindow()` runs once per page append (not per scroll event —
  it's a no-op until the feed is `FEED_WINDOW_BEHIND + FEED_WINDOW_AHEAD +
  FEED_TRIM_SLACK` posts long) and, when the user has scrolled
  `FEED_WINDOW_BEHIND` posts past the front of the list, calls
  `trimFeedFront()`.
- `trimFeedFront()` drops the trimmed posts from `currentFeedPosts` /
  `feedCards`, remaps every surviving `FeedRow.postIndex`, and applies the
  change via a `DiffUtil.Callback` that matches `ROW_POST` rows by `reelId`
  (`applyFeedItemsDiff`) instead of hand-rolled position math.
- Only the front (already-scrolled-past posts) is ever trimmed. The ahead
  side is deliberately left alone — it's already bounded by
  `FEED_FETCH_BATCH` per page, and trimming it would create a permanent gap
  once `oldestFeedTimestamp` has moved past it.
- Scroll-up safety: `frontTrimHighWaterTimestamp` records the newest
  timestamp ever trimmed. Scrolling back within `paginateThresholdPx` of the
  top re-triggers `reloadTrimmedFrontPosts()`, which re-fetches exactly that
  range from Firebase and `prependFeedPosts()` puts it back via the same
  DiffUtil path.

## Not changed
- `HomeFeedWindowManager` (bitmap/detach virtualization for the old
  NestedScrollView-era feed) is untouched — orthogonal concern.
- Pagination (`loadMoreFeedPosts`) and the real-time new-posts listener are
  untouched; `reloadTrimmedFrontPosts` intentionally ignores anything newer
  than `frontTrimHighWaterTimestamp` so the two don't double-fetch the same
  posts.
