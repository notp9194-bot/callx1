# v169 — Community Feed: Bounded/Windowed Query + Local Load-More

Follow-up to v168 (Canvas draw-path allocations + Glide sizing). This round
fixes a data-layer bottleneck that's invisible on a small test community but
gets worse the longer/more active a real community is — exactly the
"community chat performance" scenario.

## Problem found

`CommunityDao.observeFeed()` / `observeAnnouncements()` were unbounded:
`SELECT * FROM community_posts WHERE communityId=? AND isAnnouncement=?
ORDER BY createdAt DESC` — no LIMIT.

Room's `LiveData` is **invalidation-based**: any write anywhere in
`community_posts` (one member liking one post, one poll vote, one comment
count bump) re-runs **every currently-observed query against that table**
and re-emits its **entire** result set — not just the changed row. Every
single tap from any member in the community was re-querying and re-diffing
the whole locally-synced post history.

That history also only ever grows: `syncRecentPosts()` fetches the latest 20
posts from Firebase on each screen open and inserts them, but nothing ever
prunes older rows already in Room. A community active for months can have
thousands of rows on-device even though the feed only ever displays the
newest ones — so the "one like re-diffs everything" cost keeps climbing over
the community's lifetime, independent of anything fixed in v168.

## Fix

**1. Bounded live window** — `CommunityDao.observeFeedWindowed()` /
`observeAnnouncementsWindowed()` cap the live query at `WINDOW_SIZE` (40)
most-recent posts. Every write still invalidates the query, but now it only
ever re-fetches/re-diffs 40 rows, flat, regardless of community history size.

**2. Local "load more" for the rest** — `CommunityDao.getOlderPostsSync()`
uses the same keyset/cursor technique as the chat module's
`MessageKeysetPagingSource` (`WHERE createdAt < :cursor`, answered directly
by the existing `(communityId, isAnnouncement, createdAt)` index — no
OFFSET, so cost doesn't grow with scroll depth). `CommunityFeedFragment` now
has a `RecyclerView.OnScrollListener` that pages in 30 more posts from the
already-synced local Room cache whenever the user scrolls within 6 rows of
the bottom — pure local read, no network.

**3. Fragment-side merge** — `latestWindow` (live, auto-updating) and
`olderExtra` (paged in locally, static until it would re-enter the live
window) are merged and submitted to the adapter together. A fresh live
emission always wins for any post id it contains, so there's never a stale
copy shown next to a live one.

## Left unbounded, intentionally

`CommunityRepository.observeFeed()`/`observeAnnouncements()` (unbounded)
are kept as-is and still used by `CommunitySearchResultsFragment` (searches
across all posts) and `CommunityAnalyticsDashboardActivity` (aggregates
stats over all posts) — those two genuinely need the full set. Only the
actual scrolling feed UI (`CommunityFeedFragment` / its
`CommunityAnnouncementsFragment` subclass) was switched to the windowed
variant.

## Compatibility

No entity/schema change, no Room migration needed — only new `@Query`
methods on the existing `community_posts` table using its existing index.
`minSdk 23` note: avoided `List.removeIf()` (API 24+) in the fragment's
merge logic in favor of a manual `Iterator` removal.
