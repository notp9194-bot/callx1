package com.callx.app.utils;

import android.graphics.Bitmap;
import android.util.Base64;

/**
 * ThumbHash — encodes an image into a very short byte string (~25 bytes for a
 * typical opaque photo, base64'd to ~34 chars) that decodes back into a small,
 * color-accurate preview of the original. Replaces {@link BlurHash} for the
 * chat media placeholder pipeline.
 *
 * Why swap from BlurHash:
 *  - Smaller payload for equivalent/better quality (DCT + LPQA color space
 *    instead of raw RGB cosine basis — sharper edges, more accurate color).
 *  - Also encodes the image's aspect ratio, so the placeholder itself can be
 *    drawn at roughly the right shape before any real dimensions are known.
 *  - Native alpha-channel support (stickers / transparent PNGs), which
 *    BlurHash never had here.
 *
 * API shape intentionally mirrors BlurHash.java (Bitmap in → String out,
 * String in → Bitmap out) so call sites are a drop-in swap:
 *   String hash = ThumbHash.encode(bitmap);
 *   Bitmap placeholder = ThumbHash.decode(hash, 32, 32);
 *
 * Core encode/decode math below is a faithful port of the reference
 * implementation (evanw/thumbhash, MIT license, com.madebyevan.thumbhash) —
 * ThumbHash's parameters are fixed/auto-configured by design (unlike
 * BlurHash's componentX/componentY), so there's no tuning knob to expose.
 */
public final class ThumbHash {

    private ThumbHash() {}

    // ── Public Android wrapper ──────────────────────────────────────────

