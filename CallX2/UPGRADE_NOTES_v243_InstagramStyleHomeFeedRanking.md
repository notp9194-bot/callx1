# v243 — Instagram-style Home Feed: Ranking, Infinite Scroll, Real-time, Inline Mixing

File touched: `feature-reels/src/main/java/com/callx/app/feed/HomeFragment.java`

## What changed, mapped to the 4 gaps

### 1. Feed structure — trending/suggested now mixed into the feed
- Added `insertInlineSuggestedCreatorsRow()` / `buildInlineSuggestedRow()`:
  every `SUGGESTED_EVERY_N_POSTS` (6) posts in **For You** mode, a
  "Suggested for you" creators row is inserted directly inside
  `containerFeed` — not a separate static section.
- **Following** mode is intentionally left as a pure reverse-chronological
  feed with no mixing, matching real Instagram: the explicit "Following"
  filter is chrono-only; ranking/mixing only happens in the default feed.
- The old standalone Trending / Friends Activity / Continue Watching /
  Suggested Creators rows still exist (nothing was deleted, no XML/layout
  risk) — they're supplementary rails, same as IG's Explore-adjacent
  surfaces, not the mechanism this task was about.

### 2. Loading — real infinite scroll, not a fixed one-shot load
- Removed the hard `Math.min(posts.size(), 10)` cap.
- Added cursor-based pagination: `loadMoreFeedPosts()` queries
  `orderByChild("timestamp").endAt(oldestFeedTimestamp - 1).limitToLast(FEED_FETCH_BATCH)`
  and appends via `appendFeedPage()`.
- Triggered from the existing `NestedScrollView` scroll listener once the
  user is within ~600dp of the bottom — no explicit "end of feed" state
  under normal use, same as IG.
- `feedHasMore` / `oldestFeedTimestamp` / `renderedReelIds` track pagination
  state and de-dupe; reset on mode switch and pull-to-refresh
  (`resetFeedPaginationState()`).

### 3. Ranking — composite score instead of one static sort
- New `rankScore(ReelModel, followedUids)` combines:
  - `trendingScore()` (existing engagement × recency decay),
  - a relationship/affinity boost for followed authors,
  - an approximate watch-time/interest proxy from `viewsCount` + `duration`.
- This is a heuristic stand-in for a trained ranking model — there's no
  client-side ML model here — but it does combine the same signal
  categories (engagement, watch time, relationship, recency) real
  ranking systems use, replacing the previous single `trendingScore()`-only
  sort in For-You mode. Following mode stays pure chronological by design.

### 4. Refresh — real-time background updates + pull-to-refresh
- `startRealtimeNewPostsListener()` attaches a `ChildEventListener` for
  posts newer than the newest one currently rendered.
- Instead of silently reflowing the feed (which would yank the user's
  scroll position and interrupt playback), new arrivals increment a
  counter and show a small "N new posts · Tap to refresh" pill pinned to
  the top of the feed (`showNewPostsBanner()`), Twitter/Instagram-style.
  Tapping it scrolls to top and reloads.
- Pull-to-refresh (`SwipeRefreshLayout`) is unchanged and still does a
  full manual reload at any time.
- Listener is torn down on `onDestroyView()`, mode switch, and refresh to
  avoid leaking `ChildEventListener`s across reloads.

## Known limitations / what this is *not*
- Not a trained ML ranking model — `rankScore()` is a heuristic, same
  category of signals, not the same fidelity as Instagram's real ranker.
- Pagination cursor is `timestamp`-only; doesn't yet account for posts
  written with identical timestamps (edge case, low collision risk given
  ms precision).
- This patch was authored and hand-reviewed for syntax (brace/paren
  balance, consistent method wiring) but **not compiled** — this sandbox
  has no Android SDK / Gradle network access. Build it locally
  (`./gradlew :feature-reels:assembleDebug`) before shipping, in case
  something in the surrounding module doesn't match assumptions made here
  (e.g. `FirebaseUtils.getReelsRef()` query indexing on `timestamp`).
