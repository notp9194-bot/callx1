# v798 — Status reply bubble: per-bind / per-draw optimization
Bind (MessagePagingAdapter)
- Label / status key / video flag built once per message (LruCache StatusReplyUi) — rebind = map hit, no string concat / getString.
- Expiry check callback lambda allocated only when a lookup is actually due.
- Expiry knowledge is persisted (SharedPreferences status_reply_expiry): "expired" is permanent, "alive" rechecked after 30 min -> reopening a chat no longer re-reads Firebase. Max 6 concurrent lookups; reads one tiny child ("type") not the whole node.
- Reply base64 pool key no longer hashes a multi-KB string per bind (key = message id + length).
Draw (MessageBubbleCanvasView)
- Thumb clip path / dst rect / play-chip path rebuilt only when the slot geometry changes.
- Paint alpha touched only while fading; expired-label text size + baseline computed once.
- Fade and pop ValueAnimators created once per view and reused (no per-bind allocation).
- Touch: reply-box hit test only on DOWN, or while pressed.
Send (StatusReplyBottomSheet)
- Text-status gradient thumb rendered off the main thread, RGB_565, memoized per status (LruCache), patched after send.
