# v941 — Bubble colour: Undo / Redo

Preview screen top-right now has **Undo ↶ · Redo ↷ · Reset ↺**.

- `BubbleColorDraft`: snapshot history (max 50) of (sent, recv). `commit()` after each change;
  HSV drag within 600 ms collapses into one step. History resets in `loadFromStore()`.
- `BubbleColourActivity`: calls `commit()` on preset pick, HSV change, recent pick, hex set;
  `onResume()` repaints picker from the draft (after undo/redo in preview).
- `BubblePreviewActivity`: undo/redo buttons dim + disable when nothing to undo/redo.
  Reset is now itself an undoable step.

## v941 perf (bubble colour screens)
- `BubbleHsvPickerView`: SV square = solid hue + 2 static gradients (no ComposeShader, no per-frame
  Shader allocation on hue drag).
- `BubbleColourActivity`: Custom pane (HSV + EditText + recents) built lazily on first Custom-tab show;
  `BubblePreviewCanvasView.prewarmFonts()` runs off-thread in onCreate.
- `BubblePreviewCanvasView`: footer strings / text widths / theme colours cached once (no String concat or
  measureText in onDraw).
