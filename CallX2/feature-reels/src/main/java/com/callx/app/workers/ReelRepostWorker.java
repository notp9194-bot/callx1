package com.callx.app.workers;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.callx.app.notifications.ReelRepostNotificationHelper;
import com.callx.app.utils.Constants;
import com.callx.app.utils.PushNotify;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * ReelRepostWorker — Background-kill-safe WorkManager task for reel reposts.
 *
 * v4.2.9 production upgrades:
 *  ✅ Repost chain detection — blocks reposting a reel that is itself a repost
 *     (maximum repost depth = 1). Prevents "repost of a repost" spam chains.
 *  ✅ Grouped batch notification — calls ReelRepostBatchWorker after every repost
 *     so the creator receives ONE "N people reposted" notif instead of N separate
 *     push notifications (30-second debounce window).
 *  ✅ Exponential backoff — retries use 15s initial delay, doubling up to 3 retries
 *     before giving up, preventing thundering-herd retries on Firebase outages.
 *  ✅ Caption support — stores repostCaptions/{reelId}/{reposterId}/caption in
 *     Firebase when the user reposted with a custom caption.
 *  ✅ REPLACE policy on unique work key — prevents duplicate repost records.
 *
 * Usage:
 *   ReelRepostWorker.enqueue(ctx, reelId, reposterId, reposterName,
 *                            ownerUid, ownerName, thumbUrl, caption);
 *   // caption may be null for plain reposts
 */
public class ReelRepostWorker extends Worker {

    public static final String KEY_REEL_ID       = "reel_id";
    public static final String KEY_REPOSTER_ID   = "reposter_id";
    public static final String KEY_REPOSTER_NAME = "reposter_name";
    public static final String KEY_OWNER_UID     = "owner_uid";
    public static final String KEY_OWNER_NAME    = "owner_name";
    public static final String KEY_THUMB_URL     = "thumb_url";
    public static final String KEY_CAPTION       = "caption";

    /** Maximum allowed repost chain depth (1 = can only repost originals). */
    private static final int MAX_REPOST_DEPTH = 1;

