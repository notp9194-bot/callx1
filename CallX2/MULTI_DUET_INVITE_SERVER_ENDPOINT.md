# Multi-Duet Invite — Server Endpoint Guide

## Android kya bhejta hai

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

## Node.js server me ye case add karo `/notify/reel` route me:

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
      from_uid:        fromUid    || '',
      from_name:       fromName   || '',
      from_photo:      fromPhoto  || '',
      reel_id:         reelId     || '',
      session_id:      sessionId  || '',
      reel_thumb:      reelThumb  || '',
    },
    android: { priority: 'high' },
  });
  break;
}
```

## Android FCM handler me ye case add karo

`ReelFCMNotificationHandler.java` me `handle()` method ke switch/if block me:

```java
case "multi_duet_invite": {
    String fromName   = data.get("from_name");
    String fromPhoto  = data.get("from_photo");
    String sessionId  = data.get("session_id");
    String reelThumb  = data.get("reel_thumb");
    String reelId     = data.get("reel_id");

    // Avatar download (executor)
    Bitmap avatar = null;
    try { avatar = Glide.with(ctx).asBitmap().load(fromPhoto).submit().get(); } catch (Exception ignored) {}

    String title = fromName + " ne tumhe Multi-Duet me invite kiya! 🎬";
    String body  = "Tap karke join karo";

    // Deep-link intent → MultiDuetActivity ya DuetInviteAcceptActivity
    Intent intent = new Intent(ctx, com.callx.app.social.MultiDuetActivity.class);
    intent.putExtra("multi_duet_session_id", sessionId);
    intent.putExtra("multi_duet_reel_id",    reelId);
    intent.putExtra("is_invited",            true);
    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

    PendingIntent pi = PendingIntent.getActivity(ctx, sessionId.hashCode(),
        intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

    NotificationCompat.Builder nb = new NotificationCompat.Builder(ctx, CHANNEL_SOCIAL)
        .setSmallIcon(R.drawable.ic_notif)
        .setContentTitle(title)
        .setContentText(body)
        .setAutoCancel(true)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setContentIntent(pi);

    if (avatar != null) nb.setLargeIcon(avatar);
    if (!reelThumb.isEmpty()) {
        try {
            Bitmap thumb = Glide.with(ctx).asBitmap().load(reelThumb).submit().get();
            nb.setStyle(new NotificationCompat.BigPictureStyle().bigPicture(thumb));
        } catch (Exception ignored) {}
    }

    NotificationManagerCompat.from(ctx).notify(("mdi_" + sessionId).hashCode(), nb.build());
    break;
}
```
