package com.callx.app.utils;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.callx.app.utils.CloudinaryUploader;
import com.callx.app.utils.MediaCompressor;

/**
 * ReelCloudinaryUtils — Reels profile ke liye dedicated Cloudinary upload helper.
 *
 * Cloudinary folder structure:
 *   Avatar thumb  → callx/reels/avatars/thumbs/
 *   Avatar full   → callx/reels/avatars/
 *   Banner image  → callx/reels/banners/
 *   Slideshow     → callx/reels/slideshows/
 *
 * Upload logic: CloudinaryUploader.uploadBytes() delegate karta hai (core) —
 * duplicate sign+upload code yahan nahi rakhा gaya hai.
 */
public class ReelCloudinaryUtils {

    private static final String TAG = "ReelCloudinary";
    private static final Handler UI = new Handler(Looper.getMainLooper());

    // ── Callbacks ─────────────────────────────────────────────────────────────

    /** Avatar dual-step upload callback */
    public interface AvatarUploadCallback {
        void onThumbReady(String thumbUrl);   // Step 1 done — 100x100 WebP
        void onFullReady(String photoUrl);    // Step 2 done — 800x800 JPEG
        void onError(String message);
    }

    /** Single image (banner / slideshow) upload callback */
    public interface ImageUploadCallback {
        void onSuccess(String url);
        void onError(String message);
    }

    // ── Avatar Upload ─────────────────────────────────────────────────────────

    /**
     * Reel profile avatar dual-upload:
     *   Step 1 → 100×100 WebP thumb  → callx/reels/avatars/thumbs/
     *   Step 2 → 800×800 JPEG full   → callx/reels/avatars/
     *
     * Delegates byte-level upload to CloudinaryUploader.uploadBytes() (core).
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
                String thumbUrl = CloudinaryUploader.uploadBytes(
                    thumbBytes, "image/webp", "reel_thumb.webp",
                    "callx/reels/avatars/thumbs", "image");
                if (thumbUrl == null) { postErr(cb, "Thumb upload failed"); return; }
                UI.post(() -> cb.onThumbReady(thumbUrl));

                // Step 2: Full photo (800px, JPEG 85%)
                byte[] fullBytes = MediaCompressor.compressImageWithQuality(ctx, uri, 800, 85, false);
                if (fullBytes == null || fullBytes.length == 0) {
                    postErr(cb, "Full photo compress failed");
                    return;
                }
                String photoUrl = CloudinaryUploader.uploadBytes(
                    fullBytes, "image/jpeg", "reel_photo.jpg",
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
                String url = CloudinaryUploader.uploadBytes(
                    bytes, "image/jpeg",
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
     *   Compressed to 1200px JPEG 80% → callx/reels/banners/
     */
    public static void uploadReelBanner(Context ctx, Uri uri, ImageUploadCallback cb) {
        new Thread(() -> {
            try {
                byte[] bannerBytes = MediaCompressor.compressImageWithQuality(ctx, uri, 1200, 80, false);
                if (bannerBytes == null || bannerBytes.length == 0) {
                    UI.post(() -> cb.onError("Banner compress failed"));
                    return;
                }
                String bannerUrl = CloudinaryUploader.uploadBytes(
                    bannerBytes, "image/jpeg", "reel_banner.jpg",
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

    private static void postErr(AvatarUploadCallback cb, String msg) {
        UI.post(() -> cb.onError(msg));
    }
}
