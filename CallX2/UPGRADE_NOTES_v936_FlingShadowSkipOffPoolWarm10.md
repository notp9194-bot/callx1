# v936 — from the v935 report (default vs custom colour, vivo V2338)
Colour feature cost is negligible (Apply 1.2 ms, 8 tick bitmaps 2.3 ms, once). Colour-independent findings fixed:
1. `setRecyclerViewScrollState` fling shadow-skip disabled: 255 `invalidate() on CLEAN bubble` per run came from the idle
   re-invalidate of every visible row (re-record each). The shadow is one drawRoundRect, so skipping it saved ~nothing.
   (BubbleTailDrawable keeps its code; sFastScroll is simply never true.) Expect: fewer `dirty` misses, higher hit-rate, fewer re-records.
2. Idle pool warm-up 6 -> 10 holders per hot type (low-RAM stays 6). Report: onCreateViewHolder x453, avg 1.7 ms, 139 over 2 ms, 17 GCs / 888 ms.
Not changed (seen in report): one-off `file` bind 17 ms (first file bubble), video records/bind 1.41 (target 1.0).
