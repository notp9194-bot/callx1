# v377 — Sticky date header: throttle the auto-hide timer reset during fling

## Context
The sticky date chip's label update (`updateStickyDateHeaderLabel()`) was
already well optimized: position-unchanged fast path, a same-day label
cache that skips `SimpleDateFormat`, and a reused `Calendar` — plus
`setText()` is only called when the label content actually changed, so
there's no redundant TextView invalidate there.

## Gap
`handleStickyDateScrolled()` runs on every single `onScrolled` callback —
i.e. every frame during a fling, potentially 60-120 times/sec — and
unconditionally called `removeCallbacks()` + `postDelayed()` on every one of
those ticks just to push the 1200ms auto-hide timer forward. Each call is a
Handler message-queue scan-and-remove plus a fresh insert — real, if small,
main-thread work paid every frame for no visible benefit: the hide delay
only ever needs to land within roughly 1200-1600ms of the last scroll
event, not to the frame.

## Fix
Added a throttle (`STICKY_DATE_HIDE_RESET_THROTTLE_MS` = 400ms): the
reset only actually runs if at least 400ms have passed since the last one.
During a sustained fling this cuts the Handler churn to roughly 1/25th.

Correctness is preserved by `handleStickyDateStateChanged()`, which already
resets the timer unconditionally the moment `onScrollStateChanged` reports
`SCROLL_STATE_IDLE` — that call only fires once per scroll gesture (not
per-frame), so it was never the target of this throttle and still guarantees
the chip hides the correct ~1200ms after scrolling actually stops, regardless
of how the throttle landed mid-fling.

## Files touched
- `feature-chat/src/main/java/com/callx/app/conversation/ChatActivity.java`
