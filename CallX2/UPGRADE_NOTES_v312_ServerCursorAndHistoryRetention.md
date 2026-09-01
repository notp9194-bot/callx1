# v312 — Server-side seq cursor + stop auto-deleting offline history

Addresses the remaining 2 of the 6 gaps from the sync-reliability review
(#2 and #6 shipped in v311).

## Gap #1 — true server cursor nahi tha

**Root cause:** the delta-sync cursor was purely client-derived
`(timestamp, messageId)` — a sound keyset for pagination, but not a real
ack/sequence token, since `timestamp` is set by whichever device sent the
message (clock skew, offline queue flush, two people typing at once).

**Fix:**
- `functions/index.js` — new `assignMessageSeq` Cloud Function. Fires on
  every new `messages/{chatId}/{messageId}`, atomically hands it the next
  integer from `chatSeqCounters/{chatId}` via an RTDB `transaction()` —
  race-free even under concurrent sends. `firebase_new_features_rules.json`
  locks that counter to function-only writes.
- `Message.seq` / `MessageEntity.seq` / `MessageSyncStateEntity.cursorSeq`
  — new nullable fields carrying it end to end.
- `AppDatabase` `MIGRATION_54_55` (v54→55) — adds the columns + a
  `(chatId, seq)` index.
- `MessageSyncStateDao#advance` — once a chat's cursor has a seq, compares
  by seq alone; falls back to `(timestamp, id)` for chats that haven't
  picked one up yet, and upgrades automatically the moment they do.
- `ChatRepository.syncMessagesDelta`, `ChatActivity`, `GroupChatActivity`
  — all query `orderByChild("seq").startAt(seq)` once available, instead
  of `orderByChild("timestamp")`.

**Backward compat:** existing messages aren't auto-backfilled (see the
function's deploy note for the one-off backfill script if you want it) —
every chat just keeps using its old timestamp cursor until a seq-bearing
message arrives, no migration-day cliff.

## Gap #3 — history prune kar deta tha, offline old messages nahi milte the

**Root cause, worse than described:** `pruneOldMessages(chatId, N)` is a
hard `DELETE` — and it was firing **unconditionally**:
- Every single chat open (`ChatActivity`/`GroupChatActivity`, 10s after
  `onCreate`) deleted anything beyond the last **500** messages for that
  chat.
- Every periodic heavy `SyncWorker` pass deleted anything beyond the last
  **200**, for *every* chat in the account.

Neither check was gated on whether the device actually needed the space —
so real chat history was capped on a schedule that had nothing to do with
storage pressure, and scrolling past that point later needs a network
fetch that simply can't succeed offline.

**Fix:** `DeviceStorageUtils.isDeviceStorageLow()` — a real `StatFs`-based
free-space check (< 300MB, roughly Android's own low-storage threshold).
New `ChatRepository#pruneOldMessagesIfLowStorage()` is a genuine no-op
(not even a DB read) unless that's true. All three call sites now route
through it, and the keep-count when it *does* fire is raised from 200/500
to **2000** — pruning is now a real last-resort disk-space measure, not a
routine cap on how much history you get to keep offline.

## Still open (not this pass)
#4/#5 (RAM/media cache policy) — flagged earlier as largely fine as-is,
polish-only if you want them next.
