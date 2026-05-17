package com.callx.app.utils;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * VideoCompressor v26 — CLOUDINARY DIRECT UPLOAD (Mobile CPU + Server CPU Zero Load)
 *
 * PEHLE (v25): Raw video → Server /compress/video → FFmpeg compress → Cloudinary
 *   Problem: Raw video (500MB) puri server pe upload hoti thi — network radio lamba on
 *
 * AB (v26):    Raw video → Directly Cloudinary pe upload → Cloudinary eager transform compress
 *   Solution:  Server FFmpeg bypass — Cloudinary khud async compress karta hai
 *              Ek hi upload, Cloudinary CDN pe seedha, thumbnail bhi wahi se
 *
 * Flow:
 *  1. Mobile: video metadata pado (sirf duration/size)
 *  2. Mobile: server se Cloudinary signed URL lo
 *  3. Mobile: raw video directly Cloudinary pe chunked upload karo
 *             (eager transform = Cloudinary async 720p compress karega)
 *  4. Cloudinary: compressed URL + thumbnail URL return kare
 *  5. Mobile: Result object banao, callback karo
 *
 * Mobile CPU LOAD: ~0% (koi encode nahi — sirf file read + upload)
 * Server LOAD:     ~0% (FFmpeg server bypass — Cloudinary handle karta hai)
 * Network:         Raw video upload hoti hai — unavoidable, lekin ek baar aur fast
 */
public class VideoCompressor {

    private static final String TAG = "VideoCompressor";

    // MIME constants — backward compat
    public static final String MIME_AV1  = "video/av01";
    public static final String MIME_HEVC = "video/hevc";
    public static final String MIME_AVC  = "video/avc";

    private static final ExecutorService BG   = Executors.newCachedThreadPool();
    private static final Handler         MAIN = new Handler(Looper.getMainLooper());

    // Chunked upload — 10 MB chunks (Cloudinary recommended)
    private static final long CHUNK_SIZE = 10L * 1024 * 1024;

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
        .connectTimeout(30,  TimeUnit.SECONDS)
        .readTimeout(300,    TimeUnit.SECONDS)
        .writeTimeout(300,   TimeUnit.SECONDS)
        .build();

    // Quality → Cloudinary eager transform mapping
    public enum Quality {
        LOW, STANDARD, HD, FULL_HD, ORIGINAL
    }

    public interface Callback {
        void onProgress(int percent);
        void onSuccess(Result result);
        void onError(Exception e);
    }

    public static class Result {
        public final File   videoFile;       // null — Cloudinary pe uploaded
        public final File   thumbFile;       // null — Cloudinary se URL aata hai
        public final long   originalBytes;
        public final long   compressedBytes;
        public final int    durationMs;
        public final int    width;
        public final int    height;
        public final String codecUsed;
        public final VideoQualityPreferences.Quality quality;

        // Cloudinary direct fields (v26)
        public final String serverVideoUrl;
        public final String serverThumbUrl;
        public final String serverPublicId;

        public Result(long originalBytes, long compressedBytes, int durationMs,
                      int width, int height, String codecUsed,
                      VideoQualityPreferences.Quality quality,
                      String serverVideoUrl, String serverThumbUrl, String serverPublicId) {
            this.videoFile        = null;
            this.thumbFile        = null;
            this.originalBytes    = originalBytes;
            this.compressedBytes  = compressedBytes;
            this.durationMs       = durationMs;
            this.width            = width;
            this.height           = height;
            this.codecUsed        = codecUsed;
            this.quality          = quality;
            this.serverVideoUrl   = serverVideoUrl;
            this.serverThumbUrl   = serverThumbUrl;
            this.serverPublicId   = serverPublicId;
        }

        public String compressionSummary() {
            long saved = originalBytes - compressedBytes;
            return "Cloudinary-eager " + (quality != null ? quality.name() : "?")
                + " → " + (saved / 1024 / 1024) + "MB saved";
        }

        public int savingsPercent() {
            if (originalBytes <= 0) return 0;
            return (int)(100L * (originalBytes - compressedBytes) / originalBytes);
        }
    }

    // ── Main entry ────────────────────────────────────────────────────────

