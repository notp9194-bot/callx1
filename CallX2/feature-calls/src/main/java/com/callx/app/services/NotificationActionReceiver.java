package com.callx.app.services;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import androidx.core.app.RemoteInput;
import androidx.work.Data;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import com.callx.app.utils.Constants;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.PushNotify;
import com.callx.app.db.AppDatabase;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ServerValue;
import java.util.HashMap;
import java.util.Map;
public class NotificationActionReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        // Fix 3: goAsync() — OS ko batao ki background kaam chal raha hai
        // Bina iske killed state mein receiver finish hote hi Thread/Firebase drop ho jaata hai
        final PendingResult pendingResult = goAsync();

        String action = intent.getAction();
        if (action == null) { pendingResult.finish(); return; }
        String chatId       = intent.getStringExtra(Constants.EXTRA_CHAT_ID);
        String partnerUid   = intent.getStringExtra(Constants.EXTRA_PARTNER_UID);
        String partnerName  = intent.getStringExtra(Constants.EXTRA_PARTNER_NAME);
        String partnerPhoto = intent.getStringExtra(Constants.EXTRA_PARTNER_PHOTO);
        int notifId         = intent.getIntExtra(Constants.EXTRA_NOTIF_ID, 0);
        if (FirebaseAuth.getInstance().getCurrentUser() == null) { pendingResult.finish(); return; }
        final String myUid  = FirebaseAuth.getInstance().getCurrentUser().getUid();
        final String myName = FirebaseUtils.getCurrentName();
        NotificationManager nm = (NotificationManager)
            context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Constants.ACTION_MARK_READ.equals(action)) {
            if (partnerUid != null) {
                FirebaseUtils.getContactsRef(myUid).child(partnerUid)
                    .child("unread").setValue(0);
            }
            if (nm != null) nm.cancel(notifId);
            pendingResult.finish();
            return;
        }
        // (Feature 1) Mute — sound off. Future notifications silent show honge.
        if (Constants.ACTION_MUTE.equals(action)) {
            if (partnerUid != null) {
                FirebaseUtils.db().getReference("muted")
                    .child(myUid).child(partnerUid).setValue(true);
            }
            if (nm != null) nm.cancel(notifId);
            // Fix 4: Toast removed — background/killed state mein crash karta hai
            pendingResult.finish();
            return;
        }
        // (Feature 2) Block — future me real notification ki jagah
        // "Unblock {sender}" prompt aayega.
        if (Constants.ACTION_BLOCK.equals(action)) {
            if (partnerUid != null) {
                FirebaseUtils.db().getReference("blocked")
                    .child(myUid).child(partnerUid).setValue(true);
            }
            if (nm != null) nm.cancel(notifId);
            // Fix 4: Toast removed
            pendingResult.finish();
            return;
        }
        // (Feature 3) Unblock — block hata do, agla message normally aayega
        if (Constants.ACTION_UNBLOCK.equals(action)) {
            if (partnerUid != null) {
                FirebaseUtils.db().getReference("blocked")
                    .child(myUid).child(partnerUid).removeValue();
            }
            if (nm != null) nm.cancel(notifId);
            // Fix 4: Toast removed
            pendingResult.finish();
            return;
        }
          
          // ── Snooze actions (1h / 8h / 24h) ──────────────────────────────────
          if (Constants.ACTION_SNOOZE_1H.equals(action)  ||
              Constants.ACTION_SNOOZE_8H.equals(action)  ||
              Constants.ACTION_SNOOZE_24H.equals(action)) {
              if (nm != null) nm.cancel(notifId);
              long delay;
              if (Constants.ACTION_SNOOZE_1H.equals(action))  delay = 60 * 60 * 1000L;
              else if (Constants.ACTION_SNOOZE_8H.equals(action)) delay = 8 * 60 * 60 * 1000L;
              else delay = 24 * 60 * 60 * 1000L;
              try {
                  com.callx.app.services.NotificationSnoozeReceiver.snoozePi(
                      context, action, notifId,
                      chatId, partnerName, partnerUid,
                      intent.getStringExtra("snooze_message")).send();
              } catch (android.app.PendingIntent.CanceledException e) {
                  android.util.Log.w("NotificationActionReceiver", "Snooze PendingIntent cancelled", e);
              }
              // Re-schedule via AlarmManager
              android.app.AlarmManager am = (android.app.AlarmManager)
                  context.getSystemService(android.content.Context.ALARM_SERVICE);
              if (am != null) {
                  Intent fireIntent = new Intent(context,
                      com.callx.app.services.NotificationSnoozeReceiver.class)
                      .setAction(com.callx.app.services.NotificationSnoozeReceiver.ACTION_SNOOZE_FIRE)
                      .putExtra(com.callx.app.services.NotificationSnoozeReceiver.EXTRA_NOTIF_ID, notifId)
                      .putExtra(com.callx.app.services.NotificationSnoozeReceiver.EXTRA_CHAT_ID, chatId)
                      .putExtra(com.callx.app.services.NotificationSnoozeReceiver.EXTRA_PARTNER_NAME, partnerName)
                      .putExtra(com.callx.app.services.NotificationSnoozeReceiver.EXTRA_PARTNER_UID, partnerUid)
                      .putExtra(com.callx.app.services.NotificationSnoozeReceiver.EXTRA_MESSAGE,
                          intent.getStringExtra("snooze_message"));
                  android.app.PendingIntent firePi = android.app.PendingIntent.getBroadcast(
                      context, notifId, fireIntent,
                      android.app.PendingIntent.FLAG_UPDATE_CURRENT |
                      android.app.PendingIntent.FLAG_IMMUTABLE);
                  if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M)
                      am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP,
                          System.currentTimeMillis() + delay, firePi);
                  else am.setExact(android.app.AlarmManager.RTC_WAKEUP,
                          System.currentTimeMillis() + delay, firePi);
              }
              // Fix 4: Toast removed — background mein crash karta hai
              android.util.Log.d("NotificationActionReceiver", "Snoozed");
              pendingResult.finish();
              return;
          }

          // ── Call back (missed call) ────────────────────────────────────────
          if (Constants.ACTION_CALL_BACK.equals(action)) {
              if (nm != null) nm.cancel(notifId);
              if (partnerUid != null && !partnerUid.isEmpty()) {
                  // FIX-3: use actual call type from missed call payload (was hardcoded false)
                  boolean cbIsVideo = intent.getBooleanExtra(Constants.EXTRA_IS_VIDEO, false);
                  String  cbPhoto   = intent.getStringExtra(Constants.EXTRA_PARTNER_PHOTO);
                  Intent callIntent = new Intent(context,
                      com.callx.app.activities.CallActivity.class);
                  callIntent.putExtra(Constants.EXTRA_PARTNER_UID,  partnerUid);
                  callIntent.putExtra(Constants.EXTRA_PARTNER_NAME, partnerName);
                  if (cbPhoto != null) callIntent.putExtra("partnerPhoto", cbPhoto);
                  callIntent.putExtra(Constants.EXTRA_IS_VIDEO, cbIsVideo); // FIX-3
                  callIntent.putExtra("isCaller", true);
                  callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                      Intent.FLAG_ACTIVITY_CLEAR_TOP);
                  context.startActivity(callIntent);
              }
              pendingResult.finish();
              return;
          }

          // ── Leave Note (missed call) — open AddNoteActivity ───────────────
          if (Constants.ACTION_LEAVE_NOTE.equals(action)) {
              if (nm != null) nm.cancel(notifId);
              if (partnerUid != null && !partnerUid.isEmpty()) {
                  boolean noteIsVideo = intent.getBooleanExtra(Constants.EXTRA_IS_VIDEO, false);
                  String  notePhoto   = intent.getStringExtra(Constants.EXTRA_PARTNER_PHOTO);
                  String  noteChatId  = intent.getStringExtra(Constants.EXTRA_CHAT_ID);
                  Intent noteIntent = new Intent(context,
                      com.callx.app.activities.AddNoteActivity.class);
                  noteIntent.putExtra(com.callx.app.activities.AddNoteActivity.EXTRA_PARTNER_UID,   partnerUid);
                  noteIntent.putExtra(com.callx.app.activities.AddNoteActivity.EXTRA_PARTNER_NAME,  partnerName != null ? partnerName : "");
                  noteIntent.putExtra(com.callx.app.activities.AddNoteActivity.EXTRA_PARTNER_PHOTO, notePhoto != null ? notePhoto : "");
                  noteIntent.putExtra(com.callx.app.activities.AddNoteActivity.EXTRA_CHAT_ID,       noteChatId != null ? noteChatId : "");
                  noteIntent.putExtra(com.callx.app.activities.AddNoteActivity.EXTRA_IS_VIDEO,      noteIsVideo);
                  noteIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                  context.startActivity(noteIntent);
              }
              pendingResult.finish();
              return;
          }
  
          // ── Call actions ────────────────────────────────────────────────
          String callId = intent.getStringExtra(Constants.EXTRA_CALL_ID);
          boolean isVid = intent.getBooleanExtra(Constants.EXTRA_IS_VIDEO, false);
          // Decline call — reject without opening app
          if (Constants.ACTION_DECLINE_CALL.equals(action)) {
              if (nm != null) nm.cancel(Constants.CALL_RING_NOTIF_ID);
              context.stopService(new android.content.Intent(context,
                  com.callx.app.services.IncomingRingService.class));
              if (callId != null && !callId.isEmpty()) {
                  com.callx.app.utils.FirebaseUtils.db()
                      .getReference("activeCalls").child(callId)
                      .child("status").setValue("rejected");
              }
              // FIX-2: Caller ko missed call notification bhejo
              // partnerUid here = the CALLER (who rang us), myUid = us (who declined)
              if (partnerUid != null && !partnerUid.isEmpty()) {
                  PushNotify.notifyMissedCall(
                      partnerUid,  // caller ko bhejo
                      myUid,       // hum hai receiver (missed by us = caller ke liye missed)
                      myName != null ? myName : "",
                      callId != null ? callId : "",
                      isVid
                  );
              }
              pendingResult.finish();
              return;
          }
          // Accept call — dismiss ring notification, open IncomingCallActivity
          if (Constants.ACTION_ACCEPT_CALL.equals(action)) {
              if (nm != null) nm.cancel(Constants.CALL_RING_NOTIF_ID);
              context.stopService(new android.content.Intent(context,
                  com.callx.app.services.IncomingRingService.class));
              android.content.Intent open = new android.content.Intent(context,
                  com.callx.app.activities.IncomingCallActivity.class);
              open.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                  | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);
              open.putExtra(Constants.EXTRA_CALL_ID,       callId);
              open.putExtra(Constants.EXTRA_PARTNER_UID,   partnerUid);
              open.putExtra(Constants.EXTRA_PARTNER_NAME,  partnerName);
              open.putExtra(Constants.EXTRA_PARTNER_PHOTO, partnerPhoto); // BUG-1 FIX: photo pass karo
              // BUG-1 FIX: partnerThumb bhi pass karo (fast avatar for CallActivity)
              String acceptThumb = intent.getStringExtra("partnerThumb");
              if (acceptThumb != null) open.putExtra("partnerThumb", acceptThumb);
              open.putExtra(Constants.EXTRA_IS_VIDEO,      isVid);
              context.startActivity(open);
              pendingResult.finish();
              return;
          }
          // End call — hang up from notification shade during active call
          if (Constants.ACTION_END_CALL.equals(action)) {
              if (nm != null) nm.cancel(Constants.CALL_ONGOING_NOTIF_ID);
              context.stopService(new android.content.Intent(context,
                  com.callx.app.services.CallForegroundService.class));
              if (callId != null && !callId.isEmpty()) {
                  com.callx.app.utils.FirebaseUtils.db()
                      .getReference("activeCalls").child(callId)
                      .child("status").setValue("ended");
              }
              pendingResult.finish();
              return;
          }
        // (Feature 4 / 12) Permanent block — sender ka koi notification nahi.
        // Sender ko ek baar return notification jaata hai.
        if (Constants.ACTION_PERMA_BLOCK.equals(action)) {
            if (partnerUid != null) {
                FirebaseUtils.db().getReference("permaBlocked")
                    .child(myUid).child(partnerUid).setValue(true);
                FirebaseUtils.db().getReference("blocked")
                    .child(myUid).child(partnerUid).setValue(true);
                PushNotify.notifyPermaBlock(partnerUid, myUid, myName);
            }
            if (nm != null) nm.cancel(notifId);
            // Fix 4: Toast removed — background mein crash karta hai
            pendingResult.finish();
            return;
        }
        if (Constants.ACTION_SPECIAL_UNBLOCK.equals(action)) {
            if (partnerUid != null) {
                FirebaseUtils.db().getReference("blocked")
                    .child(myUid).child(partnerUid).removeValue();
                FirebaseUtils.db().getReference("permaBlocked")
                    .child(myUid).child(partnerUid).removeValue();
                FirebaseUtils.db().getReference("specialRequests")
                    .child(myUid).child(partnerUid).removeValue();
            }
            if (nm != null) nm.cancel(notifId);
            // Fix 4: Toast removed
            pendingResult.finish();
            return;
        }
        if (Constants.ACTION_REPLY.equals(action)) {
            Bundle remote = RemoteInput.getResultsFromIntent(intent);
            if (remote == null) { pendingResult.finish(); return; }
            CharSequence reply = remote.getCharSequence(Constants.KEY_TEXT_REPLY);
            if (reply == null) { pendingResult.finish(); return; }
            String text = reply.toString().trim();
            if (text.isEmpty() || chatId == null || partnerUid == null) { pendingResult.finish(); return; }

            // Fix 3: WorkManager use karo — killed state mein bhi guaranteed run karega
            // new Thread() OS kill kar deta tha jab receiver finish hota tha
            Data inputData = new Data.Builder()
                .putString("myUid",      myUid)
                .putString("myName",     myName)
                .putString("chatId",     chatId)
                .putString("partnerUid", partnerUid)
                .putString("text",       text)
                .putInt("notifId",       notifId)
                .putBoolean("isGroup",   false)
                .build();
            OneTimeWorkRequest replyWork = new OneTimeWorkRequest.Builder(
                    com.callx.app.workers.NotificationReplyWorker.class)
                .setInputData(inputData)
                .build();
            WorkManager.getInstance(context.getApplicationContext()).enqueue(replyWork);

            if (nm != null) nm.cancel(notifId);
            pendingResult.finish();
            return;
        }

        // ==================== GROUP NOTIFICATION ACTIONS ====================
        String groupId   = intent.getStringExtra(Constants.EXTRA_GROUP_ID);
        String groupName = intent.getStringExtra(Constants.EXTRA_GROUP_NAME);

        // Group: Mark as read — local notification cancel + server unread reset
        if (Constants.ACTION_GROUP_MARK_READ.equals(action)) {
            if (groupId != null && !groupId.isEmpty()) {
                FirebaseUtils.db().getReference("groups")
                    .child(groupId).child("unread").child(myUid).setValue(0);
            }
            if (nm != null) nm.cancel(notifId);
            pendingResult.finish();
            return;
        }
        // Group: Mute
        if (Constants.ACTION_GROUP_MUTE.equals(action)) {
            if (groupId != null && !groupId.isEmpty()) {
                FirebaseUtils.db().getReference("groups")
                    .child(groupId).child("mutedBy").child(myUid).setValue(true);
            }
            if (nm != null) nm.cancel(notifId);
            // Fix 4: Toast removed
            pendingResult.finish();
            return;
        }
        // Group: Unmute
        if (Constants.ACTION_GROUP_UNMUTE.equals(action)) {
            if (groupId != null && !groupId.isEmpty()) {
                FirebaseUtils.db().getReference("groups")
                    .child(groupId).child("mutedBy").child(myUid).removeValue();
            }
            if (nm != null) nm.cancel(notifId);
            // Fix 4: Toast removed
            pendingResult.finish();
            return;
        }
        // Group: Leave
        if (Constants.ACTION_GROUP_LEAVE.equals(action)) {
            if (groupId != null && !groupId.isEmpty()) {
                Map<String, Object> upd = new HashMap<>();
                upd.put("groups/" + groupId + "/members/" + myUid, null);
                upd.put("groups/" + groupId + "/admins/"  + myUid, null);
                upd.put("groups/" + groupId + "/mutedBy/" + myUid, null);
                upd.put("groups/" + groupId + "/unread/"  + myUid, null);
                upd.put("userGroups/" + myUid + "/" + groupId, null);
                FirebaseUtils.db().getReference().updateChildren(upd);
                DatabaseReference sysRef = FirebaseUtils
                    .getGroupMessagesRef(groupId).push();
                Map<String, Object> sys = new HashMap<>();
                sys.put("id",         sysRef.getKey());
                sys.put("senderId",   "system");
                sys.put("senderName", "System");
                sys.put("text",       myName + " left the group");
                sys.put("type",       "system");
                sys.put("timestamp",  System.currentTimeMillis());
                sysRef.setValue(sys);
            }
            if (nm != null) nm.cancel(notifId);
            // Fix 4: Toast removed
            pendingResult.finish();
            return;
        }
        // Group: Inline reply — Fix 8: WorkManager use karo (same as 1-1 reply Fix 3)
        // Fix 2 + 11: groupName bhi pass karo taaki reply ke baad GroupChatActivity khul sake
        if (Constants.ACTION_GROUP_REPLY.equals(action)) {
            Bundle remote = RemoteInput.getResultsFromIntent(intent);
            if (remote == null) { pendingResult.finish(); return; }
            CharSequence reply = remote.getCharSequence(Constants.KEY_GROUP_TEXT_REPLY);
            if (reply == null) { pendingResult.finish(); return; }
            String text = reply.toString().trim();
            if (text.isEmpty() || groupId == null || groupId.isEmpty()) { pendingResult.finish(); return; }

            // Fix 11: groupName bhi include karo (pehle missing tha)
            Data inputData = new Data.Builder()
                .putString("myUid",     myUid)
                .putString("myName",    myName)
                .putString("chatId",    groupId)
                .putString("groupName", groupName != null ? groupName : "")
                .putString("text",      text)
                .putInt("notifId",      notifId)
                .putBoolean("isGroup",  true)
                .build();
            OneTimeWorkRequest replyWork = new OneTimeWorkRequest.Builder(
                    com.callx.app.workers.NotificationReplyWorker.class)
                .setInputData(inputData)
                .build();
            WorkManager.getInstance(context.getApplicationContext()).enqueue(replyWork);

            // Fix 2: Reply ke baad GroupChatActivity kholo
            // Note: feature-calls module mein GroupChatActivity class visible nahi —
            // string-based Intent use karo (no cross-module dependency needed)
            Intent openGroup = new Intent();
            openGroup.setClassName(context, "com.callx.app.activities.GroupChatActivity");
            openGroup.putExtra("groupId",    groupId);
            openGroup.putExtra("groupName",  groupName != null ? groupName : "");
            openGroup.putExtra("groupPhoto", "");
            openGroup.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            try { context.startActivity(openGroup); } catch (Exception ignored) {}

            if (nm != null) nm.cancel(notifId);
            pendingResult.finish();
        }
    }
}
