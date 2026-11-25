# v855 — Album cell badges + group sender avatar drawn live (on top of v854)

- **Album per-cell badge** (dim + circle + download/retry icon + progress/spinner ring): `MediaGroupRenderer.drawCellBadgesLive()`
  from the overlay layer. Progress ticks, spinner frames, pending/downloading/failed flips, upload state changes and
  `markGroupCellDownloaded` now invalidate only the cell / grid rect. Master "Download N photos" pill still lives in the
  cached node (only its on/off transition re-records).
- `shouldSkipFullCache()` now always false: no bubble type bypasses the outer cache any more. Inner group cache disabled.
- **Group sender avatar** moved to the overlay layer: bitmap arrival and the run-tail show/hide flip redraw only the 28dp avatar rect.
- Unchanged (content really changes): bind, bitmap swaps (placeholder->thumb->full), reply/pin/edit/forward set, read-more,
  link preview, theme change, bubble-tail flip, first-time reactions/seen-by (height change).
Built without Gradle/SDK — run a normal build.
