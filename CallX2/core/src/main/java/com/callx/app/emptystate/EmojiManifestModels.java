package com.callx.app.emptystate;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/**
 * Gson models for GET {SERVER_URL}/api/emoji-packs/manifest
 *
 * Server response shape (see index.js -> /api/emoji-packs/manifest):
 * {
 *   "version": 3,
 *   "emojis": [
 *     { "id": "wave_default", "url": "/emoji-assets/wave_default.json",
 *       "sha256": "…", "sizeBytes": 21874, "isDefault": true },
 *     { "id": "confetti",     "url": "/emoji-assets/confetti.json",
 *       "sha256": "…", "sizeBytes": 28210, "isDefault": false }
 *   ]
 * }
 *
 * Point 6/12 of the plan: APK ships ONLY the default emoji (bundled as an
 * asset, see EmptyChatLottieController#DEFAULT_ASSET_NAME). Everything else
 * in this manifest is downloaded lazily by EmojiPackDownloadWorker.
 */
public class EmojiManifestModels {

    public static class Manifest {
        @SerializedName("version")
        public int version;

        @SerializedName("emojis")
        public List<Entry> emojis;
    }

    public static class Entry {
        @SerializedName("id")
        public String id;

        @SerializedName("url")
        public String url;          // relative to SERVER_URL, e.g. /emoji-assets/confetti.json

        @SerializedName("sha256")
        public String sha256;       // integrity check before caching

        @SerializedName("sizeBytes")
        public long sizeBytes;      // point 12: server enforces ~30KB/emoji budget

        @SerializedName("isDefault")
        public boolean isDefault;   // true = the one emoji allowed to ship inside the APK
    }
}
