# v369 — Bubble drawable: static process-wide pre-bake (WhatsApp-level)

Only 4 distinct bubble backgrounds exist in the entire app: sent/received
× normal-tail/media-tail corner radius. Every message row previously
rebuilt its own copy the first time each combo was needed on that
*instance* (per-view `bubbleDrawable`/`lastCacheKey` fields) — meaning a
freshly created or freshly recycled RecyclerView holder always paid for
at least one `new GradientDrawable()` + `setCornerRadii()` on its very
first bind, and every distinct combo it later encountered.

## Fix

- `sharedBubbleDrawable(ctx, sent, isMediaTail, density)` — new static
  method, one 4-slot pool (`BUBBLE_DRAWABLE_POOL`) shared by every
  `MessageBubbleCanvasView` instance across every chat screen in the
  process. Built once per combo, forever reused after that.
- Pool is invalidated (and lazily rebuilt) if the resolved bubble colors
  or density ever change — covers a runtime day/night theme switch
  without a process restart, so nothing stale gets stuck.
- Removed the now-unused per-instance `lastCacheKey` field and the old
  `buildBubbleDrawable()` instance methods; all 8 call sites (text,
  media, media-group, reel-share, file, poll, audio, reply-preview)
  now just fetch from the shared pool.
- Sharing one `GradientDrawable` instance across many bubbles is safe
  here because every use is `setBounds()` immediately followed by
  `draw(canvas)` within the same synchronous `onDraw()` — RecyclerView
  draws children one at a time on the UI thread, so there's no
  interleaving that could make one bubble draw with another's bounds.

Net effect: bubble background allocation happens **at most 4 times total,
ever, per process** — not per view instance, not per bind, not per
scroll. Every chat screen in the app shares the same 4 drawables.
