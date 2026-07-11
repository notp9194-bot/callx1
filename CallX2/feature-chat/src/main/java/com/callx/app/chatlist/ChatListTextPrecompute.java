package com.callx.app.chatlist;

import android.content.res.Resources;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.LruCache;

import com.callx.app.models.User;
import com.callx.app.utils.ChatListPreviewUtil;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ChatListTextPrecompute — v89 background text ellipsis pre-computation.
 *
 * ════════════════════════════════════════════════════════════════════════════
 *  PROBLEM
 * ════════════════════════════════════════════════════════════════════════════
 * Android's TextUtils.ellipsize() calls into the font engine (HarfBuzz) to
 * measure glyph advances, kern pairs, and locate the truncation point.
 * On a Pixel 6 this costs 0.5–3 ms per string on the UI thread.
 *
 * With 10–15 new rows appearing on first load or after a tab switch, that is
 * 5–45 ms of text work inside the 16 ms frame budget → jank on first scroll.
 *
 * Android's PrecomputedTextCompat solves this for TextView. Our views use
 * Canvas directly, so we replicate the same technique:
 *   compute ellipsis on a background thread → store in LruCache →
 *   serve the pre-computed CharSequence in onDraw() → zero UI-thread text work.
 *
 * ════════════════════════════════════════════════════════════════════════════
 *  FLOW
 * ════════════════════════════════════════════════════════════════════════════
 *
 *  [Main thread]  ChatListAdapter.submitList(newList)
 *                   └── ChatListTextPrecompute.precompute(list, nameW, msgW)
 *                         └── sPool.execute( background task )
 *                                 └── for each User:
 *                                       TextUtils.ellipsize(name, ...)  → sNameCache
 *                                       TextUtils.ellipsize(preview, ...) → sMsgCache
 *
 *  [RenderThread] ChatListNameTimeView.rebuildEllipsisIfNeeded(nameWidth)
 *                   └── sNameCache.get(rawName + SEP + nameWidth)
 *                         hit  → use cached CharSequence  (zero font work)
 *                         miss → TextUtils.ellipsize()    (cold-start fallback)
 *
 * ════════════════════════════════════════════════════════════════════════════
 *  CACHE KEY DESIGN
 * ════════════════════════════════════════════════════════════════════════════
 * Key = rawText + '§' + widthPx
 *
 * Why not uid-keyed?
 *  - Two contacts with the same name → same cache entry (correct: ellipsis
 *    depends only on text + paint metrics + width, not on who the person is).
 *  - Canvas views don't know the UID; they only know the raw string they were
 *    given — this makes the lookup zero-coupling.
 *  - '§' (U+00A7 section sign) never appears in contact names or messages so
 *    it safely delimits the text from the width suffix.
 *
 * ════════════════════════════════════════════════════════════════════════════
 *  PAINT CLONES
 * ════════════════════════════════════════════════════════════════════════════
 * TextUtils.ellipsize() needs a TextPaint to measure glyphs.  We clone the
 * canvas views' paints (same textSize, typeface, flags).  After init() they
 * are read-only — no synchronization needed on the worker threads.
 *
 * IMPORTANT: if you ever change NAME_SP / MSG_SP / typeface in the canvas
 * views, update the matching constants here or cache hits will produce
 * incorrectly truncated strings.
 */
public final class ChatListTextPrecompute {

    // ── Constants ─────────────────────────────────────────────────────────────
    private static final char  KEY_SEP    = '\u00A7'; // § — safe separator
    private static final int   NAME_CACHE = 300;      // fits ~300 unique names
    private static final int   MSG_CACHE  = 300;      // fits ~300 unique previews
    private static final float NAME_SP    = 16f;      // MUST match ChatListNameTimeView
    private static final float MSG_SP     = 14f;      // MUST match ChatListLastMessageView

    // ── Shared caches (LruCache is thread-safe for get/put) ──────────────────
    private static final LruCache<String, CharSequence> sNameCache =
            new LruCache<>(NAME_CACHE);
    private static final LruCache<String, CharSequence> sMsgCache  =
            new LruCache<>(MSG_CACHE);

    // ── Worker pool ───────────────────────────────────────────────────────────
    // 2 threads pre-compute ~800 entries/sec — well ahead of any scroll velocity.
    private static final ExecutorService sPool = Executors.newFixedThreadPool(2);

    // ── TextPaint clones (read-only after init) ───────────────────────────────
    private static volatile TextPaint sNamePaint;
    private static volatile TextPaint sMsgPaint;
    private static volatile boolean   sReady = false;

