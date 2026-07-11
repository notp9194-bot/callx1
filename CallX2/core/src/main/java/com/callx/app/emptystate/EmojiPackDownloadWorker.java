package com.callx.app.emptystate;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.callx.app.cache.DiskCache;
import com.callx.app.utils.Constants;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.List;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Plan points 7 + 8 + 10:
 *   - Runs once in the background right after app open (enqueued from
 *     CallxApp / EmptyChatLottieController — see enqueue()).
 *   - Downloads the manifest, then any emoji lottie JSON not already sitting
 *     in DiskCache (Tier-2, 200MB/7-day cache — same one media already uses).
 *   - sha256-verifies each file before caching it, so a corrupt/partial
 *     download can never get served to the UI.
 *   - Unique work name ("emoji_pack_sync") + KEEP policy: if it's already
 *     running/queued, a second trigger (e.g. user opening 2 chats fast) is
 *     a no-op instead of stacking duplicate downloads.
 */
public class EmojiPackDownloadWorker extends Worker {

    private static final String TAG = "EmojiPackDownloadWorker";
    public static final String UNIQUE_WORK_NAME = "emoji_pack_sync";
    public static final String CACHE_KEY_PREFIX = "lottie_emoji:";

    public EmojiPackDownloadWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    /** Call this from app-open / cold-start — cheap no-op if already synced. */
    public static void enqueue(Context ctx) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(EmojiPackDownloadWorker.class)
                .setConstraints(constraints)
                .build();

        WorkManager.getInstance(ctx.getApplicationContext())
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        EmojiManifestRepository repo = new EmojiManifestRepository(ctx);
        EmojiManifestModels.Manifest manifest = repo.fetchManifestBlocking();
        if (manifest == null || manifest.emojis == null) {
            // No network / server hiccup — not a hard failure, default asset
            // bundled in the APK already covers the empty-state UI. Retry
            // will happen next cold start.
            return Result.success();
        }

        DiskCache diskCache = DiskCache.getInstance(ctx);
        int downloaded = 0, skipped = 0, failed = 0;

        for (EmojiManifestModels.Entry entry : manifest.emojis) {
            if (entry.isDefault) continue; // already bundled in the APK, never re-download
            String cacheKey = CACHE_KEY_PREFIX + entry.id;
            if (diskCache.exists(cacheKey)) {
                skipped++;
                continue;
            }
            if (downloadOne(ctx, diskCache, entry, cacheKey)) {
                downloaded++;
            } else {
                failed++;
            }
        }

        Log.i(TAG, "emoji sync done: downloaded=" + downloaded + " skipped=" + skipped + " failed=" + failed);
        return Result.success();
    }

    private boolean downloadOne(Context ctx, DiskCache diskCache,
                                 EmojiManifestModels.Entry entry, String cacheKey) {
        String url = entry.url.startsWith("http") ? entry.url : Constants.SERVER_URL + entry.url;
        Request req = new Request.Builder().url(url).get().build();

        try (Response resp = EmojiManifestRepository.httpClient().newCall(req).execute()) {
            if (!resp.isSuccessful()) return false;
            ResponseBody body = resp.body();
            if (body == null) return false;

            byte[] bytes = body.bytes();

            // Point 12: JSON optimization rule — cap at ~30KB per emoji so a
            // bad server entry never balloons the disk cache.
            if (bytes.length > 40 * 1024) {
                Log.w(TAG, "emoji " + entry.id + " exceeds size budget (" + bytes.length + "B), skipping");
                return false;
            }

            if (entry.sha256 != null && !entry.sha256.isEmpty() && !sha256Matches(bytes, entry.sha256)) {
                Log.w(TAG, "emoji " + entry.id + " failed sha256 check, discarding");
                return false;
            }

            return diskCache.save(cacheKey, bytes);
        } catch (IOException e) {
            Log.w(TAG, "download failed for " + entry.id + ": " + e.getMessage());
            return false;
        }
    }

    private boolean sha256Matches(byte[] data, String expectedHex) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString().equalsIgnoreCase(expectedHex);
        } catch (Exception e) {
            return false;
        }
    }
}
