# v260 — Instagram-style "Suggested reels" row in the Home feed

File touched: `feature-reels/src/main/java/com/callx/app/feed/HomeFragment.java`

## What this adds
Matches the screenshot reference: a **"Suggested reels"** header with a
kebab (⋮) menu, followed by a horizontally-scrollable row of tall
video-thumbnail tiles, mixed directly into the scrolling feed between
regular posts — the same interleaving pattern the existing v243
"Suggested for you" creators row already used, just for reel thumbnails
instead of creator avatars.

## How it works
- `SUGGESTED_REELS_EVERY_N_POSTS = 4` — every 4 rendered posts, in For-You
  mode only (Following stays pure chronological, same rule as the
  creators row). Deliberately offset from the creators row's `6` so the
  two don't always land on the same post — feels organic instead of
  mechanically alternating.
- `insertInlineSuggestedReelsRow()` fetches a candidate pool once per
  session (`orderByChild("viewsCount").limitToLast(20)`, most-viewed
  first) and caches it in `suggestedReelsPool`, same fetch-once/reuse
  pattern as `suggestedCreatorPool`.
- `buildInlineSuggestedReelsRow()` filters out reels already rendered in
  the feed (`renderedReelIds`) and your own reels, takes up to 8, and
  builds the row:
  - Each tile: 112×198dp `FrameLayout`, `CENTER_CROP` thumbnail (RGB_565
    decode, same memory-saving option used for feed thumbnails in v244),
    rounded via the existing `bg_speed_chip` background + `clipToOutline`.
  - A views-count pill (▶ 1.2K style, using the existing `formatCount()`
    helper) sits bottom-left with a text shadow for legibility over any
    thumbnail.
  - Kebab menu → "Not interested" dismisses just that row (removes the
    section view), same lightweight affordance as Instagram's.
- Tapping a tile opens `SingleReelPlayerActivity` with the **whole
  suggested pool** as `EXTRA_REEL_IDS` and that tile's index as
  `EXTRA_START_POSITION` — so swiping up/down from a suggested reel
  continues through the rest of the suggested set, exactly like tapping
  into Instagram's suggested-reels rail.
- Counter (`postsSinceSuggestedReels`) resets in the same three places
  the creators-row counter already resets: `resetFeedPaginationState()`,
  the initial `renderFeedPostsWithState()` render, and pull-to-refresh —
  so a fresh feed load doesn't inherit a stale count.

## Known limitations
- Candidate pool query is `viewsCount`-ordered only, no affinity/ranking
  weighting yet (unlike `FeedRankingEngine` used for the main feed posts)
  — same tier of heuristic as the trending rail (`loadTrending()`), not a
  personalized recommender.
- Authored and hand-reviewed for syntax (brace/paren balance confirmed:
  461/461, 2841/2841) but **not compiled** — no Android SDK/Gradle
  network access in this sandbox. Build locally
  (`./gradlew :feature-reels:assembleDebug`) before shipping.
