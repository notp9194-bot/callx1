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

import java.io.File;
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
        // NOTE: deliberately NOT using setExpedited() here — Worker (not
        // ListenableWorker/CoroutineWorker) doesn't override
        // getForegroundInfoAsync(), and expedited work on pre-Android-12
        // devices requires that or it throws at runtime. The real fix for
        // "picker still shows unicode right after opening a chat" is
        // downloadSingleBlocking() below, called on-demand from the
        // reaction picker itself — that doesn't wait on WorkManager's
        // scheduler at all.
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

    /**
     * On-demand single-entry fetch — used by the reaction-picker UI
     * (MessagePagingAdapter#showActionBottomSheet) to try to get one
     * specific animated pack right away instead of waiting for the next
     * WorkManager-scheduled background sync (which, under Doze/battery
     * optimization, can sit queued for minutes even with setExpedited()).
     * Blocking network + disk I/O — call off the UI thread only.
     *
     * @return the cached file on success (already-cached counts as
     *         success too), or null if the entry isn't in the manifest /
     *         download fails / no network.
     */
    public static File downloadSingleBlocking(Context ctx, String id) {
        return downloadSingleBlocking(ctx, id, null);
    }

    /**
     * Same as {@link #downloadSingleBlocking(Context, String)} but fills
     * {@code outReason[0]} with a short human-readable status — used by the
     * DEBUG-build reaction-picker summary dialog so it's obvious WHY a
     * given reaction stayed unicode instead of just silently failing.
     */
    public static File downloadSingleBlocking(Context ctx, String id, String[] outReason) {
        LottieAssetCache lottieCache = LottieAssetCache.getInstance(ctx);
        String cacheKey = CACHE_KEY_PREFIX + id;
        File existing = lottieCache.get(cacheKey);
        if (existing != null) {
            if (outReason != null) outReason[0] = "already cached";
            return existing;
        }

        EmojiManifestRepository repo = new EmojiManifestRepository(ctx);
        // BUG FIX: this used to try repo.getCachedManifest() first and only
        // hit the network if that was null. But getCachedManifest() happily
        // returns whatever was cached last time — including a manifest that
        // came back with an empty `emojis` array because the server folder
        // was misconfigured at the time. That empty result then sat valid
        // in SharedPrefs for up to 6h (MANIFEST_TTL), so even after the
        // server got fixed, this on-demand path kept silently reusing the
        // stale empty manifest and returning null for every id. Always go
        // through fetchManifestBlocking() instead — it's still cheap
        // (conditional GET with If-None-Match → 304 when nothing changed),
        // but it guarantees a fresh look whenever the id we need isn't in
        // what we have cached.
        EmojiManifestModels.Manifest manifest = repo.fetchManifestBlocking();
        if (manifest == null) {
            if (outReason != null) outReason[0] = "manifest fetch failed (network/server error)";
            return null;
        }
        if (manifest.emojis == null || manifest.emojis.length == 0) {
            if (outReason != null) outReason[0] = "manifest has 0 emojis (server misconfigured?)";
            return null;
        }

        for (EmojiManifestModels.Entry entry : manifest.emojis) {
            if (entry.isDefault) continue;
            if (id.equals(entry.id)) {
                if (!downloadOne(ctx, lottieCache, entry, cacheKey)) {
                    if (outReason != null) outReason[0] = "download/sha256-verify failed for url=" + entry.url;
                    return null;
                }
                if (outReason != null) outReason[0] = "downloaded fresh, ok";
                return lottieCache.get(cacheKey);
            }

        }
        if (outReason != null) outReason[0] = "id '" + id + "' not present in manifest (server deploy check)";
        return null; // server manifest doesn't have this id (yet) — deploy check
    }

    private static boolean downloadOne(Context ctx, LottieAssetCache lottieCache,
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

    private static boolean sha256Matches(byte[] data, String expectedHex) {
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
