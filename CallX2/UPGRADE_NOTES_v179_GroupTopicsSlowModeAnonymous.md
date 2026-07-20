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



---

# Upgrade Notes — v180: Bot Commands + Chat Backup/Export

## Feature 8: Bot Support / Commands

**New files:**
- `core/.../models/BotCommand.java` — Data model + built-in command catalogue
- `feature-chat/.../bots/BotManager.java` — Slash-command engine (built-ins + custom)
- `feature-chat/.../bots/BotCommandSuggestionsAdapter.java` — Horizontal suggestion strip
- `feature-chat/.../bots/BotSettingsActivity.java` — Admin: manage custom commands
- `feature-chat/res/layout/activity_bot_settings.xml`
- `feature-chat/res/layout/item_bot_command.xml`
- `feature-chat/res/layout/item_bot_suggestion.xml`
- `feature-chat/res/layout/dialog_add_bot_command.xml`
- `feature-chat/res/drawable/ic_bot.xml`

**Modified files:**
- `feature-chat/.../group/GroupChatActivity.java` — "/" TextWatcher, bot intercept in sendText, new menu items
- `feature-chat/AndroidManifest.xml` — BotSettingsActivity registered

**Built-in commands (no setup needed):**

| Command | Description | Admin only? |
|---------|-------------|-------------|
| `/help` | Show all available commands | No |
| `/flip` | Flip a coin (heads/tails) | No |
| `/roll [N]` | Roll a dice (default d6, e.g. `/roll 20`) | No |
| `/stats` | Group member count + message count | No |
| `/announce <text>` | Send bold announcement | ✅ Yes |
| `/pin` | Reminder to use long-press pin | ✅ Yes |
| `/mute @user` | Hint to use Group Info for muting | ✅ Yes |
| `/kick @user` | Hint to use Group Info for removing | ✅ Yes |
| `/remind <time> <msg>` | Timed reminder (10s / 5m / 2h) | No |

**Custom commands (admin-defined):**
1. Admin → Group Chat 3-dot → "🤖 Bot Commands" → opens BotSettingsActivity
2. Tap FAB (+) → enter command name + description + bot reply text
3. Members type `/rules` (or whatever you named it) → bot sends the saved reply
4. Long-press custom command → Edit / Enable-Disable / Delete

**Firebase:**
```
groups/{groupId}/botCommands/{commandName}/
  command, description, response, kind="custom",
  enabled, createdBy, createdAt
```

**Suggestion strip:**
- User types "/" → horizontal RecyclerView appears above keyboard
- Shows matching built-in + custom commands
- Tap any row → auto-fills the command in EditText

---

## Feature 9: Chat Backup / Export

**New files:**
- `feature-chat/.../group/GroupChatExportController.java` — Group export engine (.txt + .html)
- `feature-chat/.../group/ChatBackupActivity.java` — Standalone backup screen
- `feature-chat/res/layout/activity_chat_backup.xml`

**Modified files:**
- `feature-chat/.../group/GroupChatActivity.java` — "📤 Export Chat" + "💾 Backup Chat" in 3-dot menu
- `feature-chat/.../conversation/ChatActivity.java` — "💾 Backup Chat" added to menu
- `feature-chat/res/menu/chat_menu.xml` — action_chat_backup item added
- `feature-chat/AndroidManifest.xml` — ChatBackupActivity registered

**How to use (Export):**
1. Group Chat → 3-dot → "📤 Export Chat"
2. Picker: "With media links" / "Without media"
3. Format picker: Plain text (.txt) or HTML (.html)
4. System share-sheet opens → save to Files, email, send to Drive, etc.

**How to use (Backup):**
1. Group Chat → 3-dot → "💾 Backup Chat"
2. ChatBackupActivity opens
3. Toggle "Include media links" if needed
4. Tap "Export as plain text" / "Export as HTML" → share
5. Tap "Backup Now" → saves .txt to `External Files / CallX_Backups/`
6. Tap "View Saved Backups" → list of previous backup files → tap to share

**Export formats:**
- **.txt** — `[dd/MM/yy, HH:mm] Sender: message text` per line (WhatsApp-style)
- **.html** — Chat-bubble UI with colour-coded sent/received, date separators, anonymous masking

**Anonymous messages:** Export shows "Anonymous" as sender name (identity protected)

**Note:** Media files are not downloaded into the export; Cloudinary URLs are embedded inline when "Include media links" is on.


---

# Upgrade Notes — v181: Message Read-by List in Groups

## Feature 10: Message Read-by List in Groups

