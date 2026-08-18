# v214 — Status Sticker Size Control (Countdown/Music/Quiz/Question)

## What's new
Creators can now resize any status sticker two ways, combined:

1. **Pinch-to-resize** — 2-finger pinch on a placed sticker scales it live
   (clamped 0.5x–2.0x). Works alongside the existing 1-finger drag-to-move.
2. **Small / Medium / Large buttons** — tapping a sticker (a quick tap, not a
   drag) pops a small S/M/L bar just above it; tapping a size button animates
   the sticker to that preset (0.7x / 1.0x / 1.4x). Tapping elsewhere on the
   canvas dismisses the bar.

The final size is baked into the sticker's saved JSON (`"scale"` field) at
post time, so the **viewer sees the sticker at the exact size the poster set**
— `StatusStickerOverlayView.fromJson()` reads it back and applies it
automatically; no viewer-side changes needed.

## Files touched
- `feature-status/.../stickers/StatusStickerOverlayView.java`
  - `applyScale()` / `animateToScale()` — clamp + apply size, `toJsonWithScale()`
    — bakes the current scale into the sticker's JSON
  - `fromJson()` now restores a saved `"scale"` value automatically
  - `attachDragToParent()` — added a `ScaleGestureDetector` for pinch, plus tap
    detection (`OnStickerTappedListener`) that fires only on a genuine tap
    (no drag/pinch happened), used to trigger the S/M/L bar
- `feature-status/.../compose/NewStatusActivity.java`
  - `showStickerSizeBar()` / `hideStickerSizeBar()` — builds and positions the
    S/M/L control row
  - `buildStickersJson()` now reads the **live sticker views** (via
    `toJsonWithScale()`) instead of the static `addedStickerJsons` strings
    captured at add-time — this also fixes a pre-existing bug where a sticker
    removed via long-press stayed in the posted JSON since the list was never
    updated on removal.
