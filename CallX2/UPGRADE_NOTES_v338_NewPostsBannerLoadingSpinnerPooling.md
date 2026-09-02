# v338 — New Posts banner pill + Loading spinner: view pooling

File touched: `feature-reels/src/main/java/com/callx/app/feed/HomeFragment.java`

## What changed
These two were previously left unpooled on the assumption that a
single-TextView/ProgressBar rebuild was too cheap to matter — closer look
showed both rows can rebind repeatedly (banner: every time the pending
count changes; loading: every time `showFeedLoading()` toggles it back
on for a pagination/refresh cycle), each rebind doing a full
`removeAllViews()` + fresh View + (banner) a fresh click listener. Applied
the same "cache the View, refresh the data" rule already used for
`ROW_SPONSORED`/`ROW_SUGGESTED_CREATORS`/`ROW_SUGGESTED_REELS`:

- New `NewPostsBannerHolder` — the pill `TextView` (background, padding,
  layout params) and its click listener are built **once** in
  `onCreateViewHolder`; `bindNewPostsBannerHolder()` only updates the
  pill's text on every rebind. The click listener never referenced
  bind-time data to begin with, so registering it once at creation
  changes nothing about its behavior.
- New `LoadingRowHolder` — the spinner `ProgressBar` is built once in
  `onCreateViewHolder`; `onBindViewHolder` for `ROW_LOADING` is now a
  no-op (same treatment as `VT_HEADER`/`VT_FOOTER`), since the row has no
  per-bind data at all.
- `bindNewPostsBannerContent(ViewGroup)` is retired in favor of
  `bindNewPostsBannerHolder(NewPostsBannerHolder)`.

## Not touched
`ROW_EMPTY` / `ROW_LOAD_MORE_FOOTER` — still built fresh at bind time;
genuinely simple one-TextView/one-ProgressBar states with no repeated-
rebind pattern like the banner/loading rows have. Everything else on Home
(post action bar, inline Suggested Creators/Reels/Sponsored rows,
Trending/Friends Activity/Continue Watching header rails, Stories tray,
Notes tray) already pooled — see v323/v333/v334/v337.
