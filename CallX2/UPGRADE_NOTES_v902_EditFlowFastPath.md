# v902 — Edit flow fast path (chat + group)

Builds on v901. Files: MessagePagingAdapter, MessageEditHistoryController, ChatActivityDelegate,
ChatActivity, GroupChatActivity, MessageDao, EditHistoryJsonUtil.

## What changed
1. **Bounded history, append-only Firebase write** — `EditHistoryJsonUtil.appendVersionCapped` (max 30,
   keeps "Original" + newest). Normal edit writes only `editHistory/{n}`; the whole node is rewritten
   only when the cap shifts indices.
2. **Edit persisted through the Activity's coalesced Room batch** — new
   `ChatActivityDelegate.queueLocalEditWrite()` -> `queueRoomWrite(m, false)`. One transaction shared with
   other events in the debounce window, and `LastMessagesCache` is updated (warm reopen shows the edit).
   The conditional `MessageDao.updateTextWithHistory` stays as the fallback for hosts without a batch.
3. **Own-echo shortcut before the decrypt executor** — `onChildChanged` (chat + group) calls
   `isRedundantEditEcho()` on the main thread and skips decrypt / precompute / Room batching for a
   byte-identical echo.
4. **Outbox deferral (online only)** — online edits journal to the outbox only if Firebase has not acked
   within 3 s (success -> never enqueued; late ack -> row deleted). Offline edits journal immediately.
   Trade-off: if the process dies inside that 3 s window while online, the edit relies on Firebase's own
   write queue instead of the outbox. Change `OUTBOX_DEFER_MS` to 0 to restore journal-first.
5. **Mixed payload fix** — `[read_by, FLAG_*]` / `[FLAG_*, search]` no longer skip or drop a payload;
   every Integer payload is OR-ed and the branch returns only when all payloads were accounted for.
6. **Measurement hooks** — Trace sections `Msg#bindTextOnly`, `Msg#applyLocalEdit`; OpenPerfLog keys
   `adapter.bind(textOnly)` and `edit.tapToApply` (tap -> adapter updated).

## How to measure
Perfetto / Macrobenchmark `TraceSectionMetric("Msg#bindTextOnly")` and `("Msg#applyLocalEdit")` while
editing a message; compare `edit.tapToApply` in OpenPerfLog before (v900) vs after.
