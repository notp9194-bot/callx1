# Chat Screen Lag / Jump / Flicker Fix

## Root cause (confirmed by code read of ChatActivity + GroupChatActivity)

Both 1:1 chat (`ChatActivity`) and group chat (`GroupChatActivity`) use:

```
Firebase ChildEventListener → Room (insertMessage, one row at a time)
                                  ↓
                     Room auto-invalidates PagingSource
                                  ↓
                  Paging3 re-queries → submitData() → DiffUtil → RecyclerView
```

When a chat is opened, Firebase replays the last ~30 messages as **30 separate
`onChildAdded()` callbacks**, fired back-to-back. The old code wrote each one
to Room **immediately** (`saveToRoom()` → `insertMessage()`), and on top of
that called `markRead()` for every unread message, which did **another**
separate Room write per message.

Result: opening a chat fired up to 60+ individual Room writes in a tight
burst. Each write invalidates the Paging `PagingSource`, so each one drove
its own `submitData()` → diff → layout pass. That's what showed up as:

- 3–4 second delay before the screen "settled"
- the list visibly growing and jumping to the bottom one message at a time
- flicker / unstable scroll while it was happening

The explicit `binding.rvMessages.scrollToPosition(total - 1)` inside
`onItemRangeInserted()` made it worse — it fired on *every one* of those
bursts, forcing a fresh scroll each time.

## Fix

**1. Write-coalescing buffer (`ChatActivity` + `GroupChatActivity`)**
All Firebase add/change/remove events — and read-receipt updates — are now
buffered in memory and flushed together after an 80ms debounce window via
one new Room transaction: `MessageDao.applyBufferedChanges()`.

- 30 historical messages opening a chat → **1** Room transaction (was 30+)
- 1 transaction → **1** PagingSource invalidation → **1** `submitData()` →
  **1** diff → **1** layout pass (single-pass render, as required)
- Buffered writes are flushed immediately in `onDestroy()` too, so nothing
  is lost if the screen is closed inside the debounce window.

**2. `MessageDao` additions** (`core/.../db/dao/MessageDao.java`)
- `softDeleteAll(List<String> ids)` — bulk soft-delete
- `markReadBulk(List<String> ids)` — bulk read-receipt write (status-only,
  doesn't touch other columns)
- `applyBufferedChanges(upserts, removedIds, readIds)` — `@Transaction`
  default method that runs all three in one DB transaction

**3. Scroll-jump fix**
`onItemRangeInserted()` in both activities now skips the explicit
`scrollToPosition()` call on the very first data load. `LinearLayoutManager`
with `stackFromEnd(true)` already anchors the initial layout at the bottom —
the old extra scroll call was producing a visible double-scroll/snap right
as the chat opened. The explicit scroll-to-bottom behavior is preserved for
genuinely new messages arriving later while the user is at the bottom.

**4. `ChatPresenceController.markRead()`**
Now calls the new `ChatActivityDelegate.queueMarkRead(messageId)` instead of
issuing its own immediate `ioExecutor.execute(updateStatus(...))` per
message, so read-receipts coalesce into the same transaction as the
message sync instead of firing extra invalidations of their own.

## Files changed
- `core/src/main/java/com/callx/app/db/dao/MessageDao.java`
- `feature-chat/src/main/java/com/callx/app/conversation/ChatActivity.java`
- `feature-chat/src/main/java/com/callx/app/group/GroupChatActivity.java`
- `feature-chat/src/main/java/com/callx/app/conversation/controllers/ChatActivityDelegate.java`
- `feature-chat/src/main/java/com/callx/app/conversation/controllers/ChatPresenceController.java`

## What was NOT touched (already fine)
- DiffUtil `areContentsTheSame()` in `MessagePagingAdapter` — already field-by-field,
  already skips rebinds when nothing changed.
- Entity↔Model conversion — already off the main thread via
  `PagingDataTransforms.map(..., ioExecutor, ...)`.
- Staged controller init (300ms / 600ms postDelayed) — already in place.
- RecyclerView tuning (fixed size, view cache, RecycledViewPool, prefetch) —
  already in place from earlier fixes.
