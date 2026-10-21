# v645 — Chat message cache / sync fixes

1. Stale old rows: `ChatRepository.refreshRecentWindowIfDue()` — after delta sync finishes, re-reads the newest 100 rows
   (max once / 10 min / chat) and writes only what changed (delete flag, reactions, poll votes, newer edit, newer tick status,
   media url) through `mergeIncomingMessages`. Rows already decrypted locally keep their local text (no E2E re-decrypt).
   Limits: edits/deletes older than the 100-row window, and nodes physically removed on the server, are still not reconciled.
2. Delta catch-up: `runDeltaPass()` keeps pulling pages while a page comes back full and the cursor advances
   (max 20 passes per open) — no hole between old cursor and the live listener's newest rows.
3. Merge: `MessageDao.mergeIncomingMessages` null-fills device-local columns the server copy lacks
   (starred, pinned, viewOnceState, editHistoryJson, thumbInlineData). Reactions/poll votes/status are not null-filled on purpose.
4. One sync path: `MessageRepository.deltaSync()` (used by ChatListViewModel prefetch of top chats) now delegates to
   `ChatRepository.syncMessagesDelta()`. The old copy used startAfter(timestamp), no decrypt, REPLACE writes. Unused
   `MessageRepository.sendMessage/insertMessage(s)` removed.
Not done: disk cache (SharedPreferences) migration, warm-cache staleness stamp, prune marker, merge unit tests.
