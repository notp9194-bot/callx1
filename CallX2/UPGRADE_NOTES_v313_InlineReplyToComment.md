# v313 — Reply-to-reply: inline reply from the overlay, no chat hop

## What changed
Tapping a commenter in `StatusRepliesBottomSheet` used to only jump into
`ChatActivity`. Now each row also has a **Reply** toggle that expands an
inline `EditText` + send button right under that comment — type and send
without ever leaving the sheet.

- `StatusRepliesBottomSheet.show()` now takes `myUid` (needed to know who's
  sending and to hide "Reply" on your own comment). Call site updated in
  `StatusViewerActivity` (`this, ownerUid, myUid, s, this::resumeProgress`).
- New `sendInlineReply()`: writes the same shape of quoted chat DM
  `StatusReplyBottomSheet#sendReply` already writes
  (`messages/{chatId}/{msgId}` with `replyToType/Text/SenderName/Id/MediaUrl`),
  except it quotes the **comment** (`r.text`/`r.name`) instead of the status,
  so the resulting bubble reads "💬 {name}'s comment" in chat — and it goes
  straight to that commenter (`r.uid`), not necessarily the status owner.
  Same push-notification hookup as the existing send path (`PushNotify.
  notifyStatusReply`, reusing the `users/{myUid}` name/photo lookup already
  needed for the notification).
- "Reply" is hidden for your own comment row (`myUid == null` or
  `myUid.equals(r.uid)`).
- Sending does NOT dismiss the sheet or navigate anywhere — clears the box
  and collapses it so you can reply to another comment in the same sheet.
- Tapping the row itself (outside the Reply toggle/inline box) still opens
  the full chat, unchanged from v312.

## Files touched
- `feature-status/src/main/java/com/callx/app/interactions/StatusRepliesBottomSheet.java`
- `feature-status/src/main/java/com/callx/app/viewer/StatusViewerActivity.java`
