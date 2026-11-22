# v827 — Bubble record-ahead + legacy direct draw + view flags
1. Record-ahead: binds that happen outside a layout/scroll pass (RecyclerView prefetch) call `MessageBubbleCanvasView.schedulePreRecord(wSpec)` (end of `bindCanvasMessage`). A static main-looper IdleHandler (2 ms budget per idle callback, queue cap 12) measures+lays out the detached view at the RV item width and records its RenderNode, so the first on-screen draw is a cache hit. API 29+ only. Kill switch: `PRERECORD_ENABLED`. Animated/progress states are skipped (`shouldSkipFullCache()`).
2. API 23-28: plain-text bubbles draw directly (no Picture record+replay). Kill switch: `LEGACY_DIRECT_DRAW_PLAIN_TEXT`.
3. Constructor flags: setSaveEnabled(false), setForceDarkAllowed(false) (API29), setDefaultFocusHighlightEnabled(false) (API26).
4. Stats: `renderCacheStats()` now shows pre-recorded count too; Perfetto section `Bubble.preRecord`.
Not compiled offline — run CI. If spec mismatch ever occurs, RV just re-measures and onDraw records as before (no correctness impact).
