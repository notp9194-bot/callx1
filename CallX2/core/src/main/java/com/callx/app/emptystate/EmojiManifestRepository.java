package com.callx.app.emptystate;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.callx.app.utils.Constants;
import com.google.gson.Gson;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Fetches /api/emoji-packs/manifest from the server and keeps a lightweight
 * local copy (just the small JSON manifest — NOT the lottie files themselves,
 * those live in DiskCache via EmojiPackDownloadWorker).
 *
 * Same OkHttpClient timeout convention as PushNotify.java, kept as its own
 * instance so an emoji-pack fetch never competes with / blocks notification
 * calls.
 */
public class EmojiManifestRepository {

    private static final String TAG = "EmojiManifestRepo";
    private static final String PREFS = "emoji_manifest_prefs";
    private static final String KEY_MANIFEST_JSON = "manifest_json";
    private static final String KEY_MANIFEST_VERSION = "manifest_version";
    private static final String KEY_FETCHED_AT = "fetched_at";

    // Point 9 (smart fallback): don't refetch the manifest more than once
    // every 6h — the app already re-checks on every cold start via
    // EmojiPackDownloadWorker's WorkManager trigger, this just stops a
    // redundant network hit if the user relaunches the app repeatedly.
    private static final long MANIFEST_TTL_MS = TimeUnit.HOURS.toMillis(6);

    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();

    private final Gson gson = new Gson();
    private final SharedPreferences prefs;

    public EmojiManifestRepository(Context ctx) {
        prefs = ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Cached manifest, or null if never fetched / cache expired. Non-blocking. */
    public EmojiManifestModels.Manifest getCachedManifest() {
        long fetchedAt = prefs.getLong(KEY_FETCHED_AT, 0L);
        if (System.currentTimeMillis() - fetchedAt > MANIFEST_TTL_MS) return null;
        String json = prefs.getString(KEY_MANIFEST_JSON, null);
        if (json == null) return null;
        try {
            return gson.fromJson(json, EmojiManifestModels.Manifest.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Blocking network fetch — call ONLY from a background thread
     * (WorkManager worker / executor), never from the UI thread.
     */
    public EmojiManifestModels.Manifest fetchManifestBlocking() {
        Request req = new Request.Builder()
                .url(Constants.SERVER_URL + "/api/emoji-packs/manifest")
                .get()
                .build();
        try (Response resp = client.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                Log.w(TAG, "manifest fetch failed: HTTP " + resp.code());
                return null;
            }
            String body = resp.body().string();
            EmojiManifestModels.Manifest manifest =
                    gson.fromJson(body, EmojiManifestModels.Manifest.class);
            if (manifest != null) {
                prefs.edit()
                        .putString(KEY_MANIFEST_JSON, body)
                        .putInt(KEY_MANIFEST_VERSION, manifest.version)
                        .putLong(KEY_FETCHED_AT, System.currentTimeMillis())
                        .apply();
            }
            return manifest;
        } catch (IOException e) {
            Log.w(TAG, "manifest fetch error: " + e.getMessage());
            return null;
        }
    }

    static OkHttpClient httpClient() {
        return client;
    }
}
