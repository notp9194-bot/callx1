# v279 — Chat-only reel-peek mini player: 40% smaller, anchored above the share card, no blur backdrop

## What changed

On the chat screen only, when a reel-share bubble has been on screen for 3s
and the auto "peek" mini video player opens:

1. The mini player card is now **40% smaller** (scaled to 60% of the shared
   default 331×475dp card).
2. It now opens **anchored directly above the reel-share bubble**, instead
   of dead-center of the screen.
3. The blurred chat-screen backdrop **and** the dim scrim behind the card
   are now both **skipped** — the chat screen stays fully clear behind the
   mini player, not blurred and not darkened.

Every other place the same peek popup is used — UserReelsActivity's reel
grid (long-press) and SoundDetailFragment's mini player — is untouched:
same 331×475dp size, same dead-center position, same everything.

## Why it's scoped to chat only

`feature-chat` can't depend on `feature-reels` directly (`feature-status`
already depends on `feature-chat`, so the reverse dependency would create a
cycle). The existing `ReelSharePeekBridge` in `feature-chat` reaches the
single shared `ReelPeekPreviewController` implementation in `feature-reels`
through reflection instead. That bridge is the *only* caller that now passes
the new size/position overrides — every other caller (in `feature-reels`
itself) keeps calling the plain, unmodified `show()` overloads.

## Fix (this pass) — the card wasn't actually anchoring above the bubble

The first version of this waited for a `ViewTreeObserver` layout callback,
then nudged the (already dead-center) card via `translationX`/`translationY`
deltas. In practice that callback could fire after the position had already
settled with no further layout pass to catch, so the card silently stayed
at its original centered spot — visually indistinguishable from "not
working".

Replaced with a deterministic approach: `applyChatAnchorPosition()` computes
the card's exact on-screen top/left from values that are already known
up-front — `sourceRect` (the bubble's on-screen rect) plus the card
width/video height in px (the same override values used for the 40%-smaller
sizing) — and writes them straight into `peekContent`'s
`FrameLayout.LayoutParams` (`gravity = Gravity.NO_GRAVITY`,
explicit `leftMargin`/`topMargin`) before the popup is ever measured or
laid out. No layout-pass race, no drift from repeated translation nudges.

## Files touched

- `feature-reels/.../ReelPeekPreviewController.java`
  - Added a 7-arg `show(..., Integer cardWidthPx, Integer videoHeightPx,
    boolean anchorAboveSource)` overload. `anchorAboveSource` defaults to
    `false` on every existing overload, so no other caller's behavior
    changes.
  - When `anchorAboveSource` is true, `applyChatAnchorPosition()` replaces
    the popup content view's shared centered `FrameLayout.LayoutParams`
    (normally `layout_gravity="center"` from the XML) with an explicit
    top/left position directly above `sourceRect` (the dwelled-on bubble's
    on-screen rect, already captured for the existing dock-close
    animation), horizontally centered on it — computed up-front from known
    values, applied before layout, clamped to stay fully on-screen near the
    top/edges of the visible chat list.
  - The pre-existing per-call size override mechanism
    (`overrideCardWidthPx`/`overrideVideoHeightPx`, already used by the Home
    feed's suggested-reels long-press) is reused as-is — no changes there.
  - Fast-switch path (`switchTo()`, e.g. a second reel-share bubble dwelling
    to 3s while the first peek is still open) re-applies the offset for the
    new source rect immediately instead of waiting on a layout pass.
  - `captureAndBlurBackdrop(blurBg, popupWindow)` — the call that screenshots
    and blurs whatever's behind the popup into `iv_peek_blur_bg` — is now
    skipped when `anchorAboveSourceForThisShow` is true. `iv_peek_blur_bg`
    simply stays at its XML-default `alpha="0"` and is never populated.
  - The `SCRIM_MAX_ALPHA` (0.55) dim-fade-in on `view_peek_scrim` is now
    also skipped for the same flag — it stays at its XML-default
    `alpha="0"` too, so the chat screen behind the card renders fully
    clear (no blur, no dim). It's still there and still tap-to-dismiss,
    just invisible.

- `feature-chat/.../ReelSharePeekBridge.java`
  - Computes `cardWidthPx`/`videoHeightPx` as 60% of the shared 331/475dp
    default, converted to px using the source view's display density.
  - Calls the new 7-arg `show()` overload via reflection with
    `anchorAboveSource = true`.
  - Falls back to the old plain 4-arg `show()` (centered, default size) if
    the 7-arg overload isn't found on the classpath, so the peek never
    silently breaks.

## Not changed

- `popup_reel_peek.xml`'s default `331dp`/`475dp` card size — still the
  default for every caller that doesn't pass overrides.
- UserReelsActivity's reel grid long-press peek — still gets the blurred
  backdrop, still centered, still default size.
- SoundDetailFragment's mini player — same, untouched.
- The 3s auto-trigger timing itself (`MessageBubbleCanvasView.
  scheduleReelPeekPreview()` — unchanged).
