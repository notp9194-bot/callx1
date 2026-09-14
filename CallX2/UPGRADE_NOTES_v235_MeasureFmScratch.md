# v235 — onMeasure() FontMetrics zero-alloc pass

Follow-up to v234 (`drawReadMoreStrip` onDraw fix). That left ~15 no-arg
`getFontMetrics()` calls in `MessageBubbleCanvasView.onMeasure()` — lower
priority than onDraw (fires once per bind/relayout, not once per frame),
but still real allocation churn during fast scroll / bulk rebind, since
many rows get remeasured in a burst (paging, rotation, new-data arrival).

## What changed

All remaining `paint.getFontMetrics()` (no-arg, allocates a fresh
`Paint.FontMetrics` every call) replaced with `paint.getFontMetrics(scratch)`
(fills a reused instance in place — zero alloc), matching the pattern
already used for `footerFmScratch` / `callEntryIconFmScratch` / etc.

Covered sites, all in `onMeasure()`:
- pinned-label / group-sender / forwarded-label row
- view-once card (icon/label/sublabel/time)
- seen-card (icon/label/name/time)
- call-entry pill sizing (reuses the same scratch fields `drawCallEntry()`
  already fills at draw time — measure and draw never run concurrently on
  one view, so sharing costs nothing)
- poll header/subtitle/footer
- link-preview domain line
- reactions badge sizing (reuses the existing lazily-cached `reactionsTextFM`
  field instead of a new scratch — that paint's size never changes after
  init, so it's safe to compute once and share between the measure and
  draw call sites)

New scratch fields (all `private final Paint.FontMetrics`, next to the
existing `footerFmScratch` block): `pinnedLabelFmScratch`,
`groupSenderFmScratch`, `forwardedFmScratch`, `viewOnceIconFmScratch`,
`viewOnceLabelFmScratch`, `viewOnceSublabelFmScratch`,
`viewOnceTimeFmScratch`, `seenIconFmScratch`, `seenLabelFmScratch`,
`seenNameFmScratch`, `seenTimeFmScratch`, `pollHeaderFmScratch`,
`pollSubtitleFmScratch`, `pollFooterFmScratch`, `linkDomainFmScratch`.

## Result

Zero remaining no-arg `getFontMetrics()` calls anywhere in
`MessageBubbleCanvasView` — onDraw (v234) and onMeasure (this pass) are
both fully zero-alloc for font metrics now.
