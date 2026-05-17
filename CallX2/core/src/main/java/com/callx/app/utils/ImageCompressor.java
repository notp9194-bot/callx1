package com.callx.app.utils;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

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
 * ImageCompressor v26 — SERVER-SIDE COMPRESSION (Mobile CPU Zero Load)
 *
 * PEHLE (v25): Mobile pe Bitmap decode + resize + WebP encode → CPU spike ~1-3 sec, mobile warm
 * AB (v26):    Raw image seedha server ke /compress/image pe bhejo →
 *              Server sharp se resize + WebP compress + Cloudinary upload kare →
 *              image_url + thumb_url wapas aaye
 *
 * Mobile CPU LOAD: ~0% (sirf file read + network send)
 * Mobile HEAT: ❄️ Cold — koi Bitmap decode/encode nahi
 *
 * Backward compatible:
 *   Result.fullFile  → null  (server pe compressed)
 *   Result.thumbFile → null  (server pe compressed)
 *   Result.serverImageUrl → full image Cloudinary URL
 *   Result.serverThumbUrl → thumb Cloudinary URL
 */
public class ImageCompressor {

    private static final String TAG              = "ImageCompressor";
    private static final String COMPRESS_ENDPOINT = Constants.SERVER_URL + "/compress/image";

    private static final ExecutorService BG   = Executors.newCachedThreadPool();
    private static final Handler         MAIN = new Handler(Looper.getMainLooper());

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
        .connectTimeout(30,  TimeUnit.SECONDS)
        .readTimeout(120,    TimeUnit.SECONDS)
        .writeTimeout(120,   TimeUnit.SECONDS)
        .build();

    // ── Public types ───────────────────────────────────────────────────────

    public interface Callback {
        void onSuccess(Result result);
        void onError(Exception e);
    }

    public static class Result {
        /** null — server pe compressed */
        public final File   fullFile;
        /** null — server pe compressed */
        public final File   thumbFile;
        public final long   originalBytes;
        public final long   fullBytes;
        public final long   thumbBytes;

        // Server-side fields (v26)
        public final String serverImageUrl;
        public final String serverThumbUrl;

        public Result(long originalBytes, long fullBytes, long thumbBytes,
                      String serverImageUrl, String serverThumbUrl) {
            this.fullFile       = null;
            this.thumbFile      = null;
            this.originalBytes  = originalBytes;
            this.fullBytes      = fullBytes;
            this.thumbBytes     = thumbBytes;
            this.serverImageUrl = serverImageUrl;
            this.serverThumbUrl = serverThumbUrl;
        }

        public String summary() {
            return String.format("Server-sharp %.1fMB → full %.0fKB + thumb %.0fKB",
                originalBytes / 1_000_000f, fullBytes / 1000f, thumbBytes / 1000f);
        }
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /** Compress on background thread, callback on main thread */
    public static void compress(Context ctx, Uri imageUri, Callback callback) {
        BG.execute(() -> {
            File tempFile = null;
            try {
                long originalBytes = getFileSize(ctx, imageUri);

                // Copy content:// → temp file for multipart upload
                tempFile = copyToTemp(ctx, imageUri);

                // POST to server /compress/image
                MultipartBody requestBody = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", tempFile.getName(),
                        RequestBody.create(tempFile, MediaType.parse("image/*")))
                    .addFormDataPart("folder", "callx/image")
                    .build();

                Request req = new Request.Builder()
                    .url(COMPRESS_ENDPOINT)
                    .post(requestBody)
                    .build();

                Response res  = HTTP.newCall(req).execute();
                String resBody = res.body() != null ? res.body().string() : "";
                res.close();

                if (!res.isSuccessful()) {
                    throw new IOException("Server compress failed " + res.code() + ": " + resBody);
                }

                JSONObject json       = new JSONObject(resBody);
                String serverImageUrl = json.getString("image_url");
                String serverThumbUrl = json.optString("thumb_url", "");
                long   compBytes      = json.optLong("compressed_bytes", 0);
                long   thumbBytes     = json.optLong("thumb_bytes", 0);

                Result result = new Result(originalBytes, compBytes, thumbBytes,
                    serverImageUrl, serverThumbUrl);

                Log.i(TAG, result.summary());
                MAIN.post(() -> callback.onSuccess(result));

            } catch (Exception e) {
                Log.e(TAG, "Server image compress failed", e);
                MAIN.post(() -> callback.onError(e));
            } finally {
                safeDelete(tempFile);
            }
        });
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static long getFileSize(Context ctx, Uri uri) {
        try (android.database.Cursor c = ctx.getContentResolver().query(
                uri, new String[]{android.provider.OpenableColumns.SIZE},
                null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getLong(0);
        } catch (Exception ignored) {}
        return 0;
    }

    private static File copyToTemp(Context ctx, Uri uri) throws IOException {
        File temp = new File(ctx.getCacheDir(),
            "img_upload_" + UUID.randomUUID().toString().substring(0, 8) + ".jpg");
        try (InputStream in  = ctx.getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(temp)) {
            if (in == null) throw new IOException("Cannot open URI: " + uri);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
        return temp;
    }

    private static void safeDelete(File f) {
        if (f != null && f.exists()) f.delete();
    }
}
