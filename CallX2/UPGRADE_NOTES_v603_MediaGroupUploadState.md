# v603 — Media bubble: SENT media-group upload progress, failed state and per-cell retry

Gap #4 from the media-bubble polish list: a sent multi-photo/video group showed no per-cell spinner, no percentage and
no failed/retry state (the group gates only existed for the RECEIVED "Download N photos" side).

1. Per-cell upload overlay (sent groups): every cell that is still uploading gets the same dim + badge + progress ring a
   downloading received cell has (determinate % when known, indeterminate spinner before the first tick). Image cells report the
   full-size upload %, video cells 0-50 % compress + 50-100 % upload (same arc as the single-video bubble). Restored after
   rebind/scroll via MediaUploadProgressTracker (key = "<groupId>_<index>") and, after an app restart, from each item's
   "uploading"/"failed" flags in mediaItemsJson.
2. Per-cell failed state: a failed cell flips to the retry arrow immediately (MediaRenderer.drawRetryIcon, now package-private and
   exposed as MessageBubbleCanvasView.drawRetryIcon). The item is flagged "failed" in mediaItemsJson.
   New MessageBubbleCanvasView.setGroupUploadState(int[] states, int[] progress) (0 none / 1 uploading / 2 failed);
   it reuses groupCellPending/Downloading/Progress for the ring and adds groupCellFailed. A progress-only update repaints
   just that cell's rect (setGroupCellProgress), not the bubble.
3. Retry: tapping a failed cell (OnBubbleClickListener.onGroupCellRetryClick) or the red tick calls
   ChatMediaController.retryFailedGroupMedia(m): re-queues only the failed cells through the same session (same bubble, no
   second row); the group is finalized to Firebase once every cell is uploaded. Previously a failed group's retry went to
   OfflineOutbox and re-pushed the message with empty URLs. ChatActivity.onRetry routes multi_media to it first and falls
   back to the old resend only when every cell already has a URL.
4. Behaviour change: a group where SOME cells failed is no longer finalized with the successful cells + blank cells. It is held
   as "failed" (red tick + retry cells) until the failed cells are retried. Previously the receiver got empty cells and the
   sender had no way to see or fix them.
5. Plumbing: ChatMediaController.GroupUploadSession (live while the group is unfinished) + enqueueGroupItemUpload() extracted from
   sendMultipleAsGroupBubble so first send and retry share one pipeline; MessagePagingAdapter.onGroupItemUploadState /
   onGroupItemUploadProgress / onGroupUploadFinished + PAYLOAD_GROUP_UPLOAD fast path (overlay-only refresh, no Glide reload).

Not in this round: cancel for group cells (cancelUpload is message-level and the queue only checks per-item ids before start),
the "+N" overflow cell does not show its own state (the red tick still appears and retries it).
