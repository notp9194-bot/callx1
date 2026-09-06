# v376 — MessageBubbleCanvasView: cold-start gap in the StaticLayout precompute cache

## Context
`MessageBubbleCanvasView` already had a very mature StaticLayout caching
system before this change: plain-text/caption layouts, poll-option layouts,
and reply-preview layouts are all background-precomputed in
`ChatActivity#entityToModel()` / `GroupChatActivity`'s equivalent (which runs
on `ioExecutor`, off the UI thread, before a message ever reaches the
adapter — see `sTextLayoutCache` / `sPollOptionLayoutCache` /
`sReplyLayoutCache`), so `onMeasure()` almost always just does a cache
lookup instead of paying `StaticLayout.Builder...build()`'s line-breaking
cost during a fast fling.

## Gap
The precompute cache keys on `(text, width)`, and the target width
(`sLastKnownMaxTextWidth`) is a static field that only gets set the first
time a real `MessageBubbleCanvasView` is actually measured (self-calibrating
from the live `onMeasure()` call). Until that happens it sits at `-1`, and
`precomputeTextLayoutIfPossible()` / the poll-option and reply-preview
equivalents all no-op when the width is unknown.

Net effect: the very FIRST chat opened after a cold app start gets **zero**
benefit from all of this precompute machinery. Its initial page of messages
finishes `entityToModel()` before any bubble has ever been measured, so
every precompute call for that first page is silently skipped — and
`onMeasure()` then builds every one of those `StaticLayout`s synchronously,
on exactly the screen where a smooth first impression matters most.

## Fix
Seed `sLastKnownMaxTextWidth` in a static initializer using
`Resources.getSystem().getDisplayMetrics()` (the same Context-free source
`sp2pxStatic()` already relies on), run through the *identical* formula
`onMeasure()` uses (`parentWidth * MAX_BUBBLE_WIDTH_FRACTION`, minus
`H_PADDING_DP * density` on each side). A chat `RecyclerView` row is
essentially always full device width, so this matches the real
`onMeasure()` call in the overwhelming majority of cases — meaning the
FIRST page of the FIRST chat opened after cold start can now also be
precomputed off the UI thread.

If the estimate doesn't match the real width for some device/window
configuration (split-screen, a resized/rotated window, an odd density
bucket), the cache key just won't match and `onMeasure()` falls back to a
synchronous build exactly as it always has — same "worst case no speedup,
never wrong content" safety invariant the rest of this cache already
documents.

## Files touched
- `feature-chat/src/main/java/com/callx/app/conversation/canvas/MessageBubbleCanvasView.java`
