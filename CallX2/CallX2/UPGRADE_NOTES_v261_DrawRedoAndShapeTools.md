# v261 — Chat Media Editor: Redo + Shape Tools (Line / Arrow / Rectangle / Circle)

Fixes the two gaps in `MediaEditActivity`'s draw tool (`feature-chat`):

❌ Redo — only Undo existed → ✅ **Redo added**
❌ Shape tools (line/arrow/rectangle/circle) — only freehand → ✅ **4 shape tools added**

## Redo

- `DrawOverlayView` now keeps a `redoStack` alongside `strokes`.
- `undoLastStroke()` moves the popped stroke onto `redoStack` instead of
  discarding it.
- New `redoLastStroke()` pops it back onto `strokes`.
- The redo stack is cleared whenever a brand-new stroke starts
  (`ACTION_DOWN`), on `clearStrokes()`, and on `bindStrokes()` (switching
  thumbnail items) — standard undo/redo semantics, no redo across
  unrelated edits.
- New `canUndo()` / `canRedo()` + `OnStrokeChangeListener` callback so
  `MediaEditActivity` can dim/enable the Undo and Redo buttons live.
- UI: a **Redo** button (↪) sits next to **Undo** in the draw tools panel
  (`activity_media_edit.xml`).

## Shape tools

- `DrawOverlayView.Stroke` gained a `shapeType` field
  (`SHAPE_FREEHAND` / `SHAPE_LINE` / `SHAPE_ARROW` / `SHAPE_RECT` /
  `SHAPE_OVAL`). Freehand strokes behave exactly as before.
- Shape strokes store only a start + end point (drag bounding box) instead
  of a full point path, and are always drawn with a plain solid stroke in
  the active color/width (eraser mode doesn't apply to shapes).
- Touch handling: `ACTION_DOWN` records the start point; each
  `ACTION_MOVE` updates just the end point and forces a full repaint
  (shapes can shrink/move on every move, unlike freehand which only
  grows, so they can't use the additive fast path); `ACTION_UP` commits.
- `renderShapePrimitive()` draws Line / Arrow (with arrowhead) /
  Rectangle / Oval-Circle. It's shared by the live preview
  (`drawSingleStrokeOnOffscreen`) and the static full-res bake
  (`drawStrokes`, used on Send), so the exported image always matches
  what was shown while drawing — same pattern the existing brush
  rendering already used.
- UI: new shape-tool row in the draw panel — **Freehand** toggle +
  **Line / Arrow / Rectangle / Circle** buttons, right below the brush
  row. New vector icons: `ic_shape_freehand`, `ic_shape_line`,
  `ic_shape_arrow`, `ic_shape_rect`, `ic_shape_circle`.
- `MediaEditActivity.selectShapeType()` highlights the active tool and
  forwards it to the overlay, mirroring `selectBrushType()`.

## Files touched

- `feature-chat/.../DrawOverlayView.java`
- `feature-chat/.../MediaEditActivity.java`
- `feature-chat/src/main/res/layout/activity_media_edit.xml`
- `feature-chat/src/main/res/drawable/ic_shape_{freehand,line,arrow,rect,circle}.xml` (new)

No other module touched. Backward compatible — old `Stroke(...)`
constructors still work and default to `SHAPE_FREEHAND`, so anything
already serialized/in-memory as a freehand stroke is unaffected.
