package com.callx.app.workers;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.*;

import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * DuetCollabIndexBackfillWorker — retroactive backfill for the profile grid's
 * new "Duet" and "Collab Repost" tabs (see UserReelsActivity.TAB_DUET /
 * TAB_COLLAB_REPOST).
 *
 * Those tabs read from two NEW indexes — userDuetReels/{uid} and
 * userCollabRepostReels/{uid} — that are only written going forward (from
 * ReelUploadActivity and CollabRepostAcceptActivity respectively). Any duet
 * or collab-repost reel a user posted BEFORE those tabs shipped already has
 * the right flag on the reel itself (duetOf / isCollabRepost), it's just
 * never been copied into the new index, so it would silently be missing
 * from the new tabs.
 *
 * This one-time job walks a batch of a user's own reels (same
 * reelsByUser/{uid} source the "Reels" tab uses), and for each reel that's
 * a duet or a collab repost, writes the matching index entry — after which
 * it shows up in the new tab exactly like a freshly-posted one, no other
 * client changes needed.
 *
 * Deliberately processes a bounded batch per run (BATCH_LIMIT) and is
 * scheduled unmetered-network-only + battery-not-low, so it never competes
 * with the user's active data usage; enqueue again (unique, KEEP policy)
 * for subsequent batches if reelCount > BATCH_LIMIT. Re-running is a
 * harmless no-op — each write just re-sets the same timestamp value.
 */
public class DuetCollabIndexBackfillWorker extends Worker {

    private static final String WORK_NAME   = "duet_collab_index_backfill";
    private static final int    BATCH_LIMIT = 100;
    private static final String KEY_OWNER_UID = "owner_uid";

    public DuetCollabIndexBackfillWorker(@NonNull Context ctx, @NonNull WorkerParameters params) {
        super(ctx, params);
    }

    /** Enqueue once per user (KEEP policy — re-running is a harmless no-op, so avoid duplicate work). */
    public static void enqueueFor(Context ctx, String ownerUid) {
        if (ownerUid == null || ownerUid.isEmpty()) return;
        Data input = new Data.Builder().putString(KEY_OWNER_UID, ownerUid).build();
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresBatteryNotLow(true)
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(DuetCollabIndexBackfillWorker.class)
                .setInputData(input)
                .setConstraints(constraints)
                .build();
        WorkManager.getInstance(ctx)
                .enqueueUniqueWork(WORK_NAME + "_" + ownerUid, ExistingWorkPolicy.KEEP, request);
    }

    @NonNull
    @Override
    public Result doWork() {
        String ownerUid = getInputData().getString(KEY_OWNER_UID);
        if (ownerUid == null || ownerUid.isEmpty()) return Result.failure();

        try {
            List<String> reelIds = fetchReelIdsForOwner(ownerUid);
            if (reelIds.isEmpty()) return Result.success();

            for (String reelId : reelIds) {
                backfillOne(ownerUid, reelId);
            }
            return Result.success();
        } catch (Exception e) {
            return Result.retry();
        }
    }

    /** Reads up to BATCH_LIMIT reel ids owned by the user, newest first. */
    private List<String> fetchReelIdsForOwner(String ownerUid) throws InterruptedException {
        List<String> ids = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        FirebaseUtils.getReelsByUserRef(ownerUid).orderByKey().limitToLast(BATCH_LIMIT)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        for (DataSnapshot s : snap.getChildren()) {
                            if (s.getKey() != null) ids.add(s.getKey());
                        }
                        latch.countDown();
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) { latch.countDown(); }
                });
        latch.await(15, TimeUnit.SECONDS);
        return ids;
    }

    /**
     * Reads one reel's full record and, if it's a duet and/or a collab
     * repost, writes the matching index entry(ies) — a reel could in
     * principle be both, so both checks run independently.
     */
    private void backfillOne(String ownerUid, String reelId) throws InterruptedException {
        final String[]  duetOfHolder      = {null};
        final boolean[] isCollabHolder    = {false};
        final Long[]    timestampHolder   = {null};
        CountDownLatch readLatch = new CountDownLatch(1);

        FirebaseUtils.getReelsRef().child(reelId)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        duetOfHolder[0]    = snap.child("duetOf").getValue(String.class);
                        Boolean isCollab   = snap.child("isCollabRepost").getValue(Boolean.class);
                        isCollabHolder[0]  = isCollab != null && isCollab;
                        timestampHolder[0] = snap.child("timestamp").getValue(Long.class);
                        readLatch.countDown();
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) { readLatch.countDown(); }
                });
        readLatch.await(10, TimeUnit.SECONDS);

        long ts = timestampHolder[0] != null ? timestampHolder[0] : System.currentTimeMillis();

        if (duetOfHolder[0] != null && !duetOfHolder[0].isEmpty()) {
            FirebaseUtils.getUserDuetReelsRef(ownerUid).child(reelId).setValue(ts);
        }
        if (isCollabHolder[0]) {
            FirebaseUtils.getUserCollabRepostReelsRef(ownerUid).child(reelId).setValue(ts);
        }
    }
}
