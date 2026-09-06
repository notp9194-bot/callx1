package com.callx.app.utils;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.MediaHashCacheEntity;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * WhatsApp-style content-hash media dedup, called from
 * CloudinaryUploader#upload right before it would otherwise sign+upload.
 *
 * ── THREE TIERS, fastest first ──────────────────────────────────────────
 *
 *  TIER 0 — IN-MEMORY (process-lifetime LRU, ~200 entries). Zero I/O at
 *  all — a plain synchronized LinkedHashMap lookup. Covers the hottest
 *  case: sending the same file to several chats back-to-back in one
 *  session (e.g. multi-forward), where even a Room/SQLite read would be
 *  needless overhead.
 *
 *  TIER 1 — LOCAL (Room, this device, survives process death). One
 *  indexed primary-key read on a WAL-mode DB — fast, but still real I/O,
 *  so it's only checked on a tier-0 miss.
 *
 *  TIER 2 — SERVER (Firebase-backed, shared across every user). Same file
 *  already uploaded by ANYONE on ANY device reuses that URL. This tier is
 *  MERGED into the existing /cloudinary/sign request instead of being a
 *  separate call — see CloudinaryUploader#upload, which sends the content
 *  hash alongside the normal sign payload and gets a `dedup:true/false`
 *  flag back in the SAME response. That collapses what would otherwise be
 *  two sequential network round trips (a dedup check, then the sign call
 *  it needed regardless) into exactly one — the biggest single latency
 *  win available here, since it applies to every upload, hit or miss.
 *
 * A miss on tiers 0+1 and a `dedup:false` sign response falls through to
 * the normal upload; on success CloudinaryUploader calls {@link #register}
 * so the NEXT identical file — from this device or any other — hits one
 * of the tiers above instead of re-uploading. register() is fully
 * non-blocking: the in-memory tier is updated synchronously (cheap), and
 * both the Room write and the server call are handed off to
 * MediaDedupExecutor so they never delay the upload's success callback.
 *
 * NOT used for E2E-encrypted media: ciphertext is unique per recipient
 * key by design there (see ChatMediaController's audio/image/video E2E
 * paths), so identical plaintext never produces identical uploaded bytes
 * to dedup against — that's inherent to per-recipient encryption, not a
 * gap in this cache.
 */
public final class MediaDedupManager {
    private static final String TAG = "MediaDedup";

    private static final int MEMORY_CACHE_MAX = 200;

    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

    /** Tier 0 — bounded LRU, evicts the least-recently-used entry past MEMORY_CACHE_MAX. */
    private static final Map<String, Result> memoryCache =
            new LinkedHashMap<String, Result>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Result> eldest) {
                    return size() > MEMORY_CACHE_MAX;
                }
            };

    // Short-lived in-memory ID-token cache — FirebaseAuth already caches the
    // token itself and getIdToken(false) doesn't force a network refresh,
    // but this skips even the Tasks.await() hop for back-to-back registers
    // in the same burst (e.g. a multi-image group upload finishing at once).
    private static volatile String cachedIdToken;
    private static volatile long cachedIdTokenExpiryMs;
    private static final long ID_TOKEN_CACHE_MS = 45 * 60 * 1000; // Firebase ID tokens last ~60 min

    private MediaDedupManager() {}

    /** Plain data holder — mirrors the fields CloudinaryUploader.Result actually populates. */
    public static class Result {
        public String source; // "memory" | "local" | "server" — logging only, never persisted
        public String secureUrl;
        public String thumbnailUrl;
        public String publicId;
        public String resourceType;
        public String format;
        public Long bytes;
        public Long durationMs;

        public CloudinaryUploader.Result toUploadResult() {
            CloudinaryUploader.Result r = new CloudinaryUploader.Result();
            r.secureUrl     = secureUrl;
            r.thumbnailUrl  = thumbnailUrl;
            r.publicId      = publicId;
            r.resourceType  = resourceType;
            r.format        = format;
            r.bytes         = bytes;
            r.durationMs    = durationMs;
            return r;
        }
    }

    /**
     * Tiers 0+1 ONLY — no network. BLOCKING on Room I/O for a tier-0 miss,
     * so call from a background thread (CloudinaryUploader already runs
     * its whole upload() body on one). Returns null on a miss in both.
     *
     * Tier 2 (server) is intentionally NOT checked here — it's merged into
     * the /cloudinary/sign request instead (see the class doc above), so
     * CloudinaryUploader only ever needs this call for the free, local
     * part of the check before it goes on to build that sign request.
     */
    @WorkerThread
    @Nullable
    public static Result lookupLocal(Context ctx, String hash) {
        if (hash == null || hash.isEmpty()) return null;

        synchronized (memoryCache) {
            Result mem = memoryCache.get(hash);
            if (mem != null) {
                Result r = copy(mem);
                r.source = "memory";
                return r;
            }
        }

        try {
            MediaHashCacheEntity e = AppDatabase.getInstance(ctx).mediaHashCacheDao().getByHash(hash);
            if (e != null && e.secureUrl != null && !e.secureUrl.isEmpty()) {
                Result r = fromEntity(e);
                r.source = "local";
                putMemory(hash, r); // warm tier 0 so the next hit in this session skips Room entirely
                return r;
            }
        } catch (Exception ex) {
            Log.w(TAG, "local dedup lookup failed: " + ex.getMessage());
        }
        return null;
    }

    /**
     * Parses the `result` object embedded in a /cloudinary/sign response
     * that came back with `dedup:true`. Never touches the network itself —
     * the round trip already happened as part of the sign call.
     */
    @Nullable
    public static Result fromSignResponse(@Nullable JSONObject dedupResult) {
        if (dedupResult == null) return null;
        Result r = fromServerJson(dedupResult);
        if (r.secureUrl == null || r.secureUrl.isEmpty()) return null;
        r.source = "server";
        return r;
    }

    /**
     * Records a freshly-uploaded (or server-dedup-resolved) file's hash →
     * URL mapping so the NEXT identical file — this device or any other —
     * can skip re-uploading. Fully non-blocking: the in-memory tier is
     * updated synchronously (cheap, no I/O), and both the Room write and
     * the server register call run on MediaDedupExecutor so this method
     * returns immediately and never delays an upload's success callback.
     */
    public static void register(Context ctx, String hash, CloudinaryUploader.Result r, @Nullable String folder) {
        if (hash == null || hash.isEmpty() || r == null || r.secureUrl == null || r.secureUrl.isEmpty()) return;

        Result cacheResult = new Result();
        cacheResult.secureUrl    = r.secureUrl;
        cacheResult.thumbnailUrl = r.thumbnailUrl;
        cacheResult.publicId     = r.publicId;
        cacheResult.resourceType = r.resourceType;
        cacheResult.format       = r.format;
        cacheResult.bytes        = r.bytes;
        cacheResult.durationMs   = r.durationMs;
        putMemory(hash, cacheResult); // instant — covers the very next send in this session

        MediaDedupExecutor.execute(() -> saveLocal(ctx, hash, cacheResult));

        MediaDedupExecutor.execute(() -> {
            try {
                String idToken = getIdTokenCached();
                if (idToken == null) return;
                JSONObject body = new JSONObject()
                        .put("hash", hash)
                        .put("secureUrl", r.secureUrl)
                        .put("thumbnailUrl", opt(r.thumbnailUrl))
                        .put("publicId", opt(r.publicId))
                        .put("resourceType", opt(r.resourceType))
                        .put("format", opt(r.format))
                        .put("bytes", r.bytes == null ? JSONObject.NULL : r.bytes)
                        .put("durationMs", r.durationMs == null ? JSONObject.NULL : r.durationMs)
                        .put("folder", opt(folder));
                Request req = new Request.Builder()
                        .url(Constants.SERVER_URL + "/media/dedup-register")
                        .header("Authorization", "Bearer " + idToken)
                        .post(RequestBody.create(body.toString(),
                                MediaType.parse("application/json; charset=utf-8")))
                        .build();
                try (Response resp = client.newCall(req).execute()) {
                    if (!resp.isSuccessful()) {
                        Log.w(TAG, "server dedup register failed (" + resp.code() + ")");
                    }
                }
            } catch (Exception ex) {
                // Best-effort — worst case another device's next identical
                // upload just re-uploads once more instead of reusing this URL.
                Log.w(TAG, "server dedup register error: " + ex.getMessage());
            }
        });
    }

    private static void putMemory(String hash, Result r) {
        synchronized (memoryCache) {
            memoryCache.put(hash, r);
        }
    }

    private static Object opt(String s) {
        return (s == null || s.isEmpty()) ? JSONObject.NULL : s;
    }

    @WorkerThread
    private static void saveLocal(Context ctx, String hash, Result r) {
        try {
            MediaHashCacheEntity e = new MediaHashCacheEntity();
            e.hash         = hash;
            e.secureUrl    = r.secureUrl;
            e.thumbnailUrl = r.thumbnailUrl;
            e.publicId     = r.publicId;
            e.resourceType = r.resourceType;
            e.format       = r.format;
            e.bytes        = r.bytes;
            e.durationMs   = r.durationMs;
            e.cachedAt     = System.currentTimeMillis();
            AppDatabase.getInstance(ctx).mediaHashCacheDao().insert(e);
        } catch (Exception ex) {
            Log.w(TAG, "local dedup save failed: " + ex.getMessage());
        }
    }

    private static Result copy(Result src) {
        Result r = new Result();
        r.secureUrl    = src.secureUrl;
        r.thumbnailUrl = src.thumbnailUrl;
        r.publicId     = src.publicId;
        r.resourceType = src.resourceType;
        r.format       = src.format;
        r.bytes        = src.bytes;
        r.durationMs   = src.durationMs;
        return r;
    }

    private static Result fromEntity(MediaHashCacheEntity e) {
        Result r = new Result();
        r.secureUrl    = e.secureUrl;
        r.thumbnailUrl = e.thumbnailUrl;
        r.publicId     = e.publicId;
        r.resourceType = e.resourceType;
        r.format       = e.format;
        r.bytes        = e.bytes;
        r.durationMs   = e.durationMs;
        return r;
    }

    private static Result fromServerJson(JSONObject j) {
        Result r = new Result();
        r.secureUrl    = j.optString("secureUrl", null);
        r.thumbnailUrl = j.optString("thumbnailUrl", null);
        r.publicId     = j.optString("publicId", null);
        r.resourceType = j.optString("resourceType", null);
        r.format       = j.optString("format", null);
        if (j.has("bytes") && !j.isNull("bytes")) r.bytes = j.optLong("bytes");
        if (j.has("durationMs") && !j.isNull("durationMs")) r.durationMs = j.optLong("durationMs");
        return r;
    }

    @Nullable
    private static String getIdTokenCached() {
        String tok = cachedIdToken;
        if (tok != null && System.currentTimeMillis() < cachedIdTokenExpiryMs) {
            return tok;
        }
        try {
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            if (user == null) return null;
            String fresh = Tasks.await(user.getIdToken(false), 8, TimeUnit.SECONDS).getToken();
            cachedIdToken = fresh;
            cachedIdTokenExpiryMs = System.currentTimeMillis() + ID_TOKEN_CACHE_MS;
            return fresh;
        } catch (Exception e) {
            return null;
        }
    }
}
