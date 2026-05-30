package com.callx.app.workers;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.*;
import com.callx.app.models.XTweet;
import com.callx.app.utils.XFirebaseUtils;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * XScheduledPostWorker — WorkManager worker that fires at the scheduled time
 * and publishes a previously saved draft tweet.
 *
 * Usage:
 *   XScheduledPostWorker.schedule(context, tweetId, scheduledAt);
 */
public class XScheduledPostWorker extends Worker {

    private static final String KEY_TWEET_ID = "tweet_id";

    public XScheduledPostWorker(@NonNull Context ctx, @NonNull WorkerParameters params) {
        super(ctx, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        String tweetId = getInputData().getString(KEY_TWEET_ID);
        if (tweetId == null || tweetId.isEmpty()) return Result.failure();

        CountDownLatch latch = new CountDownLatch(1);
        final Result[] outcome = {Result.failure()};

        XFirebaseUtils.scheduledPostsRef().child(tweetId)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    XTweet tweet = snap.getValue(XTweet.class);
                    if (tweet == null) { outcome[0] = Result.failure(); latch.countDown(); return; }
                    tweet.id          = snap.getKey();
                    tweet.timestamp   = System.currentTimeMillis();
                    tweet.scheduledAt = 0;    // clear flag so it appears as a live tweet

                    String myUid = tweet.authorUid;
                    if (myUid == null || myUid.isEmpty()) {
                        // fallback: use currently signed-in user
                        if (FirebaseAuth.getInstance().getCurrentUser() != null)
                            myUid = FirebaseAuth.getInstance().getCurrentUser().getUid();
                    }

                    Map<String, Object> updates = new HashMap<>();
                    updates.put("/x/tweets/" + tweetId, tweet);
                    updates.put("/x/global_feed/" + tweetId, tweet);
                    if (myUid != null) {
                        updates.put("/x/user_tweets/" + myUid + "/" + tweetId, tweet);
                        updates.put("/x/user_feeds/"  + myUid + "/" + tweetId, tweet);
                    }
                    // Remove from scheduled store
                    updates.put("/x/scheduled_posts/" + tweetId, null);

                    // Hashtag feeds
                    if (tweet.hashtags != null) {
                        for (String tag : tweet.hashtags) {
                            updates.put("/x/hashtag_feeds/" + tag + "/" + tweetId, true);
                            updates.put("/x/trending/" + tag + "/countAll",
                                com.google.firebase.database.ServerValue.increment(1));
                            updates.put("/x/trending/" + tag + "/lastPostAt",
                                System.currentTimeMillis());
                        }
                    }

                    final String finalMyUid = myUid;
                    FirebaseDatabase.getInstance().getReference().updateChildren(updates)
                        .addOnSuccessListener(v -> {
                            // Fan-out to followers
                            if (finalMyUid != null) fanOut(tweet, finalMyUid);
                            outcome[0] = Result.success(); latch.countDown();
                        })
                        .addOnFailureListener(e -> { outcome[0] = Result.retry(); latch.countDown(); });
                }
                @Override public void onCancelled(DatabaseError e) {
                    outcome[0] = Result.retry(); latch.countDown();
                }
            });

        try { latch.await(30, TimeUnit.SECONDS); } catch (InterruptedException e) { return Result.retry(); }
        return outcome[0];
    }

    private void fanOut(XTweet tweet, String myUid) {
        XFirebaseUtils.userFollowersRef(myUid).limitToFirst(500).get()
            .addOnSuccessListener(snap -> {
                Map<String, Object> upd = new HashMap<>();
                for (DataSnapshot ds : snap.getChildren())
                    upd.put("/x/user_feeds/" + ds.getKey() + "/" + tweet.id, tweet);
                if (!upd.isEmpty())
                    FirebaseDatabase.getInstance().getReference().updateChildren(upd);
            });
    }

    // ── Static schedule helper ────────────────────────────────────────────────

    public static void schedule(Context ctx, String tweetId, long fireAtMs) {
        long delay = Math.max(0, fireAtMs - System.currentTimeMillis());

        Data inputData = new Data.Builder()
            .putString(KEY_TWEET_ID, tweetId)
            .build();

        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(XScheduledPostWorker.class)
            .setInputData(inputData)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .addTag("scheduled_post_" + tweetId)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .setConstraints(new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build())
            .build();

        WorkManager.getInstance(ctx).enqueueUniqueWork(
            "scheduled_post_" + tweetId,
            ExistingWorkPolicy.REPLACE,
            request);
    }

    /** Cancel a previously scheduled post */
    public static void cancel(Context ctx, String tweetId) {
        WorkManager.getInstance(ctx).cancelAllWorkByTag("scheduled_post_" + tweetId);
        XFirebaseUtils.scheduledPostsRef().child(tweetId).removeValue();
    }
}
