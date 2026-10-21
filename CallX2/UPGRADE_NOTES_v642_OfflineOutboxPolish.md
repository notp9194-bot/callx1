# v642 — Offline queue polish (items 5-10)

5. Head-of-line blocking: a timed-out Firebase op (send/edit/delete) stops the batch (retry later) instead of burning 45s per
   remaining op. A failed media upload does NOT stop the batch (file/CDN specific). Interrupted worker releases the row without
   counting an attempt.
6. Permanent failure: `getDue()/countDue()` only return state='pending'. A row that hit MAX_ATTEMPTS stays 'failed' until the
   user taps the ⚠ (OfflineOutbox.retry -> fresh pending op). It is no longer silently re-tried every 6h.
7+8. `OutboxBannerController` (shared by ChatActivity + GroupChatActivity) uses `observeActiveCountForChat()`:
   offline -> "No internet — N message(s) waiting to send"; online but still queued after 3s -> "Sending N message(s)…".
   The "No connection — message/media queued" toasts are removed (bubble clock + banner replace them).
9. Media: CloudinaryUploader now reports real upload progress (ProgressBody); the worker publishes it through
   `OutboxProgressBus` -> `OutboxProgressBinder` -> adapter.onMediaUploadProgress (bubble ring). Wait is stall-based
   (90s without progress, 10 min hard cap; flat 5 min if no progress is ever reported) instead of a flat 2 min. Main file URL is
   checkpointed in Room right after upload, so a failed voice part/send does not re-upload the main file.
   NOT done: chunked/resumable upload and setForeground (need Cloudinary chunk protocol / foreground-service manifest + permission).
10. Ordering: edit / delete-for-everyone ops wait while a send or media_upload op for the same message is pending/processing.
   Caveat: if that send is permanently 'failed', the edit/delete will still run later (no node yet) — drop them when the user gives up.
