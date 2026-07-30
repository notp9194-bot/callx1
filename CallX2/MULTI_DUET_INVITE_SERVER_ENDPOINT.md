# Multi-Duet Invite — Server Endpoint Guide

> ⚠️ **v2 FIX**: v1 of this doc told the server to send FCM data keys
> `from_uid` / `from_name` / `from_photo`, but `ReelFCMNotificationHandler.java`
> has always read `sender_uid` / `sender_name` / `sender_photo` (same
> convention used by every other notif type in this app — see
> `SERVER_REPOST_ENDPOINTS.md`, `UPGRADE_NOTES_v8_AllGapsFixed.md`, etc).
> That mismatch meant the invite notification always showed a blank
> host name/photo. **All key names below are now corrected to match the
> real handler code.**

## 1. Multi-Duet Invite

### Android kya bhejta hai

`PushNotify.notifyMultiDuetInvite()` → POST `Constants.SERVER_URL/notify/reel`

```json
{
  "toUid":     "invited_user_uid",
  "fromUid":   "host_uid",
  "fromName":  "Host Name",
  "fromPhoto": "https://...",
  "reelId":    "original_reel_id",
  "sessionId": "multi_duet_sessions_key",
  "reelThumb": "https://...",
  "type":      "multi_duet_invite"
}
```

### Node.js server me ye case add karo `/notify/reel` route me:

```js
// server/routes/notify.js  (ya jahan bhi /notify/reel handle hota hai)

case 'multi_duet_invite': {
  const { toUid, fromUid, fromName, fromPhoto, reelId, sessionId, reelThumb } = body;

  // FCM token fetch karo
  const userSnap = await admin.database().ref(`users/${toUid}`).once('value');
  const fcmToken = userSnap.val()?.fcmToken;
  if (!fcmToken) break;

  await admin.messaging().send({
    token: fcmToken,
    data: {
      reel_notif_type: 'multi_duet_invite',
      // ✅ FIXED: keys now match ReelFCMNotificationHandler.java's
      // TYPE_MULTI_DUET_INVITE case (get(data, "sender_name") etc.),
      // same convention as every other notif type in this app.
      sender_uid:   fromUid    || '',
      sender_name:  fromName   || '',
      sender_photo: fromPhoto  || '',
      reel_id:      reelId     || '',
      session_id:   sessionId  || '',
      reel_thumb:   reelThumb  || '',
    },
    android: { priority: 'high' },
  });
  break;
}
```

Android side already handles this correctly — no app changes needed for
this part, `ReelFCMNotificationHandler.TYPE_MULTI_DUET_INVITE` reads
`sender_name` / `sender_uid` / `sender_photo` / `reel_id` / `session_id` /
`reel_thumb` and deep-links into `MultiDuetAcceptActivity`.

---

## 2. Multi-Duet Ready (✅ NEW — fixes the "host must keep the screen open" gap)

Sent once every participant in a session has `status="recorded"`. This is
what lets the host's device reliably know it's time to download all the
clips and merge them, even if `MultiDuetActivity` isn't open at that
moment (participants record async, often hours/days apart).

### Android kya bhejta hai

`PushNotify.notifyMultiDuetReady()` → POST `Constants.SERVER_URL/notify/reel`

```json
{
  "toUid":     "host_uid",
  "sessionId": "multi_duet_sessions_key",
  "type":      "multi_duet_ready"
}
```

### Node.js server me ye case add karo:

```js
case 'multi_duet_ready': {
  const { toUid, sessionId } = body;

  const userSnap = await admin.database().ref(`users/${toUid}`).once('value');
  const fcmToken = userSnap.val()?.fcmToken;
  if (!fcmToken) break;

  await admin.messaging().send({
    token: fcmToken,
    data: {
      reel_notif_type: 'multi_duet_ready',
      session_id: sessionId || '',
    },
    android: { priority: 'high' },
  });
  break;
}
```

Android side: `ReelFCMNotificationHandler.TYPE_MULTI_DUET_READY` reads
`session_id` and deep-links into `MultiDuetActivity` with extra
`resume_session_id`, which makes the activity re-attach its Firebase
listener and immediately re-check/trigger the composite — instead of
relying on the original listener that only existed while the host
happened to be looking at that exact screen.
