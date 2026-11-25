# v863 - Chat 3-dot menu -> "📊 Bubble Perf" (on-device numbers, no adb)

Use: chat -> 3-dot -> "📊 Bubble Perf" (starts collecting) -> scroll / fling / send / receive in the chat
-> 3-dot -> "📊 Bubble Perf" again -> Copy -> paste to Claude. "Restart" resets the counters.

Collected (only while collecting; one volatile read otherwise):
- Bubble.record count / total / avg / max, cache hit-rate, preRecord count (MessageBubbleCanvasView)
- onMeasure count / total / avg / max
- onBindViewHolder (full) count / total / avg / max
- Frames via Window FrameMetrics (API 24+): P50/P90/P95/P99/max, over-budget %, avg layout/draw/sync ms

Files: core/.../debug/BubblePerfStats.java (new), ChatActivity (menu handler + dialog + stop in onDestroy),
chat_menu.xml (new item), MessageBubbleCanvasView + MessagePagingAdapter (hooks).
