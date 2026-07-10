# v24 — Group List: Canvas ticks + live typing (parity with 1:1 chat list)

## What changed

The group list (`GroupAdapter`/`item_group.xml`) didn't have read-receipt
ticks or a live "typing..." indicator at all — only the 1:1 chat list did
(`ChatListAdapter`, v22/v23). This brings the group list to parity, reusing
the same Canvas rendering technique (`ChatListLastMessageView`) instead of
a plain `TextView`.

### Data model (new fields, mirrors `User`/`ChatEntity`)

- `Group.lastMessageType` / `lastMessageStatus` / `lastMessageSenderUid` /
  `lastMessageId` (Firebase `groups/{groupId}` node)
- `GroupEntity` — same four columns (Room cache), added via
  `MIGRATION_28_29` (DB version 28 → 29)
- `GroupsFragment` — carries the new fields through Room ↔ Firebase ↔
  `DiffUtil`'s `areContentsTheSame()` (without this, a tick flip or a
  media-type change would be silently skipped since every other field
  stayed the same)

### Where ticks come from

`lastMessageStatus` on the group is the **aggregate** status —
`GroupMessageStatusSync` already tracked per-member delivered/read acks
and flips the *message's own* `status` field once every other member has
acked (see that file's existing doc comment). `checkAggregate()` now also
calls a new `updateGroupListTicks()`, which — guarded by
`groups/{groupId}/lastMessageId` matching the just-acked message, same
guard `ChatPresenceController#updateSenderChatListTicks` uses for 1:1 —
writes that aggregate status onto `groups/{groupId}/lastMessageStatus` so
the SENDER's own group-list row updates without reopening the chat.

`GroupChatActivity#firebasePushGroup` now also writes
`lastMessageType`/`lastMessageSenderUid`/`lastMessageStatus="sent"`/
`lastMessageId` alongside the existing `lastMessage`/`lastMessageAt`
write, mirroring `ChatMessageSender`'s 1:1 equivalent.

### Typing indicator

Reuses the existing `groups/{groupId}/typing/{uid} = displayName` node
(already written by `GroupChatActivity#setMyTyping` for the in-chat typing
strip) — `GroupAdapter` now also watches it per-row, attached/detached
with bind/recycle exactly like `ChatListAdapter`'s 1:1 typing listener, so
scrolling never leaks a listener. Multiple simultaneous typers are
summarized the same way the in-chat strip does: `"Alice typing..."` /
`"Alice, Bob typing..."` / `"Alice +2 typing..."` for 3+.

### Rendering

`item_group.xml`'s `tv_group_last` `TextView` is replaced by a
`ChatListLastMessageView` (the same class the 1:1 chat list now uses) —
ticks are drawn with `canvas.drawLine()`, text with
`TextUtils.ellipsize()` + `canvas.drawText()`, no extra `ImageView`/
`TextView` pair needed.

## Files touched

- `core/.../models/Group.java`
- `core/.../db/entity/GroupEntity.java`
- `core/.../db/AppDatabase.java` (version 28 → 29, `MIGRATION_28_29`)
- `core/.../utils/GroupMessageStatusSync.java`
- `feature-chat/.../group/GroupChatActivity.java` (`firebasePushGroup`)
- `feature-chat/.../group/GroupsFragment.java`
- `feature-chat/.../group/GroupAdapter.java`
- `feature-chat/.../res/layout/item_group.xml`
