package com.callx.app.utils;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;
import com.google.firebase.storage.UploadTask;

import java.io.File;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ImageUploader — WhatsApp-level dual upload system
 *
 * FLOW:
 *   ImageCompressor.Result
 *       ↓
 *   Upload thumb → Firebase: images/thumb/{id}.webp
 *   Upload full  → Firebase: images/full/{id}.webp
 *       ↓
 *   Callback with both download URLs
 *
 * Features:
 *  ✅ Dual file upload (thumb + full)
 *  ✅ Progress callback (0-100%)
 *  ✅ Auto retry (3 times on failure)
 *  ✅ Network check before upload
 *  ✅ Cleanup temp files after success
 */
public class ImageUploader {

    private static final String TAG       = "ImageUploader";
    private static final int    MAX_RETRY = 3;

    public interface UploadCallback {
        void onProgress(int percent);                           // 0-100 overall
        void onSuccess(String thumbUrl, String fullUrl);        // both URLs ready
        void onError(Exception e);
    }

    public static class UploadResult {
        public final String thumbUrl;
        public final String fullUrl;
        public UploadResult(String t, String f) { thumbUrl = t; fullUrl = f; }
    }

    // ── Main entry: upload both files ────────────────────────────────────

    public static void upload(Context ctx,
                              ImageCompressor.Result compressed,
                              UploadCallback callback) {
        String id = UUID.randomUUID().toString();
        uploadBoth(ctx, compressed, id, callback, 1);
    }

    private static void uploadBoth(Context ctx,
                                   ImageCompressor.Result compressed,
                                   String imageId,
                                   UploadCallback callback,
                                   int attempt) {

        if (!NetworkUtils.isOnline(ctx)) {
            callback.onError(new Exception("No network"));
            return;
        }

        FirebaseStorage storage = FirebaseStorage.getInstance();

        StorageReference thumbRef = storage.getReference()
            .child("images/thumb/" + imageId + ".webp");
        StorageReference fullRef  = storage.getReference()
            .child("images/full/"  + imageId + ".webp");

        // Track both uploads
        final String[] thumbUrl = {null};
        final String[] fullUrl  = {null};
        AtomicInteger  done     = new AtomicInteger(0);

        // Progress: thumb = 0-40%, full = 40-100%
        uploadFile(thumbRef, compressed.thumbFile, 0, 40,
            callback,
            url -> {
                thumbUrl[0] = url;
                if (done.incrementAndGet() == 2) {
                    cleanupAndDeliver(compressed, thumbUrl[0], fullUrl[0], callback);
                }
            },
            e -> retryOrFail(ctx, compressed, imageId, callback, attempt, e)
        );

        uploadFile(fullRef, compressed.fullFile, 40, 100,
            callback,
            url -> {
                fullUrl[0] = url;
                if (done.incrementAndGet() == 2) {
                    cleanupAndDeliver(compressed, thumbUrl[0], fullUrl[0], callback);
                }
            },
            e -> retryOrFail(ctx, compressed, imageId, callback, attempt, e)
        );
    }

    // ── Single file upload with progress ─────────────────────────────────

    private static void uploadFile(StorageReference ref,
                                   File file,
                                   int progressStart,
                                   int progressEnd,
                                   UploadCallback callback,
                                   SuccessListener onSuccess,
                                   FailureListener onFailure) {

        Uri fileUri = Uri.fromFile(file);
        UploadTask task = ref.putFile(fileUri);

        task.addOnProgressListener(snap -> {
            double pct = 100.0 * snap.getBytesTransferred() / snap.getTotalByteCount();
            int overall = progressStart + (int)((progressEnd - progressStart) * pct / 100.0);
            callback.onProgress(Math.min(overall, progressEnd));
        });

        task.continueWithTask(t -> {
            if (!t.isSuccessful()) throw t.getException();
            return ref.getDownloadUrl();
        }).addOnSuccessListener(uri -> {
            Log.d(TAG, "Uploaded: " + uri.toString());
            onSuccess.onSuccess(uri.toString());
        }).addOnFailureListener(e -> {
            Log.e(TAG, "Upload failed: " + e.getMessage());
            onFailure.onFailure(e);
        });
    }

    // ── Retry logic ───────────────────────────────────────────────────────

    private static void retryOrFail(Context ctx,
                                    ImageCompressor.Result compressed,
                                    String imageId,
                                    UploadCallback callback,
                                    int attempt,
                                    Exception e) {
        if (attempt < MAX_RETRY) {
            Log.w(TAG, "Retry " + attempt + " of " + MAX_RETRY);
            // Exponential backoff: 2s, 4s, 8s
            long delay = (long) Math.pow(2, attempt) * 1000L;
            new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(() -> uploadBoth(ctx, compressed, imageId,
                    callback, attempt + 1), delay);
        } else {
            callback.onError(e);
        }
    }

    // ── Cleanup temp files after success ─────────────────────────────────

    private static void cleanupAndDeliver(ImageCompressor.Result result,
                                          String thumbUrl,
                                          String fullUrl,
                                          UploadCallback callback) {
        // Delete temp cache files
        if (result.thumbFile.exists()) result.thumbFile.delete();
        if (result.fullFile.exists())  result.fullFile.delete();

        callback.onProgress(100);
        callback.onSuccess(thumbUrl, fullUrl);
    }

    // ── Functional interfaces ─────────────────────────────────────────────

    interface SuccessListener { void onSuccess(String url); }
    interface FailureListener { void onFailure(Exception e); }
}