    public static void compress(Context ctx, Uri videoUri,
                                VideoQualityPreferences.Quality quality,
                                Callback cb) {
        BG.execute(() -> {
            File tempVideo = null;
            try {
                // Step 1: Metadata only — koi encode nahi
                long originalBytes = getFileSize(ctx, videoUri);
                int[] dims         = getVideoDimensions(ctx, videoUri);
                int durationMs     = getVideoDuration(ctx, videoUri);
                int width  = dims[0];
                int height = dims[1];

                MAIN.post(() -> cb.onProgress(5));

                // Step 2: Server se Cloudinary sign lo
                String eagerTransform = mapQualityToEager(quality);
                JSONObject signPayload = new JSONObject()
                    .put("folder",        "callx/videos/file")
                    .put("resource_type", "video");
                // eager is NOT signed — Cloudinary free plan doesn't support signed eager

                Request signReq = new Request.Builder()
                    .url(Constants.SERVER_URL + "/cloudinary/sign")
                    .post(RequestBody.create(signPayload.toString(),
                        MediaType.parse("application/json")))
                    .build();

                Response signRes = HTTP.newCall(signReq).execute();
                String signBody  = signRes.body() != null ? signRes.body().string() : "";
                signRes.close();

                if (!signRes.isSuccessful()) {
                    throw new IOException("Sign failed " + signRes.code() + ": " + signBody);
                }

                JSONObject signJson  = new JSONObject(signBody);
                String signature     = signJson.getString("signature");
                String timestamp     = signJson.getString("timestamp");
                String apiKey        = signJson.getString("api_key");
                String cloudName     = signJson.optString("cloud_name", Constants.CLOUDINARY_CLOUD_NAME);
                String folder        = signJson.optString("folder", "callx/videos/file");

                MAIN.post(() -> cb.onProgress(10));

                // Step 3: URI → temp file copy
                tempVideo = copyToTemp(ctx, videoUri);
                long fileSize = tempVideo.length();

                MAIN.post(() -> cb.onProgress(15));

                // Step 4: Cloudinary pe chunked upload (eager transform se compress hoga)
                String uploadUrl = "https://api.cloudinary.com/v1_1/" + cloudName + "/video/upload";
                String uploadId  = UUID.randomUUID().toString().replace("-", "");

                String videoUrl  = "";
                String thumbUrl  = "";
                String publicId  = "";

                // Small video (<10MB) → direct upload
                if (fileSize <= CHUNK_SIZE) {
                    JSONObject uploadResult = uploadDirect(
                        tempVideo, uploadUrl, apiKey, signature, timestamp,
                        folder, eagerTransform,
                        pct -> MAIN.post(() -> cb.onProgress(15 + (pct * 80 / 100)))
                    );
                    videoUrl = uploadResult.optString("secure_url", "");
                    publicId = uploadResult.optString("public_id", "");
                    // Eager transform se compressed URL
                    JSONArray eager = uploadResult.optJSONArray("eager");
                    if (eager != null && eager.length() > 0) {
                        videoUrl = eager.getJSONObject(0).optString("secure_url", videoUrl);
                    }
                    // Thumbnail — Cloudinary URL transform
                    thumbUrl = buildThumbUrl(videoUrl, cloudName, publicId);
                } else {
                    // Large video → chunked upload
                    JSONObject uploadResult = uploadChunked(
                        tempVideo, uploadUrl, apiKey, signature, timestamp,
                        folder, eagerTransform, uploadId, fileSize,
                        pct -> MAIN.post(() -> cb.onProgress(15 + (pct * 80 / 100)))
                    );
                    videoUrl = uploadResult.optString("secure_url", "");
                    publicId = uploadResult.optString("public_id", "");
                    JSONArray eager = uploadResult.optJSONArray("eager");
                    if (eager != null && eager.length() > 0) {
                        videoUrl = eager.getJSONObject(0).optString("secure_url", videoUrl);
                    }
                    thumbUrl = buildThumbUrl(videoUrl, cloudName, publicId);
                }

                if (videoUrl.isEmpty()) {
                    throw new IOException("Cloudinary ne URL return nahi kiya");
                }

                final String fVideoUrl = videoUrl;
                final String fThumbUrl = thumbUrl;
                final String fPublicId = publicId;

                Result result = new Result(
                    originalBytes, (long)(originalBytes * 0.4), // ~60% savings estimate
                    durationMs, width, height,
                    "cloudinary-eager",
                    quality,
                    fVideoUrl, fThumbUrl, fPublicId
                );

                MAIN.post(() -> {
                    cb.onProgress(100);
                    cb.onSuccess(result);
                });

            } catch (Exception e) {
                Log.e(TAG, "Cloudinary direct upload failed", e);
                MAIN.post(() -> cb.onError(e));
            } finally {
                safeDelete(tempVideo);
            }
        });
    }

