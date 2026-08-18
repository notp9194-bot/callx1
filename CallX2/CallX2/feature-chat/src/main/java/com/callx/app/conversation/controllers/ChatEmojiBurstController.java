package com.callx.app.conversation.controllers;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;

import com.callx.app.conversation.canvas.EmojiBurstCanvasView;
import com.callx.app.models.Message;

/**
 * ChatEmojiBurstController — big emoji pop-up, re-enabled with an
 * "ultra advanced" performance-safe implementation.
 *
 * WHY THE ORIGINAL WAS RIPPED OUT: the earlier version animated the burst
 * view on every onChildAdded — including the 30-message backlog replay that
 * fires the instant a chat is opened — and it re-inflated/re-measured things
 * on the main thread while the RecyclerView was doing its first layout +
 * scroll-to-bottom pass. That's exactly the kind of main-thread contention
 * that causes scroll jank on open. This version fixes all of that, and this
 * pass additionally strips every avoidable allocation and regex hit out of
 * the hot per-message path:
 *
 *  1. RECENCY GUARD — a message only qualifies for a burst if it arrived
 *     within RECENT_WINDOW_MS of "now". Historical backlog messages that
 *     stream in via onChildAdded when a chat is opened (or via delta sync)
 *     all carry OLD timestamps, so they're silently skipped. Zero cost:
 *     one long subtraction, no reflection, no view work.
 *
 *  2. SELF-MESSAGE GUARD — only the partner's incoming messages trigger it;
 *     your own echoed-back sends never do. Checked before any string work.
 *
 *  3. REGEX-FREE, ALLOCATION-FREE EMOJI DETECTION — the previous version
 *     ran String.replace()/replaceAll() (which COMPILES A NEW REGEX on
 *     every single call) and then, per character, allocated a substring
 *     and ran it through Pattern.matcher(...).matches(). That's a fresh
 *     Matcher + regex traversal for every codepoint of every incoming
 *     message. isEmojiOnly() now does one raw single-pass scan over the
 *     string's codepoints with plain integer range comparisons — no
 *     Pattern, no Matcher, no substring, no intermediate String at all.
 *
 *  4. CANVAS-RENDERED, SINGLE REUSED VIEW — no inflate, no allocation per
 *     burst. This talks to a plain custom View (EmojiBurstCanvasView) that
 *     paints the emoji with one canvas.drawText() call instead of a
 *     TextView, so changing the emoji is just an invalidate() — no
 *     Editable/StaticLayout rebuild, and critically no requestLayout(),
 *     since the view's size never depends on its content.
 *
 *  5. ZERO PER-BURST OBJECT CHURN — the interpolator, both animator
 *     listeners, and the hide Runnable are all built ONCE as final fields
 *     and reused for every burst, instead of `new`-ing a fresh
 *     OvershootInterpolator + AnimatorListenerAdapter + lambda every time
 *     an emoji arrives. The resolved EmojiBurstCanvasView reference is
 *     cached too (it can't change during the Activity's lifetime), so
 *     view()/getBinding() is only ever walked once. Less garbage means
 *     fewer GC pauses competing with scroll frames.
 *
 *  6. NO EXPLICIT HARDWARE LAYER — the previous pass toggled
 *     LAYER_TYPE_HARDWARE on/off around every burst. That's the right move
 *     for a view with an expensive onDraw() (e.g. a bitmap or complex
 *     canvas work), because it caches the rendered content in an offscreen
 *     texture so later frames are pure compositing. But this view's
 *     onDraw() is a single canvas.drawText() call — building/destroying an
 *     offscreen texture on every burst costs more than it saves. On a
 *     hardware-accelerated window (virtually all Android apps), the
 *     scaleX/scaleY/alpha ViewPropertyAnimator already drives the View's
 *     RenderNode as GPU transform + alpha uniforms without re-invoking
 *     onDraw() per frame, REGARDLESS of an explicit layer — so dropping
 *     setLayerType() entirely removes pure overhead with no animation-
 *     smoothness cost.
 *
 *  7. ONE ViewPropertyAnimator CHAIN — no ObjectAnimator/AnimatorSet
 *     allocation, no property-reflection lookups. Every call cancels the
 *     previous chain first so rapid-fire emoji spam can never stack
 *     animators or leak handlers.
 *
 *  8. COOLDOWN — a 4-second minimum gap between bursts, independent of the
 *     animation's own lifecycle, so a partner spamming emoji can't queue
 *     up dozens of pending pop-ups.
 *
 *  9. ISOLATED FROM CHAT LAYOUT — the burst view lives in the outer
 *     FrameLayout as an independent, match_parent sibling of the
 *     RecyclerView (see activity_chat.xml). Its own size is fixed and
 *     content-independent (EmojiBurstCanvasView#onMeasure), and FrameLayout
 *     measures siblings independently anyway, so nothing here ever triggers
 *     a measure/layout pass on the message list — smooth scrolling is
 *     completely unaffected.
 *
 * Net effect: the feature is fully back, animating only for a genuinely
 * new, genuinely partner, genuinely emoji-only message at most once every
 * 4 seconds — with a detection path that touches zero regex/allocation and
 * a render/animation path that allocates nothing per burst at all.
 */
