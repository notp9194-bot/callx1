# v311 — Stale edit/delete watcher + unified decrypt path

Addresses 2 of the 6 gaps flagged in the sync-reliability review:

## Gap #2 — old messages ke edits/deletes miss ho sakte the

**Root cause:** the live `messageQuery` in both `ChatActivity` and
`GroupChatActivity` is cursor-bound —
`orderByChild("timestamp").startAt(cursor.timestamp)`. Firebase's
`ChildEventListener` only reports `onChildChanged`/`onChildRemoved` for
children *inside* a query's own filtered result set. A message older than
the last delta-sync cursor was never in that set, so an edit or delete to
it (from another device, an admin action, etc.) silently never reached
this device.

**Fix:** a second, bounded listener — `historyQuery` /
`attachHistoryWatcher()` — added in both `ChatActivity.java` and
`GroupChatActivity.java`. It watches `limitToLast(HISTORY_WATCH_WINDOW)`
(200) independent of the cursor, and *only* acts on `onChildChanged` /
`onChildRemoved` (its `onChildAdded` is a deliberate no-op — inserts stay
owned by the primary listener / cold delta sync / `MessageRemoteMediator`,
so nothing is fetched twice). Both events feed into the existing
`pendingUpserts` / `pendingRemovals` write-coalescing buffer, so there's no
new Room write path to keep in sync with the old one. Proper
`removeEventListener` cleanup added to both Activities' `onDestroy()`
(it's a distinct `Query` object from `messageQuery`, so it needed its own
removal — same class of leak the existing `LISTENER-LEAK FIX` comment
already documents for `messageQuery`).

**Bound:** window is 200 messages, not "all history," to avoid an
unbounded live socket on a chat with years of history. Anything older
than that reconciles on the next full cursor reset (e.g. reinstall)
rather than live — worth widening later if real-world reports show edits
further back than that are common.

## Gap #6 — repository sync aur realtime listener fully unified nahi the

**Root cause:** `ChatRepository#decryptIfNeeded` and
`ChatActivity#decryptIncomingIfNeeded` were two independently hand-written
copies of the same rule (own-outgoing-message-echo → restore cached
plaintext; everything else → real ratchet decrypt). Two copies of a
subtle rule is a drift risk, not (as far as this pass found) an active
bug — `E2EEncryptionManager`'s per-message idempotency already protected
against the literal race described in the old comment.

**Fix:** new `core/.../sync/MessageDecryptor.java` — the single canonical
implementation. `ChatRepository` and `ChatActivity` both now delegate to
it; `ChatActivity` keeps only its one genuinely Activity-specific bit
(firing the security-alert bubble immediately since the chat is visibly
open).

**Not done in this pass (scope note):** this only unifies the *decrypt*
step, not the full realtime-listener/query-construction machinery itself
(`ChatRepository`'s one-shot delta sync vs. `ChatActivity`'s live
`ChildEventListener` are structurally different for a reason — background
preload shouldn't hold open live sockets for chats that aren't on
screen). A full `MessageSyncCoordinator` that ref-counts a single live
listener per chatId across every screen that wants one is the deeper
version of this fix and is a bigger, separate change — flagged for a
follow-up pass rather than folded into this one.

## Not touched this pass
Gaps #1 (server-side cursor/ack), #3 (history prune vs. offline access),
#4/#5 (RAM/media cache policy) — as discussed, next up whenever you want
them.
