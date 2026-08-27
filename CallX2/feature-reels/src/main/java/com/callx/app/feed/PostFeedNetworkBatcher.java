package com.callx.app.feed;

import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.callx.app.models.ReelModel;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PostFeedNetworkBatcher — HomeFeedNetworkBatcher's coalescing idea, scoped
 * to PostsFeedActivity's reads.
 *
 * Home's batcher coalesces small counts-only "metadata" reads. Posts feed
 * doesn't need that split — {@link com.callx.app.profile.PostsFeedActivity}
 * always reads the full {@link ReelModel} per row — but it had NO
 * coalescing at all: the initial load, the background "before" batch, and
 * every scroll-triggered window reload each fired their own uncoalesced
 * {@code addListenerForSingleValueEvent} per reelId. Fast-scrolling right
 * after opening the screen (initial load still in flight + a window
 * reload already firing) could request the same reelId twice within a few
 * milliseconds.
 *
 * Fix: requests for the same reelId arriving inside a short coalesce
 * window collapse into a single Firebase read; every caller's callback
 * still fires once the value comes back.
 */
public class PostFeedNetworkBatcher {

    public interface ReelCallback {
        void onReel(@Nullable ReelModel reel);
    }

    private final DatabaseReference reelsRootRef;
    private final int coalesceWindowMs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Map<String, List<ReelCallback>> pendingCallbacks = new HashMap<>();
    private volatile boolean batchScheduled = false;

    public PostFeedNetworkBatcher(@NonNull DatabaseReference reelsRootRef, int coalesceWindowMs) {
        this.reelsRootRef = reelsRootRef;
        this.coalesceWindowMs = coalesceWindowMs;
    }

    /**
     * Queue a single post's full-object read. If another read for the same
     * reelId is already queued in the current window, this callback rides
     * along with it instead of starting a second Firebase listener.
     */
    public void queueReelRead(@NonNull String reelId, @NonNull ReelCallback callback) {
        synchronized (pendingCallbacks) {
            List<ReelCallback> cbs = pendingCallbacks.get(reelId);
            if (cbs == null) {
                cbs = new ArrayList<>();
                pendingCallbacks.put(reelId, cbs);
            }
            cbs.add(callback);

            if (!batchScheduled) {
                batchScheduled = true;
                mainHandler.postDelayed(this::fireBatch, coalesceWindowMs);
            }
        }
    }

    private void fireBatch() {
        Map<String, List<ReelCallback>> toFire;
        synchronized (pendingCallbacks) {
            batchScheduled = false;
            if (pendingCallbacks.isEmpty()) return;
            toFire = new HashMap<>(pendingCallbacks);
            pendingCallbacks.clear();
        }
        for (Map.Entry<String, List<ReelCallback>> entry : toFire.entrySet()) {
            fetchOne(entry.getKey(), entry.getValue());
        }
    }

    private void fetchOne(@NonNull String reelId, @NonNull List<ReelCallback> callbacks) {
        reelsRootRef.child(reelId).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                ReelModel r = snap.getValue(ReelModel.class);
                if (r != null) r.reelId = snap.getKey();
                for (ReelCallback cb : callbacks) cb.onReel(r);
            }
            @Override public void onCancelled(@NonNull DatabaseError error) {
                for (ReelCallback cb : callbacks) cb.onReel(null);
            }
        });
    }

    public void shutdown() {
        mainHandler.removeCallbacksAndMessages(null);
        synchronized (pendingCallbacks) {
            pendingCallbacks.clear();
        }
    }
}
