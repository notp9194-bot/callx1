package com.callx.app.utils;

/**
 * WhatsApp-level embedded-thumbnail helper.
 *
 * Originally written inline in StatusReplyBottomSheet (embedReplyThumbnail)
 * for status-reply chat bubbles, and now shared by the "Seen your status"
 * (StatusSeenTracker) and "Watched your reel" (ReelSeenTracker) bubbles too:
 * all three used to only store a pointer back to a live Firebase/Cloudinary
 * URL, so once the underlying status/reel expired, got deleted, or the CDN
 * URL otherwise changed, the thumbnail already shown in a previously-sent
 * chat bubble broke. WhatsApp's own quoted/system bubbles don't have this
 * problem because each bubble carries its own small copy of the image from
 * the moment it's sent.
 *
 * This downloads the source media once, downsamples + center-crops it to a
 * small square JPEG, and hands back the Base64 string to embed directly into
 * the message (e.g. replyToThumbBase64 / statusThumbBase64 / reelThumbBase64).
 * The original URL field should still be written by the caller as a fallback
 * for older clients that don't understand the *_Base64 field yet, or in case
 * this embed fails.
 *
 * Runs entirely off the calling thread; the callback fires from that
 * background thread once done (with null if the URL was empty/invalid or the
 * download/decode failed — callers should treat that as "no embed, fall back
 * to the URL field").
 */
public final class ThumbnailEmbedder {
    private ThumbnailEmbedder() {}

    public interface Callback {
        void onDone(String base64OrNull);
    }

    public static void embed(String url, Callback cb) {
        if (url == null || url.isEmpty()) {
            cb.onDone(null);
            return;
        }
        new Thread(() -> {
            String result = null;
            try {
                java.net.URL u = new java.net.URL(url);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) u.openConnection();
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);
                conn.setDoInput(true);
                conn.connect();
                byte[] raw;
                try (java.io.InputStream is = conn.getInputStream()) {
                    java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                    byte[] chunk = new byte[16384];
                    int n;
                    while ((n = is.read(chunk)) != -1) buf.write(chunk, 0, n);
                    raw = buf.toByteArray();
                }
                android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
                opts.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.length, opts);
                int target = 160; // small quoted-bubble/system-bubble size — plenty at high density
                int sample = 1;
                while (opts.outWidth / (sample * 2) >= target && opts.outHeight / (sample * 2) >= target) sample *= 2;
                opts.inJustDecodeBounds = false;
                opts.inSampleSize = sample;
                android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.length, opts);
                if (bmp != null) {
                    int side = Math.min(bmp.getWidth(), bmp.getHeight());
                    int x = (bmp.getWidth()  - side) / 2;
                    int y = (bmp.getHeight() - side) / 2;
                    android.graphics.Bitmap square = (side == bmp.getWidth() && side == bmp.getHeight())
                            ? bmp : android.graphics.Bitmap.createBitmap(bmp, x, y, side, side);
                    java.io.ByteArrayOutputStream jpeg = new java.io.ByteArrayOutputStream();
                    square.compress(android.graphics.Bitmap.CompressFormat.JPEG, 55, jpeg);
                    result = android.util.Base64.encodeToString(jpeg.toByteArray(), android.util.Base64.NO_WRAP);
                    if (square != bmp) square.recycle();
                    bmp.recycle();
                }
            } catch (Exception ignored) {
                // best-effort — caller's URL field still covers rendering
            }
            cb.onDone(result);
        }).start();
    }
}
