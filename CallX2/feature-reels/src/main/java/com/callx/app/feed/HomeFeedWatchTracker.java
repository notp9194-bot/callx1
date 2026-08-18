package com.callx.app.feed;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.MutableData;
import com.google.firebase.database.Transaction;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * HomeFeedWatchTracker — engagement/watch bookkeeping for the Reels "Home"
 * tab feed, mirroring what ReelSocialController already does for the
 * full-screen swipe player.
 *
 * Before this existed, inline Home-feed playback was completely invisible to
 * the rest of the app: a reel could be watched start-to-finish in the feed
 * without ever incrementing viewsCount, landing in reelWatchHistory, or
 * writing reelWatchProgress — which is why Home's own "Continue Watching"
 * strip only ever filled up from the full-screen player.
 *
 * What it writes (all under the same Firebase paths the player uses, so the
 * two surfaces share one history):
 *  • reelViews/{reelId}/{uid} = true  + reels/{reelId}/viewsCount transaction
 *    — once per viewer per reel, after a real {@link #VIEW_DWELL_MS} dwell so
 *      a card that merely flashed past mid-fling never counts as a view.
 *  • reelWatchHistory/{uid}/{reelId} = timestamp — after a longer dwell, this
 *    is what "Continue Watching" reads.
 *  • reelWatchProgress/{uid}/{reelId} = percent watched (0–100), throttled to
 *    one write per {@link #PROGRESS_WRITE_INTERVAL_MS} while playing, reset to
 *    0 once the reel is effectively finished (same convention the player uses).
 *
 * Read side: {@link #preloadWatchProgress} pulls the whole progress map once
 * per feed load so resume-position lookups during scrolling are pure in-memory
 * hits instead of a Firebase read per card.
 */
public class HomeFeedWatchTracker {

    /** Dwell before a visible card counts as a "view". */
    private static final long VIEW_DWELL_MS    = 3_000L;
    /** Dwell before a card is written to watch history ("Continue Watching"). */
    private static final long HISTORY_DWELL_MS = 5_000L;
    /** Minimum gap between two reelWatchProgress writes for the same reel. */
    private static final long PROGRESS_WRITE_INTERVAL_MS = 4_000L;
    /** Percent past which a reel counts as finished (progress resets to 0). */
    private static final int  COMPLETE_PCT = 95;
    /** Resume window — below this it isn't worth resuming, above it the reel
     *  is basically finished and should restart from the top. */
    private static final int  RESUME_MIN_PCT = 5;
    private static final int  RESUME_MAX_PCT = 90;

    private final String  myUid;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** reelId → last known watched percent (server snapshot + local updates). */
    private final Map<String, Integer> progressPct     = new HashMap<>();
    /** reelId → SystemClock of the last reelWatchProgress write. */
    private final Map<String, Long>    lastWriteMs     = new HashMap<>();
    private final Set<String>          viewCounted     = new HashSet<>();
    private final Set<String>          historyMarked   = new HashSet<>();
    /** Reels whose saved position has already been applied this session — a
     *  reel is resumed once, then behaves normally if scrolled back to. */
    private final Set<String>          resumeConsumed  = new HashSet<>();

    private Runnable pendingView;
    private Runnable pendingHistory;
    private String   activeReelId;

    public HomeFeedWatchTracker(@Nullable String myUid) {
        this.myUid = myUid;
    }

    // ── Read side ─────────────────────────────────────────────────────────

    /**
     * One-shot read of reelWatchProgress/{uid} so every card's resume
     * position is available synchronously while rendering the feed.
     * {@code onLoaded} runs on the main thread, success or failure.
     */
    public void preloadWatchProgress(@Nullable Runnable onLoaded) {
        if (myUid == null) { if (onLoaded != null) onLoaded.run(); return; }
        FirebaseUtils.getReelWatchProgressRef(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    for (DataSnapshot child : snap.getChildren()) {
                        Integer pct = child.getValue(Integer.class);
                        if (child.getKey() != null && pct != null) {
                            progressPct.put(child.getKey(), pct);
                        }
                    }
                    if (onLoaded != null) onLoaded.run();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    if (onLoaded != null) onLoaded.run();
                }
            });
    }

    /** Saved watched-percent for a reel, or 0 when unknown. */
    public int savedProgressPct(@Nullable String reelId) {
        if (reelId == null) return 0;
        Integer pct = progressPct.get(reelId);
        return pct != null ? pct : 0;
    }

    /**
     * Resume position in millis for a reel that was left partway through, or
     * 0 when it shouldn't resume (never watched, barely watched, basically
     * finished, or already resumed once this session). Consumes the resume —
     * a second call for the same reel returns 0.
     */
    public long consumeResumePositionMs(@Nullable String reelId, long durationMs) {
        if (reelId == null || durationMs <= 0) return 0L;
        if (resumeConsumed.contains(reelId)) return 0L;
        resumeConsumed.add(reelId);
        int pct = savedProgressPct(reelId);
        if (pct < RESUME_MIN_PCT || pct > RESUME_MAX_PCT) return 0L;
        return (long) (durationMs * (pct / 100f));
    }

    // ── Write side ────────────────────────────────────────────────────────

    /**
     * Called when a feed card becomes the actively playing one. Starts the
     * view/history dwell timers; both are cancelled by {@link #onCardInactive}
     * if the user scrolls away first.
     */
    public void onCardActive(@Nullable String reelId) {
        cancelPendingTimers();
        activeReelId = reelId;
        if (myUid == null || reelId == null) return;

        if (!viewCounted.contains(reelId)) {
            pendingView = () -> recordView(reelId);
            handler.postDelayed(pendingView, VIEW_DWELL_MS);
        }
        if (!historyMarked.contains(reelId)) {
            pendingHistory = () -> markWatchHistory(reelId);
            handler.postDelayed(pendingHistory, HISTORY_DWELL_MS);
        }
    }

    /** Called when the active card stops playing (scrolled away, tab hidden,
     *  fragment paused). Cancels dwell timers that haven't fired yet. */
    public void onCardInactive() {
        cancelPendingTimers();
        activeReelId = null;
    }

    /**
     * Progress tick from the feed's playback ticker. Throttled internally, so
     * it is safe to call as often as the UI updates.
     */
    public void onPlaybackProgress(@Nullable String reelId, long positionMs, long durationMs) {
        if (myUid == null || reelId == null || durationMs <= 0) return;
        int pct = (int) Math.max(0, Math.min(100, positionMs * 100 / durationMs));
        if (pct >= COMPLETE_PCT) { onPlaybackCompleted(reelId); return; }

        progressPct.put(reelId, pct);
        Long last = lastWriteMs.get(reelId);
        long now  = System.currentTimeMillis();
        if (last != null && now - last < PROGRESS_WRITE_INTERVAL_MS) return;
        lastWriteMs.put(reelId, now);
        FirebaseUtils.getReelWatchProgressRef(myUid).child(reelId).setValue(pct);
    }

    /**
     * Reel watched to the end (or past {@link #COMPLETE_PCT}). Mirrors the
     * player's convention: history keeps the timestamp, progress goes back to
     * 0 so "Continue Watching" shows it as finished rather than half-done.
     */
    public void onPlaybackCompleted(@Nullable String reelId) {
        if (myUid == null || reelId == null) return;
        progressPct.put(reelId, 0);
        lastWriteMs.put(reelId, System.currentTimeMillis());
        FirebaseUtils.getReelWatchProgressRef(myUid).child(reelId).setValue(0);
        markWatchHistory(reelId);
        recordView(reelId);
    }

    /** Flush of the current position, e.g. on pause / fragment teardown. */
    public void flushProgress(@Nullable String reelId, long positionMs, long durationMs) {
        if (myUid == null || reelId == null || durationMs <= 0) return;
        lastWriteMs.remove(reelId); // bypass the throttle for this one write
        onPlaybackProgress(reelId, positionMs, durationMs);
    }

    public void release() {
        cancelPendingTimers();
        activeReelId = null;
    }

    @Nullable public String activeReelId() { return activeReelId; }

    // ── Internals ─────────────────────────────────────────────────────────

    private void cancelPendingTimers() {
        if (pendingView    != null) { handler.removeCallbacks(pendingView);    pendingView    = null; }
        if (pendingHistory != null) { handler.removeCallbacks(pendingHistory); pendingHistory = null; }
    }

    private void markWatchHistory(String reelId) {
        if (myUid == null || historyMarked.contains(reelId)) return;
        historyMarked.add(reelId);
        FirebaseUtils.getReelWatchHistoryRef(myUid).child(reelId)
            .setValue(System.currentTimeMillis());
    }

    /**
     * viewsCount is incremented once per viewer per reel, ever — guarded by
     * the permanent reelViews/{reelId}/{uid} marker, same as the full-screen
     * player, so watching the same reel in both surfaces can't double-count.
     */
    private void recordView(String reelId) {
        if (myUid == null || viewCounted.contains(reelId)) return;
        viewCounted.add(reelId);
        DatabaseReference viewRef = FirebaseUtils.getReelViewsRef(reelId).child(myUid);
        viewRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (snap.exists()) return;
                viewRef.setValue(true);
                FirebaseUtils.getReelsRef().child(reelId).child("viewsCount")
                    .runTransaction(new Transaction.Handler() {
                        @NonNull @Override
                        public Transaction.Result doTransaction(@NonNull MutableData data) {
                            Integer c = data.getValue(Integer.class);
                            data.setValue(c != null ? c + 1 : 1);
                            return Transaction.success(data);
                        }
                        @Override public void onComplete(@Nullable DatabaseError e,
                                                         boolean committed,
                                                         @Nullable DataSnapshot s) { }
                    });
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { }
        });
    }
}
