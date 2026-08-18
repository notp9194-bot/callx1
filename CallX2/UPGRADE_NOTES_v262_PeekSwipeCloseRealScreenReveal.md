# v262 — Reel peek mini player: swipe-close now reveals the REAL screen, not a blurred one

Files touched:
- `core/src/main/java/com/callx/app/utils/MediaSwipeReplyCloseHelper.java`
- `feature-reels/src/main/java/com/callx/app/profile/ReelPeekPreviewController.java`

## The bug this fixes
`ReelPeekPreviewController`'s peek popup shows a blurred screenshot
(`iv_peek_blur_bg`) behind a dim scrim, captured the instant the peek
opens (Instagram-style backdrop). The swipe-up/down-to-close gesture
(shared `MediaSwipeReplyCloseHelper`, same class `MediaViewerActivity`
uses) was only ever fading the **scrim's** solid-color alpha as you
dragged — it never touched the blurred screenshot sitting behind that
scrim. So no matter how far you dragged, what became visible behind the
shrinking card was always the still-opaque **blurred** screenshot, never
the actual live `UserReelsActivity` grid / `SoundDetailFragment` screen
underneath — unlike `MediaViewerActivity`'s swipe-close (which has no
static backdrop image at all, so its reveal is the real screen by
construction).

## The fix
- **`MediaSwipeReplyCloseHelper`**: new `setExtraFadeViews(View...)` —
  any views passed here fade alpha 1→0 in perfect sync with the existing
  scrim dim, on the same eased curve, during both the live drag
  (`applyLiveDragFrame`) and the bouncy spring-back
  (`springBack`'s update listener) if the drag doesn't cross the dismiss
  threshold. Restored to alpha 1 in `resetVisualsInstant()` so an
  incomplete drag leaves the backdrop exactly as it was.
  `chromeViews`/other callers (`MediaViewerActivity`) don't call this, so
  `extraFadeViews` stays an empty array for them — zero behavior change
  there.
- **`ReelPeekPreviewController`**: passes the blurred backdrop
  (`blurBgView`) into `swipeHelper.setExtraFadeViews(blurBg)` right after
  building the swipe helper, so it now dissolves along with the scrim as
  the user drags — by the time the drag crosses the dismiss threshold,
  the blur is already fully gone and the real screen is what's showing.
  Also wired the same fade into `dismissAnimated()` (the dock-back-to-source
  animation that plays on scrim-tap / back-press / post-threshold swipe
  release) so a tap-to-close reveals the real screen exactly as smoothly
  as a swipe-close does, not just the drag path.

## Net effect
Long-press peek in `UserReelsActivity`'s grid and `SoundDetailFragment`'s
mini player now behaves exactly like `MediaViewerActivity`'s swipe-close:
the background screen gets progressively, smoothly visible as you drag —
and it's the actual real screen, not a blurred stand-in.

## Known limitations
- Authored and hand-reviewed for syntax (brace/paren balance confirmed on
  both files) but **not compiled** — no Android SDK/Gradle network access
  in this sandbox. Build locally before shipping
  (`./gradlew :core:assembleDebug :feature-reels:assembleDebug`).
