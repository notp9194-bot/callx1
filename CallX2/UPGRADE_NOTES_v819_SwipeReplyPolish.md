# v819 — Swipe-to-reply polish (8 items)

1. **Reply bar tap -> jump to original** (1:1 + group). Card tap par `navigateToOriginalMsg()` / `scrollToMessageId()`. TalkBack label: "Go to original message".
2. **Reply state restore**: `ReplySnapshot` (tiny Bundle: id, sender, type, text, thumb...) rotation / process death ke baad reply bar wapas dikhata hai — silently (keyboard / analytics nahi).
3. **Swipe gate** (`SwipeReplyHandler.setGate`): blocked chat, recording, multi-select, admins-only group (non-admin) me swipe bilkul band (na row hilti, na haptic).
4. **Per-frame allocation fix**: reply icon ka `PorterDuffColorFilter` ab accent color par ek baar cache hota hai (pehle har drag frame par naya).
5. **Arm "pop" animation**: trigger cross hone par chip + icon overshoot ke saath pop (180ms), disarm par smooth settle; apni clock par chalta hai.
6. **Group parity**: gallery-item reply quote (`ReplyPreviewBinder.describe(ctx, m, idx)` — 1:1 aur group dono use karte hain), analytics (`onSwipeAttempt/onSwipeTriggered`), restore.
7. **Dead code removed**: `onSwipeReplyCancelled`, `ENABLE_SOUND`, `ReplyController` ka PENDING_UNDO/undo flow + snackbar + `pendingReplySnackbar`.
8. **Accessibility**: `ReplyAccessibility` — har replyable row par TalkBack custom action "Reply" (sirf jab accessibility service on ho; swipe jaisi hi rules).

New files: `ReplySnapshot`, `ReplyAccessibility`. Edited: `SwipeReplyHandler`, `ReplyController`, `ReplyPreviewBinder`, `ChatActivity`, `GroupChatActivity`, `strings_reply_bar.xml`.
