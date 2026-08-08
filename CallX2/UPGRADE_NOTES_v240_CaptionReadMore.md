# v240 — Read More / Read Less for Media Captions

## What changed
Chat text bubbles already had a WhatsApp-style "Read more ▼ / Read less ▲"
collapse for messages longer than `MAX_COLLAPSED_LINES` (10 lines). This
upgrade extends the exact same mechanism to the caption system:

- Single image caption (`bindMedia`)
- Single video caption (`bindVideo`)
- Media-group (multi-image/video grid) caption (`bindMediaGroup`)

Long captions now truncate to 10 lines with an ellipsis + tappable
"Read more ▼" link; tapping expands to the full caption ("Read less ▲"
to collapse back). Expand state persists correctly through RecyclerView
recycling (tracked by message id in `MessagePagingAdapter.expandedMessageIds`,
same Set already used for text messages).

Reel-share caption (the bottom-gradient overlay on a shared reel card) is
NOT included — it's a fixed-size bubbleless card, always capped at 2 lines,
same as before.

## Files touched
- `MessageBubbleCanvasView.java`
  - `onMeasure()`: `hasLongText` now reset at the top of every measure pass
    (prevents a recycled view from carrying stale collapse state from a
    previous, differently-typed bind).
  - Media-caption branch (`isMedia`) and media-group-caption branch
    (`isMediaGroup`): same `MAX_COLLAPSED_LINES` truncation + rebuild logic
    the plain-text branch already had, plus the extra `readMoreRowH` folded
    into `captionBlockHeight`/`groupCaptionBlockHeight` so the bubble grows
    to fit the strip.
  - New shared `drawReadMoreStrip(Canvas, x, rowTop)` — the draw+hit-rect
    logic was previously inlined in the plain-text draw path; extracted so
    `MediaRenderer` and `MediaGroupRenderer` can call it too.
- `MediaRenderer.java` — draws the strip under a long single-image/video
  caption; clears `readMoreRect` on the captionless path.
- `MediaGroupRenderer.java` — same, for the group-caption path.
- `MessagePagingAdapter.java` — new `wireCaptionReadMore(h, cv, msgId)`
  helper (same scroll-anchor-preserving `notifyItemChanged` + 
  `scrollToPositionWithOffset` behaviour the text branch already used),
  called after `bindMediaGroup()`, `bindMedia()`, and `bindVideo()`.

## Notes
- Tap target, colors (gold on sent / blue on received), and row height are
  all identical to the existing text-message strip — no new constants.
- No caption previously had a length cap on the single-image/video path at
  all (it rendered every line unbounded); it now caps at 10 lines like
  everything else in the chat.
