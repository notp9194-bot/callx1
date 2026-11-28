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

/*
 * v92 ROOT-CAUSE FIX — cache-key width mismatch (Ultra Diagnostics: Name 43%,
 * Message 55% hit ratio).
 *
 * The cache key is rawText + width. Reads (ChatRowContentView.onDraw) compute
 * width as the row's REAL measured column width minus whatever that specific
 * row reserves for its time label / read ticks. Writes (this file, v89) used
 * to compute width as one GLOBAL estimate (screenWidth − a fixed 130dp/104dp
 * guess) that never subtracted the per-row time-string width, tick-state
 * width, or the unread-badge/call-buttons column — all of which vary row to
 * row. Two different formulas producing the "same" width only by coincidence
 * → most rows missed the cache and paid the onDraw()-time ellipsize() cost
 * anyway, defeating the whole point of precomputing.
 *
 * Fix: precomputeOne() below now derives width with the EXACT same formula
 * ChatRowContentView/item_chat.xml actually use — same dp constants, same
 * per-row inputs (unread count, selection mode, tick state, time string) —
 * so a precomputed entry's key is bit-for-bit identical to the key onDraw()
 * will look up. See computeRowContentWidth()/precomputeOne() for the full
 * derivation.
 */

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
 *                   └── ChatListTextPrecompute.precompute(list, myUid, res, isSelecting)
 *                         └── sPool.execute( background task )
 *                                 └── for each User:
 *                                       computeRowContentWidth(u) → exact per-row width
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
    private static final int   MSG_CACHE  = 600;      // v948: plain + bold variants      // fits ~300 unique previews
    private static final float NAME_SP    = 16f;      // MUST match ChatRowContentView
    private static final float MSG_SP     = 14f;      // MUST match ChatRowContentView
    private static final float TIME_SP    = 11f;      // MUST match ChatRowContentView

    // v92: geometry constants — MUST match item_chat.xml + ChatRowContentView +
    // ChatListUnreadBadgeView + ChatListCallButtonsView exactly, or cache keys
    // drift out of sync with the real draw-time width again.
    private static final float NAME_TIME_GAP_DP = 8f;   // ChatRowContentView.nameTimeGapPx
    private static final float TICK_SIZE_DP     = 12f;  // ChatRowContentView tick size
    private static final float TICK_GAP_DP      = 4f;   // ChatRowContentView tick gap
    private static final float PIN_SIZE_DP      = 14f;  // ChatRowContentView pin icon size
    private static final float PIN_GAP_DP       = 6f;   // ChatRowContentView pin icon gap
    private static final float MUTE_SIZE_DP     = 14f;  // ChatRowContentView mute icon size
    private static final float MUTE_GAP_DP      = 6f;   // ChatRowContentView mute icon gap

    /** uids of muted chats (muted/{myUid}) — set by ChatsFragment so pre-computed
     *  widths account for the mute icon each muted row reserves. Immutable snapshot. */
    private static volatile java.util.Set<String> sMutedUids = java.util.Collections.emptySet();

    public static void setMutedUids(java.util.Set<String> uids) {
        sMutedUids = (uids == null || uids.isEmpty())
                ? java.util.Collections.<String>emptySet()
                : java.util.Collections.unmodifiableSet(new java.util.HashSet<>(uids));
    }
    // item_chat.xml: 14dp*2 row padding + 58dp avatar box + 14dp rowContent
    // marginStart + 8dp meta-column marginStart = fixed reserved width before
    // the (variable) badge/call-buttons meta column is subtracted.
    private static final float ROW_FIXED_RESERVED_DP = 14f * 2 + 58f + 14f + 8f;
    private static final float CALL_BTN_WIDTH_DP = 34f * 2 + 6f; // ChatListCallButtonsView.onMeasure
    private static final float BADGE_MIN_DP      = 20f;          // ChatListUnreadBadgeView.MIN_SIZE_DP
    private static final float BADGE_PAD_H_DP    = 6f;           // ChatListUnreadBadgeView.PAD_H_DP

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
    private static volatile TextPaint sMsgBoldPaint; // v948: unread rows draw the preview bold
    /** v948: preview cap — a 2000-char message made ellipsize/measure/shape O(full length) on the UI thread. */
    private static final int MAX_PREVIEW_CHARS = 120;
    private static final float VERIFIED_BADGE_DP = 13f, VERIFIED_GAP_DP = 3f; // MUST match ChatRowContentView
    private static volatile TextPaint sTimePaint;  // v92: for exact time-string width
    private static volatile TextPaint sBadgePaint; // v92: for exact badge-pill width
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

            sMsgBoldPaint = new TextPaint(flags);
            sMsgBoldPaint.setTextSize(MSG_SP * sp);
            sMsgBoldPaint.setTypeface(Typeface.defaultFromStyle(Typeface.BOLD));

            sTimePaint = new TextPaint(flags);
            sTimePaint.setTextSize(TIME_SP * sp);
            sTimePaint.setTypeface(Typeface.DEFAULT);

            sBadgePaint = new TextPaint(flags);
            sBadgePaint.setTextSize(TIME_SP * sp); // badge TEXT_SIZE_SP is also 11f
            sBadgePaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));

            sReady = true;
        }
    }

    /**
     * Submit a batch for background pre-computation.
     * Only computes entries absent from the cache.
     * Safe to call on the main thread — all heavy work runs off-thread.
     *
     * v92: width is no longer passed in as a single global estimate — it's
     * derived PER USER inside precomputeOne(), from the exact same geometry
     * ChatRowContentView will use at draw time (screen width, that user's
     * unread badge, whether call buttons are showing, their time label, and
     * their read-tick state). This is what makes the cache actually hit.
     *
     * @param users       list from the latest {@code submitList()} call
     * @param myUid       current user's uid (needed to resolve read-tick state)
     * @param res         Resources, for screen width / density
     * @param isSelecting whether the list is currently in selection mode
     *                    (hides badges/call-buttons, changing row geometry)
     */
    public static void precompute(List<User> users, String myUid, Resources res, boolean isSelecting) {
        if (!sReady || users == null || users.isEmpty() || res == null) return;
        // Snapshot to avoid holding a live reference to an AsyncListDiffer-managed list
        final User[] snapshot = users.toArray(new User[0]);
        final DisplayMetrics dm = res.getDisplayMetrics();
        sPool.execute(() -> {
            for (User u : snapshot) {
                if (u != null) precomputeOne(u, myUid, dm, isSelecting);
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
        return getMessage(rawText, widthPx, false);
    }

    /** v948: bold = unread row (preview drawn bold). Bold entries carry a 'b' key prefix. */
    public static CharSequence getMessage(String rawText, int widthPx, boolean bold) {
        if (!sReady || rawText == null || rawText.isEmpty()) return null;
        return sMsgCache.get(msgKey(rawText, widthPx, bold));
    }

    private static String msgKey(String text, int widthPx, boolean bold) {
        return bold ? ("b" + KEY_SEP + text + KEY_SEP + widthPx) : (text + KEY_SEP + widthPx);
    }

    // ── ULTRA DIAGNOSTICS: live cache efficiency stats ────────────────────────
    // LruCache already tracks hitCount()/missCount()/size()/maxSize() internally
    // for free — these are real counters accumulated since process start, not
    // sampled or estimated. A low hit ratio here directly explains onDraw()-time
    // ellipsize() fallbacks (the "cold-start fallback" path in the flow above).
    public static final class CacheStats {
        public final int nameHits, nameMisses, nameSize, nameMax;
        public final int msgHits, msgMisses, msgSize, msgMax;
        CacheStats(int nameHits, int nameMisses, int nameSize, int nameMax,
                   int msgHits, int msgMisses, int msgSize, int msgMax) {
            this.nameHits = nameHits; this.nameMisses = nameMisses;
            this.nameSize = nameSize; this.nameMax = nameMax;
            this.msgHits = msgHits; this.msgMisses = msgMisses;
            this.msgSize = msgSize; this.msgMax = msgMax;
        }
        public double nameHitRatio() {
            int total = nameHits + nameMisses;
            return total == 0 ? 1.0 : (double) nameHits / total;
        }
        public double msgHitRatio() {
            int total = msgHits + msgMisses;
            return total == 0 ? 1.0 : (double) msgHits / total;
        }
    }

    public static CacheStats getCacheStats() {
        return new CacheStats(
                sNameCache.hitCount(), sNameCache.missCount(), sNameCache.size(), sNameCache.maxSize(),
                sMsgCache.hitCount(), sMsgCache.missCount(), sMsgCache.size(), sMsgCache.maxSize());
    }

    // ── Private ───────────────────────────────────────────────────────────────

    /**
     * v92: exact row-content width for this specific user — the fixed part
     * (padding/avatar/margins) plus whatever THIS row's meta column (unread
     * badge and/or call buttons) actually reserves. Mirrors item_chat.xml's
     * LinearLayout math + ChatListUnreadBadgeView/ChatListCallButtonsView's
     * own onMeasure() formulas exactly.
     */
    /** px of a dp dimension the way the framework resolves XML dimensions (round half up, min 1). */
    private static int px(float dp, float density) {
        int v = (int) (dp * density + 0.5f);
        return v == 0 && dp > 0 ? 1 : v;
    }

    /**
     * v948: mirrors ChatRowContentView's real width integer-for-integer (per-component pixel rounding for padding /
     * avatar / margins, ceil() of the meta view's float width). The old version used one float sum + a single
     * truncation, which was routinely 1px off -> exact-int cache key miss -> UI-thread ellipsize in onDraw.
     */
    private static int computeRowContentWidth(User u, DisplayMetrics dm, boolean isSelecting) {
        float density = dm.density;

        long unread = u.unread == null ? 0 : u.unread;
        boolean badgeVisible = unread > 0 && !isSelecting;
        float badgeWidthPx = 0f;
        if (badgeVisible) {
            String label = unread > 99 ? "99+" : String.valueOf(unread);
            float textW = sBadgePaint.measureText(label);
            badgeWidthPx = Math.max(BADGE_MIN_DP * density, textW + BADGE_PAD_H_DP * density * 2f);
        }
        float callBtnWidthPx = (isSelecting || u.isGroup) ? 0f : CALL_BTN_WIDTH_DP * density;
        int metaWidthPx = (int) Math.ceil(Math.max(badgeWidthPx, callBtnWidthPx));

        int reservedPx = 2 * px(14f, density) + px(58f, density) + px(14f, density) + px(8f, density) + metaWidthPx;
        return dm.widthPixels - reservedPx;
    }

    public static String previewFor(User u) {
        return capPreview(previewForRaw(u));
    }

    private static String capPreview(String s) {
        if (s == null || s.length() <= MAX_PREVIEW_CHARS) return s;
        int end = MAX_PREVIEW_CHARS;
        if (Character.isHighSurrogate(s.charAt(end - 1))) end--;   // never split an emoji
        return s.substring(0, end);
    }

    private static String previewForRaw(User u) {
        if (u.isGroup) {
            String preview = ChatListPreviewUtil.buildPreview(
                    u.lastMessageType, u.lastMessage, u.memberCount + " members");
            if (u.lastSenderName != null && !u.lastSenderName.isEmpty()
                    && u.lastMessage != null && !u.lastMessage.isEmpty()) {
                preview = u.lastSenderName + ": " + preview;
            }
            return preview;
        }
        return ChatListPreviewUtil.buildPreview(
                u.lastMessageType, u.lastMessage, "Tap karke chat karo");
    }

    private static void precomputeOne(User u, String myUid, DisplayMetrics dm, boolean isSelecting) {
        int rowWidthPx = computeRowContentWidth(u, dm, isSelecting);
        float density = dm.density;

        Long when = u.lastMessageAt != null ? u.lastMessageAt : u.lastSeen;
        String timeStr = (when != null && when > 0) ? ChatListTimeCache.getFormatted(when) : "";
        float timeW = timeStr.isEmpty() ? 0f : sTimePaint.measureText(timeStr);
        float nameGapPx = NAME_TIME_GAP_DP * density;

        // v948: verified badge reserves width after the name (view: nameAvail = w - timeW - gap - badge).
        boolean verified = false;
        if (!u.isGroup && u.uid != null) {
            Boolean v = com.callx.app.cache.VerifiedStatusCache.getInstance().getCached(u.uid);
            verified = Boolean.TRUE.equals(v);
        }
        float badgeReserved = verified ? (VERIFIED_BADGE_DP * density + VERIFIED_GAP_DP * density) : 0f;
        int nameWidthPx = (int) (rowWidthPx - timeW - (timeW > 0f ? nameGapPx : 0f) - badgeReserved);

        // v948: view does `w - (int) tickReserved - (int) iconsReserved` (two separate truncations).
        int tickReservedPx = 0;
        boolean iAmLastSender = myUid != null && u.uid != null && myUid.equals(u.lastMessageSenderUid);
        if (!isSelecting && iAmLastSender && u.lastMessageStatus != null) {
            float tickSizePx = TICK_SIZE_DP * density;
            float tickGapPx  = TICK_GAP_DP * density;
            boolean wide = "read".equals(u.lastMessageStatus) || "delivered".equals(u.lastMessageStatus);
            float span = wide ? tickSizePx * 1.35f : tickSizePx;
            tickReservedPx = (int) (span + tickGapPx);
        }
        float iconsF = (u.localPinned ? (PIN_SIZE_DP + PIN_GAP_DP) * density : 0f);
        if (u.isGroup ? u.localMuted : (u.uid != null && sMutedUids.contains(u.uid))) {
            iconsF += (MUTE_SIZE_DP + MUTE_GAP_DP) * density;
        }
        int msgWidthPx = Math.max(0, rowWidthPx - tickReservedPx - (int) iconsF);

        String name    = u.name != null ? u.name : (u.isGroup ? "Group" : "User");
        String nameKey = name + KEY_SEP + nameWidthPx;
        if (sNameCache.get(nameKey) == null) {
            CharSequence el = TextUtils.ellipsize(
                    name, sNamePaint, Math.max(0f, nameWidthPx),
                    TextUtils.TruncateAt.END);
            sNameCache.put(nameKey, el);
        }

        String preview = previewFor(u);
        long unread = u.unread == null ? 0 : u.unread;
        boolean bold = unread > 0 && !isSelecting;       // same rule as the adapter's hasUnread
        String msgKey = msgKey(preview, msgWidthPx, bold);
        if (sMsgCache.get(msgKey) == null) {
            CharSequence el = TextUtils.ellipsize(
                    preview, bold ? sMsgBoldPaint : sMsgPaint, Math.max(0f, msgWidthPx),
                    TextUtils.TruncateAt.END);
            sMsgCache.put(msgKey, el);
        }
    }
}
