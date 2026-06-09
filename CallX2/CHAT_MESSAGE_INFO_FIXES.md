# Chat Message Info System — Fixes & New Features

## Kya Kya Add/Fix Kiya Gaya

---

### 1. `deliveredAt` + `readAt` Timestamps (Message.java + MessageEntity.java)

**Problem:** `status` field sirf "sent/delivered/read" store karta tha — kab deliver/read hua ye bilkul nahi pata tha.

**Fix:**
- `Message.java` mein `deliveredAt` aur `readAt` (Long millis) add kiye
- `MessageEntity.java` mein same fields add kiye (Room DB persist)
- `ChatActivity.markRead()` ab Firebase mein `readAt = now` bhi set karta hai
- Naya `ChatActivity.markDelivered()` method — `deliveredAt = now` set karta hai jab message arrive kare

**Room DB Migration needed:**
```sql
ALTER TABLE messages ADD COLUMN deliveredAt INTEGER;
ALTER TABLE messages ADD COLUMN readAt INTEGER;
ALTER TABLE messages ADD COLUMN deliveredToJson TEXT;
ALTER TABLE messages ADD COLUMN readByJson TEXT;
```

---

### 2. Group `readBy` + `deliveredTo` Maps (Message.java)

**Problem:** Group chats mein kaun kaun ne padha — ye data store hi nahi hota tha.

**Fix:**
- `Message.java` mein `Map<String, Long> readBy` add kiya (uid → readAt timestamp)
- `Message.java` mein `Map<String, Long> deliveredTo` add kiya (uid → deliveredAt timestamp)
- Room mein JSON string ke form mein store hota hai (`readByJson`, `deliveredToJson`)
- `MessageInfoActivity.parseReadMap()` JSON ko Map mein convert karta hai

---

### 3. Dedicated `MessageInfoActivity` (NEW FILE)

**Problem:** Info sirf ek plain `AlertDialog` tha — 4 lines text.

**Fix:** Naya `MessageInfoActivity.java` banaya jo dikhata hai:
- **Sent at** — exact date + time
- **Status** — Sent / Delivered / Read / Pending / Failed
- **Delivered at** — exact timestamp (1-on-1 chats)
- **Read at** — exact timestamp (1-on-1 chats)
- **Message type** — Text / Image / Video / Audio / File
- **Media details** — file size, duration (non-text messages)
- **Edited at** — agar message edit hua toh kab
- **Group section (group chats only):**
  - **Read By** — list of members + their read timestamps + avatars
  - **Delivered To** — list of members + their delivery timestamps + avatars

**Files:**
- `feature-chat/src/main/java/com/callx/app/conversation/MessageInfoActivity.java`
- `feature-chat/src/main/res/layout/activity_message_info.xml`

---

### 4. Firebase Rules Update (`firebase_chat_rules.json`)

**Problem:** `deliveredAt`, `readAt`, `deliveredTo`, `readBy`, `editedAt` — koi bhi rule nahi tha. Writes silently blocked hote the.

**Fix:** Naye security rules add kiye:
```json
"deliveredAt": { ".write": "recipient only", ".validate": "timestamp <= now+5s" }
"readAt":      { ".write": "recipient only", ".validate": "timestamp <= now+5s" }
"deliveredTo": { "$uid": { ".write": "own uid only" } }
"readBy":      { "$uid": { ".write": "own uid only" } }
"editedAt":    { ".write": "both participants", ".validate": "timestamp <= now+5s" }
```

**Deploy:**
```bash
firebase deploy --only database
```

---

### 5. `MessageAdapter` — Info Button Fix

**Problem:** Info button sirf **sender** ke liye visible tha (`if (sent)` check). Receiver apne received messages ka info nahi dekh sakta tha.

**Fix:** Info button ab **dono** ke liye show hota hai:
- Sender: "ℹ  Info" — delivered/read timestamps dikhata hai
- Receiver: "ℹ  Message Info" — apna received time + read time dikhata hai
- Sirf deleted messages mein info button hidden rehta hai

---

### 6. `MessageDao` — New DAO Methods

**Problem:** Room mein `deliveredAt`/`readAt` update karne ke liye koi method nahi tha.

**Fix:** 4 naye `@Query` methods add kiye:
- `updateDeliveredAt(messageId, timestamp)`
- `updateReadAt(messageId, timestamp)`
- `updateReadByJson(messageId, json)` — group chats ke liye
- `updateDeliveredToJson(messageId, json)` — group chats ke liye

---

### 7. `modelToEntity` + `entityToModel` Fixes

**Problem:** Conversion mein `editedAt` field missing tha — Room se load hone ke baad edit time lost ho jata tha.

**Fix:**
- `modelToEntity`: `editedAt`, `deliveredAt`, `readAt`, `deliveredToJson`, `readByJson` ab persist hote hain
- `entityToModel`: Same fields restore hote hain, aur `mapToJson()` helper bhi add kiya

---

### 8. `AndroidManifest.xml` — Activity Registration

```xml
<activity android:name="com.callx.app.conversation.MessageInfoActivity"
    android:exported="false"
    android:screenOrientation="portrait"
    android:parentActivityName="com.callx.app.conversation.ChatActivity"/>
```

---

## Files Changed

| File | Change |
|------|--------|
| `core/.../models/Message.java` | +deliveredAt, readAt, readBy, deliveredTo |
| `core/.../db/entity/MessageEntity.java` | +deliveredAt, readAt, deliveredToJson, readByJson |
| `core/.../db/dao/MessageDao.java` | +4 new update methods |
| `feature-chat/.../ChatActivity.java` | markRead fix, markDelivered new, modelToEntity/entityToModel fix, showMessageInfoDialog replaced |
| `feature-chat/.../MessageAdapter.java` | Info button shown for both sender + receiver |
| `feature-chat/.../MessageInfoActivity.java` | **NEW FILE** — dedicated info screen |
| `feature-chat/.../activity_message_info.xml` | **NEW FILE** — info screen layout |
| `feature-chat/.../AndroidManifest.xml` | MessageInfoActivity registered |
| `firebase_rules/firebase_chat_rules.json` | deliveredAt, readAt, deliveredTo, readBy, editedAt rules added |

---

## Group Chat Integration Note

`GroupChatActivity` mein bhi ye changes add karne honge:
1. Message receive hone par: `messagesRef.child(msgId).child("deliveredTo").child(myUid).setValue(now)`
2. Message read hone par: `messagesRef.child(msgId).child("readBy").child(myUid).setValue(now)`
3. Firebase ChildEventListener mein: `db.messageDao().updateReadByJson(...)` call karo
