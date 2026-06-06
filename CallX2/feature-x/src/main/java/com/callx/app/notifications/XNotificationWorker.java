package com.callx.app.notifications;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.callx.app.utils.XFirebaseUtils;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Background WorkManager worker that polls for new X notifications every 15 minutes.
 *
 * Runs even when the app is killed (WorkManager survives process death).
 * Falls back to Firebase one-shot reads so no persistent connection is needed.
 *
 * FIX: Ab XFCMNotificationHandler.handle() use karta hai —
 *   → Avatar (profile photo) properly download hota hai
 *   → fromName, fromHandle, fromPhoto sab notification me dikh te hain
 *   → DM notifications me conversationId, otherHandle etc. properly pass hote hain
 */
public class XNotificationWorker extends Worker {

    private static final String WORK_TAG = "x_notification_poll";

    public XNotificationWorker(@NonNull Context ctx, @NonNull WorkerParameters params) {
        super(ctx, params);
    }

    public static void schedule(Context ctx) {
        Constraints constraints = new Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build();

        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(
                XNotificationWorker.class, 15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .addTag(WORK_TAG)
            .build();

        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            WORK_TAG,
            ExistingPeriodicWorkPolicy.KEEP,
            req);
    }

    @NonNull @Override
    public Result doWork() {
        String uid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;
        if (uid == null) return Result.success();

        final Object lock = new Object();
        final boolean[] done = {false};

        XFirebaseUtils.xNotificationsRef(uid)
            .orderByChild("read")
            .equalTo(false)
            .limitToLast(10)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    for (DataSnapshot ds : snap.getChildren()) {

                        // ── Firebase se saare fields read karo ──────────────────
                        String type           = safeStr(ds, "type");
                        String fromUid        = safeStr(ds, "fromUid");
                        String fromName       = safeStr(ds, "fromName");
                        String fromPhoto      = safeStr(ds, "fromPhotoUrl");   // avatar URL
                        String fromHandle     = safeStr(ds, "fromHandle");     // @handle
                        String tweetId        = safeStr(ds, "tweetId");
                        String conversationId = safeStr(ds, "conversationId");
                        String otherUid       = safeStr(ds, "otherUid");
                        String otherHandle    = safeStr(ds, "otherHandle");
                        String otherPhoto     = safeStr(ds, "otherPhotoUrl");
                        String preview        = safeStr(ds, "preview");
                        String pollQuestion   = safeStr(ds, "pollQuestion");
                        String listName       = safeStr(ds, "listName");
                        String spaceId        = safeStr(ds, "spaceId");
                        String spaceTitle     = safeStr(ds, "spaceTitle");

                        if (fromName.isEmpty()) fromName = "Someone";

                        // ── XFCMNotificationHandler ke format me Map banao ──────
                        // Isse avatar download + proper title/body sab automatically handle hoga
                        Map<String, String> data = new HashMap<>();
                        data.put("x_notif_type",    type);
                        data.put("fromUid",          fromUid);
                        data.put("fromName",         fromName);
                        data.put("fromPhoto",        fromPhoto);      // ← avatar ke liye
                        data.put("fromHandle",       fromHandle);
                        data.put("tweetId",          tweetId);
                        data.put("conversationId",   conversationId);
                        data.put("otherUid",         otherUid);
                        data.put("otherHandle",      otherHandle);
                        data.put("otherPhoto",       otherPhoto);
                        data.put("preview",          preview.isEmpty() ? "New message" : preview);
                        data.put("pollQuestion",     pollQuestion);
                        data.put("listName",         listName);
                        data.put("spaceId",          spaceId);
                        data.put("spaceTitle",       spaceTitle);

                        // ── Handle karo — avatar download + notification post ───
                        // XFCMNotificationHandler.handle() internally Executor use karta hai
                        // aur network ke hisaab se avatar download karta hai
                        XFCMNotificationHandler.handle(getApplicationContext(), data);

                        // Mark as notified
                        ds.getRef().child("notified").setValue(true);
                    }
                    synchronized (lock) { done[0] = true; lock.notifyAll(); }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    synchronized (lock) { done[0] = true; lock.notifyAll(); }
                }
            });

        // Wait up to 12 seconds for Firebase response (safe to block on Worker thread)
        synchronized (lock) {
            if (!done[0]) {
                try { lock.wait(12_000); } catch (InterruptedException ignored) {}
            }
        }

        return Result.success();
    }

    /** DataSnapshot se safe String read — null safe */
    private static String safeStr(DataSnapshot ds, String key) {
        String v = ds.child(key).getValue(String.class);
        return v != null ? v : "";
    }
}
