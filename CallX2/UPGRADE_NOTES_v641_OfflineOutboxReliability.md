# v641 — Offline message queue: reconnect flush, scheduling, status, no double-send

1. Reconnect: `CallxApp.registerOutboxNetworkKick()` (global, VALIDATED internet) -> `OfflineOutbox.kick()` ->
   `OutboxOperationDao.resetBackoff()` (pending ops parked behind 30s..6h backoff become due now) + immediate worker start.
2. Scheduling: `OfflineOutbox.scheduleNow()` no longer uses ExistingWorkPolicy.KEEP. Worker RUNNING -> skip (the worker
   re-checks `countDue()` before finishing and returns retry); otherwise REPLACE, so a new message is never stuck behind
   WorkManager's retry-backoff. `schedule()` is now main-thread safe.
3. Status: `OutboxSyncWorker.fail()` flips the bubble to "failed" (tap-to-retry) only when the op reached MAX_ATTEMPTS;
   while auto-retry is pending the bubble keeps its pending/uploading state.
4. No double send / overwrite: worker skips ops whose journal row the foreground path already deleted, skips messages whose
   Room status is already sent/delivered/read/seen, and sends via a create-if-absent transaction instead of setValue()
   (recipient-written ticks/reactions can no longer be overwritten). Requires read access to the message node in RTDB rules.
