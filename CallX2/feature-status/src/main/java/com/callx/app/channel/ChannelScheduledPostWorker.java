package com.callx.app.channel;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.*;
import com.callx.app.db.AppDatabase;
import com.callx.app.db.dao.ChannelDao;
import com.callx.app.db.entity.ChannelPostEntity;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ServerValue;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * ChannelScheduledPostWorker — WorkManager Worker that auto-publishes scheduled
 * channel posts when their scheduled time has arrived.
 *
 * Runs as a PeriodicWorkRequest every 15 minutes.
 * For each overdue scheduled post:
 *   1. Writes to Firebase: clears scheduledAt, sets timestamp, updates channel.lastPostAt
 *   2. Updates Room DB via ChannelDao.publishScheduledPost()
 *
 * Register on app start or after creating any scheduled post:
 *   ChannelScheduledPostWorker.schedulePeriodicWork(context);
 */
public class ChannelScheduledPostWorker extends Worker {

    private static final String TAG = "channel_scheduled_posts";

    public ChannelScheduledPostWorker(@NonNull Context context,
                                       @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            ChannelDao dao = AppDatabase.getInstance(getApplicationContext()).channelDao();
            long now = System.currentTimeMillis();

            List<ChannelPostEntity> duePosts = dao.getPostsDueForPublishing(now);
            if (duePosts == null || duePosts.isEmpty()) return Result.success();

            for (ChannelPostEntity post : duePosts) {
                publishPost(post, now);
                // Update Room immediately (Firebase is async but Room is the source of truth for UI)
                dao.publishScheduledPost(post.id, now);
            }

            return Result.success();
        } catch (Exception e) {
            // Retry on failure
            return Result.retry();
        }
    }

    private void publishPost(ChannelPostEntity post, long now) {
        if (post.channelId == null || post.id == null) return;

        DatabaseReference root = FirebaseUtils.db().getReference();

        // Build multi-path update to atomically publish the post
        Map<String, Object> updates = new HashMap<>();
        updates.put("channelPosts/" + post.channelId + "/" + post.id + "/scheduledAt", 0);
        updates.put("channelPosts/" + post.channelId + "/" + post.id + "/timestamp",   now);

        // Update channel's lastPost metadata so followers see new content
        updates.put("channels/" + post.channelId + "/lastPostAt",   now);
        updates.put("channels/" + post.channelId + "/lastPostType", post.type != null ? post.type : "text");
        if (post.text != null && !post.text.isEmpty()) {
            // Truncate to avoid large payloads in channel list
            String preview = post.text.length() > 100 ? post.text.substring(0, 100) + "…" : post.text;
            updates.put("channels/" + post.channelId + "/lastPostText", preview);
        }

        root.updateChildren(updates, (err, ref) -> {
            if (err != null) {
                android.util.Log.w("ChannelScheduledWorker",
                        "Failed to publish scheduled post " + post.id + ": " + err.getMessage());
            }
        });
    }

    // ── Static helpers ────────────────────────────────────────────────────

    /**
     * Schedule periodic work to auto-publish scheduled posts.
     * Safe to call multiple times — uses KEEP policy to avoid duplicates.
     * Call this from Application.onCreate() and after creating any scheduled post.
     */
    public static void schedulePeriodicWork(@NonNull Context ctx) {
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                ChannelScheduledPostWorker.class,
                15, TimeUnit.MINUTES)
                .addTag(TAG)
                .setConstraints(new Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build())
                .build();

        WorkManager.getInstance(ctx.getApplicationContext())
                .enqueueUniquePeriodicWork(
                        TAG,
                        ExistingPeriodicWorkPolicy.KEEP,
                        request);
    }

    /**
     * Cancel the periodic work (e.g., when user signs out).
     */
    public static void cancelWork(@NonNull Context ctx) {
        WorkManager.getInstance(ctx.getApplicationContext()).cancelAllWorkByTag(TAG);
    }
}
