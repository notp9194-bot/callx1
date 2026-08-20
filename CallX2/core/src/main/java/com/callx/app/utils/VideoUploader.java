package com.callx.app.utils;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import okhttp3.*;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * VideoUploader v24 — Chunked Cloudinary upload with pause/resume/cancel + internal stats.
 *
 * BACKWARD COMPATIBLE: UploadCallback.onSuccess() keeps original 5-param signature.
 * Stats (compressionSummary, savingsPercent) are recorded internally via
 * VideoQualityPreferences.recordCompression() — callers don't need to change.
 *
 * NEW:
 *  ✅ Chunked upload (5 MB chunks) — resumable on slow networks
 *  ✅ pause() / resume() / cancel() support
 *  ✅ Internal compression stats tracking (no API change for callers)
 *  ✅ Exponential backoff retry (3x)
 *  ✅ Sequential thumb → video (no race conditions)
 *  ✅ Automatic temp file cleanup after success
 */
public class VideoUploader {

    private static final String TAG        = "VideoUploader";
    private static final int    MAX_RETRY  = 3;
    private static final long   CHUNK_SIZE = 5L * 1024 * 1024; // 5 MB per chunk
    private static final Handler MAIN      = new Handler(Looper.getMainLooper());

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
        .connectTimeout(30,  TimeUnit.SECONDS)
        .readTimeout(240,    TimeUnit.SECONDS) // bumped 120→240: HLS eager transform
        .writeTimeout(300,   TimeUnit.SECONDS) // (adaptive streaming) runs synchronously
        .build();                              // before Cloudinary responds to the last chunk

    /**
     * Cloudinary eager transformation string requesting an HLS adaptive
     * streaming manifest using the predefined "full_hd" profile — bundles
     * 240p/360p/480p/720p/1080p renditions as segments under ONE .m3u8, so
     * ExoPlayer can switch quality mid-playback without re-downloading and
     * CacheDataSource caches under one manifest regardless of resolution
     * watched. Requires Cloudinary's Adaptive Streaming add-on to be enabled
     * on the account — if it isn't, Cloudinary simply omits `eager` from the
     * response and upload still succeeds; see parseEagerHlsUrl().
     */
    private static final String EAGER_HLS = "sp_full_hd/m3u8";

    /**
     * BACKWARD-COMPATIBLE callback — same 5-param onSuccess as v23.
     * compressionSummary and savingsPercent are stored internally.
     */
    public interface UploadCallback {
        void onProgress(int percent);
        void onSuccess(String thumbUrl, String videoUrl,
                       int durationMs, int width, int height);
        /** Called with adaptive quality URLs — override to use them */
        default void onSuccessWithQualities(String thumbUrl, String videoUrl,
                       String video480, String video720, String video1080,
                       int durationMs, int width, int height) {
            onSuccess(thumbUrl, videoUrl, durationMs, width, height);
        }
        /**
         * Called with the HLS master playlist URL alongside everything
         * onSuccessWithQualities() gives — override this for the new
         * single-manifest ABR flow. hlsManifestUrl is "" (not null) when
         * Cloudinary didn't return an eager HLS variant (add-on not enabled
         * on the account, or eager transform failed) — callers should treat
         * empty as "fall back to video480/720/1080 like before".
         * Default just forwards to onSuccessWithQualities so existing
         * overrides that don't know about HLS keep working unchanged.
         */
        default void onSuccessWithHls(String thumbUrl, String videoUrl, String hlsManifestUrl,
                       String video480, String video720, String video1080,
                       int durationMs, int width, int height) {
            onSuccessWithQualities(thumbUrl, videoUrl, video480, video720, video1080,
                durationMs, width, height);
        }
        void onError(Exception e);
    }

    // ── Generate Cloudinary transformation URLs ────────────────────────────
    public static String cloudinaryQualityUrl(String originalUrl, int widthPx, int heightPx) {
        if (originalUrl == null || originalUrl.isEmpty()) return originalUrl;
        // Insert transformation after /upload/
        // e.g. https://res.cloudinary.com/dvqqgqdls/video/upload/callx/videos/file/abc.mp4
        // → https://res.cloudinary.com/dvqqgqdls/video/upload/q_auto,w_854,h_480,c_limit/callx/videos/file/abc.mp4
        String marker = "/upload/";
        int idx = originalUrl.indexOf(marker);
        if (idx < 0) return originalUrl;
        String transform = "q_auto,w_" + widthPx + ",h_" + heightPx + ",c_limit/";
        return originalUrl.substring(0, idx + marker.length())
             + transform
             + originalUrl.substring(idx + marker.length());
    }

    // pause / cancel flags
    private volatile boolean paused    = false;
    private volatile boolean cancelled = false;

    private static VideoUploader activeUploader;

    /** Start upload. Returns the uploader instance for pause/cancel control. */
    public static VideoUploader upload(Context ctx, VideoCompressor.Result compressed,
                                       UploadCallback callback) {
        return upload(ctx, compressed, null, "image", callback);
    }

