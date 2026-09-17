# Chat history pagination fix

## What was fixed

The chat screen uses a hand-written keyset `PagingSource`. Older pages downloaded
by `MessageRemoteMediator` were being inserted into Room, but that custom source
was not listening to Room invalidation events. As a result, the Firebase request
could succeed while the currently open screen kept the old paging window. The
new page only became visible after reopening the chat, which caused the
"40 messages, then a few more after every reopen" behaviour after reinstall.

`MessageKeysetPagingSource` is refreshed explicitly for the active chat instead
of registering a broad Room `InvalidationTracker` observer for the entire
`messages` table. Current-chat writes use the existing debounced Activity
refresh path, while `MessageRemoteMediator` invalidates the source only after
it inserts an older page for this chat. Unrelated chats can therefore update
without rebuilding the visible chat's Paging generation.

## Viewport jump fix

The history request now captures the real message and pixel offset currently
at the top of the RecyclerView before the Firebase PREPEND starts. The new
Paging generation is explicitly anchored to that message, and the adapter
restores the same message offset after the diff is applied. This prevents an
older-page insert from being interpreted as a bottom refresh and pulling the
screen back down.

## Firebase rules

Both the active `messages/{chatId}` path and the legacy
`chats/{chatId}/messages` path include the `seq` index alongside `timestamp`.
The pagination fix does not loosen read or write permissions.

## Firebase → Room batch sync

Realtime Firebase add/change/remove callbacks are buffered briefly and applied
in one Room transaction. Conflict resolution still runs per message, but the
resolved rows now use one bulk `insertMessages()` call instead of one
`insertMessage()` SQL write per callback. Bulk soft-deletes and mark-read
updates remain in the same transaction, reducing invalidations and RecyclerView
diff/layout passes during an initial message burst.

No Gradle build or app test was run, as requested.