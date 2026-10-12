package com.callx.app.compose;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

import com.callx.app.utils.ThumbHash;

import java.io.File;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * v557 — computes the ~25-byte ThumbHash of a status photo/video off the main thread while it uploads.
 * The hash is stored on the status node ({@code thumbHash}) and shown as an instant blurred placeholder on the
 * carousel card. Best effort: any failure just yields null (card falls back to the flat placeholder).
 */
final class StatusThumbHashHelper {
    private StatusThumbHashHelper() {}

    interface Result { void on(String hash); }

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final int TARGET = 128; // decode to roughly this max side; ThumbHash itself caps at 100

    static void computeAsync(Context ctx, Uri uri, boolean video, File thumbFile, Result cb) {
        final Context app = ctx.getApplicationContext();
        IO.execute(() -> {
            String hash = null;
            Bitmap b = null;
            try {
                if (thumbFile != null && thumbFile.exists()) b = decodeFile(thumbFile.getAbsolutePath());
                if (b == null) b = video ? videoFrame(app, uri) : decodeUri(app, uri);
                if (b != null) hash = ThumbHash.encode(b);
            } catch (Throwable ignored) {
            } finally {
                if (b != null) { try { b.recycle(); } catch (Throwable ignored) {} }
            }
            cb.on(hash);
        });
    }

    private static int sample(int w, int h) {
        int s = 1;
        while (Math.max(w, h) / (s * 2) >= TARGET) s *= 2;
        return s;
    }

    private static Bitmap decodeFile(String path) {
        final BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample(o.outWidth, o.outHeight);
        return BitmapFactory.decodeFile(path, o);
    }

    private static Bitmap decodeUri(Context app, Uri uri) throws Exception {
        final BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = app.getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            BitmapFactory.decodeStream(in, null, o);
        }
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample(o.outWidth, o.outHeight);
        try (InputStream in = app.getContentResolver().openInputStream(uri)) {
            return in == null ? null : BitmapFactory.decodeStream(in, null, o);
        }
    }

    private static Bitmap videoFrame(Context app, Uri uri) {
        final MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(app, uri);
            return r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
        } catch (Throwable t) {
            return null;
        } finally {
            try { r.release(); } catch (Throwable ignored) {}
        }
    }
}
