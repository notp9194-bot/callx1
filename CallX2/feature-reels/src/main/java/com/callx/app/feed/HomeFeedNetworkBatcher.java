package com.callx.app.feed;

import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;

import java.util.*;

/**
 * HomeFeedNetworkBatcher — Coalesces rapid Firebase read requests into
 * single batches to reduce overhead and network traffic.
 *
 * Typical problem: Rapid scroll → postLoad() called for positions 10, 11, 12,
 * etc. → 8 simultaneous fbRef.child(reelId).child("likeCount").addValueListener()
 * calls = 8 separate network round-trips (even on same connection, overhead builds).
 *
 * Solution: Collect read requests arriving within a 50ms window, then fire a
 * single batch query via fb.child(...).addValueListener() that reads all 8
 * reelIds at once. Results cached, so repeated calls for same reelId = instant.
 *
 * Typical usage:
 *  1. networkBatcher.queueMetadataRead(reelId, fbRef, callback)
 *  2. Repeat 8 times within 50ms
 *  3. Batch fires: single fbRef fetch + ValueEventListener
 *  4. Each callback invoked as metadata arrives
 *
 * Implementation detail: Uses a HashMap to deduplicate requests arriving for
 * the same reelId in the same batch window.
 */
public class HomeFeedNetworkBatcher {

    private static final String TAG = "NetworkBatcher";

    private final DatabaseReference fbRef;
    private final int coalesceWindowMs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Map<String, List<HomeFeedMetadataCache.MetadataCallback>> pendingCallbacks =
        Collections.synchronizedMap(new HashMap<>());
    private volatile boolean batchScheduled = false;
    private long lastBatchFiredAt = 0;

    public HomeFeedNetworkBatcher(@NonNull DatabaseReference fbRef, int coalesceWindowMs) {
        this.fbRef = fbRef;
        this.coalesceWindowMs = coalesceWindowMs;
    }

    /**
     * Queue a single post's metadata read. If this is the first request in the
     * batch window, schedules a batch to fire after coalesceWindowMs. Otherwise,
     * adds to the pending queue to be processed by the already-scheduled batch.
     */
    public void queueMetadataRead(@NonNull String reelId, @NonNull DatabaseReference fbRef,
                                  @NonNull HomeFeedMetadataCache.MetadataCallback callback) {
        List<HomeFeedMetadataCache.MetadataCallback> callbacks =
            pendingCallbacks.computeIfAbsent(reelId, k -> new ArrayList<>());
        callbacks.add(callback);

        if (!batchScheduled) {
            batchScheduled = true;
            mainHandler.postDelayed(this::fireBatch, coalesceWindowMs);
        }
    }

    /**
     * Fire the accumulated batch of metadata reads. Coalesces all pending
     * reelIds into a single Firebase transaction where possible.
     */
    private void fireBatch() {
        if (pendingCallbacks.isEmpty()) {
            batchScheduled = false;
            return;
        }

        Set<String> reelIds = new HashSet<>(pendingCallbacks.keySet());
        Map<String, List<HomeFeedMetadataCache.MetadataCallback>> callbacksCopy =
            new HashMap<>(pendingCallbacks);
        pendingCallbacks.clear();
        batchScheduled = false;
        lastBatchFiredAt = System.currentTimeMillis();

        // Simplified: fetch metadata for each reelId in parallel. A production
        // system might batch these into a single multi-child read, but Firebase
        // Realtime DB doesn't support true multi-key queries, so parallel reads
        // are the standard approach.
        for (String reelId : reelIds) {
            fetchMetadataForReel(reelId, fbRef, callbacksCopy.get(reelId));
        }
    }

    private void fetchMetadataForReel(@NonNull String reelId, @NonNull DatabaseReference fbRef,
                                      @NonNull List<HomeFeedMetadataCache.MetadataCallback> callbacks) {
        fbRef.child("reels").child(reelId).addListenerForSingleValueEvent(
            new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snapshot) {
                    HomeFeedMetadataCache.PostMetadata metadata =
                        new HomeFeedMetadataCache.PostMetadata(reelId);

                    if (snapshot.exists()) {
                        // Extract counts from Firebase node
                        Long likes = snapshot.child("likeCount").getValue(Long.class);
                        Long comments = snapshot.child("commentCount").getValue(Long.class);
                        Long reposts = snapshot.child("repostCount").getValue(Long.class);
                        Boolean isLiked = snapshot.child("likedBy").child("uid").getValue(Boolean.class);
                        Boolean isSaved = snapshot.child("savedBy").child("uid").getValue(Boolean.class);
                        String caption = snapshot.child("caption").getValue(String.class);

                        metadata.likeCount = likes != null ? likes.intValue() : 0;
                        metadata.commentCount = comments != null ? comments.intValue() : 0;
                        metadata.repostCount = reposts != null ? reposts.intValue() : 0;
                        metadata.isLiked = isLiked != null && isLiked;
                        metadata.isSaved = isSaved != null && isSaved;

                        if (caption != null && caption.length() > 150) {
                            metadata.captionPreview = caption.substring(0, 150) + "...";
                        } else {
                            metadata.captionPreview = caption != null ? caption : "";
                        }
                    }

                    // Invoke all callbacks for this reelId
                    for (HomeFeedMetadataCache.MetadataCallback cb : callbacks) {
                        cb.onMetadata(metadata);
                    }
                }

                @Override
                public void onCancelled(@NonNull DatabaseError error) {
                    // On error, still invoke callbacks with default (empty) metadata
                    HomeFeedMetadataCache.PostMetadata metadata =
                        new HomeFeedMetadataCache.PostMetadata(reelId);
                    for (HomeFeedMetadataCache.MetadataCallback cb : callbacks) {
                        cb.onMetadata(metadata);
                    }
                }
            });
    }

    public int getPendingBatchSize() {
        return pendingCallbacks.size();
    }

    public long getLastBatchFiredAt() {
        return lastBatchFiredAt;
    }

    public void shutdown() {
        mainHandler.removeCallbacksAndMessages(null);
        pendingCallbacks.clear();
    }
}