    /**
     * Encodes a bitmap into a base64 ThumbHash string, suitable for storing
     * in Message#blurHash / the E2E key envelope exactly like the old
     * BlurHash string was. Downscales internally to fit the algorithm's
     * 100x100 cap (chat thumbs are already tiny by the time this is called,
     * so this is normally a no-op resize).
     * Returns null on a null/empty bitmap.
     */
    public static String encode(Bitmap bitmap) {
        if (bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) return null;
        try {
            Bitmap src = bitmap;
            int w = src.getWidth(), h = src.getHeight();
            if (w > 100 || h > 100) {
                float scale = 100f / Math.max(w, h);
                int nw = Math.max(1, Math.round(w * scale));
                int nh = Math.max(1, Math.round(h * scale));
                src = Bitmap.createScaledBitmap(src, nw, nh, true);
                w = nw; h = nh;
            }
            int[] pixels = new int[w * h];
            src.getPixels(pixels, 0, w, 0, 0, w, h);
            byte[] rgba = new byte[w * h * 4];
            for (int i = 0, j = 0; i < pixels.length; i++, j += 4) {
                int p = pixels[i];
                rgba[j]     = (byte) ((p >> 16) & 0xFF); // R
                rgba[j + 1] = (byte) ((p >> 8) & 0xFF);  // G
                rgba[j + 2] = (byte) (p & 0xFF);         // B
                rgba[j + 3] = (byte) ((p >> 24) & 0xFF); // A
            }
            if (src != bitmap) src.recycle();
            byte[] hash = rgbaToThumbHash(w, h, rgba);
            return Base64.encodeToString(hash, Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Decodes a base64 ThumbHash string into a bitmap scaled to the
     * requested width/height. Returns null if the string is malformed
     * (caller falls back gracefully, same contract as BlurHash.decode).
     */
    public static Bitmap decode(String base64Hash, int width, int height) {
        if (base64Hash == null || base64Hash.isEmpty()) return null;
        try {
            byte[] hash = Base64.decode(base64Hash, Base64.NO_WRAP);
            if (hash.length < 5) return null;
            Image img = thumbHashToRGBA(hash);
            int[] pixels = new int[img.width * img.height];
            for (int i = 0, j = 0; i < pixels.length; i++, j += 4) {
                int r = img.rgba[j] & 0xFF;
                int g = img.rgba[j + 1] & 0xFF;
                int b = img.rgba[j + 2] & 0xFF;
                int a = img.rgba[j + 3] & 0xFF;
                pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
            }
            // PERF (advance #2): RGB_565 instead of ARGB_8888 — half the
            // memory per placeholder and a faster blur pass downstream
            // (2 bytes/pixel vs 4). Only safe for opaque hashes though:
            // RGB_565 has no alpha channel, so a transparent-PNG/sticker
            // hash (img.hasAlpha) still decodes as ARGB_8888 or its
            // transparency would flatten to solid color.
            Bitmap.Config cfg = img.hasAlpha ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
            Bitmap raw = Bitmap.createBitmap(img.width, img.height, cfg);
            raw.setPixels(pixels, 0, img.width, 0, 0, img.width, img.height);
            // BUG FIX (all-aspect-ratio placeholder): img.width/img.height
            // above already carry the image's REAL aspect ratio — decoded
            // straight from the ratio bits ThumbHash stores in its header
            // (see thumbHashToRGBA()). Every call site here always passes a
            // fixed square (width=height=32), so blindly stretching raw to
            // that exact box with Bitmap.createScaledBitmap(raw, width,
            // height, ...) squashed every non-1:1 photo into a square,
            // throwing away the very aspect-ratio data ThumbHash exists to
            // preserve (see this class's javadoc). Fit the aspect-correct
            // raw bitmap inside the requested box instead — scale both
            // dimensions by the same factor so the ratio survives; the
            // caller (MediaRenderer) already center-crops whatever this
            // returns into the bubble's real, non-square mediaRect, so an
            // exact width x height match was never actually required.
            float fitScale = Math.min((float) width / img.width, (float) height / img.height);
            int outW = Math.max(1, Math.round(img.width * fitScale));
            int outH = Math.max(1, Math.round(img.height * fitScale));
            if (outW == img.width && outH == img.height) return raw;
            Bitmap scaled = Bitmap.createScaledBitmap(raw, outW, outH, true);
            raw.recycle();
            return scaled;
        } catch (Exception e) {
            return null; // malformed hash — caller falls back gracefully
        }
    }

    // ── Core algorithm (ported from evanw/thumbhash, MIT license) ───────

    private static byte[] rgbaToThumbHash(int w, int h, byte[] rgba) {
        if (w > 100 || h > 100) throw new IllegalArgumentException(w + "x" + h + " doesn't fit in 100x100");

        float avg_r = 0, avg_g = 0, avg_b = 0, avg_a = 0;
        for (int i = 0, j = 0; i < w * h; i++, j += 4) {
            float alpha = (rgba[j + 3] & 255) / 255.0f;
            avg_r += alpha / 255.0f * (rgba[j] & 255);
            avg_g += alpha / 255.0f * (rgba[j + 1] & 255);
            avg_b += alpha / 255.0f * (rgba[j + 2] & 255);
            avg_a += alpha;
        }
        if (avg_a > 0) {
            avg_r /= avg_a;
            avg_g /= avg_a;
            avg_b /= avg_a;
        }

        boolean hasAlpha = avg_a < w * h;
        int l_limit = hasAlpha ? 5 : 7;
        int lx = Math.max(1, Math.round((float) (l_limit * w) / (float) Math.max(w, h)));
        int ly = Math.max(1, Math.round((float) (l_limit * h) / (float) Math.max(w, h)));
        float[] l = new float[w * h];
        float[] p = new float[w * h];
        float[] q = new float[w * h];
        float[] a = new float[w * h];

        for (int i = 0, j = 0; i < w * h; i++, j += 4) {
            float alpha = (rgba[j + 3] & 255) / 255.0f;
            float r = avg_r * (1.0f - alpha) + alpha / 255.0f * (rgba[j] & 255);
            float g = avg_g * (1.0f - alpha) + alpha / 255.0f * (rgba[j + 1] & 255);
            float b = avg_b * (1.0f - alpha) + alpha / 255.0f * (rgba[j + 2] & 255);
            l[i] = (r + g + b) / 3.0f;
            p[i] = (r + g) / 2.0f - b;
            q[i] = r - g;
            a[i] = alpha;
        }

        Channel l_channel = new Channel(Math.max(3, lx), Math.max(3, ly)).encode(w, h, l);
        Channel p_channel = new Channel(3, 3).encode(w, h, p);
        Channel q_channel = new Channel(3, 3).encode(w, h, q);
        Channel a_channel = hasAlpha ? new Channel(5, 5).encode(w, h, a) : null;

        boolean isLandscape = w > h;
        int header24 = Math.round(63.0f * l_channel.dc)
                | (Math.round(31.5f + 31.5f * p_channel.dc) << 6)
                | (Math.round(31.5f + 31.5f * q_channel.dc) << 12)
                | (Math.round(31.0f * l_channel.scale) << 18)
                | (hasAlpha ? 1 << 23 : 0);
        int header16 = (isLandscape ? ly : lx)
                | (Math.round(63.0f * p_channel.scale) << 3)
                | (Math.round(63.0f * q_channel.scale) << 9)
                | (isLandscape ? 1 << 15 : 0);

        int ac_start = hasAlpha ? 6 : 5;
        int ac_count = l_channel.ac.length + p_channel.ac.length + q_channel.ac.length
                + (hasAlpha ? a_channel.ac.length : 0);
        byte[] hash = new byte[ac_start + (ac_count + 1) / 2];
        hash[0] = (byte) header24;
        hash[1] = (byte) (header24 >> 8);
        hash[2] = (byte) (header24 >> 16);
        hash[3] = (byte) header16;
        hash[4] = (byte) (header16 >> 8);
        if (hasAlpha) hash[5] = (byte) (Math.round(15.0f * a_channel.dc)
                | (Math.round(15.0f * a_channel.scale) << 4));

        int ac_index = 0;
        ac_index = l_channel.writeTo(hash, ac_start, ac_index);
        ac_index = p_channel.writeTo(hash, ac_start, ac_index);
        ac_index = q_channel.writeTo(hash, ac_start, ac_index);
        if (hasAlpha) a_channel.writeTo(hash, ac_start, ac_index);

        return hash;
    }

    private static Image thumbHashToRGBA(byte[] hash) {
        int header24 = (hash[0] & 255) | ((hash[1] & 255) << 8) | ((hash[2] & 255) << 16);
        int header16 = (hash[3] & 255) | ((hash[4] & 255) << 8);
        float l_dc = (float) (header24 & 63) / 63.0f;
        float p_dc = (float) ((header24 >> 6) & 63) / 31.5f - 1.0f;
        float q_dc = (float) ((header24 >> 12) & 63) / 31.5f - 1.0f;
        float l_scale = (float) ((header24 >> 18) & 31) / 31.0f;
        boolean hasAlpha = (header24 >> 23) != 0;
        float p_scale = (float) ((header16 >> 3) & 63) / 63.0f;
        float q_scale = (float) ((header16 >> 9) & 63) / 63.0f;
        boolean isLandscape = (header16 >> 15) != 0;
        int lx = Math.max(3, isLandscape ? (hasAlpha ? 5 : 7) : header16 & 7);
        int ly = Math.max(3, isLandscape ? header16 & 7 : (hasAlpha ? 5 : 7));
        float a_dc = hasAlpha ? (float) (hash[5] & 15) / 15.0f : 1.0f;
        float a_scale = (float) ((hash[5] >> 4) & 15) / 15.0f;

        int ac_start = hasAlpha ? 6 : 5;
        int ac_index = 0;
        Channel l_channel = new Channel(lx, ly);
        Channel p_channel = new Channel(3, 3);
        Channel q_channel = new Channel(3, 3);
        Channel a_channel = null;
        ac_index = l_channel.decode(hash, ac_start, ac_index, l_scale);
        ac_index = p_channel.decode(hash, ac_start, ac_index, p_scale * 1.25f);
        ac_index = q_channel.decode(hash, ac_start, ac_index, q_scale * 1.25f);
        if (hasAlpha) {
            a_channel = new Channel(5, 5);
            a_channel.decode(hash, ac_start, ac_index, a_scale);
        }

        float[] l_ac = l_channel.ac;
        float[] p_ac = p_channel.ac;
        float[] q_ac = q_channel.ac;
        float[] a_ac = hasAlpha ? a_channel.ac : null;

        float ratio = thumbHashToApproximateAspectRatio(hash);
        int w = Math.round(ratio > 1.0f ? 32.0f : 32.0f * ratio);
        int h = Math.round(ratio > 1.0f ? 32.0f / ratio : 32.0f);
        byte[] rgba = new byte[w * h * 4];
        int cx_stop = Math.max(lx, hasAlpha ? 5 : 3);
        int cy_stop = Math.max(ly, hasAlpha ? 5 : 3);
        float[] fx = new float[cx_stop];
        float[] fy = new float[cy_stop];

        for (int y = 0, i = 0; y < h; y++) {
            for (int x = 0; x < w; x++, i += 4) {
                float l = l_dc, p = p_dc, q = q_dc, a = a_dc;

                for (int cx = 0; cx < cx_stop; cx++)
                    fx[cx] = (float) Math.cos(Math.PI / w * (x + 0.5f) * cx);
                for (int cy = 0; cy < cy_stop; cy++)
                    fy[cy] = (float) Math.cos(Math.PI / h * (y + 0.5f) * cy);

                for (int cy = 0, j = 0; cy < ly; cy++) {
                    float fy2 = fy[cy] * 2.0f;
                    for (int cx = cy > 0 ? 0 : 1; cx * ly < lx * (ly - cy); cx++, j++)
                        l += l_ac[j] * fx[cx] * fy2;
                }

                for (int cy = 0, j = 0; cy < 3; cy++) {
                    float fy2 = fy[cy] * 2.0f;
                    for (int cx = cy > 0 ? 0 : 1; cx < 3 - cy; cx++, j++) {
                        float f = fx[cx] * fy2;
                        p += p_ac[j] * f;
                        q += q_ac[j] * f;
                    }
                }

                if (hasAlpha)
                    for (int cy = 0, j = 0; cy < 5; cy++) {
                        float fy2 = fy[cy] * 2.0f;
                        for (int cx = cy > 0 ? 0 : 1; cx < 5 - cy; cx++, j++)
                            a += a_ac[j] * fx[cx] * fy2;
                    }

                float b = l - 2.0f / 3.0f * p;
                float r = (3.0f * l - b + q) / 2.0f;
                float g = r - q;

                rgba[i] = (byte) Math.max(0, Math.round(255.0f * Math.min(1, r)));
                rgba[i + 1] = (byte) Math.max(0, Math.round(255.0f * Math.min(1, g)));
                rgba[i + 2] = (byte) Math.max(0, Math.round(255.0f * Math.min(1, b)));
                rgba[i + 3] = (byte) Math.max(0, Math.round(255.0f * Math.min(1, a)));
            }
        }

        return new Image(w, h, rgba, hasAlpha);
    }

    private static float thumbHashToApproximateAspectRatio(byte[] hash) {
        byte header = hash[3];
        boolean hasAlpha = (hash[2] & 0x80) != 0;
        boolean isLandscape = (hash[4] & 0x80) != 0;
        int lx = isLandscape ? (hasAlpha ? 5 : 7) : header & 7;
        int ly = isLandscape ? header & 7 : (hasAlpha ? 5 : 7);
        return (float) lx / (float) ly;
    }

    private static final class Image {
        int width, height;
        byte[] rgba;
        boolean hasAlpha;
        Image(int width, int height, byte[] rgba, boolean hasAlpha) {
            this.width = width;
            this.height = height;
            this.rgba = rgba;
            this.hasAlpha = hasAlpha;
        }
    }

    private static final class Channel {
        int nx, ny;
        float dc;
        float[] ac;
        float scale;

        Channel(int nx, int ny) {
            this.nx = nx;
            this.ny = ny;
            int n = 0;
            for (int cy = 0; cy < ny; cy++)
                for (int cx = cy > 0 ? 0 : 1; cx * ny < nx * (ny - cy); cx++)
                    n++;
            ac = new float[n];
        }

        Channel encode(int w, int h, float[] channel) {
            int n = 0;
            float[] fx = new float[w];
            for (int cy = 0; cy < ny; cy++) {
                for (int cx = 0; cx * ny < nx * (ny - cy); cx++) {
                    float f = 0;
                    for (int x = 0; x < w; x++)
                        fx[x] = (float) Math.cos(Math.PI / w * cx * (x + 0.5f));
                    for (int y = 0; y < h; y++) {
                        float fy = (float) Math.cos(Math.PI / h * cy * (y + 0.5f));
                        for (int x = 0; x < w; x++)
                            f += channel[x + y * w] * fx[x] * fy;
                    }
                    f /= w * h;
                    if (cx > 0 || cy > 0) {
                        ac[n++] = f;
                        scale = Math.max(scale, Math.abs(f));
                    } else {
                        dc = f;
                    }
                }
            }
            if (scale > 0)
                for (int i = 0; i < ac.length; i++)
                    ac[i] = 0.5f + 0.5f / scale * ac[i];
            return this;
        }

        int decode(byte[] hash, int start, int index, float scale) {
            for (int i = 0; i < ac.length; i++) {
                int data = hash[start + (index >> 1)] >> ((index & 1) << 2);
                ac[i] = ((float) (data & 15) / 7.5f - 1.0f) * scale;
                index++;
            }
            return index;
        }

        int writeTo(byte[] hash, int start, int index) {
            for (float v : ac) {
                hash[start + (index >> 1)] |= Math.round(15.0f * v) << ((index & 1) << 2);
                index++;
            }
            return index;
        }
    }
}
