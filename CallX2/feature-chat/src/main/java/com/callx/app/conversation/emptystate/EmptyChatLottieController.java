package com.callx.app.conversation.emptystate;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.callx.app.cache.DiskCache;
import com.callx.app.emptystate.EmojiPackDownloadWorker;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Drives the empty-chat welcome animation (plan points 2-3-4-9-11-13).
 *
 * Wire-up in ChatActivity:
 *   controller = new EmptyChatLottieController(this, binding.rlottieEmptyChat,
 *                                               binding.tvEmptyEmojiFallback);
 *   ...at the existing isEmpty toggle (binding.llEmptyChat.setVisibility)...
 *   if (isEmpty) controller.onShown(); else controller.onHidden();
 *   ...
 *   onResume()  -> controller.onResume();
 *   onPause()   -> controller.onPause();
 *   onDestroy() -> controller.onDestroy();
 *
 * Behaviour:
 *   - Low-end device (ActivityManager#isLowRamDevice): RLottie never even
 *     initializes — the plain emoji TextView (already in the layout, zero
 *     cost) stays up permanently. Point 13.
 *   - Otherwise: emoji TextView shows instantly (point 9 — user never sees
 *     a blank/delayed state), while the default lottie asset decodes on a
 *     background thread; once ready it swaps in and the TextView hides.
 *   - EmojiPackDownloadWorker is enqueued (idempotent, WorkManager KEEP
 *     policy) so future non-default packs are ready ahead of time —
 *     point 7/10. This controller itself only ever renders the DEFAULT
 *     bundled emoji; wiring a per-chat/custom emoji picker is a follow-up,
 *     not part of this pass.
 */
public class EmptyChatLottieController {

    /** Point 6: the one emoji allowed to ship physically inside the APK. */
    private static final String DEFAULT_ASSET_NAME = "lottie/empty_chat_wave.json";
    private static final String DEFAULT_CACHE_KEY = EmojiPackDownloadWorker.CACHE_KEY_PREFIX + "wave_default";

    private static final ExecutorService IO_EXECUTOR = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Context appContext;
    private final RLottieViewWrapper lottieView;
    private final TextView fallbackEmoji;
    private final boolean lowEndDevice;

    private boolean loadedOnce = false;
    private boolean currentlyShown = false;

    public EmptyChatLottieController(@NonNull Context ctx,
                                      @NonNull RLottieViewWrapper lottieView,
                                      @NonNull TextView fallbackEmoji) {
        this.appContext = ctx.getApplicationContext();
        this.lottieView = lottieView;
        this.fallbackEmoji = fallbackEmoji;
        this.lowEndDevice = isLowRamDevice(appContext);

        // Point 7: kick off background sync of any non-default packs so
        // they're cached ahead of time, next cold start onward.
        if (!lowEndDevice) {
            EmojiPackDownloadWorker.enqueue(appContext);
        }
    }

    /** Call when ll_empty_chat becomes VISIBLE. */
    public void onShown() {
        currentlyShown = true;
        if (lowEndDevice) return; // TextView fallback is already the only thing in the layout path we use

        if (loadedOnce) {
            lottieView.resume();
            return;
        }

        // Instant state: fallback emoji already visible from the XML default.
        // Kick the (possibly slow, first-time asset decode) load off-thread.
        IO_EXECUTOR.execute(() -> {
            // A previously-downloaded, nicer default could live in DiskCache
            // (e.g. server pushed an updated default). Prefer that; else the
            // asset bundled in the APK.
            File cached = DiskCache.getInstance(appContext).get(DEFAULT_CACHE_KEY);

            mainHandler.post(() -> {
                if (!currentlyShown) return; // user already left the empty state / left the screen
                boolean loaded = (cached != null)
                        ? lottieView.loadFromFile(cached)
                        : lottieView.loadFromAsset(DEFAULT_ASSET_NAME);
                if (loaded) {
                    lottieView.setVisibility(View.VISIBLE);
                    fallbackEmoji.setVisibility(View.GONE);
                }
                // If loading failed, the fallback TextView (👋) stays visible
                // exactly as it was — nothing to do.
                loadedOnce = true;
            });
        });
    }

    /** Call when ll_empty_chat becomes GONE (real messages showed up / user left). */
    public void onHidden() {
        currentlyShown = false;
        if (!lowEndDevice && loadedOnce) lottieView.pause();
    }

    /** Point 11: lifecycle-aware pause — call from Activity#onPause(). */
    public void onPause() {
        if (!lowEndDevice && loadedOnce && currentlyShown) lottieView.pause();
    }

    /** Point 11: lifecycle-aware resume — call from Activity#onResume(). */
    public void onResume() {
        if (!lowEndDevice && loadedOnce && currentlyShown) lottieView.resume();
    }

    /** Call from Activity#onDestroy() — releases the native RLottie drawable. */
    public void onDestroy() {
        if (!lowEndDevice) lottieView.release();
    }

    private static boolean isLowRamDevice(Context ctx) {
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        return am != null && am.isLowRamDevice();
    }
}
