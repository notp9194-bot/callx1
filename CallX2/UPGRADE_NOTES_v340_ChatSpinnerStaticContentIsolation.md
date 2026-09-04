# v340 — Chat bubble: isolate indeterminate spinner from static content (media-group + file bubble)

## Problem
While an indeterminate (progress-unknown) download/upload spinner was
animating, `drawProgressRing()`'s `postInvalidateOnAnimation()` forced a
**full `onDraw()` re-execution of the whole bubble** — grid cells, borders,
captions, shaders, everything — at up to ~30fps (already throttled from
60fps in an earlier pass), for as long as any cell/file was in that state.

`MediaRenderer` (single image/video bubble) already had this solved: its
static content is recorded once into a cached `Picture`/`RenderNode` and
only the live arc redraws every frame (`spinnerHandledSeparately` param +
`drawIndeterminateSpinnerOnly()`). `MediaGroupRenderer` (album grid) and
`FileBubbleRenderer` never got the same treatment — every frame of an
indeterminate spinner on a grid cell or a file download redrew the entire
bubble from scratch.

## Fix — same split, now on all three renderers
- **`MediaGroupRenderer`**: `draw(canvas, vPad)` → `draw(canvas, vPad,
  spinnerHandledSeparately)`. When `true`, an indeterminate cell's dim
  overlay + badge circle still draw (static), only the moving ring is
  skipped. New `hasActiveIndeterminateSpinner()` (any visible, non-overlay,
  pending, downloading cell with `progress < 0`) and
  `drawIndeterminateSpinnersOnly(canvas)` (redraws just the arc for each
  such cell, at `groupRects[i].centerX/Y()`).
- **`FileBubbleRenderer`**: same shape — `draw(canvas,
  spinnerHandledSeparately)`, action-button circle stays static, ring
  skipped when indeterminate + separately-handled. New
  `drawIndeterminateSpinnerOnly(canvas)`, using geometry cached in
  `lastActionCx/Cy/R` from the last `draw()` call.
- **`MessageBubbleCanvasView`**:
  - `onDraw()`'s `indeterminate` (drives `skipFullCache` for the *outer*
    full-bubble cache) now also covers media-group and file-bubble states,
    not just single media.
  - New `drawMediaGroupWithOptionalCache()` / `drawFileBubbleWithOptionalCache()`,
    mirroring the existing `drawMediaWithOptionalCache()` exactly (Picture
    fallback, RenderNode path on API 29+, size-change guard). They reuse
    the same `cachedMediaPicture`/`cachedMediaRenderNode`/`staticPictureDirty`
    fields `drawMediaWithOptionalCache()` already used — safe because
    exactly one of `isMedia`/`isMediaGroup`/`isFileBubble` is ever true for
    a given bound view, so there's no collision between them.
  - `drawBubbleContent()` now dispatches to these wrappers instead of
    calling `mediaGroupRenderer.draw()` / `fileBubbleRenderer.draw()`
    directly.
  - `staticPictureDirty = true` added at every group/file setter that can
    change what the cache would capture: `bindMediaGroup`,
    `setGroupDownloadGate`, `setGroupCellDownloading`,
    `setGroupCellProgress`, `markGroupCellDownloaded`, `bindFile`,
    `setFileDownloadState`, `setFileCached` — mirrors the existing
    media-side call sites (`bindMedia`, `setMediaDownloadGate`, etc.).

## Net effect
A media-group cell or file bubble with unknown download progress now
redraws only the live arc each frame; the rest of the bubble (thumbnails,
borders, captions, text, footer) is replayed from a cached Picture/
RenderNode instead of re-walking every draw call — same win
`drawMediaWithOptionalCache()` already delivered for single-image/video
bubbles, now consistent across all three media-bearing bubble types.

## Files touched
- `conversation/canvas/MediaGroupRenderer.java`
- `conversation/canvas/FileBubbleRenderer.java`
- `conversation/canvas/MessageBubbleCanvasView.java`

No layout/XML/behavior changes — pure draw-path isolation, verified by
brace/paren balance checks on all four touched files (no Android SDK
available in this environment to run a full Gradle build — recommend
building via the existing GitHub Actions CI before merging).