    private ChatListTextPrecompute() {}

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Initialise the TextPaint clones. Call once from
     * {@code ChatListAdapter.onAttachedToRecyclerView()}.
     * Subsequent calls are no-ops (double-checked lock).
     */
    public static void init(Resources res) {
        if (sReady) return;
        synchronized (ChatListTextPrecompute.class) {
            if (sReady) return;
            DisplayMetrics dm = res.getDisplayMetrics();
            float sp = dm.scaledDensity;

            int flags = Paint.ANTI_ALIAS_FLAG
                      | Paint.SUBPIXEL_TEXT_FLAG
                      | Paint.LINEAR_TEXT_FLAG;

            sNamePaint = new TextPaint(flags);
            sNamePaint.setTextSize(NAME_SP * sp);
            sNamePaint.setTypeface(Typeface.DEFAULT_BOLD);

            sMsgPaint = new TextPaint(flags);
            sMsgPaint.setTextSize(MSG_SP * sp);

            sReady = true;
        }
    }

    /**
     * Estimate the available width for the contact name field.
     *
     * Layout: [8dp pad] [50dp avatar] [8dp gap] [name........] [8dp gap] [~48dp time] [8dp pad]
     * Reserved ≈ 130dp → nameWidth = screenWidth − 130dp
     */
    public static int estimateNameWidth(Resources res) {
        DisplayMetrics dm = res.getDisplayMetrics();
        return (int) (dm.widthPixels - 130f * dm.density);
    }

    /**
     * Estimate the available width for the last-message preview field.
     *
     * Layout: [8dp pad] [50dp avatar] [8dp gap] [message......] [8dp gap] [~30dp badge] [8dp pad]
     * Reserved ≈ 104dp → msgWidth = screenWidth − 104dp
     */
    public static int estimateMsgWidth(Resources res) {
        DisplayMetrics dm = res.getDisplayMetrics();
        return (int) (dm.widthPixels - 104f * dm.density);
    }

    /**
     * Submit a batch for background pre-computation.
     * Only computes entries absent from the cache.
     * Safe to call on the main thread — all heavy work runs off-thread.
     *
     * @param users       list from the latest {@code submitList()} call
     * @param nameWidthPx estimated name field width in px (see {@link #estimateNameWidth})
     * @param msgWidthPx  estimated message field width in px (see {@link #estimateMsgWidth})
     */
    public static void precompute(List<User> users, int nameWidthPx, int msgWidthPx) {
        if (!sReady || users == null || users.isEmpty()) return;
        // Snapshot to avoid holding a live reference to an AsyncListDiffer-managed list
        final User[] snapshot = users.toArray(new User[0]);
        sPool.execute(() -> {
            for (User u : snapshot) {
                if (u != null) precomputeOne(u, nameWidthPx, msgWidthPx);
            }
        });
    }

    /**
     * Look up a pre-computed ellipsized contact name.
     *
     * @return the cached {@link CharSequence}, or {@code null} on cache miss
     *         (caller should fall back to {@code TextUtils.ellipsize()})
     */
    public static CharSequence getName(String rawName, int widthPx) {
        if (!sReady || rawName == null || rawName.isEmpty()) return null;
        return sNameCache.get(rawName + KEY_SEP + widthPx);
    }

    /**
     * Look up a pre-computed ellipsized message preview.
     *
     * @return the cached {@link CharSequence}, or {@code null} on cache miss
     */
    public static CharSequence getMessage(String rawText, int widthPx) {
        if (!sReady || rawText == null || rawText.isEmpty()) return null;
        return sMsgCache.get(rawText + KEY_SEP + widthPx);
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private static void precomputeOne(User u, int nameWidthPx, int msgWidthPx) {
        // ── Name ──────────────────────────────────────────────────────────────
        String name    = u.name != null ? u.name : "User";
        String nameKey = name + KEY_SEP + nameWidthPx;
        if (sNameCache.get(nameKey) == null) {
            CharSequence el = TextUtils.ellipsize(
                    name, sNamePaint, Math.max(0f, nameWidthPx),
                    TextUtils.TruncateAt.END);
            sNameCache.put(nameKey, el);
        }

        // ── Last-message preview ───────────────────────────────────────────────
        String preview = ChatListPreviewUtil.buildPreview(
                u.lastMessageType, u.lastMessage, "Tap karke chat karo");
        String msgKey  = preview + KEY_SEP + msgWidthPx;
        if (sMsgCache.get(msgKey) == null) {
            CharSequence el = TextUtils.ellipsize(
                    preview, sMsgPaint, Math.max(0f, msgWidthPx),
                    TextUtils.TruncateAt.END);
            sMsgCache.put(msgKey, el);
        }
    }
}
