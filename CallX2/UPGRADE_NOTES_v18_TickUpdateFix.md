# v18 — Fixed: chat ticks not updating on canvas bubbles

## Root cause
`MessagePagingAdapter`'s DiffUtil already detected status-only changes
correctly (sent → delivered → read) and returned `PAYLOAD_STATUS` to skip
a full rebind — that part was working. But the `onBindViewHolder(..,
payloads)` fast path for `PAYLOAD_STATUS` only ever called
`bindStatusTick(h, m)`, which exclusively updates the legacy `tv_status`
TextView (`h.tvStatus`).

Canvas-eligible bubbles — plain text, images, the common case per
`isCanvasEligible()` — don't have a `tv_status` View at all. So every time
a message's status changed *without* anything else changing (the normal
case for delivery/read receipts), the fast path ran, found `h.tvStatus ==
null`, did nothing, and returned. The tick on the actual on-screen Canvas
bubble was never touched. Only a message that also happened to change
text/type/etc. at the same time (forcing a full `bind()`/`bindMedia()`/etc.
call, which does set the tick from scratch) ever picked up the new status.

## Fix
Added `MessageBubbleCanvasView.setDeliveryStatus(isRead, isDelivered)` — a
cheap, draw-only setter in the same family as `setAudioPlaying()`/
`setExpiryText()`'s "cheap path": updates the `read`/`delivered` fields +
`tickPaint` color, then repaints just the footer band (reuses the existing
`invalidateExpiryRegion()` dirty-rect, which already covers the footer/tick
area with generous padding). No `requestLayout()` — the tick icon reserves
a fixed width regardless of read/delivered state (see
`footerReserveWidth`'s unconditional `TICK_SIZE_DP` add for any sent
bubble), so there's nothing to re-measure.

`onBindViewHolder`'s `PAYLOAD_STATUS` branch now calls this on
`h.canvasView` alongside (not instead of) the existing `bindStatusTick`
legacy-view call, so both canvas and any remaining legacy-view bubbles get
updated.

## Perf impact
None. This only fires on an actual status transition (guarded by an
early-return no-op if read/delivered didn't actually change), touches two
fields + one Paint color + one small dirty-rect `invalidate()` — no
allocation, no relayout, no full rebind. Scrolling/fling performance is
unaffected; if anything this is cheaper than before since a status change
used to sometimes fall through to other codepaths doing more work.

## Not touched (out of scope for this fix)
The legacy `tv_status`/`bindStatusTick` tick colors are still the old blue
(`#4FC3F7`) rather than the v17 gold signature — that path is effectively
dead for the common canvas-eligible case, so left alone here. Happy to
align it if it's still reachable in your build.
