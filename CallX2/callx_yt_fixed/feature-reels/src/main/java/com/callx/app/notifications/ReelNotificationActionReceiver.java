package com.callx.app.notifications;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;
import androidx.core.app.RemoteInput;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.auth.FirebaseAuth;

/**
 * ReelNotificationActionReceiver — Handles all reel notification action buttons.
 *
 * Actions handled (all work when app is killed):
 *  ✅ LIKE_REEL — like reel directly from notification
 *  ✅ REEL_COMMENT_REPLY — inline reply to a reel comment
 *  ✅ LIKE_COMMENT — like a comment from notification
 *  ✅ FOLLOW_BACK — follow a new follower back
 *  ✅ COLLAB_ACCEPT — accept a collab invitation
 *  ✅ COLLAB_DECLINE — decline a collab invitation
 *  ✅ MARK_MENTIONS_READ — mark all mentions as read
 *  ✅ DISMISS_REEL_NOTIF — dismiss + mark read in Firebase
 */
public class ReelNotificationActionReceiver extends BroadcastReceiver {

    public static final String ACTION_LIKE_REEL          = "com.callx.app.ACTION_LIKE_REEL";
    public static final String ACTION_REEL_COMMENT_REPLY = "com.callx.app.ACTION_REEL_COMMENT_REPLY";
    public static final String ACTION_LIKE_COMMENT        = "com.callx.app.ACTION_LIKE_COMMENT";
    public static final String ACTION_FOLLOW_BACK         = "com.callx.app.ACTION_FOLLOW_BACK";
    public static final String ACTION_COLLAB_ACCEPT       = "com.callx.app.ACTION_COLLAB_ACCEPT";
    public static final String ACTION_COLLAB_DECLINE      = "com.callx.app.ACTION_COLLAB_DECLINE";
    public static final String ACTION_MARK_MENTIONS_READ  = "com.callx.app.ACTION_MARK_MENTIONS_READ";
    public static final String ACTION_DISMISS_REEL_NOTIF  = "com.callx.app.ACTION_DISMISS_REEL_NOTIF";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;

        String reelId  = intent.getStringExtra("reel_id");
        String extra   = intent.getStringExtra("extra");
        int    notifId = intent.getIntExtra("notif_id", 0);

        FirebaseAuth auth = FirebaseAuth.getInstance();
        if (auth.getCurrentUser() == null) return;
        String myUid = auth.getCurrentUser().getUid();

        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);

        // ── Like Reel ──────────────────────────────────────────────────
        if (ACTION_LIKE_REEL.equals(action) && reelId != null) {
            FirebaseUtils.getReelLikesRef(reelId).child(myUid).setValue(true);
            FirebaseUtils.getReelsRef().child(reelId).child("likeCount")
                .setValue(com.google.firebase.database.ServerValue.increment(1));
            if (nm != null) nm.cancel(notifId);
            Toast.makeText(ctx, "❤️ Reel liked!", Toast.LENGTH_SHORT).show();
            return;
        }

        // ── Inline Comment Reply ───────────────────────────────────────
        if (ACTION_REEL_COMMENT_REPLY.equals(action) && reelId != null) {
            Bundle results = RemoteInput.getResultsFromIntent(intent);
            if (results == null) return;
            CharSequence replyText = results.getCharSequence(ReelNotificationHelper.KEY_REPLY_TEXT);
            if (replyText == null || replyText.toString().trim().isEmpty()) return;
            String text = replyText.toString().trim();
            String myName = FirebaseUtils.getCurrentName();

            com.google.firebase.database.DatabaseReference commentRef =
                com.callx.app.utils.FirebaseUtils.db()
                    .getReference("reelComments").child(reelId).push();
            java.util.Map<String, Object> m = new java.util.HashMap<>();
            m.put("text",        text);
            m.put("authorUid",   myUid);
            m.put("authorName",  myName);
            m.put("timestamp",   System.currentTimeMillis());
            m.put("parentId",    extra != null ? extra : "");
            commentRef.setValue(m);
            FirebaseUtils.getReelsRef().child(reelId).child("commentCount")
                .setValue(com.google.firebase.database.ServerValue.increment(1));
            if (nm != null) nm.cancel(notifId);
            return;
        }

        // ── Like Comment ───────────────────────────────────────────────
        if (ACTION_LIKE_COMMENT.equals(action) && reelId != null && extra != null) {
            FirebaseUtils.db().getReference("reelComments").child(reelId)
                .child(extra).child("likes").child(myUid).setValue(true);
            if (nm != null) nm.cancel(notifId);
            Toast.makeText(ctx, "❤️ Comment liked!", Toast.LENGTH_SHORT).show();
            return;
        }

        // ── Follow Back ────────────────────────────────────────────────
        if (ACTION_FOLLOW_BACK.equals(action) && reelId != null) {
            // reelId here holds followerUid
            String followerUid = reelId;
            java.util.Map<String, Object> upd = new java.util.HashMap<>();
            upd.put("reelFollows/"   + myUid        + "/" + followerUid, true);
            upd.put("reelFollowers/" + followerUid   + "/" + myUid,       true);
            FirebaseUtils.db().getReference().updateChildren(upd);
            if (nm != null) nm.cancel(notifId);
            Toast.makeText(ctx, "✓ Following back!", Toast.LENGTH_SHORT).show();
            return;
        }

        // ── Collab Accept ──────────────────────────────────────────────
        if (ACTION_COLLAB_ACCEPT.equals(action) && extra != null) {
            FirebaseUtils.db().getReference("reelCollabs").child(extra)
                .child("status").setValue("accepted");
            FirebaseUtils.db().getReference("reelCollabs").child(extra)
                .child("acceptedAt").setValue(System.currentTimeMillis());
            if (nm != null) nm.cancel(notifId);
            Toast.makeText(ctx, "✓ Collab accepted!", Toast.LENGTH_SHORT).show();
            return;
        }

        // ── Collab Decline ─────────────────────────────────────────────
        if (ACTION_COLLAB_DECLINE.equals(action) && extra != null) {
            FirebaseUtils.db().getReference("reelCollabs").child(extra)
                .child("status").setValue("declined");
            if (nm != null) nm.cancel(notifId);
            return;
        }

        // ── Mark Mentions Read ─────────────────────────────────────────
        if (ACTION_MARK_MENTIONS_READ.equals(action)) {
            FirebaseUtils.db().getReference("reelMentions").child(myUid)
                .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                    @Override public void onDataChange(@androidx.annotation.NonNull com.google.firebase.database.DataSnapshot snap) {
                        for (com.google.firebase.database.DataSnapshot s : snap.getChildren())
                            s.getRef().child("read").setValue(true);
                    }
                    @Override public void onCancelled(@androidx.annotation.NonNull com.google.firebase.database.DatabaseError e) {}
                });
            if (nm != null) nm.cancel(notifId);
            return;
        }

        // ── Dismiss ────────────────────────────────────────────────────
        if (ACTION_DISMISS_REEL_NOTIF.equals(action)) {
            if (nm != null) nm.cancel(notifId);
        }
    }
}
