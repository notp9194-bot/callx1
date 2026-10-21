# v643 — Media bubble progress: cancel, resume, offline state

1. Cancel: `ChatMediaController.cancelUpload()` now also deletes the message's outbox rows (media_upload + send).
   `OutboxSyncWorker.uploadMedia()` re-checks the journal row after every upload stage (`isCancelled`) and discards the result,
   so a cancelled message is never uploaded/sent later and its "failed" status is not overwritten.
2. Resume: `onChatResumed()` no longer flips "uploading" rows to "failed" when the offline outbox still owns them
   (`countPendingSendFor > 0`); toast count only covers truly interrupted uploads. (Fixes the false ⚠ introduced by v642.)
3. Offline: new `MessageBubbleCanvasView.setMediaWaitingForNetwork()` — static clock pill "No network · tap to cancel"
   (no endless spinner while offline); tap cancels. `MessagePagingAdapter.applyUploadGate()` picks ring vs pill;
   `refreshUploadGates()` is called from the Chat/Group network callbacks so the bubble flips when connectivity changes.
Not changed: group chat has no cancel handler (canCancelUpload=false) so the pill there is informational only.
