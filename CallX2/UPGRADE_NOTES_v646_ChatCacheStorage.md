# v646 — Chat cache: disk store, staleness, prune marker, merge tests

5. `LastMessagesDiskCache` v2: one AES-256-GCM encrypted file per chat in cacheDir/last_messages_v2 (key derived from the
   Keystore-backed DB secret via EncryptedDbKeyStore), atomic tmp+rename writes, LRU 50 files, no more SharedPreferences
   blob. Fails closed (writes nothing) if encryption is unavailable. v1 prefs entries migrate lazily on first read.
   `clearForAccountAsync` (already called on logout / account switch in Auth, PhoneAuth, AccountMenu) now also wipes v2 files
   and invalidates in-flight saves.
6. `LastMessagesCache`: warm frame never shows deleted rows (same rule as the Paging query) or expired disappearing
   messages; upsert of a deleted message removes the cached copy; `getAgeMs(chatId)` exposes how old the warm list is
   (Long.MAX_VALUE = unknown / migrated). Disk snapshots carry `savedAt`.
7. Prune: `MessageDao.pruneOldMessages` no longer deletes starred or undelivered (pending/uploading/failed) rows;
   `ChatRepository.pruneNow` is COUNT-first and records the new local-history start (`getPrunedBeforeTimestamp(chatId)`).
   Nothing reads the marker yet — it is groundwork for an "older messages are online" hint / end-of-history check.
8. Tests: `MessageMergePolicy` extracted from MessageDao.mergeIncomingMessages (pure, JVM-testable) +
   MessageMergePolicyTest (11) and LastMessagesCacheTest (5). Run against minimal stubs here: 16/16 pass.
   Not covered: Room-backed DAO tests (need androidTest/Robolectric), disk cache I/O.
