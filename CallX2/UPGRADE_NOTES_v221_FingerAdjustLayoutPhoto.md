# v221 — Layout picker: finger-adjust (pinch-zoom + drag-pan) each photo in its cell

`StatusLayoutPreviewView` cells used `ScaleType.CENTER_CROP`, which is a
fixed auto-crop with no user control over which part of the photo shows.

**Now:** each cell's ImageView uses `ScaleType.MATRIX`, driven by:
- `PhotoAdjust` (per-Uri: zoom `scale` 1x–3x, pan `panX`/`panY`) so a
  user's adjustment survives switching layout style (cells rebuild, but
  the map is keyed by photo Uri).
- `ScaleGestureDetector` for pinch-to-zoom.
- Manual one-finger drag tracking for pan, clamped so the photo always
  keeps covering the cell (can't pan past the edge and leave a gap).
- `applyPhotoMatrix()` recomputes the cover-fit + zoom + pan Matrix any
  time the gesture updates, or once Glide's `RequestListener.onResourceReady`
  fires and the cell has its real measured size.

Net effect: after picking photos and a layout style, the user can now
pinch/drag each photo inside its cell to reposition/zoom it exactly like
screenshot 2 — same as the earlier "Choose layout" preview, now
interactive.
