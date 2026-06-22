# Build Fix v5.1 — 3 Errors Fixed

## Errors jo the

```
error: no such column: groupId  (MessageDao.java:39, :103, :123)
error: Not sure how to convert Cursor to PagingSource  (secondary, paging artifact missing)
```

## Root Cause

1. `messages` table mein `groupId` column exist nahi karta.
2. `room-paging` artifact missing tha (PagingSource support ke liye zaroori hai).

---

## Fix 1 — MessageDao.java (already applied)

Sab `groupId` column queries remove kar di gayi hain.
Ab sab queries `chatId` column use karti hain.
Group messages ke liye `chatId = groupId` store karo (koi naya column nahi chahiye).

---

## Fix 2 — GroupChatActivity.java (already applied)

`parseGroupMessage()` mein:
```java
// V5 (WRONG — groupId column nahi hai):
e.groupId = groupId;

// V5.1 (CORRECT — chatId column already exist karta hai):
e.chatId = groupId;
```

---

## Fix 3 — build.gradle (MANUAL STEP)

`core/build.gradle` ya `feature-chat/build.gradle` mein add karo:

```groovy
// Paging3 + Room integration (PagingSource support)
implementation "androidx.room:room-paging:2.5.2"
```

Bina is artifact ke Room `PagingSource<Integer, MessageEntity>` return type
support nahi karta → "Not sure how to convert Cursor" error aata hai.

Room version match karo apne existing `room-runtime` version se.
Agar `room-runtime:2.6.x` use kar rahe ho to `room-paging:2.6.x` use karo.

---

## Summary

| Error | Fix |
|-------|-----|
| `no such column: groupId` | `e.chatId = groupId` set karo GroupChatActivity mein |
| `Not sure how to convert Cursor to PagingSource` | `room-paging` add karo build.gradle mein |
| Secondary PagingSource error on groupId queries | MessageDao queries fixed (chatId use) |