**New files:**
- `feature-chat/.../group/GroupReadByActivity.java` — Full-screen "Message Info" with live Read/Delivered/Pending sections
- `feature-chat/.../group/GroupReadByAdapter.java` — RecyclerView adapter for member rows
- `feature-chat/.../group/GroupMessageReadObserver.java` — Real-time Firebase listener for readBy/deliveredBy on a single message
- `feature-chat/res/layout/activity_group_read_by.xml` — Full-screen layout
- `feature-chat/res/layout/item_group_read_by_member.xml` — Member row: avatar + name + timestamp
- `feature-chat/res/drawable/ic_eye.xml` — Eye/visibility icon

**Modified files:**
- `feature-chat/res/layout/item_message_sent.xml` — Added `ll_seen_by` strip with 3 avatar circles + eye icon + count
- `feature-chat/.../conversation/MessagePagingAdapter.java` — `PAYLOAD_READ_BY`, `bindSeenByStrip()`, `OnSeenByClickListener`, `setMemberPhotos()`
- `feature-chat/.../group/GroupChatActivity.java` — Live observer, wires "Seen by" click → GroupReadByActivity, member photos sync
- `feature-chat/AndroidManifest.xml` — GroupReadByActivity registered

---

### Architecture

```
GroupMessages/{groupId}/{msgId}/
  readBy/{uid}      = epoch ms   ← already written by existing markRead()
  deliveredBy/{uid} = epoch ms   ← already written by existing ackDelivered()
```

The backend was already complete. This feature adds the three missing UI layers.

---

### Layer 1 — Inline "Seen by X" strip on sent bubbles

Each outgoing group message now shows a small strip below the bubble:

```
[bubble content ...]
👁 3  [tiny avatar][tiny avatar][tiny avatar]
```

- Only visible on outgoing messages with ≥ 1 reader
- Shows up to 3 reader avatars (overlapping circles, 16dp each)
- Count = total number of readers
- **Tap strip → opens GroupReadByActivity** (full screen)
- Updates in real-time via `PAYLOAD_READ_BY` partial rebind (no full bubble redraw)

---

### Layer 2 — Full-screen GroupReadByActivity

**How to open:**
- Tap the "👁 X" strip under any sent message → GroupReadByActivity opens
- OR: Long-press message → select → ℹ️ Info button in selection bar → MessageInfoBottomSheet (existing) → already shows READ BY sections

**GroupReadByActivity layout:**
- Message preview card at top
- Flat list with 3 sections:
  - **👁 READ BY (X/Y)** — members who read it, newest first, with blue double-tick + timestamp
  - **✓✓ DELIVERED TO (Y)** — delivered but not opened, grey double-tick + timestamp
  - **⏳ PENDING (Z)** — not yet delivered (offline members)

**Live updates:**
- `GroupMessageReadObserver` attaches a Firebase `ValueEventListener` on the message
- As members open the chat, their row moves from Delivered → Read in real time (no refresh needed)

---

### Layer 3 — Live updates in existing MessageInfoBottomSheet

When the selection-bar "ℹ️ Info" button opens `MessageInfoBottomSheet`:
- GroupChatActivity now attaches a `GroupMessageReadObserver` for that message
- If new reads arrive while the sheet is open, `PAYLOAD_READ_BY` triggers a partial rebind of the bubble
- The observer is detached automatically when GroupChatActivity is destroyed

---

### How it works end-to-end

1. **User A** sends a message in a group (10 members)
2. `GroupChatActivity.markRead()` fires for **User B** the moment B opens the chat
3. Firebase write: `groupMessages/{groupId}/{msgId}/readBy/{uidB} = now()`
4. A's `GroupMessageReadObserver` fires → `m.readBy` updated → `PAYLOAD_READ_BY` rebind
5. A's bubble now shows "👁 1" with B's avatar
6. A taps "👁 1" → `GroupReadByActivity` opens:
   - READ BY (1/9): User B — 2:34 PM ✓✓ (blue)
   - DELIVERED TO (3): User C, D, E — timestamps
   - PENDING (5): remaining offline members
7. As more members open the chat, rows move up in real time

---

### Notes
- Anonymous messages: `isAnonymous=true` senders are listed as "Anonymous" in export but their readBy entry is still tracked normally (they are a reader, not a sender)
- Admin & non-admin alike can see read-by info for their OWN sent messages
- Incoming messages (received by current user) show only a simple status row in MessageInfoBottomSheet — not a per-member breakdown (same as WhatsApp)
- The `btn_selection_info` in GroupChatActivity's selection bar already wired `showGroupMessageInfoDialog()` — this was already working; the new feature adds inline visibility + dedicated Activity