    /**
     * Media E2E (video, thumbnail-only): same as {@link #upload(Context, VideoCompressor.Result, UploadCallback)}
     * but lets the caller substitute an already-encrypted thumbnail file
     * (and override the Cloudinary resource_type used to upload it — pass
     * "raw" alongside an encrypted file). The video file itself is always
     * uploaded as-is (plaintext) — HLS transcoding / adaptive quality
     * variants require Cloudinary to be able to decode the actual video,
     * so full video E2E is out of scope; only the thumbnail (which is what
     * would otherwise leak a content preview) can be end-to-end encrypted.
     * See ChatMediaController#doStartVideoUploadWork.
     *
     * @param thumbFileOverride if non-null, uploaded INSTEAD of
     *        {@code compressed.thumbFile} — the caller owns cleaning this
     *        file up (in onSuccess/onError), same as the encrypted temp
     *        files in the image E2E path. {@code compressed.thumbFile}
     *        itself (the plaintext original) is still deleted as normal
     *        once the upload completes either way.
     */
    public static VideoUploader upload(Context ctx, VideoCompressor.Result compressed,
                                       File thumbFileOverride, String thumbResourceType,
                                       UploadCallback callback) {
        VideoUploader uploader = new VideoUploader();
        activeUploader = uploader;
        new Thread(() -> uploader.doUpload(ctx, compressed, thumbFileOverride, thumbResourceType, callback, 1)).start();
        return uploader;
    }

    /**
     * Overload: upload with a custom videoFile (e.g. the audio-mixed output).
     * All other metadata (dimensions, duration, thumb) comes from {@code compressed}.
     */
    public static VideoUploader upload(Context ctx, VideoCompressor.Result compressed,
                                       File videoOverride, UploadCallback callback) {
        if (videoOverride == null || !videoOverride.exists()) {
            // Fallback to normal upload if override is invalid
            return upload(ctx, compressed, callback);
        }
        VideoUploader uploader = new VideoUploader();
        activeUploader = uploader;
        new Thread(() -> uploader.doUploadWithOverride(ctx, compressed, videoOverride, callback, 1)).start();
        return uploader;
    }

    /** Same as doUpload but uses videoOverride as the video file to upload. */
    private void doUploadWithOverride(Context ctx, VideoCompressor.Result r,
                                      File videoOverride,
                                      UploadCallback cb, int attempt) {
        paused = false;
        try {
            MAIN.post(() -> cb.onProgress(5));

            String thumbUrl = r.thumbFile != null && r.thumbFile.exists()
                ? uploadDirect(r.thumbFile, "image", "callx/videos/thumb",
                    pct -> MAIN.post(() -> cb.onProgress(5 + pct / 5)))
                : "";

            MAIN.post(() -> cb.onProgress(25));

            final JSONObject[] lastVideoResponseJson = new JSONObject[1];
            String videoUrl = uploadVideoChunked(videoOverride, "callx/videos/file", EAGER_HLS,
                pct -> MAIN.post(() -> cb.onProgress(25 + (pct * 70 / 100))), lastVideoResponseJson);

            MAIN.post(() -> cb.onProgress(98));

            VideoCompressor.safeDelete(r.thumbFile);
            // Do NOT delete videoOverride — AudioMixHelper cache cleanup handled separately.

            final String fThumb = thumbUrl, fVideo = videoUrl;
            final String fVideo480  = cloudinaryQualityUrl(videoUrl, 854,  480);
            final String fVideo720  = cloudinaryQualityUrl(videoUrl, 1280, 720);
            final String fVideo1080 = cloudinaryQualityUrl(videoUrl, 1920, 1080);
            final String fHlsUrl    = parseEagerHlsUrl(lastVideoResponseJson[0]);
            MAIN.post(() -> cb.onSuccessWithHls(fThumb, fVideo, fHlsUrl,
                fVideo480, fVideo720, fVideo1080, r.durationMs, r.width, r.height));

        } catch (Exception e) {
            if (cancelled) return;
            if (attempt < MAX_RETRY) {
                Log.w(TAG, "Upload attempt " + attempt + " failed, retrying…", e);
                doUploadWithOverride(ctx, r, videoOverride, cb, attempt + 1);
            } else {
                MAIN.post(() -> cb.onError(e));
            }
        }
    }

    public void pause()  { paused    = true;  }
    public void resume() { paused    = false; }
    public void cancel() { cancelled = true;  }

    public static void cancelActive() {
        if (activeUploader != null) activeUploader.cancel();
    }

    // ── Upload logic ───────────────────────────────────────────────────────

    private void doUpload(Context ctx, VideoCompressor.Result r,
                          UploadCallback cb, int attempt) {
        doUpload(ctx, r, null, "image", cb, attempt);
    }

