# v367 — Canvas onDraw() Zero-Alloc: last 3 leaks closed

`onBindViewHolder()` → `bindCanvasMessage()` was already clean (no per-bind
`new` beyond the deliberate small `StringBuilder` in
`requestLayoutIfSizeChanged()`, kept on purpose to avoid layout thrash).

The real leak was in `MessageBubbleCanvasView.onDraw()`: 3 call sites still
used the no-arg `Paint.getFontMetrics()`, which allocates a fresh
`FontMetrics` object on every single invocation. `drawFooter()` and the
reactions badge were already fixed earlier (`footerFmScratch`,
`reactionsTextFM`) — these three were missed:

- `drawCallEntry()` — 4 allocations per draw (icon/label/dot/time paints),
  fired every frame for every visible call-log row during a fling.
- `drawCornerExpiryPill()` — 1 allocation per draw, every visible
  view-once/expiring card.
- `drawBigReactionBadge()` — 1 allocation per draw, every bubble carrying a
  big reaction badge.

## Fix

Same pattern as `footerFmScratch`: one `final Paint.FontMetrics` scratch
field per paint, filled in place via `paint.getFontMetrics(scratch)`
instead of the allocating no-arg overload. Zero heap churn now on all of
`onDraw()`'s FontMetrics reads.

New fields: `callEntryIconFmScratch`, `callEntryLabelFmScratch`,
`callEntryDotFmScratch`, `callEntryTimeFmScratch`, `cornerExpiryFmScratch`,
`bigReactionFmScratch`.

No behavior change — same metrics, same layout, same visuals. Pure GC
pressure removal during scroll/fling on chat threads with call-log rows,
expiring messages, or big reaction badges on screen.
