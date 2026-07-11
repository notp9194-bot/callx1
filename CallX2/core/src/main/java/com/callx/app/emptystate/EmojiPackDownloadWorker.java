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

import com.callx.app.cache.LottieAssetCache;
import com.callx.app.utils.Constants;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.List;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Plan points 7 + 8 + 10 (+ gaps #1/#2 fix pass):
 *   - Runs in the background whenever a chat is opened (enqueued from
 *     EmptyChatLottieController — see enqueue()) — this IS the "prefetch
 *     when the pack surface is opened" trigger, not a blind global timer.
 *   - Downloads the manifest, then any emoji lottie JSON not already sitting
 *     in LottieAssetCache — a PERMANENT, dedicated cache (gap #1) that is
 *     never evicted by ordinary media traffic the way the shared 200MB/
 *     7-day DiskCache was.
 *   - Prefers each entry's gzUrl (TGS-style gzip transport, gap #2) when the
 *     server provides one; decompresses locally, then sha256-verifies the
 *     DECOMPRESSED bytes before caching, so a corrupt/partial/truncated
 *     download can never get served to the UI. Falls back to the plain
 *     `url` field for older manifests that don't have gzUrl yet.
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

        LottieAssetCache lottieCache = LottieAssetCache.getInstance(ctx);
        int downloaded = 0, skipped = 0, failed = 0;

        for (EmojiManifestModels.Entry entry : manifest.emojis) {
            if (entry.isDefault) continue; // already bundled in the APK, never re-download
            String cacheKey = CACHE_KEY_PREFIX + entry.id;
            if (lottieCache.exists(cacheKey)) {
                skipped++;
                continue;
            }
            if (downloadOne(ctx, lottieCache, entry, cacheKey)) {
                downloaded++;
            } else {
                failed++;
            }
        }

        Log.i(TAG, "emoji sync done: downloaded=" + downloaded + " skipped=" + skipped + " failed=" + failed);
        return Result.success();
    }

    private boolean downloadOne(Context ctx, LottieAssetCache lottieCache,
                                 EmojiManifestModels.Entry entry, String cacheKey) {
        boolean isGzip = entry.gzUrl != null && !entry.gzUrl.isEmpty();
        String rawUrl = isGzip ? entry.gzUrl : entry.url;
        if (rawUrl == null || rawUrl.isEmpty()) return false;
        String url = rawUrl.startsWith("http") ? rawUrl : Constants.SERVER_URL + rawUrl;
        Request req = new Request.Builder().url(url).get().build();

        try (Response resp = EmojiManifestRepository.httpClient().newCall(req).execute()) {
            if (!resp.isSuccessful()) return false;
            ResponseBody body = resp.body();
            if (body == null) return false;

            byte[] wireBytes = body.bytes();

            // Gap #2: gzUrl bytes arrive compressed — decompress before any
            // of the size-budget / sha256 checks below, since those are
            // always defined against the plain JSON.
            byte[] jsonBytes = isGzip ? lottieCache.decompressGzip(wireBytes) : wireBytes;
            if (jsonBytes == null) {
                Log.w(TAG, "emoji " + entry.id + " gzip decompress failed, discarding");
                return false;
            }

            // Point 12: JSON optimization rule — cap at ~40KB per emoji so a
            // bad server entry never balloons the disk cache.
            if (jsonBytes.length > 40 * 1024) {
                Log.w(TAG, "emoji " + entry.id + " exceeds size budget (" + jsonBytes.length + "B), skipping");
                return false;
            }

            if (entry.sha256 != null && !entry.sha256.isEmpty() && !sha256Matches(jsonBytes, entry.sha256)) {
                Log.w(TAG, "emoji " + entry.id + " failed sha256 check, discarding");
                return false;
            }

            return lottieCache.savePlain(cacheKey, jsonBytes);
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