    private void doUpload(Context ctx, VideoCompressor.Result r, File thumbFileOverride,
                          String thumbResourceType, UploadCallback cb, int attempt) {
        if (cancelled) {
            MAIN.post(() -> cb.onError(new Exception("Upload cancelled")));
            return;
        }
        if (!NetworkUtils.isOnline(ctx)) {
            MAIN.post(() -> cb.onError(new Exception("No internet connection")));
            return;
        }

        try {
            MAIN.post(() -> cb.onProgress(0));

            // 1. Thumbnail (0–20%) — small, direct upload. When
            // thumbFileOverride is set (Media E2E video thumbnail), upload
            // THAT (the encrypted temp file) with the given resourceType
            // ("raw") instead of the plaintext r.thumbFile.
            File thumbToUpload = (thumbFileOverride != null) ? thumbFileOverride : r.thumbFile;
            String thumbUrl = uploadDirect(thumbToUpload, thumbResourceType, "callx/videos/thumb",
                pct -> MAIN.post(() -> cb.onProgress((int)(pct * 0.20f))));

            // 2. Video (20–100%) — chunked for large files.
            // lastVideoResponseJson[] is filled in by uploadVideoChunked()/uploadDirect()
            // with the raw Cloudinary JSON of the FINAL request, so we can pull the
            // `eager` HLS manifest out of it without changing every call site's
            // return type from String → object.
            final JSONObject[] lastVideoResponseJson = new JSONObject[1];
            String videoUrl = uploadVideoChunked(r.videoFile, "callx/videos/file", EAGER_HLS,
                pct -> MAIN.post(() -> cb.onProgress(20 + (int)(pct * 0.80f))), lastVideoResponseJson);

            // 3. Record compression stats internally (no API change needed)
            try {
                new VideoQualityPreferences(ctx)
                    .recordCompression(r.originalBytes, r.compressedBytes);
            } catch (Exception ignored) {}

            // 4. Cleanup
            VideoCompressor.safeDelete(r.thumbFile);
            VideoCompressor.safeDelete(r.videoFile);

            // Generate Cloudinary adaptive quality URLs (no extra upload needed)
            // — kept as fallback for pre-HLS reels / accounts without the
            // Adaptive Streaming add-on.
            final String fVideo480  = cloudinaryQualityUrl(videoUrl, 854,  480);
            final String fVideo720  = cloudinaryQualityUrl(videoUrl, 1280, 720);
            final String fVideo1080 = cloudinaryQualityUrl(videoUrl, 1920, 1080);
            final String fHlsUrl    = parseEagerHlsUrl(lastVideoResponseJson[0]);

            MAIN.post(() -> {
                cb.onProgress(100);
                cb.onSuccessWithHls(thumbUrl, videoUrl, fHlsUrl,
                    fVideo480, fVideo720, fVideo1080,
                    r.durationMs, r.width, r.height);
            });

        } catch (Exception e) {
            Log.e(TAG, "Upload attempt " + attempt + " failed: " + e.getMessage());
            if (!cancelled && attempt < MAX_RETRY) {
                long delay = (long) Math.pow(2, attempt) * 1000L;
                try { Thread.sleep(delay); } catch (InterruptedException ignored) {}
                doUpload(ctx, r, thumbFileOverride, thumbResourceType, cb, attempt + 1);
            } else {
                MAIN.post(() -> cb.onError(new Exception(
                    "Upload failed after " + attempt + " tries: " + e.getMessage())));
            }
        }
    }

    // ── Chunked video upload (5 MB chunks) ────────────────────────────────

    /**
     * ✅ FIX (401 on reel upload): wraps uploadVideoChunked() with an
     * automatic fallback. Requesting the "sp_full_hd/m3u8" HLS eager
     * transform requires Cloudinary's Adaptive Streaming add-on. When that
     * add-on is NOT enabled on the account, Cloudinary does not silently
     * drop the eager param — it rejects the entire signed upload with
     * HTTP 401 (Unauthorized), because the add-on gate is enforced at
     * auth time, not at transformation time. That took down 100% of reel
     * uploads even though the video itself was never the problem.
     *
     * Fix: if the eager-HLS attempt fails with a 401, retry once with
     * eager = null (plain signed upload, no HLS manifest requested). This
     * matches how thumbnails/photos/chat media (which never request eager)
     * already upload successfully on this same account.
     */
    private String uploadVideoChunked(File file, String folder, String eager,
                                      ProgressListener progress, JSONObject[] outLastResponseJson)
            throws Exception {
        try {
            return uploadVideoChunkedInternal(file, folder, eager, progress, outLastResponseJson);
        } catch (IOException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            boolean isEagerRequest = eager != null && !eager.isEmpty();
            boolean looksLikeAddonAuthReject = msg.contains("(401)");
            if (isEagerRequest && looksLikeAddonAuthReject) {
                Log.w(TAG, "Eager HLS upload rejected with 401 (Adaptive Streaming "
                    + "add-on likely not enabled on this Cloudinary account) — "
                    + "retrying reel upload without the eager transform.", e);
                if (progress != null) progress.onProgress(0);
                return uploadVideoChunkedInternal(file, folder, null, progress, outLastResponseJson);
            }
            throw e;
        }
    }

