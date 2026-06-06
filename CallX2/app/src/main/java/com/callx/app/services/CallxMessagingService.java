package com.callx.app.services;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.Person;
import androidx.core.app.RemoteInput;
import androidx.core.graphics.drawable.IconCompat;
import com.callx.app.CallxApp;
import com.callx.app.R;
import com.callx.app.conversation.ChatActivity;
import com.callx.app.incoming.IncomingCallActivity;
import com.callx.app.incoming.IncomingGroupCallActivity;
import com.callx.app.activities.MainActivity;
import com.callx.app.activities.SpecialRequestPopupActivity;
import com.callx.app.cache.CacheManager;
import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.MessageEntity;
import com.callx.app.utils.Constants;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.Query;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Executors;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.telephony.TelephonyManager;
import com.callx.app.notifications.ReelFCMNotificationHandler;
import com.callx.app.group.GroupChatActivity;
import com.callx.app.services.GroupCallRingService;
import com.callx.app.services.IncomingRingService;
import com.callx.app.services.NotificationActionReceiver;
import com.callx.app.viewer.StatusViewerActivity;
import com.callx.app.utils.StatusNotificationHelper;
public class CallxMessagingService extends FirebaseMessagingService {
    @Override public void onNewToken(String token) {
        if (FirebaseAuth.getInstance().getCurrentUser() == null) return;
        String uid = FirebaseAuth.getInstance().getCurrentUser().getUid();
        FirebaseUtils.getUserRef(uid).child("fcmToken").setValue(token);
    }
    @Override public void onMessageReceived(RemoteMessage msg) {
        Map<String, String> data = msg.getData();

        // ── Log every notification to SharedPrefs for NotificationCenterActivity ──
        logNotificationToPrefs(data);
        // ─────────────────────────────────────────────────────────────────────────

        // ── Reel notification system (v5) ──────────────────────────────
        // If payload contains reel_notif_type, delegate entirely to
        // ReelFCMNotificationHandler and return — no further processing needed.
        if (data.containsKey("reel_notif_type")) {
            com.callx.app.notifications.ReelNotificationChannelManager.ensureChannels(this);
            com.callx.app.notifications.ReelFCMNotificationHandler.handle(this, data);
            return;
        }
        // ───────────────────────────────────────────────────────────────

        // ── X notification system ───────────────────────────────────────────
        // Bilkul reel_notif_type jaisa pattern.
        // Payload me "x_notif_type" key detect ho to X handler ko delegate karo.
        // XFirebaseMessagingService ab deprecated hai — yahi single entry point hai.
        if (data.containsKey("x_notif_type")) {
            com.callx.app.notifications.XNotificationChannelManager.ensureChannels(this);
            com.callx.app.notifications.XFCMNotificationHandler.handle(this, data);
            return;
        }
        // ────────────────────────────────────────────────────────────────────

        // ── YouTube notification system ──────────────────────────────────────
        // Same pattern as X system — "yt_notif_type" key detect ho to delegate.
        // Background/killed state safe — YouTubeFCMNotificationHandler Executor use karta hai.
        if (data.containsKey("yt_notif_type")) {
            com.callx.app.notifications.YouTubeNotificationChannelManager.ensureChannels(this);
            com.callx.app.notifications.YouTubeFCMNotificationHandler.handle(this, data);
            return;
        }
        // ────────────────────────────────────────────────────────────────────

        String type = data.getOrDefault("type", "message");
        if ("call".equals(type) || "video_call".equals(type)) {
            showIncomingCall(data, "video_call".equals(type));
        } else if (Constants.GCALL_FCM_TYPE.equals(type)) {
            // Incoming group call — works even when app is killed
            showIncomingGroupCall(data);
        } else if ("group_call_missed".equals(type) || "missed_group_call".equals(type)) {
            // FIX-2: "missed_group_call" — PushNotify.notifyMissedGroupCall() se aata hai
            showMissedGroupCallNotification(data);
        } else if ("status_reply".equals(type)) {
            showStatusReply(data);
        } else if ("status_reaction".equals(type)) {
            handleStatusReaction(data);
        } else if ("contact_join".equals(type)) {
            handleContactJoin(data);
        } else if ("group_member_joined".equals(type)) {
            handleGroupMemberJoined(data);
        } else if ("call_missed".equals(type) || "missed_call".equals(type)) {
            // FIX-2: "missed_call" — PushNotify.notifyMissedCall() se aata hai
            // "call_missed"  — purana legacy type (backward compat)
            showMissedCallNotification(data);
        } else if ("group_message".equals(type)) {
            showGroupMessage(data);
        } else if ("status".equals(type)) {
            showStatus(data);
        } else if ("request".equals(type)) {
            // Request system hata diya gaya hai — kuch mat karo
        } else if ("permablock_notify".equals(type)) {
            // Sender ko receiver ne perma-block kar diya — return notification
            showPermaBlockReturnNotification(data);
        } else if ("special_request".equals(type)) {
            // Sender (jo perma-block ho chuka hai) ne special request bheji
            showSpecialRequestNotification(data);
        } else if ("unblock_notify".equals(type)) {
            // Blocker ne unblock kar diya — blocked user ko notify karo
            showUnblockNotification(data);
        } else {
            showMessage(data);
        }
    }
    private void showRequest(Map<String, String> data) {
        String fromUid  = data.getOrDefault("fromUid", "");
        String fromName = data.getOrDefault("fromName", "Friend");
        // App khuli ho ya killed ho — dono case me bottom popup activity launch karo
        Intent popup = new Intent(this, com.callx.app.activities.RequestPopupActivity.class);
        popup.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
            | Intent.FLAG_ACTIVITY_CLEAR_TOP
            | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        popup.putExtra("fromUid", fromUid);
        popup.putExtra("fromName", fromName);
        // Notification bhi dikhao taaki status bar me trace rahe
        PendingIntent pi = PendingIntent.getActivity(this, 0, popup,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(this,
                Constants.CHANNEL_REQUESTS)
            .setSmallIcon(R.drawable.ic_person_add)
            .setContentTitle(fromName)
            .setContentText("Aapko contact request bheji hai")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi);
        NotificationManager nm = (NotificationManager)
            getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(new Random().nextInt(99999), b.build());
        // Popup bhi turant launch karo
        try { startActivity(popup); } catch (Exception ignored) {}
    }
    // ════════════════════════════════════════════════════════════
      // showIncomingCall — Production-grade, background-killed safe
      //
      //  • Starts IncomingRingService (foreground, phoneCall type) so the
      //    phone rings even when app process is dead
      //  • CallStyle.forIncomingCall() on Android 12+ (API 31)
      //  • Falls back to PRIORITY_MAX + fullScreenIntent on API < 31
      //  • Decline / Accept action PendingIntents in notification body
      //  • Caller avatar downloaded async → set as largeIcon
      //  • Notification auto-cancels after CALL_TIMEOUT_MS (60 s)
      //  • callId read from dedicated "callId" field (falls back to "text")
      // ════════════════════════════════════════════════════════════
      private void showIncomingCall(final Map<String, String> data, final boolean isVideo) {
          final String callId    = data.containsKey("callId")
                                   ? data.get("callId")
                                   : data.getOrDefault("text", "");
          final String fromUid   = data.getOrDefault("fromUid", "");
          final String fromName  = data.getOrDefault("fromName", "Unknown");
          final String fromPhoto = data.getOrDefault("fromPhoto", "");
          final String fromThumb = data.getOrDefault("fromThumb", ""); // FIX-1: thumb for fast avatar

          // 1. Start IncomingRingService — rings even when app is killed
          Intent ringIntent = new Intent(this, IncomingRingService.class);
          ringIntent.putExtra(Constants.EXTRA_CALL_ID,        callId);
          ringIntent.putExtra(Constants.EXTRA_PARTNER_UID,    fromUid);
          ringIntent.putExtra(Constants.EXTRA_PARTNER_NAME,   fromName);
          ringIntent.putExtra(Constants.EXTRA_PARTNER_PHOTO,  fromPhoto); // FIX-1
          ringIntent.putExtra("partnerThumb",                  fromThumb); // FIX-1
          ringIntent.putExtra(Constants.EXTRA_IS_VIDEO,        isVideo);
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
              startForegroundService(ringIntent);
          } else {
              startService(ringIntent);
          }

