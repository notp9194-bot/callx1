# v639 — Status editor safe-area guide: vertical guide + viewer-accurate model

- Left/right guide added (dashed vertical lines + shaded side strips). `StoryFrameSpec.safeInsets()` returns top/bottom/left/right.
- Guide always draws a faint dashed safe rect + "Safe area OK" when nothing overlaps (was: nothing drawn).
- Viewer-accurate: real window size (WindowMetrics / getRealMetrics), window insets (status bar, cutout), card top gap 12dp
  and 64dp reply bar, same as StatusViewerActivity#setupEdgeToEdge.
- Header chrome height is measured by the viewer (`StoryChromePrefs.publishTop`) and read by the editor guide; falls back to
  `StoryFrameSpec.TOP_CHROME_IN_CARD_DP` until the viewer has been opened once.
- Bottom chrome (caption/seen-by/live comments) is dynamic in the viewer, so it still uses `BOTTOM_CHROME_DP`.
- Old `safeInsetsCanvasPx()` kept (unused) so existing tests still compile.
- Files: core/StoryFrameSpec, core/StoryChromePrefs (new), feature-status/StorySafeAreaView, viewer/StatusViewerActivity, StoryFrameSpecTest.

## v640 follow-up (items 5, 6, 7)
- Labels moved to `feature-status/res/values/strings_story_safe_area.xml` (story_safe_top / story_safe_bottom / story_safe_ok). Side strips are too thin for text, so side guides stay line-only.
- Tests: tall screen, wide screen, asymmetric cutout, isInsideSafe (+ rotation) added to StoryFrameSpecTest.
- Pinned guide (rail "Guide") is now subtle: faint dashed border + light shade (`StorySafeAreaView.setPinned`).
- Haptic CLOCK_TICK when text or a sticker crosses the safe-area edge (`StoryFrameSpec.isInsideSafe`,
  `StatusStickerOverlayView.setOnStickerDragListener`); guide also shows while dragging a sticker.
