# v236 — PollRenderer zero-alloc FontMetrics

## What changed
`PollRenderer.draw()` (poll bubble content — runs on every `onDraw()` /
scroll frame a poll bubble is on screen) was still calling the no-arg
`Paint#getFontMetrics()` overload, which allocates a fresh
`Paint.FontMetrics` object on every call:

- `pollHeaderLabelPaint.getFontMetrics()` — "POLL" label
- `pollChipPaint.getFontMetrics()` — called **twice** per frame (chip
  height calc, then chip baseline calc)
- `pollSubtitlePaint.getFontMetrics()` — subtitle line
- `pollOptionPctPaint.getFontMetrics()` — called **once per option**,
  inside the option-row loop (n allocations per frame for an n-option poll)
- `pollFooterPaint.getFontMetrics()` — "N votes" footer

Total: `5 + (n_options - 1)` allocations every single draw() call for any
poll bubble on screen, i.e. every scroll/fling frame — same GC-pressure
bug class already fixed in `MessageBubbleCanvasView.onMeasure()`, just
still live at draw time in this renderer.

## Fix
- Widened 3 existing scratch fields on the host
  (`pollHeaderFmScratch`, `pollSubtitleFmScratch`, `pollFooterFmScratch`)
  from `private` to package-private so `PollRenderer` can reuse the exact
  same instances `onMeasure()` already populates.
- Added 2 new package-private scratch fields on the host:
  `pollChipFmScratch`, `pollOptionPctFmScratch`.
- Replaced every no-arg call with `paint.getFontMetrics(scratch)`.
- Hoisted the option-row percentage-label metrics fetch **out of the
  loop** — it's the same Paint/text-size for every row, so it's now
  fetched once per frame instead of once per option.
- `pollChipPaint`'s metrics are now fetched once and read twice (height
  calc + baseline calc) instead of two separate allocating calls.

## Verified
- Only remaining `getFontMetrics()` hits in `PollRenderer.java` are the
  `// PERF: was ...` comments — no live no-arg call sites left.
- Brace/paren balance unchanged from baseline (same pre-existing paren
  delta in `MessageBubbleCanvasView.java`, `PollRenderer.java` balanced
  at 0/0).

## Result
Poll bubble draw path is now zero-alloc for FontMetrics, matching the
rest of `MessageBubbleCanvasView`'s onMeasure()/onDraw() paths fixed in
v235.
