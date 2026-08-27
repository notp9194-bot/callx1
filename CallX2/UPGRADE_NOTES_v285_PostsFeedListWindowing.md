# v285 — Post Feed: List Cap / Windowing

Same class of fix as v280 (`HomeFragment`'s feed windowing), applied to
`PostsFeedActivity`.

## Problem
`posts` only ever grew — `prependBeforeBatch()` added rows, nothing ever
trimmed. On a 100+ photo profile, every `ReelModel` (plus its v284 live
Firebase count-listener) stayed resident in memory for the entire scroll
session, no matter how far past it the user had scrolled.

## Fix
- `originalReelIds` keeps the full, order-preserved id list this screen
  was launched with; `posts` is now only ever a **window** into it.
  `windowStartOffset` tracks which original index `posts.get(0)` maps to.
- `maybeTrimOrReloadPostWindow()` runs on every scroll tick (cheap —
  no-op until the list is longer than `POST_WINDOW_BEHIND` (15) +
  `POST_WINDOW_AHEAD` (30) + `POST_TRIM_SLACK` (10)):
  - **Trim front** — rows scrolled well past get removed
    (`trimPostFront`), their v284 count-listener detached first via
    `holderBoundTo()`, `windowStartOffset` advances.
  - **Trim back** — rows not yet reached, far below the visible window,
    get removed too (`trimPostBack`) — unlike Home's feed, this screen's
    "ahead" side isn't bounded by per-page fetch size (it loads the
    whole after-range up front), so it needs trimming on both ends.
  - **Reload before/after** — scrolling back toward a trimmed edge
    re-fetches that range by id from `originalReelIds` (`reloadPostsBefore`
    reuses the existing `prependBeforeBatch` path; `reloadPostsAfter`
    appends) and stitches it back in.

## Files touched
`feature-reels/src/main/java/com/callx/app/profile/PostsFeedActivity.java`
