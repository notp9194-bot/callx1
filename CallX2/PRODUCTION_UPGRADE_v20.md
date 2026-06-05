# CallX2 v20 — Production Chat System Upgrade

**Date:** June 2026
**Baseline:** v19 (29 notification features + reel notifications)
**Focus:** Chat system production hardening

---

## Summary

v20 adds **8 major missing features** and **3 critical fixes** to the chat system,
making it production-equivalent to WhatsApp / Telegram for the core chat experience.

---

## 🔴 Critical Fixes

### Fix 1 — Group E2E Encryption (was MISSING)
**Files:** `core/.../encryption/GroupE2EManager.java`

GroupChatActivity previously sent all messages as **plaintext** to Firebase.
v20 adds a Sender-Key based group encryption system:
- Group admin generates a random 256-bit AES group key
- Admin encrypts it with each member's ECDH public key (via E2EEncryptionManager)
- Messages encrypted with AES-256-GCM using the shared group key
- Key rotation on member removal
- Firebase path: `/group_keys/{groupId}/{memberUid}`

**Integration:** Call `GroupE2EManager.getInstance(ctx).encryptMessage(groupId, text)`
before pushing to Firebase. Decrypt on receive.

---

### Fix 2 — Firebase Rules: Group Chat (was MISSING)
**Files:** `firebase_rules/firebase_chat_rules.json` (version upgraded: v1 → v2)

Previously only 1:1 chats were covered. Group rules added:
- `/groups/$groupId/messages` — only members can read/write
- `/groups/$groupId/members` — admin controls, member self-remove
- `/groups/$groupId/admins` — admin-only writes
- `/groups/$groupId/inviteCode` — admin-only write
- `/group_keys/$groupId/$uid` — own-read, admin-write
- `/liveLocations/$chatId/$uid` — own-write, chat-member-read
- Rate: timestamp validation (≤ now + 5s) on ALL writes

---

### Fix 3 — DB Migration v9 → v10
**Files:** `core/.../db/AppDatabase.java`

