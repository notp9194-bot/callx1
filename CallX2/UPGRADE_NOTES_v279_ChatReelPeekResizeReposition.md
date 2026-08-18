# v279 — Chat-only reel-peek mini player: 40% smaller, anchored above the share card, no blur backdrop

## What changed

On the chat screen only, when a reel-share bubble has been on screen for 3s
and the auto "peek" mini video player opens:

1. The mini player card is now **40% smaller** (scaled to 60% of the shared
   default 331×475dp card).
2. It now opens **anchored directly above the reel-share bubble**, instead
   of dead-center of the screen.
3. The blurred chat-screen backdrop behind the card is now **skipped** — the
   chat screen stays clear/unblurred behind the mini player (just the normal
   scrim dim, same as before).

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

## Files touched

- `feature-reels/.../ReelPeekPreviewController.java`
  - Added a 7-arg `show(..., Integer cardWidthPx, Integer videoHeightPx,
    boolean anchorAboveSource)` overload. `anchorAboveSource` defaults to
    `false` on every existing overload, so no other caller's behavior
    changes.
  - When `anchorAboveSource` is true, the popup's content view (normally
    laid out dead-center via the shared XML's `layout_gravity="center"`) is
    nudged up above `sourceRect` (the long-pressed/dwelled source view's
    on-screen rect, already captured for the existing dock-close animation)
    via `translationX`/`translationY`, computed once the card's real
    (possibly size-overridden) dimensions are known after its first layout
    pass. Horizontally centered on the source, clamped to stay fully
    on-screen near the top/edges of the visible chat list.
  - The pre-existing per-call size override mechanism
    (`overrideCardWidthPx`/`overrideVideoHeightPx`, already used by the Home
    feed's suggested-reels long-press) is reused as-is — no changes there.
  - Fast-switch path (`switchTo()`, e.g. a second reel-share bubble dwelling
    to 3s while the first peek is still open) re-applies the offset for the
    new source rect immediately instead of waiting on a layout pass.
  - `captureAndBlurBackdrop(blurBg, popupWindow)` — the call that screenshots
    and blurs whatever's behind the popup into `iv_peek_blur_bg` — is now
    skipped when `anchorAboveSourceForThisShow` is true. `iv_peek_blur_bg`
    simply stays at its XML-default `alpha="0"` and is never populated, so
    the chat screen behind the card stays fully clear/unblurred. The
    `SCRIM_MAX_ALPHA` (0.55) dim view on top is untouched — that's the
    normal peek darkening, not the blur, and still applies.

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
- The scrim dim (`SCRIM_MAX_ALPHA` = 0.55 black overlay) — still applies on
  chat too; only the blurred-screenshot layer underneath it was removed.
