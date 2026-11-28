package com.callx.app.utils;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

/**
 * User-chosen chat bubble colours (global, both day and night).
 *
 * PERF CONTRACT — this class sits next to the chat hot path, so:
 *  - every read is a plain static field read (no prefs, no allocation, no locking);
 *  - SharedPreferences is touched exactly once per process ({@link #ensureLoaded}) and writes
 *    use apply() (async);
 *  - consumers detect changes with ONE int compare on {@link #sVersion} (bumped on every
 *    apply/reset) instead of re-resolving colours per bind;
 *  - the readable-text colour for a bubble is derived once when a colour is applied, never per bind.
 *
 * "Default" = not custom: callers fall back to the theme resources (day/night aware), exactly
 * like before this feature existed.
 */
public final class BubbleColorStore {

    private static final String PREFS  = "callx_bubble_colors";
    private static final String K_SENT = "sent";
    private static final String K_RECV = "recv";

    /** Bumped on every apply()/reset(). Hot-path consumers compare this int to their cached copy. */
    public static volatile int sVersion = 0;

    private static volatile boolean sLoaded;
    private static volatile boolean sCustomSent, sCustomRecv;
    private static volatile int sSent, sRecv, sSentText, sRecvText;

    private static final int TEXT_DARK  = 0xFF111B21;
    private static final int TEXT_LIGHT = 0xFFFFFFFF;

    // 8 curated tones per side; slot 0 is always "theme default" (value 0 = use resources).
    public static final int[] SEND_PRESETS = {
            0, 0xFF6C2BD9, 0xFF3B82F6, 0xFF16A34A, 0xFFEC4899,
            0xFFF97316, 0xFF0EA5B7, 0xFF5B5BE0,
    };
    public static final int[] RECV_PRESETS = {
            0, 0xFFE5E7EB, 0xFFEDE9FE, 0xFFD1FAE5, 0xFFDBEAFE,
            0xFFFEF3C7, 0xFFFCE7F3, 0xFFECFCCB,
    };

    private BubbleColorStore() {}

    public static void ensureLoaded(@NonNull Context ctx) {
        if (sLoaded) return;
        synchronized (BubbleColorStore.class) {
            if (sLoaded) return;
            SharedPreferences sp = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            final int s = sp.getInt(K_SENT, 0);
            final int r = sp.getInt(K_RECV, 0);
            setInternal(s, r);
            sLoaded = true;
        }
    }

    private static void setInternal(int sent, int recv) {
        sCustomSent = sent != 0;
        sCustomRecv = recv != 0;
        sSent = sent;
        sRecv = recv;
        sSentText = sent != 0 ? readableOn(sent) : 0;
        sRecvText = recv != 0 ? readableOn(recv) : 0;
    }

    /** 0 for a side means "keep the theme default" for that side. */
    public static void apply(@NonNull Context ctx, int sent, int recv) {
        ensureLoaded(ctx);
        synchronized (BubbleColorStore.class) {
            setInternal(sent, recv);
            sVersion++;
        }
        SharedPreferences.Editor e = ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        if (sent != 0) e.putInt(K_SENT, sent); else e.remove(K_SENT);
        if (recv != 0) e.putInt(K_RECV, recv); else e.remove(K_RECV);
        e.apply();
    }

    public static void reset(@NonNull Context ctx) { apply(ctx, 0, 0); }

    public static boolean isCustom() { return sCustomSent || sCustomRecv; }
    /** Raw saved value (0 = theme default) — used by the picker to preselect swatches. */
    public static int savedSent() { return sSent; }
    public static int savedRecv() { return sRecv; }

    /** Bubble fill. {@code themeDefault} is the day/night resource colour. */
    public static int bubbleColor(boolean sent, int themeDefault) {
        return sent ? (sCustomSent ? sSent : themeDefault) : (sCustomRecv ? sRecv : themeDefault);
    }

    /** Text colour for the bubble; {@code themeDefault} is the day/night resource text colour. */
    public static int textColor(boolean sent, int themeDefault) {
        return sent ? (sCustomSent ? sSentText : themeDefault) : (sCustomRecv ? sRecvText : themeDefault);
    }

    /** Dark text on light fills, white on dark fills (perceived luminance). */
    public static int readableOn(int bg) {
        final float lum = (0.299f * ((bg >> 16) & 0xFF) + 0.587f * ((bg >> 8) & 0xFF)
                + 0.114f * (bg & 0xFF)) / 255f;
        return lum > 0.55f ? TEXT_DARK : TEXT_LIGHT;
    }
}
