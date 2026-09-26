# v450 — 1:1 Received Text Bubble Avatar (Canvas)

- Updated only the existing `MessageBubbleCanvasView` rendering path for received plain-text messages in 1:1 chats.
- Draws a 20dp circular peer avatar, a curved connector, pale-blue bubble fill, and blue outline on the Canvas; no new layout/XML is used.
- Reuses the decoded bitmap already shown in `ChatActivity`'s partner header. The adapter receives that bitmap from the header ImageView; it does not request or fetch another avatar.
- Group chats, sent messages, media messages, deleted messages, and system rows keep their prior rendering.
- No app build or test was run, as requested.

Files changed:
- `feature-chat/src/main/java/com/callx/app/conversation/canvas/MessageBubbleCanvasView.java`
- `feature-chat/src/main/java/com/callx/app/conversation/MessagePagingAdapter.java`
- `feature-chat/src/main/java/com/callx/app/conversation/ChatActivity.java`