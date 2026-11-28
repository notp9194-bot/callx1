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
 *  - every derived colour (readable text, tick, accent, reply card ...) is computed ONCE when a
 *    colour is applied and kept in an immutable {@link Side}; reads are one volatile deref.
 *
 * "Default" = not custom: callers fall back to the theme resources (day/night aware), exactly
 * like before this feature existed.
 *
 * v928 polish: WCAG contrast based text colour, contrast-safe tick / accent / reply-card colours
 * for custom bubbles, recent custom colours.
 */
public final class BubbleColorStore {

    private static final String PREFS    = "callx_bubble_colors";
    private static final String K_SENT   = "sent";
    private static final String K_RECV   = "recv";
    private static final String K_RECENT = "recent";

    /** Bumped on every apply()/reset(). Hot-path consumers compare this int to their cached copy. */
    // v929: plain (non-volatile) on purpose — written only from the main thread (apply), read on the main thread
    // at bind time as ONE ldr; a background reader that sees a stale value only caches for one extra bind.
    public static int sVersion = 0;

    /**
     * v930: single theme epoch. Folds "bubble colour version" + "uiMode" into ONE static int, so every
     * bind does one compare instead of {@code getConfiguration().uiMode} reads + several version compares.
     * Bumped by {@link #apply} and by {@link #syncUiMode} when the uiMode really changed.
     * Main-thread written; a stale read on a background thread only causes one extra (idempotent) re-resolve.
     */
    public static int sEpoch = 1;
    // v931: per-side epochs — changing only the SENT (or only the RECV) colour leaves the other side's rows,
    // paints and (for sent-only vs recv-only) tick bitmaps untouched. sEpoch stays the "anything changed" int.
    public static int sEpochSent = 1, sEpochRecv = 1;
    /** v931: bumped only when the SENT colour really changed (tick bitmaps follow the sent side only). */
    public static int sVersionSent = 0;
    private static int sUiMode = -1;

    /**
     * Call with the current {@code Configuration.uiMode} wherever it may have changed (Application /
     * Activity onConfigurationChanged, onCreate, onResume, view creation). One int compare when unchanged.
     * MUST be called BEFORE notifying adapters about a theme change.
     */
    public static void syncUiMode(int uiMode) {
        if (uiMode != sUiMode) { sUiMode = uiMode; sEpoch++; sEpochSent++; sEpochRecv++; }
    }

    private static volatile boolean sLoaded;
    private static volatile Side sSent = Side.NONE, sRecv = Side.NONE;
    private static volatile int[] sRecent = new int[0];

    private static final int TEXT_DARK  = 0xFF111B21;
    private static final int TEXT_LIGHT = 0xFFFFFFFF;

    /** Brand signature colours that normally sit on top of a bubble (see MessageBubbleCanvasView). */
    private static final int GOLD        = 0xFFD4AF37;
    private static final int TICK_UNREAD = 0xFF8FAF9F;

    private static final float TEXT_MIN_WHITE = 3.0f;  // white text is kept while it still has >= 3:1
    private static final float ACCENT_MIN     = 1.8f;  // large shapes: bars, play button, waveform
    private static final float TICK_MIN       = 2.5f;  // thin 12dp glyph needs more

    public static final int MAX_RECENT = 4;

    // 8 curated tones per side; slot 0 is always "theme default" (value 0 = use resources).
    public static final int[] SEND_PRESETS = {
            0, 0xFF6C2BD9, 0xFF3B82F6, 0xFF16A34A, 0xFFEC4899,
            0xFFF97316, 0xFF0EA5B7, 0xFF5B5BE0,
    };
    public static final int[] RECV_PRESETS = {
            0, 0xFFE5E7EB, 0xFFEDE9FE, 0xFFD1FAE5, 0xFFDBEAFE,
            0xFFFEF3C7, 0xFFFCE7F3, 0xFFECFCCB,
    };

    /** Human names for the presets (accessibility + toasts). Same order as the arrays above. */
    public static final String[] SEND_NAMES = {
            "Default", "Violet", "Blue", "Green", "Pink", "Orange", "Teal", "Indigo"};
    public static final String[] RECV_NAMES = {
            "Default", "Grey", "Lavender", "Mint", "Sky", "Amber", "Rose", "Lime"};

    private BubbleColorStore() {}

    /** Immutable bundle of everything derived from one custom bubble fill. */
    private static final class Side {
        static final Side NONE = new Side(0);
        final boolean custom;
        final int bg, text, tickRead, tickUnread, gold, replyBg, replyText, linkBg;
        // v929: two-entry memo for accent(): (preferred << 32 | result). One volatile long read = atomic.
        // Covers reply bar + sender (two constants) and the brand colour with no pow() after the first call.
        volatile long memo0, memo1;