    private String uploadVideoChunkedInternal(File file, String folder, String eager,
                                      ProgressListener progress, JSONObject[] outLastResponseJson)
            throws Exception {
        if (file == null || !file.exists() || file.length() == 0)
            throw new IOException("Video file missing or empty");

        // Small files: direct upload (no chunking overhead)
        if (file.length() <= CHUNK_SIZE)
            return uploadDirect(file, "video", folder, eager, progress, outLastResponseJson);

        // Sign once for the entire upload — eager MUST be included here too,
        // since it's part of what gets signed (see server /cloudinary/sign/video).
        JSONObject payload = new JSONObject()
            .put("folder", folder).put("resource_type", "video");
        if (eager != null && !eager.isEmpty()) payload.put("eager", eager);
        JSONObject s = sign(payload);

        String apiKey  = s.getString("api_key");
        String sig     = s.getString("signature");
        String ts      = s.getString("timestamp");
        String cloud   = s.optString("cloud_name", Constants.CLOUDINARY_CLOUD_NAME);
        String f       = s.optString("folder", folder);
        String uploadId = UUID.randomUUID().toString().replace("-", "");
        String upUrl   = "https://api.cloudinary.com/v1_1/" + cloud + "/video/upload";

        long   fileSize    = file.length();
        long   offset      = 0;
        int    chunkNum    = 0;
        int    totalChunks = (int) Math.ceil((double) fileSize / CHUNK_SIZE);
        String lastUrl     = null;

        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] chunkBuf = new byte[(int) CHUNK_SIZE];
            while (offset < fileSize) {
                // Pause support
                while (paused && !cancelled) {
                    try { Thread.sleep(300); } catch (InterruptedException ignored) {}
                }
                if (cancelled) throw new Exception("Upload cancelled");

                int bytesRead = fis.read(chunkBuf);
                if (bytesRead <= 0) break;
                byte[] chunkData = Arrays.copyOf(chunkBuf, bytesRead);
                long end = offset + bytesRead - 1;

                final int    cNum    = chunkNum;
                final int    cTotal  = totalChunks;
                MultipartBody.Builder chunkBuilder = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", "chunk.mp4",
                        new CountingRequestBody(
                            RequestBody.create(chunkData, MediaType.parse("video/mp4")),
                            pct -> {
                                if (progress != null) {
                                    float overall = (cNum + pct / 100f) / cTotal;
                                    progress.onProgress((int)(overall * 100));
                                }
                            }))
                    .addFormDataPart("api_key",   apiKey)
                    .addFormDataPart("timestamp", ts)
                    .addFormDataPart("signature", sig)
                    .addFormDataPart("folder",    f);
                if (eager != null && !eager.isEmpty()) chunkBuilder.addFormDataPart("eager", eager);
                RequestBody multipart = chunkBuilder.build();

                Request req = new Request.Builder()
                    .url(upUrl).post(multipart)
                    .header("X-Unique-Upload-Id", uploadId)
                    .header("Content-Range", "bytes " + offset + "-" + end + "/" + fileSize)
                    .build();

                Response res  = HTTP.newCall(req).execute();
                String resBody = res.body() != null ? res.body().string() : "";
                res.close();

                if (res.code() == 200) {
                    JSONObject j = new JSONObject(resBody);
                    lastUrl = j.optString("secure_url", j.optString("url", ""));
                    if (outLastResponseJson != null) outLastResponseJson[0] = j;
                } else if (res.code() == 308) {
                    Log.d(TAG, "Chunk " + chunkNum + " accepted (308), continuing");
                } else {
                    throw new IOException("Chunk " + chunkNum + " failed ("
                        + res.code() + "): " + resBody);
                }

