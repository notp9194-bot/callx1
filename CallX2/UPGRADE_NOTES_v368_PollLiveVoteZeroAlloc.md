# v368 — Poll live-vote zero-alloc (WhatsApp-level)

Deep pass over the canvas message renderer found one remaining per-bind
allocation hotspot the earlier onDraw() cleanup (v367) didn't cover: the
**live poll vote-count path**.

## The problem

`bindPollOnly()` — the fast-path called once for every incoming vote on an
open poll (realtime listener, can fire rapidly on an active group poll) —
unconditionally allocated fresh buffers every single call:

- `PollJsonUtil.countVotes(...)` → new `int[optionCount]`
- `new boolean[optionCount]` for the caller's own vote
- Inside `MessageBubbleCanvasView.bindPoll()`: `new String[n]` (options),
  `new boolean[n]` (leader flags), `new float[n]` (fill widths) — every
  call, even though option count essentially never changes between votes.

Same three arrays were also reallocated on every plain scroll-recycle of a
poll bubble via the full-bind path in `bindCanvasMessage()`.

## The fix

- `PollJsonUtil.countVotes(votes, optionCount, int[] out)` — new overload
  that fills a caller-supplied buffer in place when it's already the right
  size, only allocating on an actual size change. Original single-arg
  overload unchanged (delegates with `out = null`).
- `VH` (the message ViewHolder) now carries `pollCountsScratch` /
  `pollMyVoteScratch` — grown only when the poll's option count changes,
  reused (and explicitly cleared) on every vote tick and scroll-rebind
  otherwise.
- `MessageBubbleCanvasView.bindPoll()`'s `pollOptions` / `pollIsLeader` /
  `pollFillWidths` arrays follow the same grow-only pattern instead of a
  bare `new` every call.
- Correctness fix that came with this: since `pollIsLeader` is no longer
  freshly zero-initialized every call, the "no votes yet" (`maxCount == 0`)
  branch now explicitly clears it — otherwise a stale `true` from an
  earlier vote state could linger on a reused array.

Net effect: an active poll with people voting in real time — or a poll
bubble scrolling on/off screen — no longer allocates anything per update.
No behavior change.