        Side(int bg) {
            this.custom = bg != 0;
            this.bg = bg;
            if (!custom) {
                text = tickRead = tickUnread = gold = replyBg = replyText = linkBg = 0;
                return;
            }
            text       = readableOn(bg);
            final boolean lightText = text == TEXT_LIGHT;
            tickRead   = ensureContrast(bg, GOLD, TICK_MIN, text);
            tickUnread = ensureContrast(bg, TICK_UNREAD, TICK_MIN, blend(text, bg, 0.62f));
            gold       = ensureContrast(bg, GOLD, ACCENT_MIN, text);
            replyBg    = lightText ? 0x33000000 : 0x1F000000;
            replyText  = (text & 0x00FFFFFF) | 0xDD000000;
            // link card keeps its white title / gold domain, so on a LIGHT bubble it needs a dark card
            linkBg     = lightText ? 0x33000000 : 0xFF1F2933;
        }
    }

    public static void ensureLoaded(@NonNull Context ctx) {
        if (sLoaded) return;
        synchronized (BubbleColorStore.class) {
            if (sLoaded) return;
            SharedPreferences sp = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            setInternal(sp.getInt(K_SENT, 0), sp.getInt(K_RECV, 0));
            sRecent = parseRecent(sp.getString(K_RECENT, ""));
            sLoaded = true;
        }
    }

    private static void setInternal(int sent, int recv) {
        sSent = sent != 0 ? new Side(sent) : Side.NONE;
        sRecv = recv != 0 ? new Side(recv) : Side.NONE;
    }

    /** 0 for a side means "keep the theme default" for that side. */
    public static void apply(@NonNull Context ctx, int sent, int recv) {
        ensureLoaded(ctx);
        final String recentCsv;
        synchronized (BubbleColorStore.class) {
            final boolean sentChanged = sent != sSent.bg, recvChanged = recv != sRecv.bg;
            setInternal(sent, recv);
            pushRecent(sent, SEND_PRESETS);
            pushRecent(recv, RECV_PRESETS);
            recentCsv = joinRecent(sRecent);
            sVersion++;
            if (sentChanged) { sVersionSent++; sEpochSent++; }
            if (recvChanged) sEpochRecv++;
            if (sentChanged || recvChanged) sEpoch++;   // nothing changed -> no row is touched at all
        }
        // v932: in-memory state is already current (all readers use it); the disk write goes to one background
        // thread so Apply never touches SharedPreferences (getSharedPreferences / edit / apply) on main.
        final Context app = ctx.getApplicationContext();
        writer().execute(() -> {
            final SharedPreferences.Editor e = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
            if (sent != 0) e.putInt(K_SENT, sent); else e.remove(K_SENT);
            if (recv != 0) e.putInt(K_RECV, recv); else e.remove(K_RECV);
            e.putString(K_RECENT, recentCsv);
            e.apply();
        });
    }

