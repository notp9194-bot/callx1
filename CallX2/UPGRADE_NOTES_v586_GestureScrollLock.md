# v586 — Keep Status Media Gestures Active

## What changed
- Status photo-editor touch sequences now call
  `requestDisallowInterceptTouchEvent(true)` on the ancestor chain while a
  finger/pinch gesture is active.
- This prevents the enclosing `NestedScrollView` from taking vertical
  `ACTION_MOVE` events away from photo drag/resize.
- Scrolling is re-enabled when the gesture ends or is cancelled; after a
  two-finger pinch, the lock remains active until the remaining finger lifts.

## Verification
- Android build and tests were not run, per request.
