# v459 — Reel comments: swipe-to-reply speed

- Reply now fires on finger RELEASE (first non-active onChildDraw), not in
  clearView() after the spring-back (was ~250-300ms late + a posted frame).
  clearView() kept as fallback.
- onChildDraw: Paint / color / ic_reply drawable created once (was per frame:
  new Paint, getColor, getDrawable().mutate()).
- Arm distance 72dp -> 56dp; row rubber-bands after 56dp, capped at 72dp visual.
  (Old clamp left a dead zone during spring-back from large raw dX.)
- Flick arms the reply: >=28dp and >=600dp/s (own velocity tracking).
- getSwipeEscapeVelocity = MAX: a fast fling no longer bypasses the 2f threshold
  and really dismisses the row (onSwiped is a no-op, row would stay off-screen).
- getAnimationDuration = 120ms (spring-back).
- Parent requestDisallowInterceptTouchEvent once dX > 8dp (bottom sheet / pager
  can't steal the gesture); released on lift.
- Ignores rows that are still recovering from a previous swipe (dragVh guard).

Files: feature-reels/.../comments/ReelCommentFragment.java (attachSwipeToReply)
