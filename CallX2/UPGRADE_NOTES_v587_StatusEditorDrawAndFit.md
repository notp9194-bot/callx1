# v587 — Status editor: draw tools, scroll-lock, canvas fit, sharp zoom

## What changed
1. **Scroll lock**: draw strokes and text-overlay drag now call
   `requestDisallowInterceptTouchEvent` (NestedScrollView can no longer steal them).
   Story canvas is capped to ~80% screen height (narrowed + centred, still 9:16),
   so the page no longer needs to scroll while editing.
2. **Sharp zoom/export**: photo preview decodes up to 1800x3200 (`AT_MOST`, no hardware
   bitmap) instead of view-size, so 5x pinch and the 1080px export stay sharp.
3. **Draw tool button**: Draw rail icon turns white while draw mode is on (was no visual change).
4. **Draw tool**: colour (7), brush size S/M/L, eraser, undo/redo, Done. Bar is hidden in
   export snapshots; draw mode auto-exits when leaving the Edit step. Second finger no
   longer makes the stroke jump.

## Files
- feature-status/.../compose/NewStatusActivity.java
- feature-status/.../compose/StatusDrawOverlayView.java (rewritten)

## Verification
- Android build/tests not run (no SDK here). Please build and test on device:
  draw + eraser, undo/redo, zoom 5x then Save, small-screen canvas fit.
