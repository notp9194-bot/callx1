package com.callx.app.utils;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;

import androidx.annotation.Nullable;

import com.callx.app.models.Message;

import java.io.ByteArrayOutputStream;
import java.io.File;

/**
 * v873 — WhatsApp-style INLINE video poster.
 *
 * Instead of uploading a separate thumbnail file to Cloudinary (one extra
 * upload on the sender, one extra download on the receiver), a tiny JPEG frame
 * is embedded in the message itself (Message#inlineThumbB64, or inside the
 * encrypted key envelope for E2E chats). The receiver decodes it locally in a
 * few ms — zero network, zero Cloudinary credits.
 */
public final class VideoInlineThumb {
    private VideoInlineThumb() {}

    /** Longest side of the inline poster in px. Small on purpose: the real
     *  frame is shown instantly, and this is smoothed by the adaptive blur. */
    private static final int MAX_SIDE_PX = 160;
    /** Hard cap on the raw JPEG size (bytes) so the message doc stays light. */
    private static final int MAX_JPEG_BYTES = 6 * 1024;
    private static final int[] QUALITY_STEPS = {55, 45, 35, 28, 22, 16};

    /**
     * Builds the Base64 JPEG poster from the already-extracted frame file
     * (VideoCompressor.Result#thumbFile) — no extra frame extraction.
     * Returns null on any failure; callers then fall back to the legacy
     * thumbnail-upload path.
     */
    @Nullable
    public static String buildInlineJpegB64(@Nullable File thumbFile) {
        return buildInlineJpegB64(thumbFile, MAX_SIDE_PX, MAX_JPEG_BYTES);
    }

    /** Max chars of the ratchet-encrypted media key envelope that Firebase rules
     *  accept (messages/$chatId/$msgId/mediaKeyEnc ".validate" length <= 4000).
     *  Keep a safety margin below it. */
    public static final int MAX_E2E_ENVELOPE_CHARS = 3800;

    /** Tiny poster for the E2E envelope: must stay far below the 4000-char rule. */
    @Nullable
    public static String buildTinyE2eJpegB64(@Nullable File thumbFile) {
        return buildInlineJpegB64(thumbFile, 80, 1400);
    }

    @Nullable
    public static String buildInlineJpegB64(@Nullable File thumbFile, int maxSidePx, int maxJpegBytes) {
        if (thumbFile == null || !thumbFile.exists() || thumbFile.length() == 0) return null;
        Bitmap src = null, scaled = null;
        try {
            BitmapFactory.Options b = new BitmapFactory.Options();
            b.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(thumbFile.getAbsolutePath(), b);
            if (b.outWidth <= 0 || b.outHeight <= 0) return null;
            int sample = 1;
            while (Math.max(b.outWidth, b.outHeight) / (sample * 2) >= maxSidePx) sample *= 2;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            src = BitmapFactory.decodeFile(thumbFile.getAbsolutePath(), o);
            if (src == null) return null;
            int w = src.getWidth(), h = src.getHeight();
            float scale = (float) maxSidePx / Math.max(w, h);
            if (scale < 1f) {
                scaled = Bitmap.createScaledBitmap(src, Math.max(1, Math.round(w * scale)),
                        Math.max(1, Math.round(h * scale)), true);
            } else {
                scaled = src;
            }
            byte[] best = null;
            for (int q : QUALITY_STEPS) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream(8 * 1024);
                scaled.compress(Bitmap.CompressFormat.JPEG, q, bos);
                best = bos.toByteArray();
                if (best.length <= maxJpegBytes) break;
            }
            if (best == null || best.length == 0 || best.length > maxJpegBytes * 2) return null;
            return Base64.encodeToString(best, Base64.NO_WRAP);
        } catch (Throwable t) {
            return null;
        } finally {
            if (scaled != null && scaled != src && !scaled.isRecycled()) scaled.recycle();
            if (src != null && !src.isRecycled()) src.recycle();
        }
    }

    /**
     * Call right before handing a Message to Firebase (setValue snapshots the
     * object synchronously). Hides local-only data from the wire:
     *  - a device-local {@code file://} thumbnailUrl (any type), and
     *  - the plaintext inline poster when the message is media-E2E (the
     *    poster then travels only inside the encrypted envelope).
     * Returns the saved values for {@link #restoreAfterWire}.
     */
    public static String[] stripForWire(@Nullable Message m) {
        if (m == null) return null;
        String[] saved = { m.thumbnailUrl, m.inlineThumbB64 };
        if (m.thumbnailUrl != null && m.thumbnailUrl.startsWith("file://")) m.thumbnailUrl = null;
        if (m.mediaKeyEnc != null && !m.mediaKeyEnc.isEmpty()) m.inlineThumbB64 = null;
        return saved;
    }

    public static void restoreAfterWire(@Nullable Message m, @Nullable String[] saved) {
        if (m == null || saved == null) return;
        m.thumbnailUrl = saved[0];
        m.inlineThumbB64 = saved[1];
    }
}