public class ChatEmojiBurstController {

    /** Ignore onChildAdded events for messages older than this — filters out
     *  the initial backlog/delta-sync replay so opening a chat never fires it. */
    private static final long RECENT_WINDOW_MS = 8_000L;

    /** Minimum gap between two bursts, regardless of how many emoji arrive. */
    private static final long COOLDOWN_MS = 4_000L;

    private static final long POP_IN_MS   = 220L;
    private static final long HOLD_MS     = 650L;
    private static final long POP_OUT_MS  = 260L;

    /** Cap on how many emoji "units" (codepoints) a message may contain and
     *  still qualify — keeps a long non-emoji message from being scanned
     *  needlessly and matches the old text-length cap's intent. */
    private static final int MAX_EMOJI_UNITS = 12;

    /** Interpolators are stateless — one shared instance for every burst
     *  instead of allocating a new one each time. */
    private static final Interpolator POP_IN_INTERPOLATOR = new OvershootInterpolator(1.6f);

    private final ChatActivityDelegate delegate;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private long lastBurstAtMs = 0L;
    private boolean hidePending = false;

    /** Resolved once and cached — the view never changes for the life of
     *  the Activity, so there's no reason to walk the binding every burst. */
    private EmojiBurstCanvasView cachedView;
    private boolean viewResolved = false;

    // Built once, reused for every burst — zero listener/lambda allocation
    // on the hot path.
    private final Runnable hideRunnable = this::playHideAnimation;
    private final Animator.AnimatorListener popInListener = new AnimatorListenerAdapter() {
        @Override public void onAnimationEnd(Animator animation) {
            hidePending = true;
            mainHandler.postDelayed(hideRunnable, HOLD_MS);
        }
    };
    private final Animator.AnimatorListener popOutListener = new AnimatorListenerAdapter() {
        @Override public void onAnimationEnd(Animator animation) {
            EmojiBurstCanvasView v = cachedView;
            if (v == null) return;
            v.setVisibility(View.GONE);
            v.clearEmoji();
        }
    };

    public ChatEmojiBurstController(ChatActivityDelegate delegate) {
        this.delegate = delegate;
    }

    public void release() {
        mainHandler.removeCallbacksAndMessages(null);
        hidePending = false;
        EmojiBurstCanvasView v = view();
        if (v != null) {
            v.animate().cancel();
            v.setVisibility(View.GONE);
            v.clearEmoji();
        }
    }

