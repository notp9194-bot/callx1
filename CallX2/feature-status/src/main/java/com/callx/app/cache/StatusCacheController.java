package com.callx.app.cache;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import androidx.work.WorkManager;
import com.callx.app.services.StatusMediaPrefetchWorker;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * StatusCacheController — lifecycle of the status caches.
 *  • onAuthChanged(): sign-out / account switch → wipe everything account-specific (reel header cache, tracked status
 *    videos, prefetch worker); sign-in → (re)schedule the worker. Called from CallxApp's auth listener.
 *  • clearMedia(): the user-facing "Clear status cache" button.
 */
public final class StatusCacheController {
    private StatusCacheController() {}
    private static final Executor IO = Executors.newSingleThreadExecutor();

    public static void onAuthChanged(Context ctx, String uidOrNull) {
        if (ctx == null) return;
        final Context app = ctx.getApplicationContext();
        final String cur = uidOrNull == null ? "" : uidOrNull;
        final String last = StatusCachePrefs.getLastUid(app);
        if (cur.equals(last)) return;
        StatusCachePrefs.setLastUid(app, cur);
        if (!last.isEmpty()) clearAll(app, null);                       // signed out or switched account
        if (!cur.isEmpty()) StatusMediaPrefetchWorker.schedule(app);    // signed in
    }

    public static void clearAll(final Context app, final Runnable done) {
        IO.execute(() -> {
            try { ReelCardMetaCache.OWNER.clear(); ReelCardMetaCache.EXISTS.clear(); ReelCardMetaCache.DURATION.clear(); } catch (Exception ignored) {}
            StatusMediaManifest.clearAll(app);
            try { WorkManager.getInstance(app).cancelUniqueWork(StatusMediaPrefetchWorker.UNIQUE_NAME); } catch (Exception ignored) {}
            if (done != null) new Handler(Looper.getMainLooper()).post(done);
        });
    }

    /** "Clear status cache": status videos we track. Keeps account state and the worker. */
    public static void clearMedia(final Context ctx, final Runnable done) {
        final Context app = ctx.getApplicationContext();
        IO.execute(() -> {
            StatusMediaManifest.clearAll(app);
            if (done != null) new Handler(Looper.getMainLooper()).post(done);
        });
    }
}
