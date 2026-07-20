package com.callx.app.channel.canvas;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.LruCache;

import com.callx.app.models.ChannelPost;

import java.util.List;

/**
 * ChannelPostLayoutPrewarmer — pre-builds StaticLayouts on a background HandlerThread.
 *
 * WHY THIS EXISTS
 * ───────────────
 * StaticLayout.Builder.build() runs text shaping (line breaking, bidi analysis,
 * hyphenation) synchronously. For complex Unicode posts or long captions this can
 * take 2–8 ms on the UI thread — easily enough to miss a 16 ms frame during a
 * slow fling. Moving this work to a background thread during scroll idle time means
 * by the time the item is about to enter the viewport, its StaticLayout is already
 * built and cached. onMeasure() then just reads the cached height — no shaping work.
 *
 * HOW IT WORKS
 * ────────────
 * 1. Adapter calls prewarm(posts, startIdx, endIdx, contentWidthPx) when the list
 *    becomes idle or a new chunk of posts is loaded.
 * 2. The prewarmer queues background tasks on a THREAD_PRIORITY_BACKGROUND Handler.
 * 3. Each task calls StaticLayout.Builder.obtain(...).build() for the post text and,
 *    if it's a link post, for the link title. Results are stored in a LruCache.
 * 4. ChannelPostCanvasView.bind() calls acceptPrewarmed(prewarmer.getPrewarmed(postId)).
 *    This injects the pre-built layouts directly into ChannelPostTextRenderer and
 *    ChannelPostLinkRenderer — skipping their lazy rebuild entirely.
 *
 * PAINT THREAD SAFETY
 * ────────────────────
 * TextPaint is NOT thread-safe — we clone the paints from the host View on
 * construction so the background thread has its own copies. The clone captures
 * the exact typeface, size, and flags needed for identical layout results.
 *
 * SHUTDOWN
 * ────────
 * Call shutdown() from Activity.onDestroy() to stop the HandlerThread cleanly.
 */
public final class ChannelPostLayoutPrewarmer {

    private static final int MAX_CACHED = 60;

    // Prewarmed result — both layouts may be null if not applicable.
    public static final class PrewarmResult {
        public final StaticLayout textLayout;       // built from post.text
        public final StaticLayout linkTitleLayout;  // built from post.linkTitle (link posts only)
        public final int          textHeight;
        public final int          linkTitleHeight;

        PrewarmResult(StaticLayout tl, StaticLayout ll) {
            textLayout      = tl;
            textHeight      = tl != null ? tl.getHeight() : 0;
            linkTitleLayout = ll;
            linkTitleHeight = ll != null ? ll.getHeight() : 0;
        }
    }

    private final HandlerThread bgThread;
    private final Handler       bgHandler;
    private final LruCache<String, PrewarmResult> resultCache = new LruCache<>(MAX_CACHED);

    // Thread-local paint copies — never shared with the UI thread.
    private final TextPaint bgTextPaint;
    private final TextPaint bgLinkTitlePaint;

    /**
     * Convenience factory — creates the prewarmer without needing a live CanvasView.
     * Uses the exact same paint spec as ChannelPostCanvasView.init() so layout results
     * are identical to what the View would produce. Call from Activity.
     */
    public static ChannelPostLayoutPrewarmer createForContext(android.content.Context ctx) {
        float density = ctx.getResources().getDisplayMetrics().density;

        TextPaint textPaint = new TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        textPaint.setTextSize(14 * density);
        textPaint.setColor(androidx.core.content.ContextCompat.getColor(
                ctx, com.callx.app.status.R.color.text_primary));

        TextPaint linkPaint = new TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        linkPaint.setTextSize(13 * density);
        linkPaint.setColor(androidx.core.content.ContextCompat.getColor(
                ctx, com.callx.app.status.R.color.text_primary));
        linkPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);

        return new ChannelPostLayoutPrewarmer(textPaint, linkPaint);
    }

    /**
     * @param postTextPaint  The exact TextPaint used for post body text in ChannelPostCanvasView.
     * @param linkTitlePaint The exact TextPaint used for link preview titles.
     */
    public ChannelPostLayoutPrewarmer(TextPaint postTextPaint, TextPaint linkTitlePaint) {
        // Clone — we own these copies; UI thread keeps its own originals.
        bgTextPaint      = new TextPaint(postTextPaint);
        bgLinkTitlePaint = new TextPaint(linkTitlePaint);

        bgThread = new HandlerThread("ChannelLayoutPrewarmer", Process.THREAD_PRIORITY_BACKGROUND);
        bgThread.start();
        bgHandler = new Handler(bgThread.getLooper());
    }

    /**
     * Schedule background pre-computation for posts[startIdx..endIdx).
     * Already-cached entries are skipped instantly.
     *
     * @param contentWidthPx Available text width in pixels = containerWidth - 2*cardPadding.
     */
    public void prewarm(List<ChannelPost> posts, int startIdx, int endIdx, int contentWidthPx) {
        if (posts == null || contentWidthPx <= 0) return;
        final int safeEnd = Math.min(endIdx, posts.size());
        // Snapshot references (safe — ChannelPost fields are final/immutable strings).
        bgHandler.post(() -> {
            for (int i = startIdx; i < safeEnd; i++) {
                ChannelPost p = posts.get(i);
                if (p == null || p.id == null) continue;
                if (resultCache.get(p.id) != null) continue; // already warm

                StaticLayout textLayout      = buildTextLayout(p.text, contentWidthPx);
                StaticLayout linkTitleLayout = null;
                if ("link".equals(p.type) && p.linkTitle != null && !p.linkTitle.isEmpty()) {
                    // Link card has horizontal padding on both sides inside the card.
                    int linkW = Math.max(1, contentWidthPx - 48);
                    linkTitleLayout = buildLinkTitleLayout(p.linkTitle, linkW);
                }
                resultCache.put(p.id, new PrewarmResult(textLayout, linkTitleLayout));
            }
        });
    }

    /**
     * Returns prewarmed layouts for the given postId, or null if not ready.
     * Call from the UI thread during bind(); the check is instant.
     */
    public PrewarmResult getPrewarmed(String postId) {
        return postId != null ? resultCache.get(postId) : null;
    }

    /** Evict all cached layouts (e.g. on width change). */
    public void clear() {
        bgHandler.post(resultCache::evictAll);
    }

    /** Stop the background thread. Call from Activity.onDestroy(). */
    public void shutdown() {
        bgThread.quitSafely();
    }

    // ── Private helpers ────────────────────────────────────────────────────

    private StaticLayout buildTextLayout(String text, int widthPx) {
        if (text == null || text.isEmpty()) return null;
        int w = Math.max(1, widthPx);
        return StaticLayout.Builder
                .obtain(text, 0, text.length(), bgTextPaint, w)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1f)
                .setIncludePad(false)
                .build();
    }

    private StaticLayout buildLinkTitleLayout(String title, int widthPx) {
        int w = Math.max(1, widthPx);
        return StaticLayout.Builder
                .obtain(title, 0, title.length(), bgLinkTitlePaint, w)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1f)
                .setIncludePad(false)
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build();
    }
}
