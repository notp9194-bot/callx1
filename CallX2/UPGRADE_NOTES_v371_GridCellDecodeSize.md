# v371 — Media-group grid cells: real per-cell decode size instead of a flat 240×240

## The problem

Every image cell in a `multi_media` bubble's grid (`MessagePagingAdapter`,
the canvas-rendered path behind `MediaGroupLayoutHelper`'s WhatsApp-style
layout rules) was decoded with a hardcoded `.override(240, 240)` —
regardless of how big that cell actually renders on screen. The grid's
real per-cell size varies a lot by item count (see
`MessageBubbleCanvasView`'s `GROUP_*` dp constants):

| items | layout            | real cell size        |
|-------|-------------------|------------------------|
| 2     | side-by-side pair | 118dp square           |
| 3     | 1 top + 2 bottom  | top 240×140dp, bottom two 116dp square |
| 4     | 2×2               | 118dp square            |
| 5+    | dense 3×3         | **78dp square**         |

So a 5+ item grid was decoding at ~2-3x the pixels it will ever display
(78dp slot vs. a 240px decode) — wasted native-heap memory and slower
decode on every image in every dense grid, compounding with scroll
speed. Meanwhile the bigger pair/3-item/2×2 slots (116-140dp) could
under-sample and look soft on high-density phones, since 240px isn't
tailored to any of them either — it was just a guess that happened to
roughly fit the old single-image case it was copied from.

This is the same class of bug `thumbPx()` / `gifStickerPx()` /
`seenThumbPx()` already fixed for other bubble types in this file — it
just hadn't been applied to the media-group grid cells yet.

## The fix

Added `groupCellPx(ctx, total, index)` in `MessagePagingAdapter`: given
the group's total item count and a cell's index (the only case where
index matters is the 3-item layout, since its top cell isn't the same
size as the two squares below it), it returns the real px target for
that cell, with the same 15% headroom margin the other density-aware
helpers in this file use, floored at 60px for very low-density devices.

The dp constants (`GROUP_PAIR_CELL`, `GROUP_THREE_TOP_W/H`,
`GROUP_THREE_BOT`, `GROUP_GRID2_CELL`, `GROUP_GRID3_CELL`) are mirrored
locally rather than exposed from `MessageBubbleCanvasView` — same
pattern as the existing `SEEN_*_DP_MIRROR` constants — since that class
lives in a different package (`.conversation.canvas`) and those fields
are intentionally package-private.

Wired into all three grid-decode call sites:

- The bulk per-cell bind loop's cached-file branch and remote/thumb-url
  branch (both previously `.override(240, 240)`).
- `downloadGroupCell()` — the "tap one cell's download badge" path,
  which now also takes the group's `total` item count so it can size
  its post-download decode the same way.

Bitmap pool/cache keys now include the target px size
(`path@WxH` / existing `poolKey(url, w, h)`) instead of a bare path or a
constant `(240, 240)`, since the same file can now legitimately decode
to different sizes depending on which grid layout it appears in.

## Net effect

- Dense 5+ item grids: ~2-3x less memory per cell and faster decode,
  with no visible quality loss (78dp slot doesn't need more).
- Pair / 3-item / 2×2 layouts: sharper thumbnails on high-density
  phones, since the decode now actually targets their real (bigger)
  slot instead of undershooting it.
- No behavior change for single-image bubbles or any non-grid path —
  this only touches `multi_media` grid cell decode sizing.