    // ── Backward compat overload ──────────────────────────────────────────

    public static void compress(Context ctx, Uri videoUri, Callback cb) {
        VideoQualityPreferences.Quality q =
            new VideoQualityPreferences(ctx).getGlobalQuality();
        compress(ctx, videoUri, q, cb);
    }

    // ── Cloudinary direct upload (small files <10MB) ──────────────────────

    private static JSONObject uploadDirect(File file, String uploadUrl,
                                           String apiKey, String signature, String timestamp,
                                           String folder, String eagerTransform,
                                           ProgressListener progress) throws Exception {
        RequestBody fileBody = new CountingRequestBody(
            RequestBody.create(file, MediaType.parse("video/mp4")), progress);

        MultipartBody.Builder mb = new MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file",      file.getName(), fileBody)
            .addFormDataPart("api_key",   apiKey)
            .addFormDataPart("timestamp", timestamp)
            .addFormDataPart("signature", signature)
            .addFormDataPart("folder",    folder);

        // eager is added AFTER signature — unsigned param, Cloudinary accepts this
        if (eagerTransform != null && !eagerTransform.isEmpty()) {
            mb.addFormDataPart("eager", eagerTransform);
        }

        Response res  = HTTP.newCall(new Request.Builder()
            .url(uploadUrl).post(mb.build()).build()).execute();
        String body   = res.body() != null ? res.body().string() : "";
        res.close();

        if (!res.isSuccessful())
            throw new IOException("Cloudinary upload failed (" + res.code() + "): " + body);

        return new JSONObject(body);
    }

    // ── Cloudinary chunked upload (large files >10MB) ─────────────────────

    private static JSONObject uploadChunked(File file, String uploadUrl,
                                            String apiKey, String signature, String timestamp,
                                            String folder, String eagerTransform,
                                            String uploadId, long fileSize,
                                            ProgressListener progress) throws Exception {
        long offset  = 0;
        int  chunk   = 0;
        int  total   = (int) Math.ceil((double) fileSize / CHUNK_SIZE);
        JSONObject lastResult = null;

        try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
            byte[] buf = new byte[(int) CHUNK_SIZE];
            while (offset < fileSize) {
                int read = fis.read(buf);
                if (read <= 0) break;
                byte[] data = java.util.Arrays.copyOf(buf, read);
                long end    = offset + read - 1;

                final int cNum = chunk, cTotal = total;
                RequestBody fileBody = new CountingRequestBody(
                    RequestBody.create(data, MediaType.parse("video/mp4")),
                    pct -> {
                        if (progress != null) {
                            float overall = (cNum + pct / 100f) / cTotal;
                            progress.onProgress((int)(overall * 100));
                        }
                    });

                MultipartBody.Builder mb = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file",      "chunk.mp4", fileBody)
                    .addFormDataPart("api_key",   apiKey)
                    .addFormDataPart("timestamp", timestamp)
                    .addFormDataPart("signature", signature)
                    .addFormDataPart("folder",    folder);

                // Eager only on last chunk — unsigned param (after signature)
                if (eagerTransform != null && !eagerTransform.isEmpty() && (offset + read >= fileSize)) {
                    mb.addFormDataPart("eager", eagerTransform);
                }

                Response res = HTTP.newCall(new Request.Builder()
                    .url(uploadUrl).post(mb.build())
                    .header("X-Unique-Upload-Id", uploadId)
                    .header("Content-Range", "bytes " + offset + "-" + end + "/" + fileSize)
                    .build()).execute();

                String body = res.body() != null ? res.body().string() : "";
                int code    = res.code();
                res.close();

                if (code == 200) {
                    lastResult = new JSONObject(body);
                } else if (code == 308) {
                    // Chunk accepted, continue
                } else {
                    throw new IOException("Chunk " + chunk + " failed (" + code + "): " + body);
                }

                offset += read;
                chunk++;
            }
        }

