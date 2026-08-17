# Media Viewer — Advanced/Polished Telegram-style dock animation (v275)

Builds on `MEDIA_VIEWER_TELEGRAM_CLOSE_v222.md` (which added the basic
"shrink into the thumbnail" open/close). This pass makes the *feel* of it
much more advanced and premium — closer to Telegram's real interactive
dismiss instead of a plain translate+fade.

## 1. Live interactive drag (`core/utils/MediaSwipeReplyCloseHelper.java`)

Previously: swipe just set `translationY` + a linear alpha fade, and
nothing happened until you lifted your finger.

Now, on every `ACTION_MOVE`, in addition to following the finger 1:1
vertically:
- The content **progressively scales down** toward a rubber-band floor
  (62%) as drag distance increases.
- The content's **corners progressively round off** in sync with the
  scale-down (0 → 20dp on-screen radius), so it visually morphs toward
  "thumbnail shape" *during* the drag, not just at the very end. The
  radius math compensates for the live scale so the on-screen radius
  grows linearly with drag distance regardless of current zoom (see the
  class doc / `applyLiveDragFrame()` for the derivation).
- The content itself fades slightly (up to ~22%) — the background scrim
  still carries most of the dimming.
- **Chrome views** (top bar, select-toolbar, page counter — wired via
  the new `setChromeViews(...)`) now fade live with the same drag
  fraction, instead of only reacting after release.
- The **background scrim** dims on an eased curve (`t^0.72`) instead of
  linearly, which reads as more atmospheric/premium.

## 2. Velocity-based fling-to-dismiss

`VelocityTracker` now runs for the whole gesture. On release:
- Distance threshold (100dp) still closes the viewer, same as before.
- **New:** a fast enough flick (≥1100dp/s) in a consistent direction
  closes the viewer even if the finger didn't travel the full 100dp —
  matches Telegram/Instagram's "throw to dismiss" behavior.
- The release velocity is passed through to the caller
  (`Callback.onSwipeDownClose(float velocityY)`, was previously
  parameterless) so `MediaViewerActivity` can factor it into how fast the
  final dock-to-thumbnail animation plays (see below) — a hard flick
  now visibly "throws" the photo into the thumbnail slot faster than a
  slow drag-and-release.

## 3. Physics-based spring snap-back

If the threshold isn't crossed, the previous behavior was a flat 180ms
linear tween back to center. Now it's an `androidx.dynamicanimation`
`SpringAnimation` (`DAMPING_RATIO_LOW_BOUNCY` / `STIFFNESS_MEDIUM`) seeded
with the release velocity, so a fast partial-drag-then-release still
feels physically continuous instead of snapping onto a fixed-duration
tween. Scale/radius/alpha/scrim are all derived from the spring's live
`translationY` value each frame (not a second, separately-timed
animation), so nothing can visually drift out of sync with anything
else.

Added dependency: `androidx.dynamicanimation:dynamicanimation:1.0.0` in
`core/build.gradle` (already used elsewhere in the app, e.g.
`feature-reels`/`feature-chat`).

## 4. Rebuilt dock-to-source / expand-from-source animation
(`app/activities/MediaViewerActivity.java`)

`animateCloseToSource()` and `animateOpenFromSource()` were rewritten
around a single `ValueAnimator` fraction driving *every* property
(position, scale, corner radius, content alpha, chrome alpha,
background scrim) together, instead of a `ViewPropertyAnimator` for the
view plus a separate, independently-timed `ValueAnimator` for the
background — guarantees they can't drift apart mid-animation.

- **Continuity from the live drag:** the close animation now reads its
  *starting* scale/position/radius straight off the view's actual
  current transform (which may already be mid-drag-gesture state) rather
  than assuming scale=1/radius=0 — so releasing mid-swipe continues
  smoothly into the dock animation with zero visual jump.
- **Velocity-aware duration:** `velocityAdjustedDuration()` shortens the
  close animation (300ms → down to a 170ms floor) proportionally to how
  hard the user flung it, so a hard flick reads as a fast "throw" and a
  gentle release reads as a slower, deliberate close.
- **Exact corner-radius match:** the final docked radius is
  `MessageBubbleCanvasView.MEDIA_CORNER_RADIUS_DP` (18dp) — the *same*
  constant the chat bubble itself uses — so the image doesn't just
  overlap the thumbnail's rect, it visually rounds off to match the
  bubble's actual corner rounding as it lands.
- **New easing:** both directions use a Material "emphasized" cubic
  bezier (`PathInterpolator(0.2f, 0f, 0f, 1f)`) instead of a plain
  `DecelerateInterpolator` — a more premium, intentional-feeling motion
  curve.
- **Open direction mirrors it exactly:** starts fully rounded (18dp) at
  thumbnail scale/position, unrounds to square as it expands to
  full-screen, chrome eases in slightly after content starts moving
  (never leads) — matching Telegram's actual open timing.

## 5. Video needs `TextureView`, not `SurfaceView`
(`app/res/layout/activity_media_viewer.xml`)

Important correctness fix uncovered while building the corner-radius
clip: a `SurfaceView`'s content is composited by the system straight to
the window, bypassing the normal per-view canvas pipeline — so
`View.setClipToOutline()`'s rounded-corner clip (and, on some OEM skins,
scale/alpha) would **not** actually apply to video content during the
dock animation; square corners would poke through the rounded overlay.
Fixed by setting `app:surface_type="texture_view"` on the single-video
`PlayerView` — `TextureView` draws through the ordinary view canvas
pipeline, so it fully respects the same transforms as the image path.

**Known remaining gap:** the grouped-media gallery's per-page video
`PlayerView`s (`GalleryPagerAdapter`, built programmatically) are still
plain `SurfaceView`-backed — `PlayerView` only reads `surface_type` from
an XML-inflated attribute set, not via any runtime setter, so switching
them would need those pages to inflate from a small XML layout instead
of `new PlayerView(ctx)`. Practical effect: opening a gallery of mixed
photos/videos and swiping to close still docks correctly for the active
page's *position/scale*, but a video page's corners may not visibly
round off mid-animation the way an image page's do. Not fixed in this
pass — flagging for a follow-up if it's noticeable in practice.

## Net result

Same wiring as before (single-image, single-video, GIF, and
grouped-media-grid open paths all still supply a `srcRect` the same
way) — only the *quality* of the animation changed. Nothing needed to
change at any call site beyond `MessagePagingAdapter` /
`MediaGroupLayoutHelper`, which don't need any changes at all for this
pass.
