# Media viewer dock animation — perf pass v2 (v278)

Continues the `AVATAR_ZOOM_TELEGRAM_DOCK_v277_PERF2.md` pass. That pass
fixed `MediaSwipeReplyCloseHelper` itself (shared by both the avatar
viewer and this chat media viewer), so the live-drag/spring-back
`invalidateOutline()` throttling already benefits `MediaViewerActivity`'s
swipe-to-close for free — no separate change needed there.

This pass covers what's specific to `MediaViewerActivity.java`.

## What was found

`DialogFullscreenHelper`'s avatar dock animation (`closeToSource` /
`animateOpenFromSource`) explicitly caches the animating photo into a
hardware layer (`LAYER_TYPE_HARDWARE`) for the ~300ms it's driving
translation + scale + alpha + outline-clip together every frame, then
releases it once the animation settles.

`MediaViewerActivity`'s own `animateCloseToSource()` /
`animateOpenFromSource()` — the original dock animation the avatar one
was modeled on — never did this. It went un-cached: every one of the
~18 frames re-drew (and, for a video page, re-composited) the full
content view from scratch while also re-clipping its rounded-corner
outline.

This matters most for **close paths that don't start with a swipe drag**
— the ✕ button, back-press, and tap-outside-to-close — because in the
swipe-close case `MediaSwipeReplyCloseHelper`'s own gesture handling
already applies a hardware layer during the drag, which happens to
still be active when the dock animation takes over. Button/back-press
closes skip that gesture entirely, so without this fix they ran the
whole dock animation completely uncached.

## What changed

- `animateCloseToSource()`: hardware-layers `activeDragView` (the
  image, video, or gallery pager currently on screen) right before the
  `ValueAnimator` starts; releases it in `onAnimationEnd`, right before
  the Activity finishes anyway.
- `animateOpenFromSource()`: same treatment on open — layered right
  before the dock-up animation starts, released once it settles into
  idle (pinch-zoomable / swipeable) state.
- Applies uniformly to all three content types this method drives:
  single image (`PhotoView`), single video (`PlayerView`), and the
  grouped-media gallery (`ViewPager2`) — same call site, no branching
  needed.

## Net result

Chat media viewer's open/close dock animation — for every entry path
(swipe, ✕ button, back, tap-outside), for image, video, and gallery —
now runs on a cached GPU layer for its whole duration instead of only
when a swipe drag happened to precede it. Same animation, same timing;
fewer full content re-draws per frame.