Bumped from v9 to v10 with proper Room migration. No fallback to destructive.
New columns added via ALTER TABLE (safe for existing users' data).

---

## 🟡 New Features

### Feature 1 — Message Search (In-Chat + Global)
**Files:**
- `feature-chat/.../search/MessageSearchActivity.java`
- `feature-chat/.../search/MessageSearchAdapter.java`
- `core/.../db/dao/MessageDao.java` (+`searchInChat`, `searchGlobal`)

**How to launch:**
```java
// Global search
startActivity(new Intent(ctx, MessageSearchActivity.class));

// In-chat search  
Intent i = new Intent(ctx, MessageSearchActivity.class);
i.putExtra("chatId", chatId);
i.putExtra("chatName", partnerName);
startActivity(i);
```

Features:
- 300ms debounce (no lag while typing)
- Highlighted matched text (yellow background, bold)
- Tapping a result opens ChatActivity scrolled to that message
- Searches only non-deleted messages

---

### Feature 2 — Disappearing Messages
**Files:**
- `feature-chat/.../disappear/DisappearingMessageWorker.java`
- `core/.../db/entity/MessageEntity.java` (+`disappearAt`)
- `core/.../db/entity/ChatEntity.java` (+`disappearTimer`)
- `core/.../db/dao/MessageDao.java` (+`getExpiredMessages`, `markDeleted`)

**Options:** 24 hours, 7 days, 90 days (+ 5 seconds for testing)

**How it works:**
1. Chat admin sets `ChatEntity.disappearTimer` (e.g. 86400000 for 24h)
2. On send: `msg.disappearAt = msg.timestamp + chat.disappearTimer`
3. `DisappearingMessageWorker` runs every 15 min, finds expired messages, soft-deletes

**Schedule in Application.onCreate():**
```java
DisappearingMessageWorker.schedule(this);
```

---

### Feature 3 — Chat Archive
**Files:**
- `feature-chat/.../archive/ChatArchiveHelper.java`
- `core/.../db/entity/ChatEntity.java` (+`archived`)
- `core/.../db/dao/ChatDao.java` (+`getActiveChatsSorted`, `getArchivedChats`, `updateArchived`)

**How to use in ChatsFragment:**
```java
// Main list (excludes archived)
db.chatDao().getActiveChatsSorted().observe(this, adapter::submitList);

// Archive screen
db.chatDao().getArchivedChats().observe(this, archivedAdapter::submitList);

// Archive a chat (on long press)
ChatArchiveHelper.archive(ctx, chatId, true);
```

Auto-unarchives on new message (call `ChatArchiveHelper.autoUnarchiveOnMessage`).

---

### Feature 4 — Group Read Receipts
**Files:**
- `core/.../models/Message.java` (+`groupReadBy: Map<String, Long>`)
- `core/.../db/entity/MessageEntity.java` (+`groupReadBy: String`)

**Firebase path:** `groups/{groupId}/messages/{msgId}/readBy/{uid} = serverTimestamp`

**How to mark read in GroupChatActivity:**
```java
groupRef.child("messages").child(msgId)
        .child("readBy").child(myUid)
        .setValue(ServerValue.TIMESTAMP);
```

**Display in message info:** Parse `msg.groupReadBy` (comma-separated UIDs), show
"Read by N of M members" with member names.

---

### Feature 5 — Voice Message Speed Control
**Files:** `core/.../utils/voice/VoiceSpeedController.java`

Cycles: 1× → 1.5× → 2× → 0.5× (same as WhatsApp)
Requires API 23+ (Android 6.0).

**Add to MessageAdapter/MessagePagingAdapter audio ViewHolder:**
```java
VoiceSpeedController speedCtrl = new VoiceSpeedController(tvSpeedBtn);
tvSpeedBtn.setOnClickListener(v -> {
    speedCtrl.cycleSpeed();
    if (mediaPlayer.isPlaying()) speedCtrl.apply(mediaPlayer);
});
playBtn.setOnClickListener(v -> {
    if (!mediaPlayer.isPlaying()) {
        speedCtrl.apply(mediaPlayer);
        mediaPlayer.start();
    } else { mediaPlayer.pause(); }
});
```

---

### Feature 6 — Location Sharing
**Files:** `feature-chat/.../location/LocationMessageHelper.java`

Message type `"location"`:
- Text field: `"location|lat|lng|address"`
- On tap: opens Google Maps at coordinates
- Requires `ACCESS_FINE_LOCATION` permission

Message type `"live_location"`:
- Same text encoding + `liveLocationExpiry` field
- Updates every 30s to Firebase `/liveLocations/{chatId}/{uid}`
- Auto-stops after expiry

**Send location message:**
```java
LocationMessageHelper.getCurrentLocation(ctx, (lat, lng, address) -> {
    Message msg = new Message();
    msg.type = "location";
    msg.text = LocationMessageHelper.encodeLocation(lat, lng, address);
    msg.locationLat = lat;
    msg.locationLng = lng;
    sendMessage(msg);
});
```

---

### Feature 7 — Group Invite Links
**Files:** `feature-chat/.../group/GroupInviteLinkActivity.java`

Launch from `GroupSettingsActivity`:
```java
Intent i = new Intent(ctx, GroupInviteLinkActivity.class);
i.putExtra("groupId", groupId);
i.putExtra("groupName", groupName);
startActivity(i);
```

Features:
- Auto-generates 12-char UUID invite code
- Copy / Share / Reset link
- Deep link format: `https://callx.app/invite/{code}`
- Firebase path: `/groups/{groupId}/inviteCode`
- Admin-only (enforced by Firebase rules)

---

## 📊 DB Schema Changes (v9 → v10)

| Table | Column | Type | Purpose |
|---|---|---|---|
| `messages` | `disappearAt` | `INTEGER` | Auto-delete timestamp |
| `messages` | `groupReadBy` | `TEXT` | Comma-separated UIDs |
| `messages` | `locationLat` | `REAL` | Latitude for location msg |
| `messages` | `locationLng` | `REAL` | Longitude for location msg |
| `messages` | `liveLocationExpiry` | `INTEGER` | When live location stops |
| `chats` | `archived` | `INTEGER` | 0=active, 1=archived |
| `chats` | `disappearTimer` | `INTEGER` | Duration in ms (0=off) |

---

## 📁 New Files

```
core/
  src/main/java/com/callx/app/
    encryption/
      GroupE2EManager.java                 ← Group E2E AES-256-GCM
    utils/voice/
      VoiceSpeedController.java            ← 0.5x/1x/1.5x/2x playback

feature-chat/
  src/main/java/com/callx/app/
    search/
      MessageSearchActivity.java           ← In-chat + global search
      MessageSearchAdapter.java            ← Search results with highlighting
    disappear/
      DisappearingMessageWorker.java       ← Auto-delete expired messages
    archive/
      ChatArchiveHelper.java               ← Archive/unarchive chats
    location/
      LocationMessageHelper.java           ← Location message encode/decode
    group/
      GroupInviteLinkActivity.java         ← Shareable group invite links
```

## 📝 Modified Files

```
core/
  db/AppDatabase.java                      ← v9→v10 migration added
  db/dao/MessageDao.java                   ← searchInChat, searchGlobal, getExpiredMessages
  db/dao/ChatDao.java                      ← getActiveChatsSorted, archive queries
  db/entity/MessageEntity.java             ← 5 new columns
  db/entity/ChatEntity.java                ← 2 new columns (archived, disappearTimer)
  models/Message.java                      ← disappearAt, groupReadBy, location fields
  utils/E2EEncryptionManager.java          ← +encryptWithPublicKey() helper

firebase_rules/
  firebase_chat_rules.json                 ← v2: group rules, group_keys, liveLocations
```

---

## 🔧 Integration Checklist

- [ ] Declare `GroupInviteLinkActivity` in `feature-chat/AndroidManifest.xml`
- [ ] Declare `MessageSearchActivity` in `feature-chat/AndroidManifest.xml`
- [ ] Call `DisappearingMessageWorker.schedule(ctx)` in `Application.onCreate()`
- [ ] Deploy updated Firebase rules: `firebase deploy --only database`
- [ ] Add `ACCESS_FINE_LOCATION` to app `AndroidManifest.xml` (for location sharing)
- [ ] Update `ChatsFragment` to use `getActiveChatsSorted()` instead of `getAllChats()`
- [ ] Add speed button (TextView) to audio message item layouts
- [ ] Add search icon to `ChatActivity` menu → launch `MessageSearchActivity`
- [ ] Add archive entry at bottom of ChatsFragment chat list
