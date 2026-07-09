# v70 — Status reply/reaction wasn't reaching the chat screen (root cause + fix)

## Bug 1 (biggest one) — replies written to the wrong Firebase node
`StatusReplyBottomSheet.sendReply()` (the full reply sheet, with the status
preview + "Replying to X" UI) wrote the outgoing message to:

    chats/{chatId}/messages/{msgId}

But `ChatRepository` — and every other send path in the app — reads/listens
on:

    messages/{chatId}/{msgId}

Two completely different Firebase nodes. So a status reply sent through the
full bottom sheet silently vanished into a node nobody ever reads: it never
showed up in the sender's own chat, and it never reached the status owner's
chat either. **Fixed** by writing to `messages/{chatId}/{msgId}` like
everything else.

## Bug 2 — inline quick-reply used the wrong field name
The lightweight inline reply bar in `StatusViewerActivity`
(`etReply`/`btnSendReply`) wrote a key called `"sender"`, but `Message.java`'s
Firebase field is `senderId`. Firebase's POJO mapping only fills fields whose
name matches the JSON key, so `senderId` deserialized to `null` on every
device that read it back — breaking sent/received attribution. It also never
set any `replyTo*` fields, so unlike the full bottom sheet, this path showed
a bare text bubble with no quoted status preview at all.
**Fixed**: correct key (`senderId`), added an `id`, and added the same
`replyToId/replyToType/replyToText/replyToSenderName/replyToMediaUrl` fields
the full bottom sheet sets, so both reply paths now render identically.

## Bug 3 — emoji reactions to a status never touched the chat at all
Tapping an emoji on someone's status only wrote to
`statuses/{ownerUid}/{statusId}/reactions/{myUid}` (+ a push notification).
That's it — no chat message, on either side, ever. WhatsApp shows a
"Reacted 😂 to your status" bubble in the 1:1 chat for both people.
**Added**: `StatusViewerActivity.sendReactionToChat()` — fires only when a
reaction is newly added (not on toggle-off), pushes a normal `type:"text"`
message (`text` = the emoji) carrying the same quoted-status `replyTo*`
fields as a real reply. This reuses the existing generic reply-quote-box
renderer and the `status_`-prefixed tap-to-reopen-status handling from the
previous fix — no new bubble type needed.

## Net effect
- Status text replies now appear in the chat screen for both people, with a
  proper "quoted status" preview box — tapping it reopens the status.
- Status emoji reactions now also appear in the chat screen the same way,
  showing the emoji with a quoted status preview underneath.

## Files touched
- `feature-status/src/main/java/com/callx/app/interactions/StatusReplyBottomSheet.java`
- `feature-status/src/main/java/com/callx/app/viewer/StatusViewerActivity.java`
