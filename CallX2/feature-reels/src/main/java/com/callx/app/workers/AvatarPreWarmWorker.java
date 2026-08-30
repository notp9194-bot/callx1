package com.callx.app.workers;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.bumptech.glide.Glide;
import com.callx.app.cache.AvatarCacheAnalytics;
import com.callx.app.cache.ReelsAvatarL2Cache;
import com.callx.app.utils.AvatarSizeTier;
import com.callx.app.utils.AvatarUrlBuilder;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * AvatarPreWarmWorker — Instagram-style background avatar pre-warming.
 *
 * AvatarPrefetcher (see that class) already warms the NEXT few reels in an
 * active scroll session, but that only helps once the user has already
 * opened the app and started scrolling. This worker does the same job
 * AHEAD of time, entirely in the background: while the phone is charging,
 * on an unmetered (WiFi) connection, and the system considers it idle, it
 * walks the current user's "following" + "close friends" lists and warms
 * both owner-avatar tiers straight into {@link ReelsAvatarL2Cache}'s L2
 * memory and L3 disk tiers — so the very first cold-open of the reels feed
 * after this runs can paint those avatars with zero network round-trip.
 *
 * Every constraint below has to hold simultaneously before the system will
 * even start this job, and there's no foreground-visible cost either way —
 * it never competes with active-session network/battery use, and a run
 * that gets interrupted (charger unplugged, WiFi drops) simply doesn't
 * complete; it isn't retried aggressively, the next periodic window covers
 * whatever was missed.
 */
public class AvatarPreWarmWorker extends Worker {

    private static final String TAG = "AvatarPreWarm";
    public static final String UNIQUE_WORK_NAME = "avatar_prewarm_following";

    // Bounded on purpose — this is a courtesy warm sharing one charging+WiFi
    // idle window with whatever else the OS schedules in it, not a full
    // background sync. A huge following list still just warms the first
    // MAX_TARGETS (most-recently-followed-first, per Firebase's own child
    // ordering) rather than trying to cover everyone every run.
    private static final int MAX_TARGETS = 60;
    private static final int PER_UID_TIMEOUT_SEC = 15;

    public AvatarPreWarmWorker(@NonNull Context ctx, @NonNull WorkerParameters params) {
        super(ctx, params);
    }

    /**
     * Enqueue (or, with KEEP, no-op if already enqueued) the periodic job.
     * Call once from CallxApp#onCreate. 6h period is just how often
     * WorkManager re-checks — the charging+unmetered(+idle) constraints
     * are what actually gate every real run, not this interval.
     */
    public static void schedule(Context ctx) {
        Constraints.Builder constraints = new Constraints.Builder()
                .setRequiresCharging(true)                    // "charging" — user's own wording
                .setRequiredNetworkType(NetworkType.UNMETERED); // WiFi (or any unmetered connection)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            constraints.setRequiresDeviceIdle(true); // real system idle signal — "phone idle pe" — API 23+
        }
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                AvatarPreWarmWorker.class, 6, TimeUnit.HOURS)
                .setConstraints(constraints.build())
                .build();
        WorkManager.getInstance(ctx.getApplicationContext())
                .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request);
    }

    @NonNull
    @Override
    public Result doWork() {
        String myUid = FirebaseUtils.getCurrentUid();
        if (myUid == null || myUid.isEmpty()) return Result.success();

        List<String> targets = new ArrayList<>();
        for (String uid : fetchUidChildrenSync(FirebaseUtils.getReelFollowsRef(myUid), MAX_TARGETS)) {
            if (!targets.contains(uid)) targets.add(uid);
        }
        for (String uid : fetchUidChildrenSync(FirebaseUtils.getUserRef(myUid).child("closeFriends"), MAX_TARGETS)) {
            if (targets.size() >= MAX_TARGETS) break;
            if (!targets.contains(uid)) targets.add(uid);
        }
        if (targets.isEmpty()) return Result.success();

        Context appCtx = getApplicationContext();
        int warmed = 0;
        for (String uid : targets) {
            if (isStopped()) break; // constraints stopped holding mid-run (e.g. unplugged) — bail cleanly, no partial-retry storm
            if (warmAvatar(appCtx, uid)) warmed++;
        }
        Log.d(TAG, "pre-warmed " + warmed + "/" + targets.size() + " avatars (charging+unmetered+idle window)");
        return Result.success();
    }

    /**
     * Targeted read of just the child KEYS under a ref (uids the person
     * follows / has close-friended), never the whole node's values — one
     * single value event, bounded with limitToFirst so this never pulls an
     * unbounded following list into memory.
     */
    private List<String> fetchUidChildrenSync(DatabaseReference ref, int limit) {
        List<String> uids = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        ref.limitToFirst(limit).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                for (DataSnapshot child : snap.getChildren()) {
                    if (child.getKey() != null) uids.add(child.getKey());
                }
                latch.countDown();
            }
            @Override public void onCancelled(@NonNull DatabaseError error) { latch.countDown(); }
        });
        try { latch.await(PER_UID_TIMEOUT_SEC, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        return uids;
    }

    /**
     * Targeted read of ONLY "photoUrl" + "avatarVersion" for one uid — two
     * scalar child reads, never the whole "users/{uid}" node — then decodes
     * and stores the SMALL + TINY tiers (the exact tiers AvatarPrefetcher /
     * ReelUiController bind at) into L2 + L3 so a later real bind is a hit.
     */
    private boolean warmAvatar(Context appCtx, String uid) {
        String[] photoHolder = new String[1];
        long[] versionHolder = new long[1];
        CountDownLatch latch = new CountDownLatch(2);

        FirebaseUtils.getUserRef(uid).child("photoUrl").addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                photoHolder[0] = snap.getValue(String.class);
                latch.countDown();
            }
            @Override public void onCancelled(@NonNull DatabaseError error) { latch.countDown(); }
        });
        FirebaseUtils.getUserRef(uid).child("avatarVersion").addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                Long v = snap.getValue(Long.class);
                versionHolder[0] = v != null ? v : 0L;
                latch.countDown();
            }
            @Override public void onCancelled(@NonNull DatabaseError error) { latch.countDown(); }
        });

        try { latch.await(PER_UID_TIMEOUT_SEC, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        String photoUrl = photoHolder[0];
        if (photoUrl == null || photoUrl.isEmpty()) return false;

        boolean any = false;
        for (AvatarSizeTier tier : new AvatarSizeTier[]{AvatarSizeTier.SMALL, AvatarSizeTier.TINY}) {
            String url = AvatarUrlBuilder.buildResponsive(appCtx, photoUrl, tier, versionHolder[0]);
            int px = AvatarUrlBuilder.tierPx(appCtx, tier);
            try {
                Bitmap bmp = Glide.with(appCtx)
                        .asBitmap()
                        .load(url)
                        .override(px, px)
                        .submit()
                        .get(PER_UID_TIMEOUT_SEC, TimeUnit.SECONDS);
                if (bmp != null) {
                    ReelsAvatarL2Cache.get(appCtx).put(url, bmp);
                    ReelsAvatarL2Cache.l3(appCtx).put(url, bmp);
                    AvatarCacheAnalytics.getInstance(appCtx).record(AvatarCacheAnalytics.Tier.PREWARM);
                    any = true;
                }
            } catch (Exception e) {
                Log.w(TAG, "warm failed for " + uid + " tier " + tier + ": " + e.getMessage());
            }
        }
        return any;
    }
}
