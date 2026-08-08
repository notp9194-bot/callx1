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

---

## v244 — Ultra-smooth ("buttery") Home feed scrolling

Same file. Four safe, real, additive performance changes — **no view
recycling / RecyclerView migration** (see "not done" note below for why).

1. **Hardware-layer scrolling.** The scroll listener now promotes the
   feed's scrolling content (`feedScrollContentRoot`, the LinearLayout
   NestedScrollView wraps) to `LAYER_TYPE_HARDWARE` the instant motion
   starts (`beginFeedScrollLayer()`), and drops it back to
   `LAYER_TYPE_NONE` ~180ms after scrolling settles
   (`endFeedScrollLayer()`). While active, the GPU composites the whole
   feed as one cached texture per frame instead of Android re-drawing
   every avatar/thumbnail/text view individually on each pixel of scroll —
   this is the standard Android technique for eliminating scroll jank on
   view-heavy content, and it's the single biggest lever available without
   touching the underlying view architecture.

2. **Cheaper image decode.** New `FEED_IMAGE_OPTS` (RGB_565 format +
   `dontAnimate()`) applied to the feed post thumbnail and avatar Glide
   loads. RGB_565 halves per-pixel memory vs. the default ARGB_8888, and
   skipping Glide's cross-fade transition removes an extra composited
   animation that was firing on every image load, including ones that
   land mid-scroll.

3. **Prefetch ahead of the fold.** `prefetchUpcomingFeedMedia()` — hooked
   into the same scroll listener at a slightly further-out threshold than
   the infinite-scroll fetch trigger — pre-warms Glide's cache for the
   next few not-yet-rendered thumbnails and calls the existing
   `ReelVideoPreloader.preloadFrom()` for upcoming video, so by the time a
   newly-loaded page's cards actually scroll into view their media is
   already cached instead of popping in raw.

4. **Staggered page append.** `appendFeedPage()` (infinite-scroll page
   load) now renders one card per animation frame via `postDelayed(...,
   16L)` instead of inflating + dispatching Glide/ExoPlayer work for an
   entire batch (up to `FEED_FETCH_BATCH` = 25 items) synchronously —
   mirrors the staggering the very first page already used, so hitting
   the pagination trigger mid-fling doesn't cause a visible spike.

### What "ultra advanced" would actually require, and why it isn't in this patch
Real Instagram-grade smoothness at large scroll depth comes from
**bounded view recycling** — a `RecyclerView` that only ever keeps a
small window of ViewHolders inflated, no matter how far the user has
scrolled. This Home feed is built on a plain `LinearLayout` inside a
`NestedScrollView`, which keeps every card ever rendered permanently
inflated and attached — memory and view count both grow unbounded as
infinite scroll (added in v243) appends more pages.

Migrating that to RecyclerView is the correct long-term fix, but it's a
genuinely invasive rewrite here: `feedCards` indices, the shared
`ExoPlayer` attach/detach logic, per-card `ViewPager2` photo pagers, and
every click handler in `addFeedPostCard` (~700 lines) are all currently
written assuming direct LinearLayout child views. Doing that migration
correctly — and I mean correctly, not "looks right" — needs an actual
build + on-device scroll test to catch ViewHolder-recycling bugs (stale
video attachment, wrong like/follow state on a recycled row, etc.), which
isn't possible in this sandbox (no Android SDK/Gradle network access). I
did not want to hand you an unverified RecyclerView rewrite of a
production feed and call it done.

**If/when you want that migration**, the safest path is:
`RecyclerView` + `ListAdapter`/`DiffUtil` with a single `ViewHolder` type
for the reel-post row, a second type for the inline "Suggested for you"
row, keep the same shared `ExoPlayer` attached/detached via
`onViewAttachedToWindow`/`onViewDetachedFromWindow`, and reuse
`rankScore()` / pagination / real-time-listener logic from v243 as-is —
none of that needs to change.
