# v585 — Smoother Status Photo Gestures

## What changed
- Photo gestures now run on a full-canvas transparent layer, so touch
  coordinates stay stable while the photo is scaled or translated.
- Two-finger pinch is calculated from a stable gesture baseline and keeps the
  content under the pinch midpoint anchored as the fingers move.
- The canvas supports a wider `0.15x`–`5x` zoom range.
- Dragging remains available across the full story canvas, including when the
  foreground image has been zoomed down; switching from pinch to one-finger
  drag rebases the pointer to avoid a position jump.
- Kept the existing hardware-accelerated image layer and the current blurred
  backdrop/export pipeline.

## Verification
- Android build and tests were not run, per request.
