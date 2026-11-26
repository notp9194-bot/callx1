package com.callx.app.utils;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.util.LruCache;

import androidx.annotation.Nullable;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * v877 — decode the inline video poster (and warm its ThumbHash) BEFORE the bubble binds, so
 * the video bubble's first bind already has its final bitmap and the canvas records ONCE.
 *
 * Why: before this, the poster was decoded on first bind (async) -> the bubble recorded once
 * with an empty/ThumbHash box and once more when the poster landed. Callers:
 *   - {@code MessageEntityMapper.toModel} (Room -> Message, background thread): {@link #warm}
 *   - {@code MessagePagingAdapter} bind: {@link #peek} (sync hit) / {@link #decodeNowIfSmall}
 *     (sync decode of a <= ~9 KB JPEG, < 1 ms) so even a cold bind stays single-bitmap.
 *
 * Everything is best-effort: a miss just means the old async path runs. Never throws.
 */
public final class VideoPosterPrewarm {
    private VideoPosterPrewarm() {}

    /** Kill switch for the synchronous (main-thread) tiny-JPEG decode on a cold bind. */
    public static volatile boolean SYNC_DECODE_ON_MISS = true;
    /** Base64 chars; ~12000 = ~9 KB JPEG = the ~160px poster. Anything larger stays async. */
    private static final int SYNC_DECODE_MAX_B64 = 12000;

    private static final LruCache<String, Bitmap> L1 = new LruCache<>(24);
    private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();
    // v878: low-priority daemon thread — poster decode must never compete with a fling / the UI thread.
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "VideoPosterPrewarm");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.setDaemon(true);
        return t;
    });

    public static String keyOf(String b64) {
        return "vinl:" + b64.length() + ":" + b64.hashCode();
    }

    @Nullable
    public static Bitmap peek(@Nullable String b64) {
        if (b64 == null || b64.isEmpty()) return null;
        Bitmap b = L1.get(keyOf(b64));
        return (b != null && !b.isRecycled()) ? b : null;
    }

    /** Idempotent + cheap when already cached/in flight; safe to call from any thread, many times. */
    public static void warm(@Nullable String b64, @Nullable String thumbHash) {
        try {
            if (thumbHash != null && !thumbHash.isEmpty()) {
                // L1-only warm (no-op callback): fills ThumbHashPlaceholder's memory cache.
                ThumbHashPlaceholder.getAsync(thumbHash, 32, 32, bmp -> { });
            }
            if (b64 == null || b64.isEmpty()) return;
            final String key = keyOf(b64);
            Bitmap hit = L1.get(key);
            if (hit != null && !hit.isRecycled()) return;
            if (!IN_FLIGHT.add(key)) return;
            EXEC.execute(() -> {
                try {
                    Bitmap bmp = decode(b64);
                    if (bmp != null) L1.put(key, bmp);
                } finally {
                    IN_FLIGHT.remove(key);
                }
            });
        } catch (Throwable ignored) { /* warm-up must never affect the message list */ }
    }

    /** Main-thread safe: only decodes a tiny poster, caches it, returns null if too big / failed. */
    @Nullable
    public static Bitmap decodeNowIfSmall(@Nullable String b64) {
        if (!SYNC_DECODE_ON_MISS || b64 == null || b64.isEmpty() || b64.length() > SYNC_DECODE_MAX_B64) return null;
        Bitmap b = peek(b64);
        if (b != null) return b;
        b = decode(b64);
        if (b != null) L1.put(keyOf(b64), b);
        return b;
    }

    @Nullable
    private static Bitmap decode(String b64) {
        try {
            byte[] bytes = Base64.decode(b64, Base64.NO_WRAP);
            Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bmp != null) {
                try { bmp.prepareToDraw(); } catch (Throwable ignored) { } // v878: GPU upload before first draw
            }
            return bmp;
        } catch (Throwable t) {
            return null;
        }
    }
}
