# v826 — Bubble paint: advanced pass
1. Display list survives detach: `onDetachedFromWindow()` no longer discards the RenderNode / marks dirty. Item-view-cache views re-attach and replay the cached bubble. GPU memory freed in new `releaseRenderCaches()`, called from `MessagePagingAdapter.onViewRecycled()`.
2. No more HW layer on top of RenderNode: `bindCanvasMessage()` always LAYER_TYPE_NONE (reactions/image/video/reel included). HW layer only during the send spring animation.
3. Search highlight moved to the dynamic overlay layer (`drawSearchHighlightOverlay`), `setSearchHighlight()` uses `super.invalidate()` so cached bubble stays valid. Highlight is now translucent yellow (0x99FFEB3B) because it draws over glyphs; set alpha back / revert if you prefer the old look.
4. `hasOverlappingRendering()` returns false (no offscreen saveLayer on alpha < 1).
5. `Trace` section "Bubble.record" + `MessageBubbleCanvasView.renderCacheStats()` (hit/miss counters) for Perfetto / logcat verification.
Not compiled here (no Android SDK offline) — run CI build.
