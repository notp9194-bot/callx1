# v927 — Bubble colour (2 screens) + chat perf bits

Entry: chat → customise sheet → "Bubble colour". Screen 1 `BubbleColourActivity` (Default presets / Custom HSV),
screen 2 `BubblePreviewActivity` (live preview, Apply, reset). Global, same pair for day + night; slot 0 = theme default.

Perf design
- `core/utils/BubbleColorStore`: static ints + `sVersion`; prefs read once, `apply()` async; text colour derived once per apply.
- Hot path cost = one int compare: `MessageBubbleCanvasView.sharedBubbleDrawable` (pool rebuilt on version change) and
  `ChatThemeManager.getTextColor` (cache keyed by version). Chats repaint only visible rows (`notifyThemeChanged`) on resume.
- UI is code-built (no XML inflate); swatches = 1 canvas view per grid, picker = 1 canvas view, preview = 1 canvas view
  (`BubblePreviewCanvasView`, real `BubbleTailDrawable`, zero alloc in onDraw). No Glide/network/RecyclerView/elevation.

Also in this zip: v926 (px radius cache, fling shadow skip), bubble corners 12dp, reply-thumb perf (v923/924).
