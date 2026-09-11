package com.callx.app.workers;

import android.content.Context;
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

import com.callx.app.cache.AvatarBatchPrefetcher;
import com.callx.app.cache.AvatarBinderCore;
import com.callx.app.cache.AvatarL2MemoryCache;
import com.callx.app.cache.AvatarL3DiskCache;
import com.callx.app.cache.ChatAvatarBinder;
import com.callx.app.cache.ChatAvatarL2Cache;
import com.callx.app.cache.ReelsAvatarL2Cache;
import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.ChatEntity;
import com.callx.app.utils.AvatarSizeTier;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 * It also warms recent DM-partner avatars into {@link ChatAvatarL2Cache} —
 * the cache ChatsFragment/ChatListAdapter actually read from — so a cold
 * open of the chat list gets the same zero-round-trip benefit. Unlike the
 * following/closeFriends path, DM targets need NO Firebase read at all:
 * ChatEntity already denormalizes partnerPhoto + partnerAvatarVersion for
 * every 1:1 chat row, so this reads straight out of Room.
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

    // Same courtesy-bound reasoning as MAX_TARGETS, applied to the DM side —
    // caps both the Room read size and the worker's own runtime; recent
    // chats first (getChatsPagedSync's own lastMessageAt DESC ordering)
    // covers the DMs someone is actually likely to reopen soon.
    private static final int MAX_DM_TARGETS = 40;

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

        Context appCtx = getApplicationContext();

        List<String> targets = new ArrayList<>();
        for (String uid : fetchUidChildrenSync(FirebaseUtils.getReelFollowsRef(myUid), MAX_TARGETS)) {
            if (!targets.contains(uid)) targets.add(uid);
        }
        for (String uid : fetchUidChildrenSync(FirebaseUtils.getUserRef(myUid).child("closeFriends"), MAX_TARGETS)) {
            if (targets.size() >= MAX_TARGETS) break;
            if (!targets.contains(uid)) targets.add(uid);
        }

        int reelsWarmed = warmReelsTargets(appCtx, targets);
        int dmWarmed = warmDmTargets(appCtx, new HashSet<>(targets));

        Log.d(TAG, "pre-warmed " + reelsWarmed + "/" + targets.size() + " following/closeFriends avatars + "
                + dmWarmed + " DM avatars (charging+unmetered+idle window)");
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
     * Following/closeFriends path: still needs one Firebase read per uid
     * (photoUrl + avatarVersion aren't denormalized anywhere reel-side),
     * but the actual avatar DOWNLOAD is now batched — AvatarBatchPrefetcher
     * composes each chunk of up to MAX_BATCH_SIZE avatars into a single
     * Cloudinary request instead of firing one Glide submit() per uid per
     * tier, for both the SMALL and TINY tiers this cache warms.
     */
    private int warmReelsTargets(Context appCtx, List<String> targets) {
        if (targets.isEmpty() || isStopped()) return 0;

        List<AvatarBatchPrefetcher.Item> items = new ArrayList<>(targets.size());
        for (String uid : targets) {
            if (isStopped()) break; // constraints stopped holding mid-run (e.g. unplugged) — bail cleanly, no partial-retry storm
            AvatarBatchPrefetcher.Item item = fetchProfileSync(uid);
            if (item != null) items.add(item);
        }
        if (items.isEmpty()) return 0;

        AvatarBinderCore.CacheProvider reelsCache = new AvatarBinderCore.CacheProvider() {
            @Override public AvatarL2MemoryCache l2(Context ctx) { return ReelsAvatarL2Cache.get(ctx); }
            @Override public AvatarL3DiskCache l3(Context ctx) { return ReelsAvatarL2Cache.l3(ctx); }
        };
        // AvatarPrefetcher / ReelUiController bind at exactly these two
        // tiers — same pair warmAvatar() used to warm one uid at a time.
        AvatarBatchPrefetcher.prefetchBatch(appCtx, items, AvatarSizeTier.SMALL, reelsCache);
        AvatarBatchPrefetcher.prefetchBatch(appCtx, items, AvatarSizeTier.TINY, reelsCache);
        return items.size();
    }

    /**
     * Targeted read of ONLY "photoUrl" + "avatarVersion" for one uid — two
     * scalar child reads, never the whole "users/{uid}" node.
     */
    private AvatarBatchPrefetcher.Item fetchProfileSync(String uid) {
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
        if (photoUrl == null || photoUrl.isEmpty()) return null;
        return new AvatarBatchPrefetcher.Item(photoUrl, versionHolder[0]);
    }

    /**
     * DM path: recent 1:1 chats straight from Room
     * ({@link com.callx.app.db.dao.ChatDao#getChatsPagedSync(int)}), filtered
     * to type=="private", deduped against uids already warmed via
     * following/closeFriends. ChatEntity's partnerPhoto + partnerAvatarVersion
     * are already denormalized onto the row — zero Firebase reads needed for
     * these targets, straight into a batched warm of ChatAvatarL2Cache/L3
     * (the SAME cache ChatsFragment/ChatListAdapter bind from, via
     * ChatAvatarBinder.tier() so this never drifts from their real bind tier).
     */
    private int warmDmTargets(Context appCtx, Set<String> alreadyWarmed) {
        if (isStopped()) return 0;

        List<ChatEntity> recentChats = AppDatabase.getInstance(appCtx).chatDao().getChatsPagedSync(MAX_DM_TARGETS);
        if (recentChats == null || recentChats.isEmpty()) return 0;

        List<AvatarBatchPrefetcher.Item> dmItems = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ChatEntity chat : recentChats) {
            if (dmItems.size() >= MAX_DM_TARGETS) break;
            if (chat == null || !"private".equals(chat.type)) continue;
            if (chat.partnerUid == null || chat.partnerUid.isEmpty()) continue;
            if (alreadyWarmed.contains(chat.partnerUid) || !seen.add(chat.partnerUid)) continue;
            if (chat.partnerPhoto == null || chat.partnerPhoto.isEmpty()) continue;
            dmItems.add(new AvatarBatchPrefetcher.Item(chat.partnerPhoto, chat.partnerAvatarVersion));
        }
        if (dmItems.isEmpty() || isStopped()) return 0;

        AvatarBinderCore.CacheProvider chatCache = new AvatarBinderCore.CacheProvider() {
            @Override public AvatarL2MemoryCache l2(Context ctx) { return ChatAvatarL2Cache.get(ctx); }
            @Override public AvatarL3DiskCache l3(Context ctx) { return ChatAvatarL2Cache.l3(ctx); }
        };
        AvatarBatchPrefetcher.prefetchBatch(appCtx, dmItems, ChatAvatarBinder.tier(), chatCache);
        return dmItems.size();
    }
}
