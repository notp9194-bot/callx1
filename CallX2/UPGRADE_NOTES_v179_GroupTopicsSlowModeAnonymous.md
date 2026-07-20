# Upgrade Notes — v179: Group Topics, Slow Mode, Anonymous Posting

## 3 New Features Added

---

### Feature 1: Group Topics / Threads (Telegram-style)

**Files Added:**
- `core/.../models/GroupTopic.java` — Data model for a topic thread
- `feature-chat/.../group/GroupTopicsActivity.java` — Topics list screen
- `feature-chat/.../group/GroupTopicChatActivity.java` — Chat within a single topic
- `feature-chat/.../group/GroupTopicAdapter.java` — RecyclerView adapter with unread badges
- `feature-chat/.../group/CreateTopicActivity.java` — Admin: create/edit topics
- `feature-chat/res/layout/activity_group_topics.xml`
- `feature-chat/res/layout/item_group_topic.xml`
- `feature-chat/res/layout/activity_create_topic.xml`
- `feature-chat/res/layout/activity_group_topic_chat.xml`
- `feature-chat/res/drawable/ic_topic.xml`

**Files Modified:**
- `core/.../models/Group.java` — Added `topicsEnabled` flag
- `core/.../models/Message.java` — Added `topicId`, `topicName` fields
- `feature-chat/AndroidManifest.xml` — Registered 3 new Activities
- `feature-chat/.../group/GroupSettingsActivity.java` — Admin toggle for Enable Topics
- `feature-chat/res/layout/activity_group_settings.xml` — Topics section added
- `feature-chat/.../group/GroupInfoActivity.java` — "Topics" row button added
- `feature-chat/res/layout/activity_group_info.xml` — Topics shortcut row

**Firebase schema:**
```
groups/{groupId}/topics/{topicId}/
  id, name, emoji, description, createdBy, createdAt,
  closed, pinned, messageCount, lastMessage, lastMessageAt,
  lastSenderName, unread/{uid}, deleted

groupMessages/{groupId}/{msgId}/
  topicId  (new field — null for general, topicId for topic messages)
  topicName (cached display name)
```

**How to use:**
1. Admin opens Group Settings → Enable Topics toggle
2. "Topics" row appears in Group Info
3. Tap Topics → GroupTopicsActivity (list of topics)
4. Admin taps FAB to create a topic (name + emoji + description + closed/pinned toggles)
5. Tap any topic → GroupTopicChatActivity (messages filtered by topicId)
6. Admin can long-press topic → Edit / Pin / Close / Delete
7. Closed topics show lock icon; only admins can post in closed topics
8. Unread badge shown per topic per user

---

### Feature 2: Slow Mode for Groups (Admin Control)

**Files Modified:**
- `feature-chat/.../group/GroupSettingsActivity.java` — Slow Mode section + dialog
- `feature-chat/res/layout/activity_group_settings.xml` — Slow Mode card added
- `feature-chat/.../group/GroupChatActivity.java` — Enforcement logic

**Firebase schema:**
```
groups/{groupId}/groupSettings/slowModeSecs = 0 (off) | 10 | 30 | 60 | 300 | 900 | 3600
```

**Options:** Off / 10s / 30s / 1m / 5m / 15m / 1h

**How to use:**
1. Admin opens Group Settings → SLOW MODE section (visible to admins only)
2. Tap "Slow Mode" row → dialog to pick interval
3. Members who try to send before the interval expires see: "Slow mode: please wait Xs"
4. Admins are exempt from slow mode
5. Status shown next to row: "Off" / "10s" / "5m" / "1h"

---

### Feature 3: Anonymous Posting in Groups (Admin Control)

**Files Modified:**
- `feature-chat/.../group/GroupSettingsActivity.java` — Anonymous Posting section
- `feature-chat/res/layout/activity_group_settings.xml` — Anonymous section added
- `core/.../models/Message.java` — Added `isAnonymous` boolean field
- `feature-chat/.../group/GroupChatActivity.java` — Toggle + name masking on send
- `feature-chat/.../group/GroupTopicChatActivity.java` — Also supports anon posting
- `feature-chat/res/layout/activity_group_topic_chat.xml` — Anon toggle button
- `feature-chat/res/drawable/ic_anon.xml` — Mask/person icon

**Firebase schema:**
```
groups/{groupId}/groupSettings/anonymousPostingEnabled = true | false
messages/{groupId}/{msgId}/isAnonymous = true | false
```

**How to use:**
1. Admin opens Group Settings → ANONYMOUS POSTING section
2. Toggle "Allow Anonymous Posting"
3. In GroupChatActivity, an anon toggle button (🎭) appears in the input bar
4. Member taps it → "Posting anonymously" banner shows
5. When sent: senderName = "Anonymous", senderPhoto = null, isAnonymous = true
6. Original senderId is preserved in Firebase (admin audit trail) but hidden in UI
7. Anonymous messages render with "Anonymous" as sender name and no avatar

---

## Notes

- All 3 features are admin-gated: visible only when `isAdmin = true`
- Slow mode and anonymous settings are stored under `groups/{groupId}/groupSettings/` (same node as existing sendPermission, approvalRequired settings)
- topicsEnabled is stored directly on the Group node (`groups/{groupId}/topicsEnabled`)
- All new Activities are registered in `feature-chat/AndroidManifest.xml`
- Backward-compatible: existing messages without topicId / isAnonymous fields work fine (treated as general / non-anonymous)

