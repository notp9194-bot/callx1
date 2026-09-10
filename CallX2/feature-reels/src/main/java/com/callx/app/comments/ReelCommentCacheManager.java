package com.callx.app.comments;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.ReelCommentCacheEntity;
import com.callx.app.models.ReelComment;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ReelCommentCacheManager — offline-first warm-start for the Reel comments
 * sheet (ReelCommentFragment).
 *
 * Room-backed, same shape as TrendingAudioCacheManager: the last live
 * window of comments is persisted here so reopening the sheet (even fully
 * offline) can paint immediately from disk while the real Firebase
 * ChildEventListener runs in the background and transparently replaces it
 * once the first burst settles (see ReelCommentFragment#requestRefresh()).
 * This is purely a paint layer — it never touches allComments/
 * loadedCommentIds, so the real data flow is unaffected.
 *
 * Only CONFIRMED comments (ReelComment#sendState == null, i.e. actually
 * synced from Firebase) are ever cached — locally-pending/failed optimistic
 * rows are excluded, since caching an unsent comment would resurrect it on
 * next open even if it never actually made it to the server.
 */
final class ReelCommentCacheManager {

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Gson GSON = new Gson();
    private static final Type MAP_BOOL_TYPE = new TypeToken<Map<String, Boolean>>() {}.getType();
    private static final Type MAP_STR_TYPE  = new TypeToken<Map<String, String>>() {}.getType();

    /** How many comments to cache per reel — a bit above PAGE_SIZE so a
     *  couple of paged-in-older comments survive too. */
    private static final int MAX_CACHE_SIZE = 40;
    private static final long MAX_AGE_MS = 3L * 24 * 60 * 60 * 1000; // 3 days — comments churn fast

    interface Callback {
        void onLoaded(List<ReelComment> cached);
    }

    private ReelCommentCacheManager() {}

    /** Fire-and-forget: replaces the cached window for this reel. */
    static void savePage(Context ctx, String reelId, List<ReelComment> comments) {
        if (reelId == null || reelId.isEmpty() || comments == null || comments.isEmpty()) return;
        Context appCtx = ctx.getApplicationContext();
        // Snapshot on the caller's thread — allComments is mutated on the
        // main thread only, so this copy is safe to hand off to IO.
        List<ReelComment> snapshot = new ArrayList<>(comments);
        IO.execute(() -> {
            try {
                AppDatabase db = AppDatabase.getInstance(appCtx);
                List<ReelCommentCacheEntity> rows = new ArrayList<>();
                int order = 0;
                for (ReelComment c : snapshot) {
                    if (c == null || c.commentId == null || c.commentId.isEmpty()) continue;
                    if (c.sendState != null) continue; // skip local pending/failed rows
                    if (order >= MAX_CACHE_SIZE) break;
                    ReelCommentCacheEntity e = new ReelCommentCacheEntity();
                    e.reelId        = reelId;
                    e.commentId     = c.commentId;
                    e.uid           = c.uid;
                    e.ownerName     = c.ownerName;
                    e.ownerPhoto    = c.ownerPhoto;
                    e.text          = c.text;
                    e.imageUrl      = c.imageUrl;
                    e.timestamp     = c.timestamp;
                    e.likesCount    = c.likesCount;
                    e.replyCount    = c.replyCount;
                    e.avatarVersion = c.avatarVersion;
                    e.isPinned      = c.isPinned;
                    e.isEdited      = c.isEdited;
                    e.editedAt      = c.editedAt;
                    e.likedByJson   = c.likedBy   != null ? GSON.toJson(c.likedBy)   : null;
                    e.reactionsJson = c.reactions != null ? GSON.toJson(c.reactions) : null;
                    e.mentionsJson  = c.mentions  != null ? GSON.toJson(c.mentions)  : null;
                    e.sortOrder     = order++;
                    rows.add(e);
                }
                if (rows.isEmpty()) return;
                db.reelCommentCacheDao().clearForReel(reelId);
                db.reelCommentCacheDao().insertAll(rows);
                db.reelCommentCacheDao().pruneOlderThan(System.currentTimeMillis() - MAX_AGE_MS);
            } catch (Exception ignored) {
                // Non-critical — sheet still works from Firebase, just without the offline warm-start.
            }
        });
    }

    /** Async read — callback fires on the main thread. */
    static void loadPageAsync(Context ctx, String reelId, Callback callback) {
        if (reelId == null || reelId.isEmpty() || callback == null) return;
        Context appCtx = ctx.getApplicationContext();
        IO.execute(() -> {
            List<ReelComment> out = loadPageBlocking(appCtx, reelId);
            MAIN.post(() -> callback.onLoaded(out));
        });
    }

    /** Synchronous read — call only from a background thread. */
    private static List<ReelComment> loadPageBlocking(Context ctx, String reelId) {
        List<ReelComment> out = new ArrayList<>();
        try {
            AppDatabase db = AppDatabase.getInstance(ctx.getApplicationContext());
            List<ReelCommentCacheEntity> rows = db.reelCommentCacheDao().getPage(reelId, MAX_CACHE_SIZE);
            for (ReelCommentCacheEntity e : rows) {
                ReelComment c = new ReelComment();
                c.commentId     = e.commentId;
                c.uid           = e.uid;
                c.ownerName     = e.ownerName;
                c.ownerPhoto    = e.ownerPhoto;
                c.text          = e.text;
                c.imageUrl      = e.imageUrl;
                c.timestamp     = e.timestamp;
                c.likesCount    = e.likesCount;
                c.replyCount    = e.replyCount;
                c.avatarVersion = e.avatarVersion;
                c.isPinned      = e.isPinned;
                c.isEdited      = e.isEdited;
                c.editedAt      = e.editedAt;
                try {
                    c.likedBy = e.likedByJson != null
                        ? GSON.<Map<String, Boolean>>fromJson(e.likedByJson, MAP_BOOL_TYPE)
                        : new HashMap<>();
                } catch (Exception ex) { c.likedBy = new HashMap<>(); }
                try {
                    c.reactions = e.reactionsJson != null
                        ? GSON.<Map<String, String>>fromJson(e.reactionsJson, MAP_STR_TYPE)
                        : new HashMap<>();
                } catch (Exception ex) { c.reactions = new HashMap<>(); }
                try {
                    c.mentions = e.mentionsJson != null
                        ? GSON.<Map<String, String>>fromJson(e.mentionsJson, MAP_STR_TYPE)
                        : null;
                } catch (Exception ex) { c.mentions = null; }
                out.add(c);
            }
        } catch (Exception ignored) {
            // Cache miss/corruption — caller falls back to the normal Firebase load.
        }
        return out;
    }
}
