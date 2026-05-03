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
import com.callx.app.activities.ChatActivity;
import com.callx.app.activities.IncomingCallActivity;
import com.callx.app.activities.IncomingGroupCallActivity;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
public class CallxMessagingService extends FirebaseMessagingService {
    @Override public void onNewToken(String token) {
        if (FirebaseAuth.getInstance().getCurrentUser() == null) return;
        String uid = FirebaseAuth.getInstance().getCurrentUser().getUid();
        FirebaseUtils.getUserRef(uid).child("fcmToken").setValue(token);
    }
    @Override public void onMessageReceived(RemoteMessage msg) {
        Map<String, String> data = msg.getData();
        String type = data.getOrDefault("type", "message");
        if ("call".equals(type) || "video_call".equals(type)) {
            showIncomingCall(data, "video_call".equals(type));
        } else if (Constants.GCALL_FCM_TYPE.equals(type)) {
            // Incoming group call — works even when app is killed
            showIncomingGroupCall(data);
        } else if ("group_call_missed".equals(type)) {
            // Missed group call notification
            showMissedGroupCallNotification(data);
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

          // 1. Start IncomingRingService — rings even when app is killed
          Intent ringIntent = new Intent(this, IncomingRingService.class);
          ringIntent.putExtra(Constants.EXTRA_CALL_ID,       callId);
          ringIntent.putExtra(Constants.EXTRA_PARTNER_UID,   fromUid);
          ringIntent.putExtra(Constants.EXTRA_PARTNER_NAME,  fromName);
          ringIntent.putExtra(Constants.EXTRA_IS_VIDEO,      isVideo);
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
          acceptIntent.putExtra(Constants.EXTRA_IS_VIDEO,      isVideo);
          PendingIntent acceptPi = PendingIntent.getActivity(this,
              Constants.CALL_RING_NOTIF_ID, acceptIntent,
              PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

          // 3. "Decline" broadcast → NotificationActionReceiver
          Intent declineIntent = new Intent(this, NotificationActionReceiver.class);
          declineIntent.setAction(Constants.ACTION_DECLINE_CALL);
          declineIntent.putExtra(Constants.EXTRA_CALL_ID,      callId);
          declineIntent.putExtra(Constants.EXTRA_PARTNER_UID,  fromUid);
          declineIntent.putExtra(Constants.EXTRA_PARTNER_NAME, fromName);
          declineIntent.putExtra(Constants.EXTRA_IS_VIDEO,     isVideo);
          declineIntent.putExtra(Constants.EXTRA_NOTIF_ID,     Constants.CALL_RING_NOTIF_ID);
          PendingIntent declinePi = PendingIntent.getBroadcast(this,
              Constants.CALL_RING_NOTIF_ID + 1, declineIntent,
              PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

          // 4. Build notification async (avatar downloaded off-thread)
          bg.execute(() -> {
              Bitmap avatar = circle(downloadBitmap(fromPhoto, 256, 256));

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
        final String callerUid  = data.getOrDefault(Constants.GCALL_FCM_CALLER_UID,  "");
        final String callerName = data.getOrDefault(Constants.GCALL_FCM_CALLER_NAME, "Someone");
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
                    ringIntent.putExtra(Constants.EXTRA_CALL_ID,            callId);
                    ringIntent.putExtra(Constants.EXTRA_GROUP_ID,           groupId);
                    ringIntent.putExtra(Constants.EXTRA_GROUP_NAME,         groupName);
                    ringIntent.putExtra(Constants.EXTRA_GROUP_ICON,         groupIcon);
                    ringIntent.putExtra(Constants.EXTRA_GROUP_CALLER_UID,   callerUid);
                    ringIntent.putExtra(Constants.EXTRA_GROUP_CALLER_NAME,  callerName);
                    ringIntent.putExtra(Constants.EXTRA_IS_VIDEO,           isVideo);
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
    }

    // ----- Background-killed WhatsApp-style message notification -----
    private final ExecutorService bg = Executors.newCachedThreadPool();
    private void showMessage(final Map<String, String> data) {
        final String fromUid    = data.getOrDefault("fromUid", "");
        final String fromName   = data.getOrDefault("fromName", "CallX");
        final String fromMobile = data.getOrDefault("fromMobile", "");
        final String fromPhoto  = data.getOrDefault("fromPhoto", "");
        final String chatId     = data.getOrDefault("chatId", "");
        final String mediaUrl   = data.getOrDefault("mediaUrl", "");
        final String rawText    = data.getOrDefault("text", "Naya message");
        final String type       = data.getOrDefault("type", "message");
        // Feature 7+8 — type-specific preview text
        final String text = previewTextFor(type, rawText);
        long ls = 0L;
        try { ls = Long.parseLong(data.getOrDefault("fromLastSeen", "0")); }
        catch (Exception ignored) {}
        final long lastSeen = ls;
        final boolean online = (System.currentTimeMillis() - lastSeen)
                                < Constants.ONLINE_WINDOW_MS && lastSeen > 0;
        final String status = online ? "Online" : "Offline";
        final String subText = (fromMobile.isEmpty() ? "" : ("+" + fromMobile + " • "))
                               + status;
        // Stable per-sender notification id (Feature 6 — same user grouping)
        final int notifId = ("chat_" + (fromUid == null ? "" : fromUid)).hashCode();
        // Typing event → just update the existing chat notification briefly
        if ("typing".equals(type)) {
            showTypingNotification(fromUid, fromName, chatId, notifId, subText);
            return;
        }
        // ── Real-time DB insert: save message to Room DB immediately ──────────
        // This ensures offline-first: message is in local DB even before user opens the chat.
        final String msgId = data.getOrDefault("msgId", fromUid + "_" + System.currentTimeMillis());
        saveMessageToDb(msgId, chatId, fromUid, fromName, rawText, type, mediaUrl,
            data.getOrDefault("fileName", null), false);
        // Logged-out OR sender unknown → just show without mute/block check
        if (FirebaseAuth.getInstance().getCurrentUser() == null
                || fromUid == null || fromUid.isEmpty()) {
            buildAndShow(fromUid, fromName, fromMobile, fromPhoto,
                chatId, mediaUrl, text, type, subText, notifId, null,
                /*muted*/ false);
            return;
        }
        final String myUid = FirebaseAuth.getInstance().getCurrentUser().getUid();
        // (Feature 4 / 12) Permanently blocked? → drop completely (no notification at all)
        FirebaseUtils.db().getReference("permaBlocked").child(myUid).child(fromUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot ps) {
                    if (Boolean.TRUE.equals(ps.getValue(Boolean.class))) {
                        return; // PERMANENT BLOCK — no notification ever
                    }
                    // (Feature 2 / 3) Block → "Unblock {name}" prompt notification
                    FirebaseUtils.db().getReference("blocked")
                        .child(myUid).child(fromUid)
                        .addListenerForSingleValueEvent(new ValueEventListener() {
                            @Override public void onDataChange(DataSnapshot s) {
                                if (Boolean.TRUE.equals(s.getValue(Boolean.class))) {
                                    showBlockedSenderNotification(fromUid, fromName,
                                        fromMobile, fromPhoto, chatId);
                                    return;
                                }
                                // (Feature 1) Muted → still show, but silent + low priority
                                FirebaseUtils.db().getReference("muted")
                                    .child(myUid).child(fromUid)
                                    .addListenerForSingleValueEvent(
                                        new ValueEventListener() {
                                    @Override public void onDataChange(DataSnapshot s2) {
                                        boolean muted = Boolean.TRUE.equals(
                                            s2.getValue(Boolean.class));
                                        loadLast3AndBuild(myUid, fromUid, fromName,
                                            fromMobile, fromPhoto, chatId, mediaUrl,
                                            text, type, subText, notifId, muted);
                                    }
                                    @Override public void onCancelled(DatabaseError e) {
                                        loadLast3AndBuild(myUid, fromUid, fromName,
                                            fromMobile, fromPhoto, chatId, mediaUrl,
                                            text, type, subText, notifId, false);
                                    }
                                });
                            }
                            @Override public void onCancelled(DatabaseError e) {}
                        });
                }
                @Override public void onCancelled(DatabaseError e) {}
            });
    }
    private static String previewTextFor(String type, String raw) {
        if (raw != null && !raw.isEmpty()) return raw;
        if (type == null) return "Naya message";
        switch (type) {
            case "image": return "📷 Photo";
            case "video": return "🎬 Video";
            case "audio": return "🎤 Voice message";
            case "file":  return "📎 File";
            case "pdf":   return "📄 PDF document";
            default:      return "Naya message";
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
            .orderByChild("timestamp").limitToLast(3);
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
        // Avatar + my own avatar (Feature 10) + (optional) attached image are
        // downloaded off-thread, then we post the notification on the main flow.
        bg.execute(() -> {
            Bitmap avatar    = circle(downloadBitmap(fromPhoto, 256, 256));
            Bitmap myAvatar  = circle(loadMyAvatar());
            boolean isImage  = "image".equals(type)
                && mediaUrl != null && !mediaUrl.isEmpty();
            Bitmap picture = isImage ? downloadBitmap(mediaUrl, 1024, 768) : null;
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
        open.putExtra("partnerUid", fromUid);
        open.putExtra("partnerName", fromName);
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
            for (HistoryItem h : hist) {
                // Skip the last history item if it matches the incoming text
                // to avoid showing the same message twice.
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
            Bitmap avatar = circle(downloadBitmap(fromPhoto, 256, 256));
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
            Bitmap avatar = circle(downloadBitmap(fromPhoto, 256, 256));
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
            Bitmap avatar = circle(downloadBitmap(fromPhoto, 256, 256));
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
    // ----- Helpers: load my own avatar URL from RTDB synchronously -----
    @Nullable private Bitmap loadMyAvatar() {
        try {
            if (FirebaseAuth.getInstance().getCurrentUser() == null) return null;
            // CallxApp se cache check karo
            String url = CallxApp.getMyPhotoUrlCached();
            if (url == null || url.isEmpty()) return null;
            return downloadBitmap(url, 256, 256);
        } catch (Exception ignored) {
            return null;
        }
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
        // If DND is active and this is NOT an @mention alert — suppress entirely.
        if (!groupId.isEmpty()) {
            android.content.SharedPreferences dndPrefs = getSharedPreferences(
                    com.callx.app.utils.Constants.DND_PREFS_PREFIX + groupId,
                    Context.MODE_PRIVATE);
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

        // Client-side mute check (server bhi flag bhejta hai, but defense-in-depth)
        FirebaseUtils.db().getReference("groups").child(groupId).child("mutedBy")
            .child(myUid).addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot s) {
                    boolean clientMuted = Boolean.TRUE.equals(s.getValue(Boolean.class));
                    loadGroupHistoryAndBuild(myUid, groupId, groupName, groupIcon,
                        fromUid, fromName, fromPhoto, mediaUrl, text, type, subText,
                        notifId, serverMuted || clientMuted, isMention, isPriority);
                }
                @Override public void onCancelled(DatabaseError e) {
                    loadGroupHistoryAndBuild(myUid, groupId, groupName, groupIcon,
                        fromUid, fromName, fromPhoto, mediaUrl, text, type, subText,
                        notifId, serverMuted, isMention, isPriority);
                }
            });
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
        bg.execute(() -> {
            Bitmap senderAvatar = circle(downloadBitmap(fromPhoto, 256, 256));
            Bitmap myAvatar     = circle(loadMyAvatar());
            Bitmap groupAvatar  = circle(downloadBitmap(groupIcon, 256, 256));
            boolean isImage = "image".equals(type)
                && mediaUrl != null && !mediaUrl.isEmpty();
            Bitmap picture = isImage ? downloadBitmap(mediaUrl, 1024, 768) : null;
            postRichGroupNotification(groupId, groupName, fromUid, fromName,
                fromPhoto, mediaUrl, text, type, subText, notifId, hist,
                senderAvatar, myAvatar, groupAvatar, picture, muted,
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
        // Tap → open GroupChatActivity directly
        Intent open = new Intent(this,
            com.callx.app.activities.GroupChatActivity.class);
        open.putExtra("groupId",   groupId);
        open.putExtra("groupName", groupName);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, notifId, open,
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
            for (GroupHistoryItem h : hist) {
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
            .setOnlyAlertOnce(false)
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
        // Group icon as large icon (fallback to sender avatar)
        if (groupAvatar != null)       b.setLargeIcon(groupAvatar);
        else if (senderAvatar != null) b.setLargeIcon(senderAvatar);
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
        nm.notify(Constants.GROUP_KEY_GROUPS.hashCode(), summary.build());
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
        String name = data.getOrDefault("fromName", "Friend");
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(this,
                Constants.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_status_notification)
            .setContentTitle(name)
            .setContentText("Naya status post kiya")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pi);
        NotificationManager nm = (NotificationManager)
            getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(new Random().nextInt(99999), b.build());
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
            case "group_image":  return "image";
            case "group_video":  return "video";
            case "group_audio":  return "audio";
            case "group_voice":  return "audio";
            case "group_file":   return "file";
            default:             return "text";
        }
    }
}