          // 2. "Accept" full-screen intent → opens IncomingCallActivity
          Intent acceptIntent = new Intent(this, IncomingCallActivity.class);
          acceptIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
              | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
          acceptIntent.putExtra(Constants.EXTRA_CALL_ID,       callId);
          acceptIntent.putExtra(Constants.EXTRA_PARTNER_UID,   fromUid);
          acceptIntent.putExtra(Constants.EXTRA_PARTNER_NAME,  fromName);
          acceptIntent.putExtra(Constants.EXTRA_PARTNER_PHOTO, fromPhoto); // FIX-2
          acceptIntent.putExtra("partnerThumb",                  fromThumb); // FIX-2
          acceptIntent.putExtra(Constants.EXTRA_IS_VIDEO,      isVideo);
          PendingIntent acceptPi = PendingIntent.getActivity(this,
              Constants.CALL_RING_NOTIF_ID, acceptIntent,
              PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

          // 3. "Decline" broadcast → NotificationActionReceiver
          Intent declineIntent = new Intent(this, NotificationActionReceiver.class);
          declineIntent.setAction(Constants.ACTION_DECLINE_CALL);
          declineIntent.putExtra(Constants.EXTRA_CALL_ID,       callId);
          declineIntent.putExtra(Constants.EXTRA_PARTNER_UID,   fromUid);
          declineIntent.putExtra(Constants.EXTRA_PARTNER_NAME,  fromName);
          declineIntent.putExtra(Constants.EXTRA_PARTNER_PHOTO, fromPhoto); // FIX-2
          declineIntent.putExtra("partnerThumb",                  fromThumb); // FIX-2
          declineIntent.putExtra(Constants.EXTRA_IS_VIDEO,      isVideo);
          declineIntent.putExtra(Constants.EXTRA_NOTIF_ID,      Constants.CALL_RING_NOTIF_ID);
          PendingIntent declinePi = PendingIntent.getBroadcast(this,
              Constants.CALL_RING_NOTIF_ID + 1, declineIntent,
              PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

          // 4. Build notification async (avatar downloaded off-thread)
          bg.execute(() -> {
              int net = getNetworkLevel();
              Bitmap avatar = (net >= 2) ? circle(downloadBitmap(fromPhoto, 100, 100)) : null;

              NotificationCompat.Builder b = new NotificationCompat.Builder(
                      this, Constants.CHANNEL_CALLS_INCOMING)
                  .setSmallIcon(isVideo
                      ? android.R.drawable.ic_menu_camera
                      : android.R.drawable.ic_menu_call)
                  .setContentTitle(fromName)
                  .setContentText(isVideo ? "Incoming video call" : "Incoming voice call")
                  .setPriority(NotificationCompat.PRIORITY_MAX)
                  .setCategory(NotificationCompat.CATEGORY_CALL)
                  .setOngoing(true)
                  .setAutoCancel(false)
                  .setTimeoutAfter(Constants.CALL_TIMEOUT_MS)
                  .setFullScreenIntent(acceptPi, true)
                  .setContentIntent(acceptPi)
                  .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                  // Decline left, Accept right — matches WhatsApp/Telegram convention
                  .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                      "Decline", declinePi)
                  .addAction(android.R.drawable.ic_menu_call,
                      "Accept",  acceptPi);

              if (avatar != null) b.setLargeIcon(avatar);

              // Android 12+ (API 31): CallStyle → system-level call UI
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                  Person.Builder pb = new Person.Builder()
                      .setName(fromName)
                      .setKey(fromUid)
                      .setImportant(true);
                  if (avatar != null) pb.setIcon(IconCompat.createWithBitmap(avatar));
                  Person caller = pb.build();
                  b.setStyle(NotificationCompat.CallStyle
                      .forIncomingCall(caller, declinePi, acceptPi));
              }

              NotificationManager nm = (NotificationManager)
                  getSystemService(Context.NOTIFICATION_SERVICE);
              if (nm != null) nm.notify(Constants.CALL_RING_NOTIF_ID, b.build());
          });
      }
    // ── Group call: incoming (background/killed) ──────────────────────────
    private void showIncomingGroupCall(final Map<String, String> data) {
        final String callId     = data.getOrDefault(Constants.GCALL_FCM_CALL_ID,    "");
        final String groupId    = data.getOrDefault(Constants.GCALL_FCM_GROUP_ID,   "");
        final String groupName  = data.getOrDefault(Constants.GCALL_FCM_GROUP_NAME, "Group");
        final String groupIcon  = data.getOrDefault(Constants.GCALL_FCM_GROUP_ICON, "");
        final String callerUid  = data.getOrDefault(Constants.GCALL_FCM_CALLER_UID,   "");
        final String callerName = data.getOrDefault(Constants.GCALL_FCM_CALLER_NAME,  "Someone");
        // FIX-4: pass caller photo so group call UI shows avatar
        final String callerPhoto = data.getOrDefault(Constants.GCALL_FCM_CALLER_PHOTO, "");
        final boolean isVideo   = "true".equalsIgnoreCase(
            data.getOrDefault(Constants.GCALL_FCM_IS_VIDEO, "false"));

        // Do not ring if self-sent (caller got their own FCM)
        String myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;
        if (callerUid.equals(myUid)) return;

        // Check if call is still active before ringing
        if (callId.isEmpty()) return;

        com.google.firebase.database.FirebaseDatabase.getInstance().getReference("groupCalls")
            .child(callId).child("status")
            .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                @Override public void onDataChange(com.google.firebase.database.DataSnapshot s) {
                    String status = s.getValue(String.class);
                    if ("ended".equals(status)) return; // Too late

                    // Start GroupCallRingService — it will show full-screen notification
                    Intent ringIntent = new Intent(
                        CallxMessagingService.this, GroupCallRingService.class);
                    ringIntent.putExtra(Constants.EXTRA_CALL_ID,             callId);
                    ringIntent.putExtra(Constants.EXTRA_GROUP_ID,            groupId);
                    ringIntent.putExtra(Constants.EXTRA_GROUP_NAME,          groupName);
                    ringIntent.putExtra(Constants.EXTRA_GROUP_ICON,          groupIcon);
                    ringIntent.putExtra(Constants.EXTRA_GROUP_CALLER_UID,    callerUid);
                    ringIntent.putExtra(Constants.EXTRA_GROUP_CALLER_NAME,   callerName);
                    ringIntent.putExtra(Constants.EXTRA_GROUP_CALLER_PHOTO,  callerPhoto); // FIX-4
                    ringIntent.putExtra(Constants.EXTRA_IS_VIDEO,            isVideo);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(ringIntent);
                    } else {
                        startService(ringIntent);
                    }
                }
                @Override public void onCancelled(com.google.firebase.database.DatabaseError e) {}
            });
    }

    // ── Group call: missed notification ────────────────────────────────────
    
      // ─── Missed 1:1 Call notification (with Call Back button) ────────────────
      private void showMissedCallNotification(java.util.Map<String, String> data) {
          // FIX-2: Support both old field names (callerUid/callerName) and new ones (fromUid/fromName)
          String _callerUid   = safeGet(data, "callerUid");
          if (_callerUid == null || _callerUid.isEmpty()) _callerUid = safeGet(data, "fromUid");
          String _callerName  = safeGet(data, "callerName");
          if (_callerName == null || _callerName.isEmpty()) _callerName = safeGet(data, "fromName");
          String _callerPhoto = safeGet(data, "callerPhoto");
          if (_callerPhoto == null || _callerPhoto.isEmpty()) _callerPhoto = safeGet(data, "fromPhoto");
          // FIX-3: read isVideo from missed call payload so Call Back uses correct media type
          final boolean missedIsVideo = "true".equalsIgnoreCase(safeGet(data, "isVideo"));
          if (_callerName == null) return;

          // Lambda ke liye final copies — reassigned vars lambda me use nahi ho sakti
          final String callerUid   = _callerUid   != null ? _callerUid   : "";
          final String callerName  = _callerName;
          final String callerPhoto = _callerPhoto  != null ? _callerPhoto : "";

          // Check quiet hours
          com.callx.app.utils.QuietHoursManager qhm =
              new com.callx.app.utils.QuietHoursManager(getApplicationContext());
          if (qhm.shouldSuppress("call")) return;

          int notifId = ("missed_" + callerUid).hashCode() & 0x7FFFFFFF;

          // Open ChatActivity on tap
          android.content.Intent openIntent = new android.content.Intent();
          openIntent.setClassName(this, "com.callx.app.conversation.ChatActivity");
          openIntent.putExtra(com.callx.app.utils.Constants.EXTRA_PARTNER_UID,   callerUid);
          openIntent.putExtra(com.callx.app.utils.Constants.EXTRA_PARTNER_NAME,  callerName);
          openIntent.putExtra("partnerPhoto", callerPhoto);
          openIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK |
              android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);
          android.app.PendingIntent openPi = android.app.PendingIntent.getActivity(
              this, notifId, openIntent,
              android.app.PendingIntent.FLAG_UPDATE_CURRENT |
              android.app.PendingIntent.FLAG_IMMUTABLE);

          // CALL BACK action
          android.content.Intent callBackIntent = new android.content.Intent(this,
              com.callx.app.services.NotificationActionReceiver.class)
              .setAction(com.callx.app.utils.Constants.ACTION_CALL_BACK)
              .putExtra(com.callx.app.utils.Constants.EXTRA_PARTNER_UID,   callerUid)
              .putExtra(com.callx.app.utils.Constants.EXTRA_PARTNER_NAME,  callerName)
              .putExtra(com.callx.app.utils.Constants.EXTRA_PARTNER_PHOTO, callerPhoto) // FIX-3
              .putExtra(com.callx.app.utils.Constants.EXTRA_IS_VIDEO,      missedIsVideo) // FIX-3
              .putExtra(com.callx.app.utils.Constants.EXTRA_NOTIF_ID, notifId);
          android.app.PendingIntent callBackPi = android.app.PendingIntent.getBroadcast(
              this, ("cb_" + callerUid).hashCode(), callBackIntent,
              android.app.PendingIntent.FLAG_UPDATE_CURRENT |
              android.app.PendingIntent.FLAG_IMMUTABLE);

          NotificationCompat.Builder b = new NotificationCompat.Builder(this,
                  com.callx.app.utils.Constants.CHANNEL_CALLS)
              .setSmallIcon(R.drawable.ic_call_notification)
              .setContentTitle("Missed call from " + callerName)
              .setContentText("Tap to call back")
              .setPriority(NotificationCompat.PRIORITY_HIGH)
              .setAutoCancel(true)
              .setContentIntent(openPi)
              .addAction(R.drawable.ic_call_answer, "📞 Call Back", callBackPi)
              .setCategory(NotificationCompat.CATEGORY_MISSED_CALL);

          // Download avatar async — final vars lambda me safely use ho sakti hain
          new Thread(() -> {
              try {
                  if (!callerPhoto.isEmpty()) {
                      java.net.HttpURLConnection c =
                          (java.net.HttpURLConnection) new java.net.URL(callerPhoto).openConnection();
                      c.setDoInput(true); c.connect();
                      Bitmap bm = BitmapFactory.decodeStream(c.getInputStream());
                      if (bm != null) b.setLargeIcon(bm);
                  }
              } catch (Exception ignored) {}
              NotificationManager nm = (NotificationManager)
                  getSystemService(Context.NOTIFICATION_SERVICE);
              if (nm != null) nm.notify(notifId, b.build());
          }).start();

          // Save to NotificationFirebaseStore
          com.callx.app.utils.NotificationFirebaseStore.save(
              com.callx.app.utils.NotificationFirebaseStore.TYPE_CALL,
              "Missed call from " + callerName, "Tap to call back",
              callerUid, callerName, callerPhoto, null, null, null,
              com.callx.app.utils.NotificationFirebaseStore.DELIVERY_BACKGROUND);
      }
  
    private void showMissedGroupCallNotification(final Map<String, String> data) {
        final String groupName  = data.getOrDefault(Constants.GCALL_FCM_GROUP_NAME, "Group");
        final String callerName = data.getOrDefault(Constants.GCALL_FCM_CALLER_NAME, "Someone");
        final boolean isVideo   = "true".equalsIgnoreCase(
            data.getOrDefault(Constants.GCALL_FCM_IS_VIDEO, "false"));
        final String callId     = data.getOrDefault(Constants.GCALL_FCM_CALL_ID, "");
        final String groupId    = data.getOrDefault(Constants.GCALL_FCM_GROUP_ID, "");

        Intent tapIntent = new Intent(this, MainActivity.class);
        tapIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent tapPi = PendingIntent.getActivity(this,
            Constants.GROUP_CALL_MISSED_NOTIF_ID, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String title = (groupName != null && !groupName.isEmpty()) ? groupName : "Group Call";
        String body  = "Missed group " + (isVideo ? "video" : "voice")
            + " call from " + callerName;

        android.app.Notification notif = new NotificationCompat.Builder(
                this, Constants.CHANNEL_GROUP_CALLS_MISSED)
            .setSmallIcon(isVideo ? R.drawable.ic_video_call : R.drawable.ic_phone)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setAutoCancel(true)
            .setContentIntent(tapPi)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build();

        NotificationManager nm =
            (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(Constants.GROUP_CALL_MISSED_NOTIF_ID, notif);

        // Save to Firebase calls/{myUid} so AllNotifications Calls tab shows group call misses
        String myUidGc = com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser() != null
            ? com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser().getUid() : null;
        if (myUidGc != null && !myUidGc.isEmpty()) {
            final String myUidFinal = myUidGc;
            bg.execute(() -> {
                try {
                    java.util.Map<String, Object> entry = new java.util.HashMap<>();
                    entry.put("status",      "missed");
                    entry.put("type",        "group_call_missed");
                    entry.put("callType",    isVideo ? "video" : "audio");
                    entry.put("callerName",  callerName);
                    entry.put("callerUid",   data.getOrDefault(Constants.GCALL_FCM_CALLER_UID, ""));
                    entry.put("callerPhoto", data.getOrDefault(Constants.GCALL_FCM_CALLER_PHOTO, ""));
                    entry.put("groupId",     groupId);
                    entry.put("groupName",   groupName);
                    entry.put("callId",      callId);
                    entry.put("timestamp",   System.currentTimeMillis());
                    com.google.firebase.database.FirebaseDatabase.getInstance()
                        .getReference("calls")
                        .child(myUidFinal)
                        .child(callId.isEmpty() ? "gc_" + System.currentTimeMillis() : callId)
                        .setValue(entry);
                } catch (Exception ex) {
                    android.util.Log.w("GRP_CALL_NOTIF", "Save failed: " + ex.getMessage());
                }
            });
        }
    }

    // ----- Background-killed WhatsApp-style message notification -----
    private final ExecutorService bg = Executors.newCachedThreadPool();
    // ══════════════════════════════════════════════════════════════════════
    //  showMessage — v18 ZERO-FIREBASE (10ms notification)
    //
    //  ALL 3 Firebase calls removed — server now sends flags in FCM payload:
    //    "permaBlocked" : "1" / "0"
    //    "blocked"      : "1" / "0"
    //    "muted"        : "1" / "0"   (was already present)
    //    "lastMsg"      : "<last message text>"
    //
    //  App side: zero network calls — instant notification build.
    //  Fallback: if server does NOT send new flags (old server), gracefully
    //            falls back to old Firebase behavior so nothing breaks.
    // ══════════════════════════════════════════════════════════════════════
    private void showMessage(final Map<String, String> data) {
        final String fromUid    = data.getOrDefault("fromUid", "");
        final String fromName   = data.getOrDefault("fromName", "CallX");
        final String fromMobile = data.getOrDefault("fromMobile", "");
        final String fromPhoto  = data.getOrDefault("fromPhoto", "");
        final String fromThumb  = data.getOrDefault("fromThumb", "");
        final String avatarUrl  = (!fromThumb.isEmpty()) ? fromThumb : fromPhoto;
        final String chatId     = data.getOrDefault("chatId", "");
        final String mediaUrl   = data.getOrDefault("mediaUrl", "");
        final String rawText    = data.getOrDefault("text", "Naya message");
        final String type       = data.getOrDefault("type", "message");
        final String text       = previewTextFor(type, rawText);
        long ls = 0L;
        try { ls = Long.parseLong(data.getOrDefault("fromLastSeen", "0")); }
        catch (Exception ignored) {}
        final long lastSeen = ls;
        final boolean online = (System.currentTimeMillis() - lastSeen)
                                < Constants.ONLINE_WINDOW_MS && lastSeen > 0;
        final String status  = online ? "Online" : "Offline";
        final String subText = (fromMobile.isEmpty() ? "" : ("+" + fromMobile + " • ")) + status;
        final int notifId = ("chat_" + (fromUid == null ? "" : fromUid)).hashCode();
        if ("typing".equals(type)) {
            showTypingNotification(fromUid, fromName, chatId, notifId, subText);
            return;
        }
        final String msgId = data.getOrDefault("msgId", fromUid + "_" + System.currentTimeMillis());
        saveMessageToDb(msgId, chatId, fromUid, fromName, rawText, type, mediaUrl,
            data.getOrDefault("fileName", null), false);

        // FIX [P2-3]: "Delivered" status — FCM notification hamare device pe pahuncha matlab
        // message deliver ho gaya. Sender ko ✓✓ (gray) dikhao, chahe chat khuli ho ya na ho.
        // Ye Firebase ChildEventListener se back-propagate hoga → sender ki Room DB update → UI refresh.
        if (msgId != null && !msgId.isEmpty() && chatId != null && !chatId.isEmpty()) {
            final String currentUid = FirebaseAuth.getInstance().getCurrentUser() != null
                    ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";
            if (!currentUid.isEmpty() && !currentUid.equals(fromUid)) {
                Executors.newSingleThreadExecutor().execute(() -> {
                    try {
                        // Sirf "sent" → "delivered" upgrade karo; "read" downgrade nahi hona chahiye
                        com.google.firebase.database.DatabaseReference msgRef =
                                FirebaseUtils.db().getReference("chats")
                                        .child(chatId).child("messages").child(msgId);
                        msgRef.child("status").addListenerForSingleValueEvent(new ValueEventListener() {
                            @Override public void onDataChange(DataSnapshot s) {
                                String cur = s.getValue(String.class);
                                if ("read".equals(cur)) return; // already better, skip
                                msgRef.child("status").setValue("delivered");
                            }
                            @Override public void onCancelled(DatabaseError e) {}
                        });
                    } catch (Exception ignored) {}
                });
            }
        }

        // ── v18: Read all flags from FCM payload (server sends these now) ──
        final boolean serverHasBlockFlags = data.containsKey("permaBlocked") || data.containsKey("blocked");
        final boolean serverHasLastMsg    = data.containsKey("history") || data.containsKey("lastMsg"); // history = new, lastMsg = legacy
        final boolean serverMuted         = "1".equals(data.getOrDefault("muted", "0"));

        // ── FAST PATH: server sent all flags — zero Firebase calls (~10ms) ──
        if (serverHasBlockFlags) {
            final boolean isPermaBlocked = "1".equals(data.getOrDefault("permaBlocked", "0"));
            final boolean isBlocked      = "1".equals(data.getOrDefault("blocked", "0"));

            if (isPermaBlocked) return; // silently drop

            if (isBlocked) {
                showBlockedSenderNotification(fromUid, fromName, fromMobile, avatarUrl, chatId);
                return;
            }

            // Build history from FCM "history" JSON field
            // Format: [{"t":"text","ts":1234567890,"me":false}, ...]
            // "me":true = message sent by the notification receiver
            List<HistoryItem> hist = null;
            final String histJson = data.getOrDefault("history", "");
            if (!histJson.isEmpty()) {
                try {
                    org.json.JSONArray arr = new org.json.JSONArray(histJson);
                    hist = new ArrayList<>();
                    for (int i = 0; i < arr.length(); i++) {
                        org.json.JSONObject o = arr.getJSONObject(i);
                        String t    = o.optString("t", "");
                        long   ts   = o.optLong("ts", System.currentTimeMillis() - (arr.length() - i) * 1000L);
                        boolean me  = o.optBoolean("me", false);
                        if (!t.isEmpty()) hist.add(new HistoryItem(t, ts, me));
                    }
                    if (hist.isEmpty()) hist = null;
                } catch (Exception ignored) {
                    hist = null; // malformed JSON — skip history gracefully
                }
            }

            // Direct build — no Firebase, no waiting
            final String myThumbUrl = data.getOrDefault("myThumb", "");
            buildAndShow(fromUid, fromName, fromMobile, avatarUrl,
                chatId, mediaUrl, text, type, subText, notifId, hist, serverMuted, myThumbUrl);
            return;
        }

        // ── FALLBACK PATH: old server without new flags — original Firebase behavior ──
        if (FirebaseAuth.getInstance().getCurrentUser() == null
                || fromUid == null || fromUid.isEmpty()) {
            buildAndShow(fromUid, fromName, fromMobile, avatarUrl,
                chatId, mediaUrl, text, type, subText, notifId, null, false);
            return;
        }
        final String myUid = FirebaseAuth.getInstance().getCurrentUser().getUid();

        final AtomicBoolean isPermaBlocked = new AtomicBoolean(false);
        final AtomicBoolean isBlocked      = new AtomicBoolean(false);
        final CountDownLatch latch          = new CountDownLatch(2);

        FirebaseUtils.db().getReference("permaBlocked").child(myUid).child(fromUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot s) {
                    if (Boolean.TRUE.equals(s.getValue(Boolean.class))) isPermaBlocked.set(true);
                    latch.countDown();
                }
                @Override public void onCancelled(DatabaseError e) { latch.countDown(); }
            });

        FirebaseUtils.db().getReference("blocked").child(myUid).child(fromUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot s) {
                    if (Boolean.TRUE.equals(s.getValue(Boolean.class))) isBlocked.set(true);
                    latch.countDown();
                }
                @Override public void onCancelled(DatabaseError e) { latch.countDown(); }
            });

        bg.execute(() -> {
            try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            if (isPermaBlocked.get()) return;
            if (isBlocked.get()) {
                showBlockedSenderNotification(fromUid, fromName, fromMobile, avatarUrl, chatId);
                return;
            }
            final boolean serverSentMuteFlag = data.containsKey("muted");
            if (serverSentMuteFlag) {
                loadLast3AndBuild(myUid, fromUid, fromName, fromMobile, avatarUrl,
                    chatId, mediaUrl, text, type, subText, notifId, serverMuted);
            } else {
                FirebaseUtils.db().getReference("muted").child(myUid).child(fromUid)
                    .addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override public void onDataChange(DataSnapshot s2) {
                            boolean muted = Boolean.TRUE.equals(s2.getValue(Boolean.class));
                            loadLast3AndBuild(myUid, fromUid, fromName, fromMobile,
                                avatarUrl, chatId, mediaUrl, text, type, subText, notifId, muted);
                        }
                        @Override public void onCancelled(DatabaseError e) {
                            loadLast3AndBuild(myUid, fromUid, fromName, fromMobile,
                                avatarUrl, chatId, mediaUrl, text, type, subText, notifId, false);
                        }
                    });
            }
        });
    }
    private static String previewTextFor(String type, String raw) {
        if (raw != null && !raw.isEmpty()) return raw;
        if (type == null) return "Naya message";
        switch (type) {
            case "image": return "📷 Photo";
            case "video": return "🎬 Video";
            case "audio": return "🎤 Voice message";
            case "file":    return "📎 File";
            case "pdf":     return "📄 PDF document";
            case "gif":     return "🎞️ GIF";
            case "sticker": return "🎭 Sticker";
            default:        return "Naya message";
        }
    }
    private static int smallIconFor(String type) {
        if (type == null) return R.drawable.ic_message_notification;
        switch (type) {
            case "image": return R.drawable.ic_gallery;
            case "video": return R.drawable.ic_video;
            case "audio": return R.drawable.ic_audio;
            case "file":  return R.drawable.ic_file;
            case "pdf":   return R.drawable.ic_pdf;
            default:      return R.drawable.ic_message_notification;
        }
    }
    // OPTION 1: Renamed loadLast1AndBuild — fetches only 1 history message (was 3)
    private void loadLast3AndBuild(final String myUid, final String fromUid,
            final String fromName, final String fromMobile, final String fromPhoto,
            final String chatId, final String mediaUrl, final String text,
            final String type, final String subText, final int notifId,
            final boolean muted) {
        if (chatId == null || chatId.isEmpty()) {
            buildAndShow(fromUid, fromName, fromMobile, fromPhoto,
                chatId, mediaUrl, text, type, subText, notifId, null, muted);
            return;
        }
        Query q = FirebaseUtils.getMessagesRef(chatId)
            .orderByChild("timestamp").limitToLast(5); // Fix 5: was limitToLast(1), now 5 for proper grouping
        q.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                List<HistoryItem> hist = new ArrayList<>();
                for (DataSnapshot c : snap.getChildren()) {
                    String s   = String.valueOf(c.child("senderId").getValue());
                    String t   = c.child("text").getValue() != null
                                ? String.valueOf(c.child("text").getValue()) : "";
                    String tp  = c.child("type").getValue() != null
                                ? String.valueOf(c.child("type").getValue()) : "text";
                    Long   ts  = c.child("timestamp").getValue() != null
                                ? c.child("timestamp").getValue(Long.class)
                                : System.currentTimeMillis();
                    if (t.isEmpty()) {
                        t = previewTextFor(tp, "");
                    }
                    boolean fromMe = s != null && s.equals(myUid);
                    hist.add(new HistoryItem(t, ts, fromMe));
                }
                Collections.sort(hist, (a, b) -> Long.compare(a.ts, b.ts));
                buildAndShow(fromUid, fromName, fromMobile, fromPhoto,
                    chatId, mediaUrl, text, type, subText, notifId, hist, muted);
            }
            @Override public void onCancelled(DatabaseError e) {
                buildAndShow(fromUid, fromName, fromMobile, fromPhoto,
                    chatId, mediaUrl, text, type, subText, notifId, null, muted);
            }
        });
    }
    private void buildAndShow(final String fromUid, final String fromName,
            final String fromMobile, final String fromPhoto, final String chatId,
            final String mediaUrl, final String text, final String type,
            final String subText, final int notifId,
            @Nullable final List<HistoryItem> hist, final boolean muted) {
        buildAndShow(fromUid, fromName, fromMobile, fromPhoto, chatId,
            mediaUrl, text, type, subText, notifId, hist, muted, "");
    }

    private void buildAndShow(final String fromUid, final String fromName,
            final String fromMobile, final String fromPhoto, final String chatId,
            final String mediaUrl, final String text, final String type,
            final String subText, final int notifId,
            @Nullable final List<HistoryItem> hist, final boolean muted,
            final String myThumbUrl) {
        // Avatar + (optional) attached image downloaded off-thread.
        // Network-aware: 2G/no-network → skip avatar (instant notification).
        bg.execute(() -> {
            int net = getNetworkLevel();
            Bitmap avatar   = null;
            Bitmap myAvatar = null;
            if (net >= 2) { // 3G, 4G, 5G, WiFi
                avatar   = circle(downloadBitmap(fromPhoto, 100, 100));  // sender
                myAvatar = loadMyAvatar(myThumbUrl);                      // receiver (me)
            }
            boolean isImage = "image".equals(type)
                && mediaUrl != null && !mediaUrl.isEmpty();
            // Image preview: only on WiFi/4G/5G (net==3), skip on 3G and below
            Bitmap picture = (isImage && net == 3)
                ? downloadBitmap(mediaUrl, 400, 300) : null;
            postRichNotification(fromUid, fromName, fromMobile, fromPhoto,
                chatId, mediaUrl, text, type, subText, notifId, hist,
                avatar, myAvatar, picture, muted);
        });
    }
    private void postRichNotification(String fromUid, String fromName, String fromMobile,
            String fromPhoto, String chatId, String mediaUrl, String text,
            String type, String subText, int notifId,
            @Nullable List<HistoryItem> hist,
            @Nullable Bitmap avatar, @Nullable Bitmap myAvatar,
            @Nullable Bitmap picture, boolean muted) {
        // Sender Person (with circular avatar — Feature 5)
        Person.Builder pb = new Person.Builder().setName(fromName).setKey(fromUid);
        if (avatar != null) pb.setIcon(IconCompat.createWithBitmap(avatar));
        Person sender = pb.build();
        // (Feature 10/11) Me Person — apna avatar set karo so reply right side
        // me apne profile image ke saath dikhega.
        Person.Builder meB = new Person.Builder().setName("You").setKey("me");
        if (myAvatar != null) meB.setIcon(IconCompat.createWithBitmap(myAvatar));
        Person me = meB.build();
        // (Feature 9) Open chat directly on tap
        Intent open = new Intent(this, ChatActivity.class);
        open.putExtra("partnerUid",   fromUid);
        open.putExtra("partnerName",  fromName);
        open.putExtra("partnerPhoto", fromPhoto != null ? fromPhoto : "");
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, notifId, open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        // Action: Reply (inline RemoteInput)
        RemoteInput remoteInput = new RemoteInput.Builder(Constants.KEY_TEXT_REPLY)
            .setLabel("Reply…").build();
        PendingIntent replyPi = PendingIntent.getBroadcast(this, notifId * 10 + 2,
            buildActionIntent(Constants.ACTION_REPLY, fromUid, fromName, fromPhoto,
                chatId, notifId),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        NotificationCompat.Action replyAction =
            new NotificationCompat.Action.Builder(
                    R.drawable.ic_message_notification, "Reply", replyPi)
                .addRemoteInput(remoteInput)
                .setAllowGeneratedReplies(true)
                .setSemanticAction(
                    NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                .build();
        // Action: Mark as read
        PendingIntent markReadPi = PendingIntent.getBroadcast(this, notifId * 10 + 1,
            buildActionIntent(Constants.ACTION_MARK_READ, fromUid, fromName,
                fromPhoto, chatId, notifId),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Action markReadAction =
            new NotificationCompat.Action.Builder(
                    R.drawable.ic_message_notification, "Mark as read", markReadPi)
                .setSemanticAction(
                    NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
                .setShowsUserInterface(false)
                .build();
        // Action: Mute
        PendingIntent mutePi = PendingIntent.getBroadcast(this, notifId * 10 + 3,
            buildActionIntent(Constants.ACTION_MUTE, fromUid, fromName,
                fromPhoto, chatId, notifId),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Action muteAction =
            new NotificationCompat.Action.Builder(
                    R.drawable.ic_message_notification, "Mute", mutePi)
                .setSemanticAction(
                    NotificationCompat.Action.SEMANTIC_ACTION_MUTE)
                .build();
        // Action: Block
        PendingIntent blockPi = PendingIntent.getBroadcast(this, notifId * 10 + 4,
            buildActionIntent(Constants.ACTION_BLOCK, fromUid, fromName,
                fromPhoto, chatId, notifId),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Action blockAction =
            new NotificationCompat.Action.Builder(
                    R.drawable.ic_message_notification, "Block", blockPi)
                .build();
        // MessagingStyle — expands to last 3 messages + current message (Feature 7)
        // NOTE: FCM can arrive before Firebase DB write propagates, so the
        // current `text` from the payload is always appended last to guarantee
        // the latest message is what the user sees in the collapsed notification.
        NotificationCompat.MessagingStyle style =
            new NotificationCompat.MessagingStyle(me)
                .setConversationTitle(fromName);
        if (hist != null && !hist.isEmpty()) {
            // Fix 3: Dedup — server limitToLast(5) history mein current message bhi hota hai.
            // Text match weak hai (same text 2 msgs ho sakti hai). Better: last item skip karo
            // agar uska timestamp current msg ke closest hai (within 3s window) aur text same ho.
            int skipIdx = -1;
            for (int i = hist.size() - 1; i >= 0; i--) {
                HistoryItem h = hist.get(i);
                if (h.text.equals(text) && Math.abs(System.currentTimeMillis() - h.ts) < 3000L) {
                    skipIdx = i;
                    break;
                }
            }
            for (int i = 0; i < hist.size(); i++) {
                if (i == skipIdx) continue;
                HistoryItem h = hist.get(i);
                style.addMessage(h.text, h.ts, h.fromMe ? me : sender);
            }
        }
        // Always add the just-arrived message at the end (latest = most visible)
        style.addMessage(text, System.currentTimeMillis(), sender);
        // Channel — muted to silent channel, baki messages channel
        String channel = muted ? Constants.CHANNEL_MUTED : Constants.CHANNEL_MESSAGES;
        // Lock-screen-safe public version (no preview / no image)
        NotificationCompat.Builder publicB = new NotificationCompat.Builder(this,
                channel)
            .setSmallIcon(smallIconFor(type))
            .setContentTitle("CallX")
            .setContentText("New message")
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
        // Main builder (Feature 6 — group key for same-user grouping)
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, channel)
            .setSmallIcon(smallIconFor(type))
            .setContentTitle(fromName)
            .setContentText(text)
            .setSubText(subText)
            .setShortcutId("chat_" + (chatId == null ? "" : chatId))
            .setStyle(style)
            .setPriority(muted ? NotificationCompat.PRIORITY_LOW
                               : NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(openPi)
            .addAction(replyAction)
            .addAction(markReadAction)
            .addAction(muteAction)
            .addAction(blockAction)
            .setGroup(Constants.GROUP_KEY_MESSAGES)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicB.build());
        if (muted) {
            b.setSilent(true);
            b.setOnlyAlertOnce(true);
        } else {
            // Fix 2: Same sender ke multiple messages pe sirf pehli baar alert karo
            b.setOnlyAlertOnce(true);
        }
        if (avatar != null) b.setLargeIcon(avatar);
        // Image message → BigPictureStyle (only when unlocked)
        if (picture != null) {
            NotificationCompat.BigPictureStyle bp =
                new NotificationCompat.BigPictureStyle()
                    .bigPicture(picture)
                    .setBigContentTitle(fromName)
                    .setSummaryText(subText);
            if (avatar != null) bp.bigLargeIcon((Bitmap) null);
            b.setStyle(bp);
            b.setContentText(text);
        }
        NotificationManager nm = (NotificationManager)
            getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(notifId, b.build());
        // (Feature 6) Group summary — multiple users hone par OS expand karega
        NotificationCompat.Builder summary = new NotificationCompat.Builder(this,
                channel)
            .setSmallIcon(R.drawable.ic_message_notification)
            .setContentTitle("CallX")
            .setContentText("New messages")
            .setStyle(new NotificationCompat.InboxStyle()
                .setSummaryText("CallX"))
            .setGroup(Constants.GROUP_KEY_MESSAGES)
            .setGroupSummary(true)
            .setAutoCancel(true);
        if (muted) summary.setSilent(true);
        nm.notify(Constants.GROUP_KEY_MESSAGES.hashCode(), summary.build());
    }
    private Intent buildActionIntent(String action, String fromUid, String fromName,
                                     String fromPhoto, String chatId, int notifId) {
        return new Intent(this, NotificationActionReceiver.class)
            .setAction(action)
            .putExtra(Constants.EXTRA_CHAT_ID,       chatId   == null ? "" : chatId)
            .putExtra(Constants.EXTRA_PARTNER_UID,   fromUid)
            .putExtra(Constants.EXTRA_PARTNER_NAME,  fromName)
            .putExtra(Constants.EXTRA_PARTNER_PHOTO, fromPhoto == null ? "" : fromPhoto)
            .putExtra(Constants.EXTRA_NOTIF_ID,      notifId);
    }
    // ----- Feature 2/3: "Unblock {sender}" prompt notification -----
    private void showBlockedSenderNotification(final String fromUid,
            final String fromName, final String fromMobile,
            final String fromPhoto, final String chatId) {
        bg.execute(() -> {
            int net = getNetworkLevel();
            Bitmap avatar = (net >= 2) ? circle(downloadBitmap(fromPhoto, 100, 100)) : null;
            int blockNotifId = ("block_" + fromUid).hashCode();
            // Tap = unblock and reveal real notification next time
            PendingIntent unblockPi = PendingIntent.getBroadcast(this,
                blockNotifId * 10 + 5,
                buildActionIntent(Constants.ACTION_UNBLOCK, fromUid, fromName,
                    fromPhoto, chatId, blockNotifId),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            // Long-press surfaces a visible "Permanently block" action
            PendingIntent permaPi = PendingIntent.getBroadcast(this,
                blockNotifId * 10 + 6,
                buildActionIntent(Constants.ACTION_PERMA_BLOCK, fromUid, fromName,
                    fromPhoto, chatId, blockNotifId),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            NotificationCompat.Builder b = new NotificationCompat.Builder(this,
                    Constants.CHANNEL_BLOCK)
                .setSmallIcon(R.drawable.ic_phone_off)
                .setContentTitle("Unblock to " + fromName)
                .setContentText("Tap to unblock and see their messages")
                .setStyle(new NotificationCompat.BigTextStyle()
                    .bigText(fromName + " ne aapko message bheja hai. " +
                             "Aapne is sender ko block kiya hua hai. " +
                             "Tap on 'Unblock' to see their notifications again, " +
                             "or 'Block forever' to permanently block."))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setContentIntent(unblockPi)
                .addAction(R.drawable.ic_message_notification,
                    "Unblock " + fromName, unblockPi)
                .addAction(R.drawable.ic_phone_off,
                    "Block forever", permaPi);
            if (avatar != null) b.setLargeIcon(avatar);
            NotificationManager nm = (NotificationManager)
                getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(blockNotifId, b.build());
        });
    }
    // ----- Feature 12: receiver ne perma-block kiya — sender ko ek baar
    //                   return notification chala — receiver details ke saath -----
    private void showPermaBlockReturnNotification(Map<String, String> data) {
        final String fromUid   = data.getOrDefault("fromUid", "");
        final String fromName  = data.getOrDefault("fromName", "User");
        final String fromPhoto = data.getOrDefault("fromPhoto", "");
        final int notifId      = ("perma_in_" + fromUid).hashCode();
        bg.execute(() -> {
            int net = getNetworkLevel();
            Bitmap avatar = (net >= 2) ? circle(downloadBitmap(fromPhoto, 100, 100)) : null;
            // Tap → open chat — banner already wahan dikhega
            Intent open = new Intent(this, ChatActivity.class);
            open.putExtra("partnerUid", fromUid);
            open.putExtra("partnerName", fromName);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(this, notifId, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            NotificationCompat.Builder b = new NotificationCompat.Builder(this,
                    Constants.CHANNEL_BLOCK)
                .setSmallIcon(R.drawable.ic_phone_off)
                .setContentTitle(fromName + " ne aapko permanently block kiya")
                .setContentText("Tap karke special request bhejo")
                .setStyle(new NotificationCompat.BigTextStyle()
                    .bigText(fromName + " ne aapko permanently block kar diya hai. " +
                             "Aap unhe chat screen se ek special unblock request " +
                             "bhej sakte ho."))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi);
            if (avatar != null) b.setLargeIcon(avatar);
            NotificationManager nm = (NotificationManager)
                getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(notifId, b.build());
        });
    }
    // ----- Feature 14/15/16/17: Special request notification at receiver -----
    private void showSpecialRequestNotification(final Map<String, String> data) {
        final String fromUid   = data.getOrDefault("fromUid", "");
        final String fromName  = data.getOrDefault("fromName", "User");
        final String fromPhoto = data.getOrDefault("fromPhoto", "");
        final String reqText   = data.getOrDefault("text", "Please unblock me");
        // (Feature 17) — agar app foreground hai to in-app popup bhi launch karo
        if (CallxApp.isAppInForeground()) {
            Intent popup = new Intent(this, SpecialRequestPopupActivity.class);
            popup.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            popup.putExtra("fromUid", fromUid);
            popup.putExtra("fromName", fromName);
            popup.putExtra("fromPhoto", fromPhoto);
            popup.putExtra("text", reqText);
            try { startActivity(popup); } catch (Exception ignored) {}
        }
        bg.execute(() -> {
            int net = getNetworkLevel();
            Bitmap avatar = (net >= 2) ? circle(downloadBitmap(fromPhoto, 100, 100)) : null;
            final int notifId = ("spreq_" + fromUid).hashCode();
            // Unblock action button
            PendingIntent unblockPi = PendingIntent.getBroadcast(this,
                notifId * 10 + 7,
                new Intent(this, NotificationActionReceiver.class)
                    .setAction(Constants.ACTION_SPECIAL_UNBLOCK)
                    .putExtra(Constants.EXTRA_PARTNER_UID,   fromUid)
                    .putExtra(Constants.EXTRA_PARTNER_NAME,  fromName)
                    .putExtra(Constants.EXTRA_PARTNER_PHOTO, fromPhoto)
                    .putExtra(Constants.EXTRA_NOTIF_ID,      notifId),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            // Tap → open SpecialRequestPopupActivity
            Intent open = new Intent(this, SpecialRequestPopupActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            open.putExtra("fromUid", fromUid);
            open.putExtra("fromName", fromName);
            open.putExtra("fromPhoto", fromPhoto);
            open.putExtra("text", reqText);
            PendingIntent openPi = PendingIntent.getActivity(this, notifId, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            NotificationCompat.Builder b = new NotificationCompat.Builder(this,
                    Constants.CHANNEL_REQUESTS)
                .setSmallIcon(R.drawable.ic_person_add)
                .setContentTitle(fromName + " — Special request")
                .setContentText(reqText)
                .setStyle(new NotificationCompat.BigTextStyle()
                    .setBigContentTitle(fromName + " — Special request")
                    .bigText(reqText))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_SOCIAL)
                .setAutoCancel(true)
                .setContentIntent(openPi)
                .addAction(R.drawable.ic_person_add,
                    "Please unblock " + fromName, unblockPi);
            if (avatar != null) b.setLargeIcon(avatar);
            NotificationManager nm = (NotificationManager)
                getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(notifId, b.build());
        });
    }
    // ----- Helpers: load my own avatar URL -----
    // Uses FCM "myThumb" field — no Firebase call needed.
    @Nullable private Bitmap loadMyAvatar(String myThumbUrl) {
        if (myThumbUrl == null || myThumbUrl.isEmpty()) return null;
        return circle(downloadBitmap(myThumbUrl, 100, 100));
    }
    // ----- Feature 5/10: bitmap → circular crop (WhatsApp style) -----
    @Nullable private static Bitmap circle(@Nullable Bitmap src) {
        if (src == null) return null;
        int size = Math.min(src.getWidth(), src.getHeight());
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        RectF r = new RectF(0, 0, size, size);
        canvas.drawOval(r, paint);
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        int x = (src.getWidth()  - size) / 2;
        int y = (src.getHeight() - size) / 2;
        canvas.drawBitmap(src, new Rect(x, y, x + size, y + size), r, paint);
        return out;
    }
    // ----- Unblock notification — blocked user ko batao ki unblock ho gaya -----
    private void showUnblockNotification(final Map<String, String> data) {
        final String fromUid  = data.getOrDefault("fromUid", "");
        final String fromName = data.getOrDefault("fromName", "User");
        final int notifId = ("unblock_" + fromUid).hashCode();

        Intent open = new Intent(this, ChatActivity.class);
        open.putExtra("partnerUid",        fromUid);
        open.putExtra("partnerName",       fromName);
        open.putExtra("show_unblock_joy",  true);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, notifId, open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this,
                Constants.CHANNEL_REQUESTS)
            .setSmallIcon(R.drawable.ic_person_add)
            .setContentTitle("You've been unblocked!")
            .setContentText(fromName + " has unblocked you. You can now message them.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setAutoCancel(true)
            .setContentIntent(openPi);

        NotificationManager nm = (NotificationManager)
            getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(notifId, b.build());
    }

    private void showTypingNotification(String fromUid, String fromName,
                                        String chatId, int notifId, String subText) {
        Intent open = new Intent(this, ChatActivity.class);
        open.putExtra("partnerUid", fromUid);
        open.putExtra("partnerName", fromName);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, notifId, open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(this,
                Constants.CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_message_notification)
            .setContentTitle(fromName)
            .setContentText("typing…")
            .setSubText(subText)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(openPi);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b.setTimeoutAfter(7000);
        }
        NotificationManager nm = (NotificationManager)
            getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(notifId, b.build());
    }
    // ── Network level helper ─────────────────────────────────────────────────
    // Returns: 0 = no network, 1 = 2G/EDGE, 2 = 3G, 3 = 4G/5G/WiFi
    private int getNetworkLevel() {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return 0;
            NetworkInfo info = cm.getActiveNetworkInfo();
            if (info == null || !info.isConnected()) return 0;
            if (info.getType() == ConnectivityManager.TYPE_WIFI) return 3;
            int sub = info.getSubtype();
            switch (sub) {
                case TelephonyManager.NETWORK_TYPE_GPRS:
                case TelephonyManager.NETWORK_TYPE_EDGE:
                case TelephonyManager.NETWORK_TYPE_CDMA:
                case TelephonyManager.NETWORK_TYPE_1xRTT:
                case TelephonyManager.NETWORK_TYPE_IDEN:
                    return 1; // 2G
                case TelephonyManager.NETWORK_TYPE_UMTS:
                case TelephonyManager.NETWORK_TYPE_HSPA:
                case TelephonyManager.NETWORK_TYPE_HSDPA:
                case TelephonyManager.NETWORK_TYPE_HSUPA:
                case TelephonyManager.NETWORK_TYPE_HSPAP:
                case TelephonyManager.NETWORK_TYPE_EHRPD:
                    return 2; // 3G
                default:
                    return 3; // 4G/5G
            }
        } catch (Exception e) { return 2; } // safe fallback
    }
    @Nullable private Bitmap downloadBitmap(String url, int maxW, int maxH) {
        if (url == null || url.isEmpty()) return null;
        HttpURLConnection conn = null;
        InputStream is = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(8000);
            conn.connect();
            if (conn.getResponseCode() != 200) return null;
            is = conn.getInputStream();
            Bitmap bmp = BitmapFactory.decodeStream(is);
            if (bmp == null) return null;
            int w = bmp.getWidth(), h = bmp.getHeight();
            if (w <= maxW && h <= maxH) return bmp;
            float r = Math.min((float) maxW / w, (float) maxH / h);
            return Bitmap.createScaledBitmap(bmp,
                Math.max(1, (int)(w * r)), Math.max(1, (int)(h * r)), true);
        } catch (Exception e) {
            return null;
        } finally {
            try { if (is != null) is.close(); } catch (Exception ignored) {}
            if (conn != null) conn.disconnect();
        }
    }
    private static class HistoryItem {
        final String text; final long ts; final boolean fromMe;
        HistoryItem(String t, long s, boolean me) { text = t; ts = s; fromMe = me; }
    }
    // ----- Background-killed WhatsApp-style GROUP message notification -----
    // Features:
    //  * Stable per-group notif id (saare msgs same group ke ek hi notification me stack)
    //  * MessagingStyle (group conversation = true) with last 3 messages history
    //  * Sender Person + circular avatar; me Person with my own avatar (inline reply)
    //  * Inline RemoteInput "Reply" action (broadcast → NotificationActionReceiver)
    //  * "Mark as read" + "Mute group" actions
    //  * Image attachment preview if msg type is image
    //  * Lock-screen safe public version
    //  * Per-receiver mute → silent low-importance channel (still visible)
    //  * Group key bundle so multiple groups stack into a summary
    //  * Tap → open GroupChatActivity directly (deep-link)
    private void showGroupMessage(final Map<String, String> data) {
        final String groupId    = data.getOrDefault("groupId", "");
        final String groupName  = data.getOrDefault("groupName", "Group");
        final String groupIcon  = data.getOrDefault("groupIcon", "");
        final String fromUid    = data.getOrDefault("fromUid", "");
        final String fromName   = data.getOrDefault("fromName", "Member");
        final String fromPhoto  = data.getOrDefault("fromPhoto", "");
        final String fromMobile = data.getOrDefault("fromMobile", "");
        final String mediaUrl   = data.getOrDefault("mediaUrl", "");
        final String rawText    = data.getOrDefault("text", "Naya message");
        final String type       = data.getOrDefault("type", "group_message");
        final boolean serverMuted = "1".equals(data.getOrDefault("muted", "0"));
        final String text = previewTextFor(messageTypeFromGroupType(type), rawText);

        // ── Real-time DB insert: save group message to Room DB immediately ────
        final String grpMsgId = data.getOrDefault("msgId", fromUid + "_" + System.currentTimeMillis());
        saveMessageToDb(grpMsgId, groupId, fromUid, fromName,
            rawText, messageTypeFromGroupType(type),
            data.getOrDefault("mediaUrl", ""),
            data.getOrDefault("fileName", null), true);

        // ── @Mention detection ───────────────────────────────────────────────
        // Check FCM flag first; fall back to scanning text for "@name" / "@everyone"
        boolean rawMentionFlag = "true".equalsIgnoreCase(
                data.getOrDefault(com.callx.app.utils.Constants.GROUP_NOTIF_KEY_MENTION, "false"));
        String myDisplayName = "";
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            com.google.firebase.auth.FirebaseUser me =
                    com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();
            if (me != null && me.getDisplayName() != null) myDisplayName = me.getDisplayName();
        }
        final boolean isMention = rawMentionFlag
                || com.callx.app.utils.GroupNotificationHelper.detectMention(rawText, myDisplayName);
        final boolean isPriority = "true".equalsIgnoreCase(
                data.getOrDefault(com.callx.app.utils.Constants.GROUP_NOTIF_KEY_PRIORITY, "false"));

        // ── Per-group DND schedule check ─────────────────────────────────────
        // Fix 16: SharedPreferences MODE_PRIVATE is memory-cached after first load —
        // killed state mein pehli baar slow ho sakta tha. bg thread pe move karo
        // via early capture so onMessageReceived (binder thread) block na ho.
        // Note: getSharedPreferences() is thread-safe — calling from any thread is fine.
        if (!groupId.isEmpty()) {
            android.content.SharedPreferences dndPrefs = getSharedPreferences(
                    com.callx.app.utils.Constants.DND_PREFS_PREFIX + groupId,
                    Context.MODE_PRIVATE);
            // isDNDActive only reads long values — fast, no disk I/O after first load
            if (com.callx.app.utils.GroupNotificationHelper.isDNDActive(dndPrefs)) {
                boolean mentionAlertEnabled = dndPrefs.getBoolean("mention_alert", true);
                if (!isMention || !mentionAlertEnabled) {
                    return; // Suppressed by DND — no notification
                }
            }
        }

        long ls = 0L;
        try { ls = Long.parseLong(data.getOrDefault("fromLastSeen", "0")); }
        catch (Exception ignored) {}
        final long lastSeen = ls;
        final boolean online = (System.currentTimeMillis() - lastSeen)
                                < Constants.ONLINE_WINDOW_MS && lastSeen > 0;
        final String status   = online ? "Online" : "Offline";
        final String subText  = (fromMobile.isEmpty() ? "" : ("+" + fromMobile + " • "))
                                + status;

        if (groupId.isEmpty()) return;

        // Stable per-group notif id (Feature: same-group grouping)
        final int notifId = ("group_" + groupId).hashCode();

        // Logged out → just show simple
        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            buildAndShowGroup(groupId, groupName, groupIcon, fromUid, fromName,
                fromPhoto, mediaUrl, text, type, subText, notifId, null,
                serverMuted, isMention, isPriority);
            return;
        }
        final String myUid = FirebaseAuth.getInstance().getCurrentUser().getUid();

        // Fix 1: Self-notification guard — sender ko apna message ka notification nahi aana chahiye
        if (fromUid != null && fromUid.equals(myUid)) return;

        // Fix 2: Firebase mute listener hata diya — server already "muted" flag bhejta hai.
        // Killed state mein addListenerForSingleValueEvent unreliable hai — server flag use karo.
        // Fix 7: Server ab history bhi bhejta hai (same as 1-1) — parse karo
        List<GroupHistoryItem> serverHist = null;
        final String histJson = data.getOrDefault("history", "");
        if (!histJson.isEmpty()) {
            try {
                org.json.JSONArray arr = new org.json.JSONArray(histJson);
                serverHist = new ArrayList<>();
                final String myUidFinal = myUid;
                for (int i = 0; i < arr.length(); i++) {
                    org.json.JSONObject o = arr.getJSONObject(i);
                    String ht  = o.optString("t", "");
                    long   hts = o.optLong("ts",
                        System.currentTimeMillis() - (arr.length() - i) * 1000L);
                    boolean hme = o.optBoolean("me", false);
                    if (!ht.isEmpty()) serverHist.add(new GroupHistoryItem(
                        ht, hts, hme,
                        hme ? "You" : fromName,
                        hme ? myUidFinal : fromUid));
                }
                if (serverHist.isEmpty()) serverHist = null;
            } catch (Exception ignored) {
                serverHist = null;
            }
        }

        if (serverHist != null) {
            // Fix 7: History server se mili — direct build, zero Firebase
            buildAndShowGroup(groupId, groupName, groupIcon, fromUid, fromName,
                fromPhoto, mediaUrl, text, type, subText, notifId, serverHist,
                serverMuted, isMention, isPriority);
        } else {
            // Fallback: server history nahi aayi (old server) — Firebase se fetch karo
            loadGroupHistoryAndBuild(myUid, groupId, groupName, groupIcon,
                fromUid, fromName, fromPhoto, mediaUrl, text, type, subText,
                notifId, serverMuted, isMention, isPriority);
        }
    }
    // group_message → message; image/video/audio/file/pdf passthrough
    private static String messageTypeFromGroupType(String t) {
        if (t == null || "group_message".equals(t)) return "text";
        return t;
    }
    private void loadGroupHistoryAndBuild(final String myUid, final String groupId,
            final String groupName, final String groupIcon, final String fromUid,
            final String fromName, final String fromPhoto, final String mediaUrl,
            final String text, final String type, final String subText,
            final int notifId, final boolean muted,
            final boolean isMention, final boolean isPriority) {
        Query q = FirebaseUtils.getGroupMessagesRef(groupId)
            .orderByChild("timestamp").limitToLast(3);
        q.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                List<GroupHistoryItem> hist = new ArrayList<>();
                for (DataSnapshot c : snap.getChildren()) {
                    String s   = c.child("senderId").getValue() != null
                                ? String.valueOf(c.child("senderId").getValue()) : "";
                    String sn  = c.child("senderName").getValue() != null
                                ? String.valueOf(c.child("senderName").getValue()) : "Member";
                    String t   = c.child("text").getValue() != null
                                ? String.valueOf(c.child("text").getValue()) : "";
                    String tp  = c.child("type").getValue() != null
                                ? String.valueOf(c.child("type").getValue()) : "text";
                    Long   ts  = c.child("timestamp").getValue() != null
                                ? c.child("timestamp").getValue(Long.class)
                                : System.currentTimeMillis();
                    if (t.isEmpty()) t = previewTextFor(tp, "");
                    boolean fromMe = s.equals(myUid);
                    hist.add(new GroupHistoryItem(t, ts, fromMe, sn, s));
                }
                Collections.sort(hist, (a, b) -> Long.compare(a.ts, b.ts));
                buildAndShowGroup(groupId, groupName, groupIcon, fromUid, fromName,
                    fromPhoto, mediaUrl, text, type, subText, notifId, hist, muted,
                    isMention, isPriority);
            }
            @Override public void onCancelled(DatabaseError e) {
                buildAndShowGroup(groupId, groupName, groupIcon, fromUid, fromName,
                    fromPhoto, mediaUrl, text, type, subText, notifId, null, muted,
                    isMention, isPriority);
            }
        });
    }
    private void buildAndShowGroup(final String groupId, final String groupName,
            final String groupIcon, final String fromUid, final String fromName,
            final String fromPhoto, final String mediaUrl, final String text,
            final String type, final String subText, final int notifId,
            @Nullable final List<GroupHistoryItem> hist, final boolean muted,
            final boolean isMention, final boolean isPriority) {
        // Network-aware: 2G/no-network → skip avatars (instant notification).
        // myAvatar removed — not worth extra HTTP call.
        bg.execute(() -> {
            int net = getNetworkLevel();
            Bitmap senderAvatar = null;
            Bitmap groupAvatar  = null;
            if (net >= 2) { // 3G, 4G, 5G, WiFi
                senderAvatar = circle(downloadBitmap(fromPhoto, 100, 100));
                groupAvatar  = circle(downloadBitmap(groupIcon, 100, 100));
            }
            boolean isImage = "image".equals(type)
                && mediaUrl != null && !mediaUrl.isEmpty();
            // Image preview: only on WiFi/4G/5G (net==3)
            Bitmap picture = (isImage && net == 3)
                ? downloadBitmap(mediaUrl, 400, 300) : null;
            postRichGroupNotification(groupId, groupName, fromUid, fromName,
                fromPhoto, mediaUrl, text, type, subText, notifId, hist,
                senderAvatar, null, groupAvatar, picture, muted,
                isMention, isPriority);
        });
    }
    private void postRichGroupNotification(String groupId, String groupName,
            String fromUid, String fromName, String fromPhoto, String mediaUrl,
            String text, String type, String subText, int notifId,
            @Nullable List<GroupHistoryItem> hist,
            @Nullable Bitmap senderAvatar, @Nullable Bitmap myAvatar,
            @Nullable Bitmap groupAvatar, @Nullable Bitmap picture, boolean muted,
            boolean isMention, boolean isPriority) {
        // Sender Person (with circular avatar)
        Person.Builder sb = new Person.Builder().setName(fromName).setKey(fromUid);
        if (senderAvatar != null) sb.setIcon(IconCompat.createWithBitmap(senderAvatar));
        Person sender = sb.build();
        // Me Person (with my own avatar — inline reply right side me dikhega)
        Person.Builder meB = new Person.Builder().setName("You").setKey("me");
        if (myAvatar != null) meB.setIcon(IconCompat.createWithBitmap(myAvatar));
        Person me = meB.build();
        // Fix 1: TaskStackBuilder — killed state mein proper back stack ke saath launch
        // FLAG_ACTIVITY_NEW_TASK alone se back press pe crash hota tha (no back stack).
        // TaskStackBuilder: MainActivity (root) → GroupChatActivity (top)
        Intent mainIntent = new Intent(this, com.callx.app.activities.MainActivity.class);
        mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);

        Intent open = new Intent(this, com.callx.app.group.GroupChatActivity.class);
        open.putExtra("groupId",   groupId);
        open.putExtra("groupName", groupName);
        open.putExtra("groupPhoto", "");
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent openPi = androidx.core.app.TaskStackBuilder.create(this)
            .addNextIntent(mainIntent)
            .addNextIntentWithParentStack(open)
            .getPendingIntent(notifId,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        // Action: Reply (inline RemoteInput, group reply key)
        RemoteInput remoteInput =
            new RemoteInput.Builder(Constants.KEY_GROUP_TEXT_REPLY)
                .setLabel("Reply to " + groupName + "…").build();
        PendingIntent replyPi = PendingIntent.getBroadcast(this, notifId * 10 + 2,
            buildGroupActionIntent(Constants.ACTION_GROUP_REPLY,
                groupId, groupName, fromUid, fromName, notifId),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        NotificationCompat.Action replyAction =
            new NotificationCompat.Action.Builder(
                    R.drawable.ic_message_notification, "Reply", replyPi)
                .addRemoteInput(remoteInput)
                .setAllowGeneratedReplies(true)
                .setSemanticAction(
                    NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                .build();
        // Action: Mark as read
        PendingIntent markPi = PendingIntent.getBroadcast(this, notifId * 10 + 1,
            buildGroupActionIntent(Constants.ACTION_GROUP_MARK_READ,
                groupId, groupName, fromUid, fromName, notifId),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Action markAction =
            new NotificationCompat.Action.Builder(
                    R.drawable.ic_message_notification, "Mark as read", markPi)
                .setSemanticAction(
                    NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
                .setShowsUserInterface(false)
                .build();
        // Action: Mute group
        PendingIntent mutePi = PendingIntent.getBroadcast(this, notifId * 10 + 3,
            buildGroupActionIntent(Constants.ACTION_GROUP_MUTE,
                groupId, groupName, fromUid, fromName, notifId),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Action muteAction =
            new NotificationCompat.Action.Builder(
                    R.drawable.ic_message_notification, "Mute group", mutePi)
                .setSemanticAction(
                    NotificationCompat.Action.SEMANTIC_ACTION_MUTE)
                .build();

        // MessagingStyle — last 3 messages + current message, isGroupConversation = true
        // NOTE: current `text` from FCM payload is always appended last so the
        // latest message is always visible even if DB write hasn't propagated yet.
        NotificationCompat.MessagingStyle style =
            new NotificationCompat.MessagingStyle(me)
                .setConversationTitle(groupName)
                .setGroupConversation(true);
        if (hist != null && !hist.isEmpty()) {
            // Sender Person ko reuse karne ke liye uid → Person map maintain karo
            Map<String, Person> personCache = new HashMap<>();
            personCache.put(fromUid, sender);
            personCache.put("me",    me);
            // Fix 3: Dedup — server history mein current message bhi hota hai (limitToLast).
            // Last item skip karo agar text match kare aur timestamp recent ho (3s window).
            int grpSkipIdx = -1;
            for (int i = hist.size() - 1; i >= 0; i--) {
                GroupHistoryItem gh = hist.get(i);
                if (gh.text.equals(text) && Math.abs(System.currentTimeMillis() - gh.ts) < 3000L) {
                    grpSkipIdx = i;
                    break;
                }
            }
            for (int i = 0; i < hist.size(); i++) {
                if (i == grpSkipIdx) continue;
                GroupHistoryItem h = hist.get(i);
                Person p;
                if (h.fromMe) {
                    p = me;
                } else if (personCache.containsKey(h.senderUid)) {
                    p = personCache.get(h.senderUid);
                } else {
                    p = new Person.Builder()
                        .setName(h.senderName == null ? "Member" : h.senderName)
                        .setKey(h.senderUid)
                        .build();
                    personCache.put(h.senderUid, p);
                }
                style.addMessage(h.text, h.ts, p);
            }
        }
        // Always add the just-arrived message last (latest = most visible)
        style.addMessage(text, System.currentTimeMillis(), sender);

        // ── Enhanced channel selection ────────────────────────────────────────
        // Priority: mention bypass > priority flag > muted > normal
        String channel;
        if (isMention) {
            // @mention always gets its own high-importance channel (bypasses mute/DND)
            channel = Constants.CHANNEL_GROUP_MENTION;
        } else if (isPriority) {
            // Admin-flagged priority message
            channel = Constants.CHANNEL_GROUP_PRIORITY;
        } else if (muted) {
            // Muted group — silent delivery
            channel = Constants.CHANNEL_GROUPS_MUTED;
        } else {
            // Normal group message
            channel = Constants.CHANNEL_GROUPS;
        }
        // Ensure all channels are registered (idempotent on subsequent calls)
        com.callx.app.utils.GroupNotificationHelper.ensureChannels(this);

        // Lock-screen safe public version (no preview / no image)
        NotificationCompat.Builder publicB = new NotificationCompat.Builder(this,
                channel)
            .setSmallIcon(R.drawable.ic_group)
            .setContentTitle(groupName)
            .setContentText("New group message")
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);

        // Main builder — group key for stacking + summary support
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_group)
            .setContentTitle(groupName)
            .setContentText(fromName + ": " + text)
            .setSubText(subText)
            .setStyle(style)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setAutoCancel(true)
            .setOnlyAlertOnce(!isMention)  // Fix 9: same sender ke multiple msgs pe sirf pehli baar sound/vibrate
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicB.build())
            .setPriority(isMention || isPriority ? NotificationCompat.PRIORITY_MAX
                             : muted ? NotificationCompat.PRIORITY_LOW
                             : NotificationCompat.PRIORITY_HIGH)
            .setVibrate(isMention  ? new long[]{0,150,80,150,80,300}
                      : isPriority ? new long[]{0,300,100,400}
                      : null)
            .setGroup(Constants.GROUP_KEY_GROUPS)
            .setContentIntent(openPi)
            .addAction(replyAction)
            .addAction(markAction)
            .addAction(muteAction);
        // Group icon as large icon (fallback to sender avatar, then default drawable)
        // Fix 15: Low network pe avatar null aata tha — blank large icon dikhta tha.
        //         Default group drawable use karo as final fallback.
        if (groupAvatar != null) {
            b.setLargeIcon(groupAvatar);
        } else if (senderAvatar != null) {
            b.setLargeIcon(senderAvatar);
        } else {
            // Vector drawable → Bitmap fallback
            try {
                android.graphics.drawable.Drawable d = androidx.core.content.ContextCompat
                    .getDrawable(this, R.drawable.ic_group);
                if (d != null) {
                    Bitmap fb = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
                    Canvas fc = new Canvas(fb);
                    d.setBounds(0, 0, 96, 96);
                    d.draw(fc);
                    b.setLargeIcon(fb);
                }
            } catch (Exception ignored) {}
        }
        // Image attachment preview
        if (picture != null) {
            b.setStyle(new NotificationCompat.BigPictureStyle()
                .bigPicture(picture)
                .setSummaryText(fromName + ": " + text));
        }
        NotificationManager nm = (NotificationManager)
            getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(notifId, b.build());

        // Summary notification — multiple groups ko ek bundle me dikhata hai
        NotificationCompat.Builder summary = new NotificationCompat.Builder(this,
                channel)
            .setSmallIcon(R.drawable.ic_group)
            .setContentTitle("Group messages")
            .setContentText(groupName + ": " + fromName)
            .setStyle(new NotificationCompat.InboxStyle()
                .setSummaryText("CallX Groups"))
            .setGroup(Constants.GROUP_KEY_GROUPS)
            .setGroupSummary(true)
            .setAutoCancel(true);
        // Fix 9: Muted group summary bhi silent hona chahiye (1-1 mein tha, group mein missing tha)
        if (muted) summary.setSilent(true);
        nm.notify(Constants.GROUP_KEY_GROUPS.hashCode(), summary.build());

        // ✅ Fix: Save to notification_log so AllNotificationsActivity Groups tab shows this notification
        if (!muted) { // Don't log muted groups (would pollute the notification center)
            String logTitle = groupName != null ? groupName : "Group message";
            String logBody  = (fromName != null ? fromName + ": " : "") + text;
            com.callx.app.utils.NotificationFirebaseStore.save(
                com.callx.app.utils.NotificationFirebaseStore.TYPE_GROUP,
                logTitle, logBody,
                fromUid, fromName, fromPhoto,
                /*reelId*/ null, /*chatId*/ null, /*groupId*/ groupId,
                com.callx.app.utils.NotificationFirebaseStore.DELIVERY_BACKGROUND);
        }
    }
    private Intent buildGroupActionIntent(String action, String groupId,
                                          String groupName, String fromUid,
                                          String fromName, int notifId) {
        return new Intent(this, NotificationActionReceiver.class)
            .setAction(action)
            .putExtra(Constants.EXTRA_GROUP_ID,     groupId   == null ? "" : groupId)
            .putExtra(Constants.EXTRA_GROUP_NAME,   groupName == null ? "" : groupName)
            .putExtra(Constants.EXTRA_PARTNER_UID,  fromUid   == null ? "" : fromUid)
            .putExtra(Constants.EXTRA_PARTNER_NAME, fromName  == null ? "" : fromName)
            .putExtra(Constants.EXTRA_NOTIF_ID,     notifId);
    }
    private static class GroupHistoryItem {
        final String text; final long ts; final boolean fromMe;
        final String senderName; final String senderUid;
        GroupHistoryItem(String t, long s, boolean me, String sn, String su) {
            text = t; ts = s; fromMe = me; senderName = sn; senderUid = su;
        }
    }
    private void showStatus(Map<String, String> data) {
        String fromUid   = data.getOrDefault("fromUid",   "");
        String name      = data.getOrDefault("fromName",  "Friend");
        String fromPhoto = data.getOrDefault("fromPhoto", "");
        String fromThumb = data.getOrDefault("fromThumb", "");
        String statusType= data.getOrDefault("statusType","text");
        String text      = data.getOrDefault("text",      "");
        String myUid     = com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser() != null
            ? com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser().getUid() : null;

        // ── Show system notification ───────────────────────────────────────
        // Deep-link directly to StatusViewerActivity so the status opens on tap,
        // even when app is killed. EXTRA_OWNER_UID + NAME are required by StatusViewerActivity.
        Intent i = new Intent(this, com.callx.app.viewer.StatusViewerActivity.class);
        i.putExtra(com.callx.app.viewer.StatusViewerActivity.EXTRA_OWNER_UID,  fromUid);
        i.putExtra(com.callx.app.viewer.StatusViewerActivity.EXTRA_OWNER_NAME, name);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this,
            fromUid.hashCode(),
            i,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String bodyText;
        switch (statusType) {
            case "image":  bodyText = "📷 Posted a photo status"; break;
            case "video":  bodyText = "🎥 Posted a video status"; break;
            default:       bodyText = text.isEmpty() ? "Posted a new status" : text; break;
        }

        NotificationCompat.Builder b = new NotificationCompat.Builder(this,
                Constants.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_status_notification)
            .setContentTitle(name)
            .setContentText(bodyText)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pi);
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(new Random().nextInt(99999), b.build());

        // ── Save to Firebase statusNotifications so AllNotifications Status tab shows it ──
        if (myUid == null || myUid.isEmpty() || fromUid.isEmpty()) return;
        bg.execute(() -> {
            try {
                java.util.Map<String, Object> entry = new java.util.HashMap<>();
                entry.put("fromUid",    fromUid);
                entry.put("fromName",   name);
                entry.put("fromPhoto",  fromPhoto);
                entry.put("fromThumb",  fromThumb.isEmpty() ? fromPhoto : fromThumb);
                entry.put("type",       statusType);
                entry.put("body",       bodyText);
                entry.put("timestamp",  System.currentTimeMillis());
                entry.put("read",       false);
                com.google.firebase.database.FirebaseDatabase.getInstance()
                    .getReference("statusNotifications")
                    .child(myUid)
                    .push()
                    .setValue(entry);
            } catch (Exception e) {
                android.util.Log.w("STATUS_NOTIF", "Failed to save status notif: " + e.getMessage());
            }
        });
    }

    // ════════════════════════════════════════════════════════════════
    // REAL-TIME DB INSERT — save every inbound FCM message to Room DB
    // Runs on background thread; never blocks main thread.
    // ChatActivity / GroupChatActivity observe LiveData — auto-updates.
    // ════════════════════════════════════════════════════════════════
    private void saveMessageToDb(String msgId, String chatId,
                                  String senderId, String senderName,
                                  String text, String type,
                                  String mediaUrl, String fileName,
                                  boolean isGroup) {
        if (chatId == null || chatId.isEmpty()) return;
        if (msgId  == null || msgId.isEmpty())  return;

        bg.execute(() -> {
            try {
                MessageEntity entity = new MessageEntity();
                entity.id         = msgId;
                entity.chatId     = chatId;
                entity.senderId   = senderId;
                entity.senderName = senderName;
                entity.text       = text;
                entity.type       = normalizeType(type);
                entity.mediaUrl   = (mediaUrl != null && !mediaUrl.isEmpty()) ? mediaUrl : null;
                entity.fileName   = fileName;
                entity.timestamp  = System.currentTimeMillis();
                entity.status     = "delivered";
                entity.deleted    = false;
                entity.starred    = false;
                entity.isGroup    = isGroup;
                entity.syncedAt   = System.currentTimeMillis();

                AppDatabase.getInstance(getApplicationContext())
                    .messageDao().insertMessage(entity);

                // OFFLINE FIX: Media pre-cache — image/audio FCM se aaye to
                // background mein download karo taaki offline mein bhi dikhe
                if (mediaUrl != null && !mediaUrl.isEmpty()) {
                    String t = normalizeType(type);
                    if ("image".equals(t) || "audio".equals(t) || "sticker".equals(t) || "gif".equals(t)) {
                        com.callx.app.utils.MediaCache.get(
                            getApplicationContext(), mediaUrl,
                            new com.callx.app.utils.MediaCache.Callback() {
                                @Override public void onReady(java.io.File f) {
                                    android.util.Log.d("FCM_CACHE",
                                        "Media pre-cached: " + f.getName());
                                }
                                @Override public void onError(String reason) {
                                    android.util.Log.w("FCM_CACHE",
                                        "Pre-cache failed: " + reason);
                                }
                            });
                    }
                }

            } catch (Exception e) {
                android.util.Log.w("FCM_DB", "saveMessageToDb failed: " + e.getMessage());
            }
        });
    }

    /** Normalize FCM type strings to Room entity type strings. */
    private String normalizeType(String fcmType) {
        if (fcmType == null) return "text";
        switch (fcmType) {
            case "image":        return "image";
            case "video":        return "video";
            case "audio":        return "audio";
            case "voice":        return "audio";
            case "file":         return "file";
            case "document":     return "file";
            case "gif":          return "gif";
            case "sticker":      return "sticker";
            case "group_sticker": return "sticker";
            case "group_image":  return "image";
            case "group_video":  return "video";
            case "group_audio":  return "audio";
            case "group_voice":  return "audio";
            case "group_file":   return "file";
            default:             return "text";
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Notification Center log
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Persists every received FCM notification to SharedPreferences so
     * NotificationCenterActivity can display a full history with the
     * app-state (foreground / background / killed) at time of delivery.
     *
     * Key  : notif_{timestamp}
     * Value: simple JSON string
     */
    private void logNotificationToPrefs(Map<String, String> data) {
        try {
            String type  = data.getOrDefault("type", "message");
            String title = data.containsKey("title") ? data.get("title") :
                           data.containsKey("senderName") ? data.get("senderName") : "CallX";
            String body  = data.containsKey("body")    ? data.get("body")    :
                           data.containsKey("message")  ? data.get("message") : "";

            // Determine category
            String cat;
            if (type != null && (type.contains("call"))) cat = "call";
            else if (type != null && type.contains("reel")) cat = "reel";
            else if (type != null && type.startsWith("group")) cat = "message";
            else cat = "message";

            // Determine app state
            String appState = isAppForeground() ? "foreground" : "background";
            // Note: when app is killed FCM delivers this via system — we still
            // log it (service starts fresh) so mark as "killed" when no activity resumed yet
            if (!isAppForeground() && !isAppVisible()) appState = "killed";

            long ts  = System.currentTimeMillis();
            String id = "notif_" + ts;

            // Build minimal JSON manually to avoid JSON library dependency
            String json = "{\"id\":\"" + id + "\","
                        + "\"cat\":\"" + cat + "\","
                        + "\"title\":\"" + escapeJson(title) + "\","
                        + "\"body\":\"" + escapeJson(body) + "\","
                        + "\"ts\":\"" + ts + "\","
                        + "\"state\":\"" + appState + "\"}";

            android.content.SharedPreferences prefs =
                getSharedPreferences("callx_notif_log", android.content.Context.MODE_PRIVATE);

            // Keep max 200 entries — prune oldest if needed
            android.content.SharedPreferences.Editor editor = prefs.edit();
            Map<String, ?> all = prefs.getAll();
            if (all.size() >= 200) {
                // Remove oldest 20
                List<String> keys = new java.util.ArrayList<>(all.keySet());
                Collections.sort(keys);
                for (int i = 0; i < 20 && i < keys.size(); i++) {
                    editor.remove(keys.get(i));
                }
            }
            editor.putString(id, json);
            editor.apply();
        } catch (Exception e) {
            android.util.Log.w("NOTIF_LOG", "logNotificationToPrefs failed: " + e.getMessage());
        }
    }

    private boolean isAppForeground() {
        android.app.ActivityManager am =
            (android.app.ActivityManager) getSystemService(android.content.Context.ACTIVITY_SERVICE);
        if (am == null) return false;
        List<android.app.ActivityManager.RunningAppProcessInfo> processes = am.getRunningAppProcesses();
        if (processes == null) return false;
        String packageName = getPackageName();
        for (android.app.ActivityManager.RunningAppProcessInfo proc : processes) {
            if (proc.importance ==
                android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                && proc.processName.equals(packageName)) {
                return true;
            }
        }
        return false;
    }

    private boolean isAppVisible() {
        android.app.ActivityManager am =
            (android.app.ActivityManager) getSystemService(android.content.Context.ACTIVITY_SERVICE);
        if (am == null) return false;
        List<android.app.ActivityManager.RunningAppProcessInfo> processes = am.getRunningAppProcesses();
        if (processes == null) return false;
        String packageName = getPackageName();
        for (android.app.ActivityManager.RunningAppProcessInfo proc : processes) {
            if (proc.importance <=
                android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
                && proc.processName.equals(packageName)) {
                return true;
            }
        }
        return false;
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

      // ─── Status Reaction ────────────────────────────────────────────────────
      private void handleStatusReaction(java.util.Map<String, String> data) {
          String reactorUid   = safeGet(data, "fromUid");
          String reactorName  = safeGet(data, "fromName");
          String reactorPhoto = safeGet(data, "fromPhoto");
          String reaction     = safeGet(data, "reaction");
          String ownerUid     = safeGet(data, "ownerUid");
          if (reactorName == null || reaction == null) return;
          com.callx.app.utils.StatusNotificationHelper.postStatusReactionNotification(
              getApplicationContext(), reactorUid, reactorName, reactorPhoto,
              reaction, ownerUid != null ? ownerUid : "");
          // Save to Firebase store
          com.callx.app.utils.NotificationFirebaseStore.save(
              com.callx.app.utils.NotificationFirebaseStore.TYPE_STATUS,
              reactorName + " reacted " + reaction + " to your status", "",
              reactorUid, reactorName, reactorPhoto, null, null, null,
              isForegrounded() ? com.callx.app.utils.NotificationFirebaseStore.DELIVERY_FOREGROUND
                               : com.callx.app.utils.NotificationFirebaseStore.DELIVERY_BACKGROUND);
      }

      // ─── Status Reply Notification (background/killed safe) ────────────────
      /**
       * Called when a contact replies to the current user's status.
       * Payload fields (sent by PushNotify.notifyStatusReply):
       *   fromUid   — replier's UID
       *   fromName  — replier's display name
       *   fromPhoto — replier's avatar URL
       *   text      — the reply text
       *   chatId    — deterministic chatId so tap opens the right conversation
       */
      // ═══════════════════════════════════════════════════════════════════════
      // showStatusReply — Background/killed-safe rich notification
      //
      // FIX: Previously this notification was missing:
      //   1. Inline "Reply" action (owner couldn't reply from notification tray)
      //   2. "Mark as read" action
      //   3. Dedicated high-importance channel (was using CHANNEL_STATUS which
      //      is IMPORTANCE_DEFAULT — no heads-up on locked screen)
      //   4. fromPhoto null-crash protection was too loose
      //   5. NotificationFirebaseStore log was missing (AllNotifications tab blank)
      //
      // Now delivers a WhatsApp-style heads-up notification with:
      //   • Large avatar (replier's photo)
      //   • BigTextStyle showing the full reply text
      //   • Inline Reply action → NotificationActionReceiver → sends chat message
      //   • Mark as read action → dismisses the notification
      //   • CHANNEL_MESSAGES channel (HIGH importance) → heads-up even on lock screen
      //   • NotificationFirebaseStore.save() so AllNotifications Status tab shows it
      // ═══════════════════════════════════════════════════════════════════════
      private void showStatusReply(java.util.Map<String, String> data) {
          final String fromUid   = safeGet(data, "fromUid");
          final String fromName  = safeGet(data, "fromName");
          final String fromPhoto = safeGet(data, "fromPhoto");
          final String replyText = safeGet(data, "text");
          final String chatId    = safeGet(data, "chatId");

          // Guard: fromName is required for a meaningful notification
          if (fromName == null || fromName.isEmpty()) return;

          final String safeFromUid   = fromUid  != null ? fromUid  : "";
          final String safeChatId    = chatId   != null ? chatId   : "";
          final String safeFromPhoto = fromPhoto != null ? fromPhoto : "";
          final String body = (replyText != null && !replyText.isEmpty())
                  ? replyText
                  : "Replied to your status";

          // Stable notification ID per replier
          final int notifId = ("status_reply_" + safeFromUid).hashCode() & 0x7FFFFFFF;

          // ── Tap intent → open ChatActivity ───────────────────────────────
          android.content.Intent openIntent = new android.content.Intent(
                  getApplicationContext(), ChatActivity.class);
          openIntent.putExtra(Constants.EXTRA_PARTNER_UID,  safeFromUid);
          openIntent.putExtra(Constants.EXTRA_PARTNER_NAME, fromName);
          openIntent.putExtra(Constants.EXTRA_PARTNER_PHOTO, safeFromPhoto);
          if (!safeChatId.isEmpty()) openIntent.putExtra("chatId", safeChatId);
          openIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                  | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);

          android.app.PendingIntent tapPi = android.app.PendingIntent.getActivity(
                  getApplicationContext(), notifId, openIntent,
                  android.app.PendingIntent.FLAG_UPDATE_CURRENT
                  | android.app.PendingIntent.FLAG_IMMUTABLE);

          // ── Inline Reply action ───────────────────────────────────────────
          // RemoteInput → status owner types reply → NotificationActionReceiver
          // handles it, sends message to chatId, dismisses notif.
          androidx.core.app.RemoteInput remoteInput =
              new androidx.core.app.RemoteInput.Builder(Constants.KEY_TEXT_REPLY)
                  .setLabel("Reply to " + fromName + "…")
                  .build();

          android.app.PendingIntent replyPi = android.app.PendingIntent.getBroadcast(
                  getApplicationContext(),
                  notifId * 10 + 2,
                  new android.content.Intent(getApplicationContext(),
                          com.callx.app.services.NotificationActionReceiver.class)
                      .setAction(Constants.ACTION_REPLY)
                      .putExtra(Constants.EXTRA_CHAT_ID,       safeChatId)
                      .putExtra(Constants.EXTRA_PARTNER_UID,   safeFromUid)
                      .putExtra(Constants.EXTRA_PARTNER_NAME,  fromName)
                      .putExtra(Constants.EXTRA_PARTNER_PHOTO, safeFromPhoto)
                      .putExtra(Constants.EXTRA_NOTIF_ID,      notifId),
                  android.app.PendingIntent.FLAG_UPDATE_CURRENT
                  | android.app.PendingIntent.FLAG_MUTABLE);

          androidx.core.app.NotificationCompat.Action replyAction =
              new androidx.core.app.NotificationCompat.Action.Builder(
                      R.drawable.ic_reply, "Reply", replyPi)
                  .addRemoteInput(remoteInput)
                  .setAllowGeneratedReplies(true)
                  .setSemanticAction(
                      androidx.core.app.NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                  .build();

          // ── Mark as read action ───────────────────────────────────────────
          android.app.PendingIntent markReadPi = android.app.PendingIntent.getBroadcast(
                  getApplicationContext(),
                  notifId * 10 + 1,
                  new android.content.Intent(getApplicationContext(),
                          com.callx.app.services.NotificationActionReceiver.class)
                      .setAction(Constants.ACTION_MARK_READ)
                      .putExtra(Constants.EXTRA_CHAT_ID,      safeChatId)
                      .putExtra(Constants.EXTRA_PARTNER_UID,  safeFromUid)
                      .putExtra(Constants.EXTRA_PARTNER_NAME, fromName)
                      .putExtra(Constants.EXTRA_NOTIF_ID,     notifId),
                  android.app.PendingIntent.FLAG_UPDATE_CURRENT
                  | android.app.PendingIntent.FLAG_IMMUTABLE);

          androidx.core.app.NotificationCompat.Action markReadAction =
              new androidx.core.app.NotificationCompat.Action.Builder(
                      R.drawable.ic_double_tick, "Mark as read", markReadPi)
                  .setSemanticAction(
                      androidx.core.app.NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
                  .setShowsUserInterface(false)
                  .build();

          // ── Build notification on background thread (avatar download) ─────
          final String finalBody    = body;
          final String finalName    = fromName;
          bg.execute(() -> {
              // Download + circle-crop avatar (safe null handling)
              android.graphics.Bitmap avatar = null;
              if (!safeFromPhoto.isEmpty()) {
                  try {
                      avatar = downloadBitmap(safeFromPhoto, 100, 100);
                      if (avatar != null) avatar = circle(avatar);
                  } catch (Exception ignored) {}
              }

              // Use CHANNEL_MESSAGES (HIGH importance) → heads-up on lock screen
              // CHANNEL_STATUS is IMPORTANCE_DEFAULT which won't show heads-up.
              androidx.core.app.NotificationCompat.Builder nb =
                  new androidx.core.app.NotificationCompat.Builder(
                          getApplicationContext(), Constants.CHANNEL_MESSAGES)
                      .setSmallIcon(R.drawable.ic_status_notification)
                      .setContentTitle(finalName + " replied to your status")
                      .setContentText(finalBody)
                      .setStyle(new androidx.core.app.NotificationCompat.BigTextStyle()
                              .bigText(finalBody)
                              .setBigContentTitle(finalName + " replied to your status"))
                      .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                      .setCategory(androidx.core.app.NotificationCompat.CATEGORY_MESSAGE)
                      .setAutoCancel(true)
                      .setOnlyAlertOnce(true)
                      .setContentIntent(tapPi)
                      .addAction(replyAction)
                      .addAction(markReadAction);

              if (avatar != null) nb.setLargeIcon(avatar);

              android.app.NotificationManager nm =
                  (android.app.NotificationManager) getSystemService(
                      android.content.Context.NOTIFICATION_SERVICE);
              if (nm != null) nm.notify(notifId, nb.build());
          });

          // ── Save to Firebase statusNotifications (AllNotifications tab) ───
          String myUid = com.google.firebase.auth.FirebaseAuth.getInstance()
                  .getCurrentUser() != null
                  ? com.google.firebase.auth.FirebaseAuth.getInstance()
                    .getCurrentUser().getUid()
                  : null;
          if (myUid != null && !safeFromUid.isEmpty()) {
              bg.execute(() -> {
                  try {
                      java.util.Map<String, Object> entry = new java.util.HashMap<>();
                      entry.put("fromUid",   safeFromUid);
                      entry.put("fromName",  fromName);
                      entry.put("fromPhoto", safeFromPhoto);
                      entry.put("type",      "status_reply");
                      entry.put("body",      finalBody);
                      entry.put("chatId",    safeChatId);
                      entry.put("timestamp", System.currentTimeMillis());
                      entry.put("read",      false);
                      com.google.firebase.database.FirebaseDatabase.getInstance()
                          .getReference("statusNotifications")
                          .child(myUid)
                          .push()
                          .setValue(entry);
                  } catch (Exception e) {
                      android.util.Log.w("STATUS_REPLY_NOTIF",
                          "Firebase save failed: " + e.getMessage());
                  }
              });
          }

          // ── NotificationFirebaseStore log (AllNotifications Status tab) ───
          com.callx.app.utils.NotificationFirebaseStore.save(
              com.callx.app.utils.NotificationFirebaseStore.TYPE_STATUS,
              finalName + " replied to your status",
              finalBody,
              safeFromUid, fromName, safeFromPhoto,
              null, safeChatId, null,
              isForegrounded()
                  ? com.callx.app.utils.NotificationFirebaseStore.DELIVERY_FOREGROUND
                  : com.callx.app.utils.NotificationFirebaseStore.DELIVERY_BACKGROUND);
      }


      private void handleContactJoin(java.util.Map<String, String> data) {
          String newUid   = safeGet(data, "newUid");
          String newName  = safeGet(data, "newName");
          String newPhoto = safeGet(data, "newPhoto");
          if (newName == null) return;
          com.callx.app.utils.ContactJoinHelper.notifyContactJoined(
              getApplicationContext(), newUid != null ? newUid : "", newName,
              newPhoto != null ? newPhoto : "");
      }

      // ─── Group Member Joined ─────────────────────────────────────────────────
      private void handleGroupMemberJoined(java.util.Map<String, String> data) {
          String groupId       = safeGet(data, "groupId");
          String groupName     = safeGet(data, "groupName");
          String memberName    = safeGet(data, "newMemberName");
          String memberPhoto   = safeGet(data, "newMemberPhoto");
          if (groupId == null || memberName == null) return;
          com.callx.app.utils.GroupNotificationHelper.showMemberJoinedNotification(
              getApplicationContext(), groupId, groupName != null ? groupName : "Group",
              memberName, memberPhoto != null ? memberPhoto : "");
      }

      // ─── App foreground state helper ─────────────────────────────────────────
      private boolean isForegrounded() {
          try {
              android.app.ActivityManager am = (android.app.ActivityManager)
                  getSystemService(android.app.ActivityManager.class);
              java.util.List<android.app.ActivityManager.RunningAppProcessInfo> procs =
                  am.getRunningAppProcesses();
              if (procs == null) return false;
              for (android.app.ActivityManager.RunningAppProcessInfo p : procs) {
                  if (p.importance ==
                      android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
                      p.processName.equals(getPackageName())) return true;
              }
          } catch (Exception ignored) {}
          return false;
      }

    /** Safely retrieve a value from an FCM data map, returning null if absent or empty. */
    private String safeGet(Map<String, String> data, String key) {
        if (data == null) return null;
        String v = data.get(key);
        return (v != null && !v.isEmpty()) ? v : null;
    }

}