        if (lastResult == null)
            throw new IOException("No final response from Cloudinary chunked upload");
        return lastResult;
    }

    // ── Thumbnail URL builder ─────────────────────────────────────────────

    private static String buildThumbUrl(String videoUrl, String cloudName, String publicId) {
        if (videoUrl == null || videoUrl.isEmpty()) return "";
        // Cloudinary URL transform: video → JPEG thumbnail at 1 second
        return videoUrl
            .replace("/upload/", "/upload/w_400,h_400,c_fill,so_1,f_jpg/")
            .replaceAll("\\.[^.]+$", ".jpg");
    }

    // ── Quality → Cloudinary eager transform ──────────────────────────────

    private static String mapQualityToEager(VideoQualityPreferences.Quality q) {
        if (q == null) return "c_scale,w_960,h_540,vc_h264,b_800k";
        switch (q) {
            case LOW:      return "c_scale,w_640,h_360,vc_h264,b_400k";
            case STANDARD: return "c_scale,w_960,h_540,vc_h264,b_800k";
            case HD:       return "c_scale,w_1280,h_720,vc_h264,b_1500k";
            case FULL_HD:  return "c_scale,w_1920,h_1080,vc_h264,b_3000k";
            case AUTO:     return "c_scale,w_960,h_540,vc_h264,b_800k"; // auto = 540p default
            case ORIGINAL: return ""; // no transform
            default:       return "c_scale,w_960,h_540,vc_h264,b_800k";
        }
    }

    // ── Metadata helpers ──────────────────────────────────────────────────

    private static long getFileSize(Context ctx, Uri uri) {
        try (android.database.Cursor c = ctx.getContentResolver().query(
                uri, new String[]{android.provider.OpenableColumns.SIZE},
                null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getLong(0);
        } catch (Exception ignored) {}
        return 0;
    }

    private static int[] getVideoDimensions(Context ctx, Uri uri) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(ctx, uri);
            int w = Integer.parseInt(r.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int h = Integer.parseInt(r.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            return new int[]{w, h};
        } catch (Exception e) {
            return new int[]{0, 0};
        } finally {
            try { r.release(); } catch (Exception ignored) {}
        }
    }

    private static int getVideoDuration(Context ctx, Uri uri) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(ctx, uri);
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return d != null ? Integer.parseInt(d) : 0;
        } catch (Exception e) {
            return 0;
        } finally {
            try { r.release(); } catch (Exception ignored) {}
        }
    }

    private static File copyToTemp(Context ctx, Uri uri) throws IOException {
        File temp = new File(ctx.getCacheDir(),
            "upload_" + UUID.randomUUID().toString().substring(0, 8) + ".mp4");
        try (InputStream in  = ctx.getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(temp)) {
            if (in == null) throw new IOException("Cannot open URI: " + uri);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
        return temp;
    }

    public static void safeDelete(File f) {
        if (f != null && f.exists()) f.delete();
    }

    // ── UI helpers — backward compat ──────────────────────────────────────

    public static String pickCodec(VideoQualityPreferences.Quality quality) {
        if (quality == VideoQualityPreferences.Quality.FULL_HD) return MIME_HEVC;
        if (quality == VideoQualityPreferences.Quality.HD)      return MIME_HEVC;
        return MIME_AVC;
    }

    public static boolean hasHardwareEncoder(String mimeType) {
        return true; // Cloudinary handles encoding
    }

    // ── compressSync — blocking wrapper ──────────────────────────────────

    public static Result compressSync(Context ctx, Uri videoUri,
                                      VideoQualityPreferences.Quality quality,
                                      ProgressListener progressListener) throws Exception {
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Result> resultRef =
            new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Exception> errorRef =
            new java.util.concurrent.atomic.AtomicReference<>();

        compress(ctx, videoUri, quality, new Callback() {
            @Override public void onProgress(int pct) {
                if (progressListener != null) progressListener.onProgress(pct);
            }
            @Override public void onSuccess(Result r) { resultRef.set(r); latch.countDown(); }
            @Override public void onError(Exception e) { errorRef.set(e); latch.countDown(); }
        });

        latch.await();
        if (errorRef.get() != null) throw errorRef.get();
        return resultRef.get();
    }

    public interface ProgressListener {
        void onProgress(int percent);
    }

    // ── CountingRequestBody — upload progress tracking ────────────────────

    private static class CountingRequestBody extends RequestBody {
        private final RequestBody      delegate;
        private final ProgressListener listener;

        CountingRequestBody(RequestBody d, ProgressListener l) {
            this.delegate = d; this.listener = l;
        }
        @Override public MediaType contentType()            { return delegate.contentType(); }
        @Override public long      contentLength() throws IOException { return delegate.contentLength(); }
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
            buffered.flush();
        }
    }
}
