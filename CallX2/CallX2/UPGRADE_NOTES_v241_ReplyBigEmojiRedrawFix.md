# v241 — Fixed unnecessary full-bubble redraw on status-reply / big-emoji bubbles

## The actual bug
Not a perception issue — `setReply()` and `clearReply()` in
`MessageBubbleCanvasView` had NO "did anything actually change" guard,
unlike `setBigReactionEmoji()` which already had one. Both are called
unconditionally on **every rebind** of a row — not just scroll recycling,
but (per the existing comment at the call site in `MessagePagingAdapter`)
every rebind of an on-screen row triggered by *any unrelated new message*
being inserted elsewhere in the chat's paging list.

`invalidate()` is overridden to always set `fullBubbleDirty = true`
(PERF #5's full-bubble RenderNode/Picture cache). So every time `setReply()`
ran — even with the exact same sender/text/thumbnail as before — it called
`invalidate()` unconditionally, forcing the *entire* bubble (background,
text, footer, and the big reaction emoji badge) to be re-recorded from
scratch. Status-reply/story-reaction bubbles (the ones with the big emoji)
felt this hardest since they're visually heavy and get rebound most often.

So: yes, it really was redrawing on every send/receive of ANY message
while the status-reply bubble was on screen — not just when its own
content changed, and not something tied to opening the chat specifically.

## The fix
Added the same no-op early-return pattern `setBigReactionEmoji()` already
uses, to both:
- `setReply(senderName, text, thumb)` — skips all recompute + `invalidate()`
  when `hasReply` is already true with the identical sender/text/thumb
  reference.
- `clearReply()` — skips when the view is already in the "no reply"
  state (`!hasReply && !bigReactionBadge`). This one matters even more
  broadly: it means most PLAIN messages (no reply at all) were also
  re-recording their full cached bubble on every unrelated send/receive.

Now a rebind with unchanged reply content (or unchanged "no reply" state)
is a true no-op — no `resolveReplyColors()`, no cache-key rebuild, no
`requestLayoutIfSizeChanged()`, and critically no `invalidate()` — so the
RenderNode/Picture cache stays valid and that row's `onDraw()` just replays
the cached display list.

## File touched
- `MessageBubbleCanvasView.java` — `setReply()`, `clearReply()`
