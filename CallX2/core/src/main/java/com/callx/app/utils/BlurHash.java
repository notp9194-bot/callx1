package com.callx.app.utils;

import android.graphics.Bitmap;

/**
 * BlurHash — encodes an image into a short text string (~20-30 chars) that
 * decodes back into a small, blurred, color-accurate preview of the original.
 *
 * Why this exists (reels grid perceived-speed fix):
 * The grid previously showed a flat "ic_reels" icon while the real thumbnail
 * was still loading/downloading. A BlurHash string is tiny enough to travel
 * inline with the reel's Firebase record (no extra network round trip) and
 * decodes to a bitmap entirely offline — so the placeholder itself already
 * looks like a soft-focus version of the actual thumbnail, the same trick
 * Instagram/Medium use, instead of a blank icon.
 *
 * This is a from-scratch implementation of the public BlurHash algorithm
 * (DC/AC cosine-basis components, base83 text encoding) — no third-party
 * dependency required.
 */
public final class BlurHash {

    private BlurHash() {}

    private static final String BASE83_CHARS =
        "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#$%*+,-.:;=?@[]^_{|}~";

    // ── Encode ───────────────────────────────────────────────────────────

    /**
     * Encodes a bitmap into a BlurHash string.
     * componentX/componentY control detail (1-9 each); 4x3 is a good default
     * for small square-ish thumbnails — enough shape/color, still a tiny string.
     * Downscale the bitmap before calling this (e.g. 32x32) — encoding cost
     * scales with pixel count and a full-res image gives no extra fidelity
     * once averaged into a handful of cosine components anyway.
     */
    public static String encode(Bitmap bitmap, int componentX, int componentY) {
        if (bitmap == null) return null;
        componentX = clamp(componentX, 1, 9);
        componentY = clamp(componentY, 1, 9);

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (width <= 0 || height <= 0) return null;

        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

        double[][] factors = new double[componentX * componentY][3];
        for (int j = 0; j < componentY; j++) {
            for (int i = 0; i < componentX; i++) {
                double[] factor = multiplyBasisFunction(i, j, width, height, pixels);
                factors[j * componentX + i] = factor;
            }
        }

        double[] dc = factors[0];
        double[][] ac = new double[factors.length - 1][];
        for (int idx = 1; idx < factors.length; idx++) ac[idx - 1] = factors[idx];

        StringBuilder sb = new StringBuilder();

        int sizeFlag = (componentX - 1) + (componentY - 1) * 9;
        sb.append(encode83(sizeFlag, 1));

        double maximumValue;
        if (ac.length > 0) {
            double actualMax = 0;
            for (double[] c : ac)
                for (double v : c)
                    actualMax = Math.max(actualMax, Math.abs(v));
            int quantisedMax = (int) Math.max(0, Math.min(82, Math.floor(actualMax * 166 - 0.5)));
            maximumValue = (quantisedMax + 1) / 166.0;
            sb.append(encode83(quantisedMax, 1));
        } else {
            maximumValue = 1;
            sb.append(encode83(0, 1));
        }

        sb.append(encode83(encodeDC(dc), 4));
        for (double[] c : ac) sb.append(encode83(encodeAC(c, maximumValue), 2));

        return sb.toString();
    }

