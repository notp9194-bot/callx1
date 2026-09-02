# v339 — Empty-state text + Load-more footer spinner: view pooling

File touched: `feature-reels/src/main/java/com/callx/app/feed/HomeFragment.java`

## What changed
Last two rows on Home's fresh-rebuild list. Both are fully static (fixed
"No posts yet" string, a bare spinner) but were still going through
`container.removeAllViews()` + a brand-new `TextView`/`ProgressBar` on
every single rebind (e.g. every time the feed empties out again, or every
time `ROW_LOAD_MORE_FOOTER` is re-added for another pagination page).
Same "cache the View, refresh the data" rule as `ROW_LOADING`:

- New `EmptyRowHolder` — the "No posts yet" `TextView` is built once in
  `onCreateViewHolder`; `onBindViewHolder` for `ROW_EMPTY` is now a no-op.
- New `LoadMoreFooterRowHolder` — the footer `ProgressBar` is built once
  in `onCreateViewHolder`; `onBindViewHolder` for `ROW_LOAD_MORE_FOOTER`
  is now a no-op.
- `onCreateViewHolder`'s `default` branch (still returns a bare
  `SimpleRowHolder`) is now unreachable in practice — every `FeedRow.type`
  constant has its own explicit case — but left in place as a fallback.

## Not touched
Nothing left to pool on Home — post action bar, inline Suggested
Creators/Reels/Sponsored rows, Trending/Friends Activity/Continue
Watching header rails, Stories tray, Notes tray, New Posts banner pill,
and the Loading spinner are all pooled now (v323/v333/v334/v337/v338).
