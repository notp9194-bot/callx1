# Broadcast List Feature — CallX2

## Overview
WhatsApp-style Broadcast List: ek hi message multiple contacts ko simultaneously bhejo.
Har recipient ko message unke personal 1-on-1 chat mein milta hai — woh nahi jaante
ki koi aur bhi receive kar raha hai.

---

## Files Added

### Java (package: `com.callx.app.broadcast`)

| File | Purpose |
|------|---------|
| `BroadcastList.java` | Firebase data model for a broadcast list |
| `BroadcastMessage.java` | Firebase data model for a sent message |
| `BroadcastListsActivity.java` | Main screen — sabhi lists dikhata hai (RecyclerView + FAB) |
| `CreateBroadcastActivity.java` | Create/edit list — naam + recipients multi-select |
| `BroadcastChatActivity.java` | Message composer + history + per-list delivery dispatch |
| `BroadcastFCMHandler.java` | FCM handler for "broadcast_message" push type |

### Layouts (`res/layout/`)

| File | Screen |
|------|--------|
| `activity_broadcast_lists.xml` | BroadcastListsActivity |
| `activity_create_broadcast.xml` | CreateBroadcastActivity |
| `activity_broadcast_chat.xml` | BroadcastChatActivity |
| `item_broadcast_list.xml` | Row in BroadcastListsActivity |
| `item_recipient_select.xml` | Row in CreateBroadcastActivity |
| `item_broadcast_message.xml` | Sent message bubble in BroadcastChatActivity |

### Other Resources
- `res/menu/menu_broadcast_chat.xml` — 3-dot menu for BroadcastChatActivity
- `res/values/broadcast_colors.xml` — Feature color palette
- `res/drawable/bg_broadcast_avatar.xml` — Circular avatar background
- `res/drawable/bg_send_button.xml` — Green circular send button

### Manifest (`AndroidManifest.xml`)
Three new `<activity>` entries added (all `exported="false"`):
- `com.callx.app.broadcast.BroadcastListsActivity`
- `com.callx.app.broadcast.CreateBroadcastActivity`
- `com.callx.app.broadcast.BroadcastChatActivity`

### FCM (`CallxMessagingService.java`)
New `"broadcast_message"` type routed to `BroadcastFCMHandler.handle()`.

---

## Firebase Database Structure

```
broadcast_lists/
  {ownerUid}/
    {listId}/
      id:              String
      name:            String        ("Meri Team", "Family", …)
      createdAt:       Long          (epoch ms)
      updatedAt:       Long
      recipients:
        {uid}:         Boolean       (true for each member)
      lastMessage:     String
      lastMessageType: String        (text|image|video|audio|file)
      lastMessageTime: Long
      sentCount:       Long          (ServerValue.increment)

broadcast_messages/
  {listId}/
    {messageId}/
      id:              String
      text:            String
      type:            String
      mediaUrl:        String
      fileName:        String
      caption:         String
      senderId:        String
      timestamp:       Long
      deliveredCount:  Integer
      totalRecipients: Integer
```

Recipients ko message deliver hota hai **existing chat schema** mein:
```
chats/{chatId}/messages/{messageId}/
  broadcast: true     ← flag — recipient's ChatActivity can show 📢 badge
  (+ all normal message fields)

contacts/{recipientUid}/{senderUid}/
  lastMessage, lastMessageType, lastMessageTime, unread (++)

contacts/{senderUid}/{recipientUid}/
  lastMessage, lastMessageType, lastMessageTime
```

---

## Entry Point Integration

**ChatsFragment / ChatsActivity mein add karo:**

```java
// Option A: Toolbar overflow menu item
// res/menu/menu_chats.xml mein:
<item android:id="@+id/action_broadcast"
      android:title="Broadcast List"
      app:showAsAction="never"/>

// ChatsFragment.onOptionsItemSelected() mein:
if (id == R.id.action_broadcast) {
    startActivity(new Intent(getContext(), BroadcastListsActivity.class));
    return true;
}

// Option B: FAB long-press (agar FAB already hai)
fabAction.setOnLongClickListener(v -> {
    startActivity(new Intent(this, BroadcastListsActivity.class));
    return true;
});
```

**MainActivity mein (existing pattern ke saath):**
```java
// onCreateOptionsMenu / onOptionsItemSelected mein:
case R.id.action_broadcast:
    startActivity(new Intent(this, BroadcastListsActivity.class));
    return true;
```

---

## Feature Flow

```
User taps "Broadcast List"
    ↓
BroadcastListsActivity
    ↓ (FAB tap)
CreateBroadcastActivity
  ├── List name type karo
  ├── Contacts multi-select karo (Firebase contacts load)
  └── Save → broadcast_lists/{uid}/{listId}
    ↓ (list tap)
BroadcastChatActivity
  ├── Message type karo
  ├── "Send" tap
  └── dispatchBroadcast()
        ├── Save to broadcast_messages/{listId}/{msgId}
        ├── For each recipient:
        │     ├── Write to chats/{chatId}/messages
        │     ├── Update contacts/{recipientUid}/{senderUid} (unread++)
        │     ├── Update contacts/{senderUid}/{recipientUid}
        │     └── Send FCM push
        └── Update broadcast_lists lastMessage, sentCount
```

---

## Max Recipients
`CreateBroadcastActivity.MAX_RECIPIENTS = 256` (production guard — change as needed)

---

## Recipient's Experience
- Message unke normal ChatActivity mein dikhega (sender ke saath)
- Message payload mein `"broadcast": true` flag hoga
- ChatActivity is flag ko use karke `📢` badge dikh sakta hai (optional — existing ChatActivity mein add karo)
- Recipients ek doosre ko nahi jaante (same as WhatsApp)
