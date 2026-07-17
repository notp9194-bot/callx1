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
 * Periodic WorkManager task (6 h). Checks saved sounds against musicLibrary usageCount >= 1000.
 * Schedule at app startup: TrendingSoundWorker.scheduleIfNeeded(context);
 */
public class TrendingSoundWorker extends Worker {
    private static final String TAG="TrendingSoundWorker", WORK="trending_sound_check";
    private static final long THRESHOLD=1_000L, TIMEOUT=20L;

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
                        String id=c.getKey(), t="";
                        if (c.getValue() instanceof String) t=(String)c.getValue();
                        else { Object o=c.child("title").getValue(); if(o!=null) t=o.toString(); }
                        notifyIfTrending(uid, id, t.isEmpty() ? "Sound" : t);
                    }
                    latch.countDown();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) { latch.countDown(); }
            });
        latch.await(TIMEOUT, TimeUnit.SECONDS);
    }

    private void notifyIfTrending(String uid, String sid, String title) {
        if (sid == null) return;
        FirebaseDatabase.getInstance().getReference("users").child(uid)
            .child("sound_trend_notified").child(sid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot ns) {
                    if (Boolean.TRUE.equals(ns.getValue(Boolean.class))) return;
                    FirebaseDatabase.getInstance().getReference("musicLibrary").child(sid).child("usageCount")
                        .addListenerForSingleValueEvent(new ValueEventListener() {
                            @Override public void onDataChange(@NonNull DataSnapshot us) {
                                Long cnt = us.getValue(Long.class);
                                if (cnt != null && cnt >= THRESHOLD) {
                                    ReelNotificationHelper.showSoundTrendingNotification(
                                        getApplicationContext(), title, sid, cnt);
                                    ns.getRef().setValue(true);
                                }
                            }
                            @Override public void onCancelled(@NonNull DatabaseError e) {}
                        });
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }
}
