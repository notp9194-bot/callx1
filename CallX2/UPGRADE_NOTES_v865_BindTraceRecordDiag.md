# v865 — Bind sub-section traces + re-record diagnostics + preRecord backstop

Based on v864. Not compiled here (no javac) — if GitHub build errors, paste the log.

## Why
Bubble Perf (vivo V2338, DEBUG): Bubble.record = 153 ms / 49 s (~0.1 ms per frame) -> not the bottleneck.
Real costs: `onBindViewHolder` avg 2.5 ms / max 66 ms, and RenderThread stages (cmdIssue, gpu, unknownDelay).
`preRecord count=0` => record-ahead (v827) never produced a single cached node; every first draw recorded inside the frame.

## Changes
1. **BubblePerfStats (core)** — new report blocks
   - `-- bind sections --`: bind.pre / bind.spacing / bind.canvas, and inside canvas: typeBind, reply, reactions/pinned/fwd, avatar, seenBy, tail (n / total / avg / max).
   - `miss reason`: dirty / sizeChanged / noDisplayList for every Bubble.record.
   - `preRecord pipeline`: sched, queueFull, run, staleSkip, earlySkip, alreadyCached, err, fallbackDrain -> tells exactly where record-ahead dies.
   - `invalidate() on CLEAN bubble`: top callers (Class.method:line) that re-dirty an already cached bubble = the generator of re-records.
2. **MessagePagingAdapter** — `BubblePerfStats.t()/sec()` around the bind phases (zero cost when collection is off: one volatile read).
3. **MessageBubbleCanvasView**
   - preRecord backstop: besides the idle handler (which rarely fires mid-fling) a static main-Handler drain runs 24 ms after scheduling, 2 ms budget per run, between frames (not in onDraw).
   - `runPreRecord` now skips an attached view whose layout is still pending (stale geometry would have been cached as clean).
   - counters for every early-return in `runPreRecord`; miss-reason + dirtier capture are only active while "Bubble Perf" collection is on.

## Not changed (on purpose)
- `setShadowLayer` (9 sites) is only on reaction/reel/contact text paints and `clipPath` only on the reply thumbnail — plain text bubbles never touch them, so removing them would change visuals for no gain on this scenario.
- Late-invalidate guards: setters (reactions, pinned, forward, avatars, seen-by) already skip same-state and use dirty-rect invalidate (v856-v861). The new "invalidate() on CLEAN bubble" list will show if any remaining caller is left.

## How to test
Release-like build if possible (R8 + baseline profile). Chat -> 3-dot -> Bubble Perf -> scroll/fling 30 s -> Bubble Perf -> Copy. Paste the whole report.
Look at: `bind sections` (which one has the big total/max), `preRecord pipeline`, `invalidate() on CLEAN bubble`.
