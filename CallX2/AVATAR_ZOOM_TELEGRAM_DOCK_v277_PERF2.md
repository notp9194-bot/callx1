# Avatar zoom dock animation — perf pass v2 (v277)

Follow-up to `AVATAR_ZOOM_TELEGRAM_DOCK_v276.md`. That pass wired the
Telegram/Instagram-style circular dock animation into all 7 avatar-zoom
call sites and already carried a first "ultra-advanced" perf pass
(GPU layer caching during gesture + dock animation, epsilon-throttled
`invalidateOutline()` inside `setLiveCornerRadius`, decoded-drawable
reuse as an instant placeholder).

This pass targets `MediaSwipeReplyCloseHelper` — the shared gesture
engine every avatar dock **and** the chat media viewer swipe-to-close
both run on — and removes a real, measurable waste that the v276 pass
didn't touch: the *live drag* and *spring-back* code paths.

## What was wrong

`setLiveCornerRadius()` (used by the programmatic open/close dock
animators) already skipped `invalidateOutline()` for sub-pixel radius
moves. But the two paths that drive the *interactive* part of the
gesture — `applyLiveDragFrame()` (every `ACTION_MOVE`) and
`springBack()` (every physics-spring tick) — still called
`dragView.invalidateOutline()` **unconditionally, every single frame**,
including on devices with 90/120Hz touch sampling where this can fire
well above the actual display refresh rate.

Worse, for the avatar viewer specifically (`insetSquareClip = true`),
the LOCAL (pre-transform) radius is deliberately left untouched during
a drag — only `scale`/`translationY` change, and those are composited
transforms applied *after* the outline clip, not part of the outline
itself. That means the outline's geometry was **provably identical
every frame**, so invalidating it was pure waste with zero visual
effect — on every avatar tap-drag in the entire app.

## What changed

- Added `updateLocalRadius()` — a single choke point all three radius-
  changing call sites (`setLiveCornerRadius`, `applyLiveDragFrame`,
  `springBack`) now go through. `currentLocalRadiusPx` (used for
  animation-continuity math and `getCurrentOnScreenRadiusPx()`) always
  stays exact; the native `invalidateOutline()` call only fires once the
  local radius has moved ≥0.5px since the last time it was invalidated.
- For the avatar (`insetSquareClip`) case in `applyLiveDragFrame` and
  `springBack`: the `invalidateOutline()` call is removed **entirely**,
  not just throttled — the local radius never changes during that
  gesture, so there's nothing to re-clip.
- `configureIdleState()` / `resetVisualsInstant()` keep the new
  epsilon-tracker (`lastInvalidatedLocalRadiusPx`) in sync so the first
  frame of the *next* gesture still compares against a real baseline.
- Cached `density` as a field (set once in the constructor) instead of
  re-resolving `Resources → DisplayMetrics → density` inside `dp()` on
  every call — `dp()` runs multiple times per `ACTION_MOVE` frame.

## Net result

Every avatar-viewer drag (all 7 call sites) now does **zero** native
outline recomputes for its entire duration — previously one per touch
frame. The shared chat-media-viewer swipe (non-avatar, radius genuinely
animates) keeps correctness but skips the sub-pixel frames too. No
behavior or visual change — same animation, fewer wasted native calls
on the hottest path in the gesture.
