package com.callx.app.services;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.callx.app.utils.Constants;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

/**
 * Handles avatar click on missed call notification.
 *
 * Logic:
 *  - Firebase "status/{callerUid}" check karo: koi active (expiresAt > now) item hai?
 *  - Agar hai  → StatusViewerActivity open karo (feature-status module)
 *  - Agar nahi → MainActivity open karo: chats tab + open_user_sheet extra
 *    (ChatsFragment ise pick up karke ContactBottomSheet dikhayega)
 */
public class MissedCallAvatarReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Constants.ACTION_MISSED_CALL_AVATAR_CLICK.equals(action)) return;

        final String callerUid   = intent.getStringExtra(Constants.EXTRA_PARTNER_UID);
        final String callerName  = intent.getStringExtra(Constants.EXTRA_PARTNER_NAME);
        final String callerPhoto = intent.getStringExtra(Constants.EXTRA_PARTNER_PHOTO);

        if (callerUid == null || callerUid.isEmpty()) return;

        final long now = System.currentTimeMillis();

        // Firebase se check: active status hai?
        FirebaseUtils.getStatusRef().child(callerUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(DataSnapshot snapshot) {
                    boolean hasActiveStatus = false;
                    for (DataSnapshot child : snapshot.getChildren()) {
                        // deleted check
                        Boolean deleted = child.child("deleted").getValue(Boolean.class);
                        if (Boolean.TRUE.equals(deleted)) continue;
                        // expiresAt check
                        Long expiresAt = child.child("expiresAt").getValue(Long.class);
                        if (expiresAt != null && expiresAt > now) {
                            hasActiveStatus = true;
                            break;
                        }
                    }

                    if (hasActiveStatus) {
                        openStatusViewer(context, callerUid, callerName);
                    } else {
                        openChatsBottomSheet(context, callerUid, callerName, callerPhoto);
                    }
                }

                @Override
                public void onCancelled(DatabaseError error) {
                    // Fallback: bottom sheet open karo
                    openChatsBottomSheet(context, callerUid, callerName, callerPhoto);
                }
            });
    }

    // ── StatusViewerActivity open karo (string-based className to avoid cross-module dep) ──
    private void openStatusViewer(Context ctx, String uid, String name) {
        try {
            Intent i = new Intent();
            i.setClassName(ctx.getPackageName(),
                "com.callx.app.viewer.StatusViewerActivity");
            i.putExtra("ownerUid",  uid);
            i.putExtra("ownerName", name != null ? name : "");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            ctx.startActivity(i);
        } catch (Exception e) {
            // Fallback to bottom sheet
            openChatsBottomSheet(ctx, uid, name, "");
        }
    }

    // ── MainActivity open karo: chats tab + bottom sheet trigger ──────────────
    private void openChatsBottomSheet(Context ctx, String uid, String name, String photo) {
        try {
            Intent i = new Intent();
            i.setClassName(ctx.getPackageName(),
                "com.callx.app.activities.MainActivity");
            i.putExtra("open_tab",         "chats");
            i.putExtra("open_user_sheet",  true);
            i.putExtra(Constants.EXTRA_PARTNER_UID,   uid);
            i.putExtra(Constants.EXTRA_PARTNER_NAME,  name  != null ? name  : "");
            i.putExtra(Constants.EXTRA_PARTNER_PHOTO, photo != null ? photo : "");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            ctx.startActivity(i);
        } catch (Exception ignored) {}
    }
}