    private static volatile java.util.concurrent.ExecutorService sWriter;
    private static java.util.concurrent.ExecutorService writer() {
        java.util.concurrent.ExecutorService w = sWriter;
        if (w == null) {
            synchronized (BubbleColorStore.class) {
                w = sWriter;
                if (w == null) {
                    w = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                        final Thread t = new Thread(r, "bubble-color-write");
                        t.setDaemon(true);
                        return t;
                    });
                    sWriter = w;
                }
            }
        }
        return w;
    }

    public static void reset(@NonNull Context ctx) { apply(ctx, 0, 0); }

    public static boolean isCustom() { return sSent.custom || sRecv.custom; }
    public static boolean isCustom(boolean sent) { return (sent ? sSent : sRecv).custom; }
    /** Raw saved value (0 = theme default) — used by the picker to preselect swatches. */
    public static int savedSent() { return sSent.bg; }
    public static int savedRecv() { return sRecv.bg; }

    // ── hot-path reads (static field read + one deref, no allocation) ───────────

    /** Bubble fill. {@code themeDefault} is the day/night resource colour. */
    public static int bubbleColor(boolean sent, int themeDefault) {
        final Side s = sent ? sSent : sRecv;
        return s.custom ? s.bg : themeDefault;
    }

    /** Text colour for the bubble; {@code themeDefault} is the day/night resource text colour. */
    public static int textColor(boolean sent, int themeDefault) {
        final Side s = sent ? sSent : sRecv;
        return s.custom ? s.text : themeDefault;
    }

    /** Delivery tick colour (sent side only — received bubbles never draw ticks). */
    public static int tickColor(boolean read, int themeDefault) {
        final Side s = sSent;
        return s.custom ? (read ? s.tickRead : s.tickUnread) : themeDefault;
    }

    /** Gold "signature" accent (audio button, waveform played part) made safe for the bubble fill. */
    public static int goldAccent(boolean sent, int themeDefault) {
        final Side s = sent ? sSent : sRecv;
        return s.custom ? s.gold : themeDefault;
    }

    /** Any other accent colour (reply bar / sender, brand colour...) made safe for the bubble fill. */
    public static int accent(boolean sent, int preferred) {
        final Side s = sent ? sSent : sRecv;
        if (!s.custom) return preferred;
        final long m0 = s.memo0;
        if ((int) (m0 >>> 32) == preferred) return (int) m0;
        final long m1 = s.memo1;
        if ((int) (m1 >>> 32) == preferred) return (int) m1;
        final int out = ensureContrast(s.bg, preferred, ACCENT_MIN, s.text);
        s.memo1 = m0;
        s.memo0 = ((long) preferred << 32) | (out & 0xFFFFFFFFL);
        return out;
    }

    public static int replyBg(boolean sent, int themeDefault) {
        final Side s = sent ? sSent : sRecv;
        return s.custom ? s.replyBg : themeDefault;
    }

    public static int replyText(boolean sent, int themeDefault) {
        final Side s = sent ? sSent : sRecv;
        return s.custom ? s.replyText : themeDefault;
    }

    public static int linkCardBg(boolean sent, int themeDefault) {
        final Side s = sent ? sSent : sRecv;
        return s.custom ? s.linkBg : themeDefault;
    }

    // ── pure helpers (also used by the preview screen for the in-progress draft) ─

    public static int tickReadOn(int bg)  { return ensureContrast(bg, GOLD, TICK_MIN, readableOn(bg)); }
    public static int goldOn(int bg)      { return ensureContrast(bg, GOLD, ACCENT_MIN, readableOn(bg)); }

    /**
     * White text while it keeps a readable 3:1 against the fill (the usual look on saturated colours),
     * otherwise dark text. Uses WCAG relative luminance instead of a plain brightness cut-off.
     */
    public static int readableOn(int bg) {
        return contrast(TEXT_LIGHT, bg) >= TEXT_MIN_WHITE ? TEXT_LIGHT : TEXT_DARK;
    }

    /** {@code fg} if it has at least {@code minRatio} contrast on {@code bg}, else {@code fallback}. */
    public static int ensureContrast(int bg, int fg, float minRatio, int fallback) {
        return contrast(fg, bg) >= minRatio ? (fg | 0xFF000000) : fallback;
    }

    /** WCAG contrast ratio (1..21) between two opaque colours. */
    public static float contrast(int a, int b) {
        final float la = luminance(a), lb = luminance(b);
        return la > lb ? (la + 0.05f) / (lb + 0.05f) : (lb + 0.05f) / (la + 0.05f);
    }

    private static float luminance(int c) {
        return 0.2126f * lin((c >> 16) & 0xFF) + 0.7152f * lin((c >> 8) & 0xFF) + 0.0722f * lin(c & 0xFF);
    }

    // v932: sRGB -> linear lookup (was 3 Math.pow per luminance); same maths, computed once.
    private static final float[] LIN = new float[256];
    static {
        for (int v = 0; v < 256; v++) {
            final float s = v / 255f;
            LIN[v] = s <= 0.03928f ? s / 12.92f : (float) Math.pow((s + 0.055f) / 1.055f, 2.4f);
        }
    }

    private static float lin(int v) { return LIN[v & 0xFF]; }

    private static int blend(int top, int bottom, float t) {
        final int r = Math.round(((top >> 16) & 0xFF) * t + ((bottom >> 16) & 0xFF) * (1f - t));
        final int g = Math.round(((top >> 8) & 0xFF) * t + ((bottom >> 8) & 0xFF) * (1f - t));
        final int b = Math.round((top & 0xFF) * t + (bottom & 0xFF) * (1f - t));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    // ── recent custom colours (picker convenience; never read on the chat hot path) ─

    /** Most-recent-first custom (non-preset) colours the user applied, max {@link #MAX_RECENT}. */
    public static int[] recents() { return sRecent; }

    private static boolean isPreset(int c, int[] presets) {
        for (int p : presets) if (p == c) return true;
        return false;
    }

    private static void pushRecent(int c, int[] presets) {
        if (c == 0 || isPreset(c, presets)) return;
        final int[] old = sRecent;
        final int[] tmp = new int[MAX_RECENT];
        int n = 0;
        tmp[n++] = c;
        for (int i = 0; i < old.length && n < MAX_RECENT; i++) {
            if (old[i] != c) tmp[n++] = old[i];
        }
        final int[] out = new int[n];
        System.arraycopy(tmp, 0, out, 0, n);
        sRecent = out;
    }

    private static String joinRecent(int[] a) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(a[i]);
        }
        return sb.toString();
    }

    private static int[] parseRecent(String csv) {
        if (csv == null || csv.isEmpty()) return new int[0];
        final String[] parts = csv.split(",");
        final int[] tmp = new int[Math.min(parts.length, MAX_RECENT)];
        int n = 0;
        for (String p : parts) {
            if (n >= tmp.length) break;
            try { tmp[n++] = Integer.parseInt(p.trim()); } catch (NumberFormatException ignored) { }
        }
        final int[] out = new int[n];
        System.arraycopy(tmp, 0, out, 0, n);
        return out;
    }
}
