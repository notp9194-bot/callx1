# v930 — Bubble colour: ultra perf pass

1. No buffer rows on Apply / theme flip: `notifyVisibleRangeChanged(payload, 0)` for PAYLOAD_BUBBLE_COLOR + PAYLOAD_THEME
   (selection keeps the +-8 buffer). Cached off-screen holders self-heal in `MessagePagingAdapter.onViewAttachedToWindow`
   via `VH.colorEpoch` (one int compare; canvas rows -> `refreshBubbleColors()`, legacy rows -> full bind).
2. Single theme epoch: `BubbleColorStore.sEpoch` = colour version + uiMode in one static int (bumped by `apply()` and
   `syncUiMode(uiMode)` when uiMode really changed). Per-bind `getConfiguration().uiMode` reads removed from
   `MessageBubbleCanvasView` (`currentUiNight`, `resolveReplyColors`, `sharedBubbleDrawable`) and `ChatThemeManager.getTextColor`.
   `syncUiMode` is called from: CallxApp.onConfigurationChanged, ChatActivity / GroupChatActivity (onCreate, onResume,
   onConfigurationChanged — BEFORE adapter notify), the canvas view constructor + its onConfigurationChanged.
3. Prewarm: `MessageBubbleCanvasView.prewarmBubbleColors()` builds the 4 tick variants (on/off bubble) + sent accent memo
   on a background thread right after Apply (BubblePreviewActivity) and at cold start when a custom colour is saved (CallxApp).
5. Baseline profile: BubbleColorStore, sharedBubbleDrawable, getTickBitmap(+Slow), refresh*, prewarm, onViewAttachedToWindow added.
Skipped on purpose: item 4 (shared reply palette, tiny gain).

# v931 — Bubble colour: advanced perf pass
- Per-side epochs (`sEpochSent` / `sEpochRecv`, `sVersionSent`): changing one side repaints only that side's rows; tick bitmaps
  flush only when the SENT colour changes; applying an identical colour touches nothing.
- `ensureBubblePool` + `prewarmBubblePool(activityCtx)`: shared bubble drawables rebuilt on Apply (main thread), not in the first bind.
- HSV picker: vertical shader + hue colours built once, listener coalesced to <= 1 call/frame (final colour flushed on ACTION_UP).
- `BubbleColourBenchmark` (macrobenchmark) + trace sections `BubbleColor#refresh`, `BubbleColor#prewarmPool`.
- NOT done: fill-as-separate-layer and "skip text layer re-record". Reply card / tick / link-card colours are all derived from the fill and
  baked into the same cached RenderNode/Picture (incl. SHARED_NODES keyed by drawable), so almost every colour change re-records anyway;
  the refactor is high-risk for a one-moment gain.

# v932 — Bubble colour: last small wins
- `BubbleColorStore.lin()` = 256-entry lookup (no Math.pow in luminance/contrast).
- `apply()`: SharedPreferences write moved to one background thread (memory state still updated synchronously).
- Colour screen hex field: no String.format per drag callback; setText only when the text changed.
- `prewarmBubblePool(this)` in ChatActivity / GroupChatActivity onCreate: shared bubble drawables ready before the first bind.
- Still NOT done: fill-as-separate-layer (see v931 notes).
