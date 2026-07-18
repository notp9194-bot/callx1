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
        .readTimeout(120,    TimeUnit.SECONDS)
        .writeTimeout(300,   TimeUnit.SECONDS)
        .build();

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
        VideoUploader uploader = new VideoUploader();
        activeUploader = uploader;
        new Thread(() -> uploader.doUpload(ctx, compressed, callback, 1)).start();
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

            String videoUrl = uploadVideoChunked(videoOverride, "callx/videos/file",
                pct -> MAIN.post(() -> cb.onProgress(25 + (pct * 70 / 100))));

            MAIN.post(() -> cb.onProgress(98));

            VideoCompressor.safeDelete(r.thumbFile);
            // Do NOT delete videoOverride — AudioMixHelper cache cleanup handled separately.

            final String fThumb = thumbUrl, fVideo = videoUrl;
            final String fVideo480  = cloudinaryQualityUrl(videoUrl, 854,  480);
            final String fVideo720  = cloudinaryQualityUrl(videoUrl, 1280, 720);
            final String fVideo1080 = cloudinaryQualityUrl(videoUrl, 1920, 1080);
            MAIN.post(() -> cb.onSuccessWithQualities(fThumb, fVideo,
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

            // 1. Thumbnail (0–20%) — small, direct upload
            String thumbUrl = uploadDirect(r.thumbFile, "image", "callx/videos/thumb",
                pct -> MAIN.post(() -> cb.onProgress((int)(pct * 0.20f))));

            // 2. Video (20–100%) — chunked for large files
            String videoUrl = uploadVideoChunked(r.videoFile, "callx/videos/file",
                pct -> MAIN.post(() -> cb.onProgress(20 + (int)(pct * 0.80f))));

            // 3. Record compression stats internally (no API change needed)
            try {
                new VideoQualityPreferences(ctx)
                    .recordCompression(r.originalBytes, r.compressedBytes);
            } catch (Exception ignored) {}

            // 4. Cleanup
            VideoCompressor.safeDelete(r.thumbFile);
            VideoCompressor.safeDelete(r.videoFile);

            // Generate Cloudinary adaptive quality URLs (no extra upload needed)
            final String fVideo480  = cloudinaryQualityUrl(videoUrl, 854,  480);
            final String fVideo720  = cloudinaryQualityUrl(videoUrl, 1280, 720);
            final String fVideo1080 = cloudinaryQualityUrl(videoUrl, 1920, 1080);

            MAIN.post(() -> {
                cb.onProgress(100);
                cb.onSuccessWithQualities(thumbUrl, videoUrl,
                    fVideo480, fVideo720, fVideo1080,
                    r.durationMs, r.width, r.height);
            });

        } catch (Exception e) {
            Log.e(TAG, "Upload attempt " + attempt + " failed: " + e.getMessage());
            if (!cancelled && attempt < MAX_RETRY) {
                long delay = (long) Math.pow(2, attempt) * 1000L;
                try { Thread.sleep(delay); } catch (InterruptedException ignored) {}
                doUpload(ctx, r, cb, attempt + 1);
            } else {
                MAIN.post(() -> cb.onError(new Exception(
                    "Upload failed after " + attempt + " tries: " + e.getMessage())));
            }
        }
    }

    // ── Chunked video upload (5 MB chunks) ────────────────────────────────

    private String uploadVideoChunked(File file, String folder,
                                      ProgressListener progress) throws Exception {
        if (file == null || !file.exists() || file.length() == 0)
            throw new IOException("Video file missing or empty");

        // Small files: direct upload (no chunking overhead)
        if (file.length() <= CHUNK_SIZE)
            return uploadDirect(file, "video", folder, progress);

        // Sign once for the entire upload
        JSONObject payload = new JSONObject()
            .put("folder", folder).put("resource_type", "video");
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
                RequestBody multipart = new MultipartBody.Builder()
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
                    .addFormDataPart("folder",    f)
                    .build();

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

    private String uploadDirect(File file, String resourceType,
                                String folder, ProgressListener progress)
        throws Exception {

        if (file == null || !file.exists() || file.length() == 0)
            throw new IOException("File missing/empty: "
                + (file != null ? file.getPath() : "null"));

        JSONObject payload = new JSONObject()
            .put("folder", folder).put("resource_type", resourceType);
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

        RequestBody multipart = new MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file",      "upload." + ext, fileBody)
            .addFormDataPart("api_key",   apiKey)
            .addFormDataPart("timestamp", ts)
            .addFormDataPart("signature", sig)
            .addFormDataPart("folder",    f)
            .build();

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
        String url   = j.optString("secure_url", j.optString("url", ""));
        if (url.isEmpty())
            throw new IOException("No URL in Cloudinary response for " + resourceType);

        Log.d(TAG, "Uploaded [" + resourceType + "]: " + url);
        return url;
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
     * @param callback  AudioUploadCallback with Cloudinary URL on success
     */
    public static void uploadOriginalAudio(Context ctx, java.io.File videoFile,
                                           AudioUploadCallback callback) {
        new Thread(() -> {
            java.io.File audioOut = null;
            try {
                // ── Step 1: Extract + re-encode audio to M4A ─────────────
                audioOut = extractAudioToM4a(ctx, videoFile);
                if (audioOut == null || !audioOut.exists() || audioOut.length() == 0) {
                    MAIN.post(() -> callback.onError(
                        new Exception("Audio extraction produced empty file")));
                    return;
                }

                // ── Step 2: Upload raw M4A to Cloudinary as "raw" resource ─
                final java.io.File finalAudio = audioOut;
                String audioUrl = uploadAudioDirect(finalAudio, "callx/audio/original");
                if (audioUrl == null || audioUrl.isEmpty()) {
                    MAIN.post(() -> callback.onError(
                        new Exception("Cloudinary returned empty URL for audio")));
                    return;
                }

                final String fUrl = audioUrl;
                MAIN.post(() -> callback.onSuccess(fUrl));

            } catch (Exception e) {
                Log.e(TAG, "uploadOriginalAudio error", e);
                MAIN.post(() -> callback.onError(e));
            } finally {
                if (audioOut != null) audioOut.delete(); // cleanup temp
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

    private VideoUploader() {}
}

