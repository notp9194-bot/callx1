# v61 — Fixed: group chat delivered + read stamped at the same instant

## Root cause
`GroupChatActivity`'s real-time listener called
`GroupMessageStatusSync.ackDeliveredAndRead()` for every incoming message
the moment the group chat screen was open. That method wrote both
`deliveredBy/{uid}` and `readBy/{uid}` in the same call (see its doc
comment — it literally said "collapses into same instant").

Effect for the sender: the two aggregate checks (`checkAggregate(...,
"deliveredBy", ..., "delivered")` and `checkAggregate(..., "readBy", ...,
"read")`) both ran back-to-back on the same event, so as soon as every
member had the chat open, `status` jumped straight from `sent` to `read`.
The `delivered` state — grey double-tick, "everyone's got it but hasn't
read it yet" — never rendered. This is exactly the intermediate state
WhatsApp always shows first; group chats here skipped it entirely.

## Fix
- Added `GroupMessageStatusSync.ackRead()` — a `readBy`-only counterpart to
  the existing `ackDelivered()` (which already existed as of v60 for the
  FCM-background-receipt case).
- `GroupChatActivity.markRead()` now calls `ackDelivered()` immediately when
  a message is received/displayed, then schedules `ackRead()` on a
  dedicated `readAckHandler` after `READ_ACK_DELAY_MS` (900ms) instead of
  calling `ackDeliveredAndRead()` once.
- `readAckHandler`'s pending callbacks are cleared in `onDestroy()` (and
  guarded with `isFinishing()/isDestroyed()` inside the callback itself) so
  a delayed ack never fires against a torn-down activity.
- `ackDeliveredAndRead()` is left in place (unused by the live listener now)
  for any future bulk "mark all history read" use case where there's no
  meaningful separate delivered moment to show.

## Result
Sender's tick sequence for a group message now genuinely passes through:
`sent (single grey) → delivered (double grey, once every member's device
has received it) → read (double blue, once every member has actually
opened the chat and the read delay has elapsed)` — matching 1:1 chat
semantics and WhatsApp's own group tick behavior, instead of skipping
straight from grey to blue.

## Perf impact
Negligible — one extra `Handler.postDelayed` per incoming message while a
group chat is open, cancelled in bulk via `removeCallbacksAndMessages(null)`
on destroy. No additional Firebase reads; `ackRead()` reuses the same
transaction-guarded `stampOnce()` path as before.
