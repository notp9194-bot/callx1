# v260 — Draw tool: advanced brushes + shared color sheet + teardrop width slider

## What changed
Chat media editor (`MediaEditActivity` → pencil/draw mode) now matches the
requested screenshots:

**Screenshot 1 — brush tool row** (`activity_media_edit.xml` drawToolsRow, Row 1)
- Replaced the old fixed 10-color swatch row with: a rainbow **color circle**
  button + 6 brush-type buttons — **Pen, Highlighter, Ink, Crayon, Neon, Marker**
  — plus a "more" (+) button.
- Each brush type renders differently (`DrawOverlayView`):
  - Pen — original solid round stroke (unchanged fast path, no perf regression).
  - Highlighter — flat cap, wide, translucent.
  - Marker — flat cap, bold, semi-opaque.
  - Ink — round cap + soft blurred "wet ink" bleed halo.
  - Crayon — grainy stippled texture sprinkled along the stroke.
  - Neon — blurred glow halo behind a bright core line.
  - Selected brush is highlighted with `bg_media_edit_brush_active` (grey pill,
    matches the screenshot's active-state look, distinct from the green
    `bg_media_edit_toolbtn_active` used elsewhere in the editor).

**Screenshot 1 — color circle → color sheet**
- Tapping the rainbow circle now opens `RainbowStripColorPickerBottomSheet`
  (the shared "core" picker already used by highlight rings / reel strips),
  instead of a fixed palette. Picking a color updates the draw color, the
  circle's center swatch, and the width-slider accent.

**Screenshot 2 — teardrop brush-width slider**
- New `TeardropWidthSlider` view (feature-chat/controllers) — a vertical
  guitar-pick-shaped slider (thick rounded top → fine point at bottom),
  overlaid on the right edge of the media canvas while draw mode is active.
  Drag up = thicker brush, drag down = thinner. Replaces the old horizontal
  SeekBar + preview dots row.
- Range kept identical to the old SeekBar (2–44dp) so existing stroke data /
  bake math (`DrawOverlayView.drawStrokes`) needed no changes.

## Files touched
- `DrawOverlayView.java` — added `BRUSH_*` constants, `Stroke.brushType`,
  `setActiveBrushType()`, and a shared `renderStroke()` used by both the live
  preview and the static full-res bake (`drawStrokes`) so exported images
  always match what was drawn on screen.
- `RainbowColorDotView.java` *(new)* — rainbow-ring color button.
- `TeardropWidthSlider.java` *(new)* — the width slider from screenshot 2.
- `activity_media_edit.xml` — new tool row, simplified eraser row, added
  `drawWidthSlider` to `mediaContainer`.
- `MediaEditActivity.java` — `setupDrawTools()` rewired for the new row,
  brush selection, color-sheet integration, and slider hookup;
  `enterDrawMode()`/`exitDrawMode()` show/hide the slider.
- New drawables: `ic_brush_pen/highlighter/ink/crayon/neon/marker.xml`,
  `ic_media_edit_plus_tool.xml`, `bg_media_edit_brush_active.xml`.

## Notes
- No new Gradle dependencies; `feature-chat` already depends on `:core`
  (where `RainbowStripColorPickerBottomSheet` lives).
- Old `drawColorRow`, `sbBrushSize`, `dotBrushSmall/Large` fields/IDs were
  removed — nothing else in the codebase referenced them (verified by grep).
