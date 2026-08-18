# v19 — Fixed: ticks still stuck on single grey tick after v18

## Why v18 wasn't enough
v18 fixed the *rendering* bug (canvas bubbles ignoring `PAYLOAD_STATUS`).
That part was real, but it only mattered if a `"sent" -> "delivered" ->
"read"` change ever reached Room in the first place. In real usage it
almost never did — this is the actual reason only the single "sent" tick
was ever visible.

## Root cause
`ChatActivity`/`GroupChatActivity` attach ONE `ChildEventListener` per chat
open, scoped to a delta query:

```java
Query query = lastTs > 0
        ? messagesRef.orderByChild("timestamp").startAfter((double) lastTs)
        : messagesRef.orderByChild("timestamp").limitToLast(INITIAL_LOAD);
```

This is correct and needed for the "don't re-sync 30 messages on every
reopen" perf fix from an earlier version. The problem: a freshly-attached
Firebase `Query` only fires `onChildChanged` for children that are inside
its OWN result set. A message with `timestamp <= lastTs` was never part of
that result set — Firebase isn't watching it for changes at all, delta or
not.

Real-world timeline where this bites:
1. You send a message. It's inside the window at that moment — fine.
2. You leave the chat (or the app). Room's `lastSyncTimestamp` is now
   past that message's timestamp.
3. The partner's device receives the FCM push and flips
   `status: "delivered"` on Firebase (this write itself works fine — see
   `CallxMessagingService.showMessage()` — it doesn't require your chat
   screen to be open). Later they open the chat and it flips to
   `"read"`.
4. You reopen the chat. `attachFirebaseListener()` re-attaches with a
   NEW query, `startAfter(newLastTs)`. That old message is now outside
   the window forever — no `onChildAdded` (already synced), no
   `onChildChanged` (excluded from the query). Its status is frozen at
   `"sent"` in Room from here on, no matter what happens on Firebase.

Since step 2–4 is the normal flow for basically every real conversation
(you don't sit staring at the chat waiting for the tick), this is why
delivered/read visually never showed up — not a rare edge case.

## Fix
Added a second, small `ChildEventListener` (`statusSyncListener`) attached
alongside the existing delta listener, scoped to the last
`STATUS_SYNC_WINDOW` (100) messages by timestamp:

```java
Query statusQuery = messagesRef.orderByChild("timestamp").limitToLast(STATUS_SYNC_WINDOW);
```

- `onChildAdded` is a deliberate no-op — new/already-loaded messages are
  fully handled by the existing delta listener and the initial Room
  cache, so this listener never duplicates that work.
- `onChildChanged` reuses the exact same `saveToRoom()` path the delta
  listener already used, so it's the same debounced/buffered write into
  one Room transaction — no new code path, no new risk.

Because `MessagePagingAdapter`'s `DiffUtil.ItemCallback` already detects
"only status changed" and returns `PAYLOAD_STATUS` (existing v18 fix),
this second listener catching a stale message's status change still ends
up as a cheap draw-only tick update, not a full rebind.

Applied to `ChatActivity` (1:1 chat) — this is where `sent/delivered/read`
tick status lives (`CallxMessagingService`'s delivered-status write only
targets 1:1 `chats/{chatId}/messages`, so group chat wasn't part of this
specific bug).

## Perf / scroll impact
None by design:
- The extra listener is a `limitToLast(100)` query — same shape/cost as
  the existing first-page-load query, just watched continuously instead
  of once.
- `onChildAdded` is a no-op, so no extra Room writes for messages already
  handled.
- `onChildChanged` writes go through the SAME debounced buffer
  (`WRITE_FLUSH_DEBOUNCE_MS`) and the SAME single-transaction
  `applyBufferedChanges()` as before — bursts still coalesce into one
  Room write and one Paging invalidation, not one per message.
- The Pager/PagingSource is never touched directly by this listener; it
  only ever reaches Room, exactly like the existing delta listener does.
- No changes to scroll anchoring, `severPagingIfAtBottom()`,
  `reanchorPagingToBottom()`, or any paging/scroll logic at all.

## Files changed
- `feature-chat/src/main/java/com/callx/app/conversation/ChatActivity.java`
  - New field `statusSyncListener` + constant `STATUS_SYNC_WINDOW = 100`.
  - `attachFirebaseListener()`: attaches the second listener after the
    existing one.
  - `onDestroy()`: removes the second listener alongside the existing one.
