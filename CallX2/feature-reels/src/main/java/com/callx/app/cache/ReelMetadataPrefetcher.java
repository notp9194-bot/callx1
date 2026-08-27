package com.callx.app.cache;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.callx.app.models.ReelModel;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ReelMetadataPrefetcher — one-shot social-metadata warm-up for the reel(s)
 * just ahead of the one currently visible.
 *
 * GAP THIS FIXES:
 * ReelPredictivePreloader / ReelVideoPreloader / ReelThumbnailPreloader all
 * warm the *video* for upcoming reels, but nothing warmed the *social*
 * metadata (like/save/follow/repost/counts). ReelSocialController only ever
 * reads that data when a reel actually becomes visible (see
 * startFirebaseListeners()) — so the first time you land on any reel, even
 * one whose video was already prefetched, its like/comment/share counts
 * still flash from zero until 5 separate addValueEventListener calls
 * resolve. ReelMetadataCache already removes that flash on *revisits*; this
 * class removes it on *first visits* too, by front-running the 5 reads with
 * cheap one-shot (addListenerForSingleValueEvent) fetches while the
 * previous reel is still on screen, so the cache is already warm by the
 * time the user actually swipes there.
 *
 * Deliberately narrow in scope — ViewPager2 here only keeps a couple of
 * fragments alive (unlike Home's 15–20 row RecyclerView), so this only
 * looks one reel ahead by default and never attaches a live listener itself;
 * ReelSocialController's own startFirebaseListeners() still owns correctness
 * and will overwrite this the instant the reel becomes visible.
 */
public final class ReelMetadataPrefetcher {

    private static volatile ReelMetadataPrefetcher instance;

    public static ReelMetadataPrefetcher getInstance() {
        if (instance == null) {
            synchronized (ReelMetadataPrefetcher.class) {
                if (instance == null) instance = new ReelMetadataPrefetcher();
            }
        }
        return instance;
    }

    private ReelMetadataPrefetcher() {}

    // Dedupe: don't fire a second round of one-shot reads for a reelId
    // that's already cached or already has a prefetch in flight.
    private final Set<String> inFlight =
        Collections.newSetFromMap(new ConcurrentHashMap<>());

    /**
     * Warm the metadata cache for one upcoming reel, if it isn't already
     * cached or already being fetched. Safe to call on every page-select —
     * it's a no-op fast path once a reel has been warmed once.
     */
    public void prefetch(@Nullable ReelModel reel) {
        if (reel == null || reel.reelId == null) return;
        final String reelId = reel.reelId;

        if (ReelMetadataCache.getInstance().get(reelId) != null) return; // already warm
        if (!inFlight.add(reelId)) return; // already in flight

        String myUid = FirebaseUtils.getCurrentUid();
        if (myUid == null) {
            inFlight.remove(reelId);
            return;
        }

        ReelMetadataCache.Snapshot snap = new ReelMetadataCache.Snapshot(reelId);
        // 5 one-shot reads: like, save, follow, repost, counts. Counted down
        // so the cache write happens once, after the last one lands, rather
        // than 5 racy partial writes.
        AtomicInteger remaining = new AtomicInteger(5);

        Runnable maybeFinish = () -> {
            if (remaining.decrementAndGet() == 0) {
                snap.followCheckLoaded = true;
                // Don't clobber a fresher snapshot a live listener may have
                // already written while this prefetch was in flight.
                if (ReelMetadataCache.getInstance().get(reelId) == null) {
                    ReelMetadataCache.getInstance().put(reelId, snap);
                }
                inFlight.remove(reelId);
            }
        };

        FirebaseUtils.getReelLikesRef(reelId).child(myUid)
            .addListenerForSingleValueEvent(oneShot(s -> snap.isLiked = s.exists(), maybeFinish));

        FirebaseUtils.getReelSavesRef(myUid).child(reelId)
            .addListenerForSingleValueEvent(oneShot(s -> snap.isSaved = s.exists(), maybeFinish));

        if (reel.uid != null && !reel.uid.equals(myUid)) {
            FirebaseUtils.getReelFollowsRef(myUid).child(reel.uid)
                .addListenerForSingleValueEvent(oneShot(s -> snap.isFollowing = s.exists(), maybeFinish));
        } else {
            maybeFinish.run();
        }

        FirebaseUtils.db().getReference("reelReposts").child(reelId).child(myUid)
            .addListenerForSingleValueEvent(oneShot(s -> snap.isReposted = s.exists(), maybeFinish));

        FirebaseUtils.getReelsRef().child(reelId)
            .addListenerForSingleValueEvent(oneShot(s -> {
                Long likes    = s.child("likesCount").getValue(Long.class);
                Long comments = s.child("commentsCount").getValue(Long.class);
                Long shares   = s.child("sharesCount").getValue(Long.class);
                Long reposts  = s.child("repostCount").getValue(Long.class);
                if (likes    != null) snap.likeCount    = likes.intValue();
                if (comments != null) snap.commentCount = comments.intValue();
                if (shares   != null) snap.sharesCount  = shares.intValue();
                if (reposts  != null) snap.repostCount  = reposts.intValue();
            }, maybeFinish));
    }

    private interface SnapshotConsumer {
        void accept(DataSnapshot s);
    }

    private ValueEventListener oneShot(@NonNull SnapshotConsumer onValue, @NonNull Runnable onDone) {
        return new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snapshot) {
                onValue.accept(snapshot);
                onDone.run();
            }
            @Override public void onCancelled(@NonNull DatabaseError error) {
                onDone.run();
            }
        };
    }
}