    public void onMessageReceived(Message m) {
        if (m == null) return;

        // Only plain text messages carry emoji content.
        if (m.type != null && !"text".equals(m.type)) return;

        // Never react to our own echoed-back sends. Checked before any
        // string scanning since it's the cheapest possible filter.
        String currentUid = delegate.getCurrentUid();
        if (m.senderId == null || m.senderId.equals(currentUid)) return;

        // Backlog/delta-sync guard: skip anything that isn't fresh.
        long now = System.currentTimeMillis();
        if (m.timestamp == null || (now - m.timestamp) > RECENT_WINDOW_MS) return;

        // Spam guard checked before the (still cheap, but not free) text
        // scan — no point scanning a message we'd discard anyway.
        if (now - lastBurstAtMs < COOLDOWN_MS) return;

        if (m.text == null || !isEmojiOnly(m.text)) return;

        lastBurstAtMs = now;
        showBurst(m.text.trim());
    }

    private EmojiBurstCanvasView view() {
        if (viewResolved) return cachedView;
        viewResolved = true;
        if (delegate != null && delegate.getBinding() != null) {
            cachedView = delegate.getBinding().tvEmojiBurst;
        }
        return cachedView;
    }

    private void showBurst(String emoji) {
        EmojiBurstCanvasView v = view();
        if (v == null) return;

        // Cancel anything in flight so rapid messages never stack animators
        // or leave a duplicate hide callback queued.
        if (hidePending) {
            mainHandler.removeCallbacks(hideRunnable);
            hidePending = false;
        }
        v.animate().cancel();

        v.setEmoji(emoji);
        v.setAlpha(0f);
        v.setScaleX(0.35f);
        v.setScaleY(0.35f);
        v.setVisibility(View.VISIBLE);

        v.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(POP_IN_MS)
                .setInterpolator(POP_IN_INTERPOLATOR)
                .setListener(popInListener)
                .start();
    }

    private void playHideAnimation() {
        hidePending = false;
        EmojiBurstCanvasView v = cachedView;
        if (v == null) return;
        v.animate()
                .alpha(0f)
                .scaleX(0.6f)
                .scaleY(0.6f)
                .setDuration(POP_OUT_MS)
                .setInterpolator(null)
                .setListener(popOutListener)
                .start();
    }

    /** Upfront O(1) length guard, checked before the per-codepoint scan —
     *  a normal chat message (paragraphs, links, long sentences) is
     *  rejected in one comparison instead of walking every character. */
    private static final int MAX_RAW_LENGTH = 64;

    /**
     * Single-pass, allocation-free, regex-free emoji-only check.
     *
     * Walks the raw string's codepoints directly: whitespace, the variation
     * selector (FE0F), and the zero-width joiner (200D) are skipped in
     * place; every other codepoint must fall in one of the emoji Unicode
     * ranges via plain integer comparisons, or the message is rejected
     * immediately. No String.trim()/replace()/replaceAll() (each of which
     * either allocates a new String or, worse, compiles a fresh regex), no
     * substring, no Matcher.
     */
    private boolean isEmojiOnly(String raw) {
        int len = raw.length();
        // Cheapest possible rejection first: the vast majority of chat
        // messages are ordinary sentences, far longer than any real
        // emoji-only message could be — one integer compare skips the
        // whole scan for all of them.
        if (len == 0 || len > MAX_RAW_LENGTH) return false;

        int i = 0;
        boolean sawEmoji = false;
        int emojiUnits = 0;

        while (i < len) {
            int cp = raw.codePointAt(i);
            int charCount = Character.charCount(cp);

            if (Character.isWhitespace(cp) || cp == 0xFE0F || cp == 0x200D) {
                i += charCount;
                continue;
            }

            if (!isEmojiCodePoint(cp)) return false;

            sawEmoji = true;
            emojiUnits++;
            if (emojiUnits > MAX_EMOJI_UNITS) return false;

            i += charCount;
        }
        return sawEmoji;
    }

    private static boolean isEmojiCodePoint(int cp) {
        return (cp >= 0x1F300 && cp <= 0x1FAFF)
                || (cp >= 0x2600 && cp <= 0x27BF)
                || (cp >= 0x1F1E6 && cp <= 0x1F1FF)
                || (cp >= 0x2190 && cp <= 0x21FF)
                || (cp >= 0x2B00 && cp <= 0x2BFF);
    }
}