                offset += bytesRead;
                chunkNum++;
            }
        }

        if (lastUrl == null || lastUrl.isEmpty())
            throw new IOException("No URL returned after chunked upload");
        if (progress != null) progress.onProgress(100);
        Log.d(TAG, "Chunked upload done: " + lastUrl);
        return lastUrl;
    }

    // ── Single-part direct upload ──────────────────────────────────────────

    /** Original signature — thumbs and any resourceType with no eager transform. */
    private String uploadDirect(File file, String resourceType,
                                String folder, ProgressListener progress)
        throws Exception {
        return uploadDirect(file, resourceType, folder, null, progress, null);
    }

    /**
     * Overload: optional eager transform (video HLS) + optional out-param to
     * capture the raw Cloudinary response JSON, so the caller can pull the
     * `eager` array (manifest url) out without changing this method's return
     * type everywhere it's already used.
     */
    private String uploadDirect(File file, String resourceType, String folder,
                                String eager, ProgressListener progress,
                                JSONObject[] outResponseJson)
        throws Exception {

        if (file == null || !file.exists() || file.length() == 0)
            throw new IOException("File missing/empty: "
                + (file != null ? file.getPath() : "null"));

        JSONObject payload = new JSONObject()
            .put("folder", folder).put("resource_type", resourceType);
        if (eager != null && !eager.isEmpty()) payload.put("eager", eager);
        JSONObject s = sign(payload);

        String apiKey = s.getString("api_key");
        String sig    = s.getString("signature");
        String ts     = s.getString("timestamp");
        String cloud  = s.optString("cloud_name", Constants.CLOUDINARY_CLOUD_NAME);
        String f      = s.optString("folder", folder);

        String mime = "image".equals(resourceType) ? "image/webp" : "video/mp4";
        String ext  = "image".equals(resourceType) ? "webp"       : "mp4";

        RequestBody fileBody = new CountingRequestBody(
            RequestBody.create(file, MediaType.parse(mime)), progress);

        MultipartBody.Builder mb = new MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file",      "upload." + ext, fileBody)
            .addFormDataPart("api_key",   apiKey)
            .addFormDataPart("timestamp", ts)
            .addFormDataPart("signature", sig)
            .addFormDataPart("folder",    f);
        if (eager != null && !eager.isEmpty()) mb.addFormDataPart("eager", eager);
        RequestBody multipart = mb.build();

        String upUrl = "https://api.cloudinary.com/v1_1/" + cloud
            + "/" + resourceType + "/upload";
        Response upRes = HTTP.newCall(new Request.Builder().url(upUrl).post(multipart).build())
            .execute();
        String upBody  = upRes.body() != null ? upRes.body().string() : "";
        upRes.close();

        if (!upRes.isSuccessful())
            throw new IOException("Cloudinary " + resourceType + " upload failed ("
                + upRes.code() + "): " + upBody);

        JSONObject j = new JSONObject(upBody);
        if (outResponseJson != null) outResponseJson[0] = j;
        String url   = j.optString("secure_url", j.optString("url", ""));
        if (url.isEmpty())
            throw new IOException("No URL in Cloudinary response for " + resourceType);

        Log.d(TAG, "Uploaded [" + resourceType + "]: " + url);
        return url;
    }

    /**
     * Pulls the HLS manifest (.m3u8) secure_url out of a Cloudinary upload
     * response's `eager` array. Returns "" (never null) if the account has
     * no Adaptive Streaming add-on enabled, the eager transform failed, or
     * responseJson is null — callers treat "" as "fall back to per-quality
     * progressive URLs like before this feature shipped".
     */
    private static String parseEagerHlsUrl(JSONObject responseJson) {
        if (responseJson == null) return "";
        try {
            org.json.JSONArray eager = responseJson.optJSONArray("eager");
            if (eager == null || eager.length() == 0) return "";
            for (int i = 0; i < eager.length(); i++) {
                JSONObject e = eager.optJSONObject(i);
                if (e == null) continue;
                String url = e.optString("secure_url", e.optString("url", ""));
                if (!url.isEmpty() && url.contains(".m3u8")) return url;
            }
        } catch (Exception ex) {
            Log.w(TAG, "parseEagerHlsUrl: " + ex.getMessage());
        }
        return "";
    }

    // ── Sign helper ───────────────────────────────────────────────────────

    private static JSONObject sign(JSONObject payload) throws Exception {
        Request req = new Request.Builder()
            .url(Constants.SERVER_URL + "/cloudinary/sign")
            .post(RequestBody.create(payload.toString(), MediaType.parse("application/json")))
            .build();
        Response res  = HTTP.newCall(req).execute();
        String   body = res.body() != null ? res.body().string() : "";
        res.close();
        if (!res.isSuccessful())
            throw new IOException("Sign failed (" + res.code() + "): " + body);
        return new JSONObject(body);
    }

    // ── Progress tracking ─────────────────────────────────────────────────

    interface ProgressListener { void onProgress(int pct); }

    private static class CountingRequestBody extends RequestBody {
        private final RequestBody      delegate;
        private final ProgressListener listener;

        CountingRequestBody(RequestBody d, ProgressListener l) {
            this.delegate = d; this.listener = l;
        }
        @Override public MediaType contentType()     { return delegate.contentType(); }
        @Override public long contentLength() throws IOException { return delegate.contentLength(); }
        @Override public void writeTo(okio.BufferedSink sink) throws IOException {
            long total = contentLength();
            okio.ForwardingSink fw = new okio.ForwardingSink(sink) {
                long written = 0;
                @Override public void write(okio.Buffer src, long n) throws IOException {
                    super.write(src, n);
                    written += n;
                    if (total > 0 && listener != null)
                        listener.onProgress((int)(written * 100 / total));
                }
            };
            okio.BufferedSink buffered = okio.Okio.buffer(fw);
            delegate.writeTo(buffered);
            buffered.flush(); // flush internal buffer → sink, prevents "unexpected end of stream"
        }
    }

    // ── Original Audio Upload ─────────────────────────────────────────────

    /**
     * Callback for uploadOriginalAudio().
     */
    public interface AudioUploadCallback {
        void onSuccess(String audioUrl);
        /**
         * ✅ NEW: called with BOTH the full-quality original audio URL and a
         * small mono/low-bitrate preview URL (used by SoundDetailActivity's
         * play button so it doesn't stream the full-quality file just to
         * preview a sound — see PreviewAudioEncoder).
         * Default just forwards to the old single-URL callback so existing
         * overrides that only implement onSuccess(String) keep working.
         */
        default void onSuccess(String audioUrl, String previewAudioUrl) {
            onSuccess(audioUrl);
        }
        /**
         * ✅ NEW: audio-fingerprint match result (Instagram-style "someone
         * else already posted this exact audio, even though I never picked
         * it from a sound page" detection — see /audio/match on the server).
         *
         * @param audioUrl        full-quality original audio URL (Cloudinary)
         * @param previewAudioUrl low-bitrate preview URL, may be ""
         * @param soundId         the sounds/{soundId} this reel should link
         *                        to — either an EXISTING sound (if matched)
         *                        or the fresh "orig_{reelId}" id (if not)
         * @param matched         true  → soundId belongs to another (or the
         *                                same) creator's earlier reel; treat
         *                                exactly like a picked existing sound
         *                        false → this is a genuinely new original;
         *                                caller should register it as such
         * @param ownerUid        original creator's uid when matched != own
         *                        upload; "" if unknown/not matched
         *
         * Default just forwards to the 2-arg callback with matched=false,
         * so existing overrides keep compiling/working unchanged.
         */
        default void onSuccess(String audioUrl, String previewAudioUrl,
                                String soundId, boolean matched, String ownerUid) {
            onSuccess(audioUrl, previewAudioUrl);
        }
        /**
         * ✅ NEW: same as the 5-arg onSuccess, plus offsetSec — where inside
         * the MATCHED original track this upload's audio actually starts
         * (server-side offset-consistent landmark alignment; see
         * /audio/match "v2" on the server). 0 when matched==false, or when
         * this upload's audio effectively starts at the original's start.
         * Only meaningful when matched==true.
         *
         * Default just forwards to the 5-arg callback, so existing overrides
         * keep compiling/working unchanged.
         */
        default void onSuccess(String audioUrl, String previewAudioUrl,
                                String soundId, boolean matched, String ownerUid,
                                double offsetSec) {
            onSuccess(audioUrl, previewAudioUrl, soundId, matched, ownerUid);
        }
        void onError(Exception e);
    }

    /**
     * Extracts audio from a local video file using MediaExtractor + MediaMuxer
     * (no FFmpeg needed), compresses it to AAC 128 kbps, and uploads to Cloudinary.
     *
     * Called after video is already uploaded; audioUrl is then saved to Firebase
     * as {@code ReelModel.originalAudioUrl}.
     *
     * @param ctx       Context
     * @param videoFile Compressed/mixed video file (already exists locally)
     * @param ownerUid  uploader's uid — needed to credit a NEW original audio
     *                  to the right person, and to skip crediting a match
     *                  back to yourself
     * @param reelId    the reel this audio belongs to — used to derive the
     *                  fallback "orig_{reelId}" sound id when no match is found
     * @param callback  AudioUploadCallback with Cloudinary URL on success
     */
    public static void uploadOriginalAudio(Context ctx, java.io.File videoFile,
                                           String ownerUid, String reelId,
                                           AudioUploadCallback callback) {
        new Thread(() -> {
            java.io.File audioOut   = null;
            java.io.File previewOut = null;
            try {
                // ── Step 1: Extract original audio (passthrough, no re-encode) ─
                audioOut = extractAudioToM4a(ctx, videoFile);
                if (audioOut == null || !audioOut.exists() || audioOut.length() == 0) {
                    MAIN.post(() -> callback.onError(
                        new Exception("Audio extraction produced empty file")));
                    return;
                }

                // ── Step 2: Upload original (full-quality) audio ───────────
                final java.io.File finalAudio = audioOut;
                String audioUrl = uploadAudioDirect(finalAudio, "callx/audio/original");
                if (audioUrl == null || audioUrl.isEmpty()) {
                    MAIN.post(() -> callback.onError(
                        new Exception("Cloudinary returned empty URL for audio")));
                    return;
                }

                // ── Step 3: Generate + upload a small mono/low-bitrate PREVIEW ─
                // ✅ FIX: this is what SoundDetailActivity's play button streams,
                // so a sound preview costs ~30-100 KB instead of the 200-300 KB
                // the full-quality passthrough file used to cost (Instagram-style).
                // If this step fails for any reason, we still succeed with the
                // original URL alone — previewAudioUrl falls back client-side.
                String previewUrl = "";
                try {
                    previewOut = PreviewAudioEncoder.generatePreview(videoFile, ctx.getCacheDir());
                    if (previewOut.exists() && previewOut.length() > 0) {
                        previewUrl = uploadAudioDirect(previewOut, "callx/audio/preview");
                    }
                } catch (Exception previewEx) {
                    Log.w(TAG, "Preview audio generation/upload failed, continuing without it", previewEx);
                }

                final String fUrl        = audioUrl;
                final String fPreviewUrl = previewUrl != null ? previewUrl : "";

                // ── Step 2.5: audio-fingerprint match against the server ───
                // Runs on THIS background thread (audioOut still exists here —
                // it gets deleted in the finally block below, after this).
                // Never blocks the reel post itself: this whole method already
                // runs after the reel is live (see ReelUploadActivity caller),
                // and any failure here just falls back to "new original audio",
                // same as before this feature existed.
                String  fallbackSoundId = "orig_" + reelId;
                String  matchedSoundId  = fallbackSoundId;
                boolean matched         = false;
                String  matchedOwnerUid = "";
                double  matchedOffsetSec = 0;
                try {
                    JSONObject matchResult =
                        matchAudioFingerprint(finalAudio, ownerUid, reelId, fallbackSoundId);
                    if (matchResult != null) {
                        matched         = matchResult.optBoolean("matched", false);
                        matchedSoundId  = matchResult.optString("sound_id", fallbackSoundId);
                        matchedOwnerUid = matchResult.optString("owner_uid", "");
                        matchedOffsetSec = matchResult.optDouble("offset_sec", 0);
                        if (matchedSoundId == null || matchedSoundId.isEmpty()) {
                            matchedSoundId = fallbackSoundId;
                        }
                    }
                } catch (Exception matchEx) {
                    Log.w(TAG, "Audio fingerprint match skipped (non-fatal): " + matchEx.getMessage());
                    // matched stays false, matchedSoundId stays fallbackSoundId — same
                    // behaviour as before this feature existed.
                }

                final String  fSoundId    = matchedSoundId;
                final boolean fMatched    = matched;
                final String  fOwnerUid   = matchedOwnerUid;
                final double  fOffsetSec  = matchedOffsetSec;
                MAIN.post(() -> callback.onSuccess(fUrl, fPreviewUrl, fSoundId, fMatched, fOwnerUid, fOffsetSec));

            } catch (Exception e) {
                Log.e(TAG, "uploadOriginalAudio error", e);
                MAIN.post(() -> callback.onError(e));
            } finally {
                if (audioOut   != null) audioOut.delete();   // cleanup temp
                if (previewOut != null) previewOut.delete(); // cleanup temp
            }
        }).start();
    }

    /**
     * Uses MediaExtractor + MediaMuxer to pull the audio track from a video
     * and write it as a plain .m4a file. No re-encoding — raw AAC passthrough.
     * Fast and zero-quality-loss.
     */
    private static java.io.File extractAudioToM4a(android.content.Context ctx,
                                                   java.io.File videoFile) throws Exception {
        java.io.File outDir  = ctx.getCacheDir();
        java.io.File outFile = new java.io.File(outDir,
            "orig_audio_" + System.currentTimeMillis() + ".m4a");

        android.media.MediaExtractor extractor = new android.media.MediaExtractor();
        extractor.setDataSource(videoFile.getAbsolutePath());

        int audioTrack = -1;
        android.media.MediaFormat audioFormat = null;
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            android.media.MediaFormat fmt = extractor.getTrackFormat(i);
            String mime = fmt.getString(android.media.MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                audioTrack  = i;
                audioFormat = fmt;
                break;
            }
        }

        if (audioTrack < 0 || audioFormat == null) {
            extractor.release();
            throw new Exception("No audio track found in video file");
        }

        extractor.selectTrack(audioTrack);

        android.media.MediaMuxer muxer = new android.media.MediaMuxer(
            outFile.getAbsolutePath(),
            android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

        int muxAudioTrack = muxer.addTrack(audioFormat);
        muxer.start();

        android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(1024 * 1024); // 1 MB

        while (true) {
            int size = extractor.readSampleData(buf, 0);
            if (size < 0) break;
            info.offset         = 0;
            info.size           = size;
            info.presentationTimeUs = extractor.getSampleTime();
            info.flags          = extractor.getSampleFlags();
            muxer.writeSampleData(muxAudioTrack, buf, info);
            extractor.advance();
        }

        muxer.stop();
        muxer.release();
        extractor.release();

        Log.d(TAG, "Audio extracted: " + outFile.length() / 1024 + " KB → " + outFile.getPath());
        return outFile;
    }

    /**
     * Direct (non-chunked) upload for small audio files to Cloudinary.
     * Uses signed upload (server sign endpoint) — resource_type=raw so Cloudinary
     * stores it as a file (not video/audio transform), streamable via plain URL.
     */
    private static String uploadAudioDirect(java.io.File audioFile, String folder)
            throws Exception {

        // ── Step 1: Get signature from sign server ────────────────────────
        JSONObject payload = new JSONObject();
        payload.put("folder",        folder);
        payload.put("resource_type", "video"); // ✅ video = byte-range streaming, not raw blob
        JSONObject signJson = sign(payload);

        String signature  = signJson.getString("signature");
        String timestamp  = signJson.getString("timestamp");
        String apiKey     = signJson.getString("api_key");
        String cloudName  = signJson.optString("cloud_name", Constants.CLOUDINARY_CLOUD_NAME);
        String folderFinal = signJson.optString("folder", folder);

        // ── Step 2: Upload to Cloudinary ──────────────────────────────────
        byte[] bytes;
        try (java.io.FileInputStream fis = new java.io.FileInputStream(audioFile)) {
            bytes = new byte[(int) audioFile.length()];
            //noinspection ResultOfMethodCallIgnored
            fis.read(bytes);
        }

        String uploadUrl = "https://api.cloudinary.com/v1_1/" + cloudName + "/video/upload";
        RequestBody body = new MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", audioFile.getName(),
                RequestBody.create(bytes, MediaType.parse("audio/mp4")))
            .addFormDataPart("api_key",   apiKey)
            .addFormDataPart("timestamp", timestamp)
            .addFormDataPart("signature", signature)
            .addFormDataPart("folder",    folderFinal)
            .build();

        Request req = new Request.Builder().url(uploadUrl).post(body).build();
        try (Response res = HTTP.newCall(req).execute()) {
            String resBody = res.body() != null ? res.body().string() : "";
            if (!res.isSuccessful())
                throw new IOException("Cloudinary audio upload failed (" + res.code() + "): " + resBody);
            JSONObject j = new JSONObject(resBody);
            String url = j.optString("secure_url", j.optString("url", ""));
            if (url.isEmpty())
                throw new IOException("No URL in Cloudinary audio response");
            Log.d(TAG, "Audio uploaded to Cloudinary: " + url);
            return url;
        }
    }

    /**
     * Audio-fingerprint match — asks the server "has anyone already posted a
     * reel with this exact audio (raw upload, not explicitly picked from a
     * sound page)?" See POST /audio/match on the server (pure server-side FFT
     * fingerprinting, no paid API).
     *
     * ✅ ASYNC QUEUE: the server no longer computes the FFT inline — it
     * queues the job and replies 202 immediately with a job_id (see server's
     * runFingerprintJob/pumpFingerprintQueue). We then wait for the result
     * via a normal Firebase RTDB listener on audio_match_jobs/{job_id},
     * bounded by a timeout, instead of holding one HTTP connection open for
     * however long a busy queue takes — same async listener pattern already
     * used for the linked-devices pairing flow on the server.
     *
     * Still fully synchronous from THIS method's caller's point of view (it
     * blocks the background thread it's already running on) — nothing about
     * the calling code in uploadOriginalAudio() had to change.
     *
     * @param audioFile   local extracted audio (m4a) — the SAME file already
     *                    uploaded to Cloudinary a moment ago in this thread
     * @param ownerUid    uploader's uid
     * @param reelId      this reel's id
     * @param newSoundId  the id the server should register this audio under
     *                    if it turns out to be genuinely new ("orig_{reelId}")
     * @return {matched, sound_id, owner_uid, offset_sec} JSON, or null if the
     *         server/queue didn't respond in time — caller treats null as
     *         "no match". offset_sec (server "v2") is where inside the
     *         matched original this upload's audio starts — 0 if unmatched.
     */
    private static JSONObject matchAudioFingerprint(java.io.File audioFile, String ownerUid,
                                                      String reelId, String newSoundId)
            throws Exception {
        OkHttpClient shortClient = HTTP.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15,    TimeUnit.SECONDS) // just submitting the job now — should be fast
            .writeTimeout(20,   TimeUnit.SECONDS)
            .build();

        byte[] bytes;
        try (java.io.FileInputStream fis = new java.io.FileInputStream(audioFile)) {
            bytes = new byte[(int) audioFile.length()];
            //noinspection ResultOfMethodCallIgnored
            fis.read(bytes);
        }

        RequestBody body = new MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", audioFile.getName(),
                RequestBody.create(bytes, MediaType.parse("audio/mp4")))
            .addFormDataPart("uid",          ownerUid != null ? ownerUid : "")
            .addFormDataPart("reel_id",      reelId != null ? reelId : "")
            .addFormDataPart("new_sound_id", newSoundId != null ? newSoundId : "")
            .build();

        Request req = new Request.Builder()
            .url(Constants.SERVER_URL + "/audio/match")
            .post(body)
            .build();

        String jobId;
        try (Response res = shortClient.newCall(req).execute()) {
            String resBody = res.body() != null ? res.body().string() : "";
            if (!res.isSuccessful()) {
                Log.w(TAG, "/audio/match submit failed (" + res.code() + "): " + resBody);
                return null;
            }
            JSONObject submitJson = new JSONObject(resBody);
            jobId = submitJson.optString("job_id", "");
            if (jobId.isEmpty()) {
                // Backward-compat: an older/non-queued deploy of the server
                // may still return the final result directly — use it as-is.
                return submitJson;
            }
        }

        return waitForAudioMatchJob(jobId);
    }

    /**
     * Blocks (with a timeout) until audio_match_jobs/{jobId} in Firebase RTDB
     * reaches status "done" or "error", then returns a {matched, sound_id,
     * owner_uid} JSON — or null on timeout/error, same "no match, fall back"
     * contract as before this became async.
     */
    private static JSONObject waitForAudioMatchJob(String jobId) throws InterruptedException {
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final JSONObject[] resultHolder = new JSONObject[1];

        com.google.firebase.database.DatabaseReference jobRef =
            com.google.firebase.database.FirebaseDatabase.getInstance()
                .getReference("audio_match_jobs").child(jobId);

        com.google.firebase.database.ValueEventListener listener =
            new com.google.firebase.database.ValueEventListener() {
                @Override
                public void onDataChange(@androidx.annotation.NonNull com.google.firebase.database.DataSnapshot snap) {
                    String status = snap.child("status").getValue(String.class);
                    if (status == null || status.equals("queued") || status.equals("processing")) {
                        return; // still working — keep listening
                    }
                    if ("done".equals(status)) {
                        try {
                            JSONObject j = new JSONObject();
                            j.put("matched",   Boolean.TRUE.equals(snap.child("matched").getValue(Boolean.class)));
                            j.put("sound_id",  snap.child("sound_id").getValue(String.class));
                            j.put("owner_uid", snap.child("owner_uid").getValue(String.class));
                            Double offsetSec = snap.child("offset_sec").getValue(Double.class);
                            j.put("offset_sec", offsetSec != null ? offsetSec : 0);
                            resultHolder[0] = j;
                        } catch (Exception ignored) { /* resultHolder stays null → treated as no-match */ }
                    } else {
                        Log.w(TAG, "audio_match_jobs/" + jobId + " status=" + status);
                    }
                    jobRef.removeEventListener(this);
                    latch.countDown();
                }

                @Override
                public void onCancelled(@androidx.annotation.NonNull com.google.firebase.database.DatabaseError e) {
                    Log.w(TAG, "audio_match_jobs listener cancelled: " + e.getMessage());
                    latch.countDown();
                }
            };

        jobRef.addValueEventListener(listener);

        // Bounded wait — a queue backlog or a cold Render dyno shouldn't hang
        // the reel post pipeline forever. On timeout we just fall back to
        // "new original audio", exactly like any other match failure.
        boolean completed = latch.await(25, TimeUnit.SECONDS);
        jobRef.removeEventListener(listener);
        if (!completed) {
            Log.w(TAG, "audio_match_jobs/" + jobId + " timed out waiting for result");
        }
        return resultHolder[0];
    }

    private VideoUploader() {}
}