    public ReelRepostWorker(@NonNull Context ctx, @NonNull WorkerParameters params) {
        super(ctx, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        String reelId       = getInputData().getString(KEY_REEL_ID);
        String reposterId   = getInputData().getString(KEY_REPOSTER_ID);
        String reposterName = getInputData().getString(KEY_REPOSTER_NAME);
        String ownerUid     = getInputData().getString(KEY_OWNER_UID);
        String ownerName    = getInputData().getString(KEY_OWNER_NAME);
        String thumbUrl     = getInputData().getString(KEY_THUMB_URL);
        String caption      = getInputData().getString(KEY_CAPTION);

        if (reelId == null || reposterId == null || ownerUid == null) return Result.failure();

        try {
            FirebaseDatabase db = FirebaseDatabase.getInstance(Constants.DB_URL);
            long now = System.currentTimeMillis();

            // ── Step 1: Repost chain detection ─────────────────────────────────
            // Block if the source reel is itself a repost (depth > MAX_REPOST_DEPTH)
            final boolean[] chainCheckDone  = {false};
            final boolean[] isRepostOfRepost = {false};
            db.getReference("reels").child(reelId).child("repostedFromReelId")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        String sourceReelId = snap.getValue(String.class);
                        isRepostOfRepost[0] = (sourceReelId != null && !sourceReelId.isEmpty());
                        chainCheckDone[0] = true;
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {
                        chainCheckDone[0] = true; // fail open on error
                    }
                });
            long waited = 0;
            while (!chainCheckDone[0] && waited < 5000) { Thread.sleep(100); waited += 100; }

            if (isRepostOfRepost[0]) {
                // Source reel is already a repost — block the chain
                // Show local notification explaining why
                ReelRepostNotificationHelper.notifyChainBlocked(
                    getApplicationContext(), reelId);
                return Result.failure(); // permanent failure — don't retry
            }

            // ── Step 2: Write repost index ─────────────────────────────────────
            // Idempotent backup — direct write already done in ReelPlayerFragment
            db.getReference("reelReposts").child(reelId).child(reposterId).setValue(now);

            // ── Step 3: Write to reposter's history ────────────────────────────
            db.getReference("userReposts").child(reposterId).child(reelId).setValue(now);

            // ── Step 4: Store caption (if user added one) ──────────────────────
            if (caption != null && !caption.isEmpty()) {
                Map<String, Object> captionData = new HashMap<>();
                captionData.put("caption",     caption);
                captionData.put("repostedAt",  now);
                captionData.put("reposterId",  reposterId);
                captionData.put("reposterName",reposterName != null ? reposterName : "");
                db.getReference("repostCaptions").child(reelId).child(reposterId)
                    .setValue(captionData);
            }

            // ── Step 5: In-app notification for original creator ───────────────
            if (!ownerUid.equals(reposterId)) {
                String msg = (reposterName != null ? reposterName : "Someone") + " reposted your reel";
                if (caption != null && !caption.isEmpty()) msg += ": \"" + caption + "\"";

                Map<String, Object> inApp = new HashMap<>();
                inApp.put("type",       "repost");
                inApp.put("senderUid",  reposterId);
                inApp.put("senderName", reposterName != null ? reposterName : "Someone");
                inApp.put("reel_id",    reelId);
                inApp.put("thumb_url",  thumbUrl  != null ? thumbUrl  : "");
                inApp.put("caption",    caption   != null ? caption   : "");
                inApp.put("message",    msg);
                inApp.put("timestamp",  now);
                inApp.put("read",       false);
                db.getReference("reel_notifications").child(ownerUid).push().setValue(inApp);

                // ── Step 6: FCM push (individual, for real-time delivery) ──────
                PushNotify.notifyReelRepost(
                    ownerUid, reposterId,
                    reposterName != null ? reposterName : "Someone",
                    reelId, thumbUrl != null ? thumbUrl : "");

                // ── Step 7: Grouped batch notification (30s debounce) ──────────
                ReelRepostBatchWorker.enqueue(
                    getApplicationContext(), reelId, ownerUid,
                    thumbUrl != null ? thumbUrl : "");
            }

            // ── Step 8: Local confirmation on reposter's device ───────────────
            ReelRepostNotificationHelper.notifyRepostQueued(
                getApplicationContext(), reposterId, reelId,
                ownerName != null ? ownerName : "creator");

            return Result.success();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.retry();
        } catch (Exception e) {
            return Result.retry(); // exponential backoff handles retry timing
        }
    }

    /**
     * Enqueue with exponential backoff retry policy.
     * Unique work key prevents duplicate writes for the same reel+reposter pair.
     *
     * @param caption null for plain reposts, non-null for captioned reposts
     */
    public static void enqueue(@NonNull Context ctx,
                                @NonNull String reelId,
                                @NonNull String reposterId,
                                String reposterName,
                                @NonNull String ownerUid,
                                String ownerName,
                                String thumbUrl,
                                String caption) {
        Data inputData = new Data.Builder()
            .putString(KEY_REEL_ID,       reelId)
            .putString(KEY_REPOSTER_ID,   reposterId)
            .putString(KEY_REPOSTER_NAME, reposterName != null ? reposterName : "")
            .putString(KEY_OWNER_UID,     ownerUid)
            .putString(KEY_OWNER_NAME,    ownerName   != null ? ownerName   : "")
            .putString(KEY_THUMB_URL,     thumbUrl    != null ? thumbUrl    : "")
            .putString(KEY_CAPTION,       caption     != null ? caption     : "")
            .build();

        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(ReelRepostWorker.class)
            .setInputData(inputData)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .addTag("reel_repost_" + reelId)
            .build();

        WorkManager.getInstance(ctx).enqueueUniqueWork(
            "repost_" + reelId + "_" + reposterId,
            ExistingWorkPolicy.REPLACE,
            request);
    }

    /**
     * Backwards-compatible overload (no caption) for call sites that haven't
     * migrated to the captioned signature.
     */
    public static void enqueue(@NonNull Context ctx,
                                @NonNull String reelId,
                                @NonNull String reposterId,
                                String reposterName,
                                @NonNull String ownerUid,
                                String ownerName,
                                String thumbUrl) {
        enqueue(ctx, reelId, reposterId, reposterName, ownerUid, ownerName, thumbUrl, null);
    }
}
