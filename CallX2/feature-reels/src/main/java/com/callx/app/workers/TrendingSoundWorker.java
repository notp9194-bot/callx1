package com.callx.app.workers;
import android.content.Context;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.work.*;
import com.callx.app.notifications.ReelNotificationHelper;
import com.google.firebase.auth.*;
import com.google.firebase.database.*;
import java.util.concurrent.*;

/**
 * Periodic WorkManager task (6 h). Checks the user's saved sounds against
 * the "sounds" node this app actually reads/writes and notifies once a
 * saved sound crosses the trending threshold.
 * Schedule at app startup: TrendingSoundWorker.scheduleIfNeeded(context);
 *
 * ✅ FIX (plan item #2 — dead code cleanup): this used to read
 * musicLibrary/{id}/usageCount, a node nothing else in the codebase writes
 * to anymore — every real write goes to sounds/{id}/reel_count (bumped in
 * ReelUploadActivity#registerOrLinkSound) and sounds/{id}/is_trending
 * (flipped once reel_count crosses TRENDING_REEL_THRESHOLD, same file).
 * So this worker could never actually fire. Now it reads is_trending
 * directly off the same "sounds" node everything else already uses —
 * no separate threshold to keep in sync in two places.
 */
public class TrendingSoundWorker extends Worker {
    private static final String TAG="TrendingSoundWorker", WORK="trending_sound_check";
    private static final long TIMEOUT=20L;

    public TrendingSoundWorker(@NonNull Context c, @NonNull WorkerParameters p) { super(c,p); }

    public static void scheduleIfNeeded(Context ctx) {
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            new PeriodicWorkRequest.Builder(TrendingSoundWorker.class,6,TimeUnit.HOURS).build());
    }

    @NonNull @Override public Result doWork() {
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u == null) return Result.success();
        try { checkSounds(u.getUid()); } catch (Exception e) { Log.e(TAG,"err",e); }
        return Result.success();
    }

    private void checkSounds(String uid) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        FirebaseDatabase.getInstance().getReference("users").child(uid).child("saved_sounds")
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot s) {
                    for (DataSnapshot c : s.getChildren()) {
                        String id = c.getKey();
                        notifyIfTrending(uid, id);
                    }
                    latch.countDown();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) { latch.countDown(); }
            });
        latch.await(TIMEOUT, TimeUnit.SECONDS);
    }

    private void notifyIfTrending(String uid, String sid) {
        if (sid == null) return;
        FirebaseDatabase.getInstance().getReference("users").child(uid)
            .child("sound_trend_notified").child(sid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot ns) {
                    if (Boolean.TRUE.equals(ns.getValue(Boolean.class))) return;
                    // ✅ FIX: read off the real "sounds" node (is_trending +
                    // reel_count), not the dead "musicLibrary" tree.
                    FirebaseDatabase.getInstance().getReference("sounds").child(sid)
                        .addListenerForSingleValueEvent(new ValueEventListener() {
                            @Override public void onDataChange(@NonNull DataSnapshot us) {
                                Boolean trending = us.child("is_trending").getValue(Boolean.class);
                                if (!Boolean.TRUE.equals(trending)) return;
                                Long cnt = us.child("reel_count").getValue(Long.class);
                                String title = us.child("title").getValue(String.class);
                                ReelNotificationHelper.showSoundTrendingNotification(
                                    getApplicationContext(),
                                    (title != null && !title.isEmpty()) ? title : "Sound",
                                    sid, cnt != null ? cnt : 0L);
                                ns.getRef().setValue(true);
                            }
                            @Override public void onCancelled(@NonNull DatabaseError e) {}
                        });
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }
}
