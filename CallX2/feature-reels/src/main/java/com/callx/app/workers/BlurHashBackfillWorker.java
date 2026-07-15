package com.callx.app.workers;

import android.content.Context;
import android.graphics.Bitmap;
import androidx.annotation.NonNull;
import androidx.work.*;

import com.bumptech.glide.Glide;
import com.callx.app.utils.BlurHash;
import com.callx.app.utils.CloudinaryUploader;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * BlurHashBackfillWorker — advance #3 (retroactive BlurHash backfill).
 *
 * New reels get a BlurHash the moment they're posted (see
 * ReelUploadActivity.generateAndAttachBlurHash). Reels posted BEFORE that
 * feature shipped have no blurHash field, so their grid cells still fall
 * back to the old flat icon placeholder instead of a blur-up preview.
 *
 * This one-time job walks a batch of a user's own reels, finds the ones
 * missing blurHash, downloads a tiny (32px) Cloudinary-derived thumbnail
 * for each, encodes a BlurHash string (same encode() call the upload flow
 * uses), and patches it onto the reel's Firebase record — after which the
 * existing ReelGridAdapter picks it up automatically on next bind, no
 * client changes needed beyond this backfill.
 *
 * Deliberately processes a bounded batch per run (BATCH_LIMIT) and is
 * scheduled unmetered-network-only + battery-not-low, so it never
 * competes with the user's active data usage; enqueue again (unique,
 * KEEP policy) for subsequent batches if reelCount > BATCH_LIMIT.
 */
public class BlurHashBackfillWorker extends Worker {

    private static final String WORK_NAME   = "blurhash_backfill";
    private static final int    BATCH_LIMIT = 60;
    private static final String KEY_OWNER_UID = "owner_uid";

    public BlurHashBackfillWorker(@NonNull Context ctx, @NonNull WorkerParameters params) {
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
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(BlurHashBackfillWorker.class)
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

            int patched = 0;
            for (String reelId : reelIds) {
                if (backfillOne(reelId)) patched++;
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
     * Returns true if a blurHash was generated and written for this reel.
     * Skips reels that already have one, or have no thumbUrl to derive from.
     */
    private boolean backfillOne(String reelId) throws InterruptedException {
        final String[] thumbUrlHolder = {null};
        final boolean[] alreadyHasHash = {false};
        CountDownLatch readLatch = new CountDownLatch(1);

        FirebaseUtils.getReelsRef().child(reelId)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        String existingHash = snap.child("blurHash").getValue(String.class);
                        alreadyHasHash[0] = existingHash != null && !existingHash.isEmpty();
                        String thumb = snap.child("thumbUrl").getValue(String.class);
                        if (thumb == null || thumb.isEmpty()) {
                            thumb = snap.child("thumbnailUrl").getValue(String.class);
                        }
                        thumbUrlHolder[0] = thumb;
                        readLatch.countDown();
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) { readLatch.countDown(); }
                });
        readLatch.await(10, TimeUnit.SECONDS);

        if (alreadyHasHash[0]) return false;
        String thumbUrl = thumbUrlHolder[0];
        if (thumbUrl == null || thumbUrl.isEmpty()) return false;

        try {
            String tinyUrl = CloudinaryUploader.deriveThumbUrl(thumbUrl, 32, "webp");
            Bitmap bmp = Glide.with(getApplicationContext())
                    .asBitmap()
                    .load(tinyUrl)
                    .submit(32, 32)
                    .get(10, TimeUnit.SECONDS);
            if (bmp == null) return false;
            String hash = BlurHash.encode(bmp, 4, 3);
            if (hash == null || hash.isEmpty()) return false;
            FirebaseUtils.getReelsRef().child(reelId).child("blurHash").setValue(hash);
            return true;
        } catch (Exception ignored) {
            return false; // this reel's thumb failed to decode/download — leave it for the next run
        }
    }
}
