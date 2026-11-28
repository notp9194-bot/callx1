# v935 — Bubble Perf menu: row creation, GC and fling/idle split (diagnostics only, no behaviour change)
New report section "Row creation / GC (v935)":
- onCreateViewHolder count/total/avg/max + count over 2 ms (row inflate/Paint allocation spikes).
- GC during run: count, blocking count, time (ART runtime stats) -> explains `unknownDelay`.
- Frames split by RecyclerView scroll state (idle / drag / fling): n, over budget, over 2x, avg ms.
Why: frame stage `animation` in a fling is mostly RecyclerView bind/create work (ViewFlinger runs in the animation
callback), so pausing animations won't fix it. The 9-14 ms `animation` spikes need to be split into create vs bind vs GC first.
Hooks: BubblePerfStats.create() in MessagePagingAdapter.onCreateViewHolder, setScrollState() in ChatActivity's unified scroll listener.
