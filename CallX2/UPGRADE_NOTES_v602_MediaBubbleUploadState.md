# v602 — Media bubble: sent-video upload state, upload cancel, retry icon

1. Sent video upload state: a SENT video that is still compressing/uploading now shows the same gate as a sent
   image (spinner ring + %, restored after rebind/scroll via uploadProgressTracker) and, if the upload failed,
   a "Tap to retry" gate that calls onRetry -> ChatMediaController.retryFailedMediaUpload (video path existed,
   but nothing in the bubble could reach it). Tapping a pending video no longer tries to open the caption text as a URL.
2. Upload cancel: while uploading (1:1 chat), the progress ring has an X and tapping the pill cancels the upload via
   the existing ChatMediaController.cancelUpload() (previously had no caller). The bubble then flips to the retry gate.
   New: OnBubbleClickListener.onMediaCancelClick, MessageBubbleCanvasView.setMediaUploadCancelable,
   ActionListener.onCancelUpload/canCancelUpload (group chat keeps the default: no X, since it has no cancel wiring).
3. Retry icon: failed upload/download gates now draw a circular retry arrow (MediaRenderer.drawRetryIcon) instead of the
   download arrow. New MessageBubbleCanvasView.setMediaDownloadGateRetry(label); all "Tap to retry" gate sites use it, and
   the label moved to strings_media_bubble.xml (media_gate_retry).
