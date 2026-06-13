package com.callx.app.utils;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.callx.app.utils.Constants;
import com.callx.app.utils.MediaCompressor;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

/**
 * ReelCloudinaryUtils — Reels profile ke liye dedicated Cloudinary upload helper.
 *
 * Cloudinary folder structure:
 *   Avatar thumb  → callx/reels/avatars/thumbs/
 *   Avatar full   → callx/reels/avatars/
 *   Banner image  → callx/reels/banners/
 *
 * Same signed-upload flow as CloudinaryUploader.java.
 * Server endpoint: Constants.SERVER_URL + /cloudinary/sign
 */
public class ReelCloudinaryUtils {

    private static final String TAG = "ReelCloudinary";

    private static final OkHttpClient client = new OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .build();

    private static final Handler UI = new Handler(Looper.getMainLooper());

    // ── Callbacks ─────────────────────────────────────────────────────────────

    /** Avatar dual-step upload callback */
    public interface AvatarUploadCallback {
        void onThumbReady(String thumbUrl);   // Step 1 done — 100x100 WebP
        void onFullReady(String photoUrl);    // Step 2 done — 800x800 JPEG
        void onError(String message);
    }

    /** Single image (banner) upload callback */
    public interface ImageUploadCallback {
        void onSuccess(String url);
        void onError(String message);
    }

    // ── Avatar Upload ─────────────────────────────────────────────────────────

    /**
     * Reel profile avatar dual-upload:
     *   Step 1 → 100×100 WebP thumb  → callx/reels/avatars/thumbs/
     *   Step 2 → 800×800 JPEG full   → callx/reels/avatars/
     */
    public static void uploadReelAvatar(Context ctx, Uri uri, AvatarUploadCallback cb) {
        new Thread(() -> {
            try {
                // Step 1: Thumbnail (100px, WebP 60%)
                byte[] thumbBytes = MediaCompressor.compressImageWithQuality(ctx, uri, 100, 60, true);
                if (thumbBytes == null || thumbBytes.length == 0) {
                    postErr(cb, "Thumb compress failed");
                    return;
                }
                String thumbUrl = uploadBytes(thumbBytes, "image/webp", "reel_thumb.webp",
                    "callx/reels/avatars/thumbs", "image");
                if (thumbUrl == null) { postErr(cb, "Thumb upload failed"); return; }
                UI.post(() -> cb.onThumbReady(thumbUrl));

                // Step 2: Full photo (800px, JPEG 85%)
                byte[] fullBytes = MediaCompressor.compressImageWithQuality(ctx, uri, 800, 85, false);
                if (fullBytes == null || fullBytes.length == 0) {
                    postErr(cb, "Full photo compress failed");
                    return;
                }
                String photoUrl = uploadBytes(fullBytes, "image/jpeg", "reel_photo.jpg",
                    "callx/reels/avatars", "image");
                if (photoUrl == null) { postErr(cb, "Full photo upload failed"); return; }
                UI.post(() -> cb.onFullReady(photoUrl));

            } catch (Exception e) {
                Log.e(TAG, "uploadReelAvatar error", e);
                postErr(cb, e.getMessage() != null ? e.getMessage() : "Upload error");
            }
        }).start();
    }

    // ── Reel Slideshow Photo Upload ───────────────────────────────────────────

    /**
     * Reels photo slideshow ke liye ek photo upload karta hai.
     * Compressed to 1080px JPEG 85% → callx/reels/slideshows/
     * Callback main thread pe fire hota hai.
     */
    public static void uploadReelSlideshowPhoto(Context ctx, Uri uri, int index,
                                                 ImageUploadCallback cb) {
        new Thread(() -> {
            try {
                byte[] bytes = MediaCompressor.compressImageWithQuality(ctx, uri, 1080, 85, false);
                if (bytes == null || bytes.length == 0) {
                    UI.post(() -> cb.onError("Compress failed for photo " + index));
                    return;
                }
                String url = uploadBytes(bytes, "image/jpeg",
                    "reel_slide_" + index + ".jpg",
                    "callx/reels/slideshows", "image");
                if (url == null) {
                    UI.post(() -> cb.onError("Upload failed for photo " + index));
                    return;
                }
                UI.post(() -> cb.onSuccess(url));
            } catch (Exception e) {
                Log.e(TAG, "uploadReelSlideshowPhoto error idx=" + index, e);
                UI.post(() -> cb.onError(e.getMessage() != null ? e.getMessage() : "Upload error"));
            }
        }).start();
    }

