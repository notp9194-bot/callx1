# v794 — Groups-in-Chats polish
- Nav Chats badge now skips muted + archived groups (muted ids persisted in group_list_settings "muted_ids").
- Leave group from Chats = real leave (members/admins/mutedBy/unread/userGroups removed, "left the group" system msg, sender-key rotate push).
- Mute / Archive selection actions work for 1:1 + groups together; Archived chip lists both; chip shows unread count.
- Archived unmuted group auto-unarchives on new unread message.
- Filter-aware empty states (Groups empty tap -> NewGroupActivity).
- Pin limit 3 (1:1 + groups combined; existing extra pins untouched).
- Room write dedupe in GroupChatListSource; areContentsTheSame includes lastMessageId.
- New strings: feature-chat/res/values/strings_chat_groups.xml
Not done: cross-device pin/archive sync; per-group full-node listener -> summary node.
