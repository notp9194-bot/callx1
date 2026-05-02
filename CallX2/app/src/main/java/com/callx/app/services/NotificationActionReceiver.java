package com.callx.app.services;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;
import androidx.core.app.RemoteInput;
import com.callx.app.utils.Constants;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.PushNotify;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ServerValue;
import java.util.HashMap;
import java.util.Map;
public class NotificationActionReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;
        String chatId       = intent.getStringExtra(Constants.EXTRA_CHAT_ID);
        String partnerUid   = intent.getStringExtra(Constants.EXTRA_PARTNER_UID);
        String partnerName  = intent.getStringExtra(Constants.EXTRA_PARTNER_NAME);
        String partnerPhoto = intent.getStringExtra(Constants.EXTRA_PARTNER_PHOTO);
        int notifId         = intent.getIntExtra(Constants.EXTRA_NOTIF_ID, 0);
        if (FirebaseAuth.getInstance().getCurrentUser() == null) return;
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
            return;
        }
        // (Feature 1) Mute — sound off. Future notifications silent show honge.
        if (Constants.ACTION_MUTE.equals(action)) {
            if (partnerUid != null) {
                FirebaseUtils.db().getReference("muted")
                    .child(myUid).child(partnerUid).setValue(true);
            }
            if (nm != null) nm.cancel(notifId);
            Toast.makeText(context, "Muted", Toast.LENGTH_SHORT).show();
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
            Toast.makeText(context, "Blocked. Aagle message pe Unblock prompt aayega.",
                Toast.LENGTH_SHORT).show();
            return;
        }
        // (Feature 3) Unblock — block hata do, agla message normally aayega
        if (Constants.ACTION_UNBLOCK.equals(action)) {
            if (partnerUid != null) {
                FirebaseUtils.db().getReference("blocked")
                    .child(myUid).child(partnerUid).removeValue();
            }
            if (nm != null) nm.cancel(notifId);
            Toast.makeText(context, "Unblocked", Toast.LENGTH_SHORT).show();
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
              open.putExtra(Constants.EXTRA_CALL_ID,      callId);
              open.putExtra(Constants.EXTRA_PARTNER_UID,  partnerUid);
              open.putExtra(Constants.EXTRA_PARTNER_NAME, partnerName);
              open.putExtra(Constants.EXTRA_IS_VIDEO,     isVid);
              context.startActivity(open);
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
                // Receiver details ke saath sender ko ek baar notify karo
                PushNotify.notifyPermaBlock(partnerUid, myUid, myName);
            }
            if (nm != null) nm.cancel(notifId);
            Toast.makeText(context, "Permanently blocked", Toast.LENGTH_SHORT).show();
            return;
        }
        // (Feature 16) Special-request notification ka "Please unblock me" button
        if (Constants.ACTION_SPECIAL_UNBLOCK.equals(action)) {
            if (partnerUid != null) {
                // Receiver = me. Sender (partnerUid) ko unblock karo.
                FirebaseUtils.db().getReference("blocked")
                    .child(myUid).child(partnerUid).removeValue();
                FirebaseUtils.db().getReference("permaBlocked")
                    .child(myUid).child(partnerUid).removeValue();
                // Special request entry bhi remove
                FirebaseUtils.db().getReference("specialRequests")
                    .child(myUid).child(partnerUid).removeValue();
            }
            if (nm != null) nm.cancel(notifId);
            Toast.makeText(context, "Unblocked", Toast.LENGTH_SHORT).show();
            return;
        }
        if (Constants.ACTION_REPLY.equals(action)) {
            Bundle remote = RemoteInput.getResultsFromIntent(intent);
            if (remote == null) return;
            CharSequence reply = remote.getCharSequence(Constants.KEY_TEXT_REPLY);
            if (reply == null) return;
            String text = reply.toString().trim();
            if (text.isEmpty() || chatId == null || partnerUid == null) return;
            DatabaseReference msgRef = FirebaseUtils.getMessagesRef(chatId).push();
            Map<String, Object> m = new HashMap<>();
            m.put("id",         msgRef.getKey());
            m.put("senderId",   myUid);
            m.put("senderName", myName);
            m.put("text",       text);
            m.put("type",       "text");
            m.put("timestamp",  System.currentTimeMillis());
            msgRef.setValue(m);
            // Update last message + unread counters (mine reset, partner +1)
            Map<String, Object> meSide = new HashMap<>();
            meSide.put("lastMessage",   text);
            meSide.put("lastMessageAt", System.currentTimeMillis());
            meSide.put("unread",        0);
            FirebaseUtils.getContactsRef(myUid).child(partnerUid)
                .updateChildren(meSide);
            Map<String, Object> partnerSide = new HashMap<>();
            partnerSide.put("lastMessage",   text);
            partnerSide.put("lastMessageAt", System.currentTimeMillis());
            partnerSide.put("unread",        ServerValue.increment(1));
            FirebaseUtils.getContactsRef(partnerUid).child(myUid)
                .updateChildren(partnerSide);
            // Push to partner
            PushNotify.notifyMessage(partnerUid, myUid, myName, chatId,
                msgRef.getKey(), text, "message", "");
            if (nm != null) nm.cancel(notifId);
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
            return;
        }
        // Group: Mute — mutedBy/{uid}=true. Future me silent low channel.
        if (Constants.ACTION_GROUP_MUTE.equals(action)) {
            if (groupId != null && !groupId.isEmpty()) {
                FirebaseUtils.db().getReference("groups")
                    .child(groupId).child("mutedBy").child(myUid).setValue(true);
            }
            if (nm != null) nm.cancel(notifId);
            Toast.makeText(context, "Group muted", Toast.LENGTH_SHORT).show();
            return;
        }
        // Group: Unmute
        if (Constants.ACTION_GROUP_UNMUTE.equals(action)) {
            if (groupId != null && !groupId.isEmpty()) {
                FirebaseUtils.db().getReference("groups")
                    .child(groupId).child("mutedBy").child(myUid).removeValue();
            }
            if (nm != null) nm.cancel(notifId);
            Toast.makeText(context, "Group unmuted", Toast.LENGTH_SHORT).show();
            return;
        }
        // Group: Leave — apne aap ko members + index dono se remove karo
        if (Constants.ACTION_GROUP_LEAVE.equals(action)) {
            if (groupId != null && !groupId.isEmpty()) {
                Map<String, Object> upd = new HashMap<>();
                upd.put("groups/" + groupId + "/members/" + myUid, null);
                upd.put("groups/" + groupId + "/admins/"  + myUid, null);
                upd.put("groups/" + groupId + "/mutedBy/" + myUid, null);
                upd.put("groups/" + groupId + "/unread/"  + myUid, null);
                upd.put("userGroups/" + myUid + "/" + groupId, null);
                FirebaseUtils.db().getReference().updateChildren(upd);
                // System message group me daalo
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
            Toast.makeText(context, "Left group", Toast.LENGTH_SHORT).show();
            return;
        }
        // Group: Inline reply — text msg push to group + fanout to other members
        if (Constants.ACTION_GROUP_REPLY.equals(action)) {
            Bundle remote = RemoteInput.getResultsFromIntent(intent);
            if (remote == null) return;
            CharSequence reply = remote.getCharSequence(
                Constants.KEY_GROUP_TEXT_REPLY);
            if (reply == null) return;
            String text = reply.toString().trim();
            if (text.isEmpty() || groupId == null || groupId.isEmpty()) return;
            DatabaseReference msgRef = FirebaseUtils
                .getGroupMessagesRef(groupId).push();
            Map<String, Object> m = new HashMap<>();
            m.put("id",         msgRef.getKey());
            m.put("senderId",   myUid);
            m.put("senderName", myName);
            m.put("text",       text);
            m.put("type",       "text");
            m.put("timestamp",  System.currentTimeMillis());
            msgRef.setValue(m);
            // Group meta update
            Map<String, Object> meta = new HashMap<>();
            meta.put("lastMessage",     text);
            meta.put("lastSenderName",  myName);
            meta.put("lastMessageAt",   System.currentTimeMillis());
            // Mera apna unread reset
            meta.put("unread/" + myUid, 0);
            FirebaseUtils.db().getReference("groups").child(groupId)
                .updateChildren(meta);
            // Server fanout (rich)
            String myPhoto = com.callx.app.CallxApp.getMyPhotoUrlCached();
            PushNotify.notifyGroupRich(groupId, myUid, myName,
                myPhoto == null ? "" : myPhoto,
                msgRef.getKey(), "group_message", text, "");
            if (nm != null) nm.cancel(notifId);
        }
    }
}