    private static double[] multiplyBasisFunction(int i, int j, int width, int height, int[] pixels) {
        double r = 0, g = 0, b = 0;
        double normalisation = (i == 0 && j == 0) ? 1.0 : 2.0;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double basis = Math.cos(Math.PI * i * x / width) * Math.cos(Math.PI * j * y / height);
                int pixel = pixels[y * width + x];
                r += basis * srgbToLinear((pixel >> 16) & 0xFF);
                g += basis * srgbToLinear((pixel >> 8) & 0xFF);
                b += basis * srgbToLinear(pixel & 0xFF);
            }
        }

        double scale = normalisation / (width * height);
        return new double[]{r * scale, g * scale, b * scale};
    }

    private static int encodeDC(double[] rgb) {
        int r = linearToSrgb(rgb[0]);
        int g = linearToSrgb(rgb[1]);
        int b = linearToSrgb(rgb[2]);
        return (r << 16) + (g << 8) + b;
    }

    private static int encodeAC(double[] rgb, double maximumValue) {
        int quantR = clamp((int) Math.floor(signedPow(rgb[0] / maximumValue, 0.5) * 9 + 9.5), 0, 18);
        int quantG = clamp((int) Math.floor(signedPow(rgb[1] / maximumValue, 0.5) * 9 + 9.5), 0, 18);
        int quantB = clamp((int) Math.floor(signedPow(rgb[2] / maximumValue, 0.5) * 9 + 9.5), 0, 18);
        return quantR * 19 * 19 + quantG * 19 + quantB;
    }

    // ── Decode ───────────────────────────────────────────────────────────

    /**
     * Decodes a BlurHash string into a small bitmap of the given size.
     * `punch` boosts contrast of the AC components (1.0 = neutral; >1.0 = more
     * vivid/contrasty blur — 1.0-1.2 looks closest to the source image).
     * Returns null if the string is malformed (caller should fall back to
     * whatever placeholder it used before BlurHash existed).
     */
    public static Bitmap decode(String blurHash, int width, int height, float punch) {
        if (blurHash == null || blurHash.length() < 6) return null;
        try {
            int sizeFlag = decode83(blurHash, 0, 1);
            int numX = (sizeFlag % 9) + 1;
            int numY = (sizeFlag / 9) + 1;

            int expectedLength = 4 + 2 * numX * numY;
            if (blurHash.length() != expectedLength) return null;

            int quantisedMax = decode83(blurHash, 1, 2);
            double maxValue = (quantisedMax + 1) / 166.0;

            double[][] colors = new double[numX * numY][3];
            colors[0] = decodeDC(decode83(blurHash, 2, 6));
            for (int i = 1; i < numX * numY; i++) {
                int value = decode83(blurHash, 4 + i * 2, 6 + i * 2);
                colors[i] = decodeAC(value, maxValue * punch);
            }

            int[] pixels = new int[width * height];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    double r = 0, g = 0, b = 0;
                    for (int j = 0; j < numY; j++) {
                        for (int i = 0; i < numX; i++) {
                            double basis = Math.cos(Math.PI * x * i / width) * Math.cos(Math.PI * y * j / height);
                            double[] color = colors[j * numX + i];
                            r += color[0] * basis;
                            g += color[1] * basis;
                            b += color[2] * basis;
                        }
                    }
                    int ri = linearToSrgb(r);
                    int gi = linearToSrgb(g);
                    int bi = linearToSrgb(b);
                    pixels[y * width + x] = 0xFF000000 | (ri << 16) | (gi << 8) | bi;
                }
            }

            Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bmp.setPixels(pixels, 0, width, 0, 0, width, height);
            return bmp;
        } catch (Exception e) {
            return null; // malformed hash — caller falls back gracefully
        }
    }

    private static double[] decodeDC(int value) {
        int r = value >> 16;
        int g = (value >> 8) & 0xFF;
        int b = value & 0xFF;
        return new double[]{srgbToLinear(r), srgbToLinear(g), srgbToLinear(b)};
    }

    private static double[] decodeAC(int value, double maximumValue) {
        int quantR = value / (19 * 19);
        int quantG = (value / 19) % 19;
        int quantB = value % 19;
        return new double[]{
            signedPow((quantR - 9) / 9.0, 2.0) * maximumValue,
            signedPow((quantG - 9) / 9.0, 2.0) * maximumValue,
            signedPow((quantB - 9) / 9.0, 2.0) * maximumValue
        };
    }

    // ── Base83 ───────────────────────────────────────────────────────────

    private static String encode83(int value, int length) {
        char[] out = new char[length];
        for (int i = 1; i <= length; i++) {
            int digit = (value / pow83(length - i)) % 83;
            out[i - 1] = BASE83_CHARS.charAt(digit);
        }
        return new String(out);
    }

    private static int decode83(String s, int start, int end) {
        int value = 0;
        for (int i = start; i < end; i++) {
            int digit = BASE83_CHARS.indexOf(s.charAt(i));
            if (digit < 0) throw new IllegalArgumentException("Invalid BlurHash character");
            value = value * 83 + digit;
        }
        return value;
    }

    private static int pow83(int exp) {
        int result = 1;
        for (int i = 0; i < exp; i++) result *= 83;
        return result;
    }

    // ── sRGB <-> linear helpers ──────────────────────────────────────────

    private static double srgbToLinear(int value) {
        double v = value / 255.0;
        return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    private static int linearToSrgb(double value) {
        double v = clamp(value, 0.0, 1.0);
        double srgb = v <= 0.0031308 ? v * 12.92 : 1.055 * Math.pow(v, 1.0 / 2.4) - 0.055;
        return (int) Math.round(srgb * 255);
    }

    private static double signedPow(double base, double exp) {
        double sign = Math.signum(base);
        return sign * Math.pow(Math.abs(base), exp);
    }

    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
    private static double clamp(double v, double min, double max) { return Math.max(min, Math.min(max, v)); }
}
