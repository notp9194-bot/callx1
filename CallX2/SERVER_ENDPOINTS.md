# Server Endpoints — Status System

## Base: /api/status (protected, requires Firebase ID Token in Authorization header)

---

### POST /api/status/post
Post a new status. For large media, client uploads to Cloudinary first and passes URLs.
```json
Request:
{
  "type": "text|image|video|gif|link|poll",
  "text": "...",
  "mediaUrl": "...",
  "thumbnailUrl": "...",
  "bgColor": "#075E54",
  "fontStyle": 0,
  "privacy": "contacts",
  "privacyList": ["uid1", "uid2"],
  "expiresAt": 1718000000000,
  "linkUrl": "...",
  "pollQuestion": "...",
  "pollOptions": ["Yes", "No"],
  "locationName": "...",
  "locationLat": 28.6,
  "locationLng": 77.2,
  "feeling": "happy",
  "feelingEmoji": "😊",
  "mentions": ["uid3"],
  "hashtags": ["travel"]
}
Response: { "statusId": "...", "expiresAt": 1718000000000 }
```

### DELETE /api/status/:statusId
Soft-delete a status. Sets deleted=true in Firebase.
```json
Response: { "ok": true }
```

### POST /api/status/:statusId/seen
Mark a status as seen. Use only if you can't write directly to Firebase from client.
```json
Request:  { "ownerUid": "...", "viewerUid": "..." }
Response: { "ok": true }
```

### POST /api/status/:statusId/react
Set or update an emoji reaction.
```json
Request:  { "ownerUid": "...", "emoji": "❤️" }
Response: { "ok": true }
```

### DELETE /api/status/:statusId/react
Remove a reaction.
```json
Response: { "ok": true }
```

### GET /api/status/:ownerUid/feed
Get active statuses for a specific user (respects privacy — server filters based on viewer).
```json
Response: { "items": [StatusItem, ...] }
```

### GET /api/status/highlights/:ownerUid
Get all highlights for a user profile.
```json
Response: { "highlights": [StatusHighlight, ...] }
```

### POST /api/status/highlights
Create or update a highlight.
```json
Request:  { "title": "Vacation", "statusIds": ["id1", "id2"], "coverUrl": "..." }
Response: { "highlightId": "..." }
```

### GET /api/status/archive
Get current user's archived statuses (paginated).
```json
Query: ?page=1&limit=20
Response: { "items": [StatusItem, ...], "total": 42 }
```

### POST /api/status/cleanup (Server cron — NOT a client endpoint)
Moves all expired statuses to archive. Run every hour via Cloud Scheduler.
```json
Response: { "archived": 15, "errors": 0 }
```

### POST /api/status/extend/:statusId
Extend a status TTL by 24h.
```json
Response: { "newExpiresAt": 1718000000000 }
```

### POST /api/status/poll/:statusId/vote
Submit a poll vote.
```json
Request:  { "ownerUid": "...", "optionIndex": 1 }
Response: { "ok": true, "counts": [5, 12, 3] }
```

### GET /api/status/analytics/:statusId
Get detailed analytics for owner.
```json
Response: {
  "totalViews": 42,
  "uniqueViewers": 38,
  "viewsByHour": { "0": 5, "1": 12, ... },
  "reactionCounts": { "❤️": 8, "😂": 3 },
  "topViewers": [{ "uid": "...", "name": "...", "photo": "...", "ts": 123 }]
}
```

---

## FCM Payload for Status Notifications

### New status posted (sent to each contact)
```json
{
  "type": "new_status",
  "fromUid": "...",
  "fromName": "...",
  "fromPhoto": "...",
  "statusId": "...",
  "statusType": "text|image|video|...",
  "text": "...",
  "mediaUrl": "..."
}
```

### Status seen
```json
{
  "type": "status_seen",
  "viewerUid": "...",
  "viewerName": "...",
  "viewerPhoto": "...",
  "statusId": "..."
}
```

### Status reaction
```json
{
  "type": "status_reaction",
  "reactorUid": "...",
  "reactorName": "...",
  "reactorPhoto": "...",
  "emoji": "❤️",
  "statusId": "..."
}
```

### Status reply
```json
{
  "type": "status_reply",
  "senderUid": "...",
  "senderName": "...",
  "replyText": "...",
  "statusId": "...",
  "chatId": "..."
}
```

---

## PushNotify.java — New methods to add

```java
// In PushNotify.java, add these static methods:

public static void notifyStatusSeen(String ownerUid, String viewerUid,
    String viewerName, String viewerPhoto, String statusId) {
    Map<String, String> data = new HashMap<>();
    data.put("type", "status_seen");
    data.put("viewerUid", viewerUid);
    data.put("viewerName", viewerName);
    data.put("viewerPhoto", viewerPhoto);
    data.put("statusId", statusId);
    sendFcm(ownerUid, "👁 " + viewerName + " saw your status", null, data);
}

public static void notifyStatusReaction(String ownerUid, String reactorUid,
    String reactorName, String reactorPhoto, String emoji, String statusId) {
    Map<String, String> data = new HashMap<>();
    data.put("type", "status_reaction");
    data.put("reactorUid", reactorUid);
    data.put("reactorName", reactorName);
    data.put("reactorPhoto", reactorPhoto);
    data.put("emoji", emoji);
    data.put("statusId", statusId);
    sendFcm(ownerUid, reactorName + " reacted " + emoji + " to your status", null, data);
}
```
