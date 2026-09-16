# Chat history pagination fix

## What was fixed

The chat screen uses a hand-written keyset `PagingSource`. Older pages downloaded
by `MessageRemoteMediator` were being inserted into Room, but that custom source
was not listening to Room invalidation events. As a result, the Firebase request
could succeed while the currently open screen kept the old paging window. The
new page only became visible after reopening the chat, which caused the
"40 messages, then a few more after every reopen" behaviour after reinstall.

`MessageKeysetPagingSource` now registers a Room `InvalidationTracker` observer
for the `messages` table and invalidates its current Paging 3 generation after
the mediator inserts an older page. Paging immediately creates a new generation
from Room, so scrolling upward can show each older page in the same open chat.
The observer removes itself when the PagingSource is invalidated to avoid
leaking old chat screens.

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

No Gradle build or app test was run, as requested.