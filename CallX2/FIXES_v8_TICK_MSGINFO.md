# Tick System + Message Info — Fixes v8

## Files Changed

| File | Fix |
|------|-----|
| AppDatabase.java | v9→v10; MIGRATION_9_10 adds deliveredAt/readAt/deliveredToJson/readByJson columns |
| MessageInfoActivity.java | Group messages use getGroupMessagesRef() path, not getMessagesRef() |
| GroupChatActivity.java | markRead stores Long timestamp (not boolean); markDelivered added; group tick aggregation |
| ChatActivity.java | markDelivered handles null status (brand-new messages) |
| CallxMessagingService.java | Background group delivery sets deliveredTo/{uid} = timestamp |

---

## Fix 1 — AppDatabase (Room Migration)
**Bug:** deliveredAt, readAt, deliveredToJson, readByJson columns missing from Room DB.
App would crash on upgrade with "Room migration required" error.

**Fix:** MIGRATION_9_10 adds 4 columns. DB version bumped 9→10.

---

## Fix 2 — MessageInfoActivity (Wrong Firebase Path for Groups)
**Bug:** Group messages are at `groupMessages/{groupId}/{msgId}` but the activity
always used `messages/{chatId}/{msgId}` → "Message not found" for every group message.

**Fix:** isGroup check added. getGroupMessagesRef() used for group, getMessagesRef() for 1-on-1.

---

## Fix 3 — GroupChatActivity (3 bugs)

### 3a. readBy stores boolean, should store timestamp
**Bug:** `setValue(true)` — MessageInfoActivity expects Long millis.
Caused ClassCastException when populating group read list.
**Fix:** `setValue(System.currentTimeMillis())`

### 3b. deliveredTo never set
**Bug:** markDelivered() method was missing entirely.
"Delivered To" list in MessageInfoActivity always empty for groups.
**Fix:** markDelivered() added. Sets deliveredTo/{uid} = timestamp. Called in onChildAdded.

### 3c. Group tick always shows single gray (sent)
**Bug:** m.status is a flat string. Group tick never upgraded to "delivered" or "read"
because nothing computed it from the readBy/deliveredTo maps.
**Fix:** updateGroupTickStatus() added. Called from onChildChanged when we are the sender.
Compares readBy.count and deliveredTo.count against (totalMembers - 1).

---

## Fix 4 — ChatActivity (markDelivered skips null status)
**Bug:** `"sent".equals(null)` = false. Brand-new messages with null status field
never got delivery confirmation.
**Fix:** Added null check: `m.status == null || "sent".equals(m.status)`

---

## Fix 5 — CallxMessagingService (Group background delivery)
**Bug:** markDeliveredBackground() only set deliveredAt for 1-on-1.
Group messages never had deliveredTo/{uid} set in background.
**Fix:** After updating status+deliveredAt, also sets deliveredTo/{myUid} = timestamp
for group messages (detected via path containing "groupMessages").

---

## Room DB Migration SQL
```sql
ALTER TABLE messages ADD COLUMN deliveredAt INTEGER DEFAULT NULL;
ALTER TABLE messages ADD COLUMN readAt INTEGER DEFAULT NULL;
ALTER TABLE messages ADD COLUMN deliveredToJson TEXT DEFAULT NULL;
ALTER TABLE messages ADD COLUMN readByJson TEXT DEFAULT NULL;
```

## Deploy Steps
1. Integrate all 5 files
2. Clean build — File > Invalidate Caches / Restart
3. Deploy to device — migrations run automatically on first launch
4. Firebase rules: deploy firebase_rules/firebase_chat_rules.json
