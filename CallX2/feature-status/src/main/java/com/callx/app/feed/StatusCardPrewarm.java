package com.callx.app.feed;

import android.content.Context;
import android.net.ConnectivityManager;
import android.os.Looper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * v557 — warms the status-carousel card image BEFORE the Status tab is opened.
 *
 * MainActivity already holds a live {@code status/{uid}} snapshot per contact (badge count). Each time one lands we
 * resolve that contact's latest card thumb and preload it with the exact request the card bind uses
 * ({@link StatusListAdapter.StatusCardAdapter#cardReq}: same URL, same size, same format) so it is already in
 * Glide's memory/disk cache when the carousel first binds.
 *
 * Budget: at most {@link #MAX} distinct URLs are tracked (LRU), data-saver is respected, text statuses are skipped.
 */
public final class StatusCardPrewarm {
    private StatusCardPrewarm() {}

    private static final int MAX = 12;
    private static final Map<String, Boolean> WARMED = new LinkedHashMap<String, Boolean>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) { return size() > MAX; }
    };

    /** Call with the raw fields of a contact's latest (non-deleted, non-expired) status. Safe from any thread. */
    public static void warm(final Context ctx, final String type, final String thumbnailUrl, final String mediaUrl) {
        if (ctx == null) return;
        final String thumb = StatusListAdapter.cardThumb(type, thumbnailUrl, mediaUrl);
        if (thumb == null || thumb.isEmpty()) return; // text status / video without thumbnail: nothing to warm
        if (Looper.myLooper() != Looper.getMainLooper()) {
            new android.os.Handler(Looper.getMainLooper()).post(() -> warm(ctx, type, thumbnailUrl, mediaUrl));
            return;
        }
        final Context app = ctx.getApplicationContext();
        try {
            final ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null && cm.isActiveNetworkMetered()
                    && cm.getRestrictBackgroundStatus() == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED) return;
        } catch (Exception ignored) {}
        final String url = StatusListAdapter.StatusCardAdapter.cardThumbUrl(app, thumb);
        if (url == null || url.isEmpty()) return;
        synchronized (WARMED) {
            if (WARMED.containsKey(url)) { WARMED.get(url); return; } // already warmed (touch for LRU)
            WARMED.put(url, Boolean.TRUE);
        }
        try {
            final int[] px = StatusListAdapter.StatusCardAdapter.cardSize(app);
            StatusListAdapter.StatusCardAdapter.cardReq(app, url, px[0], px[1]).preload(px[0], px[1]);
        } catch (Exception ignored) {
            synchronized (WARMED) { WARMED.remove(url); }
        }
    }
}
