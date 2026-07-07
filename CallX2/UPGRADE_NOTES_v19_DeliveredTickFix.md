# v19 — 1:1 Delivered Tick Fix

## Problem (2 gaps in 1:1 chat tick flow)

1. **Delivered was FCM-push-dependent.** `CallxMessagingService` only
   upgraded `sent → delivered` when a push notification actually arrived.
   Push miss (Doze, notification permission off, network drop, or the
   recipient already receiving the message via the chat screen's own
   Firebase `ChildEventListener` instead of a push) meant `delivered` was
   never written — status jumped straight from `sent` to `read`.
2. **Race condition.** The old delivered-write was
   `addListenerForSingleValueEvent()` (read) + `setValue()` (write) — two
   separate network round-trips. A concurrent `read` write from the
   recipient opening the chat at the same moment could land between the
   read and the write, and get overwritten back down to `delivered`.

## Fix

- New shared helper: `core/.../utils/MessageStatusSync.java`
  `upgradeStatus(chatMessagesRef, msgId, targetStatus)` — a single Firebase
  `runTransaction()` that only ever moves status forward
  (`pending < sent < delivered < read`), server-side compare-and-swap, so
  it's race-safe no matter how many callers fire at once.
- `CallxMessagingService`: delivered-on-push now calls
  `MessageStatusSync.upgradeStatus(..., "delivered")` instead of the old
  read-then-write.
- `ChatActivity.attachFirebaseListener().onChildAdded()`: this is the
  recipient's direct RTDB listener firing when a message syncs to their
  device — independent of any push. It now also calls
  `MessageStatusSync.upgradeStatus(..., "delivered")` right before
  `markRead()`, so delivery is acknowledged the moment the message reaches
  the device via Firebase, not only when/if a push notification shows up.

## Not touched

- Group chat ticks / `readBy` wiring — separate, larger piece of work, out
  of scope for this pass.
