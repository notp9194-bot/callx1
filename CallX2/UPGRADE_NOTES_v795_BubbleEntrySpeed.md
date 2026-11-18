# v795 — Faster message / status-reply bubble entry
- Status replies (private reply + question answer): optimistic Room "pending" row + OfflineOutbox entry; status -> sent/failed on Firebase result.
- All status DM paths (reply/question/quiz/countdown/poll/slider): Firebase write goes out FIRST; replyToThumbBase64 is patched in afterwards (was: download+crop+compress before send, up to ~12s).
- ChatActivity: Paging refresh debounce 180ms -> 50ms; own sends refresh with 0ms; Room write flush debounce 80ms -> 30ms.
- ChatMessageSender: optimistic Room insert on a dedicated single-thread executor (not shared AppBgExecutor pool).
- StatusViewerActivity now calls sendPrivateReply(this, ...).