    // ── Banner Upload ─────────────────────────────────────────────────────────

    /**
     * Reel profile banner upload:
     *   Compressed to 1200×400 JPEG → callx/reels/banners/
     */
    public static void uploadReelBanner(Context ctx, Uri uri, ImageUploadCallback cb) {
        new Thread(() -> {
            try {
                byte[] bannerBytes = MediaCompressor.compressImageWithQuality(ctx, uri, 1200, 80, false);
                if (bannerBytes == null || bannerBytes.length == 0) {
                    UI.post(() -> cb.onError("Banner compress failed"));
                    return;
                }
                String bannerUrl = uploadBytes(bannerBytes, "image/jpeg", "reel_banner.jpg",
                    "callx/reels/banners", "image");
                if (bannerUrl == null) {
                    UI.post(() -> cb.onError("Banner upload failed"));
                    return;
                }
                UI.post(() -> cb.onSuccess(bannerUrl));
            } catch (Exception e) {
                Log.e(TAG, "uploadReelBanner error", e);
                UI.post(() -> cb.onError(e.getMessage() != null ? e.getMessage() : "Upload error"));
            }
        }).start();
    }

    // ── Internal: byte[] → Cloudinary → secureUrl ────────────────────────────

    private static String uploadBytes(byte[] bytes, String mime, String filename,
                                      String folder, String resourceType) {
        try {
            // Step 1: Sign
            JSONObject payload = new JSONObject()
                .put("folder", folder)
                .put("resource_type", resourceType);
            Request signReq = new Request.Builder()
                .url(Constants.SERVER_URL + "/cloudinary/sign")
                .post(RequestBody.create(payload.toString(), MediaType.parse("application/json")))
                .build();
            Response signRes = client.newCall(signReq).execute();
            String signBody = signRes.body() != null ? signRes.body().string() : "";
            signRes.close();
            if (!signRes.isSuccessful()) {
                Log.e(TAG, "Sign failed (" + signRes.code() + "): " + signBody);
                return null;
            }
            JSONObject signJson = new JSONObject(signBody);
            String signature = signJson.getString("signature");
            String timestamp = signJson.getString("timestamp");
            String apiKey    = signJson.getString("api_key");
            String cloudName = signJson.optString("cloud_name", Constants.CLOUDINARY_CLOUD_NAME);
            String f         = signJson.optString("folder", folder);

            // Step 2: Upload
            MultipartBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", filename,
                    RequestBody.create(bytes, MediaType.parse(mime)))
                .addFormDataPart("api_key", apiKey)
                .addFormDataPart("timestamp", timestamp)
                .addFormDataPart("signature", signature)
                .addFormDataPart("folder", f)
                .build();

            String upUrl = "https://api.cloudinary.com/v1_1/" + cloudName + "/" + resourceType + "/upload";
            Request upReq = new Request.Builder().url(upUrl).post(body).build();
            Response upRes = client.newCall(upReq).execute();
            String upBody = upRes.body() != null ? upRes.body().string() : "";
            upRes.close();
            if (!upRes.isSuccessful()) {
                Log.e(TAG, "Upload failed: " + upBody);
                return null;
            }
            JSONObject upJson = new JSONObject(upBody);
            String url = upJson.optString("secure_url", upJson.optString("url"));
            return (url == null || url.isEmpty()) ? null : url;

        } catch (Exception e) {
            Log.e(TAG, "uploadBytes error: " + e.getMessage());
            return null;
        }
    }

    private static void postErr(AvatarUploadCallback cb, String msg) {
        UI.post(() -> cb.onError(msg));
    }
}
