# v242 — Fixed: tapping "Read more" on a caption didn't actually show the full text

## Bug
`computeSizeSignature()` — the string `requestLayoutIfSizeChanged()` uses to
decide whether a real relayout is needed — only appended the "Read more"
expand state (`isTextExpanded`) inside the **plain-text bubble** branch.
The `isMedia` (image/video caption) and `isMediaGroup` (media-group caption)
branches never included it.

So tapping "Read more" on a long caption:
1. `setTextExpanded(true)` flips the flag and calls `requestLayoutIfSizeChanged()`.
2. That recomputes the signature — for a caption, the signature only
   depends on the caption text + aspect ratio/count, which hadn't changed.
3. Signature unchanged → `requestLayout()` never fires → `onMeasure()`
   never reruns → the bubble never grows and the caption's `textLayout` /
   `groupCaptionLayout` never gets rebuilt past the truncated version.
4. `invalidate()` still fired, so the "Read less ▲" label itself flipped
   correctly (that's drawn straight from the flag) — but the actual caption
   text underneath stayed clipped at the old collapsed height. Looked like
   the tap "did nothing" for the text itself.

## Fix
Added `isTextExpanded` to `computeSizeSignature()` for both the
`isMedia`/`mediaHasCaption` and `isMediaGroup`/`groupHasCaption` cases,
same as the plain-text branch already did. Expand/collapse now correctly
triggers a real relayout, so the bubble grows and the full caption renders.

## File touched
- `MessageBubbleCanvasView.java` — `computeSizeSignature()`
