# v940 — call-entry pill: measure uses the width caches
onMeasure's call-entry branch did 4x Paint.measureText per measure; drawCallEntry already had TextWidthCache for icon/label/dot.
Measure now uses the same caches (+ a new one for the time) -> unchanged rows do 0 measureText in measure and draw.
Perf report context: call_entry bind avg 61-75 us (4-6 rows per run) — the call pill was already ~0.3% of bind time; draw is baked in the cached
RenderNode, FontMetrics are scratch-reused, bubbleless (no shadow/tail). Remaining jank is elsewhere (create/GC/gpu).
