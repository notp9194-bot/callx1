package com.callx.app.conversation;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaPlayer;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.paging.PagingDataAdapter;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.chat.R;

import com.callx.app.models.Message;
import com.callx.app.audio.GlobalVoicePlaybackManager;
import com.callx.app.utils.FileUtils;
import com.callx.app.utils.MediaCache;
import com.callx.app.utils.MediaAutoDownloadPolicy;
import com.callx.app.utils.MediaSaveHelper;
import com.callx.app.utils.BlurHashPlaceholder;
import com.callx.app.conversation.controllers.MediaDownloadQueue;

import java.text.SimpleDateFormat;
import java.util.Locale;
import com.callx.app.utils.LinkPreviewFetcher;

/**
 * MessagePagingAdapter — Paging 3 PagingDataAdapter for chat messages.
 *
 * Drop-in replacement for MessageAdapter when loading messages from Room DB
 * via Pager3 + PagingSource. Supports sent/received layout types, text,
 * image, audio, file, and video message rendering identical to MessageAdapter.
 *
 * Usage in ChatActivity:
 *   MessagePagingAdapter pagingAdapter = new MessagePagingAdapter(uid, false);
 *   binding.rvMessages.setAdapter(pagingAdapter);
 *   viewModel.getPagedMessages(chatId).observe(this, pagingAdapter::submitData);
 */
public class MessagePagingAdapter
        extends PagingDataAdapter<Message, MessagePagingAdapter.VH> {
    private java.util.function.Consumer<com.callx.app.models.Message> seenByClickListener;
    private java.util.Map<String, String> memberPhotos;

    // ── Read-more / Read-less expanded-state tracker ─────────────────────
    // Keeps expand state here (in the adapter) rather than in the canvas
    // view, so RecyclerView recycling never loses a user's "Read more" tap.
    private final java.util.Set<String> expandedMessageIds =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    // ── TELEGRAM-STYLE SCROLL OPTIMIZATION ────────────────────────────────
    // Track RecyclerView scroll state to defer expensive binds during fling.
    // During scroll: skip full text linkification, skip Glide overrides, defer reactions
    // On idle: fully bind everything with polish
    private volatile int recyclerViewScrollState = RecyclerView.SCROLL_STATE_IDLE;
    
    // Set by ChatActivity's OnScrollListener — used here to optimize bind time
    public void setRecyclerViewScrollState(int state) {
        this.recyclerViewScrollState = state;
    }

    // ── TELEGRAM-STYLE "SEND" ANIMATION — single-spring, multi-property ──
    // The bulk-load ItemAnimator in ChatActivity is intentionally instant
    // (0ms) for performance during history/pagination loads — see the
    // WHATSAPP-STYLE FIX comment on rvMessages' ItemAnimator. We don't touch
    // that. Instead, the one bubble WE just sent gets a one-shot physics
    // rise+fade applied directly on bind, driven by the message id that
    // ChatActivity.pushMessage() stashes here right after the id is minted.
    // Single-shot: consumed (nulled) the instant it matches, so scrolling
    // the same holder off/on screen later never re-triggers it, and it
    // never fires for incoming/forwarded/history items.
    //
    // ADVANCED: driven by ONE SpringAnimation over an abstract 0→1 progress
    // value (FloatValueHolder), not one spring per property. Each
    // SpringAnimation is its own Choreographer frame-callback registration,
    // so 4 independent springs (translationY/scaleX/scaleY/alpha) means the
    // animation subsystem does 4x the per-frame bookkeeping for one visual
    // effect. Collapsing to a single physics simulation — with translationY,
    // scale, and alpha all derived from the same progress value inside one
    // update listener — cuts that to 1x while looking identical (alpha is
    // clamped to [0,1] so it doesn't inherit the small bounce-overshoot
    // that gives translationY/scale their "pop"). SpringForce holds no
    // per-animation mutable state (its physics coefficients are a pure
    // function of stiffness/damping/finalPosition), so one config instance
    // is safely reused for every send. androidx.dynamicanimation is already
    // a feature-chat dependency (used by feature-reels too) — no new deps.
    private volatile String pendingSendAnimMessageId;

    private static final androidx.dynamicanimation.animation.SpringForce SEND_ANIM_FORCE =
            new androidx.dynamicanimation.animation.SpringForce(1f)
                    .setStiffness(1200f)
                    .setDampingRatio(androidx.dynamicanimation.animation.SpringForce.DAMPING_RATIO_LOW_BOUNCY);

    /** Called by ChatActivity right after an outgoing message's id is set. */
    public void markMessageForSendAnimation(String messageId) {
        this.pendingSendAnimMessageId = messageId;
    }

    /**
     * Cancels any in-flight send-in spring on this holder and snaps its
     * itemView back to a clean identity transform. MUST be called before
     * reusing a holder for a different message (onViewRecycled) and is also
     * the fast no-op path taken on every ordinary bind so a holder that was
     * mid-bounce can never bleed its transform onto unrelated content.
     */
    private void cancelSendAnim(VH h) {
        if (h.sendSpringProgress != null) {
            h.sendSpringProgress.cancel(); // triggers the end listener synchronously -> restores layer type
            h.sendSpringProgress = null;
        }
    }

    private void resetSendAnimState(VH h) {
        cancelSendAnim(h);
        View v = h.itemView;
        // Cheap float compares — skips the (rare) write + invalidate when
        // already clean, which is the case for ~every bind that isn't the
        // one just-sent bubble.
        if (v.getAlpha()        != 1f) v.setAlpha(1f);
        if (v.getScaleX()       != 1f) v.setScaleX(1f);
        if (v.getScaleY()       != 1f) v.setScaleY(1f);
        if (v.getTranslationY() != 0f) v.setTranslationY(0f);
        if (v.getLayerType()    != View.LAYER_TYPE_NONE) v.setLayerType(View.LAYER_TYPE_NONE, null);
    }

    private void playSendInAnimation(VH h) {
        final View v = h.itemView;
        cancelSendAnim(h);

        // Respect the OS-level "remove animations" accessibility/dev-option
        // setting — DynamicAnimation springs are real-time physics and do
        // NOT auto-respect ValueAnimator's duration-scale the way a normal
        // ObjectAnimator does, so this has to be checked explicitly. Also
        // skip while the list is actively being flung: stacking a physics
        // animation on top of an in-progress fling is pure added GPU work
        // that can only cost frames, never add polish, since the item is
        // about to be scrolled past anyway.
        boolean animsEnabled = android.animation.ValueAnimator.areAnimatorsEnabled();
        if (!animsEnabled || recyclerViewScrollState != RecyclerView.SCROLL_STATE_IDLE) {
            v.setAlpha(1f); v.setScaleX(1f); v.setScaleY(1f); v.setTranslationY(0f);
            return;
        }

        final float density = v.getResources().getDisplayMetrics().density;
        final float riseDistancePx = 40f * density; // rises from ~where the compose bar sits

        // Cache the row as a GPU texture for the transform's lifetime so
        // every physics frame is a cheap composited blit instead of a full
        // re-draw of the canvas-rendered bubble (text layout/paint/shadow).
        // Mirrors the same trick bindCanvasMessage() already uses on
        // canvasView during scroll — released back to LAYER_TYPE_NONE the
        // instant the spring settles so an idle row never keeps a GPU
        // texture pinned in the RecycledViewPool.
        v.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        v.setAlpha(0f);
        v.setScaleX(0.9f);
        v.setScaleY(0.9f);
        v.setTranslationY(riseDistancePx);

        androidx.dynamicanimation.animation.FloatValueHolder progress =
                new androidx.dynamicanimation.animation.FloatValueHolder(0f);

        h.sendSpringProgress = new androidx.dynamicanimation.animation.SpringAnimation(progress)
                .setSpring(SEND_ANIM_FORCE)
                .setStartVelocity(3.2f) // "flicked up" momentum from the Send tap (progress-units/sec, not px/sec)
                .addUpdateListener((animation, value, velocity) -> {
                    // value oscillates slightly past 1 near the end (the
                    // LOW_BOUNCY damping ratio) — that overshoot is exactly
                    // what gives translationY/scale their single "pop"
                    // rather than a dead-stop. Alpha is clamped since going
                    // past full/zero opacity would be visually invalid.
                    v.setTranslationY((1f - value) * riseDistancePx);
                    float scale = 0.9f + 0.1f * value;
                    v.setScaleX(scale);
                    v.setScaleY(scale);
                    v.setAlpha(Math.max(0f, Math.min(1f, value)));
                })
                .addEndListener((animation, canceled, value, velocity) -> {
                    if (v.getLayerType() != View.LAYER_TYPE_NONE) {
                        v.setLayerType(View.LAYER_TYPE_NONE, null);
                    }
                });
        h.sendSpringProgress.start();
    }

    /**
     * Wires the "Read more / Read less" expand toggle for a bound holder —
     * shared by the plain-text bubble AND the image/video/media-group
     * caption paths, since MessageBubbleCanvasView's canvas rendering
     * reuses the same hasLongText/isTextExpanded machinery for all of
     * them (see MessageBubbleCanvasView.drawReadMoreStrip()). Same
     * scroll-anchor behaviour as the original text-only wiring: the
     * item's on-screen top edge is preserved across the resulting
     * notifyItemChanged() so expanding/collapsing a long caption doesn't
     * jump the list.
     */
    private void wireCaptionReadMore(VH h,
            com.callx.app.conversation.canvas.MessageBubbleCanvasView cv, String msgId) {
        cv.setTextExpanded(expandedMessageIds.contains(msgId));
        cv.setReadMoreListener(nowExpanded -> {
            if (nowExpanded) expandedMessageIds.add(msgId);
            else             expandedMessageIds.remove(msgId);

            int pos = h.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;

            final RecyclerView rv = (h.itemView.getParent() instanceof RecyclerView)
                    ? (RecyclerView) h.itemView.getParent() : null;
            final int savedTop = (rv != null) ? h.itemView.getTop() : 0;

            notifyItemChanged(pos);

            if (rv != null) {
                rv.post(() -> {
                    RecyclerView.LayoutManager lm = rv.getLayoutManager();
                    if (lm instanceof androidx.recyclerview.widget.LinearLayoutManager) {
                        ((androidx.recyclerview.widget.LinearLayoutManager) lm)
                                .scrollToPositionWithOffset(pos, savedTop);
                    }
                });
            }
        });
    }


    // CONFIRMED (user asked to verify): PagingDataAdapter(DiffUtil.ItemCallback)
    // below is called with no explicit dispatcher args, which means it uses
    // Paging3's defaults — Dispatchers.Main.immediate for applying the diff
    // to the adapter, and Dispatchers.Default (a background thread pool) for
    // COMPUTING the diff itself. DiffUtil.calculateDiff() never runs on the
    // main thread here; only the final notifyItem*() calls do, which is the
    // correct/required behavior. No change needed.

    // ── DiffUtil — required by PagingDataAdapter ──────────────────
    private static final DiffUtil.ItemCallback<Message> DIFF =
        new DiffUtil.ItemCallback<Message>() {
            @Override
            public boolean areItemsTheSame(@NonNull Message a, @NonNull Message b) {
                return a.messageId != null && a.messageId.equals(b.messageId);
            }

            @Override
            public boolean areContentsTheSame(@NonNull Message a, @NonNull Message b) {
                // TELEGRAM+WHATSAPP HYBRID: Only compare CRITICAL fields.
                // Removed: deliveredAt, readAt, pinned, broadcast, forwardedFrom, expiresAt
                // These trigger full reblind but aren't shown during normal scroll.
                // They're caught by getChangePayload() if needed later.
                // Result: 19→6 field comparisons = 3x faster on 1000-message lists
                boolean same = a.messageId.equals(b.messageId)
                    && safeEquals(a.text, b.text)           // Content changed
                    && safeEquals(a.type, b.type)           // Type changed (e.g. call_entry)
                    && safeEquals(a.status, b.status)       // Read/delivered ticks
                    && longEquals(a.timestamp, b.timestamp)
                    && boolEquals(a.edited, b.edited)
                    && reactionsEqual(a.reactions, b.reactions)     // Emoji reactions
                    && pollVotesEqual(a.pollVotes, b.pollVotes)     // Poll updates
                    && safeEquals(asStr(a.pollClosed), asStr(b.pollClosed));
                // DEBUG (temporary): if this is STILL flagging unchanged
                // messages as "different" on a plain send, this log proves
                // it directly — with exactly which field tripped it.
                if (!same) {
                    com.callx.app.debug.DebugLogBuffer.d("ChatPagingDebug", "areContentsTheSame=FALSE id=" + a.messageId
                        + " text=" + safeEquals(a.text, b.text)
                        + " type=" + safeEquals(a.type, b.type)
                        + " status=" + safeEquals(a.status, b.status)
                        + " ts=" + longEquals(a.timestamp, b.timestamp)
                        + " edited=" + boolEquals(a.edited, b.edited)
                        + " reactions=" + reactionsEqual(a.reactions, b.reactions)
                        + " pollVotes=" + pollVotesEqual(a.pollVotes, b.pollVotes)
                        + " pollClosed=" + safeEquals(asStr(a.pollClosed), asStr(b.pollClosed)));
                }
                return same;
            }

            private String asStr(Boolean b) { return b == null ? "null" : b.toString(); }
            private String asStr(Long l) { return l == null ? "null" : l.toString(); }

            // BUG FIX: timestamp/edited are boxed Long/Boolean. Every Paging
            // refresh (triggered by reanchorPagingToBottom() on EVERY send —
            // see ChatActivity#severPagingIfAtBottom) re-queries Room and maps
            // fresh MessageEntity → Message objects for every currently-loaded
            // row, including ones nothing changed for. Those fresh objects get
            // brand-new Long instances for timestamp — epoch-millis values are
            // always outside Java's Long autobox cache (-128..127), so the old
            // `a.timestamp == b.timestamp` reference comparison was FALSE for
            // literally every message, every single refresh, even when the
            // value was numerically identical. That made areContentsTheSame()
            // (and getChangePayload()'s structuralSame check, same bug) report
            // "changed" for every visible row on every send — forcing a full
            // rebind (Glide reload included) of every message, not just the
            // new one. That's the "images above flicker/rebuild after sending"
            // bug. Value-based equals() fixes it for both fields.
            private boolean longEquals(Long x, Long y) {
                if (x == null && y == null) return true;
                if (x == null || y == null) return false;
                return x.longValue() == y.longValue();
            }

            private boolean boolEquals(Boolean x, Boolean y) {
                if (x == null && y == null) return true;
                if (x == null || y == null) return false;
                return x.booleanValue() == y.booleanValue();
            }

            private boolean pollVotesEqual(java.util.Map<String, java.util.List<Integer>> x,
                                            java.util.Map<String, java.util.List<Integer>> y) {
                if (x == null && y == null) return true;
                if (x == null || y == null) return false;
                return x.equals(y);
            }

            private boolean safeEquals(String x, String y) {
                if (x == null && y == null) return true;
                if (x == null || y == null) return false;
                return x.equals(y);
            }

            private boolean reactionsEqual(java.util.Map<String, String> x,
                                            java.util.Map<String, String> y) {
                if (x == null && y == null) return true;
                if (x == null || y == null) return false;
                return x.equals(y);
            }

            @Override
            public Object getChangePayload(@NonNull Message a, @NonNull Message b) {
                // Structural fields (text/type/timestamp/messageId) never get
                // a fast path — if any of these differ it's a genuinely
                // different-looking bubble, so fall through to a full rebind.
                boolean structuralSame =
                        safeEquals(a.text, b.text) &&
                        safeEquals(a.type, b.type) &&
                        longEquals(a.timestamp, b.timestamp);
                if (!structuralSame) return null; // null → full rebind

                // PERF: bit-flag combine — check each of the 4 fast-path
                // fields independently instead of mutually-exclusive "only X
                // changed" branches, so e.g. status+reactions changing in
                // the same diff pass still gets ONE combined fast-path
                // payload instead of falling through to a full rebind.
                int flags = 0;
                if (!safeEquals(a.status, b.status))               flags |= FLAG_STATUS;
                if (!reactionsEqual(a.reactions, b.reactions))     flags |= FLAG_REACTIONS;
                if (!pollVotesEqual(a.pollVotes, b.pollVotes))     flags |= FLAG_POLL;
                if (!boolEquals(a.edited, b.edited))               flags |= FLAG_EDITED;

                // No recognized fast-path field actually changed (e.g. a
                // field outside the DIFF's areContentsTheSame() 6-field set
                // that we don't have a partial-bind path for) — full rebind.
                if (flags == 0) return null;

                return flags; // single combined payload, autoboxed to Integer
            }
        };

    // ── View types ────────────────────────────────────────────────
    // WhatsApp-style manual image download — URLs currently being fetched
    // via the download pill, so a rebind mid-download (scroll away/back)
    // doesn't kick off a second parallel download for the same message.
    private final java.util.Set<String> downloadingMediaUrls = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    private static final int TYPE_SENT        = 1;
    private static final int TYPE_RECEIVED    = 2;
    private static final int TYPE_STATUS_SEEN = 3;
    private static final int TYPE_REEL_SEEN   = 4;
    private static final int TYPE_CALL_ENTRY  = 5;
    /** reel_seen row that belongs to THIS viewer's own "watched X's reel"
     *  event — must render as nothing (0x0), since only the reel OWNER
     *  should ever see the bubble. See getItemViewType() below. */
    private static final int TYPE_HIDDEN         = 6;
    /** Standalone date separator chip — injected by insertSeparators() in ChatActivity.
     *  Stored as a synthetic Message with type="date_separator"; text holds the label. */
    private static final int TYPE_DATE_SEPARATOR   = 7;
    /** View-once message: receiver sees "View Once" tap badge. */
    private static final int TYPE_VIEW_ONCE_SENT    = 8;
    /** View-once message: already opened — shows expired state. */
    private static final int TYPE_VIEW_ONCE_EXPIRED = 9;
    /** View-once message: sent by ME, not yet opened by receiver — shows lock/waiting state. */
    private static final int TYPE_VIEW_ONCE_SENT_WAITING = 10;
    /**
     * Phase 1 Canvas rendering (see conversation.canvas.MessageBubbleCanvasView).
     * Used ONLY for messages isCanvasEligible() accepts — plain text (any
     * sent/received), a single sent image, or a sent media group, with or
     * without reactions/pinned-state/group-sender-name, but no
     * forward-label/edited-label/broadcast. Everything else still uses
     * TYPE_SENT/TYPE_RECEIVED + item_message_sent/received.xml as before.
     */
    private static final int TYPE_CANVAS_SENT     = 11;
    private static final int TYPE_CANVAS_RECEIVED = 12;

    static final String PAYLOAD_VIEW_ONCE  = "view_once_state";

    // ── PERF: bit-flag combined payload for status/reactions/poll/edited ──
    // These 4 used to be mutually-exclusive String payloads — getChangePayload()
    // checked "ONLY status changed", "ONLY reactions changed", etc. one at a
    // time, so if e.g. status AND reactions changed in the same diff pass
    // (e.g. a delivered→read tick flip that arrived in the same Firestore
    // snapshot as a new emoji reaction), none of the "only X" checks matched
    // and it silently fell through to `return null` — a full rebind (Glide
    // reload, Linkify, bubble redraw) just to update two small views.
    //
    // Now each of the 4 is a single bit; getChangePayload() ORs together
    // every field that actually changed and returns one Integer. A single
    // onBindViewHolder(payloads) branch below reads the bitmask and runs
    // only the fast-path binds for the bits that are set — still zero full
    // rebinds, even when multiple of these fields change at once.
    //
    // notifyItemChanged(i, PAYLOAD_REACTIONS) call sites (e.g. the reactions-
    // only manual trigger below) keep working unchanged: a lone flag is
    // just a 1-bit mask, handled the same way as a combined one.
    static final int FLAG_STATUS     = 1 << 0;
    static final int FLAG_REACTIONS  = 1 << 1;
    static final int FLAG_POLL       = 1 << 2;
    static final int FLAG_EDITED     = 1 << 3;

    // Kept as Integer constants (not String) so existing call sites that pass
    // e.g. PAYLOAD_REACTIONS directly to notifyItemChanged(...) still compile
    // and behave identically — just a 1-bit mask instead of a String key.
    static final Integer PAYLOAD_STATUS     = FLAG_STATUS;
    static final Integer PAYLOAD_REACTIONS  = FLAG_REACTIONS;
    static final Integer PAYLOAD_POLL       = FLAG_POLL;
    static final Integer PAYLOAD_EDITED     = FLAG_EDITED;
    /** Sent message's readBy map changed — update the "Seen by X" strip only. */
    public static final String PAYLOAD_READ_BY = "read_by";
    // PERF: search-query-only change (user typed/cleared a character in
    // the search bar). Old code called notifyDataSetChanged() on every
    // keystroke, which rebinds EVERY visible row from scratch — Glide
    // reload, Linkify, full canvas re-measure, the works — just to redraw
    // a yellow highlight. This payload skips straight to
    // bindSearchHighlightOnly(), which for the common Canvas-rendered case
    // is just cv.setSearchHighlight() + invalidate(), no rebind at all.
    static final String PAYLOAD_SEARCH     = "search";

    // PERF: RGB_565 for thumbnail-sized images — half the memory of ARGB_8888.
    // Thumbnails (avatars, video covers, reply previews, status/reel chips) have
    // no alpha channel, so the extra byte per pixel in ARGB_8888 is pure waste.
    // Full-size image loads (720×720) keep ARGB_8888 for quality.
    private static final RequestOptions THUMB_RGB565 = new RequestOptions()
            .format(DecodeFormat.PREFER_RGB_565)
            .diskCacheStrategy(DiskCacheStrategy.ALL);

    // Corner radius for the swipe-reply media thumbnail (iv_reply_thumb).
    // PERF (ultra): rounding is done via ViewOutlineProvider/clipToOutline,
    // set ONCE per pooled ViewHolder in VH's constructor — not a Glide
    // RoundedCorners bitmap transform recomputed on every bind/rebind/scroll.
    // This avoids an extra bitmap allocation + draw per load, and lets the
    // thumbnail share Glide's plain centerCrop() memory/disk cache entry
    // with other centerCrop thumbnails (video/link/reel) instead of a
    // separate transformed-bitmap cache key per corner radius.
    private static final float REPLY_THUMB_CORNER_DP = 14f;
    private static volatile float sReplyThumbCornerPx = -1f;
    private static final ViewOutlineProvider REPLY_THUMB_OUTLINE = new ViewOutlineProvider() {
        @Override
        public void getOutline(View view, android.graphics.Outline outline) {
            outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), sReplyThumbCornerPx);
        }
    };

    // ── PERF ADV: Shared avatar bitmap LruCache ───────────────────────────
    // In group chats the same sender photo URL appears for every message from
    // that person.  Without this cache every cell makes a separate Glide
    // decode of the same URL; with it the first decode is stored here and
    // every subsequent cell delivers the result instantly via setX(poolHit)
    // with zero network/disk/decode overhead.
    // Sized to 60 entries — avatar bitmaps are small (96×96 circle-cropped,
    // ~36 KiB each at RGB_565) so all 60 together cost < 2 MB RAM, far less
    // than a single full-resolution media thumbnail.
    // LruCache is internally thread-safe (synchronized get/put).
    private static final android.util.LruCache<String, android.graphics.Bitmap> AVATAR_BITMAP_CACHE =
            new android.util.LruCache<>(60);

    // ── BUGFIX: composite pool key (url@WxH) ──────────────────────────────
    // DECODED_BITMAP_CACHE used to be keyed by raw URL alone. The SAME
    // remote URL can legitimately be decoded at different target sizes in
    // different bubble types — e.g. a reel's thumbnail URL is loaded at
    // 330×474 for the big reel-share card AND at 240×240 for a "watched
    // your reel" seen-bubble. With a URL-only key, whichever size decoded
    // (and cached) FIRST would silently get served back — wrong aspect
    // ratio / stretched or over-cropped — the next time that same URL hit
    // the pool at the other bubble's size. Local-file-path keys (already
    // unique per decoded file+size in practice, e.g. GIF/sticker/group
    // cells reading MediaCache's on-disk file) are left as-is; this helper
    // is for remote/derived URL keys where the same URL can recur at a
    // different override() size.
    private static String poolKey(String url, int w, int h) {
        return url + "@" + w + "x" + h;
    }

    // ── PERF: in-memory "already on disk" File cache ──────────────────────
    // MediaCache.getCached(url) does a real File.exists()+length() disk
    // stat() every time it's called — cheap once, but GIF/sticker/media-
    // group bind fired it unconditionally on EVERY bind (scroll-recycle,
    // and every rebind of a visible row that a new message elsewhere
    // triggers), same "unconditional work on every rebind" pattern as the
    // bitmap-decode flicker bugs above, just costing a syscall instead of
    // a blank frame. Once a URL resolves to a cached File, that answer is
    // permanently true for the life of the process (files are only ever
    // removed by an explicit "clear cache" action elsewhere, same trust
    // assumption DECODED_BITMAP_CACHE already makes) — so only the
    // positive (hit) result is cached; a not-yet-downloaded URL keeps
    // checking disk each bind exactly as before, which is correct since
    // that state can change at any time from a background download.
    private static final android.util.LruCache<String, java.io.File> CACHED_FILE_CHECK =
            new android.util.LruCache<>(300);

    private static java.io.File getCachedFileFast(android.content.Context ctx, String url) {
        if (url == null || url.isEmpty()) return null;
        java.io.File hit = CACHED_FILE_CHECK.get(url);
        if (hit != null) return hit; // trust — no repeat disk stat
        java.io.File f = com.callx.app.utils.MediaCache.getCached(ctx, url);
        if (f != null) CACHED_FILE_CHECK.put(url, f);
        return f;
    }

    // ── PERF #1: In-memory decoded-Bitmap pool ────────────────────────────
    // Independent of Glide's disk cache: stores the already-decoded Bitmap
    // objects in RAM so a scroll-back to a message never triggers a re-decode.
    // Keyed by URL (or local file path). Sized to 1/8 of the available heap.
    // LruCache is internally synchronized — safe to call from any thread.
    private static final android.util.LruCache<String, android.graphics.Bitmap> DECODED_BITMAP_CACHE;
    static {
        int maxMem = (int) (Runtime.getRuntime().maxMemory() / 1024); // KiB
        DECODED_BITMAP_CACHE = new android.util.LruCache<String, android.graphics.Bitmap>(maxMem / 8) {
            @Override
            protected int sizeOf(String key, android.graphics.Bitmap value) {
                return value.getByteCount() / 1024; // KiB
            }
        };
    }

    // ── DASHBOARD WIRING (Settings → Storage & Cache / CacheStatsActivity) ──
    // That screen only reads com.callx.app.cache.CacheManager's MemoryCache/
    // DiskCache — a completely separate tier from DECODED_BITMAP_CACHE and
    // Glide's own disk cache used everywhere above. Result: real thumbnail
    // traffic (reel-seen/status-seen bubble, image/video bubbles, reel-share
    // avatar+thumb, contact avatar) never showed up there — "0% hits" even
    // after 1000+ messages, because none of it was ever recorded. These two
    // helpers mirror the outcome into CacheManager so the dashboard reflects
    // reality. They do NOT change caching behavior — the local pool / Glide
    // disk cache remain the actual fast path; this is bookkeeping only.
    private static final java.util.concurrent.ExecutorService DASHBOARD_DISK_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    /** Call when an existing pool/cache hit is served, so MemoryCache's hit counter is honest. */
    private static void dashboardRecordHit(Context ctx, String key) {
        if (key == null || key.isEmpty()) return;
        com.callx.app.cache.CacheDashboardStats.getInstance(ctx).recordMemoryHit(key);
    }

    /** Call once a bitmap is freshly decoded, so Memory+Disk tiers both pick it up. */
    private static void dashboardRecordDecoded(Context ctx, String key, Bitmap bmp) {
        if (key == null || key.isEmpty() || bmp == null || bmp.isRecycled()) return;
        com.callx.app.cache.CacheManager cm = com.callx.app.cache.CacheManager.getInstance(ctx.getApplicationContext());
        cm.getMemoryCache().put("thumb_" + key, bmp);
        com.callx.app.cache.CacheDashboardStats.getInstance(ctx)
                .recordMemoryEntry(key, bmp.getByteCount());
        if (cm.getDiskCache().exists(key)) return; // already recorded once — don't re-encode every rebind
        // Compress synchronously here (bitmap is guaranteed non-recycled on this
        // callback thread); only the disk WRITE is pushed to a background thread.
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, bos);
        byte[] bytes = bos.toByteArray();
        DASHBOARD_DISK_EXECUTOR.execute(() -> {
            try { cm.getDiskCache().save(key, bytes); } catch (Exception ignored) {}
        });
    }

    // ── PERF #4: Density-aware thumbnail pixel size ───────────────────────
    // Replaces hard-coded override(480, 480) throughout.  MEDIA_MAX_WIDTH_DP
    // in MessageBubbleCanvasView is 260dp — on an xhdpi device (2×) that is
    // 520px, on xxhdpi (3×) 780px.  480px was UNDER-sampling on every modern
    // mid-range phone, wasting quality without saving much RAM.  We compute
    // the real pixel size once, adding a 10% upscale margin for the JPEG
    // chroma sub-sampling boundary, then cap at 80% of the screen width so
    // we don't load a massive bitmap for a narrow bubble on a tablet.
    // volatile write is safe: worst case two threads compute the same value.
    private static volatile int sThumbPx = 0;
    static int thumbPx(android.content.Context ctx) {
        if (sThumbPx > 0) return sThumbPx;
        android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int computed = (int) Math.min(
                260f * dm.density * 1.10f,          // 260dp + 10% margin
                dm.widthPixels * 0.80f);             // cap at 80% screen width
        sThumbPx = Math.max(computed, 320);          // never go below 320px
        return sThumbPx;
    }

    // PERF FIX: GIF/sticker bubbles render into a FIXED 180dp square
    // (MessageBubbleCanvasView.MEDIA_SIZE_DP — see bindGif()/bindSticker(),
    // both explicitly reuse the single-image 180dp slot). The Glide loads
    // below used to hardcode .override(720, 720) regardless of density —
    // on a 1x/1.5x/2x device that's 2-4x more pixels than the 180dp slot
    // will ever display, so every GIF/sticker paid for a needlessly large
    // decode (extra native heap + slower first-frame + more GC pressure
    // while scrolling past several GIF/sticker bubbles in a row).
    // gifStickerPx() targets the real 180dp slot with a 15% headroom
    // margin (covers minor upscale/anti-alias blur on hi-dpi screens)
    // instead of a fixed guess, same density-aware pattern as thumbPx().
    private static volatile int sGifStickerPx = 0;
    static int gifStickerPx(android.content.Context ctx) {
        if (sGifStickerPx > 0) return sGifStickerPx;
        android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int computed = (int) (180f * dm.density * 1.15f); // 180dp slot + 15% margin
        sGifStickerPx = Math.max(computed, 200);           // sane floor for very low density
        return sGifStickerPx;
    }
    // PERF: seen-bubble ("watched your reel" / "seen your status" system
    // row) avatar + thumbnail Glide loads used a hardcoded
    // .override(96, 96) / .override(240, 240) regardless of density — same
    // problem gifStickerPx()/thumbPx() above already fixed for other
    // bubbles. The avatar only ever renders at
    // MessageBubbleCanvasView.SEEN_AVATAR_SIZE_DP (36dp) and the thumbnail
    // at SEEN_THUMB_W_DP×SEEN_THUMB_H_DP (120×80dp, non-square) — decoding
    // a fixed 96px/240px square on every density wastes memory+time on
    // hi-dpi screens and under-decodes on low-dpi ones. Compute the real
    // pixel target once (with a small upscale margin) and cache it, same
    // density-aware pattern as thumbPx()/gifStickerPx().
    // NOTE: mirrors MessageBubbleCanvasView.SEEN_AVATAR_SIZE_DP (36f) /
    // SEEN_THUMB_W_DP (120f) / SEEN_THUMB_H_DP (80f) — those are
    // package-private to com.callx.app.conversation.canvas, so the literal
    // dp values are duplicated here rather than widening their visibility
    // just for this. Keep in sync if the seen-bubble layout constants change.
    private static final float SEEN_AVATAR_SIZE_DP_MIRROR = 36f;
    private static final float SEEN_THUMB_W_DP_MIRROR = 120f;
    private static final float SEEN_THUMB_H_DP_MIRROR = 80f;

    private static volatile int sSeenAvatarPx = 0;
    static int seenAvatarPx(android.content.Context ctx) {
        if (sSeenAvatarPx > 0) return sSeenAvatarPx;
        float density = ctx.getResources().getDisplayMetrics().density;
        int computed = (int) (SEEN_AVATAR_SIZE_DP_MIRROR * density * 1.15f); // 36dp slot + 15% margin
        sSeenAvatarPx = Math.max(computed, 48); // sane floor for very low density
        return sSeenAvatarPx;
    }

    private static volatile int sSeenThumbPxW = 0;
    private static volatile int sSeenThumbPxH = 0;
    static int seenThumbPxW(android.content.Context ctx) {
        if (sSeenThumbPxW == 0) seenThumbPx(ctx);
        return sSeenThumbPxW;
    }
    static int seenThumbPxH(android.content.Context ctx) {
        if (sSeenThumbPxH == 0) seenThumbPx(ctx);
        return sSeenThumbPxH;
    }
    private static void seenThumbPx(android.content.Context ctx) {
        float density = ctx.getResources().getDisplayMetrics().density;
        sSeenThumbPxW = Math.max((int) (SEEN_THUMB_W_DP_MIRROR * density * 1.15f), 100);
        sSeenThumbPxH = Math.max((int) (SEEN_THUMB_H_DP_MIRROR * density * 1.15f), 70);
    }

    // ── Payload key for presence-only updates (viewing-dot / reply-glow /
    //    playing-badge) — lets setViewingMessageIds() etc. refresh just
    //    those three views instead of re-running the entire bindMessage()
    //    (Glide reloads, Linkify, GradientDrawable alloc, CountDownTimer
    //    restart...) every time a presence broadcast comes in. See
    //    bindPresenceOnly() and the payload check in onBindViewHolder().
    static final String PAYLOAD_PRESENCE = "presence";
    /** Payload for a live upload-percentage tick — see onMediaUploadProgress(). */
    static final String PAYLOAD_MEDIA_PROGRESS = "media_progress";
    /** Live upload % per message id, for WhatsApp-style local-first media
     *  bubbles — see ChatMediaController#uploadAndSend()/MediaUploadProgressTracker. */
    private final MediaUploadProgressTracker uploadProgressTracker = new MediaUploadProgressTracker();

    // ── Local-first sent-media availability cache ────────────────────────
    // BUGFIX: LocalMediaAvailability.isAvailable() does a real ContentResolver
    // round-trip (openFileDescriptor) for content:// Uris — calling that
    // SYNCHRONOUSLY on every single onBindViewHolder() for every sent image
    // (including old ones scrolled past during initial chat load) blocked
    // the main thread just often enough to stall the live upload-percentage
    // ticks and the failed/tap-to-retry gate for whichever row happened to
    // be mid-upload at the same time. Now computed once per mediaLocalPath
    // on a background thread and cached; the bind uses the cached verdict
    // (defaulting to "not yet known" → falls back to fullUrl for that one
    // frame) and only refreshes the row once the real answer is in.
    private static final java.util.concurrent.ConcurrentHashMap<String, Boolean> LOCAL_AVAIL_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ExecutorService LOCAL_AVAIL_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final android.os.Handler LOCAL_AVAIL_MAIN_HANDLER =
            new android.os.Handler(android.os.Looper.getMainLooper());

    /**
     * Returns the cached local-availability verdict for this path, kicking
     * off a background check (and caching the result) if it isn't known
     * yet. Never blocks the calling (main) thread.
     */
    private Boolean checkLocalAvailabilityAsync(Context ctx, String mediaLocalPath, String messageId) {
        Boolean cached = LOCAL_AVAIL_CACHE.get(mediaLocalPath);
        if (cached != null) return cached;
        final Context appCtx = ctx.getApplicationContext();
        LOCAL_AVAIL_EXECUTOR.execute(() -> {
            boolean avail = com.callx.app.utils.LocalMediaAvailability.isAvailable(appCtx, mediaLocalPath);
            LOCAL_AVAIL_CACHE.put(mediaLocalPath, avail);
            if (avail && messageId != null) {
                // Refresh just this row once we know the local file is
                // usable — cheap full rebind, one-time per message.
                LOCAL_AVAIL_MAIN_HANDLER.post(() -> {
                    for (int i = 0; i < getItemCount(); i++) {
                        Message mm = getItem(i);
                        if (mm == null) continue;
                        String id = mm.messageId != null ? mm.messageId : mm.id;
                        if (messageId.equals(id)) {
                            notifyItemChanged(i);
                            break;
                        }
                    }
                });
            }
        });
        return null; // not known yet this frame — caller falls back to fullUrl
    }

    /**
     * Called by ChatMediaController on every upload progress tick for a
     * local-pending media bubble. Updates the tracker and, if that row is
     * currently bound, refreshes ONLY its spinner ring — no full rebind,
     * same PAYLOAD_PRESENCE-style fast path used elsewhere in this adapter.
     * @param percent 0-100, or -1 for indeterminate.
     */
    public void onMediaUploadProgress(String messageId, int percent) {
        if (messageId == null) return;
        uploadProgressTracker.setProgress(messageId, percent);
        for (int i = 0; i < getItemCount(); i++) {
            Message m = getItem(i);
            if (m == null) continue;
            String id = m.messageId != null ? m.messageId : m.id;
            if (messageId.equals(id)) {
                notifyItemChanged(i, PAYLOAD_MEDIA_PROGRESS);
                break;
            }
        }
    }

    /** Call once an upload finishes (success or failure) to stop tracking its progress. */
    public void onMediaUploadFinished(String messageId) {
        uploadProgressTracker.clear(messageId);
    }

    // ── Mention pattern — used for @Name blue highlight rendering ────────────
    private static final java.util.regex.Pattern MENTION_PATTERN =
            java.util.regex.Pattern.compile("@([\\w.]+)");
    private static final int MENTION_COLOR = 0xFF1DA1F2;

    // ── Search query — set by ChatSearchController; null = no active search ──
    private volatile String activeSearchQuery = null;

    /**
     * Set the current search query so that matching text in visible message
     * bubbles is highlighted with a yellow background.
     * Pass null to clear all highlights.
     */
    public void setSearchQuery(String query) {
        String norm = (query != null && !query.isEmpty()) ? query : null;
        if (java.util.Objects.equals(norm, activeSearchQuery)) return; // no-op, nothing to redraw
        activeSearchQuery = norm;
        // PERF: notifyItemRangeChanged(..., PAYLOAD_SEARCH) instead of
        // notifyDataSetChanged() — RecyclerView only actually invokes
        // onBindViewHolder(...,payloads) for holders currently attached
        // (the visible window + a small prefetch margin), so a keystroke
        // in the search box repaints a handful of on-screen bubbles, not
        // the whole chat, and doesn't reset scroll position or restart any
        // in-flight animations the way notifyDataSetChanged() would.
        notifyItemRangeChanged(0, getItemCount(), PAYLOAD_SEARCH);
    }

    // PERF: Linkify.addLinks() runs several regex passes (URL/phone/email)
    // over the full message text — a real cost on every onBindViewHolder,
    // paid again and again for the very common "scroll away, scroll back"
    // case where the same message gets rebound to a recycled holder
    // repeatedly. Cache the finished CharSequence (SpannableString with
    // link spans, or the plain String when there's no link) keyed by
    // messageId+text-hash, so a repeat bind is a HashMap lookup instead
    // of a fresh regex scan + allocation.
    private final Object precomputeCacheLock = new Object();
    private final java.util.LinkedHashMap<String, CharSequence> linkifiedTextCache =
            new java.util.LinkedHashMap<String, CharSequence>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        java.util.Map.Entry<String, CharSequence> eldest) {
                    return size() > 120;
                }
            };

    // WHATSAPP-STYLE HEIGHT CACHE: LRU cache of measured message row heights
    // keyed by messageId. When a new message is bound, if we've previously
    // measured a similar message (same sender type, rough text length), reuse
    // that height. This prevents the RecyclerView from re-measuring and
    // re-laying-out the entire list when a new message arrives at the bottom.
    // The height is cached in onViewRecycled() after the view has been
    // laid out once, so it's accurate.
    private final java.util.LinkedHashMap<String, Integer> messagHeightCache =
            new java.util.LinkedHashMap<String, Integer>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        java.util.Map.Entry<String, Integer> eldest) {
                    return size() > 64; // Keep only recent 64 messages' heights
                }
            };

    // TELEGRAM-STYLE OBJECT CACHE: Lightweight message cache to avoid
    // re-processing during scroll. Keyed by messageId, stores the last-seen
    // Message object. Speeds up repeated binds for off-screen items
    // during rapid scroll-back operations.
    private final java.util.LinkedHashMap<String, Message> messageObjectCache =
            new java.util.LinkedHashMap<String, Message>(48, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        java.util.Map.Entry<String, Message> eldest) {
                    return size() > 128; // Keep last 128 messages in memory
                }
            };

    // BUG FIX (v45-4): PrecomputedTextCompat (sync AND async) removed
    // entirely — it was the root cause of the "cold-open bubble sometimes
    // full, sometimes partial" bug. It worked by setting the plain text
    // first, then silently swapping tv_message's content a second time
    // with a separately-built layout. Any time that second layout
    // resolved a different line count than the first plain setText() did
    // — which could happen whenever the swap ran before the TextView's
    // width was 100% final, not only in the old async-after-400ms branch
    // — the bubble's measured height and its actually-drawn content
    // disagreed, and with stackFromEnd + itemAnimator(null) that
    // disagreement doesn't always trigger a re-anchor, so the bubble can
    // end up only partially inside the viewport ("thoda sa dikhta tha").
    // Now there is exactly ONE setText() call per bind — no swap, so no
    // possible mismatch, ever.
    public volatile boolean asyncTextEnabled = false;

    // WHATSAPP-STYLE FOOTER RESERVE — invisible trailing span appended to
    // every text-bubble's message text so the time+tick footer (drawn as
    // a FrameLayout-overlay sibling, not a real second row) never paints
    // on top of the last line of text.
    //
    // BUG FIXED: nothing reserved real room for the footer — tv_message
    // only had paddingEnd=6dp/paddingBottom=2dp, nowhere near the ~40-50dp
    // the time+tick actually occupy. Long/wrapped messages "looked" fine
    // purely by luck (their last line rarely reaches the right edge), but
    // any SHORT single-line message had the footer sit directly on top of
    // its only line of text.
    //
    // This ReplacementSpan draws nothing — it just tells the line-breaker
    // "reserve this many px of blank space here," exactly like WhatsApp's
    // own trailing-space trick. If the real last line + reserve doesn't
    // fit the bubble's max width, it naturally wraps to its own line,
    // which is also correct (footer then sits under a blank reserve run).
    private static final class FooterReserveSpan extends android.text.style.ReplacementSpan {
        private final int widthPx;
        FooterReserveSpan(int widthPx) { this.widthPx = Math.max(0, widthPx); }
        @Override
        public int getSize(@NonNull android.graphics.Paint paint, CharSequence text, int start, int end,
                            android.graphics.Paint.FontMetricsInt fm) {
            return widthPx;
        }
        @Override
        public void draw(@NonNull android.graphics.Canvas canvas, CharSequence text, int start, int end,
                          float x, int top, int y, int bottom, @NonNull android.graphics.Paint paint) {
            // Intentionally blank — reserves horizontal space only.
        }
    }

    /**
     * Approximates how many px of blank space the ll_msg_footer (time +
     * tick + edited-pencil, sent messages also get the tick glyph) will
     * actually occupy, so we can reserve exactly that much room on the
     * message text's last line.
     *
     * Derived straight from the Message model rather than from the footer
     * views themselves, since the tick/expiry views are bound to their
     * final state AFTER the text bubble is bound in bindMessage() — using
     * their current (possibly stale/leftover-from-recycling) state here
     * would be unreliable.
     */
    private int computeFooterReservePx(VH h, Message m, boolean isSentMsg, String timeStr) {
        float density = h.itemView.getResources().getDisplayMetrics().density;
        float w = 0f;
        if (h.tvTime != null && timeStr != null) {
            w += h.tvTime.getPaint().measureText(timeStr);
        }
        if (isSentMsg) {
            // Widest tick glyph is the double-check — reserve for it
            // regardless of actual current status so re-binds on status
            // change (sent -> delivered -> read) never need a different
            // reserve width.
            w += 16f * density;  // glyph
            w += 2f * density;   // tv_status marginStart
        }
        long expiresAt = m.expiresAt != null ? m.expiresAt : 0L;
        if (expiresAt > 0 && expiresAt - System.currentTimeMillis() > 0) {
            w += 34f * density;  // worst-case countdown text width
            w += 2f * density;
        }
        w += 8f * density; // ll_msg_footer's own marginEnd + a small safety buffer
        return (int) Math.ceil(w);
    }

    /** Wraps {@code base} with an invisible trailing {@link FooterReserveSpan}. */
    private static CharSequence appendFooterReserve(CharSequence base, int reservePx) {
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(base);
        sb.append(' ');
        int start = sb.length();
        sb.append('\u00A0');
        sb.setSpan(new FooterReserveSpan(reservePx), start, sb.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    // ── Fields ────────────────────────────────────────────────────
    private final String currentUid;
    private final boolean isGroup;
    /** Set by the Activity right after construction (setChatId). Threaded
     *  through to MediaViewerActivity (video) so it can publish playback
     *  presence on chatPlayback/{chatId}/{uid} — audio playback presence
     *  doesn't need this since it goes through ActionListener instead,
     *  but video opens a separate full-screen Activity. */
    private String chatId;
    private final SimpleDateFormat timeFmt =
            new SimpleDateFormat("hh:mm a", Locale.getDefault());
    private final SimpleDateFormat dateLabelFmt =
            new SimpleDateFormat("d MMM yyyy", Locale.getDefault());

    // ── PERF: timestamp → formatted string cache (LongSparseArray, 256 cap) ──
    // Messages in the same minute share a key → very high hit rate.
    // reuseDate avoids Date allocation on every format call.
    // PERF: android.util.LongSparseArray instead of LruCache<Long,String> —
    // LruCache's key type is Long, so every get()/put() autoboxes the primitive
    // `key` into a new Long (or hits the [-128,127] cache, which timestamp
    // buckets almost never fall into). LongSparseArray stores primitive longs
    // directly in a backing array, so lookups on a scroll-heavy screen no
    // longer allocate a Long per row. Not truly LRU any more — capped by
    // clearing the whole array once it outgrows the old LruCache capacity,
    // which is fine for a cache (worst case is a few extra recomputes right
    // after the clear, never incorrect data).
    private final android.util.LongSparseArray<String> timeStringCache =
            new android.util.LongSparseArray<>(256);
    private final java.util.Date reuseDate = new java.util.Date();

    private String formatTime(long ts) {
        long key = (ts / 60_000L) * 60_000L;
        String s = timeStringCache.get(key);
        if (s != null) return s;
        reuseDate.setTime(ts);
        s = timeFmt.format(reuseDate);
        if (timeStringCache.size() >= 256) timeStringCache.clear();
        timeStringCache.put(key, s);
        return s;
    }

    // ── PERF: view-once timestamp cache ("h:mm a" — no leading zero) ─────────
    // bindViewOnceSentWaiting()/bindViewOnceExpired()/openedAt label used to
    // `new SimpleDateFormat("h:mm a", ...).format(new Date(ts))` fresh on every
    // single onBindViewHolder() — a SimpleDateFormat construction (locale/
    // pattern parsing) plus a Date allocation, every rebind. View-once rows
    // are less common than text bubbles but still rebind on every list
    // update (tick changes, payload updates), same as everything else.
    // Separate cache/field from formatTime() above because the pattern is
    // "h:mm a" (no leading zero) here vs "hh:mm a" there — kept distinct so
    // the displayed text is byte-identical to before this change.
    private final SimpleDateFormat viewOnceTimeFmt =
            new SimpleDateFormat("h:mm a", Locale.getDefault());
    // PERF: cached formatter for the "different year" date-label branch —
    // was `new SimpleDateFormat("d MMM yyyy", ...)` built fresh every single
    // bind for any message not from today/yesterday/this-year.
    private final SimpleDateFormat dateLabelOtherYearFmt =
            new SimpleDateFormat("d MMM yyyy", Locale.getDefault());
    private final android.util.LongSparseArray<String> viewOnceTimeCache =
            new android.util.LongSparseArray<>(64);
    private final java.util.Date reuseDateViewOnce = new java.util.Date();

    private String formatViewOnceTime(long ts) {
        long key = (ts / 60_000L) * 60_000L;
        String s = viewOnceTimeCache.get(key);
        if (s != null) return s;
        reuseDateViewOnce.setTime(ts);
        s = viewOnceTimeFmt.format(reuseDateViewOnce);
        if (viewOnceTimeCache.size() >= 64) viewOnceTimeCache.clear();
        viewOnceTimeCache.put(key, s);
        return s;
    }

    // ── PERF: date-label cache — "Today"/"Yesterday"/"3 Jan" per day ─────────
    // Keys are midnight-truncated timestamps. Recomputed once per day per key.
    // PERF: LongSparseArray, same autoboxing rationale as timeStringCache.
    private final android.util.LongSparseArray<String> dateLabelCache =
            new android.util.LongSparseArray<>(64);

    // ── PERF: isSameDay result cache — keyed by (ts1/day, ts2/day) ───────────
    // Called on every row to decide whether to show the date separator.
    // PERF: LongSparseArray<Boolean> instead of HashMap<Long,Boolean> — the
    // combined cacheKey is a primitive long, so HashMap.get/put were boxing
    // it on every call (Boolean values are still boxed, but TRUE/FALSE are
    // JVM-cached singletons, so that side was already free). Cache size
    // capped — at most one entry per unique pair in a visible window
    // (~20 rows = ~20 unique pairs at most); cleared wholesale if it ever
    // grows past that in one long-lived adapter instance.
    private final android.util.LongSparseArray<Boolean> sameDayCache =
            new android.util.LongSparseArray<>(32);

    // ── PERF FIX #3: reel-share avatar/thumb in-memory cache ─────────────────
    // Root cause: bindReelShareBubble fired a fresh Firebase "users" query
    // (by username) on EVERY bind where m.reelShareOwnerPhoto was empty —
    // and Room-loaded Message objects don't carry the previous fetch result
    // forward, so scrolling past the same reel-share message (or several
    // messages sharing the same reel owner) re-triggered the network query
    // each time it came back on screen. Static (process-wide) LruCache keyed
    // by username/reelId means the query fires at most once per key for the
    // lifetime of the app process; every other bind is a cache hit with zero
    // network calls. Static (not instance) so the cache survives adapter
    // recreation (e.g. chat re-opened) and is shared across chats.
    private static final android.util.LruCache<String, String> reelOwnerAvatarCache =
            new android.util.LruCache<>(128);
    private static final android.util.LruCache<String, String> reelThumbCache =
            new android.util.LruCache<>(128);
    // In-flight guards: prevent duplicate concurrent Firebase queries for the
    // same key when multiple rows for the same username/reelId bind in the
    // same fling before the first query returns.
    private static final java.util.Set<String> reelAvatarFetchInFlight =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    private static final java.util.Set<String> reelThumbFetchInFlight =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    // ── PERF: static Typeface — Typeface.create() is measured at ~0.5–2ms ────
    private static final android.graphics.Typeface TF_NORMAL =
            android.graphics.Typeface.create(
                    android.graphics.Typeface.SANS_SERIF,
                    android.graphics.Typeface.NORMAL);

    private ActionListener actionListener;
    private MediaPlayer player;
    private int playingPos = -1;
    // PERF: mirrors player.isPlaying() without the MediaPlayer binder/JNI
    // round-trip. player.isPlaying() used to be called from bindMessage()
    // (fires on every RecyclerView bind, i.e. every scroll frame that
    // recycles a voice bubble into view) AND from the 250ms seek-progress
    // Runnable — both are hot paths where an IPC call into the media
    // server on every tick/bind is pure waste. This flag is set at every
    // point that actually changes play state (start/pause/stop/complete/
    // error) and is what those hot paths read instead.
    private volatile boolean isPlayerPlaying = false;
    // FIX [P3-1]: Track the ViewHolder that is currently playing so we can
    // reset its UI (icon + seekbar) when a different message starts playing.
    private VH playingVH = null;

    private static String midOf(@Nullable Message m) {
        if (m == null) return null;
        return m.messageId != null ? m.messageId : m.id;
    }

    /**
     * Mirrors GlobalVoicePlaybackManager's stop back onto this adapter when
     * playback was stopped from OUTSIDE this chat screen (mini player ✕ in
     * MainActivity, or the clip finishing while backgrounded) yet this
     * adapter is somehow still attached — resets the bubble UI without
     * touching the MediaPlayer again (it's already released by the manager).
     */
    private final GlobalVoicePlaybackManager.Listener globalPlaybackListener =
            new GlobalVoicePlaybackManager.Listener() {
        @Override public void onPlaybackStarted(String messageId, String chatId, String partnerUid,
                                                  String displayName, String avatarUrl, boolean outgoing) { }
        @Override public void onPlaybackToggled(String messageId, boolean playing) { }
        @Override public void onPlaybackStopped(String messageId) {
            if (playingVH != null && messageId != null && messageId.equals(midOf(getItem(playingPos)))) {
                seekHandler.removeCallbacks(seekUpdater);
                resetAudioUi(playingVH);
            }
            isPlayerPlaying = false;
            player = null;
            playingVH = null;
            playingPos = -1;
        }
    };
    // Feature 4: Voice speed. Resets to 1.0f each time a new audio starts.
    private float currentPlaybackSpeed = 1.0f;

    // Feature: Voice Caption on Photo — messageIds whose attached voice
    // note has already auto-played once for the receiver in this adapter's
    // lifetime (walkie-talkie style — see bindMessage's "image" case).
    // Plain in-memory set is enough: it only needs to survive scroll/rebind
    // within a single chat-screen session, not across app restarts.
    private final java.util.Set<String> autoPlayedVoiceOnImage = new java.util.HashSet<>();

    // Feature 3: Spoiler — per-message map of revealed span-start indices.
    // Keyed by messageId so reveal state persists across recycler reuse.
    // When user taps a spoiler span, its startIndex is added here and the
    // adapter rebinds that position (notifyItemChanged) to re-draw revealed.
    private final java.util.Map<String, java.util.Set<Integer>> revealedSpoilers
            = new java.util.HashMap<>();
    // FIX: SeekBar progress update via Handler — 250ms interval during playback
    private final android.os.Handler seekHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable seekUpdater;

    // ── Interface for long-press actions ─────────────────────────
    public interface ActionListener {
        void onReply(Message m);
        void onNavigateToOriginal(String messageId);
        /**
         * BUG FIX: status-reply/reaction quote boxes (replyToId = "status_"+id)
         * need to know WHO SENT this chat message to figure out who actually
         * owns the status — it is NOT always the chat partner. If I sent this
         * message (I replied/reacted to partner's status), partner owns the
         * status. If the PARTNER sent this message (they replied/reacted to
         * MY status), I own the status. The single-arg overload above has no
         * way to tell these apart, so it always assumed "partner owns it" —
         * correct only for the first case, wrong for the second, which made
         * tapping a reply/reaction to your OWN status show "This status is no
         * longer available". Default implementation falls back to the old
         * (partner-always-owns-it) behavior for callers that don't override it.
         */
        default void onNavigateToOriginal(String messageId, String senderId) {
            onNavigateToOriginal(messageId);
        }
        void onDelete(Message m);
        void onReact(Message m, String emoji);
        void onStar(Message m);
        void onCopy(Message m);
        void onForward(Message m);
        /** Called when user taps the ⚠ failed-status icon to retry sending. */
        default void onRetry(Message m) {}
        /** Called when user chooses Edit from the action sheet (own messages only). */
        default void onEdit(Message m) {}
        /** Called when user taps the "✏️ edited" tag on a bubble's timestamp
         *  to view every prior version of the message text. */
        default void onShowEditHistory(Message m) {}
        /** Called when user pins or unpins a message from the action sheet. */
        default void onPin(Message m) {}
        /** Called when user taps a poll option to cast/change their vote. */
        default void onPollVote(Message m, int optionIndex) {}
        /** Called when user taps a message's existing reactions row (the small
         *  "❤️2 👍1" chip under a bubble) to see who reacted with what. */
        default void onReactionTap(Message m) {}
        /** Called when poll creator chooses Close/Reopen poll from the action sheet. */
        default void onPollToggleClose(Message m) {}
        /** Called whenever OUR OWN audio/video playback for this message
         *  starts or stops (play, pause, finish, error, or switching to a
         *  different bubble) — lets ChatPlaybackPresenceController publish
         *  a real-time "listening…/watching…" badge to the partner. */
        default void onPlaybackStateChanged(Message m, boolean playing) {}
        /** Called when user taps "Info" on a message to see delivery/read receipts. */
        default void onInfo(Message m) {}
        /**
         * Feature 2: Called when the user taps "Save" or "Unsave" in the
         * long-press action sheet. The implementor should toggle the
         * SavedMessageEntity in Room and show a snackbar.
         * @param m        The message to save/unsave.
         * @param save     true = save, false = unsave.
         */
        default void onSaveMessage(Message m, boolean save) {}
        /** Called when user chooses "Translate" from the long-press action sheet
         *  on a text message. Implementor should call the translate API and
         *  show the result (e.g. a small dialog/snackbar with original + translated text). */
        default void onTranslate(Message m) {}
    }

    // ── Multi-select interface ────────────────────────────────────
    public interface MultiSelectListener {
        void onSelectionChanged(int count);
    }

    // ── Multi-select state ────────────────────────────────────────
    private boolean multiSelectMode = false;
    private final java.util.Set<String> selectedMessageIds = new java.util.HashSet<>();

    // ── "Currently viewing this message" live dots ──────────────────────
    // Set of messageIds that some OTHER participant currently has scrolled
    // into view, fed by ChatPresenceController / GroupWatchingController
    // listening on chatViewing/{chatOrGroupId}/*. Purely additive — never
    // touches tv_status (delivered/read ticks).
    private java.util.Set<String> currentlyViewedMessageIds = java.util.Collections.emptySet();

    /** Replaces the set of "being viewed right now" message ids and
     *  refreshes only the rows whose dot state actually changed. */
    public void setViewingMessageIds(java.util.Set<String> newIds) {
        if (newIds == null) newIds = java.util.Collections.emptySet();
        java.util.Set<String> old = currentlyViewedMessageIds;
        if (old.equals(newIds)) return;
        java.util.Set<String> changed = new java.util.HashSet<>(old);
        changed.addAll(newIds);
        currentlyViewedMessageIds = newIds;
        for (int i = 0; i < getItemCount(); i++) {
            Message m = getItem(i);
            if (m == null) continue;
            String id = m.messageId != null ? m.messageId : m.id;
            // PERF: payload-only refresh — see PAYLOAD_PRESENCE. Avoids
            // re-running the full bindMessage() (Glide reloads, Linkify,
            // bubble redraw, countdown restart) just to flip a dot.
            if (id != null && changed.contains(id)) notifyItemChanged(i, PAYLOAD_PRESENCE);
        }
    }

    // ── "Someone is currently composing a reply to this message" glow ──────
    // Sibling set to currentlyViewedMessageIds, but scoped to whatever
    // bubble a participant has open in the reply bar AND is actively typing
    // into right now (not just "scrolled into view"). Fed by
    // ChatPresenceController / GroupChatActivity listening on
    // chatTypingReply/{chatOrGroupId}/{uid} = messageId | null.
    private java.util.Set<String> replyTargetMessageIds = java.util.Collections.emptySet();

    /** Replaces the set of "being replied to right now" message ids and
     *  refreshes only the rows whose highlight state actually changed. */
    public void setReplyTargetMessageIds(java.util.Set<String> newIds) {
        if (newIds == null) newIds = java.util.Collections.emptySet();
        java.util.Set<String> old = replyTargetMessageIds;
        if (old.equals(newIds)) return;
        java.util.Set<String> changed = new java.util.HashSet<>(old);
        changed.addAll(newIds);
        replyTargetMessageIds = newIds;
        for (int i = 0; i < getItemCount(); i++) {
            Message m = getItem(i);
            if (m == null) continue;
            String id = m.messageId != null ? m.messageId : m.id;
            // PERF: payload-only refresh — see PAYLOAD_PRESENCE.
            if (id != null && changed.contains(id)) notifyItemChanged(i, PAYLOAD_PRESENCE);
        }
    }

    // ── "Someone is currently playing this voice note / video" badge ───────
    // Sibling of currentlyViewedMessageIds, but for actual audio/video
    // PLAYBACK rather than scroll position. Fed by ChatPlaybackPresenceController
    // listening on chatPlayback/{chatOrGroupId}/{uid} = messageId | null.
    private java.util.Set<String> currentlyPlayingMessageIds = java.util.Collections.emptySet();

    /** Replaces the set of "being played right now" message ids and
     *  refreshes only the rows whose badge state actually changed. */
    public void setPlayingMessageIds(java.util.Set<String> newIds) {
        if (newIds == null) newIds = java.util.Collections.emptySet();
        java.util.Set<String> old = currentlyPlayingMessageIds;
        if (old.equals(newIds)) return;
        java.util.Set<String> changed = new java.util.HashSet<>(old);
        changed.addAll(newIds);
        currentlyPlayingMessageIds = newIds;
        for (int i = 0; i < getItemCount(); i++) {
            Message m = getItem(i);
            if (m == null) continue;
            String id = m.messageId != null ? m.messageId : m.id;
            // PERF: payload-only refresh — see PAYLOAD_PRESENCE.
            if (id != null && changed.contains(id)) notifyItemChanged(i, PAYLOAD_PRESENCE);
        }
    }
    private MultiSelectListener multiSelectListener;

    public void setMultiSelectListener(MultiSelectListener l) { this.multiSelectListener = l; }
    public ActionListener getActionListener() { return actionListener; }

    /** Called once by the Activity right after construction — threads chatId
     *  through to the video tap-to-view Intent so MediaViewerActivity can
     *  publish playback presence (see class-level field doc above). */
    public void setChatId(String chatId) { this.chatId = chatId; }

    /** The OTHER party's uid in this 1:1 chat — set once by ChatActivity right
     *  after construction. Needed because a voice-note Message's senderId is
     *  the current user's own uid for outgoing clips, so it can't be used to
     *  identify "who to reopen" from GlobalVoicePlaybackManager's mini player. */
    private String partnerUid;
    public void setPartnerUid(String partnerUid) { this.partnerUid = partnerUid; }

    /**
     * Finds a Message in the currently loaded paging snapshot by id.
     * Used by ChatActivity/GroupChatActivity to resolve the message that
     * GalleryReplyBridge points at after a swipe-up-to-reply gesture in
     * MediaViewerActivity's grouped-media gallery. Returns null if the
     * message has scrolled out of the loaded window or id is null.
     */
    public Message findMessageById(String id) {
        if (id == null || id.isEmpty()) return null;
        androidx.paging.ItemSnapshotList<Message> snapshot = snapshot();
        for (Message m : snapshot) {
            if (m == null) continue;
            if (id.equals(m.id) || id.equals(m.messageId)) return m;
        }
        return null;
    }

    public void enterMultiSelectMode(Message firstMessage) {
        multiSelectMode = true;
        selectedMessageIds.clear();
        String id = firstMessage != null ? firstMessage.messageId : null;
        if (id == null && firstMessage != null) id = firstMessage.id;
        if (id != null) selectedMessageIds.add(id);
        // PERF ADV: was notifyItemRangeChanged(0, getItemCount()) — touches
        // EVERY row in the whole chat just to dim the ones that aren't
        // selected, even though only the on-screen rows are visibly
        // affected. On a chat with thousands of messages that's a huge
        // rebind burst for a single long-press. notifyVisibleRangeChanged()
        // limits this to what's actually on screen (+ a small buffer for
        // prefetched/cached rows just off-screen).
        notifyVisibleRangeChanged();
        if (multiSelectListener != null) multiSelectListener.onSelectionChanged(selectedMessageIds.size());
    }

    public void exitMultiSelectMode() {
        multiSelectMode = false;
        selectedMessageIds.clear();
        // PERF ADV: see enterMultiSelectMode() above — same visible-range-only fix.
        notifyVisibleRangeChanged();
        if (multiSelectListener != null) multiSelectListener.onSelectionChanged(0);
    }

    /**
     * Notifies only the currently visible rows (plus a small buffer for
     * rows RecyclerView has already prefetched/cached just off-screen), not
     * the entire list — used when toggling multi-select mode, where every
     * row's selection-highlight state needs to be repainted but only the
     * ones actually on screen (or about to be) are visible to the user.
     * Falls back to the old full-range notify if the RecyclerView isn't
     * attached yet or isn't using a LinearLayoutManager (defensive — this
     * chat always uses one in practice).
     */
    private void notifyVisibleRangeChanged() {
        androidx.recyclerview.widget.RecyclerView.LayoutManager lm =
                attachedRecyclerView != null ? attachedRecyclerView.getLayoutManager() : null;
        if (lm instanceof androidx.recyclerview.widget.LinearLayoutManager) {
            androidx.recyclerview.widget.LinearLayoutManager llm =
                    (androidx.recyclerview.widget.LinearLayoutManager) lm;
            int first = llm.findFirstVisibleItemPosition();
            int last = llm.findLastVisibleItemPosition();
            if (first != androidx.recyclerview.widget.RecyclerView.NO_POSITION
                    && last != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                int bufferRows = 8; // covers RecyclerView's default prefetch/cache window
                int start = Math.max(0, first - bufferRows);
                int end = Math.min(getItemCount() - 1, last + bufferRows);
                notifyItemRangeChanged(start, end - start + 1);
                return;
            }
        }
        // Defensive fallback — layout not measured yet, or a different
        // LayoutManager type. Correct either way, just not the optimized path.
        notifyItemRangeChanged(0, getItemCount());
    }

    public boolean isInMultiSelectMode() { return multiSelectMode; }

    public java.util.List<Message> getSelectedMessages() {
        java.util.List<Message> result = new java.util.ArrayList<>();
        for (int i = 0; i < getItemCount(); i++) {
            Message m = getItem(i);
            if (m == null) continue;
            // BUG FIX: synthetic rows (date_separator etc.) live in the same
            // position-indexed list as real messages but were never meant to
            // be selectable — they carry no senderId/timestamp/status, so if
            // one ever slips into selectedMessageIds (e.g. a stale id from a
            // list reshuffle right after a fast send), the caller (Message
            // Info) ends up rendering a near-blank Message as if it were a
            // real received message ("Sent" fallback, no Seen/Delivered
            // rows) instead of the outgoing message the user actually
            // selected. Filter these out defensively at the source.
            if ("date_separator".equals(m.type) || "security_event".equals(m.type)) continue;
            String id = m.messageId != null ? m.messageId : m.id;
            if (id != null && selectedMessageIds.contains(id)) result.add(m);
        }
        return result;
    }

    private static final int TAG_KEY_SELECTED = 0x7F_0B_0001;

    private void applySelectionHighlight(VH h, Message m) {
        String id = m.messageId != null ? m.messageId : m.id;
        boolean selected = id != null && selectedMessageIds.contains(id);
        if (multiSelectMode) {
            h.itemView.setAlpha(selected ? 1.0f : 0.55f);
            Boolean was = (Boolean) h.itemView.getTag(TAG_KEY_SELECTED);
            if (was == null || was != selected) {
                // PERF/OVERDRAW: setBackground(null) instead of a TRANSPARENT
                // ColorDrawable — a transparent color still queues a full-row
                // draw/blend pass (shows up as +1 layer in GPU overdraw debug),
                // whereas null background is skipped by View#draw() entirely.
                // Every row is "not selected" almost all the time, so this was
                // a wasted paint pass on nearly every bind during scroll.
                if (selected) {
                    h.itemView.setBackgroundColor(0x336200EE);
                } else {
                    h.itemView.setBackground(null);
                }
                h.itemView.setTag(TAG_KEY_SELECTED, selected);
            }
        } else {
            if (h.itemView.isActivated() || h.itemView.getTag(TAG_KEY_SELECTED) != null) {
                h.itemView.setAlpha(1.0f);
                h.itemView.setBackground(null);
                h.itemView.setTag(TAG_KEY_SELECTED, null);
            }
        }
    }

    public MessagePagingAdapter(String currentUid, boolean isGroup) {
        super(DIFF);
        this.currentUid = currentUid;
        this.isGroup    = isGroup;
        // NOTE: setHasStableIds(true) + custom getItemId() was attempted here
        // but PagingDataAdapter.getItemId(int) is FINAL — it already manages
        // item identity internally via the DiffUtil.ItemCallback (DIFF above
        // handles areItemsTheSame() by messageId), so a custom getItemId()
        // isn't possible and isn't needed: DiffUtil already gives correct
        // identity tracking across submitData() calls, including the
        // warm-cache-list → real-Paging-list transition.
    }

    // PERF: one cached RequestManager for the RecyclerView's whole attached
    // lifetime instead of calling glide(ctx) on every single bind().
    // Glide.with() isn't free — it walks up to the right Activity/Fragment,
    // finds-or-creates a lifecycle-aware RequestManager, and registers a
    // support-fragment observer the first time. With ~24 call sites across
    // onBindViewHolder for a chat screen that rebinds constantly during
    // scroll, that lookup was repeating on every bind of every media bubble.
    // Grabbing it once here (same Activity context either way — the
    // RecyclerView and every itemView in it share the same Activity) and
    // reusing it is the standard Glide "attach a manager, don't re-fetch it"
    // pattern, and still fully respects Activity lifecycle (pause/resume/
    // clear on destroy) since it's the same underlying RequestManager Glide
    // would have handed back anyway.
    private com.bumptech.glide.RequestManager glideRequestManager;
    // PERF ADV: needed so enterMultiSelectMode()/exitMultiSelectMode() can
    // limit their notify to the actually-visible range instead of the whole
    // list — see attachedRecyclerView usage below.
    private RecyclerView attachedRecyclerView;

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onAttachedToRecyclerView(recyclerView);
        glideRequestManager = com.bumptech.glide.Glide.with(recyclerView.getContext());
        attachedRecyclerView = recyclerView;
        GlobalVoicePlaybackManager.getInstance().addListener(globalPlaybackListener);
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onDetachedFromRecyclerView(recyclerView);
        glideRequestManager = null;
        attachedRecyclerView = null;
        GlobalVoicePlaybackManager.getInstance().removeListener(globalPlaybackListener);

        // WhatsApp-style persistent mini player: if a voice note is still
        // mid-playback when this chat screen goes away, do NOT stop/release
        // it — just drop OUR reference. GlobalVoicePlaybackManager already
        // holds its own strong reference to this exact MediaPlayer (set in
        // playAudioFromPath's notifyStarted call), so the audio keeps
        // playing and stays controllable from MainActivity's mini player.
        if (player != null && playingVH != null) {
            GlobalVoicePlaybackManager.getInstance().onChatScreenLeftWhilePlaying();
        } else if (player != null) {
            // Paused / idle player left behind — nothing to hand off, safe to release.
            try { player.release(); } catch (Exception ignored) {}
        }
        player = null;
        playingVH = null;
        playingPos = -1;
        seekHandler.removeCallbacks(seekUpdater);
    }

    /**
     * Renders a resolved link-preview Result onto the Canvas bubble —
     * title/domain card plus thumbnail (cache-hit-synchronous or
     * Glide-async as needed). Shared by both the synchronous cache-peek
     * path and the async fetch() callback in bindText() above, so a
     * cached preview and a freshly-fetched one render identically.
     */
    private void bindLinkPreviewResult(
            com.callx.app.conversation.canvas.MessageBubbleCanvasView cv,
            android.content.Context ctx,
            String previewUrl,
            com.callx.app.utils.LinkPreviewFetcher.Result r) {
        boolean hasThumb = r.imageUrl != null && !r.imageUrl.isEmpty();
        cv.setLinkPreview(r.url, r.title, r.domain, hasThumb);
        if (!hasThumb) return;
        // PERF #4: density-aware width; keep 2:1 aspect for link-preview card.
        // Check the shared decoded-bitmap pool synchronously first — a hit
        // renders the thumb in the very same frame as the title/domain text,
        // zero flash. Only a genuine miss falls back to async Glide.
        final int lpW = thumbPx(ctx), lpH = thumbPx(ctx) / 2;
        android.graphics.Bitmap lpHit = DECODED_BITMAP_CACHE.get(poolKey(r.imageUrl, lpW, lpH));
        if (lpHit != null && !lpHit.isRecycled()) {
            cv.setLinkPreviewThumbBitmap(lpHit);
            return;
        }
        glide(ctx).asBitmap().load(r.imageUrl).apply(THUMB_RGB565)
                .override(lpW, lpH).centerCrop()
                .into(new com.bumptech.glide.request.target.CustomTarget<Bitmap>() {
                    @Override public void onResourceReady(@NonNull Bitmap resource,
                            @Nullable com.bumptech.glide.request.transition.Transition<? super Bitmap> transition) {
                        DECODED_BITMAP_CACHE.put(poolKey(r.imageUrl, lpW, lpH), resource);
                        if (!previewUrl.equals(cv.getTag())) return;
                        cv.setLinkPreviewThumbBitmap(resource);
                    }
                    @Override public void onLoadCleared(@Nullable android.graphics.drawable.Drawable placeholder) {
                        if (!previewUrl.equals(cv.getTag())) return;
                        cv.setLinkPreviewThumbBitmap(null);
                    }
                });
    }

    /** Cached RequestManager if attached; falls back to glide(ctx) so callers never null-check. */
    private com.bumptech.glide.RequestManager glide(Context ctx) {
        return glideRequestManager != null ? glideRequestManager : com.bumptech.glide.Glide.with(ctx);
    }


    public void setActionListener(ActionListener l) {
        this.actionListener = l;
    }

    // ──────────────────────────────────────────────────────────────
    @Override
    public int getItemViewType(int position) {
        Message m = getItem(position);
        if (m == null) return TYPE_RECEIVED;
        // "security_event" (E2EE security-code-change notice) reuses the
        // same standalone pill-chip rendering as date separators — see
        // ChatMessageSender#insertSecurityEventIfPending. Both are
        // synthetic/local-only rows, never real messages.
        if ("date_separator".equals(m.type) || "security_event".equals(m.type)) return TYPE_DATE_SEPARATOR;
        // status_seen / reel_seen — now rendered on Canvas (always the
        // "received" shape, left-aligned) instead of item_status_seen_
        // bubble.xml / item_reel_seen_bubble.xml. TYPE_STATUS_SEEN/
        // TYPE_REEL_SEEN + their legacy bind*Bubble() methods are kept
        // only as an unused fallback.
        if ("status_seen".equals(m.type)) {
            // BUG FIX: this used to unconditionally return TYPE_CANVAS_RECEIVED,
            // so the "seen your status" bubble rendered on BOTH sides of the
            // chat — the viewer who watched the status also saw it in their
            // own chat, not just the status owner. Mirrors the reel_seen fix
            // right below: only the status OWNER should ever see this bubble.
            String statusOwnerUid = (m.statusOwnerUid != null && !m.statusOwnerUid.isEmpty())
                    ? m.statusOwnerUid : m.senderId;
            return currentUid.equals(statusOwnerUid) ? TYPE_CANVAS_RECEIVED : TYPE_HIDDEN;
        }
        if ("reel_seen".equals(m.type)) {
            // Bubble must show ONLY to the reel's owner (the person who got
            // watched), never to the viewer who did the watching — otherwise
            // both sides see "watched your reel" for every reel view.
            return currentUid.equals(m.reelOwnerUid) ? TYPE_CANVAS_RECEIVED : TYPE_HIDDEN;
        }
        // call_entry — now rendered on Canvas as a bubbleless pill
        // (MessageBubbleCanvasView.bindCallEntry) instead of
        // item_call_entry_bubble.xml. Aligned to whichever side placed
        // the call, same as the legacy row's dynamic gravity.
        // TYPE_CALL_ENTRY + bindCallEntryBubble() are kept only as an
        // unused fallback.
        if ("call_entry".equals(m.type)) {
            return currentUid.equals(m.senderId) ? TYPE_CANVAS_SENT : TYPE_CANVAS_RECEIVED;
        }
        // Feature 13: View Once — now rendered on Canvas (all 3 states)
        // instead of item_view_once_bubble/sent_waiting/expired.xml.
        // TYPE_VIEW_ONCE_* + their legacy bind methods are kept only as
        // an unused fallback.
        if (Boolean.TRUE.equals(m.viewOnce)) {
            if (com.callx.app.conversation.controllers.ChatViewOnceController.isExpired(m)) {
                // Opened/expired/removed card follows the actual sender/
                // receiver of the message, same alignment the legacy
                // TYPE_VIEW_ONCE_EXPIRED row always had either way.
                return currentUid.equals(m.senderId) ? TYPE_CANVAS_SENT : TYPE_CANVAS_RECEIVED;
            }
            // Sender sees their own un-opened message as "Waiting to be opened" (lock state)
            // Only after receiver actually opens it does it become the expired/opened card
            if (currentUid.equals(m.senderId)) return TYPE_CANVAS_SENT;
            return TYPE_CANVAS_RECEIVED;
        }
        boolean sentFlag = currentUid.equals(m.senderId);
        if (isCanvasEligible(m, sentFlag)) {
            return sentFlag ? TYPE_CANVAS_SENT : TYPE_CANVAS_RECEIVED;
        }
        return sentFlag ? TYPE_SENT : TYPE_RECEIVED;
    }

    /**
     * True if MessageBubbleCanvasView (Phase 1) can fully render this
     * message on its own. Deliberately conservative — anything this
     * returns false for keeps using the existing item_message_sent/
     * received.xml + bindMessage() path, unchanged. See
     * MessageBubbleCanvasView's class doc for the full list of what it
     * does/doesn't handle; this method must stay in sync with that list.
     */
    private boolean isCanvasEligible(@NonNull Message m, boolean sentFlag) {
        // "This message was deleted" text swap is now modeled
        // (MessageBubbleCanvasView.setDeletedStyle) — a deleted message
        // always renders as the plain-text placeholder regardless of its
        // original type (mirrors bindMessage()'s early return), so it's
        // eligible here no matter what m.type says.
        if (Boolean.TRUE.equals(m.deleted)) return true;
        // edited label (setGroupSender/footer suffix), forwarded label
        // (setForwardedFrom), and broadcast badge (setGroupSender) are now
        // modeled — no longer disqualifying.
        // pinned is now modeled (MessageBubbleCanvasView.setPinned) — no longer disqualifying
        // reactions are now modeled (MessageBubbleCanvasView.setReactions) — no longer disqualifying
        // isGroup is now modeled for received messages (MessageBubbleCanvasView.setGroupSender) — no longer disqualifying
        if (m.expiresAt != null && m.expiresAt > 0) {
            // Disappearing-message countdown (setExpiryText) is now modeled
            // for the plain-text footer, the captioned/captionless media
            // pill (image/video/gif/multi_media reuse the same pill), the
            // audio/file/poll footer row, and now a small top-corner expiry
            // pill on the contact/location cards + the reel-share card's
            // existing bottom-end timestamp pill — every bubble type has
            // somewhere to draw it now.
            String expiryType = m.type != null ? m.type : "text";
            switch (expiryType) {
                case "text":
                case "image":
                case "video":
                case "gif":
                case "file":
                case "audio":
                case "multi_media":
                case "poll":
                case "contact":
                case "location":
                case "reel_share":
                case "reel_link":
                case "sticker":
                    break; // eligible — footer/pill models expiry
                default:
                    return false;
            }
        }

        String type = m.type != null ? m.type : "text";
        if ("text".equals(type)) return true;
        if ("image".equals(type)) {
            // Manual download-overlay pill (mirrors fl_download_overlay) is
            // now modeled (MessageBubbleCanvasView.setMediaDownloadGate) —
            // received images are eligible too, same as sent.
            //
            // Feature: Voice Caption on Photo — an image that also carries
            // a short attached voice note (m.voiceUrl != null) is routed to
            // the LEGACY View-based bubble instead, so it lands in
            // content_frame (a real FrameLayout) where a play-badge overlay
            // can sit on top of iv_image — see bindMessage()'s "image" case
            // and fl_voice_on_image in item_message_sent/received.xml.
            // Teaching Canvas itself to draw + hit-test that badge is a
            // bigger change left for later; every plain image (the
            // overwhelming majority) keeps rendering on Canvas exactly as
            // before, unaffected.
            if (m.voiceUrl != null && !m.voiceUrl.isEmpty()) return false;
            return true;
        }
        if ("multi_media".equals(type)) {
            // Both sent and received groups are eligible now — the
            // manual per-cell download-gate + master "Download N photos"
            // pill is modeled in MessageBubbleCanvasView (setGroupDownloadGate)
            // for the received case, same as MediaGroupLayoutHelper's old
            // View-based grid. Per-item captions are now modeled too
            // (MessageBubbleCanvasView.GridItem.caption / drawMediaGroup's
            // per-cell strip). Mixed-type groups (audio/file cells alongside
            // image/video) are now modeled too — MediaGroupRenderer draws a
            // dark placeholder + glyph + filename/duration label for those
            // cells (GridItem.isAudio/isFile/label), same visual as
            // MediaGroupLayoutHelper.buildCell()'s isAudio||isFile branch.
            if (m.mediaItems == null || m.mediaItems.isEmpty()) return false;
            for (java.util.Map<String, Object> item : m.mediaItems) {
                Object mtObj = item.get("mediaType");
                String mt = mtObj instanceof String ? (String) mtObj : "";
                if (!"image".equals(mt) && !"video".equals(mt)
                        && !"audio".equals(mt) && !"file".equals(mt)) return false;
            }
            return true;
        }
        if ("reel_share".equals(type) || "reel_link".equals(type)) {
            // Reel-share card (MessageBubbleCanvasView.bindReelShare) —
            // bubbleless 165×237dp Instagram-style card, same shape sent
            // and received. Deleted/expiry are already handled above this
            // check — its always-shown bottom-end timestamp pill now also
            // reserves space for + draws the expiry countdown.
            return true;
        }
        if ("video".equals(type)) {
            // Single video message (MessageBubbleCanvasView.bindVideo) —
            // reuses the same 180dp-square slot as "image", just with the
            // play-glyph + duration-badge overlay. No caption support,
            // same as the legacy fl_video case.
            return true;
        }
        if ("audio".equals(type)) {
            // Voice-message row (MessageBubbleCanvasView.bindAudio) — play/
            // pause button + waveform + elapsed-time label, same visual as
            // layout_msg_audio.xml. Playback itself is still driven by the
            // adapter's shared MediaPlayer (toggleAudio/playAudioFromPath),
            // just pushed through cv.setAudioPlaying()/setAudioProgress()/
            // setAudioElapsedText() instead of btnPlayPause/seekAudio/tvAudioDur.
            return true;
        }
        if ("contact".equals(type)) {
            // Contact-share card (MessageBubbleCanvasView.bindContact) —
            // bubbleless 165dp-wide card, same shape sent and received.
            // No regular timestamp/tick footer (matches item_msg_contact.xml
            // having none), but now shows a small top-corner expiry pill
            // when a disappearing-message timer is running. Deleted/expiry
            // already handled above this check.
            return true;
        }
        if ("location".equals(type)) {
            // Location-share card (MessageBubbleCanvasView.bindLocation) —
            // bubbleless 165dp-wide card, same shape family as the contact
            // card. No regular timestamp/tick footer (matches
            // item_msg_location.xml having none), but now shows a small
            // top-corner expiry pill when a disappearing-message timer is
            // running. Deleted/expiry already handled above this check.
            return true;
        }
        if ("poll".equals(type)) {
            // Poll card rendered by MessageBubbleCanvasView.bindPoll() —
            // same Canvas bubble path as contact/location. Replaces the
            // old ensurePollInflated / ViewStub path entirely.
            return true;
        }
        // v59: GIF and file bubbles now render on Canvas
        if ("gif".equals(type))  return true;
        if ("file".equals(type)) return true;
        // Sticker — bubbleless-feeling single-image slot (MessageBubbleCanvasView.bindSticker),
        // reuses the GIF layout path minus the badge pill.
        if ("sticker".equals(type)) return true;
        return false;
    }

    // ── PERF (v176): RecycledViewPool pre-warm ──────────────────────────────
    // Everything else in this file avoids paying inflation/construction cost
    // *during* scroll — but the very first fling after cold-open still has to
    // pay it at least once per view type, because the pool starts empty.
    // Build a few TYPE_CANVAS_SENT/RECEIVED holders (the two hottest,
    // highest-pool-size types — see setupPagingRecyclerView()'s
    // pool.setMaxRecycledViews calls) up front and drop them straight into
    // the RecycledViewPool, so the very first scroll finds warm views
    // waiting instead of triggering onCreateViewHolder() +
    // MessageBubbleCanvasView's Paint/RectF field init on the UI thread
    // mid-fling.
    //
    // Call ONCE, after setRecycledViewPool(pool), posted to the next frame
    // (not inline) so it never competes with the cold-open path this
    // adapter already special-cases elsewhere in this file.
    public void warmUpRecycledViewPool(@NonNull ViewGroup parent,
                                        @NonNull RecyclerView.RecycledViewPool pool,
                                        int countPerType) {
        for (int i = 0; i < countPerType; i++) {
            pool.putRecycledView(onCreateViewHolder(parent, TYPE_CANVAS_SENT));
            pool.putRecycledView(onCreateViewHolder(parent, TYPE_CANVAS_RECEIVED));
        }
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_HIDDEN) {
            View v = new View(parent.getContext());
            v.setLayoutParams(new ViewGroup.LayoutParams(0, 0));
            return new VH(v);
        }
        if (viewType == TYPE_DATE_SEPARATOR) {
            // Canvas replacement for item_date_separator.xml — see
            // DateSeparatorCanvasView's class doc. Kept as a plain View
            // (not added to the shared MessageBubbleCanvasView pool) since
            // its shape/frequency has nothing in common with message rows.
            com.callx.app.conversation.canvas.DateSeparatorCanvasView dv =
                    new com.callx.app.conversation.canvas.DateSeparatorCanvasView(parent.getContext());
            dv.setLayoutParams(new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            VH vh = new VH(dv);
            vh.dateSeparatorView = dv;
            return vh;
        }
        if (viewType == TYPE_CANVAS_SENT || viewType == TYPE_CANVAS_RECEIVED) {
            com.callx.app.conversation.canvas.MessageBubbleCanvasView cv =
                    new com.callx.app.conversation.canvas.MessageBubbleCanvasView(parent.getContext());
            RecyclerView.LayoutParams cvLp = new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT);
            // Placeholder margins for the very first measure pass — real
            // values are set per-position in onBindViewHolder (see
            // applyGroupedSpacing()) since grouping depends on the
            // neighboring message, which isn't known at creation/recycle
            // time. Recycled views always get rebound before layout, so
            // this initial value is never actually visible.
            cv.setLayoutParams(cvLp);
            cv.setSaveEnabled(false);
            VH vh = new VH(cv);
            vh.canvasView = cv;
            return vh;
        }
        int layout;
        if (viewType == TYPE_VIEW_ONCE_SENT)         layout = R.layout.item_view_once_bubble;
        else if (viewType == TYPE_VIEW_ONCE_EXPIRED) layout = R.layout.item_view_once_expired;
        else if (viewType == TYPE_VIEW_ONCE_SENT_WAITING) layout = R.layout.item_view_once_sent_waiting;
        else if (viewType == TYPE_SENT)          layout = R.layout.item_message_sent;
        else if (viewType == TYPE_STATUS_SEEN) layout = R.layout.item_status_seen_bubble;
        else if (viewType == TYPE_REEL_SEEN)   layout = R.layout.item_reel_seen_bubble;
        else if (viewType == TYPE_CALL_ENTRY)  layout = R.layout.item_call_entry_bubble;
        else                                   layout = R.layout.item_message_received;
        View v = LayoutInflater.from(parent.getContext()).inflate(layout, parent, false);
        // PERF: item-level micro-optimizations (applied once at create time)
        // setSaveEnabled(false) — skip useless per-item state parcelling
        // LAYER_TYPE_NONE       — ensure no stray software layer from XML
        v.setSaveEnabled(false);
        v.setLayerType(View.LAYER_TYPE_NONE, null);
        VH vh = new VH(v);
        // ── One-time constant setup — keeps onBindViewHolder lean ────────────
        if (vh.tvMessage != null) {
            vh.tvMessage.setTypeface(TF_NORMAL);
            vh.tvMessage.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
                    com.callx.app.utils.MessageFontSizeManager.get(parent.getContext()).getFontSizeSp());
        }
        // ── Apply 70% screen-width cap ──
        int screenW = parent.getContext().getResources().getDisplayMetrics().widthPixels;
        int maxW = (int) (screenW * 0.70f);
        if (vh.tvMessage != null) vh.tvMessage.setMaxWidth(maxW);
        // llAudio / llFile / flVideo / llLinkPreview / llPoll are now ViewStubs —
        // their LayoutParams are set via android:layout_width in the stub tag
        // (= @dimen/msg_bubble_max_width), so no runtime width override is needed.
        if (vh.ivImage != null) { android.view.ViewGroup.LayoutParams lp = vh.ivImage.getLayoutParams(); if (lp != null) { lp.width = maxW; vh.ivImage.setLayoutParams(lp); } }
        return vh;
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position,
                                 @NonNull java.util.List<Object> payloads) {
        if (!payloads.isEmpty() && PAYLOAD_READ_BY.equals(payloads.get(0))) {
            // Only update the "Seen by X" strip; skip full rebind
            Message m = getItem(position);
            if (m != null) bindSeenByStrip(h, m);
            return;
        }
        // PERF: combined bit-flag payload from DIFF.getChangePayload() —
        // status/reactions/poll/edited may all be set together in one
        // Integer when multiple of these fields changed in the same diff
        // pass, so this runs every matching fast-path bind in a single
        // onBindViewHolder call instead of falling through to a full
        // rebind just because no single "only X changed" branch matched.
        if (!payloads.isEmpty() && payloads.get(0) instanceof Integer) {
            int flags = (Integer) payloads.get(0);
            Message m = getItem(position);
            if (m != null) {
                if ((flags & FLAG_STATUS) != 0) {
                    // FIX: this used to only update the legacy tv_status
                    // TextView (bindStatusTick below) — but canvas-eligible
                    // bubbles (the common case: plain text, images, etc.,
                    // see isCanvasEligible()) don't have a tv_status at all,
                    // so their tick silently never updated on a sent→
                    // delivered→read transition. bindStatusTick still runs
                    // for the legacy/non-canvas fallback views; the canvas
                    // view now gets its own cheap, draw-only update too.
                    if (h.tvStatus != null) {
                        bindStatusTick(h, m);
                    }
                    if (h.canvasView != null) {
                        boolean isRead = "read".equals(m.status);
                        boolean isDelivered = isRead || "delivered".equals(m.status);
                        h.canvasView.setDeliveryStatus(isRead, isDelivered);
                    }
                }
                if ((flags & FLAG_REACTIONS) != 0) {
                    bindReactionsOnly(h, m);
                }
                if ((flags & FLAG_POLL) != 0) {
                    bindPollOnly(h, m);
                }
                if ((flags & FLAG_EDITED) != 0) {
                    bindEditedOnly(h, m);
                }
            }
            return;
        }
        if (!payloads.isEmpty() && PAYLOAD_PRESENCE.equals(payloads.get(0))) {
            // Fast path: a viewing/typing-reply/playback broadcast changed —
            // update only the dot/glow/badge, skip full bind entirely.
            Message m = getItem(position);
            if (m != null) {
                bindPresenceOnly(h, m);
            }
            return;
        }
        if (!payloads.isEmpty() && PAYLOAD_MEDIA_PROGRESS.equals(payloads.get(0))) {
            // Fast path: an in-flight upload's percentage ticked — update
            // only the spinner ring, skip Glide reloads / full bind entirely.
            Message m = getItem(position);
            if (m != null && h.canvasView != null) {
                String id = m.messageId != null ? m.messageId : m.id;
                int percent = uploadProgressTracker.getProgress(id);
                if (percent >= 0) h.canvasView.setMediaDownloadProgress(percent);
            }
            return;
        }
        // NOTE: standalone PAYLOAD_REACTIONS/PAYLOAD_POLL/PAYLOAD_EDITED
        // branches used to live here — they're now handled by the combined
        // bit-flag `instanceof Integer` branch above (a lone flag is just a
        // 1-bit mask, so notifyItemChanged(i, PAYLOAD_REACTIONS)-style
        // single-flag calls still route through the same fast path).
        if (!payloads.isEmpty() && PAYLOAD_SEARCH.equals(payloads.get(0))) {
            // Fast path: only the search query changed — repaint just the
            // highlight, skip Glide/Linkify/full canvas rebind entirely.
            Message m = getItem(position);
            if (m != null) bindSearchHighlightOnly(h, m);
            return;
        }
        // Full bind
        onBindViewHolder(h, position);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        // TraceSectionMetric("Msg#bind") -- full bind cost per message row.
        // Target: median < 2ms, P99 < 8ms.
        android.os.Trace.beginSection("Msg#bind");
        try {
        Message m = getItem(position);
        if (m == null) {
            // Placeholder — show shimmer or empty
            if (h.tvMessage != null) h.tvMessage.setVisibility(View.GONE);
            return;
        }
        
        // Store reference for height caching on recycle
        h.boundMessage = m;

        // TELEGRAM-STYLE SEND ANIMATION — one-shot spring rise+fade, only
        // for the bubble we just sent (see markMessageForSendAnimation /
        // playSendInAnimation). Every other bind takes the cheap reset path
        // so a recycled holder can never bleed a stray transform onto
        // unrelated content (see resetSendAnimState).
        if (m.id != null && m.id.equals(pendingSendAnimMessageId)) {
            pendingSendAnimMessageId = null; // consume once
            playSendInAnimation(h);
        } else {
            resetSendAnimState(h);
        }
        
        // WHATSAPP-STYLE SMOOTHNESS: Set a fixed height hint BEFORE binding
        // This prevents the RecyclerView from recalculating layout when new
        // messages arrive. If height was previously measured, reuse it.
        // If not, the view will measure naturally but won't cause full list re-layout.
        if (h.itemView != null && m.messageId != null) {
            String cacheKey = m.messageId;
            Integer cachedHeight = messagHeightCache.get(cacheKey);
            if (cachedHeight != null && cachedHeight > 0) {
                // Reuse cached height — prevents measure thrashing
                android.view.ViewGroup.LayoutParams lp = h.itemView.getLayoutParams();
                if (lp != null && lp.height != cachedHeight) {
                    lp.height = cachedHeight;
                    h.itemView.setLayoutParams(lp);
                }
            }
        }
        // ── DATE SEPARATOR / SECURITY EVENT — standalone chip row ────────
        if ("date_separator".equals(m.type) || "security_event".equals(m.type)) {
            if (h.dateSeparatorView != null) h.dateSeparatorView.setLabel(m.text);
            return;
        }
        // ── CANVAS PATH — text/media/contact/location/poll/view-once/
        // seen-system-rows etc. all render through MessageBubbleCanvasView
        // now; checked before any of the legacy per-type branches below so
        // getItemViewType's TYPE_CANVAS_SENT/RECEIVED routing actually
        // takes effect for them (status_seen/reel_seen/view_once included).
        if (h.canvasView != null) {
            applyGroupedSpacing(h, position, m);
            bindCanvasMessage(h, m);
            return;
        }
        // ── STATUS SEEN BUBBLE — special system event row (legacy fallback,
        // unreachable now that getItemViewType() routes these to Canvas) ──
        if ("status_seen".equals(m.type)) {
            String statusOwnerUid = (m.statusOwnerUid != null && !m.statusOwnerUid.isEmpty())
                    ? m.statusOwnerUid : m.senderId;
            if (!currentUid.equals(statusOwnerUid)) return; // hidden for the viewer side
            bindStatusSeenBubble(h, m);
            return;
        }
        // ── REEL SEEN BUBBLE — special system event row (legacy fallback,
        // unreachable now that getItemViewType() routes these to Canvas) ──
        if ("reel_seen".equals(m.type)) {
            if (!currentUid.equals(m.reelOwnerUid)) return; // hidden for the viewer side
            bindReelSeenBubble(h, m);
            return;
        }
        // ── CALL ENTRY BUBBLE — system call log row in chat ──────────────────
        if ("call_entry".equals(m.type)) {
            bindCallEntryBubble(h, m);
            return;
        }
        // ── VIEW ONCE BUBBLES — Feature 13 (legacy fallback, unreachable
        // now that getItemViewType() routes these to Canvas) ─────────────
        if (Boolean.TRUE.equals(m.viewOnce)) {
            if (com.callx.app.conversation.controllers.ChatViewOnceController.isExpired(m)) {
                bindViewOnceExpired(h, m);
                return;
            }
            if (currentUid.equals(m.senderId)) {
                bindViewOnceSentWaiting(h, m);
                return;
            }
            bindViewOnceSent(h, m);
            return;
        }
        bindMessage(h, m, position);
        // Update "Seen by" strip for sent group messages
        bindSeenByStrip(h, m);
        } finally {
            android.os.Trace.endSection();
        }
    }

    // ──────────────────────────────────────────────────────────────
    // CALL ENTRY BUBBLE — centered system call log row in chat.
    // Layout: item_call_entry_bubble.xml
    //   tv_call_entry_icon  — emoji (📞 audio, 📹 video)
    //   tv_call_entry_label — e.g. "Audio call • 2:30" or "Missed video call"
    //   tv_call_entry_time  — formatted timestamp (hh:mm a)
    // No long-press / reactions — it's a system event.
    // ──────────────────────────────────────────────────────────────
    private void bindCallEntryBubble(@NonNull VH h, @NonNull Message m) {
        android.widget.TextView tvIcon  = h.tvCallEntryIcon;
        android.widget.TextView tvLabel = h.tvCallEntryLabel;
        android.widget.TextView tvTime  = h.tvCallEntryTime;
        android.view.View llRoot = h.llCallEntryRoot;
        android.view.View llPill = h.llCallEntryPill;

        boolean isVideoCall = "video".equals(m.fileName);
        boolean isMissed    = "missed".equals(m.text);
        boolean iAmCaller   = currentUid != null && currentUid.equals(m.senderId);

        // Align bubble to the caller's side — right if I called, left if they called.
        if (llRoot instanceof android.widget.LinearLayout) {
            ((android.widget.LinearLayout) llRoot).setGravity(
                    iAmCaller ? android.view.Gravity.END : android.view.Gravity.START);
        }
        if (llPill != null) {
            android.widget.LinearLayout.LayoutParams lp =
                    (android.widget.LinearLayout.LayoutParams) llPill.getLayoutParams();
            lp.gravity = iAmCaller ? android.view.Gravity.END : android.view.Gravity.START;
            llPill.setLayoutParams(lp);
        }

        // Icon
        if (tvIcon != null) tvIcon.setText(isVideoCall ? "📹" : "📞");

        // Label text
        String label;
        if (isMissed) {
            if (iAmCaller) {
                label = isVideoCall ? "No answer (video)" : "No answer";
            } else {
                label = isVideoCall ? "Missed video call" : "Missed call";
            }
            if (tvLabel != null) tvLabel.setTextColor(android.graphics.Color.parseColor("#FF5555"));
        } else {
            // Call was connected — show duration
            String durStr = "";
            if (m.duration != null && m.duration > 0) {
                long sec = m.duration / 1000;
                durStr = " • " + String.format(java.util.Locale.getDefault(), "%d:%02d", sec / 60, sec % 60);
            }
            if (iAmCaller) {
                label = isVideoCall ? ("Video call" + durStr) : ("Audio call" + durStr);
            } else {
                label = isVideoCall ? ("Incoming video call" + durStr) : ("Incoming call" + durStr);
            }
            if (tvLabel != null) tvLabel.setTextColor(0xFFFFFFFF);
        }
        if (tvLabel != null) tvLabel.setText(label);

        // Time
        if (tvTime != null && m.timestamp != null && m.timestamp > 0) {
            tvTime.setText(formatTime(m.timestamp));
        }
    }

    // ──────────────────────────────────────────────────────────────
    // STATUS SEEN BUBBLE — "👁 Seen your status" system event row.
    // Layout: item_status_seen_bubble.xml
    //   • iv_status_seen_avatar  → circular avatar (Glide)
    //   • fl_status_seen_thumb   → thumbnail container (visible for image/video statuses)
    //   • iv_status_seen_thumb   → status thumbnail (tappable → StatusViewerActivity)
    //   • iv_status_seen_eye     → eye overlay icon on thumbnail
    //   • tv_status_seen_label   → "Seen your status" (set in XML)
    //   • tv_status_seen_name    → sender name (group only)
    //   • tv_status_seen_time    → formatted timestamp
    // No long-press / reactions / reply — it's a system event.
    // ──────────────────────────────────────────────────────────────
    private void bindStatusSeenBubble(@NonNull VH h, @NonNull Message m) {
        Context ctx = h.itemView.getContext();

        // Avatar
        de.hdodenhof.circleimageview.CircleImageView ivAvatar = h.ivStatusSeenAvatar;
        if (ivAvatar != null) {
            String photo = m.senderPhoto != null ? m.senderPhoto : "";
            if (!photo.isEmpty()) {
                // PERF FIX: same root cause as the chat-list flicker bug —
                // this used to fire an unconditional async Glide load on
                // EVERY bind (including plain scroll-recycle and any
                // rebind this row picks up from an unrelated list change),
                // guaranteeing a blank/placeholder flash before the avatar
                // popped back in even though it was already decoded a
                // moment ago. Check AVATAR_BITMAP_CACHE synchronously
                // first — same shared pool the canvas seen-avatar path uses.
                android.graphics.Bitmap statusAvatarHit = AVATAR_BITMAP_CACHE.get(photo);
                if (statusAvatarHit != null && !statusAvatarHit.isRecycled()) {
                    dashboardRecordHit(ctx, photo);
                    ivAvatar.setImageBitmap(statusAvatarHit);
                } else {
                    ivAvatar.setImageResource(R.drawable.ic_person);
                    glide(ctx).asBitmap()
                        .load(photo)
                        .apply(THUMB_RGB565)
                        .override(96, 96)
                        .apply(com.bumptech.glide.request.RequestOptions.circleCropTransform())
                        .listener(com.callx.app.cache.CacheDashboardStats.glideListener(
                                ctx, photo))
                        .into(new com.bumptech.glide.request.target.CustomTarget<Bitmap>() {
                            @Override
                            public void onResourceReady(@NonNull Bitmap resource,
                                    @Nullable com.bumptech.glide.request.transition.Transition<? super Bitmap> transition) {
                                AVATAR_BITMAP_CACHE.put(photo, resource);
                                dashboardRecordDecoded(ctx, photo, resource);
                                if (h.getBindingAdapterPosition() == RecyclerView.NO_POSITION) return;
                                ivAvatar.setImageBitmap(resource);
                            }
                            @Override
                            public void onLoadCleared(@Nullable android.graphics.drawable.Drawable placeholder) {}
                        });
                }
            } else {
                ivAvatar.setImageResource(R.drawable.ic_person);
            }
        }

        // Status thumbnail
        android.view.View flThumb = h.flStatusSeenThumb;
        android.widget.ImageView ivThumb = h.ivStatusSeenThumb;
        android.widget.ImageView ivEye   = h.ivStatusSeenEye;
        if (ivThumb != null && flThumb != null) {
            String thumb = m.statusThumbUrl != null ? m.statusThumbUrl : "";
            if (!thumb.isEmpty()) {
                flThumb.setVisibility(View.VISIBLE);
                if (ivEye != null) ivEye.setVisibility(View.VISIBLE);
                // Same fix as the avatar above — sync DECODED_BITMAP_CACHE
                // check before falling back to an async load.
                android.graphics.Bitmap statusThumbHit = DECODED_BITMAP_CACHE.get(poolKey(thumb, 240, 240));
                if (statusThumbHit != null && !statusThumbHit.isRecycled()) {
                    dashboardRecordHit(ctx, thumb);
                    ivThumb.setImageBitmap(statusThumbHit);
                } else {
                    ivThumb.setImageResource(R.drawable.bg_skeleton_rect);
                    glide(ctx).asBitmap()
                        .load(thumb)
                        .apply(THUMB_RGB565)
                        .override(240, 240)
                        .centerCrop()
                        .listener(com.callx.app.cache.CacheDashboardStats.glideListener(
                                ctx, thumb))
                        .into(new com.bumptech.glide.request.target.CustomTarget<Bitmap>() {
                            @Override
                            public void onResourceReady(@NonNull Bitmap resource,
                                    @Nullable com.bumptech.glide.request.transition.Transition<? super Bitmap> transition) {
                                DECODED_BITMAP_CACHE.put(poolKey(thumb, 240, 240), resource);
                                dashboardRecordDecoded(ctx, thumb, resource);
                                if (h.getBindingAdapterPosition() == RecyclerView.NO_POSITION) return;
                                ivThumb.setImageBitmap(resource);
                            }
                            @Override
                            public void onLoadCleared(@Nullable android.graphics.drawable.Drawable placeholder) {}
                        });
                }
            } else {
                flThumb.setVisibility(View.GONE);
                if (ivEye != null) ivEye.setVisibility(View.GONE);
            }
        }

        // Click on whole bubble or thumbnail → open StatusViewerActivity
        final String ownerUid  = (m.statusOwnerUid != null && !m.statusOwnerUid.isEmpty())
                                 ? m.statusOwnerUid : m.senderId;
        final String ownerName = m.statusOwnerName != null ? m.statusOwnerName
                                 : (m.senderName != null ? m.senderName : "");
        android.view.View.OnClickListener openStatus = v -> {
            if (ownerUid == null || ownerUid.isEmpty()) return;
            android.content.Intent intent = new android.content.Intent(
                    com.callx.app.utils.Constants.ACTION_OPEN_STATUS);
            intent.putExtra("ownerUid",  ownerUid);
            intent.putExtra("ownerName", ownerName);
            intent.setPackage(ctx.getPackageName());
            try { ctx.startActivity(intent); }
            catch (android.content.ActivityNotFoundException e) {
                android.widget.Toast.makeText(ctx, "Status viewer not available",
                        android.widget.Toast.LENGTH_SHORT).show();
            }
        };
        h.itemView.setOnClickListener(openStatus);
        if (flThumb != null) flThumb.setOnClickListener(openStatus);

        // Sender name (shown in group chat only)
        android.widget.TextView tvName = h.tvStatusSeenName;
        if (tvName != null) {
            if (isGroup && m.senderName != null && !m.senderName.isEmpty()) {
                tvName.setText(m.senderName);
                tvName.setVisibility(View.VISIBLE);
            } else {
                tvName.setVisibility(View.GONE);
            }
        }

        // Time
        android.widget.TextView tvTime = h.tvStatusSeenTime;
        if (tvTime != null && m.timestamp != null && m.timestamp > 0) {
            tvTime.setText(formatTime(m.timestamp));
        }
    }

    // ──────────────────────────────────────────────────────────────
    // REEL SEEN BUBBLE — "🎬 Watched your reel" system event row.
    // Layout: item_reel_seen_bubble.xml
    //   • iv_reel_seen_avatar   → circular avatar (Glide)
    //   • fl_reel_seen_thumb    → FrameLayout container (tappable → opens reel)
    //   • iv_reel_seen_thumb    → reel thumbnail
    //   • iv_reel_seen_play     → play icon overlay on thumbnail
    //   • tv_reel_seen_label    → "Watched your reel" (set in XML)
    //   • tv_reel_seen_name     → sender name (group only)
    //   • tv_reel_seen_time     → formatted timestamp
    // No long-press / reactions / reply — system event.
    // ──────────────────────────────────────────────────────────────
    private void bindReelSeenBubble(@NonNull VH h, @NonNull Message m) {
        Context ctx = h.itemView.getContext();

        // Avatar
        de.hdodenhof.circleimageview.CircleImageView ivAvatar = h.ivReelSeenAvatar;
        if (ivAvatar != null) {
            String photo = m.senderPhoto != null ? m.senderPhoto : "";
            if (!photo.isEmpty()) {
                // Same sync-cache-first fix as bindStatusSeenBubble above —
                // check AVATAR_BITMAP_CACHE before firing an async Glide load.
                android.graphics.Bitmap reelSeenAvatarHit = AVATAR_BITMAP_CACHE.get(photo);
                if (reelSeenAvatarHit != null && !reelSeenAvatarHit.isRecycled()) {
                    dashboardRecordHit(ctx, photo);
                    ivAvatar.setImageBitmap(reelSeenAvatarHit);
                } else {
                    ivAvatar.setImageResource(R.drawable.ic_person);
                    glide(ctx).asBitmap()
                        .load(photo)
                        .apply(THUMB_RGB565)
                        .override(96, 96)
                        .apply(com.bumptech.glide.request.RequestOptions.circleCropTransform())
                        .listener(com.callx.app.cache.CacheDashboardStats.glideListener(
                                ctx, photo))
                        .into(new com.bumptech.glide.request.target.CustomTarget<Bitmap>() {
                            @Override
                            public void onResourceReady(@NonNull Bitmap resource,
                                    @Nullable com.bumptech.glide.request.transition.Transition<? super Bitmap> transition) {
                                AVATAR_BITMAP_CACHE.put(photo, resource);
                                dashboardRecordDecoded(ctx, photo, resource);
                                if (h.getBindingAdapterPosition() == RecyclerView.NO_POSITION) return;
                                ivAvatar.setImageBitmap(resource);
                            }
                            @Override
                            public void onLoadCleared(@Nullable android.graphics.drawable.Drawable placeholder) {}
                        });
                }
            } else {
                ivAvatar.setImageResource(R.drawable.ic_person);
            }
        }

        // Click handler — reelId se reel kholo (thumb ho ya na ho, always kaam kare)
        final String reelId = m.reelId;
        android.view.View.OnClickListener openReel = v -> {
            if (reelId == null || reelId.isEmpty()) return;
            android.content.Intent intent = new android.content.Intent(
                    com.callx.app.utils.Constants.ACTION_OPEN_REEL);
            intent.putExtra("reelId", reelId);
            intent.setPackage(ctx.getPackageName());
            ctx.startActivity(intent);
        };

        // Reel thumbnail + play icon
        android.view.View flThumb = h.flReelSeenThumb;
        android.widget.ImageView ivThumb = h.ivReelSeenThumb;
        android.widget.ImageView ivPlay  = h.ivReelSeenPlay;
        if (ivThumb != null) {
            String thumb = m.reelThumbUrl != null ? m.reelThumbUrl : "";
            if (!thumb.isEmpty()) {
                ivThumb.setVisibility(android.view.View.VISIBLE);
                if (ivPlay != null) ivPlay.setVisibility(android.view.View.VISIBLE);
                // Same fix — sync DECODED_BITMAP_CACHE check before an
                // async load, same as the status-seen thumb above.
                android.graphics.Bitmap reelSeenThumbHit = DECODED_BITMAP_CACHE.get(poolKey(thumb, 240, 240));
                if (reelSeenThumbHit != null && !reelSeenThumbHit.isRecycled()) {
                    ivThumb.setImageBitmap(reelSeenThumbHit);
                } else {
                    ivThumb.setImageResource(R.drawable.bg_skeleton_rect);
                    glide(ctx).asBitmap()
                        .load(thumb)
                        .apply(THUMB_RGB565)
                        .override(240, 240)
                        .centerCrop()
                        .into(new com.bumptech.glide.request.target.CustomTarget<Bitmap>() {
                            @Override
                            public void onResourceReady(@NonNull Bitmap resource,
                                    @Nullable com.bumptech.glide.request.transition.Transition<? super Bitmap> transition) {
                                DECODED_BITMAP_CACHE.put(poolKey(thumb, 240, 240), resource);
                                if (h.getBindingAdapterPosition() == RecyclerView.NO_POSITION) return;
                                ivThumb.setImageBitmap(resource);
                            }
                            @Override
                            public void onLoadCleared(@Nullable android.graphics.drawable.Drawable placeholder) {}
                        });
                }
            } else {
                ivThumb.setVisibility(android.view.View.GONE);
                if (ivPlay != null) ivPlay.setVisibility(android.view.View.GONE);
            }
        }
        // Click on FrameLayout container, play icon, AND whole item
        if (flThumb != null) flThumb.setOnClickListener(openReel);
        if (ivPlay  != null) ivPlay.setOnClickListener(openReel);
        h.itemView.setOnClickListener(openReel);

        // Sender name (group only)
        android.widget.TextView tvName = h.tvReelSeenName;
        if (tvName != null) {
            if (isGroup && m.senderName != null && !m.senderName.isEmpty()) {
                tvName.setText(m.senderName);
                tvName.setVisibility(android.view.View.VISIBLE);
            } else {
                tvName.setVisibility(android.view.View.GONE);
            }
        }

        // Time
        android.widget.TextView tvTime = h.tvReelSeenTime;
        if (tvTime != null && m.timestamp != null && m.timestamp > 0) {
            tvTime.setText(formatTime(m.timestamp));
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Core bind logic (mirrors MessageAdapter)
    // ──────────────────────────────────────────────────────────────
    // ──────────────────────────────────────────────────────────────
    // Date separator helper — returns "Today", "Yesterday", or "3 Jan 2025"
    // PERF: result is cached per midnight-truncated timestamp so Calendar
    // isn't allocated on every row during a scroll.
    // ──────────────────────────────────────────────────────────────
    private String formatDateLabel(long timestamp) {
        long dayKey = (timestamp / 86_400_000L) * 86_400_000L;
        String cached = dateLabelCache.get(dayKey);
        if (cached != null) return cached;

        java.util.Calendar msgCal = java.util.Calendar.getInstance();
        msgCal.setTimeInMillis(timestamp);
        java.util.Calendar today = java.util.Calendar.getInstance();
        java.util.Calendar yesterday = java.util.Calendar.getInstance();
        yesterday.add(java.util.Calendar.DAY_OF_YEAR, -1);

        boolean isToday = msgCal.get(java.util.Calendar.YEAR) == today.get(java.util.Calendar.YEAR)
                && msgCal.get(java.util.Calendar.DAY_OF_YEAR) == today.get(java.util.Calendar.DAY_OF_YEAR);
        boolean isYesterday = msgCal.get(java.util.Calendar.YEAR) == yesterday.get(java.util.Calendar.YEAR)
                && msgCal.get(java.util.Calendar.DAY_OF_YEAR) == yesterday.get(java.util.Calendar.DAY_OF_YEAR);

        String label;
        if (isToday) {
            label = "Today";
        } else if (isYesterday) {
            label = "Yesterday";
        } else {
            boolean sameYear = msgCal.get(java.util.Calendar.YEAR) == today.get(java.util.Calendar.YEAR);
            reuseDate.setTime(timestamp);
            label = (sameYear ? dateLabelFmt : dateLabelOtherYearFmt)
                    .format(reuseDate);
        }
        // Don't cache "Today" / "Yesterday" — they become stale at midnight.
        // Cache only absolute date strings which never change.
        if (!label.equals("Today") && !label.equals("Yesterday")) {
            if (dateLabelCache.size() >= 64) dateLabelCache.clear();
            dateLabelCache.put(dayKey, label);
        }
        return label;
    }

    // PERF: isSameDay — cached by combined day-truncated key pair.
    // Avoids two Calendar.getInstance() + setTimeInMillis on every row.
    private boolean isSameDay(long ts1, long ts2) {
        long day1 = ts1 / 86_400_000L;
        long day2 = ts2 / 86_400_000L;
        if (day1 == day2) return true;          // fast path — same millisecond-day bucket
        long cacheKey = (day1 << 20) ^ day2;    // combine; collisions harmless (fallback to Calendar)
        Boolean hit = sameDayCache.get(cacheKey);
        if (hit != null) return hit;
        java.util.Calendar c1 = java.util.Calendar.getInstance();
        java.util.Calendar c2 = java.util.Calendar.getInstance();
        c1.setTimeInMillis(ts1);
        c2.setTimeInMillis(ts2);
        boolean same = c1.get(java.util.Calendar.YEAR) == c2.get(java.util.Calendar.YEAR)
                && c1.get(java.util.Calendar.DAY_OF_YEAR) == c2.get(java.util.Calendar.DAY_OF_YEAR);
        if (sameDayCache.size() >= 32) sameDayCache.clear();
        sameDayCache.put(cacheKey, same);
        return same;
    }

    /**
     * Binds a message to a MessageBubbleCanvasView holder (see
     * isCanvasEligible()). Covers plain-text messages, a sent single
     * image, or a sent/received multi-image/video group (all cells plain
     * image/video, no per-item captions) — optionally with a reply,
     * reactions, pinned/forwarded/broadcast labels, and (plain-text only)
     * a disappearing-message countdown. A deleted message always renders
     * as the plain-text placeholder regardless of its original type.
     * Every other shape is filtered out before a holder ever gets here.
     */
    /**
     * TELEGRAM-STYLE GROUPING: consecutive messages from the same sender
     * (with no date-separator/system row between them) sit close together
     * — only a conversation "turn" (sender change or a date boundary) gets
     * the full gap. Previously every bubble got a fixed 4dp/4dp margin
     * regardless of neighbors, which is the "every message is its own
     * pill, evenly spaced" look. Must run per-bind (not at view creation)
     * since grouping depends on the neighboring item, and recycled views
     * are rebound to a different position on every reuse.
     */
    private void applyGroupedSpacing(@NonNull VH h, int position, @NonNull Message m) {
        View v = h.canvasView;
        ViewGroup.LayoutParams raw = v.getLayoutParams();
        if (!(raw instanceof RecyclerView.LayoutParams)) return;
        RecyclerView.LayoutParams lp = (RecyclerView.LayoutParams) raw;

        float density = v.getContext().getResources().getDisplayMetrics().density;
        int tightGap = Math.round(1.5f * density); // within a group
        int fullGap  = Math.round(6f * density);   // between groups/turns
        int bottomGap = Math.round(1.5f * density); // small fixed trailer; next item's topMargin does the real work

        boolean grouped = isGroupedWithPrevious(position, m);
        int newTop = grouped ? tightGap : fullGap;
        if (lp.topMargin != newTop || lp.bottomMargin != bottomGap) {
            lp.topMargin = newTop;
            lp.bottomMargin = bottomGap;
            v.setLayoutParams(lp);
        }
    }

    /** True if `m` should visually group with the message immediately above it. */
    private boolean isGroupedWithPrevious(int position, @NonNull Message m) {
        if (m.senderId == null || isNonGroupingRow(m.type)) return false;
        Message prev;
        try { prev = getItem(position - 1); } catch (Exception e) { return false; }
        if (prev == null || prev.senderId == null || isNonGroupingRow(prev.type)) return false;
        return prev.senderId.equals(m.senderId);
    }

    /** System/utility rows never group with their neighbors — always full gap. */
    private boolean isNonGroupingRow(String type) {
        return "date_separator".equals(type) || "security_event".equals(type)
                || "status_seen".equals(type) || "reel_seen".equals(type)
                || "view_once".equals(type);
    }

    private void bindCanvasMessage(@NonNull VH h, @NonNull Message m) {
        final Context ctx = h.itemView.getContext();
        final com.callx.app.conversation.canvas.MessageBubbleCanvasView cv = h.canvasView;
        final int myToken = ++h.canvasBindToken;
        final boolean sent = currentUid != null && currentUid.equals(m.senderId);
        final boolean isRead = "read".equals(m.status);
        final boolean isDelivered = isRead || "delivered".equals(m.status);
        // Same "  ✏️ edited" suffix bindMessage() appends to tv_time — flows
        // through into whichever bind*() call below via this one string, so
        // it shows up for text/image/multi_media/deleted-placeholder alike.
        String timeStr = (m.timestamp != null && m.timestamp > 0) ? formatTime(m.timestamp) : "";
        if (Boolean.TRUE.equals(m.edited)) timeStr = timeStr + "  \u270F\uFE0F edited";
        // FIX: tell the canvas view whether the "✏️ edited" suffix above is
        // actually present, so its onTouchEvent knows to treat the footer's
        // hit-rect as tappable (see setEdited() doc) — without this the
        // pencil tag drew fine but was never clickable and edit history
        // never opened.
        cv.setEdited(Boolean.TRUE.equals(m.edited));
        final String type = m.type != null ? m.type : "text";
        final boolean isImage = "image".equals(type);
        final boolean isMultiMedia = "multi_media".equals(type);
        final boolean isReelShare = "reel_share".equals(type) || "reel_link".equals(type);
        final boolean isVideo = "video".equals(type);
        final boolean isAudio = "audio".equals(type);
        final boolean isContact = "contact".equals(type);
        final boolean isLocation = "location".equals(type);
        final boolean isGif  = "gif".equals(type);
        final boolean isSticker = "sticker".equals(type);
        final boolean isFile = "file".equals(type);
        final boolean isPoll = "poll".equals(type);
        final boolean isDeleted = Boolean.TRUE.equals(m.deleted);
        final boolean isStatusSeen = "status_seen".equals(m.type);
        final boolean isReelSeen = "reel_seen".equals(m.type);
        final boolean isSeen = isStatusSeen || isReelSeen;
        final boolean isCallEntry = "call_entry".equals(m.type);
        final boolean isViewOnceMsg = Boolean.TRUE.equals(m.viewOnce);
        final boolean isViewOnceExpiredState = isViewOnceMsg
                && com.callx.app.conversation.controllers.ChatViewOnceController.isExpired(m);
        final boolean isViewOnceWaiting = isViewOnceMsg && !isViewOnceExpiredState && sent;

        // ── Quick Forward Button — media/link messages pe dikhao ──────────
        // Mirrors the legacy btnQuickForward.setVisibility() rule (see
        // bindMessage() above) — same message types, plus "gif" (canvas-only
        // type, added after that legacy list was written). Never shown for
        // deleted placeholders, view-once cards, seen-bubbles, or call-entry
        // pills — none of those were forwardable in the legacy path either.
        boolean showFwd = !isDeleted && !isViewOnceMsg && !isSeen && !isCallEntry
                && (isImage || isVideo || isAudio || isFile || isReelShare || isMultiMedia || isGif || isSticker
                    || ("text".equals(type) && m.text != null
                        && (m.text.contains("http://") || m.text.contains("https://"))));
        cv.setQuickForwardVisible(showFwd);

        if (isDeleted) {
            // Mirrors bindMessage()'s deleted-message branch: always the
            // plain-text placeholder, regardless of the message's original
            // type — no media/group content is ever shown once deleted.
            String placeholder = sent ? "You deleted this message" : "This message was deleted";
            cv.bind(placeholder, timeStr, sent, isRead, isDelivered);
            cv.setDeletedStyle(true);
            // Deleted placeholders are always short — clear any leftover
            // read-more state from a recycled holder.
            cv.setTextExpanded(false);
            cv.setReadMoreListener(null);
        } else if (isViewOnceMsg) {
            // Mirrors the legacy bindViewOnceSentWaiting/bindViewOnceSent/
            // bindViewOnceExpired trio (Feature 13) — same 3 states, just
            // pushed through cv.bindViewOnce() instead of item_view_once_*
            // .xml inflate. Time text always plain "h:mm a" here (no
            // "edited" suffix — view-once messages can't be edited).
            String voTime = (m.timestamp != null && m.timestamp > 0) ? formatTime(m.timestamp) : "";
            if (isViewOnceExpiredState) {
                String expiredLabel;
                boolean showOpenedAt;
                String openedAtText = "";
                if (com.callx.app.conversation.controllers.ChatViewOnceController.isTimerExpired(m)) {
                    expiredLabel = "Expired";
                    showOpenedAt = false;
                } else if (com.callx.app.conversation.controllers.ChatViewOnceController.isRevoked(m)) {
                    expiredLabel = "Removed";
                    showOpenedAt = false;
                } else {
                    expiredLabel = "Opened";
                    showOpenedAt = sent && m.openedAt != null;
                    if (showOpenedAt) {
                        // PERF: cached formatter — was `new SimpleDateFormat(...)` per bind
                        openedAtText = "Opened \u00b7 " + formatViewOnceTime(m.openedAt);
                    }
                }
                cv.bindViewOnce(com.callx.app.conversation.canvas.MessageBubbleCanvasView.VIEW_ONCE_EXPIRED,
                        null, expiredLabel, openedAtText, showOpenedAt, voTime, sent);
            } else if (isViewOnceWaiting) {
                cv.bindViewOnce(com.callx.app.conversation.canvas.MessageBubbleCanvasView.VIEW_ONCE_WAITING,
                        null, null, null, false, voTime, true);
            } else {
                cv.bindViewOnce(com.callx.app.conversation.canvas.MessageBubbleCanvasView.VIEW_ONCE_RECEIVED,
                        buildTypeHint(m.type), null, null, false, voTime, false);
            }
            cv.setDeletedStyle(false);
        } else if (isSeen) {
            // Mirrors the legacy bindStatusSeenBubble/bindReelSeenBubble
            // pair (system event rows) — same avatar + optional thumbnail
            // + sender-name-in-groups + time, just pushed through
            // cv.bindSeenBubble()/setSeenAvatarBitmap()/setSeenThumbBitmap()
            // instead of CircleImageView/ImageView/TextView calls.
            String seenTime = (m.timestamp != null && m.timestamp > 0) ? formatTime(m.timestamp) : "";
            final String thumbUrl = isReelSeen
                    ? (m.reelThumbUrl != null ? m.reelThumbUrl : "")
                    : (m.statusThumbUrl != null ? m.statusThumbUrl : "");
            final boolean hasThumb = !thumbUrl.isEmpty();
            final String senderNameForSeen = (isGroup && m.senderName != null && !m.senderName.isEmpty())
                    ? m.senderName : null;
            // Batched reel-seen bubbles carry their display text directly in
            // m.text (e.g. "Watched 5 of your reels" — see ReelSeenTracker).
            // status_seen and legacy single-reel rows leave m.text as the
            // plain default, so we only pass it through as an override for
            // reel_seen — everything else keeps the static label.
            final String seenLabelOverride = (isReelSeen && m.text != null && !m.text.isEmpty())
                    ? m.text : null;
            cv.bindSeenBubble(isReelSeen, null, null, hasThumb, senderNameForSeen, seenTime, seenLabelOverride);
            cv.setDeletedStyle(false);

            final String avatarUrl = m.senderPhoto != null ? m.senderPhoto : "";
            if (!avatarUrl.isEmpty()) {
                // PERF ADV: check shared avatar pool first — same sender appears in
                // every message in a group chat; without the pool each cell triggers
                // a separate Glide decode of the identical URL.
                android.graphics.Bitmap avatarHit = AVATAR_BITMAP_CACHE.get(avatarUrl);
                if (avatarHit != null && !avatarHit.isRecycled()) {
                    dashboardRecordHit(ctx, avatarUrl);
                    cv.setSeenAvatarBitmap(avatarHit);
                } else {
                    // PERF: decode at the real 36dp display size (see
                    // seenAvatarPx()) instead of a hardcoded 96×96 square.
                    int avatarPx = seenAvatarPx(ctx);
                    glide(ctx).asBitmap().load(avatarUrl).apply(THUMB_RGB565)
                            .override(avatarPx, avatarPx).circleCrop()
                            .listener(com.callx.app.cache.CacheDashboardStats.glideListener(
                                    ctx, avatarUrl))
                            .into(new com.bumptech.glide.request.target.CustomTarget<Bitmap>() {
                                @Override
                                public void onResourceReady(@NonNull Bitmap resource,
                                        @Nullable com.bumptech.glide.request.transition.Transition<? super Bitmap> transition) {
                                    AVATAR_BITMAP_CACHE.put(avatarUrl, resource);
                                    dashboardRecordDecoded(ctx, avatarUrl, resource);
                                    if (h.canvasBindToken != myToken) return;
                                    cv.setSeenAvatarBitmap(resource);
                                }
                                @Override
                                public void onLoadCleared(@Nullable android.graphics.drawable.Drawable placeholder) {
                                    if (h.canvasBindToken != myToken) return;
                                    cv.setSeenAvatarBitmap(null);
                                }
                            });
                }
            }
            if (hasThumb) {
                // FIX: same root cause as the reel-share 330×474 thumb flicker —
                // no cache check meant EVERY bind (including a plain scroll-recycle,
                // and every rebind of an on-screen seen-bubble row that a new
                // message send/receive elsewhere in the list triggers) unconditionally
                // re-fired an async Glide load, guaranteeing a blank/junk frame on
                // this thumbnail before the image popped back in. Now checks
                // DECODED_BITMAP_CACHE synchronously first, same as reel-share/reply/video.
                // PERF: decode at the real 120×80dp display size (see
                // seenThumbPxW/H()) instead of a hardcoded 240×240 square —
                // that was 2x-oversized on width and ~3x-oversized on
                // height versus the actual non-square thumb slot.
                final int seenThumbPxW = seenThumbPxW(ctx);
                final int seenThumbPxH = seenThumbPxH(ctx);
                android.graphics.Bitmap seenThumbHit = DECODED_BITMAP_CACHE.get(poolKey(thumbUrl, seenThumbPxW, seenThumbPxH));
                if (seenThumbHit != null && !seenThumbHit.isRecycled()) {
                    dashboardRecordHit(ctx, thumbUrl);
                    cv.setSeenThumbBitmap(seenThumbHit);
                } else {
                    glide(ctx).asBitmap().load(thumbUrl).apply(THUMB_RGB565)
                            .override(seenThumbPxW, seenThumbPxH).centerCrop()
                            .listener(com.callx.app.cache.CacheDashboardStats.glideListener(
                                    ctx, thumbUrl))
                            .into(new com.bumptech.glide.request.target.CustomTarget<Bitmap>() {
                                @Override
                                public void onResourceReady(@NonNull Bitmap resource,
                                        @Nullable com.bumptech.glide.request.transition.Transition<? super Bitmap> transition) {
                                    DECODED_BITMAP_CACHE.put(poolKey(thumbUrl, seenThumbPxW, seenThumbPxH), resource);
                                    dashboardRecordDecoded(ctx, thumbUrl, resource);
                                    if (h.canvasBindToken != myToken) return;
                                    cv.setSeenThumbBitmap(resource);
                                }
                                @Override
                                public void onLoadCleared(@Nullable android.graphics.drawable.Drawable placeholder) {
                                    if (h.canvasBindToken != myToken) return;
                                    cv.setSeenThumbBitmap(null);
                                }
                            });
                }
            }
        } else if (isCallEntry) {
            // Mirrors the legacy bindCallEntryBubble() exactly — same
            // icon/label/color/alignment logic, just pushed through
            // cv.bindCallEntry() instead of tv_call_entry_icon/label/time
            // + ll_call_entry_pill's gravity flip.
            boolean isVideoCall = "video".equals(m.fileName);
            boolean isMissed    = "missed".equals(m.text);
            String icon = isVideoCall ? "\uD83D\uDCF9" : "\uD83D\uDCDE";
            String label;
            int labelColor;
            if (isMissed) {
                if (sent) {
                    label = isVideoCall ? "No answer (video)" : "No answer";
                } else {
                    label = isVideoCall ? "Missed video call" : "Missed call";
                }
                labelColor = 0xFFFF5555;
            } else {
                String durStr = "";
                if (m.duration != null && m.duration > 0) {
                    long sec = m.duration / 1000;
                    durStr = " \u2022 " + String.format(java.util.Locale.getDefault(), "%d:%02d", sec / 60, sec % 60);
                }
                if (sent) {
                    label = isVideoCall ? ("Video call" + durStr) : ("Audio call" + durStr);
                } else {
                    label = isVideoCall ? ("Incoming video call" + durStr) : ("Incoming call" + durStr);
                }
                labelColor = 0xFFFFFFFF;
            }
            String callTime = (m.timestamp != null && m.timestamp > 0) ? formatTime(m.timestamp) : "";
            cv.bindCallEntry(icon, label, labelColor, callTime, sent);
            cv.setDeletedStyle(false);
        } else if (isMultiMedia) {
            final java.util.List<java.util.Map<String, Object>> items = m.mediaItems;
            final int total = items != null ? items.size() : 0;
            java.util.List<com.callx.app.conversation.canvas.GridItem> gridItems =
                    new java.util.ArrayList<>();
            int visible = Math.min(total, 9);
            for (int i = 0; i < visible; i++) {
                java.util.Map<String, Object> item = items.get(i);
                Object mtObj = item.get("mediaType");
                String mt = mtObj instanceof String ? (String) mtObj : "";
                boolean isVideoCell = "video".equals(mt);
                boolean isAudioCell = "audio".equals(mt);
                boolean isFileCell  = "file".equals(mt);
                Object durObj = item.get("duration");
                String dur = durObj instanceof String ? (String) durObj : null;
                Object capObj = item.get("caption");
                String cap = capObj instanceof String ? (String) capObj : null;
                // Label under the glyph for audio/file cells — audio
                // duration (falls back to "Audio"), or file name (falls
                // back to "File") — mirrors MediaGroupLayoutHelper.buildCell().
                String cellLabel = null;
                if (isAudioCell) {
                    cellLabel = (dur == null || dur.isEmpty()) ? "Audio" : dur;
                } else if (isFileCell) {
                    Object fnObj = item.get("fileName");
                    String fn = fnObj instanceof String ? (String) fnObj : null;
                    cellLabel = (fn == null || fn.isEmpty()) ? "File" : fn;
                }
                gridItems.add(new com.callx.app.conversation.canvas.GridItem(
                        isVideoCell, isAudioCell, isFileCell, dur, cap, cellLabel));
            }
            cv.bindMediaGroup(gridItems, m.caption, timeStr, sent, isRead, isDelivered);
            cv.setDeletedStyle(false); // clears any italic/dim state a recycled view carried from a deleted message
            wireCaptionReadMore(h, cv, m.messageId); // caption read-more/read-less

            // Per-cell thumbnail load, plus (received-only) the manual
            // download-gate flagging — mirrors MediaGroupLayoutHelper's
            // buildCell(): an already-cached image cell loads its sharp
            // local copy straight away with no gate; an un-cached one
            // shows a lightweight thumb and gets marked pending so
            // setGroupDownloadGate() can put up the master pill.
            boolean[] cellPending = new boolean[visible];
            for (int i = 0; i < visible; i++) {
                java.util.Map<String, Object> item = items.get(i);
                Object urlObj = item.get("url");
                Object thumbObj = item.get("thumbUrl");
                Object mtObj = item.get("mediaType");
                String cellUrl = urlObj instanceof String ? (String) urlObj : "";
                String cellThumb = thumbObj instanceof String ? (String) thumbObj : "";
                String mediaType = mtObj instanceof String ? (String) mtObj : "image";
                boolean isImageCell = "image".equals(mediaType);
                boolean isAudioOrFileCell = "audio".equals(mediaType) || "file".equals(mediaType);
                final int cellIndex = i;

                // Audio/file cells have no thumbnail — MediaGroupRenderer
                // draws an icon+label placeholder for them directly, so skip
                // the Glide load entirely and never mark them pending (no
                // manual download gate for these, same as the legacy
                // MediaGroupLayoutHelper.buildCell() isAudio||isFile branch,
                // which never wires a download overlay for those cells).
                if (isAudioOrFileCell) continue;

                java.io.File cachedFile = (!sent && isImageCell && !cellUrl.isEmpty())
                        ? getCachedFileFast(ctx, cellUrl) : null;

                String loadUrl;
                if (cachedFile != null) {
                    loadUrl = null; // loaded from the local File below instead
                } else if (!cellThumb.isEmpty()) {
                    loadUrl = cellThumb;
                } else if (!sent && isImageCell && !cellUrl.isEmpty()) {
                    // No thumbUrl on a received image — same fallback
                    // MediaGroupLayoutHelper uses: a derived low-res
                    // Cloudinary transform instead of the raw full url.
                    loadUrl = com.callx.app.utils.CloudinaryUploader.deriveThumbUrl(cellUrl, 200);
                    cellPending[i] = true;
                } else {
                    loadUrl = cellUrl;
                }
                if (!sent && isImageCell && cachedFile == null && !cellUrl.isEmpty() && !cellThumb.isEmpty()) {
                    cellPending[i] = true; // has a thumb to show, but full-res still needs downloading
                }

                if (cachedFile != null) {
                    // FIX: same flicker root cause as reel-share/seen-bubble —
                    // grid cells had zero cache check, so every rebind of a
                    // media-group row (scroll, or a new message elsewhere
                    // triggering a rebind of this visible row) blanked every
                    // cell in the grid for a frame before Glide redecoded it.
                    String cellPoolKey = cachedFile.getAbsolutePath();
                    android.graphics.Bitmap cellHit = DECODED_BITMAP_CACHE.get(cellPoolKey);
                    if (cellHit != null && !cellHit.isRecycled()) {
                        cv.setMediaGroupBitmap(cellIndex, cellHit);
                    } else {
                        glide(ctx).asBitmap().load(cachedFile).apply(THUMB_RGB565).override(240, 240)
                                .into(new com.bumptech.glide.request.target.CustomTarget<Bitmap>() {
                                    @Override
                                    public void onResourceReady(@NonNull Bitmap resource,
                                            @Nullable com.bumptech.glide.request.transition.Transition<? super Bitmap> transition) {
                                        DECODED_BITMAP_CACHE.put(cellPoolKey, resource);
                                        if (h.canvasBindToken != myToken) return;
                                        cv.setMediaGroupBitmap(cellIndex, resource);
                                    }
                                    @Override
                                    public void onLoadCleared(@Nullable android.graphics.drawable.Drawable placeholder) {
                                        if (h.canvasBindToken != myToken) return;
                                        cv.setMediaGroupBitmap(cellIndex, null);
                                    }
                                });
                    }
                } else if (loadUrl != null && !loadUrl.isEmpty()) {
                    final String finalLoadUrl = loadUrl;
                    android.graphics.Bitmap cellHit = DECODED_BITMAP_CACHE.get(poolKey(finalLoadUrl, 240, 240));
                    if (cellHit != null && !cellHit.isRecycled()) {
                        cv.setMediaGroupBitmap(cellIndex, cellHit);
                    } else {
                        glide(ctx).asBitmap().load(loadUrl).apply(THUMB_RGB565).override(240, 240)
                                .into(new com.bumptech.glide.request.target.CustomTarget<Bitmap>() {
                                    @Override
                                    public void onResourceReady(@NonNull Bitmap resource,
                                            @Nullable com.bumptech.glide.request.transition.Transition<? super Bitmap> transition) {
                                        DECODED_BITMAP_CACHE.put(poolKey(finalLoadUrl, 240, 240), resource);
                                        if (h.canvasBindToken != myToken) return;
                                        cv.setMediaGroupBitmap(cellIndex, resource);
                                    }
                                    @Override
                                    public void onLoadCleared(@Nullable android.graphics.drawable.Drawable placeholder) {
                                        if (h.canvasBindToken != myToken) return;
                                        cv.setMediaGroupBitmap(cellIndex, null);
                                    }
                                });
                    }
                }
            }
            // For sent groups this is an all-false array (gate stays inert,
            // unchanged behavior); for received groups it arms the master
            // "Download N photos" pill iff at least one cell is pending.
            cv.setGroupDownloadGate(cellPending);
        } else if (isImage) {
            final String fullUrl = m.mediaUrl != null ? m.mediaUrl : m.text;
            // Known width/height captured at send time (see ChatMediaController)
            // beats waiting for Glide to decode — sizes the bubble correctly on
            // the very first layout pass even for images never seen before.
            float knownRatio = (m.mediaWidth != null && m.mediaHeight != null
                    && m.mediaWidth > 0 && m.mediaHeight > 0)
                    ? (float) m.mediaWidth / m.mediaHeight : 0f;
            cv.bindMedia(null, m.caption, timeStr, sent, isRead, isDelivered, fullUrl, knownRatio);
            cv.setDeletedStyle(false); // clears any italic/dim state a recycled view carried from a deleted message
            wireCaptionReadMore(h, cv, m.messageId); // caption read-more/read-less

            // WhatsApp-style local-first media bubble: this SENT image was
            // just picked and is still uploading (or its upload failed) —
            // see ChatMediaController#uploadAndSend()'s insertLocalPendingMedia()
            // call. Neither case has a real mediaUrl yet, so render straight
            // from the local file the user picked, with an upload spinner /
            // tap-to-retry gate, instead of falling through to the normal
            // Glide-from-remote-URL path below.
            boolean localPendingMedia = sent && m.mediaLocalPath != null && !m.mediaLocalPath.isEmpty()
                    && (fullUrl == null || fullUrl.isEmpty());
            if (localPendingMedia) {
                cv.clearMediaDownloadGate();
                glide(ctx).asBitmap()
                        .load(android.net.Uri.parse(m.mediaLocalPath))
                        .apply(THUMB_RGB565)
                        .override(thumbPx(ctx), thumbPx(ctx))
                        .into(new com.bumptech.glide.request.target.CustomTarget<Bitmap>() {
                            @Override public void onResourceReady(@NonNull Bitmap resource,
                                    @Nullable com.bumptech.glide.request.transition.Transition<? super Bitmap> transition) {
                                if (h.canvasBindToken != myToken) return;
                                cv.setMediaBitmap(resource);
                            }
                            @Override public void onLoadCleared(@Nullable android.graphics.drawable.Drawable placeholder) {
                                if (h.canvasBindToken != myToken) return;
                                cv.setMediaBitmap(null);
                            }
                        });

                String mid = m.messageId != null ? m.messageId : m.id;
                if ("failed".equals(m.status)) {
                    uploadProgressTracker.clear(mid);
                    cv.setMediaDownloadGate(false, Integer.MIN_VALUE, "Tap to retry");
                } else {
                    // Reuses the RECEIVED-side download spinner ring for the
                    // SENT-side upload — same visual language, opposite
                    // direction. Restores the last known % if this row was
                    // scrolled off-screen and back mid-upload.
                    int trackedPercent = uploadProgressTracker.getProgress(mid);
                    cv.setMediaDownloadGate(true, trackedPercent, null);
                }
            } else {
            // Mirrors bindDownloadOverlay(): sent images (and any received
            // image already local) load straight away; a not-yet-cached
            // RECEIVED image shows the manual download gate instead (idle
            // pill, or a live spinner/percentage if a download from an
            // earlier bind is still in flight) until the person taps it.
            java.io.File cachedFile = (!sent && fullUrl != null && !fullUrl.isEmpty())
                    ? com.callx.app.utils.MediaCache.getCached(ctx, fullUrl) : null;

            // WhatsApp-style local-first render: a SENT image whose original
            // local file is still on the phone renders straight from it —
            // full quality, no network — instead of the (possibly
            // compressed) Cloudinary mediaUrl. The instant the user deletes
            // it from their device this naturally falls back to fullUrl,
            // since the cache entry gets recomputed on the next fresh path.
            // Availability is checked off the main thread (see
            // checkLocalAvailabilityAsync) so this never blocks a bind.
            String mid0 = m.messageId != null ? m.messageId : m.id;
            boolean useLocalSent = sent && m.mediaLocalPath != null && !m.mediaLocalPath.isEmpty()
                    && Boolean.TRUE.equals(checkLocalAvailabilityAsync(ctx, m.mediaLocalPath, mid0));

            if (sent || cachedFile != null) {
                cv.clearMediaDownloadGate();
                Object loadSrc = useLocalSent ? android.net.Uri.parse(m.mediaLocalPath)
                        : (cachedFile != null ? cachedFile : fullUrl);
                if (loadSrc != null) {
                    // PERF #1: check in-memory Bitmap pool before firing a Glide decode.
                    // BUG FIX: this used to always key the pool by fullUrl, even when
                    // useLocalSent was true — so a SENT bubble whose original file is
                    // still on the phone could get served a stale bitmap that some
                    // earlier bind had decoded from the (possibly compressed) remote
                    // URL, silently breaking the "local-first, full quality, as long
                    // as it's on the device" guarantee above. Local-first renders now
                    // get their own pool key (the local path) so they never collide
                    // with — or get shadowed by — remote-keyed pool entries.
                    final String poolKey = useLocalSent ? m.mediaLocalPath
                            : (fullUrl != null ? fullUrl : "");
                    android.graphics.Bitmap poolHit = poolKey.isEmpty() ? null
                            : DECODED_BITMAP_CACHE.get(poolKey);
                    if (poolHit != null && !poolHit.isRecycled()) {
                        if (poolHit.getHeight() > 0) {
                            com.callx.app.conversation.canvas.MessageBubbleCanvasView
                                    .cacheAspectRatio(fullUrl, (float) poolHit.getWidth() / poolHit.getHeight());
                        }
                        dashboardRecordHit(ctx, poolKey);
                        cv.setMediaBitmap(poolHit);
                    } else {
                    // PERF #4: use density-aware thumb size instead of hard-coded 480px
                    glide(ctx).asBitmap()
                            .load(loadSrc)
                            .apply(THUMB_RGB565)
                            .override(thumbPx(ctx), thumbPx(ctx))
                            .into(new com.bumptech.glide.request.target.CustomTarget<Bitmap>() {
                                @Override
                                public void onResourceReady(@NonNull Bitmap resource,
                                        @Nullable com.bumptech.glide.request.transition.Transition<? super Bitmap> transition) {
                                    // Record the real aspect ratio regardless of
                                    // whether this holder still shows this image —
                                    // fast scrolling recycles/rebinds the view long
                                    // before Glide's callback fires, and without this
                                    // unconditional write the square-placeholder flash
                                    // was repeating on every single scroll-past
                                    // instead of only the image's very first view.
                                    if (resource.getHeight() > 0) {
                                        com.callx.app.conversation.canvas.MessageBubbleCanvasView
                                                .cacheAspectRatio(fullUrl, (float) resource.getWidth() / resource.getHeight());
                                    }
                                    // PERF #1: store decoded bitmap in pool for scroll-back reuse —
                                    // under the same source-aware key it was looked up with above,
                                    // so a local-first decode never gets stored under (and later
                                    // handed out for) the plain remote-URL key.
                                    if (!poolKey.isEmpty()) {
                                        DECODED_BITMAP_CACHE.put(poolKey, resource);
                                        dashboardRecordDecoded(ctx, poolKey, resource);
                                    }
                                    if (h.canvasBindToken != myToken) return; // holder recycled/rebound since this load started
                                    cv.setMediaBitmap(resource);
                                }
                                @Override
                                public void onLoadCleared(@Nullable android.graphics.drawable.Drawable placeholder) {
                                    if (h.canvasBindToken != myToken) return;
                                    cv.setMediaBitmap(null);
                                }
                            });
                    }
                }
            } else if (fullUrl != null && !fullUrl.isEmpty()) {
                // ── BlurHash placeholder: show a blurred color preview the instant
                // the bubble appears, before any network download starts. ─────────
                // Media E2E (image): m.blurHash is intentionally left blank on
                // these messages (see ChatMediaController / Message#mediaKeyEnc)
                // — the placeholder string instead travels inside the encrypted
                // key envelope, so decrypt it here rather than reading m.blurHash.
                String blurHash = m.blurHash;
                byte[] iMediaKey = null;
                byte[] iInlineThumbPlain = null; // decrypted inline-thumb bytes, if this envelope embeds one
                if (!sent && m.mediaKeyEnc != null) {
                    com.callx.app.utils.MediaE2ECrypto.KeyEnvelope env =
                            com.callx.app.utils.MediaE2ECrypto.decryptEnvelopeForMessage(ctx, m.mediaKeyEnc,
                                    m.senderId, (m.messageId != null ? m.messageId : m.id));
                    blurHash = (env != null) ? env.blurHash : null;
                    // Media E2E v2: the thumbnail ciphertext is encrypted with
                    // an HKDF-derived subkey distinct from the full-res key
                    // (see MediaE2ECrypto) — env.thumbKey() picks that subkey
                    // for v2 envelopes, or falls back to the raw key for
                    // legacy v1 messages that predate this derivation.
                    iMediaKey = (env != null) ? env.thumbKey() : null;
                    // WhatsApp-style inline thumbnail: if the sender embedded
                    // the thumb directly in the envelope (see
                    // ChatMediaController / MediaE2ECrypto#shouldInlineThumb),
                    // decrypt it right here in memory — no network round trip,
                    // no separate Cloudinary thumb blob at all.
                    if (env != null && env.inlineThumbCipher != null) {
                        iInlineThumbPlain = env.decryptInlineThumb();
                    }
                }
                if (blurHash != null && !blurHash.isEmpty()) {
                    android.graphics.Bitmap placeholder = BlurHashPlaceholder.get(blurHash, 32, 32);
                    if (placeholder != null) cv.setMediaBitmap(placeholder);
                }

                // ── WhatsApp-style low-quality thumbnail (receiver side) ───────────
                // Load thumbnailUrl from Cloudinary immediately so the receiver sees a
                // real (compressed) preview the instant the bubble appears — before the
                // full-res download auto-starts or the user taps to download.
                // Mirrors the isVideo block exactly: BlurHash shows first (~1 ms,
                // synchronous), then this Glide load upgrades it to the real low-res
                // frame as soon as the network responds. When the full download
                // completes, onReady() calls setMediaBitmap() again with the hi-res
                // bitmap, so the user always gets the best available quality.
                final String iThumbUrl = (m.thumbnailUrl != null && !m.thumbnailUrl.isEmpty())
                        ? m.thumbnailUrl : null;
                if (iInlineThumbPlain != null && iInlineThumbPlain.length > 0) {
                    // Inline thumb: decode straight from the already-decrypted
                    // in-memory bytes — instant, no MediaCache/network call at all.
                    final String iInlinePoolKey = "inline:" + (m.messageId != null ? m.messageId : m.id);
                    android.graphics.Bitmap iInlinePoolHit = DECODED_BITMAP_CACHE.get(iInlinePoolKey);
                    if (iInlinePoolHit != null && !iInlinePoolHit.isRecycled()) {
                        if (iInlinePoolHit.getHeight() > 0) {
                            com.callx.app.conversation.canvas.MessageBubbleCanvasView.cacheAspectRatio(
                                    iInlinePoolKey, (float) iInlinePoolHit.getWidth() / iInlinePoolHit.getHeight());
                        }
                        cv.setMediaBitmap(iInlinePoolHit);
                    } else {
                        android.graphics.Bitmap decoded = android.graphics.BitmapFactory
                                .decodeByteArray(iInlineThumbPlain, 0, iInlineThumbPlain.length);
                        if (decoded != null) {
                            if (decoded.getHeight() > 0) {
                                com.callx.app.conversation.canvas.MessageBubbleCanvasView.cacheAspectRatio(
                                        iInlinePoolKey, (float) decoded.getWidth() / decoded.getHeight());
                            }
                            DECODED_BITMAP_CACHE.put(iInlinePoolKey, decoded);
                            cv.setMediaBitmap(decoded);
                        }
                        // decode failure (corrupted/tampered inline thumb) — BlurHash placeholder stays up
                    }
                } else if (iThumbUrl != null) {
                    final String iThumbPoolKey = iThumbUrl;
                    android.graphics.Bitmap iThumbPoolHit = DECODED_BITMAP_CACHE.get(iThumbPoolKey);
                    if (iThumbPoolHit != null && !iThumbPoolHit.isRecycled()) {
                        // Cache hit — show instantly with no Glide overhead.
                        if (iThumbPoolHit.getHeight() > 0) {
                            com.callx.app.conversation.canvas.MessageBubbleCanvasView
                                    .cacheAspectRatio(iThumbUrl, (float) iThumbPoolHit.getWidth() / iThumbPoolHit.getHeight());
                        }
                        cv.setMediaBitmap(iThumbPoolHit);
                    } else if (iMediaKey != null) {
                        // Media E2E (image): m.thumbnailUrl is ciphertext for
                        // these messages (uploaded as resource_type=raw) —
                        // Glide can't decode it directly. Go through the same
                        // decrypting MediaCache path as the full image so the
                        // low-res preview still works instead of silently
                        // failing to load.
                        final String iThumbUrlF = iThumbUrl;
                        com.callx.app.utils.MediaCache.get(ctx, iThumbUrl, iMediaKey,
                                new com.callx.app.utils.MediaCache.Callback() {
                            @Override public void onReady(java.io.File file) {
                                if (h.canvasBindToken != myToken) return;
                                glide(ctx).asBitmap().load(file).apply(THUMB_RGB565)
                                        .override(thumbPx(ctx), thumbPx(ctx))
                                        .into(new com.bumptech.glide.request.target.CustomTarget<android.graphics.Bitmap>() {
                                            @Override public void onResourceReady(@NonNull android.graphics.Bitmap resource,
                                                    @Nullable com.bumptech.glide.request.transition.Transition<? super android.graphics.Bitmap> t) {
                                                if (resource.getHeight() > 0) {
                                                    com.callx.app.conversation.canvas.MessageBubbleCanvasView
                                                            .cacheAspectRatio(iThumbUrlF, (float) resource.getWidth() / resource.getHeight());
                                                }
                                                DECODED_BITMAP_CACHE.put(iThumbUrlF, resource);
                                                if (h.canvasBindToken != myToken) return;
                                                cv.setMediaBitmap(resource);
                                            }
                                            @Override public void onLoadCleared(@Nullable android.graphics.drawable.Drawable p) { }
                                        });
                            }
                            @Override public void onError(String reason) { /* blurHash placeholder stays up */ }
                        });
                    } else {
                        // Cache miss — fire a lightweight Glide decode of the Cloudinary
                        // thumbnail URL. thumbnail(0.1f) lets Glide show a placeholder at
                        // 10 % of the requested size almost instantly while the full thumb
                        // loads, so there’s zero blank-frame window.
                        glide(ctx).asBitmap()
                                .load(iThumbUrl)
                                .apply(THUMB_RGB565)
                                .thumbnail(0.1f)
                                .override(thumbPx(ctx), thumbPx(ctx))
                                .into(new com.bumptech.glide.request.target.CustomTarget<android.graphics.Bitmap>() {
                                    @Override
                                    public void onResourceReady(@NonNull android.graphics.Bitmap resource,
                                            @Nullable com.bumptech.glide.request.transition.Transition<? super android.graphics.Bitmap> transition) {
                                        if (resource.getHeight() > 0) {
                                            com.callx.app.conversation.canvas.MessageBubbleCanvasView
                                                    .cacheAspectRatio(iThumbUrl, (float) resource.getWidth() / resource.getHeight());
                                        }
                                        // Cache so scroll-back is instant.
                                        DECODED_BITMAP_CACHE.put(iThumbPoolKey, resource);
                                        if (h.canvasBindToken != myToken) return;
                                        // Only replace the bitmap if the full-res hasn’t
                                        // already loaded (full download calls setMediaBitmap
                                        // and clearMediaDownloadGate; checking the gate state
                                        // would race, so we unconditionally set here — the
                                        // full-res load always fires after this callback and
                                        // will overwrite with higher quality).
                                        cv.setMediaBitmap(resource);
                                    }
                                    @Override
                                    public void onLoadCleared(@Nullable android.graphics.drawable.Drawable p) {
                                        // Intentional no-op: clearing here would cause a flash
                                        // back to the BlurHash while the full download is still
                                        // in progress. The full-res onReady() handles the final
                                        // bitmap transition.
                                    }
                                });
                    }
                }

                boolean isDownloading = downloadingMediaUrls.contains(fullUrl);
                if (isDownloading) {
                    cv.setMediaDownloadGate(true, -1, null);
                } else if (MediaAutoDownloadPolicy.shouldAutoDownload(ctx, "image")) {
                    // ── Auto-download on WiFi (or per user policy) ─────────────
                    // Enqueue through the network-aware receiver download queue so
                    // we cap at 3 concurrent downloads even when many images are
                    // visible at once.
                    downloadingMediaUrls.add(fullUrl);
                    cv.setMediaDownloadGate(true, 0, null);
                    final String capturedUrl = fullUrl;
                    // Media E2E (image): resolve the AES key up front (cheap —
                    // E2EEncryptionManager caches the ratchet-decrypt result per
                    // message) so the auto-download below decrypts as it writes
                    // to the disk cache. Null (and thus a no-op here) for a
                    // received image that predates this feature, or any other
                    // media type — MediaCache.getWithProgress falls back to its
                    // old plaintext behavior when decryptKey is null.
                    // Media E2E v2: resolve the full envelope once so we get
                    // both the derived full-purpose key AND its ciphertext
                    // digest (WhatsApp-style file-hash check — see
                    // MediaE2ECrypto / MediaCache#getWithProgress). null env
                    // fields (legacy v1 message, or no digest present) just
                    // mean the digest check is skipped for this download.
                    final com.callx.app.utils.MediaE2ECrypto.KeyEnvelope autoDlEnv =
                            (!sent && m.mediaKeyEnc != null)
                            ? com.callx.app.utils.MediaE2ECrypto.decryptEnvelopeForMessage(ctx, m.mediaKeyEnc,
                                    m.senderId, m.messageId != null ? m.messageId : m.id)
                            : null;
                    final byte[] autoDlKey    = (autoDlEnv != null) ? autoDlEnv.fullKey() : null;
                    final byte[] autoDlDigest = (autoDlEnv != null) ? autoDlEnv.fullDigest : null;
                    MediaDownloadQueue.getInstance(ctx).enqueue(capturedUrl, null, () -> {
                        com.callx.app.utils.MediaCache.getWithProgress(ctx, capturedUrl, autoDlKey, autoDlDigest,
                                new com.callx.app.utils.MediaCache.ProgressCallback() {
                            @Override public void onProgress(int percent) {
                                if (h.canvasBindToken != myToken) return;
                                cv.setMediaDownloadGate(true, percent, null);
                            }
                            @Override public void onReady(java.io.File file) {
                                downloadingMediaUrls.remove(capturedUrl);
                                if (h.canvasBindToken != myToken) return;
                                cv.clearMediaDownloadGate();
                                android.graphics.Bitmap poolHit =
                                        DECODED_BITMAP_CACHE.get(capturedUrl);
                                if (poolHit != null && !poolHit.isRecycled()) {
                                    cv.setMediaBitmap(poolHit);
                                } else {
                                    glide(ctx).asBitmap().load(file).apply(THUMB_RGB565)
                                            .override(thumbPx(ctx), thumbPx(ctx))
                                            .into(new com.bumptech.glide.request.target.CustomTarget<android.graphics.Bitmap>() {
                                        @Override public void onResourceReady(@NonNull android.graphics.Bitmap resource,
                                                @Nullable com.bumptech.glide.request.transition.Transition<? super android.graphics.Bitmap> t) {
                                            DECODED_BITMAP_CACHE.put(capturedUrl, resource);
                                            if (h.canvasBindToken != myToken) return;
                                            cv.setMediaBitmap(resource);
                                        }
                                        @Override public void onLoadCleared(@Nullable android.graphics.drawable.Drawable p) {
                                            if (h.canvasBindToken != myToken) return;
                                            cv.setMediaBitmap(null);
                                        }
                                    });
                                }
                            }
                            @Override public void onError(String err) {
                                downloadingMediaUrls.remove(capturedUrl);
                                if (h.canvasBindToken != myToken) return;
                                cv.setMediaDownloadGate(false, Integer.MIN_VALUE,
                                        "Tap to download");
                            }
                        });
                    });
                } else {
                    // Manual download: show size label on the idle pill.
                    cv.setMediaDownloadGate(false, Integer.MIN_VALUE, "Photo");
                    com.callx.app.utils.MediaCache.getRemoteSize(ctx, fullUrl,
                            new com.callx.app.utils.MediaCache.SizeCallback() {
                        @Override public void onSize(long bytes) {
                            if (h.canvasBindToken != myToken) return;
                            if (!downloadingMediaUrls.contains(fullUrl)) {
                                cv.setMediaDownloadGate(false, Integer.MIN_VALUE,
                                        formatFileSize(bytes));
                            }
                        }
                        @Override public void onError(String reason) { /* keep "Photo" label */ }
                    });
                }
            } else {
                cv.clearMediaDownloadGate();
            }
            }
        } else if (isReelShare) {
            // Mirrors the legacy ViewHolder's "reel_share"/"reel_link"
            // case (bindReelShareBubble) — same in-memory caches
            // (reelOwnerAvatarCache/reelThumbCache) and Firebase
            // "reels/{id}" fallback, just pushed through the canvas
            // setters instead of ImageView/TextView calls.
            final String rUsername = m.reelShareUsername != null ? m.reelShareUsername : "";
            cv.bindReelShare(null, null, rUsername.isEmpty() ? null : rUsername,
                    m.reelShareCaption, timeStr, sent, isRead, isDelivered);
            cv.setDeletedStyle(false);

            // Avatar
            String avatarUrl = m.reelShareOwnerPhoto != null ? m.reelShareOwnerPhoto : "";
            if (avatarUrl.isEmpty() && !rUsername.isEmpty()) {
                String cachedAvatar = reelOwnerAvatarCache.get(rUsername);
                if (cachedAvatar != null) avatarUrl = cachedAvatar;
            }
            if (!avatarUrl.isEmpty()) {
                // PERF ADV: check shared avatar pool — reel-share owners repeat
                final String finalAvatarUrl = avatarUrl;
                android.graphics.Bitmap reelAvatarHit = AVATAR_BITMAP_CACHE.get(finalAvatarUrl);
                if (reelAvatarHit != null && !reelAvatarHit.isRecycled()) {
                    dashboardRecordHit(ctx, finalAvatarUrl);
                    cv.setReelShareAvatarBitmap(reelAvatarHit);
                } else {
                glide(ctx).asBitmap().load(finalAvatarUrl).apply(THUMB_RGB565).override(96, 96).circleCrop()
                        .listener(com.callx.app.cache.CacheDashboardStats.glideListener(
                                ctx, finalAvatarUrl))
                        .into(new com.bumptech.glide.request.target.CustomTarget<Bitmap>() {
                            @Override
                            public void onResourceReady(@NonNull Bitmap resource,
                                    @Nullable com.bumptech.glide.request.transition.Transition<? super Bitmap> transition) {
                                AVATAR_BITMAP_CACHE.put(finalAvatarUrl, resource);
                                dashboardRecordDecoded(ctx, finalAvatarUrl, resource);
                                if (h.canvasBindToken != myToken) return;
                                cv.setReelShareAvatarBitmap(resource);
                            }
                            @Override
                            public void onLoadCleared(@Nullable android.graphics.drawable.Drawable placeholder) {}
                        });
                }
            } else if (!rUsername.isEmpty() && reelAvatarFetchInFlight.add(rUsername)) {
                final String fUKey = rUsername;
                final android.content.Context fCtxA = ctx.getApplicationContext();
                com.google.firebase.database.FirebaseDatabase.getInstance()
                        .getReference("users").orderByChild("username").equalTo(rUsername).limitToFirst(1)
                        .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                            @Override public void onDataChange(@androidx.annotation.NonNull com.google.firebase.database.DataSnapshot snap) {
                                reelAvatarFetchInFlight.remove(fUKey);
                                if (!snap.exists() || h.canvasBindToken != myToken) return;
                                String photo = null;
                                for (com.google.firebase.database.DataSnapshot child : snap.getChildren()) {
                                    photo = child.child("profileImage").getValue(String.class);
                                    if (photo == null || photo.isEmpty()) photo = child.child("photoUrl").getValue(String.class);
                                    if (photo == null || photo.isEmpty()) photo = child.child("profilePhoto").getValue(String.class);
                                    break;
                                }
                                if (photo == null || photo.isEmpty()) return;
                                reelOwnerAvatarCache.put(fUKey, photo);
                                glide(fCtxA).asBitmap().load(photo).apply(THUMB_RGB565).override(96, 96).circleCrop()
                                        .into(new com.bumptech.glide.request.target.CustomTarget<Bitmap>() {
                                            @Override
                                            public void onResourceReady(@NonNull Bitmap resource,
                                                    @Nullable com.bumptech.glide.request.transition.Transition<? super Bitmap> transition) {
                                                if (h.canvasBindToken != myToken) return;
                                                cv.setReelShareAvatarBitmap(resource);
                                            }
                                            @Override
                                            public void onLoadCleared(@Nullable android.graphics.drawable.Drawable placeholder) {}
                                        });
                            }
                            @Override public void onCancelled(@androidx.annotation.NonNull com.google.firebase.database.DatabaseError e) {
                                reelAvatarFetchInFlight.remove(fUKey);
                            }
                        });
            }

            // Thumbnail
            // FIX: unlike the avatar block right above (which checks
            // AVATAR_BITMAP_CACHE synchronously first), this thumbnail had
            // NO cache check at all — every single bind (including a plain
            // scroll-recycle, and every rebind of an on-screen row that a
            // new message insert elsewhere triggers) unconditionally
            // re-fired an async Glide load, guaranteeing at least one blank
            // frame on this big 330×474 card before the image popped back
            // in. Same root cause as the reply/status-reply thumb flicker —
            // now fixed the same way: check DECODED_BITMAP_CACHE first.
            String thumb = m.reelShareThumb != null ? m.reelShareThumb : "";
            final String rKey = m.reelId != null ? m.reelId : "";
            if (thumb.isEmpty() && !rKey.isEmpty()) {
                String cachedThumb = reelThumbCache.get(rKey);
                if (cachedThumb != null) thumb = cachedThumb;
            }
            if (!thumb.isEmpty()) {
                final String finalThumbUrl = thumb;
                android.graphics.Bitmap reelThumbHit = DECODED_BITMAP_CACHE.get(poolKey(finalThumbUrl, 330, 474));
                if (reelThumbHit != null && !reelThumbHit.isRecycled()) {
                    dashboardRecordHit(ctx, finalThumbUrl);
                    cv.setReelShareThumbBitmap(reelThumbHit);
                } else {
                glide(ctx).asBitmap().load(finalThumbUrl).apply(THUMB_RGB565).override(330, 474).centerCrop()
                        .into(new com.bumptech.glide.request.target.CustomTarget<Bitmap>() {
                            @Override
                            public void onResourceReady(@NonNull Bitmap resource,
                                    @Nullable com.bumptech.glide.request.transition.Transition<? super Bitmap> transition) {
                                DECODED_BITMAP_CACHE.put(poolKey(finalThumbUrl, 330, 474), resource);
                                dashboardRecordDecoded(ctx, finalThumbUrl, resource);
                                if (h.canvasBindToken != myToken) return;
                                cv.setReelShareThumbBitmap(resource);
                            }
                            @Override
                            public void onLoadCleared(@Nullable android.graphics.drawable.Drawable placeholder) {}
                        });
                }
            } else if (!rKey.isEmpty() && reelThumbFetchInFlight.add(rKey)) {
                final android.content.Context fCtxT = ctx.getApplicationContext();
                com.google.firebase.database.FirebaseDatabase.getInstance()
                        .getReference("reels").child(rKey)
                        .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                            @Override public void onDataChange(@androidx.annotation.NonNull com.google.firebase.database.DataSnapshot snap) {
                                reelThumbFetchInFlight.remove(rKey);
                                if (!snap.exists() || h.canvasBindToken != myToken) return;
                                String t = snap.child("thumbUrl").getValue(String.class);
                                if (t == null || t.isEmpty()) t = snap.child("thumbnailUrl").getValue(String.class);
                                if (t != null && !t.isEmpty()) {
                                    reelThumbCache.put(rKey, t);
                                    glide(fCtxT).asBitmap().load(t).apply(THUMB_RGB565).override(330, 474).centerCrop()
                                            .into(new com.bumptech.glide.request.target.CustomTarget<Bitmap>() {
                                                @Override
                                                public void onResourceReady(@NonNull Bitmap resource,
                                                        @Nullable com.bumptech.glide.request.transition.Transition<? super Bitmap> transition) {
                                                    if (h.canvasBindToken != myToken) return;
                                                    cv.setReelShareThumbBitmap(resource);
                                                }
                                                @Override
                                                public void onLoadCleared(@Nullable android.graphics.drawable.Drawable placeholder) {}
                                            });
                                }
                                String u = snap.child("ownerName").getValue(String.class);
                                if (u == null || u.isEmpty()) u = snap.child("username").getValue(String.class);
                                if (u != null && !u.isEmpty()) cv.setReelShareUsername(u);
                                String ap = snap.child("ownerPhoto").getValue(String.class);
                                if (ap == null || ap.isEmpty()) ap = snap.child("profileImage").getValue(String.class);
                                if (ap != null && !ap.isEmpty()) {
                                    if (u != null && !u.isEmpty()) reelOwnerAvatarCache.put(u, ap);
                                    glide(fCtxT).asBitmap().load(ap).apply(THUMB_RGB565).override(96, 96).circleCrop()
                                            .into(new com.bumptech.glide.request.target.CustomTarget<Bitmap>() {
                                                @Override
                                                public void onResourceReady(@NonNull Bitmap resource,
                                                        @Nullable com.bumptech.glide.request.transition.Transition<? super Bitmap> transition) {
                                                    if (h.canvasBindToken != myToken) return;
                                                    cv.setReelShareAvatarBitmap(resource);
                                                }
                                                @Override
                                                public void onLoadCleared(@Nullable android.graphics.drawable.Drawable placeholder) {}
                                            });
                                }
                                String c = snap.child("caption").getValue(String.class);
                                if (c != null && !c.isEmpty()) cv.setReelShareCaption(c);
                            }
                            @Override public void onCancelled(@androidx.annotation.NonNull com.google.firebase.database.DatabaseError e) {
                                reelThumbFetchInFlight.remove(rKey);
                            }
                        });
            }
        } else if (isVideo) {
            // Mirrors the legacy "video" case (fl_video/iv_video_thumb) —
            // prefer the Cloudinary thumbnailUrl over the raw video URL
            // for the preview frame, same fallback order, and format the
            // duration badge the same "m:ss" way.
            final String vUrl = m.mediaUrl != null ? m.mediaUrl : m.text;
            // BUG FIX (v44): Never fall back to the raw video URL for the
            // thumbnail.  If thumbnailUrl is null (e.g. because the Firebase
            // → Room mapping was broken before v44), Glide would try to decode
            // the full mp4 URL as a Bitmap — this triggered a complete video
            // download as a side-effect, causing "pura video download hota
            // chat kholte hi". Now we fall back to null so the canvas shows
            // the BlurHash (or a plain dark background) until the thumbnail
            // URL arrives via the fixed mapping.
            final String vThumbUrl = (m.thumbnailUrl != null && !m.thumbnailUrl.isEmpty()) ? m.thumbnailUrl : null;
            String durText = null;
            if (m.duration != null && m.duration > 0) {
                long secs = m.duration / 1000;
                durText = String.format(java.util.Locale.US, "%d:%02d", secs / 60, secs % 60);
            }
            float vKnownRatio = (m.mediaWidth != null && m.mediaHeight != null
                    && m.mediaWidth > 0 && m.mediaHeight > 0)
                    ? (float) m.mediaWidth / m.mediaHeight : 0f;
            cv.bindVideo(null, m.caption, durText, timeStr, sent, isRead, isDelivered, vThumbUrl, vKnownRatio);
            cv.setDeletedStyle(false);
            wireCaptionReadMore(h, cv, m.messageId); // caption read-more/read-less

            // BlurHash placeholder: show blurred color preview before the video thumb loads.
            // If thumbnailUrl is present we load from server (no full download needed for preview).
            final String vBlurHash = m.blurHash;
            if (vBlurHash != null && !vBlurHash.isEmpty()) {
                android.graphics.Bitmap vBlurhashBmp = BlurHashPlaceholder.get(vBlurHash, 32, 32);
                if (vBlurhashBmp != null) cv.setMediaBitmap(vBlurhashBmp);
            }

            // Media E2E (video, thumbnail-only): m.thumbnailUrl is ciphertext
            // (uploaded as resource_type=raw) when mediaKeyEnc is set — see
            // ChatMediaController#doStartVideoUploadWork. The video file
            // itself (vUrl, below) is never encrypted.
            // Media E2E v2: video thumbnails are encrypted with the
            // thumb-purpose subkey (see MediaE2ECrypto.PURPOSE_THUMB) — use
            // the matching decrypt helper, not the full-purpose one.
            final byte[] vThumbKey = (!sent && m.mediaKeyEnc != null)
                    ? com.callx.app.utils.MediaE2ECrypto.decryptThumbKeyOnly(ctx, m.mediaKeyEnc,
                            m.senderId, (m.messageId != null ? m.messageId : m.id))
                    : null;

            if (vThumbUrl != null && !vThumbUrl.isEmpty()) {
                // PERF #1: check decoded-Bitmap pool before Glide decode
                final String vPoolKey = vThumbUrl;
                android.graphics.Bitmap vPoolHit = DECODED_BITMAP_CACHE.get(vPoolKey);
                if (vPoolHit != null && !vPoolHit.isRecycled()) {
                    if (vPoolHit.getHeight() > 0) {
                        com.callx.app.conversation.canvas.MessageBubbleCanvasView
                                .cacheAspectRatio(vThumbUrl, (float) vPoolHit.getWidth() / vPoolHit.getHeight());
                    }
                    dashboardRecordHit(ctx, vPoolKey);
                    cv.setMediaBitmap(vPoolHit);
                } else if (vThumbKey != null) {
                    // Encrypted thumb — decrypt via MediaCache before handing to Glide.
                    final String vThumbUrlF = vThumbUrl;
                    com.callx.app.utils.MediaCache.get(ctx, vThumbUrl, vThumbKey,
                            new com.callx.app.utils.MediaCache.Callback() {
                        @Override public void onReady(java.io.File file) {
                            if (h.canvasBindToken != myToken) return;
                            glide(ctx).asBitmap().load(file).apply(THUMB_RGB565)
                                    .override(thumbPx(ctx), thumbPx(ctx))
                                    .into(new com.bumptech.glide.request.target.CustomTarget<Bitmap>() {
                                        @Override public void onResourceReady(@NonNull Bitmap resource,
                                                @Nullable com.bumptech.glide.request.transition.Transition<? super Bitmap> t) {
                                            if (resource.getHeight() > 0) {
                                                com.callx.app.conversation.canvas.MessageBubbleCanvasView
                                                        .cacheAspectRatio(vThumbUrlF, (float) resource.getWidth() / resource.getHeight());
                                            }
                                            DECODED_BITMAP_CACHE.put(vThumbUrlF, resource);
                                            if (h.canvasBindToken != myToken) return;
                                            cv.setMediaBitmap(resource);
                                        }
                                        @Override public void onLoadCleared(@Nullable android.graphics.drawable.Drawable p) { }
                                    });
                        }
                        @Override public void onError(String reason) { /* BlurHash/placeholder stays up */ }
                    });
                } else {
                // PERF #4: density-aware override size
                glide(ctx).asBitmap()
                        .load(vThumbUrl)
                        .apply(THUMB_RGB565)
                        .thumbnail(0.1f)
                        .override(thumbPx(ctx), thumbPx(ctx))
                        .into(new com.bumptech.glide.request.target.CustomTarget<Bitmap>() {
                            @Override
                            public void onResourceReady(@NonNull Bitmap resource,
                                    @Nullable com.bumptech.glide.request.transition.Transition<? super Bitmap> transition) {
                                // Same reasoning as the "image" case above: cache
                                // the real ratio unconditionally so a fast-scroll
                                // rebind doesn't silently drop this decode's result
                                // and force the square placeholder to flash again
                                // next time this video thumb scrolls into view.
                                if (resource.getHeight() > 0) {
                                    com.callx.app.conversation.canvas.MessageBubbleCanvasView
                                            .cacheAspectRatio(vThumbUrl, (float) resource.getWidth() / resource.getHeight());
                                }
                                // PERF #1: store in pool for scroll-back reuse
                                DECODED_BITMAP_CACHE.put(vPoolKey, resource);
                                dashboardRecordDecoded(ctx, vPoolKey, resource);
                                if (h.canvasBindToken != myToken) return;
                                cv.setMediaBitmap(resource);
                            }
                            @Override
                            public void onLoadCleared(@Nullable android.graphics.drawable.Drawable placeholder) {
                                if (h.canvasBindToken != myToken) return;
                                cv.setMediaBitmap(null);
                            }
                        });
                }
            }

            // ── WhatsApp-style video download gate (receiver side only) ──────
            // Sender already has the file locally (mediaLocalPath) so no gate.
            // Receiver must tap the download pill → video downloads to local
            // cache → tap play to open the player.  This is the same pattern
            // as the image download gate above, just gated on "video" type.
            if (!sent && vUrl != null && !vUrl.isEmpty()) {
                boolean vLocalAvail = m.mediaLocalPath != null && !m.mediaLocalPath.isEmpty()
                        && Boolean.TRUE.equals(checkLocalAvailabilityAsync(ctx, m.mediaLocalPath,
                                m.messageId != null ? m.messageId : m.id));
                java.io.File vCached = vLocalAvail ? null
                        : com.callx.app.utils.MediaCache.getCached(ctx, vUrl);
                if (vLocalAvail || vCached != null) {
                    // Already on device — no gate needed.
                    cv.clearMediaDownloadGate();
                } else {
                    boolean vIsDownloading = downloadingMediaUrls.contains(vUrl);
                    if (vIsDownloading) {
                        cv.setMediaDownloadGate(true, -1, null);
                    } else {
                        // Show idle pill: file size if already known, "Video" otherwise.
                        if (m.fileSize != null && m.fileSize > 0) {
                            cv.setMediaDownloadGate(false, Integer.MIN_VALUE,
                                    formatFileSize(m.fileSize));
                        } else {
                            cv.setMediaDownloadGate(false, Integer.MIN_VALUE, "Video");
                            final String capturedVUrlSize = vUrl;
                            com.callx.app.utils.MediaCache.getRemoteSize(ctx, capturedVUrlSize,
                                    new com.callx.app.utils.MediaCache.SizeCallback() {
                                @Override public void onSize(long bytes) {
                                    if (h.canvasBindToken != myToken) return;
                                    if (!downloadingMediaUrls.contains(capturedVUrlSize))
                                        cv.setMediaDownloadGate(false, Integer.MIN_VALUE,
                                                formatFileSize(bytes));
                                }
                                @Override public void onError(String reason) {}
                            });
                        }
                    }
                }
            }
        } else if (isContact) {
            // Mirrors the legacy "contact" case (ChatContactShareController.
            // bindBubble) — same contactPhotoUrl Glide load (placeholder
            // ic_person if absent), just pushed through
            // cv.bindContact()/setContactAvatarBitmap() instead of
            // CircleImageView/TextView calls. No caption, no timestamp/
            // tick footer for this type (see MessageBubbleCanvasView's
            // CONTACT_* doc).
            cv.bindContact(null, m.contactName, m.contactPhone, sent);
            cv.setDeletedStyle(false);

            final String contactPhotoUrl = m.contactPhotoUrl;
            if (contactPhotoUrl != null && !contactPhotoUrl.isEmpty()) {
                // FIX: same flicker root cause as seen-bubble/reel-share —
                // no cache check meant every rebind (scroll, or a new
                // message elsewhere triggering a rebind of this visible
                // contact-card row) blanked the avatar for a frame.
                android.graphics.Bitmap contactHit = DECODED_BITMAP_CACHE.get(poolKey(contactPhotoUrl, 96, 96));
                if (contactHit != null && !contactHit.isRecycled()) {
                    dashboardRecordHit(ctx, contactPhotoUrl);
                    cv.setContactAvatarBitmap(contactHit);
                } else {
                    glide(ctx).asBitmap().load(contactPhotoUrl).apply(THUMB_RGB565)
                            .override(96, 96).circleCrop()
                            .listener(com.callx.app.cache.CacheDashboardStats.glideListener(
                                    ctx, contactPhotoUrl))
                            .into(new com.bumptech.glide.request.target.CustomTarget<Bitmap>() {
                                @Override
                                public void onResourceReady(@NonNull Bitmap resource,
                                        @Nullable com.bumptech.glide.request.transition.Transition<? super Bitmap> transition) {
                                    DECODED_BITMAP_CACHE.put(poolKey(contactPhotoUrl, 96, 96), resource);
                                    dashboardRecordDecoded(ctx, contactPhotoUrl, resource);
                                    if (h.canvasBindToken != myToken) return;
                                    cv.setContactAvatarBitmap(resource);
                                }
                                @Override
                                public void onLoadCleared(@Nullable android.graphics.drawable.Drawable placeholder) {
                                    if (h.canvasBindToken != myToken) return;
                                    cv.setContactAvatarBitmap(null);
                                }
                            });
                }
            }
        } else if (isLocation) {
            // Mirrors the legacy "location" case (ChatLocationShareController.
            // bindBubble) — same address-or-"lat, lng" fallback text and the
            // same Google Static Maps thumbnail Glide load when an API key
            // is configured, just pushed through cv.bindLocation()/
            // setLocationMapBitmap() instead of ImageView/TextView calls.
            // No caption, no timestamp/tick footer for this type (see
            // MessageBubbleCanvasView's LOCATION_* doc).
            final double lat = m.locationLat != null ? m.locationLat : 0;
            final double lng = m.locationLng != null ? m.locationLng : 0;
            final String addr = (m.locationAddress != null && !m.locationAddress.isEmpty())
                    ? m.locationAddress
                    : String.format(java.util.Locale.getDefault(), "%.5f, %.5f", lat, lng);
            cv.bindLocation(null, addr, sent);
            cv.setDeletedStyle(false);

            String mapsKey = com.callx.app.conversation.controllers.ChatLocationShareController.getMapsApiKey();
            if (mapsKey != null && !mapsKey.isEmpty() && lat != 0) {
                final String thumbUrl = String.format(java.util.Locale.US,
                        "https://maps.googleapis.com/maps/api/staticmap"
                        + "?center=%.6f,%.6f&zoom=15&size=400x200&markers=%.6f,%.6f&key=%s",
                        lat, lng, lat, lng, mapsKey);
                // PERF: same in-memory decoded-Bitmap pool used for media/reel
                // thumbnails above — a Glide disk-cache hit still pays a
                // decode + host-lookup cost, so check the pool first. The
                // static-map URL is a stable, unique key per lat/lng (fixed
                // zoom/size), so scroll-back to the same location bubble is
                // an instant pool hit with zero network/disk/decode work.
                Bitmap locPoolHit = DECODED_BITMAP_CACHE.get(thumbUrl);
                if (locPoolHit != null && !locPoolHit.isRecycled()) {
                    dashboardRecordHit(ctx, thumbUrl);
                    cv.setLocationMapBitmap(locPoolHit);
                } else {
                glide(ctx).asBitmap().load(thumbUrl).apply(THUMB_RGB565)
                        .override(720, 720)
                        .listener(com.callx.app.cache.CacheDashboardStats.glideListener(
                                ctx, thumbUrl))
                        .into(new com.bumptech.glide.request.target.CustomTarget<Bitmap>() {
                            @Override
                            public void onResourceReady(@NonNull Bitmap resource,
                                    @Nullable com.bumptech.glide.request.transition.Transition<? super Bitmap> transition) {
                                // PERF: store for scroll-back reuse regardless
                                // of whether this holder still shows this bubble
                                DECODED_BITMAP_CACHE.put(thumbUrl, resource);
                                dashboardRecordDecoded(ctx, thumbUrl, resource);
                                if (h.canvasBindToken != myToken) return;
                                cv.setLocationMapBitmap(resource);
                            }
                            @Override
                            public void onLoadCleared(@Nullable android.graphics.drawable.Drawable placeholder) {
                                if (h.canvasBindToken != myToken) return;
                                cv.setLocationMapBitmap(null);
                            }
                        });
                }
            }
        } else if (isAudio) {
            // Mirrors the legacy "audio" case (ll_audio/btn_play_pause/
            // seek_audio) — same MediaStreamCache preload-partial-first
            // trick so tapping play starts instantly, just pushed through
            // cv.bindAudio()/setAudioPlaying()/setAudioProgress()/
            // setAudioElapsedText() (see toggleAudio/playAudioFromPath)
            // instead of ImageButton/AudioWaveformView/TextView calls.
            // Always starts idle on a fresh bind — same as the legacy
            // seekAudio.setProgress(0f) — even if this message happens to
            // be the one currently playing (a rare rebind-mid-playback
            // edge case the legacy View path doesn't handle either).
            final String aUrl = m.mediaUrl != null ? m.mediaUrl : m.text;
            cv.bindAudio(aUrl, timeStr, sent, isRead, isDelivered);
            cv.setDeletedStyle(false);
            java.io.File cachedAudio = MediaCache.getCached(ctx, aUrl);
            if (cachedAudio == null && aUrl != null && !aUrl.isEmpty()) {
                // Media E2E (audio): MediaStreamCache doesn't know how to
                // decrypt, so an E2E voice note skips the "stream first
                // 512KB while downloading" fast-start trick and instead
                // warms the plain decrypting MediaCache download (full
                // file — voice notes are small, so the wait is
                // negligible). See ChatMediaController#doUpload's audio
                // branch / MediaE2ECrypto.
                byte[] aWarmKey = (!sent && m.mediaKeyEnc != null)
                        ? com.callx.app.utils.MediaE2ECrypto.decryptKeyOnly(ctx, m.mediaKeyEnc,
                                m.senderId, (m.messageId != null ? m.messageId : m.id))
                        : null;
                if (aWarmKey != null) {
                    com.callx.app.utils.MediaCache.get(ctx, aUrl, aWarmKey,
                            new com.callx.app.utils.MediaCache.Callback() {
                        @Override public void onReady(java.io.File file) {}
                        @Override public void onError(String reason) {}
                    });
                } else {
                    com.callx.app.cache.MediaStreamCache.getInstance(ctx)
                        .preloadPartial(aUrl, new com.callx.app.cache.MediaStreamCache.DownloadCallback() {
                            @Override public void onComplete(java.io.File file) {}
                            @Override public void onError(String error) {}
                            @Override public void onProgress(int percent) {}
                        });
                }
            }
        } else if (isGif) {
            // ── v59: GIF Canvas bubble ────────────────────────────────────────
            // Reuses the single-image slot (same 180dp square, same download-gate)
            // with a "GIF" badge pill. Mirrors the legacy GifDrawable/Glide path.
            final String gifUrl = m.mediaUrl != null ? m.mediaUrl : "";
            cv.bindGif(gifUrl, timeStr, sent, isRead, isDelivered);
            cv.setDeletedStyle(false);

            java.io.File gifCached = getCachedFileFast(ctx, gifUrl);
            if (gifCached != null) {
                // FIX: disk-cached doesn't mean flicker-free — decode was
                // still async with zero in-memory pool check, so every
                // rebind (scroll, or a new message elsewhere triggering a
                // rebind of this visible GIF row) blanked it for a frame.
                String gifPoolKey = gifCached.getAbsolutePath();
                android.graphics.Bitmap gifHit = DECODED_BITMAP_CACHE.get(gifPoolKey);
                if (gifHit != null && !gifHit.isRecycled()) {
                    dashboardRecordHit(ctx, gifPoolKey);
                    cv.setGifBitmap(gifHit);
                } else {
                    // Already on disk — decode first-frame and display immediately.
                    glide(ctx).asBitmap().load(gifCached).apply(THUMB_RGB565)
                            .override(gifStickerPx(ctx), gifStickerPx(ctx)) // PERF: match 180dp slot, avoid oversized decode
                            .listener(com.callx.app.cache.CacheDashboardStats.glideListener(
                                    ctx, gifPoolKey))
                            .into(new com.bumptech.glide.request.target.CustomTarget<android.graphics.Bitmap>() {
                                @Override
                                public void onResourceReady(@NonNull android.graphics.Bitmap resource,
                                        @Nullable com.bumptech.glide.request.transition.Transition<? super android.graphics.Bitmap> transition) {
                                    DECODED_BITMAP_CACHE.put(gifPoolKey, resource);
                                    dashboardRecordDecoded(ctx, gifPoolKey, resource);
                                    if (h.canvasBindToken != myToken) return;
                                    cv.setGifBitmap(resource);
                                }
                                @Override
                                public void onLoadCleared(@Nullable android.graphics.drawable.Drawable p) {}
                            });
                }
            } else if (!gifUrl.isEmpty()) {
                // Not cached — show download gate; fetch remote size for the pill label.
                cv.setMediaDownloadGate(false, Integer.MIN_VALUE, "GIF");
                com.callx.app.utils.MediaCache.getRemoteSize(ctx, gifUrl,
                        new com.callx.app.utils.MediaCache.SizeCallback() {
                    @Override public void onSize(long bytes) {
                        if (h.canvasBindToken != myToken) return;
                        if (!downloadingMediaUrls.contains(gifUrl))
                            cv.setMediaDownloadGate(false, Integer.MIN_VALUE, formatFileSize(bytes));
                    }
                    @Override public void onError(String reason) { /* keep "GIF" label */ }
                });
            }

            // NOTE: GIF taps (onGifClick) and the download-gate pill
            // (onMediaDownloadClick) used to be wired via a separate
            // setOnBubbleClickListener() call right here — it was always
            // clobbered by the single unconditional setOnBubbleClickListener()
            // call at the end of this method, so tapping a GIF bubble did
            // nothing. That logic now lives in that surviving listener's
            // onGifClick()/onMediaDownloadClick() overrides (isGif-gated).

        } else if (isSticker) {
            // ── Sticker Canvas bubble ──────────────────────────────────────────
            // Reuses the single-image slot/download-gate (same path as GIF),
            // just no badge pill and no size label on the gate ("Sticker"
            // instead of a byte-count string once known — matches the
            // GIF gate's placeholder-then-size-label behavior).
            final String stickerUrl = m.mediaUrl != null ? m.mediaUrl : "";
            cv.bindSticker(stickerUrl, timeStr, sent, isRead, isDelivered);
            cv.setDeletedStyle(false);

            java.io.File stickerCached = getCachedFileFast(ctx, stickerUrl);
            if (stickerCached != null) {
                // FIX: same as GIF above — disk-cached but no in-memory pool
                // check meant every rebind blanked the sticker for a frame.
                String stickerPoolKey = stickerCached.getAbsolutePath();
                android.graphics.Bitmap stickerHit = DECODED_BITMAP_CACHE.get(stickerPoolKey);
                if (stickerHit != null && !stickerHit.isRecycled()) {
                    dashboardRecordHit(ctx, stickerPoolKey);
                    cv.setStickerBitmap(stickerHit);
                } else {
                    glide(ctx).asBitmap().load(stickerCached).apply(THUMB_RGB565)
                            .override(gifStickerPx(ctx), gifStickerPx(ctx)) // PERF: match 180dp slot, avoid oversized decode
                            .listener(com.callx.app.cache.CacheDashboardStats.glideListener(
                                    ctx, stickerPoolKey))
                            .into(new com.bumptech.glide.request.target.CustomTarget<android.graphics.Bitmap>() {
                                @Override
                                public void onResourceReady(@NonNull android.graphics.Bitmap resource,
                                        @Nullable com.bumptech.glide.request.transition.Transition<? super android.graphics.Bitmap> transition) {
                                    DECODED_BITMAP_CACHE.put(stickerPoolKey, resource);
                                    dashboardRecordDecoded(ctx, stickerPoolKey, resource);
                                    if (h.canvasBindToken != myToken) return;
                                    cv.setStickerBitmap(resource);
                                }
                                @Override
                                public void onLoadCleared(@Nullable android.graphics.drawable.Drawable p) {}
                            });
                }
            } else if (!stickerUrl.isEmpty()) {
                cv.setMediaDownloadGate(false, Integer.MIN_VALUE, "Sticker");
                com.callx.app.utils.MediaCache.getRemoteSize(ctx, stickerUrl,
                        new com.callx.app.utils.MediaCache.SizeCallback() {
                    @Override public void onSize(long bytes) {
                        if (h.canvasBindToken != myToken) return;
                        if (!downloadingMediaUrls.contains(stickerUrl))
                            cv.setMediaDownloadGate(false, Integer.MIN_VALUE, formatFileSize(bytes));
                    }
                    @Override public void onError(String reason) { /* keep "Sticker" label */ }
                });
            }

        } else if (isFile) {
            // ── v59: File Canvas bubble ───────────────────────────────────────
            // Card-style bubble: file-type icon circle, name+size row,
            // download/open button, footer. Mirrors the legacy ll_file row.
            final String fileUrl  = m.mediaUrl != null ? m.mediaUrl : "";
            final String fileName = m.fileName != null ? m.fileName : "File";
            // Derive MIME type from file extension — Message has no mimeType field.
            final String mime     = guessMimeFromFileName(fileName);
            final long   sizeRaw  = m.fileSize != null ? m.fileSize : 0L;
            final String sizeStr  = sizeRaw > 0
                    ? android.text.format.Formatter.formatShortFileSize(ctx, sizeRaw) : "";

            boolean fileCached = !fileUrl.isEmpty() && MediaCache.getCached(ctx, fileUrl) != null;
            cv.bindFile(fileName, mime, sizeStr, fileCached, sent, isRead, isDelivered);
            cv.setDeletedStyle(false);
            // NOTE: the download button (onFileDownloadClick) and open
            // button (onFileOpenClick) used to be wired via a separate
            // setOnBubbleClickListener() call right here — it was always
            // clobbered by the single unconditional setOnBubbleClickListener()
            // call at the end of this method, so both buttons silently did
            // nothing. That logic now lives in that surviving listener's
            // onFileDownloadClick()/onFileOpenClick() overrides (isFile-gated).

        } else if (isPoll) {
            // Mirrors the legacy ensurePollInflated / bindPoll(VH, ...) path —
            // pushed through cv.bindPoll() instead of ViewStub/LinearLayout calls.
            java.util.List<String> opts = m.pollOptions != null
                    ? m.pollOptions : java.util.Collections.emptyList();
            java.util.Map<String, java.util.List<Integer>> votesMap = m.pollVotes != null
                    ? m.pollVotes : java.util.Collections.emptyMap();
            int[] counts = com.callx.app.utils.PollJsonUtil.countVotes(votesMap, opts.size());
            int total    = com.callx.app.utils.PollJsonUtil.totalVotes(votesMap);
            boolean[] myVote = new boolean[opts.size()];
            if (currentUid != null) {
                java.util.List<Integer> mine = votesMap.get(currentUid);
                if (mine != null) {
                    for (int idx : mine) {
                        if (idx >= 0 && idx < myVote.length) myVote[idx] = true;
                    }
                }
            }
            cv.bindPoll(
                    m.pollQuestion,
                    opts,
                    counts,
                    myVote,
                    total,
                    Boolean.TRUE.equals(m.pollClosed),
                    Boolean.TRUE.equals(m.pollMultiChoice),
                    sent, timeStr, isRead, isDelivered);
            cv.setDeletedStyle(false);
            // NOTE: poll-option tap → ActionListener.onPollVote() is wired
            // in the single setOnBubbleClickListener() call at the end of
            // this method (onPollOptionClick override) — a second,
            // poll-only listener used to be set right here, but it was
            // always clobbered by that later unconditional call, which is
            // exactly why voting silently did nothing. See that override's
            // comment for details.
        } else {
            // Feature 3: strip ||spoiler|| markers for canvas path (canvas can't render custom spans)
            String canvasText = m.text != null ? m.text : "";
            if (com.callx.app.utils.SpoilerTextHelper.hasSpoiler(canvasText)) {
                canvasText = com.callx.app.utils.SpoilerTextHelper.stripMarkers(canvasText);
            }
            cv.bind(canvasText, timeStr, sent, isRead, isDelivered);
            cv.setDeletedStyle(false); // clears any italic/dim state a recycled view carried from a deleted message
            cv.setSearchHighlight(activeSearchQuery);

            // ── Read-more / Read-less wiring ─────────────────────────────
            final String msgIdForExpand = m.messageId;
            cv.setTextExpanded(expandedMessageIds.contains(msgIdForExpand));
            cv.setReadMoreListener(nowExpanded -> {
                if (nowExpanded) expandedMessageIds.add(msgIdForExpand);
                else             expandedMessageIds.remove(msgIdForExpand);

                int pos = h.getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return;

                // ── WhatsApp-style scroll anchor ─────────────────────────
                // Capture the item's current top edge (relative to the
                // RecyclerView's visible area) BEFORE notifyItemChanged()
                // changes the bubble height.  After the rebind we restore
                // that offset with scrollToPositionWithOffset() so the item
                // stays visually pinned at the same Y position — expanded
                // text grows downward and the user scrolls to read it,
                // exactly like WhatsApp.
                final RecyclerView rv = (h.itemView.getParent() instanceof RecyclerView)
                        ? (RecyclerView) h.itemView.getParent() : null;
                final int savedTop = (rv != null) ? h.itemView.getTop() : 0;

                notifyItemChanged(pos);

                if (rv != null) {
                    rv.post(() -> {
                        RecyclerView.LayoutManager lm = rv.getLayoutManager();
                        if (lm instanceof androidx.recyclerview.widget.LinearLayoutManager) {
                            ((androidx.recyclerview.widget.LinearLayoutManager) lm)
                                    .scrollToPositionWithOffset(pos, savedTop);
                        }
                    });
                }
            });

            // ── Link-preview card ─────────────────────────────────────
            // Mirrors the legacy ll_link_preview ViewStub path (same
            // LinkPreviewFetcher cache/fetch, same URL-detected-once
            // guard) — pushed through setLinkPreview()/
            // setLinkPreviewThumbBitmap() instead of TextView/ImageView
            // calls. Known simplification vs. the legacy path: the card
            // only appears once the fetch resolves — no reserved
            // "loading" space (legacy shows an INVISIBLE placeholder of
            // final size while fetching; a cache hit here is effectively
            // instant anyway, same as it is on the legacy path).
            // cv's own tag doubles as the staleness guard (equivalent to
            // h.llLinkPreview.getTag() there), since MessageBubbleCanvasView
            // has no ViewStub-backed child views to tag instead.
            final String previewUrl = m.text != null
                    ? com.callx.app.utils.LinkPreviewFetcher.extractFirstUrl(m.text) : null;
            cv.setTag(previewUrl);
            if (previewUrl == null) {
                cv.clearLinkPreview();
            } else {
                // FLICKER ROOT CAUSE (whole chat list junk on every send/
                // receive once a link message exists): this used to call
                // clearLinkPreview() UNCONDITIONALLY before firing fetch() —
                // even when the URL was already resolved and sitting in
                // LinkPreviewFetcher's cache. clearLinkPreview() collapses the
                // card to zero height (requestLayoutIfSizeChanged() sees a
                // real size change), and fetch()'s callback — even on a pure
                // cache hit — always lands a frame later via mainHandler.post(),
                // never synchronously. So every rebind of this row replayed a
                // collapse-then-expand cycle. And this row DOES get rebound on
                // every unrelated send/receive: reanchorPagingToBottom()
                // invalidates the live PagingSource on every write, reloading
                // the whole visible page as fresh Message objects. That
                // per-row height thrash is exactly what forces RecyclerView to
                // reflow every row below it — the "puri chat list
                // flickering/junk" the moment a link exists anywhere onscreen.
                //
                // Fix: peek the cache synchronously FIRST. The overwhelmingly
                // common case — a link that already resolved once — renders
                // in the very same frame with no clear/collapse step at all.
                // Only a genuine cache miss (URL never fetched before) still
                // falls back to clearLinkPreview() + async fetch().
                com.callx.app.utils.LinkPreviewFetcher.Result cachedPreview =
                        com.callx.app.utils.LinkPreviewFetcher.peek(previewUrl);
                if (cachedPreview != null) {
                    bindLinkPreviewResult(cv, ctx, previewUrl, cachedPreview);
                } else {
                    cv.clearLinkPreview(); // genuinely nothing to show yet — fetch in flight
                    com.callx.app.utils.LinkPreviewFetcher.fetch(previewUrl,
                            new com.callx.app.utils.LinkPreviewFetcher.Callback() {
                        @Override public void onResult(com.callx.app.utils.LinkPreviewFetcher.Result r) {
                            if (!previewUrl.equals(cv.getTag())) return; // recycled/rebound since this fetch started
                            bindLinkPreviewResult(cv, ctx, previewUrl, r);
                        }
                        @Override public void onError(String url) {
                            if (!previewUrl.equals(cv.getTag())) return;
                            cv.clearLinkPreview();
                        }
                    });
                }
            }
        }

        // ── Reply preview (also covers status-reply/status-reaction quote
        //    boxes — those are ordinary messages whose replyToId is
        //    "status_"+statusId, rendered through this exact same path) ──
        //
        // FIX: this used to unconditionally call setReply(..., null) first
        // and let the async Glide load fill the thumbnail in afterward —
        // even when the bitmap was already sitting in Glide's memory cache.
        // Every rebind (recycler reuse on scroll, AND every rebind of an
        // on-screen row triggered by an unrelated new message being
        // inserted elsewhere in the paging list) replayed that
        // blank→pop-in sequence, which is exactly the flicker/junk on
        // send/receive. Status-reply bubbles were hit hardest since they
        // almost always carry a thumbnail. Now: check the same in-memory
        // decoded-Bitmap pool the media bubbles use (PERF #1) synchronously
        // first — a cache hit renders the thumb in the very same frame as
        // the text, with zero flash. Only a genuine cache miss falls back
        // to the async Glide load, exactly like the media-bitmap path above.
        if (m.replyToId != null && !m.replyToId.isEmpty()) {
            final String replyThumbUrl = m.replyToMediaUrl;
            if (replyThumbUrl != null && !replyThumbUrl.isEmpty()) {
                android.graphics.Bitmap replyPoolHit = DECODED_BITMAP_CACHE.get(poolKey(replyThumbUrl, 88, 88));
                if (replyPoolHit != null && !replyPoolHit.isRecycled()) {
                    cv.setReply(m.replyToSenderName, m.replyToText, replyPoolHit);
                } else {
                    cv.setReply(m.replyToSenderName, m.replyToText, null);
                    glide(ctx).asBitmap()
                            .load(replyThumbUrl)
                            .apply(THUMB_RGB565)
                            .override(88, 88)
                            .into(new com.bumptech.glide.request.target.CustomTarget<Bitmap>() {
                                @Override
                                public void onResourceReady(@NonNull Bitmap resource,
                                        @Nullable com.bumptech.glide.request.transition.Transition<? super Bitmap> transition) {
                                    DECODED_BITMAP_CACHE.put(poolKey(replyThumbUrl, 88, 88), resource);
                                    if (h.canvasBindToken != myToken) return; // holder recycled/rebound since this load started
                                    cv.setReply(m.replyToSenderName, m.replyToText, resource);
                                }
                                @Override
                                public void onLoadCleared(@Nullable android.graphics.drawable.Drawable placeholder) {}
                            });
                }
            } else {
                cv.setReply(m.replyToSenderName, m.replyToText, null);
            }
        } else {
            cv.clearReply();
        }

        // Instagram-style big emoji badge: only for story/reel-reaction
        // messages (replyToId = "status_"+id, text = a single emoji) —
        // see StatusViewerActivity#sendReactionToChat. Every other reply
        // (normal text/media replies, and reactions whose text isn't a
        // lone emoji) keeps the plain small quote-box text as before.
        cv.setBigReactionEmoji(isStoryReactionEmojiMessage(m) ? m.text : null);

        // ── Reaction badge ──
        String reactionsText = formatReactions(m.reactions);
        if (reactionsText != null) cv.setReactions(reactionsText);
        else cv.clearReactions();

        // ── Pinned label ──
        cv.setPinned(Boolean.TRUE.equals(m.pinned));

        // ── Group-chat sender name (received only — same gate bindMessage()
        // uses for tvSenderName) — also carries the 📢 broadcast badge,
        // exactly mirroring bindMessage()'s tv_sender_name block: a group
        // message gets a "📢 " prefix on the sender name, a 1:1 broadcast
        // shows the row solely for "📢 Broadcast". ──
        boolean isBroadcastMsg = Boolean.TRUE.equals(m.broadcast);
        if (!sent && isGroup && m.senderName != null && !m.senderName.isEmpty()) {
            cv.setGroupSender(isBroadcastMsg ? "\uD83D\uDCE2 " + m.senderName : m.senderName);
        } else if (!sent && isBroadcastMsg) {
            cv.setGroupSender("\uD83D\uDCE2 Broadcast");
        } else {
            cv.clearGroupSender();
        }

        // ── Forwarded label ──
        if (m.forwardedFrom != null && !m.forwardedFrom.isEmpty()) {
            cv.setForwardedFrom(m.forwardedFrom);
        } else {
            cv.clearForwarded();
        }

        // ── Disappearing-message countdown — same shared ExpiryTickManager
        // handler the legacy path uses, targeting the canvas view instead of
        // tv_expiry. Mirrors bindMessage(): skipped entirely once a message
        // is deleted (nothing left to count down to). ──
        com.callx.app.utils.ExpiryTickManager.get().unregister(h);
        if (!isDeleted) {
            long expiresAt = m.expiresAt != null ? m.expiresAt : 0L;
            long remaining = expiresAt - System.currentTimeMillis();
            if (expiresAt > 0 && remaining > 0) {
                cv.setExpiryText("\u23F3 " + formatRemaining(remaining));
                com.callx.app.utils.ExpiryTickManager.get().register(h, expiresAt,
                        new com.callx.app.utils.ExpiryTickManager.Listener() {
                    @Override public void onTick(long ms) {
                        if (h.canvasView != null) h.canvasView.setExpiryText("\u23F3 " + formatRemaining(ms));
                    }
                    @Override public void onFinish() {
                        if (h.canvasView != null) h.canvasView.clearExpiry();
                    }
                });
            } else {
                cv.clearExpiry();
            }
        } else {
            cv.clearExpiry();
        }

        // ── Selection highlight (multi-select mode) — works unmodified since
        // it only touches h.itemView's alpha/background/tag, which is cv itself. ──
        applySelectionHighlight(h, m);

        cv.setOnBubbleClickListener(new com.callx.app.conversation.canvas.OnBubbleClickListener() {
            @Override
            public void onBubbleClick() {
                if (multiSelectMode) {
                    String id = m.messageId != null ? m.messageId : m.id;
                    if (id != null) {
                        if (selectedMessageIds.contains(id)) selectedMessageIds.remove(id);
                        else selectedMessageIds.add(id);
                        // FIX: don't rely on h.getAdapterPosition() here — it can
                        // return NO_POSITION right after a long-press-triggered
                        // notifyItemRangeChanged (ViewHolder in a transient
                        // state), silently dropping the highlight refresh so the
                        // bubble LOOKS still-selected even though it was removed
                        // from selectedMessageIds. h/m are already in scope, so
                        // update this row's highlight directly instead.
                        applySelectionHighlight(h, m);
                        if (multiSelectListener != null) multiSelectListener.onSelectionChanged(selectedMessageIds.size());
                        if (selectedMessageIds.isEmpty()) exitMultiSelectMode();
                    }
                }
            }

            @Override
            public void onBubbleLongClick() {
                cv.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                if (!multiSelectMode) {
                    enterMultiSelectMode(m);
                } else if (actionListener != null) {
                    showActionBottomSheet(ctx, m);
                }
            }

            @Override
            public boolean onLinkClick(String url) {
                // Tapping the link-preview CARD (setLinkPreview/drawLinkPreview)
                // fires here — mirrors the legacy ll_link_preview click
                // listener that opens the URL in a browser. Tap-on-a-URL-
                // SPAN-inside-the-text-itself (Linkify-equivalent) is still
                // not modeled — this method is only ever invoked for the
                // card's whole-card tap right now, never for an in-text span.
                if (url == null || url.isEmpty()) return false;
                android.content.Intent browserIntent = new android.content.Intent(
                        android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url));
                browserIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(browserIntent);
                return true;
            }

            @Override
            public void onReplyPreviewClick() {
                if (actionListener != null && m.replyToId != null) {
                    actionListener.onNavigateToOriginal(m.replyToId, m.senderId);
                }
            }

            @Override
            public void onReactionsClick() {
                if (actionListener != null) actionListener.onReactionTap(m);
            }

            @Override
            public void onForwardClick() {
                if (actionListener != null) actionListener.onForward(m);
            }

            @Override
            public void onImageClick() {
                if (isReelShare) {
                    // Shared reel cards open the full reel on a normal tap.
                    // A 3-second hold is handled separately by
                    // onReelPeekPreview() below.
                    String reelId = m.reelId != null ? m.reelId : "";
                    String reelUrl = m.reelShareUrl != null ? m.reelShareUrl : "";
                    if (reelId.isEmpty() && reelUrl.isEmpty()) return;
                    String deepLink = !reelId.isEmpty()
                            ? com.callx.app.utils.Constants.DEEP_LINK_BASE_URL + "/reel/" + reelId
                            : reelUrl;
                    try {
                        android.content.Intent ri = new android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(deepLink));
                        ri.setPackage(ctx.getPackageName());
                        ri.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                        ctx.startActivity(ri);
                    } catch (Exception ignored) {}
                } else if (isImage) {
                    // Still uploading / failed local-first bubble — no
                    // remote URL to open yet; the gate tap (onMediaDownloadClick)
                    // handles the failed-retry case instead.
                    if (sent && m.mediaLocalPath != null && !m.mediaLocalPath.isEmpty()
                            && (m.mediaUrl == null || m.mediaUrl.isEmpty())) {
                        return;
                    }
                    String fullUrl = m.mediaUrl != null ? m.mediaUrl : m.text;
                    // Telegram-style close animation — capture this bubble's
                    // on-screen image rect now (while `cv` is still laid
                    // out here) so the sheet's "View" action can hand it to
                    // MediaViewerActivity (see MediaViewerSourceRect).
                    showImageActionSheet(ctx, m, fullUrl, fullUrl, cv.getMediaRectOnScreen());
                } else if (isVideo) {
                    // WhatsApp-style video tap, now mirroring the single-image
                    // flow exactly:
                    //   Sender / already-cached → open the same action sheet
                    //   an image tap opens (Play/Edit/Save/Share/Forward/
                    //   Star/Delete) instead of jumping straight into the
                    //   player — a video bubble used to have none of those
                    //   options reachable except via the long-press sheet
                    //   (which has no View/Edit/Save at all).
                    //   Receiver + not yet cached → this branch isn't even
                    //   reached (the bubble is showing the download gate —
                    //   see bindVideo's WhatsApp-style gate — so the tap
                    //   goes to onMediaDownloadClick instead); the fallback
                    //   download-then-open path below only covers the rare
                    //   edge case where this fires before the gate state
                    //   settles, and now opens the sheet too once ready
                    //   rather than force-launching the player.
                    final String vUrl2 = m.mediaUrl != null ? m.mediaUrl : m.text;
                    if (vUrl2 == null || vUrl2.isEmpty()) return;

                    // Check local availability: sender's original file or
                    // a previously downloaded cached copy.
                    boolean vHasLocal = m.mediaLocalPath != null && !m.mediaLocalPath.isEmpty()
                            && Boolean.TRUE.equals(checkLocalAvailabilityAsync(ctx,
                                    m.mediaLocalPath, m.messageId != null ? m.messageId : m.id));
                    java.io.File vCachedFile = vHasLocal ? null
                            : com.callx.app.utils.MediaCache.getCached(ctx, vUrl2);

                    if (sent || vHasLocal || vCachedFile != null) {
                        // Already on device — same advanced-action sheet a
                        // single-image bubble gets.
                        String vLocalPath = vHasLocal ? m.mediaLocalPath
                                : (vCachedFile != null ? vCachedFile.getAbsolutePath() : null);
                        showMediaActionSheet(ctx, m, vUrl2, vUrl2, "video", vLocalPath,
                                null, -1, cv.getMediaRectOnScreen());
                    } else if (downloadingMediaUrls.contains(vUrl2)) {
                        // Already downloading — the gate shows progress; nothing to do.
                    } else {
                        // Not cached → download first, then play.
                        // (Same as tapping the download pill but initiated via the
                        // play-button tap so the UX feels seamless.)
                        downloadingMediaUrls.add(vUrl2);
                        cv.setMediaDownloadGate(true, 0, null);
                        MediaDownloadQueue.getInstance(ctx).enqueue(vUrl2, null, () -> {
                            com.callx.app.utils.MediaCache.getWithProgress(ctx, vUrl2,
                                    new com.callx.app.utils.MediaCache.ProgressCallback() {
                                @Override public void onProgress(int percent) {
                                    if (h.canvasBindToken != myToken) return;
                                    cv.setMediaDownloadGate(true, percent, null);
                                }
                                @Override public void onReady(java.io.File file) {
                                    downloadingMediaUrls.remove(vUrl2);
                                    if (h.canvasBindToken != myToken) return;
                                    cv.clearMediaDownloadGate();
                                    ((android.app.Activity) ctx).runOnUiThread(() -> {
                                        android.content.Intent i2 = new android.content.Intent()
                                                .setClassName(ctx.getPackageName(),
                                                        "com.callx.app.activities.MediaViewerActivity");
                                        i2.putExtra("url", vUrl2);
                                        i2.putExtra("type", "video");
                                        i2.putExtra("localPath", file.getAbsolutePath());
                                        i2.putExtra("chatId", chatId);
                                        i2.putExtra("messageId",
                                                m.messageId != null ? m.messageId : m.id);
                                        com.callx.app.utils.MediaViewerSourceRect.attach(i2, cv.getMediaRectOnScreen());
                                        ctx.startActivity(i2);
                                    });
                                }
                                @Override public void onError(String reason) {
                                    downloadingMediaUrls.remove(vUrl2);
                                    if (h.canvasBindToken != myToken) return;
                                    cv.setMediaDownloadGate(false, Integer.MIN_VALUE,
                                            "Tap to retry");
                                }
                            });
                        });
                    }
                }
            }

            @Override
            public void onReelPeekPreview(android.view.View sourceView) {
                if (isReelShare) {
                    ReelSharePeekBridge.show(ctx, m, sourceView);
                }
            }

            @Override
            public void onMediaDownloadClick() {
                // WhatsApp-style local-first media bubble: this is a SENT
                // image whose gate is showing "Tap to retry" (upload failed)
                // rather than a RECEIVED download pill — route to the retry
                // flow instead of treating it as a download tap.
                if (isImage && sent && m.mediaLocalPath != null && !m.mediaLocalPath.isEmpty()
                        && (m.mediaUrl == null || m.mediaUrl.isEmpty())) {
                    if (actionListener != null) actionListener.onRetry(m);
                    return;
                }
                if (isGif) {
                    // FIX: previously wired via a separate setOnBubbleClickListener()
                    // set inside the isGif branch — always clobbered by this
                    // single unconditional call, so the GIF download-gate
                    // pill silently did nothing.
                    final String gifUrl = m.mediaUrl != null ? m.mediaUrl : "";
                    if (gifUrl.isEmpty() || !downloadingMediaUrls.add(gifUrl)) return;
                    cv.setMediaDownloadGate(true, 0, null);
                    com.callx.app.utils.MediaCache.getWithProgress(ctx, gifUrl,
                            new com.callx.app.utils.MediaCache.ProgressCallback() {
                        @Override public void onProgress(int percent) {
                            if (h.canvasBindToken != myToken) return;
                            cv.setMediaDownloadProgress(percent);
                        }
                        @Override public void onReady(java.io.File file) {
                            downloadingMediaUrls.remove(gifUrl);
                            CACHED_FILE_CHECK.put(gifUrl, file);
                            if (h.canvasBindToken != myToken) return;
                            cv.clearMediaDownloadGate();
                            glide(ctx).asBitmap().load(file).apply(THUMB_RGB565)
                                    .override(gifStickerPx(ctx), gifStickerPx(ctx)) // PERF: match 180dp slot, avoid oversized decode
                                    .into(new com.bumptech.glide.request.target.CustomTarget<android.graphics.Bitmap>() {
                                        @Override public void onResourceReady(@NonNull android.graphics.Bitmap bmp,
                                                @Nullable com.bumptech.glide.request.transition.Transition<? super android.graphics.Bitmap> t) {
                                            if (h.canvasBindToken != myToken) return;
                                            cv.setGifBitmap(bmp);
                                        }
                                        @Override public void onLoadCleared(@Nullable android.graphics.drawable.Drawable p) {}
                                    });
                        }
                        @Override public void onError(String reason) {
                            downloadingMediaUrls.remove(gifUrl);
                            if (h.canvasBindToken != myToken) return;
                            cv.setMediaDownloadGate(false, Integer.MIN_VALUE, "Tap to retry");
                        }
                    });
                    return;
                }
                // ── Video download gate tap ──────────────────────────────────
                // Receiver taps the "Video / file-size" pill → download the
                // full video via MediaDownloadQueue, show progress, then open
                // the player from the cached local file.
                if (isVideo) {
                    final String vDlUrl = m.mediaUrl != null ? m.mediaUrl : m.text;
                    if (vDlUrl == null || vDlUrl.isEmpty() || downloadingMediaUrls.contains(vDlUrl)) return;
                    downloadingMediaUrls.add(vDlUrl);
                    cv.setMediaDownloadGate(true, 0, null);
                    MediaDownloadQueue.getInstance(ctx).enqueue(vDlUrl, null, () -> {
                        com.callx.app.utils.MediaCache.getWithProgress(ctx, vDlUrl,
                                new com.callx.app.utils.MediaCache.ProgressCallback() {
                            @Override public void onProgress(int percent) {
                                if (h.canvasBindToken != myToken) return;
                                cv.setMediaDownloadGate(true, percent, null);
                            }
                            @Override public void onReady(java.io.File file) {
                                downloadingMediaUrls.remove(vDlUrl);
                                if (h.canvasBindToken != myToken) return;
                                cv.clearMediaDownloadGate();
                                ((android.app.Activity) ctx).runOnUiThread(() -> {
                                    android.content.Intent i3 = new android.content.Intent()
                                            .setClassName(ctx.getPackageName(),
                                                    "com.callx.app.activities.MediaViewerActivity");
                                    i3.putExtra("url", vDlUrl);
                                    i3.putExtra("type", "video");
                                    i3.putExtra("localPath", file.getAbsolutePath());
                                    i3.putExtra("chatId", chatId);
                                    i3.putExtra("messageId",
                                            m.messageId != null ? m.messageId : m.id);
                                    ctx.startActivity(i3);
                                });
                            }
                            @Override public void onError(String reason) {
                                downloadingMediaUrls.remove(vDlUrl);
                                if (h.canvasBindToken != myToken) return;
                                cv.setMediaDownloadGate(false, Integer.MIN_VALUE, "Tap to retry");
                            }
                        });
                    });
                    return;
                }

                if (!isImage) return;
                final String fullUrl = m.mediaUrl != null ? m.mediaUrl : m.text;
                if (fullUrl == null || fullUrl.isEmpty() || downloadingMediaUrls.contains(fullUrl)) return;
                downloadingMediaUrls.add(fullUrl);
                cv.setMediaDownloadGate(true, 0, null);

                // Media E2E (image) — see the matching comment on the
                // auto-download path above.
                final com.callx.app.utils.MediaE2ECrypto.KeyEnvelope tapDlEnv =
                        (!sent && m.mediaKeyEnc != null)
                        ? com.callx.app.utils.MediaE2ECrypto.decryptEnvelopeForMessage(ctx, m.mediaKeyEnc,
                                m.senderId, m.messageId != null ? m.messageId : m.id)
                        : null;
                final byte[] tapDlKey    = (tapDlEnv != null) ? tapDlEnv.fullKey() : null;
                final byte[] tapDlDigest = (tapDlEnv != null) ? tapDlEnv.fullDigest : null;
                com.callx.app.utils.MediaCache.getWithProgress(ctx, fullUrl, tapDlKey, tapDlDigest,
                        new com.callx.app.utils.MediaCache.ProgressCallback() {
                    @Override public void onProgress(int percent) {
                        if (h.canvasBindToken != myToken) return;
                        cv.setMediaDownloadProgress(percent);
                    }
                    @Override public void onReady(java.io.File file) {
                        downloadingMediaUrls.remove(fullUrl);
                        if (h.canvasBindToken != myToken) return;
                        cv.clearMediaDownloadGate();
                        // PERF #4 + #1: density-aware size, store in pool on decode
                        glide(ctx).asBitmap().load(file).apply(THUMB_RGB565)
                                .override(thumbPx(ctx), thumbPx(ctx))
                                .into(new com.bumptech.glide.request.target.CustomTarget<Bitmap>() {
                                    @Override
                                    public void onResourceReady(@NonNull Bitmap resource,
                                            @Nullable com.bumptech.glide.request.transition.Transition<? super Bitmap> transition) {
                                        if (resource.getHeight() > 0) {
                                            com.callx.app.conversation.canvas.MessageBubbleCanvasView
                                                    .cacheAspectRatio(fullUrl, (float) resource.getWidth() / resource.getHeight());
                                        }
                                        // PERF #1: pool the decoded bitmap
                                        if (fullUrl != null && !fullUrl.isEmpty())
                                            DECODED_BITMAP_CACHE.put(fullUrl, resource);
                                        if (h.canvasBindToken != myToken) return;
                                        cv.setMediaBitmap(resource);
                                    }
                                    @Override
                                    public void onLoadCleared(@Nullable android.graphics.drawable.Drawable placeholder) {}
                                });
                    }
                    @Override public void onError(String reason) {
                        downloadingMediaUrls.remove(fullUrl);
                        if (h.canvasBindToken != myToken) return;
                        cv.setMediaDownloadGate(false, Integer.MIN_VALUE, "Tap to retry");
                    }
                });
            }

            @Override
            public void onMediaCellClick(int index) {
                if (!isMultiMedia || m.mediaItems == null || index < 0 || index >= m.mediaItems.size()) return;
                java.util.Map<String, Object> item = m.mediaItems.get(index);
                Object urlObj = item.get("url");
                Object thumbObj = item.get("thumbUrl");
                Object mtObj = item.get("mediaType");
                String url = urlObj instanceof String ? (String) urlObj : "";
                String thumbUrl = thumbObj instanceof String ? (String) thumbObj : "";
                String mediaType = mtObj instanceof String ? (String) mtObj : "image";
                if (url.isEmpty()) return;
                // Audio/file cells have their own dedicated tap handling
                // elsewhere (no image/video viewer applies to them) — this
                // sheet is only for image/video cells, same restriction the
                // single-media bubble already has.
                if ("audio".equals(mediaType) || "file".equals(mediaType)) return;
                try {
                    String mediaItemsJson = com.callx.app.utils.MediaItemsJsonUtil.mediaItemsToJson(m.mediaItems);
                    showMediaActionSheet(ctx, m, url, !thumbUrl.isEmpty() ? thumbUrl : url,
                            mediaType, null, mediaItemsJson, index);
                } catch (Exception ignored) {}
            }

            @Override
            public void onGroupDownloadAllClick() {
                if (!isMultiMedia || m.mediaItems == null) return;
                int visible = Math.min(m.mediaItems.size(), 9);
                for (int i = 0; i < visible; i++) {
                    downloadGroupCell(ctx, h, cv, myToken, m.mediaItems.get(i), i);
                }
            }

            @Override
            public void onGroupCellDownloadClick(int index) {
                if (!isMultiMedia || m.mediaItems == null || index < 0 || index >= m.mediaItems.size()) return;
                downloadGroupCell(ctx, h, cv, myToken, m.mediaItems.get(index), index);
            }

            @Override
            public void onAudioPlayPauseClick() {
                if (!isAudio) return;
                String aUrl = m.mediaUrl != null ? m.mediaUrl : m.text;
                toggleAudio(h, aUrl, h.getAdapterPosition());
            }

            @Override
            public void onAudioSeek(float fraction) {
                if (!isAudio) return;
                // Only meaningful if THIS bubble is the one actually
                // playing right now — mirrors seekAudio.setOnSeekListener's
                // player.seekTo() call, just resolved dynamically here
                // since the canvas click callback doesn't capture durationMs.
                if (playingPos == h.getAdapterPosition() && player != null) {
                    try {
                        int durationMs = player.getDuration();
                        if (durationMs > 0) player.seekTo((int) (fraction * durationMs));
                    } catch (Exception ignored) {}
                }
            }

            @Override
            public void onContactViewClick() {
                // Mirrors ChatContactShareController.bindBubble's
                // btnViewContact click listener exactly: open the system
                // Contacts app filtered to this phone number, falling back
                // to the dial pad if nothing can resolve that lookup intent.
                if (!isContact || m.contactPhone == null) return;
                android.net.Uri uri = android.net.Uri.withAppendedPath(
                        android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                        android.net.Uri.encode(m.contactPhone));
                android.content.Intent viewIntent = new android.content.Intent(android.content.Intent.ACTION_VIEW, uri);
                if (viewIntent.resolveActivity(ctx.getPackageManager()) != null) {
                    ctx.startActivity(viewIntent);
                } else {
                    android.content.Intent dial = new android.content.Intent(
                            android.content.Intent.ACTION_DIAL, android.net.Uri.parse("tel:" + m.contactPhone));
                    ctx.startActivity(dial);
                }
            }

            @Override
            public void onLocationOpenMapsClick() {
                // Mirrors ChatLocationShareController.bindBubble's
                // btnOpenMaps click listener exactly: try the Google Maps
                // app via a geo: intent first, falling back to a plain
                // maps.google.com URL if it isn't installed/resolvable.
                if (!isLocation) return;
                double lat = m.locationLat != null ? m.locationLat : 0;
                double lng = m.locationLng != null ? m.locationLng : 0;
                String geoUri = String.format(java.util.Locale.US, "geo:%.6f,%.6f?q=%.6f,%.6f", lat, lng, lat, lng);
                android.content.Intent mapIntent = new android.content.Intent(
                        android.content.Intent.ACTION_VIEW, android.net.Uri.parse(geoUri));
                mapIntent.setPackage("com.google.android.apps.maps");
                if (mapIntent.resolveActivity(ctx.getPackageManager()) != null) {
                    ctx.startActivity(mapIntent);
                } else {
                    android.content.Intent fallback = new android.content.Intent(android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(String.format(java.util.Locale.US,
                                    "https://maps.google.com/?q=%.6f,%.6f", lat, lng)));
                    ctx.startActivity(fallback);
                }
            }

            @Override
            public void onViewOnceClick() {
                // Fires for every variant — mirrors the legacy path's null
                // click listener on WAITING/EXPIRED (nothing happens) and
                // only actually opens the viewer for the RECEIVED
                // tap-to-open state, with the same 800ms debounce tag
                // bindViewOnceSent() used to guard against a rebind/
                // rapid-multi-tap double-fire.
                if (!isViewOnceMsg || isViewOnceExpiredState || isViewOnceWaiting) return;
                Object lastClick = cv.getTag(com.callx.app.chat.R.id.ll_bubble);
                long now = System.currentTimeMillis();
                if (lastClick instanceof Long && now - (Long) lastClick < 800) return;
                cv.setTag(com.callx.app.chat.R.id.ll_bubble, now);
                if (viewOnceOpenListener != null) viewOnceOpenListener.onOpenViewOnce(m);
            }

            @Override
            public void onSeenBubbleClick() {
                // Mirrors bindStatusSeenBubble/bindReelSeenBubble's
                // openStatus/openReel click listeners exactly — same
                // deep-link intents, just fired from the canvas card tap.
                if (!isSeen) return;
                if (isReelSeen) {
                    if (m.reelId == null || m.reelId.isEmpty()) return;
                    android.content.Intent intent = new android.content.Intent(
                            com.callx.app.utils.Constants.ACTION_OPEN_REEL);
                    intent.putExtra("reelId", m.reelId);
                    intent.setPackage(ctx.getPackageName());
                    ctx.startActivity(intent);
                } else {
                    String ownerUid = (m.statusOwnerUid != null && !m.statusOwnerUid.isEmpty())
                            ? m.statusOwnerUid : m.senderId;
                    String ownerName = m.statusOwnerName != null ? m.statusOwnerName
                            : (m.senderName != null ? m.senderName : "");
                    if (ownerUid == null || ownerUid.isEmpty()) return;
                    android.content.Intent intent = new android.content.Intent(
                            com.callx.app.utils.Constants.ACTION_OPEN_STATUS);
                    intent.putExtra("ownerUid", ownerUid);
                    intent.putExtra("ownerName", ownerName);
                    intent.setPackage(ctx.getPackageName());
                    try {
                        ctx.startActivity(intent);
                    } catch (android.content.ActivityNotFoundException e) {
                        android.widget.Toast.makeText(ctx, "Status viewer not available",
                                android.widget.Toast.LENGTH_SHORT).show();
                    }
                }
            }

            @Override
            public void onGifClick() {
                // FIX: see the NOTE left in the isGif branch above — this
                // used to be wired via a listener that always got clobbered.
                if (!isGif) return;
                final String gifUrl = m.mediaUrl != null ? m.mediaUrl : "";
                android.content.Intent i = new android.content.Intent().setClassName(
                        ctx.getPackageName(), "com.callx.app.activities.MediaViewerActivity");
                i.putExtra("url", gifUrl);
                i.putExtra("type", "gif");
                if (chatId != null) i.putExtra("chatId", chatId);
                String mid = m.messageId != null ? m.messageId : m.id;
                if (mid != null) i.putExtra("messageId", mid);
                com.callx.app.utils.MediaViewerSourceRect.attach(i, cv.getMediaRectOnScreen());
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                try { ctx.startActivity(i); } catch (Exception ignored) {}
            }

            @Override
            public void onFileDownloadClick() {
                // FIX: see the NOTE left in the isFile branch above — this
                // used to be wired via a listener that always got clobbered.
                if (!isFile) return;
                final String fileUrl = m.mediaUrl != null ? m.mediaUrl : "";
                if (fileUrl.isEmpty() || !downloadingMediaUrls.add(fileUrl)) return;
                cv.setFileDownloadState(true, -1);
                com.callx.app.utils.MediaCache.getWithProgress(ctx, fileUrl,
                        new com.callx.app.utils.MediaCache.ProgressCallback() {
                    @Override public void onProgress(int percent) {
                        if (h.canvasBindToken == myToken) cv.setFileDownloadState(true, percent);
                    }
                    @Override public void onReady(java.io.File file) {
                        downloadingMediaUrls.remove(fileUrl);
                        if (h.canvasBindToken == myToken) cv.setFileCached(true);
                    }
                    @Override public void onError(String reason) {
                        downloadingMediaUrls.remove(fileUrl);
                        if (h.canvasBindToken == myToken) cv.setFileDownloadState(false, 0);
                    }
                });
            }

            @Override
            public void onFileOpenClick() {
                // FIX: see the NOTE left in the isFile branch above — this
                // used to be wired via a listener that always got clobbered.
                if (!isFile) return;
                final String fileUrl = m.mediaUrl != null ? m.mediaUrl : "";
                java.io.File cached = MediaCache.getCached(ctx, fileUrl);
                if (cached == null) return;
                try {
                    final String fileNameForOpen = m.fileName != null ? m.fileName : "File";
                    final String mimeForOpen = guessMimeFromFileName(fileNameForOpen);
                    android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                            ctx, ctx.getPackageName() + ".provider", cached);
                    android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW);
                    intent.setDataAndType(uri, mimeForOpen.isEmpty() ? "*/*" : mimeForOpen);
                    intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(intent);
                } catch (Exception ignored) { /* no app handles this type */ }
            }

            @Override
            public void onEditedTagClick() {
                // Mirrors the legacy tv_time click listener — tapping the
                // "✏️ edited" tag opens the edit-history sheet.
                if (actionListener != null) actionListener.onShowEditHistory(m);
            }

            @Override
            public void onPollOptionClick(int optionIndex) {
                // FIX: this used to be wired via a SECOND, poll-only
                // setOnBubbleClickListener() call made earlier in the
                // isPoll branch above — but this single unconditional
                // setOnBubbleClickListener() call (which runs for every
                // message type, poll included) always ran after it and
                // silently replaced it, so poll votes never fired. Voting
                // now lives here, in the one listener that actually stays
                // attached.
                if (!isPoll) return;
                if (Boolean.TRUE.equals(m.pollClosed)) {
                    android.widget.Toast.makeText(ctx, "This poll is closed", android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                if (actionListener != null) actionListener.onPollVote(m, optionIndex);
            }
        });

        // PERF #8: Hardware layer — selectively promote complex bubbles to a
        // GPU-resident RenderNode texture.  On complex items (reactions badge
        // with shadow, image/video thumbnail, media-group grid, reel card)
        // Android must composite many draw calls per scroll frame.  A hardware
        // layer rasterises those calls once into an offscreen texture that the
        // GPU reuses on every subsequent frame until the view is invalidated —
        // cutting per-frame GPU work from O(draw-calls) to O(1) blit.
        //
        // Plain text / audio / call-entry bubbles stay LAYER_TYPE_NONE:
        // on those, the overhead of allocating + uploading a texture is higher
        // than the (few, cheap) draw calls it would replace.
        boolean hasReactions = m.reactions != null && !m.reactions.isEmpty();
        boolean needsHwLayer = hasReactions || isImage || isVideo || isMultiMedia || isReelShare;
        int targetLayerType = needsHwLayer ? View.LAYER_TYPE_HARDWARE : View.LAYER_TYPE_NONE;
        if (h.lastCanvasLayerType != targetLayerType) {
            cv.setLayerType(targetLayerType, null);
            h.lastCanvasLayerType = targetLayerType;
        }
    }

    /**
     * Starts (or no-ops if already in flight, or not an image cell) the
     * manual download for one cell inside a RECEIVED media-group grid —
     * mirrors MediaGroupLayoutHelper.startCellDownload(): dedupes against
     * the same downloadingMediaUrls set the single-image download pill
     * uses, drives the cell's spinner badge live, and swaps in the
     * full-res bitmap once ready.
     */
    private void downloadGroupCell(Context ctx, VH h, com.callx.app.conversation.canvas.MessageBubbleCanvasView cv,
                                    int myToken, java.util.Map<String, Object> item, int index) {
        Object mtObj = item.get("mediaType");
        String mediaType = mtObj instanceof String ? (String) mtObj : "image";
        if (!"image".equals(mediaType)) return; // video/audio/file cells never gate
        Object urlObj = item.get("url");
        String url = urlObj instanceof String ? (String) urlObj : "";
        if (url.isEmpty() || downloadingMediaUrls.contains(url)) return;
        downloadingMediaUrls.add(url);
        cv.setGroupCellDownloading(index, true);

        // Enqueue through the receiver-side download queue (max-3-concurrent,
        // network-aware) so group-grid downloads don't flood bandwidth alongside
        // single-image downloads happening in the same scroll viewport.
        MediaDownloadQueue.getInstance(ctx).enqueue(url, null, () -> {
            com.callx.app.utils.MediaCache.getWithProgress(ctx, url,
                    new com.callx.app.utils.MediaCache.ProgressCallback() {
                @Override public void onProgress(int percent) {
                    // Grid cells are too small for a "%" label, but the badge's
                    // ring itself now sweeps live with real progress instead of
                    // a static partial-arc (see MessageBubbleCanvasView.drawProgressRing).
                    if (h.canvasBindToken == myToken) cv.setGroupCellProgress(index, percent);
                }
                @Override public void onReady(java.io.File file) {
                    downloadingMediaUrls.remove(url);
                    CACHED_FILE_CHECK.put(url, file);
                    if (h.canvasBindToken != myToken) return; // holder recycled/rebound since this started
                    glide(ctx).asBitmap().load(file).apply(THUMB_RGB565).override(240, 240)
                            .into(new com.bumptech.glide.request.target.CustomTarget<Bitmap>() {
                                @Override
                                public void onResourceReady(@NonNull Bitmap resource,
                                        @Nullable com.bumptech.glide.request.transition.Transition<? super Bitmap> transition) {
                                    if (h.canvasBindToken != myToken) return;
                                    cv.setMediaGroupBitmap(index, resource);
                                    cv.markGroupCellDownloaded(index);
                                }
                                @Override
                                public void onLoadCleared(@Nullable android.graphics.drawable.Drawable placeholder) {}
                            });
                }
                @Override public void onError(String reason) {
                    downloadingMediaUrls.remove(url);
                    if (h.canvasBindToken != myToken) return;
                    cv.setGroupCellDownloading(index, false); // stays pending — tap the cell again to retry
                }
            });
        });
    }

    private void bindMessage(@NonNull VH h, @NonNull Message m, int position) {
        Context ctx = h.itemView.getContext();
        boolean sent = currentUid.equals(m.senderId);

        // ── "Someone is viewing this message right now" dot ───────────────
        if (h.viewSeenDot != null) {
            String mid = m.messageId != null ? m.messageId : m.id;
            boolean viewing = mid != null && currentlyViewedMessageIds.contains(mid);
            h.viewSeenDot.setVisibility(viewing ? View.VISIBLE : View.GONE);
        }

        // ── "Someone is currently playing this voice note / video" badge ──
        // Instagram-DM-style live indicator — independent of the dot above
        // (that one means "scrolled into view", this one means "actually
        // pressed play right now"). Audio/video only; harmless no-op for
        // text/image/poll bubbles since the badge just stays hidden.
        if (h.tvListeningBadge != null) {
            String mid = m.messageId != null ? m.messageId : m.id;
            boolean playing = mid != null && currentlyPlayingMessageIds.contains(mid);
            if (playing) {
                boolean isVideoMsg = "video".equals(m.type);
                h.tvListeningBadge.setText(isVideoMsg ? "▶ watching…" : "🎧 listening…");
            }
            h.tvListeningBadge.setVisibility(playing ? View.VISIBLE : View.GONE);
        }

        // ── Theme-aware bubble background ─────────────────────────────────
        // Instagram/WhatsApp style: image, gif, video, reel_share, and
        // grouped media (multi_media) are BUBBLELESS — no chat-bubble
        // background, the media card itself is the visual frame. Mirrors
        // MessageAdapter's (1:1 chat) existing behavior, which group chat
        // was missing entirely.
        android.view.View llBubble = h.llBubble;
        String bMsgType = m.type != null ? m.type : "text";
        boolean isMediaMsg = "image".equals(bMsgType) || "gif".equals(bMsgType)
                || "sticker".equals(bMsgType)
                || "video".equals(bMsgType) || "reel_share".equals(bMsgType)
                || "multi_media".equals(bMsgType);
        try {
            if (llBubble != null) {
                if (isMediaMsg) {
                    llBubble.setBackground(null);
                    llBubble.setPadding(0, 0, 0, 0);
                    // Force a real re-apply next time this holder shows a
                    // text bubble — background is null right now, not the
                    // GradientDrawable the cache key would imply.
                    h.lastBubbleReplyState = -1;
                } else {
                    boolean hasReply = m.replyToId != null && !m.replyToId.isEmpty();
                    int replyState = hasReply ? 1 : 0;
                    // PERF/RAM: skip mutate()+setBackground() entirely when
                    // this holder already has the right bubble drawable from
                    // its previous bind — avoids an allocation + an
                    // invalidate/draw pass on nearly every scroll-triggered
                    // rebind, since hasReply flips far less often than the
                    // row itself gets recycled.
                    if (h.lastBubbleReplyState != replyState) {
                        com.callx.app.utils.ChatThemeManager
                                .get(ctx)
                                .applyBubble(llBubble, sent, bMsgType, hasReply);
                        h.lastBubbleReplyState = replyState;
                    }
                }
            }
        } catch (Exception ignored) {}

        // Dark scrim pill behind the timestamp/tick footer so it stays
        // readable when sitting directly on top of a photo/video thumbnail
        // instead of inside a solid-color bubble.
        try {
            android.view.View footer = h.itemView.findViewById(R.id.ll_msg_footer);
            if (footer != null) {
                footer.setBackgroundResource(isMediaMsg
                        ? R.drawable.bg_media_timestamp : 0);
            }
        } catch (Exception ignored) {}

        // ── "Someone is currently composing a reply to THIS message" glow ──
        // Finer-grained sibling of the viewing-dot above: lights up only the
        // exact bubble being replied to (not just "screen open" or "this
        // message is in view"), fed by chatTypingReply/{id}/{uid}=messageId.
        if (llBubble != null) {
            String mid = m.messageId != null ? m.messageId : m.id;
            boolean isReplyTarget = mid != null && replyTargetMessageIds.contains(mid);
            llBubble.setForeground(isReplyTarget
                    ? ContextCompat.getDrawable(ctx, R.drawable.bg_reply_target_highlight)
                    : null);
        }

        // Reset visibility
        h.tvMessage.setVisibility(View.GONE);
        if (h.ivImage    != null) h.ivImage.setVisibility(View.GONE);
        if (h.llAudio    != null) h.llAudio.setVisibility(View.GONE);
        if (h.llFile     != null) h.llFile.setVisibility(View.GONE);
        if (h.llPoll     != null) h.llPoll.setVisibility(View.GONE);
        if (h.llReelShare!= null) h.llReelShare.setVisibility(View.GONE);
        if (h.llMediaGroup != null) h.llMediaGroup.setVisibility(View.GONE);
        if (h.llContact  != null) h.llContact.setVisibility(View.GONE);
        if (h.llLocation != null) h.llLocation.setVisibility(View.GONE);
        if (h.tvTime     != null) h.tvTime.setVisibility(View.VISIBLE);

        // ── Quick Forward Button — media/link messages pe dikhao ──────────
        if (h.btnQuickForward != null) {
            String mt = m.type != null ? m.type : "text";
            boolean showFwd = mt.equals("image") || mt.equals("video") || mt.equals("audio")
                    || mt.equals("file") || mt.equals("reel_share") || mt.equals("reel_link")
                    || mt.equals("multi_media")
                    || (mt.equals("text") && m.text != null
                        && (m.text.contains("http://") || m.text.contains("https://")));
            h.btnQuickForward.setVisibility(showFwd ? View.VISIBLE : View.GONE);
            if (showFwd) {
                final Message fwdMsg = m;
                h.btnQuickForward.setOnClickListener(v -> {
                    if (actionListener != null) actionListener.onForward(fwdMsg);
                });
            }
        }

        // Timestamp — append "(edited)" when applicable
        if (h.tvTime != null && m.timestamp > 0) {
            String timeStr = formatTime(m.timestamp);
            boolean isEdited = Boolean.TRUE.equals(m.edited);
            if (isEdited) timeStr = timeStr + "  \u270F\uFE0F edited";
            h.tvTime.setText(timeStr);
            // Tap the "✏️ edited" tag to view every prior version of the text.
            if (isEdited) {
                h.tvTime.setOnClickListener(v -> {
                    if (actionListener != null) actionListener.onShowEditHistory(m);
                });
            } else {
                h.tvTime.setOnClickListener(null);
                h.tvTime.setClickable(false);
            }
        }

        // ── REPLY PREVIEW (SwipeReplySystem v1) ─────────────────────────
        if (h.llReplyPreview != null) {
            boolean hasReply = m.replyToId != null && !m.replyToId.isEmpty();
            h.llReplyPreview.setVisibility(hasReply ? View.VISIBLE : View.GONE);
            if (hasReply) {
                if (h.tvReplySender != null)
                    h.tvReplySender.setText(
                            m.replyToSenderName != null ? m.replyToSenderName : "");
                if (h.tvReplyText != null)
                    h.tvReplyText.setText(
                            m.replyToText != null ? m.replyToText : "[Original message]");
                // Thumbnail
                if (h.ivReplyThumb != null) {
                    String thumbUrl = m.replyToMediaUrl;
                    if (thumbUrl != null && !thumbUrl.isEmpty()) {
                        h.ivReplyThumb.setVisibility(View.VISIBLE);
                        glide(ctx)
                                .load(thumbUrl)
                                .apply(THUMB_RGB565)
                                .override(120, 120)
                                .centerCrop()
                                .into(h.ivReplyThumb);
                    } else {
                        h.ivReplyThumb.setVisibility(View.GONE);
                    }
                }
                // Click → scroll to original message
                final String replyId = m.replyToId;
                final String replySenderId = m.senderId;
                h.llReplyPreview.setOnClickListener(v -> {
                    if (actionListener != null) {
                        actionListener.onNavigateToOriginal(replyId, replySenderId);
                    }
                });
            } else {
                h.llReplyPreview.setOnClickListener(null);
            }
        }

        // ── Reactions display ─────────────────────────────────────────
        bindReactionsOnly(h, m);

        // ── Forwarded label ─────────────────────────────────────────────
        if (h.tvForwarded != null) {
            boolean fwd = m.forwardedFrom != null && !m.forwardedFrom.isEmpty();
            h.tvForwarded.setVisibility(fwd ? View.VISIBLE : View.GONE);
            if (fwd) h.tvForwarded.setText("\u21AA Forwarded from " + m.forwardedFrom);
        }

        // Sender name (group chats) — also show broadcast badge for 1:1 received broadcast messages
        if (!sent && h.tvSenderName != null) {
            boolean isBroadcast = Boolean.TRUE.equals(m.broadcast);
            if (isGroup) {
                h.tvSenderName.setVisibility(View.VISIBLE);
                String sn = m.senderName != null ? m.senderName : "Member";
                // Prepend 📢 if this group message also carries the broadcast flag
                h.tvSenderName.setText(isBroadcast ? "📢 " + sn : sn);
            } else if (isBroadcast) {
                // 1:1 chat — show sender name row solely to display the broadcast badge
                h.tvSenderName.setVisibility(View.VISIBLE);
                h.tvSenderName.setText("📢 Broadcast");
            } else {
                h.tvSenderName.setVisibility(View.GONE);
            }
        } else if (h.tvSenderName != null) {
            h.tvSenderName.setVisibility(View.GONE);
        }

        // Deleted message
        if (Boolean.TRUE.equals(m.deleted)) {
            h.tvMessage.setVisibility(View.VISIBLE);
            h.tvMessage.setText(sent ? "You deleted this message" : "This message was deleted");
            h.tvMessage.setAlpha(0.6f);
            return;
        }

        // ── Render by type ───────────────────────────────────────
        String type = m.type != null ? m.type : "text";
        switch (type) {
            case "image":
            case "gif":
            case "sticker":
                if (h.ivImage != null) {
                    h.ivImage.setVisibility(View.VISIBLE);
                    String fullUrl  = m.mediaUrl != null ? m.mediaUrl : m.text;
                    String thumbUrl = m.thumbnailUrl;
                    boolean isGifMsg = "gif".equals(m.type) || "sticker".equals(m.type);

                    // PERF FIX (WhatsApp-style lazy media): bubble shows ONLY
                    // the thumbnail — no eager full-res load/crossfade here.
                    // Scrolling through a chat with many image bubbles used
                    // to fire a full-res Glide request (720x720, disk-cached)
                    // for EVERY bound bubble, on top of the thumb — extra
                    // network + decode work competing with scroll, causing
                    // jank on fast flings through media-heavy chats.
                    // Full resolution now only loads on demand: when the
                    // user actually taps the bubble, MediaViewerActivity
                    // (opened via showImageActionSheet below) loads fullUrl
                    // itself. This matches WhatsApp: the chat thread only
                    // ever shows the lightweight thumbnail; full quality is
                    // fetched on open, not upfront.
                    if (thumbUrl != null && !thumbUrl.isEmpty() && !isGifMsg) {
                        // FIX: same flicker root cause as reel-share/seen-bubble/
                        // contact/location above — this was the one remaining
                        // spot with no in-memory cache check, so EVERY rebind
                        // of an on-screen image bubble (e.g. the Room→PagingSource
                        // requery that fires on every new message send, per
                        // CHAT_OPEN_LAG_FIX_NOTES.md) blanked it to the grey
                        // skeleton placeholder for a frame before Glide's
                        // memory/disk-cache hit swapped the real thumbnail back
                        // in — visible as "flicker / junk / rebuilding" on any
                        // chat that has image messages, never on text-only
                        // chats (text has no async placeholder step). A pool
                        // hit now sets the bitmap straight onto the ImageView
                        // with zero placeholder flash; a genuine miss (first
                        // load of this thumb) falls through to Glide exactly
                        // as before.
                        final String imgPoolKey = poolKey(thumbUrl, 200, 200);
                        Bitmap imgPoolHit = DECODED_BITMAP_CACHE.get(imgPoolKey);
                        if (imgPoolHit != null && !imgPoolHit.isRecycled()) {
                            h.ivImage.setImageBitmap(imgPoolHit);
                        } else {
                            glide(ctx)
                                .asBitmap()
                                .load(thumbUrl)
                                .apply(THUMB_RGB565)
                                .override(200, 200)
                                .placeholder(R.drawable.bg_skeleton_rect)
                                .error(R.drawable.bg_skeleton_rect)
                                .listener(new com.bumptech.glide.request.RequestListener<Bitmap>() {
                                    @Override
                                    public boolean onLoadFailed(@Nullable com.bumptech.glide.load.engine.GlideException e,
                                            Object model, com.bumptech.glide.request.target.Target<Bitmap> target,
                                            boolean isFirstResource) {
                                        return false;
                                    }
                                    @Override
                                    public boolean onResourceReady(Bitmap resource, Object model,
                                            com.bumptech.glide.request.target.Target<Bitmap> target,
                                            com.bumptech.glide.load.DataSource dataSource, boolean isFirstResource) {
                                        DECODED_BITMAP_CACHE.put(imgPoolKey, resource);
                                        return false; // let Glide still deliver it to h.ivImage
                                    }
                                })
                                .into(h.ivImage);
                        }
                    } else if (!isGifMsg) {
                        // PERF FIX: this used to be the real remaining leak —
                        // any message missing a real thumbnailUrl (thumb
                        // upload failed, or an older client sent it without
                        // one) fell straight through to an EAGER full-res
                        // (720x720) download + a background MediaCache.get()
                        // full-file fetch, for every such bubble, on chat
                        // open/scroll. That's why full images kept
                        // downloading immediately even after the main
                        // thumbnail path was fixed.
                        //
                        // Fix: derive a lightweight Cloudinary transform URL
                        // from fullUrl (no thumbnailUrl needed, no extra
                        // upload — see CloudinaryUploader.deriveThumbUrl) and
                        // use that for the bubble instead. Full resolution
                        // still only loads on tap, via showImageActionSheet
                        // below, which is already passed the real fullUrl.
                        String derivedThumb = com.callx.app.utils.CloudinaryUploader
                                .deriveThumbUrl(fullUrl, 200);
                        // FIX: same cache-first pattern as the thumbUrl branch
                        // above — otherwise this fallback path reintroduces
                        // the identical rebind-flicker for any image whose
                        // thumbnailUrl upload failed.
                        final String derivedPoolKey = poolKey(derivedThumb, 200, 200);
                        Bitmap derivedPoolHit = DECODED_BITMAP_CACHE.get(derivedPoolKey);
                        if (derivedPoolHit != null && !derivedPoolHit.isRecycled()) {
                            h.ivImage.setImageBitmap(derivedPoolHit);
                        } else {
                            glide(ctx)
                                .asBitmap()
                                .load(derivedThumb)
                                .apply(THUMB_RGB565)
                                .override(200, 200)
                                .placeholder(R.drawable.bg_skeleton_rect)
                                .error(R.drawable.bg_skeleton_rect)
                                .listener(new com.bumptech.glide.request.RequestListener<Bitmap>() {
                                    @Override
                                    public boolean onLoadFailed(@Nullable com.bumptech.glide.load.engine.GlideException e,
                                            Object model, com.bumptech.glide.request.target.Target<Bitmap> target,
                                            boolean isFirstResource) {
                                        return false;
                                    }
                                    @Override
                                    public boolean onResourceReady(Bitmap resource, Object model,
                                            com.bumptech.glide.request.target.Target<Bitmap> target,
                                            com.bumptech.glide.load.DataSource dataSource, boolean isFirstResource) {
                                        DECODED_BITMAP_CACHE.put(derivedPoolKey, resource);
                                        return false;
                                    }
                                })
                                .into(h.ivImage);
                        }
                    } else {
                        // GIF: asGif() se URL directly load karo — MediaCache file use
                        // mat karo kyunki file mein .gif extension nahi hogi, Glide
                        // decode fail karta hai. Glide DiskCache GIF cache kar lega.
                        glide(ctx)
                            .asGif()
                            .load(fullUrl)
                            .apply(THUMB_RGB565)
                            .override(thumbPx(ctx), thumbPx(ctx)) // PERF #4: density-aware; GIFs are heavy at full res
                            .placeholder(R.drawable.bg_skeleton_rect)
                            .error(R.drawable.bg_skeleton_rect)
                            .into(h.ivImage);
                    }

                    // Click → WhatsApp-style image action bottom sheet, UNLESS
                    // we're in multi-select mode — then a tap should toggle
                    // this item's selection (same as text bubbles), not jump
                    // straight into the full-screen viewer.
                    h.ivImage.setOnClickListener(v -> {
                        if (multiSelectMode) {
                            h.itemView.callOnClick();
                            return;
                        }
                        showImageActionSheet(ctx, m, fullUrl, thumbUrl != null ? thumbUrl : fullUrl,
                                com.callx.app.utils.MediaViewerSourceRect.ofView(h.ivImage));
                    });
                    // Long-press → GAP FIX: this used to jump STRAIGHT to
                    // showActionBottomSheet(), skipping multi-select mode
                    // entirely — but that action sheet's option list (Reply/
                    // Copy/Star/Pin/Forward/Delete) has no "Info" entry at all.
                    // Text bubbles instead enter multi-select mode first (see
                    // h.itemView's long-click below), which shows the selection
                    // toolbar with an Info button wired to
                    // showMessageInfoDialog() — that's the ONLY way "Message
                    // Info" was reachable, and images never had a path to it.
                    // Now mirrors text exactly: first long-press selects (Info
                    // button available in the toolbar); a second long-press
                    // while already selecting still opens the full action sheet.
                    h.ivImage.setOnLongClickListener(v -> {
                        v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                        if (!multiSelectMode) {
                            enterMultiSelectMode(m);
                        } else if (actionListener != null) {
                            showActionBottomSheet(ctx, m);
                        }
                        return true;
                    });

                    // ── MANUAL DOWNLOAD OVERLAY (WhatsApp-style) ─────────────
                    // Received images only — bubble keeps showing the blurred
                    // low-res thumbnail underneath until the user taps the
                    // pill; only then is fullUrl actually fetched. Sent
                    // messages (sender's own upload) and GIFs skip this.
                    if (h.fl_download_overlay != null && !sent && !isGifMsg) {
                        bindDownloadOverlay(ctx, h, fullUrl, m);
                    } else if (h.fl_download_overlay != null) {
                        h.fl_download_overlay.setVisibility(View.GONE);
                    }

                    // ── Voice Caption on Photo (image + attached voice note) ──
                    // Only reached at all when isCanvasEligible() routed this
                    // message here for exactly this reason (m.voiceUrl set).
                    bindVoiceOnImage(h, m, position);
                }
                break;
            // ── MULTI MEDIA (WhatsApp-style grid, multi-image send) ──────
            case "multi_media": {
                if (h.llMediaGroup != null && m.mediaItems != null && !m.mediaItems.isEmpty()) {
                    h.llMediaGroup.setVisibility(View.VISIBLE);
                    String groupMsgId = (m.id != null && !m.id.isEmpty()) ? m.id : m.messageId;
                    MediaGroupLayoutHelper.populate(ctx, h.llMediaGroup, m.mediaItems, m.caption,
                            chatId, groupMsgId);
                    // GAP FIX: same bug as the single-image/video bubbles —
                    // this jumped STRAIGHT to showActionBottomSheet(), whose
                    // option list (Reply/Copy/Star/Pin/Forward/Delete) has no
                    // "Info" entry at all. Now mirrors every other bubble
                    // type: first long-press enters multi-select mode (Info
                    // button available in the selection toolbar); a second
                    // long-press while already selecting opens the full
                    // action sheet.
                    h.llMediaGroup.setOnLongClickListener(v -> {
                        v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                        if (!multiSelectMode) {
                            enterMultiSelectMode(m);
                        } else if (actionListener != null) {
                            showActionBottomSheet(ctx, m);
                        }
                        return true;
                    });
                } else {
                    // Defensive fallback — if mediaItems somehow came through empty
                    // (e.g. legacy/partial data), at least show something instead
                    // of a blank bubble.
                    h.tvMessage.setVisibility(View.VISIBLE);
                    h.tvMessage.setText("\uD83D\uDCF7 Photos");
                }
                break;
            }
            case "video": {
                // POLISH: Use fl_video + iv_video_thumb (thumbnail + play overlay)
                // Prefer thumbnailUrl (Cloudinary thumb) over raw video URL for preview
                final String vMid = m.messageId != null ? m.messageId : m.id;
                ensureVideoInflated(h); // ViewStub lazy inflate
                if (h.flVideo != null && h.ivVideoThumb != null) {
                    h.flVideo.setVisibility(View.VISIBLE);
                    if (h.ivImage != null) h.ivImage.setVisibility(View.GONE);
                    String vUrl   = m.mediaUrl != null ? m.mediaUrl : m.text;
                    // BUG FIX (v44): Never fall back to the raw video URL —
                    // Glide.asBitmap() on an mp4 URL silently downloads the
                    // whole file trying to decode a frame.  Use null so the
                    // skeleton placeholder shows until thumbnailUrl is fixed
                    // by the @PropertyName mapping.
                    String thumbUrl = (m.thumbnailUrl != null && !m.thumbnailUrl.isEmpty())
                            ? m.thumbnailUrl : null;
                    if (thumbUrl != null) {
                    glide(ctx)
                        .load(thumbUrl)
                        .apply(THUMB_RGB565)
                        .thumbnail(0.1f) // PERF: render 10% low-res frame instantly, then upgrade
                        .override(thumbPx(ctx), thumbPx(ctx)) // PERF #4: density-aware size
                        .placeholder(R.drawable.bg_skeleton_rect)
                        .centerCrop()
                        .into(h.ivVideoThumb);
                    } // else: BlurHash / skeleton stays until thumb URL arrives
                    // Duration overlay
                    if (h.tvDuration != null && m.duration != null && m.duration > 0) {
                        long secs = m.duration / 1000;
                        h.tvDuration.setText(String.format(
                                java.util.Locale.US, "%d:%02d", secs / 60, secs % 60));
                        h.tvDuration.setVisibility(View.VISIBLE);
                    }
                    // GAP FIX: this block never had a setOnLongClickListener
                    // at all — flVideo was only setOnClickListener (play),
                    // which makes it clickable but NOT long-clickable, so a
                    // long-press here did literally nothing (no multi-select,
                    // no action sheet, no way to reach "Message Info"). Same
                    // fix as the single-image bubble: tap opens the player
                    // normally, but a tap while already in multi-select mode
                    // toggles selection instead; long-press enters
                    // multi-select (Info button in the toolbar) the first
                    // time, and opens the full action sheet on a second
                    // long-press while already selecting.
                    h.flVideo.setOnClickListener(v -> {
                        if (multiSelectMode) {
                            h.itemView.callOnClick();
                            return;
                        }
                        Intent i = new Intent().setClassName(ctx.getPackageName(),
                                "com.callx.app.activities.MediaViewerActivity");
                        i.putExtra("url", vUrl);
                        i.putExtra("type", "video");
                        // WhatsApp-style local-first: render from the original
                        // local file (full quality) as long as it's still on
                        // the device, falling back to `url` otherwise.
                        if (m.mediaLocalPath != null && !m.mediaLocalPath.isEmpty()) {
                            i.putExtra("localPath", m.mediaLocalPath);
                        }
                        // Lets MediaViewerActivity publish playback presence
                        // (chatPlayback/{chatId}/{uid}=messageId) while the
                        // video is actually playing — null-safe extras.
                        i.putExtra("chatId", chatId);
                        i.putExtra("messageId", vMid);
                        com.callx.app.utils.MediaViewerSourceRect.attach(i, h.flVideo);
                        ctx.startActivity(i);
                    });
                    h.flVideo.setOnLongClickListener(v -> {
                        v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                        if (!multiSelectMode) {
                            enterMultiSelectMode(m);
                        } else if (actionListener != null) {
                            showActionBottomSheet(ctx, m);
                        }
                        return true;
                    });
                } else if (h.ivImage != null) {
                    // Fallback: layout without fl_video — show thumbnail in ivImage
                    h.ivImage.setVisibility(View.VISIBLE);
                    String vUrl     = m.mediaUrl != null ? m.mediaUrl : m.text;
                    String thumbUrl = (m.thumbnailUrl != null && !m.thumbnailUrl.isEmpty())
                            ? m.thumbnailUrl : vUrl;
                    glide(ctx).load(thumbUrl)
                        .apply(THUMB_RGB565)
                        .override(thumbPx(ctx), thumbPx(ctx)) // PERF #4: density-aware size
                        .placeholder(R.drawable.bg_skeleton_rect)
                        .into(h.ivImage);
                    h.ivImage.setOnClickListener(v -> {
                        if (multiSelectMode) {
                            h.itemView.callOnClick();
                            return;
                        }
                        Intent i = new Intent().setClassName(ctx.getPackageName(),
                                "com.callx.app.activities.MediaViewerActivity");
                        i.putExtra("url", vUrl);
                        i.putExtra("type", "video");
                        if (m.mediaLocalPath != null && !m.mediaLocalPath.isEmpty()) {
                            i.putExtra("localPath", m.mediaLocalPath);
                        }
                        i.putExtra("chatId", chatId);
                        i.putExtra("messageId", vMid);
                        com.callx.app.utils.MediaViewerSourceRect.attach(i, h.ivImage);
                        ctx.startActivity(i);
                    });
                    // GAP FIX: same missing long-press wiring as the flVideo
                    // branch above — this fallback thumbnail had no way to
                    // reach multi-select/Message Info either.
                    h.ivImage.setOnLongClickListener(v -> {
                        v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                        if (!multiSelectMode) {
                            enterMultiSelectMode(m);
                        } else if (actionListener != null) {
                            showActionBottomSheet(ctx, m);
                        }
                        return true;
                    });
                }
                break;
            }
            case "audio":
                ensureAudioInflated(h, ctx, sent); // ViewStub lazy inflate
                if (h.llAudio != null && h.btnPlayPause != null) {
                    h.llAudio.setVisibility(View.VISIBLE);
                    String aUrl = m.mediaUrl != null ? m.mediaUrl : m.text;
                    if (h.seekAudio != null) {
                        h.seekAudio.setSeed(aUrl);
                        h.seekAudio.setProgress(0f);
                    }
                    final int pos = position;
                    h.btnPlayPause.setOnClickListener(v -> toggleAudio(h, aUrl, pos));
                    // Feature 4: Speed toggle chip — 1x → 1.5x → 2x → 0.5x → 1x
                    if (h.btnAudioSpeed != null) {
                        h.btnAudioSpeed.setVisibility(View.VISIBLE);
                        h.btnAudioSpeed.setText("1×");
                        h.btnAudioSpeed.setOnClickListener(v -> {
                            // Cycle speed: 1.0 → 1.5 → 2.0 → 0.5 → 1.0
                            if      (currentPlaybackSpeed == 1.0f)  currentPlaybackSpeed = 1.5f;
                            else if (currentPlaybackSpeed == 1.5f)  currentPlaybackSpeed = 2.0f;
                            else if (currentPlaybackSpeed == 2.0f)  currentPlaybackSpeed = 0.5f;
                            else                                     currentPlaybackSpeed = 1.0f;
                            // Format label
                            String label = (currentPlaybackSpeed == 0.5f) ? "0.5×"
                                         : (currentPlaybackSpeed == 1.0f) ? "1×"
                                         : (currentPlaybackSpeed == 1.5f) ? "1.5×" : "2×";
                            h.btnAudioSpeed.setText(label);
                            // Apply to active player (API 23+)
                            if (player != null) {
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                                    try {
                                        android.media.PlaybackParams pp = new android.media.PlaybackParams();
                                        pp.setSpeed(currentPlaybackSpeed);
                                        player.setPlaybackParams(pp);
                                    } catch (Exception ignored) {}
                                }
                            }
                        });
                    }
                    // FIX v14: Audio preload — MediaStreamCache se pehle 512KB cache karo
                    // Taaki play button press karne par turant start ho, buffer nahi kare
                    java.io.File cachedAudio = MediaCache.getCached(ctx, aUrl);
                    if (cachedAudio == null && aUrl != null && !aUrl.isEmpty()) {
                        com.callx.app.cache.MediaStreamCache.getInstance(ctx)
                            .preloadPartial(aUrl, new com.callx.app.cache.MediaStreamCache.DownloadCallback() {
                                @Override public void onComplete(java.io.File file) {
                                    android.util.Log.d("PagingAdapter", "Audio preloaded: " + file.getName());
                                }
                                @Override public void onError(String error) {}
                                @Override public void onProgress(int percent) {}
                            });
                    }
                } else {
                    // Fallback if no audio layout
                    h.tvMessage.setVisibility(View.VISIBLE);
                    h.tvMessage.setText("Audio message");
                }
                break;
            case "file":
            case "document":
                ensureFileInflated(h, ctx, sent); // ViewStub lazy inflate
                if (h.llFile != null && h.tvFileName != null) {
                    h.llFile.setVisibility(View.VISIBLE);
                    String fName = m.fileName != null ? m.fileName : "File";
                    h.tvFileName.setText(fName);
                    if (h.btnDownload != null) {
                        String fUrl = m.mediaUrl != null ? m.mediaUrl : m.text;
                        h.btnDownload.setOnClickListener(v -> {
                            // Pehle local cache check karo
                            java.io.File cached = MediaCache.getCached(ctx, fUrl);
                            if (cached != null) {
                                FileUtils.openOrDownload(ctx, cached.toURI().toString(), fName);
                                return;
                            }
                            android.widget.Toast.makeText(ctx, "Downloading…", android.widget.Toast.LENGTH_SHORT).show();
                            MediaCache.get(ctx, fUrl, new MediaCache.Callback() {
                                @Override public void onReady(java.io.File file) {
                                    FileUtils.openOrDownload(ctx, file.toURI().toString(), fName);
                                }
                                @Override public void onError(String reason) {
                                    FileUtils.openOrDownload(ctx, fUrl, fName);
                                }
                            });
                        });
                    }
                } else {
                    h.tvMessage.setVisibility(View.VISIBLE);
                    h.tvMessage.setText(m.fileName != null ? m.fileName : "File");
                }
                break;
            case "poll":
                // Canvas path — getItemViewType() now returns TYPE_CANVAS_SENT/RECEIVED for
                // poll messages (isCanvasEligible() returns true), so this case is never
                // reached for normal operation. Kept as a safety fallback only.
                h.tvMessage.setVisibility(View.VISIBLE);
                h.tvMessage.setText("\uD83D\uDCCA " + (m.pollQuestion != null ? m.pollQuestion : "Poll"));
                break;
            case "reel_share":
            case "reel_link": {
                // ── Inflate ViewStub on first use ──
                if (h.stubReelShare != null) {
                    h.stubReelShare.inflate();
                    h.llReelShare         = h.itemView.findViewById(R.id.ll_reel_share);
                    h.ivReelShareThumb    = h.itemView.findViewById(R.id.iv_reel_share_thumb);
                    h.ivReelShareAvatar   = h.itemView.findViewById(R.id.iv_reel_share_avatar);
                    h.tvReelShareUsername = h.itemView.findViewById(R.id.tv_reel_share_username);
                    h.tvReelShareCaption  = h.itemView.findViewById(R.id.tv_reel_share_caption);
                    // Apply circular clip to avatar via ShapeAppearance (no CircleImageView dep needed)
                    if (h.ivReelShareAvatar != null) {
                        h.ivReelShareAvatar.setClipToOutline(true);
                        h.ivReelShareAvatar.setOutlineProvider(new android.view.ViewOutlineProvider() {
                            @Override public void getOutline(android.view.View v, android.graphics.Outline outline) {
                                outline.setOval(0, 0, v.getWidth(), v.getHeight());
                            }
                        });
                    }
                    h.stubReelShare = null; // mark inflated
                }
                if (h.llReelShare == null) {
                    // Fallback: stub missing in layout, show text
                    h.tvMessage.setVisibility(View.VISIBLE);
                    h.tvMessage.setText(m.text != null ? m.text : "🎬 Reel");
                    break;
                }
                h.llReelShare.setVisibility(View.VISIBLE);

                // Username
                if (h.tvReelShareUsername != null) {
                    String uname = (m.reelShareUsername != null && !m.reelShareUsername.isEmpty())
                            ? "@" + m.reelShareUsername : "@callx_reel";
                    h.tvReelShareUsername.setText(uname);
                }
                // Avatar — load from reelShareOwnerPhoto; else in-memory cache;
                // else Firebase fallback (profileImage/photoUrl), ONCE per
                // username for the app's lifetime (PERF FIX #3).
                if (h.ivReelShareAvatar != null) {
                    String avatarUrl = m.reelShareOwnerPhoto != null ? m.reelShareOwnerPhoto : "";
                    String uKey = m.reelShareUsername != null ? m.reelShareUsername : "";
                    if (avatarUrl.isEmpty() && !uKey.isEmpty()) {
                        String cached = reelOwnerAvatarCache.get(uKey);
                        if (cached != null) {
                            avatarUrl = cached;
                            m.reelShareOwnerPhoto = cached;
                        }
                    }
                    if (!avatarUrl.isEmpty()) {
                        glide(ctx)
                                .load(avatarUrl)
                                .apply(THUMB_RGB565)
                                .override(48, 48)
                                .circleCrop()
                                .placeholder(android.R.drawable.ic_menu_camera)
                                .into(h.ivReelShareAvatar);
                    } else if (!uKey.isEmpty()) {
                        h.ivReelShareAvatar.setImageResource(android.R.drawable.ic_menu_camera);
                        if (reelAvatarFetchInFlight.add(uKey)) {
                            final String fUKey = uKey;
                            final VH fhA = h;
                            final android.content.Context fCtxA = ctx.getApplicationContext();
                            com.google.firebase.database.FirebaseDatabase.getInstance()
                                .getReference("users").orderByChild("username")
                                .equalTo(uKey)
                                .limitToFirst(1)
                                .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                                    @Override public void onDataChange(@androidx.annotation.NonNull com.google.firebase.database.DataSnapshot snap) {
                                        reelAvatarFetchInFlight.remove(fUKey);
                                        if (!snap.exists()) return;
                                        String photo = null;
                                        for (com.google.firebase.database.DataSnapshot child : snap.getChildren()) {
                                            photo = child.child("profileImage").getValue(String.class);
                                            if (photo == null || photo.isEmpty())
                                                photo = child.child("photoUrl").getValue(String.class);
                                            if (photo == null || photo.isEmpty())
                                                photo = child.child("profilePhoto").getValue(String.class);
                                            break;
                                        }
                                        if (photo == null || photo.isEmpty()) return;
                                        reelOwnerAvatarCache.put(fUKey, photo);
                                        // Guard: only push into the ImageView if this row is
                                        // still showing the same username (it may have been
                                        // recycled to a different message by the time this
                                        // async Firebase callback returns).
                                        CharSequence curName = fhA.tvReelShareUsername != null
                                                ? fhA.tvReelShareUsername.getText() : null;
                                        if (fhA.ivReelShareAvatar != null && curName != null
                                                && curName.toString().equals("@" + fUKey)) {
                                            glide(fCtxA)
                                                    .load(photo)
                                                    .apply(THUMB_RGB565)
                                                    .override(48, 48)
                                                    .circleCrop()
                                                    .placeholder(android.R.drawable.ic_menu_camera)
                                                    .into(fhA.ivReelShareAvatar);
                                        }
                                    }
                                    @Override public void onCancelled(@androidx.annotation.NonNull com.google.firebase.database.DatabaseError e) {
                                        reelAvatarFetchInFlight.remove(fUKey);
                                    }
                                });
                        }
                    }
                }
                // Caption
                if (h.tvReelShareCaption != null) {
                    if (m.reelShareCaption != null && !m.reelShareCaption.isEmpty()) {
                        h.tvReelShareCaption.setText(m.reelShareCaption);
                        h.tvReelShareCaption.setVisibility(View.VISIBLE);
                    } else {
                        h.tvReelShareCaption.setVisibility(View.GONE);
                    }
                }
                // Thumbnail — same PERF FIX #3 pattern: in-memory cache keyed by
                // reelId, plus an in-flight guard so N rows sharing one reelId
                // (e.g. same reel forwarded/reshared multiple times in a chat)
                // only ever trigger ONE Firebase "reels/{id}" read for the
                // lifetime of the app process instead of one per bind.
                if (h.ivReelShareThumb != null) {
                    String thumb = m.reelShareThumb != null ? m.reelShareThumb : "";
                    String rKey = m.reelId != null ? m.reelId : "";
                    // Tag the row with the reelId it's currently bound to, so an
                    // async Firebase callback returning after this row got
                    // recycled to a different message can detect the mismatch
                    // and skip touching views that no longer belong to it.
                    h.itemView.setTag(R.id.iv_reel_share_thumb, rKey);
                    if (thumb.isEmpty() && !rKey.isEmpty()) {
                        String cachedThumb = reelThumbCache.get(rKey);
                        if (cachedThumb != null) {
                            thumb = cachedThumb;
                            m.reelShareThumb = cachedThumb;
                        }
                    }
                    if (!thumb.isEmpty()) {
                        glide(ctx)
                                .load(thumb)
                                .apply(THUMB_RGB565)
                                .override(330, 474) // PERF: match ~165x237dp bubble, avoid full-res decode
                                .centerCrop()
                                .placeholder(android.R.color.darker_gray)
                                .into(h.ivReelShareThumb);
                    } else if (!rKey.isEmpty() && reelThumbFetchInFlight.add(rKey)) {
                        final VH fh = h;
                        final String fRKey = rKey;
                        final android.content.Context fCtxT = ctx.getApplicationContext();
                        com.google.firebase.database.FirebaseDatabase.getInstance()
                            .getReference("reels").child(rKey)
                            .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                                @Override public void onDataChange(@androidx.annotation.NonNull com.google.firebase.database.DataSnapshot snap) {
                                    reelThumbFetchInFlight.remove(fRKey);
                                    if (!snap.exists()) return;
                                    String t = snap.child("thumbUrl").getValue(String.class);
                                    if (t == null || t.isEmpty())
                                        t = snap.child("thumbnailUrl").getValue(String.class);
                                    boolean rowStillMatches = fRKey.equals(fh.itemView.getTag(R.id.iv_reel_share_thumb));
                                    if (t != null && !t.isEmpty()) {
                                        reelThumbCache.put(fRKey, t);
                                        if (fh.ivReelShareThumb != null && rowStillMatches) {
                                            glide(fCtxT).load(t)
                                                .apply(THUMB_RGB565)
                                                .override(330, 474)
                                                .centerCrop()
                                                .into(fh.ivReelShareThumb);
                                        }
                                    }
                                    if (!rowStillMatches) return;
                                    String u = snap.child("ownerName").getValue(String.class);
                                    if (u == null || u.isEmpty())
                                        u = snap.child("username").getValue(String.class);
                                    if (u != null && !u.isEmpty() && fh.tvReelShareUsername != null)
                                        fh.tvReelShareUsername.setText("@" + u);
                                    // Also load avatar if not yet loaded, and warm the avatar cache.
                                    String ap = snap.child("ownerPhoto").getValue(String.class);
                                    if (ap == null || ap.isEmpty())
                                        ap = snap.child("profileImage").getValue(String.class);
                                    if (ap != null && !ap.isEmpty()) {
                                        if (u != null && !u.isEmpty()) reelOwnerAvatarCache.put(u, ap);
                                        if (fh.ivReelShareAvatar != null) {
                                            glide(fCtxT)
                                                    .load(ap)
                                                    .apply(THUMB_RGB565)
                                                    .override(48, 48)
                                                    .circleCrop()
                                                    .placeholder(android.R.drawable.ic_menu_camera)
                                                    .into(fh.ivReelShareAvatar);
                                        }
                                    }
                                    String c = snap.child("caption").getValue(String.class);
                                    if (c != null && !c.isEmpty() && fh.tvReelShareCaption != null) {
                                        fh.tvReelShareCaption.setText(c);
                                        fh.tvReelShareCaption.setVisibility(View.VISIBLE);
                                    }
                                }
                                @Override public void onCancelled(@androidx.annotation.NonNull com.google.firebase.database.DatabaseError e) {
                                    reelThumbFetchInFlight.remove(fRKey);
                                }
                            });
                    }
                }
                // Tap to open reel
                final String fReelId  = m.reelId      != null ? m.reelId      : "";
                final String fReelUrl = m.reelShareUrl != null ? m.reelShareUrl : "";
                h.llReelShare.setOnClickListener(v -> {
                    String deepLink = !fReelId.isEmpty()
                            ? com.callx.app.utils.Constants.DEEP_LINK_BASE_URL + "/reel/" + fReelId
                            : fReelUrl;
                    if (!deepLink.isEmpty()) {
                        try {
                            android.content.Intent ri = new android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(deepLink));
                            ri.setPackage(ctx.getPackageName());
                            ri.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                            ctx.startActivity(ri);
                        } catch (Exception ignored) {}
                    }
                });
                break;
            }
            case "contact": {
                // Inflate stub on first use
                if (h.stubContact != null) {
                    h.llContact = h.stubContact.inflate();
                    h.stubContact = null;
                }
                if (h.llContact == null) {
                    h.tvMessage.setVisibility(View.VISIBLE);
                    h.tvMessage.setText(m.contactName != null ? "📇 " + m.contactName : "📇 Contact");
                    break;
                }
                h.llContact.setVisibility(View.VISIBLE);
                com.callx.app.conversation.controllers.ChatContactShareController
                        .bindBubble(h.llContact, m);
                h.llContact.setOnLongClickListener(v -> {
                    if (actionListener != null) showActionBottomSheet(ctx, m);
                    return true;
                });
                break;
            }
            case "location": {
                // Inflate stub on first use
                if (h.stubLocation != null) {
                    h.llLocation = h.stubLocation.inflate();
                    h.stubLocation = null;
                }
                if (h.llLocation == null) {
                    h.tvMessage.setVisibility(View.VISIBLE);
                    h.tvMessage.setText(m.locationAddress != null ? "📍 " + m.locationAddress : "📍 Location");
                    break;
                }
                h.llLocation.setVisibility(View.VISIBLE);
                com.callx.app.conversation.controllers.ChatLocationShareController
                        .bindBubble(h.llLocation, m);
                h.llLocation.setOnLongClickListener(v -> {
                    if (actionListener != null) showActionBottomSheet(ctx, m);
                    return true;
                });
                break;
            }
            default: // "text", "emoji", etc.
                h.tvMessage.setVisibility(View.VISIBLE);
                String txt = m.text != null ? m.text : "";
                // Feature 3: Spoiler text — parse ||hidden|| syntax before edited suffix
                final String spoilerMsgId = m.messageId != null ? m.messageId : m.id;
                if (com.callx.app.utils.SpoilerTextHelper.hasSpoiler(txt)) {
                    final String fTxt = txt;
                    final int fPos   = position;
                    java.util.Set<Integer> revealed = revealedSpoilers.computeIfAbsent(
                            spoilerMsgId, k -> new java.util.HashSet<>());
                    android.text.SpannableString spoilerSpan = com.callx.app.utils.SpoilerTextHelper.apply(
                            fTxt, revealed, () -> {
                                // Record which spans are now revealed via the span objects
                                android.text.SpannableString cur = null;
                                if (h.tvMessage.getText() instanceof android.text.SpannableString)
                                    cur = (android.text.SpannableString) h.tvMessage.getText();
                                if (cur != null) {
                                    com.callx.app.chat.ui.SpoilerSpan[] spans = cur.getSpans(
                                            0, cur.length(), com.callx.app.chat.ui.SpoilerSpan.class);
                                    if (spans != null) for (com.callx.app.chat.ui.SpoilerSpan sp : spans)
                                        if (sp.isRevealed()) revealed.add(cur.getSpanStart(sp));
                                }
                                notifyItemChanged(fPos);
                            });
                    if (spoilerSpan != null) {
                        if (Boolean.TRUE.equals(m.edited))
                            spoilerSpan = android.text.SpannableString.valueOf(
                                    android.text.TextUtils.concat(spoilerSpan, " (edited)"));
                        h.tvMessage.setText(spoilerSpan);
                        h.tvMessage.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
                        break; // skip the rest of the default case text binding
                    }
                }
                if (Boolean.TRUE.equals(m.edited)) txt += " (edited)";
                // ── Font Style — cached static Typeface, no allocation ────────
                h.tvMessage.setTypeface(TF_NORMAL);
                // ── Font Size — moved to onCreateViewHolder for constant case ──
                // Only call here for safety (noop if already set at create time)
                // ── Clickable links: URLs, phone numbers, emails ─────────────
                // PERF: check the linkify cache before running Linkify's regex
                // passes — see linkifiedTextCache field comment. Cache hit means
                // "spanned instanceof String" tells us there was no link (we
                // only ever store the plain String in that case), no need to
                // re-derive mightHaveLink.
                final String linkCacheKey = (m.messageId != null ? m.messageId : m.id) + "#" + txt.hashCode();
                CharSequence spanned;
                boolean mightHaveLink;
                synchronized (precomputeCacheLock) {
                    spanned = linkifiedTextCache.get(linkCacheKey);
                }
                if (spanned != null) {
                    mightHaveLink = !(spanned instanceof String);
                } else {
                    mightHaveLink = txt.contains("http://")
                            || txt.contains("https://")
                            || txt.contains("www.")
                            || txt.contains("@")
                            || (txt.length() >= 7 && txt.contains("+"));
                    // PERF/RAM: SpannableString always carries an internal span
                    // array even with zero spans attached — for the common
                    // plain-text message (no link) that's a pure-waste
                    // allocation on every single bind. Only pay for it when
                    // Linkify actually has something to attach.
                    if (mightHaveLink) {
                        android.text.SpannableString linkSpanned = new android.text.SpannableString(txt);
                        android.text.util.Linkify.addLinks(linkSpanned,
                            android.text.util.Linkify.WEB_URLS |
                            android.text.util.Linkify.PHONE_NUMBERS |
                            android.text.util.Linkify.EMAIL_ADDRESSES);
                        spanned = linkSpanned;
                    } else {
                        spanned = txt;
                    }
                    synchronized (precomputeCacheLock) {
                        linkifiedTextCache.put(linkCacheKey, spanned);
                    }
                }
                boolean isSentMsg = currentUid.equals(m.senderId);
                if (mightHaveLink) {
                    // Link color matching bubble theme
                    int linkColor = isSentMsg ? 0xFFB3E5FC : 0xFF1565C0;
                    h.tvMessage.setLinkTextColor(linkColor);
                    h.tvMessage.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
                    h.tvMessage.setHighlightColor(0x33FFFFFF);
                } else {
                    // Plain text — remove MovementMethod so RecyclerView keeps scroll events
                    h.tvMessage.setMovementMethod(null);
                }
                h.tvMessage.setAlpha(1f);
                h.tvMessage.setTextColor(
                    com.callx.app.utils.ChatThemeManager.get(ctx).getTextColor(ctx, isSentMsg));

                // ── BUG FIX (v45-4): PrecomputedTextCompat REMOVED entirely ──
                // The previous "perf" path replaced the just-set plain text
                // with a PrecomputedTextCompat result (sync or async) after
                // the fact. Even the "synchronous" branch swapped the
                // TextView's text a second time via
                // TextViewCompat.setPrecomputedText() with a layout built
                // from Params captured off a TextView whose width isn't
                // guaranteed final yet on a freshly-inflated/cold-open
                // holder — occasionally resolving to a different line count
                // than plain setText() would, so the bubble's measured
                // height (first pass) and its actually-drawn content
                // (second, silently-swapped pass) disagreed. That mismatch
                // is exactly the "kabhi pura bubble dikhta hai, kabhi thoda
                // sa dikhta hai" bug — and it could recur any time the swap
                // and the real layout width disagreed, not only in the old
                // async branch. Root-caused for good by removing the second
                // pass entirely: a single plain setText() call is the only
                // thing that ever sets tv_message's content, so there is
                // only ever ONE measurement of it, period. No swap, no
                // possible mismatch, no "thoda sa dikhta hai" — guaranteed,
                // not just in the common case.
                //
                // BUG FIX: footerReservePx used to be computed from
                // h.tvTime's CURRENT (possibly stale, recycled-from-a-
                // previous-message) text, read before this message's own
                // time was ever set on it. Now computed straight from this
                // message's own timestamp so the reserved gap always
                // matches the footer that will actually be drawn.
                String footerTimeStr = m.timestamp > 0 ? formatTime(m.timestamp) : null;
                int footerReservePx = computeFooterReservePx(h, m, isSentMsg, footerTimeStr);
                CharSequence displaySpanned = appendFooterReserve(spanned, footerReservePx);

                // Set message text directly. (An earlier "precompute"
                // path here called TextViewCompat.setTextFuture(), which
                // does not exist on TextViewCompat and never compiled as
                // written — removed. See v45-4 bug-fix note above: a single
                // plain setText() call is the only thing that should ever
                // set tv_message's content, so there is only ever ONE
                // measurement of it.)
                h.tvMessage.setText(displaySpanned);
                h.textBindToken++;

                // ── @mention blue highlight + search yellow highlight ─────────
                // Applied AFTER the single setText() so the underlying linkified
                // spans are already in place. We read the TextView's current text
                // into a SpannableStringBuilder (preserving link spans), append
                // our extra spans on top, then do one final setText(). This is
                // safe because we only reach this branch for text/emoji messages
                // whose content was just set a line above — no stale state risk.
                boolean hasMention = txt.contains("@");
                boolean hasSearch  = activeSearchQuery != null && !activeSearchQuery.isEmpty()
                                     && txt.toLowerCase(java.util.Locale.getDefault())
                                            .contains(activeSearchQuery.toLowerCase(java.util.Locale.getDefault()));
                if (hasMention || hasSearch) {
                    android.text.SpannableStringBuilder overlay =
                            new android.text.SpannableStringBuilder(h.tvMessage.getText());
                    // @mention — blue foreground
                    if (hasMention) {
                        java.util.regex.Matcher mm = MENTION_PATTERN.matcher(txt);
                        while (mm.find()) {
                            int ms = mm.start(), me = mm.end();
                            if (ms < overlay.length() && me <= overlay.length()) {
                                overlay.setSpan(
                                    new android.text.style.ForegroundColorSpan(MENTION_COLOR),
                                    ms, me,
                                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                            }
                        }
                    }
                    // Search — yellow background
                    if (hasSearch) {
                        String lq = activeSearchQuery.toLowerCase(java.util.Locale.getDefault());
                        String lt = txt.toLowerCase(java.util.Locale.getDefault());
                        int si = 0;
                        while ((si = lt.indexOf(lq, si)) != -1) {
                            int se = si + lq.length();
                            if (se <= overlay.length()) {
                                overlay.setSpan(
                                    new android.text.style.BackgroundColorSpan(0xFFFFEB3B),
                                    si, se,
                                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                            }
                            si = se;
                        }
                    }
                    h.tvMessage.setText(overlay);
                }
                // ── end mention/search overlay ─────────────────────────────────

                // ── Link preview (ViewStub lazy inflate) ─────────────────────
                // BUG FIX (cold-open big bubble / "shrinks on selection"):
                // ensureLinkPreviewInflated() used to run for EVERY text
                // message (any m.text != null), not just ones with a URL.
                // layout_msg_link_preview.xml's root has no
                // android:visibility="gone", so the moment the ViewStub
                // inflated, an EMPTY-but-VISIBLE match_parent-width preview
                // card appeared inside content_frame — stretching that
                // fresh bubble to full row width on first bind. The old
                // GONE-guard above only worked once h.llLinkPreview was
                // already non-null, i.e. AFTER a stub had already been
                // inflated once — which is exactly why entering selection
                // mode (forcing a rebind of the already-inflated holder)
                // made the bubble "shrink" to its correct size instead of
                // the underlying bug being fixed.
                //
                // Fix: detect the URL FIRST, and only inflate the stub
                // (and touch h.llLinkPreview) when a URL genuinely exists.
                // For plain text (the overwhelming majority of messages)
                // the stub is never inflated at all, and any leftover
                // preview card on a *recycled* holder is explicitly hidden.
                String cachedText = (String) h.tvMessage.getTag(R.id.tv_message);
                String previewUrl;
                if (m.text != null && m.text.equals(cachedText)) {
                    previewUrl = h.llLinkPreview != null ? (String) h.llLinkPreview.getTag() : null;
                    if (previewUrl == null) previewUrl = com.callx.app.utils.LinkPreviewFetcher.extractFirstUrl(m.text);
                } else {
                    if (m.text != null) h.tvMessage.setTag(R.id.tv_message, m.text);
                    previewUrl = m.text != null ? com.callx.app.utils.LinkPreviewFetcher.extractFirstUrl(m.text) : null;
                }

                if (previewUrl != null) {
                    ensureLinkPreviewInflated(h, isSentMsg); // inflate only when actually needed
                }

                if (h.llLinkPreview != null) {
                    if (previewUrl == null) {
                        // Recycled holder previously showed a link preview
                        // but this message has none — hide the leftover card.
                        h.llLinkPreview.setVisibility(View.GONE);
                    } else {
                        // Tag itemView with URL so we detect stale VH on recycle
                        h.llLinkPreview.setTag(previewUrl);
                        h.llLinkPreview.setVisibility(View.INVISIBLE); // reserve space while loading
                        // FIX: must be final for use inside anonymous inner class
                        final String finalPreviewUrl = previewUrl;
                        com.callx.app.utils.LinkPreviewFetcher.fetch(finalPreviewUrl,
                                new com.callx.app.utils.LinkPreviewFetcher.Callback() {
                            @Override public void onResult(com.callx.app.utils.LinkPreviewFetcher.Result r) {
                                // Guard against recycled VH
                                if (!finalPreviewUrl.equals(h.llLinkPreview.getTag())) return;
                                h.llLinkPreview.setVisibility(View.VISIBLE);
                                if (h.tvLinkDomain != null) h.tvLinkDomain.setText(r.domain);
                                if (h.tvLinkTitle  != null) h.tvLinkTitle.setText(r.title);
                                if (h.ivLinkThumb  != null) {
                                    if (r.imageUrl != null && !r.imageUrl.isEmpty()) {
                                        h.ivLinkThumb.setVisibility(View.VISIBLE);
                                        glide(ctx)
                                            .load(r.imageUrl)
                                            .apply(THUMB_RGB565)
                                            .override(300, 300)
                                            .centerCrop()
                                            .into(h.ivLinkThumb);
                                    } else {
                                        h.ivLinkThumb.setVisibility(View.GONE);
                                    }
                                }
                                // Tapping the card opens the URL in browser
                                h.llLinkPreview.setOnClickListener(v -> {
                                    Intent browserIntent = new Intent(
                                            Intent.ACTION_VIEW, android.net.Uri.parse(r.url));
                                    browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                    ctx.startActivity(browserIntent);
                                });
                            }
                            @Override public void onError(String url) {
                                if (!finalPreviewUrl.equals(h.llLinkPreview.getTag())) return;
                                h.llLinkPreview.setVisibility(View.GONE);
                            }
                        });
                    }
                }
                break;
        }

        // ── Delivery status (sent messages only) ─────────────────
        if (sent && h.tvStatus != null) {
            h.tvStatus.setVisibility(View.VISIBLE);
            String status = m.status != null ? m.status : "sent";
            // TICK ADVANCE #4: WhatsApp-style symmetry — if I've turned OFF
            // read receipts myself, I don't get to see the other person's
            // blue tick either, even though the real Firebase status is
            // "read". Display-only downgrade; the real status is untouched.
            if (("read".equals(status) || "seen".equals(status))
                    && !new com.callx.app.utils.SecurityManager(ctx).isReadReceiptsEnabled()) {
                status = "delivered";
            }
            switch (status) {
                case "seen":
                case "read":
                    h.tvStatus.setText("✓✓");
                    h.tvStatus.setTextColor(
                        com.callx.app.utils.ChatThemeManager.getTickColor(true));
                    break;
                case "delivered":
                    h.tvStatus.setText("✓✓");
                    h.tvStatus.setTextColor(
                        com.callx.app.utils.ChatThemeManager.getTickColor(false));
                    break;
                case "pending":
                    // Clock icon — sent locally, not yet reached Firebase
                    h.tvStatus.setText("🕐");
                    h.tvStatus.setTextColor(0xFFAAAAAA);
                    break;
                case "uploading":
                    // WhatsApp-style local-first media bubble — clock icon,
                    // same treatment as "pending" (the bubble's own spinner
                    // ring is what actually shows upload progress).
                    h.tvStatus.setText("🕐");
                    h.tvStatus.setTextColor(0xFFAAAAAA);
                    h.tvStatus.setOnClickListener(null);
                    break;
                case "failed":
                    // Error icon — Firebase push rejected; tap to retry
                    h.tvStatus.setText("⚠");
                    h.tvStatus.setTextColor(0xFFFF5555);
                    h.tvStatus.setOnClickListener(v -> {
                        if (actionListener != null) actionListener.onRetry(m);
                    });
                    break;
                default: // "sent" — one grey tick
                    h.tvStatus.setText("✓");
                    h.tvStatus.setTextColor(
                        com.callx.app.utils.ChatThemeManager.getTickColor(false));
                    h.tvStatus.setOnClickListener(null);
                    break;
            }
        } else if (h.tvStatus != null) {
            h.tvStatus.setVisibility(View.GONE);
        }

        // ── Disappearing message countdown ────────────────────────────────
        // PERF: shared ExpiryTickManager handler instead of a per-row CountDownTimer.
        com.callx.app.utils.ExpiryTickManager.get().unregister(h);
        if (h.tvExpiry != null) {
            long expiresAt = m.expiresAt != null ? m.expiresAt : 0L;
            long remaining = expiresAt - System.currentTimeMillis();
            if (expiresAt > 0 && remaining > 0) {
                h.tvExpiry.setVisibility(View.VISIBLE);
                h.tvExpiry.setText("⏳ " + formatRemaining(remaining));
                com.callx.app.utils.ExpiryTickManager.get().register(h, expiresAt,
                        new com.callx.app.utils.ExpiryTickManager.Listener() {
                    @Override public void onTick(long ms) {
                        if (h.tvExpiry != null)
                            h.tvExpiry.setText("⏳ " + formatRemaining(ms));
                    }
                    @Override public void onFinish() {
                        if (h.tvExpiry != null) h.tvExpiry.setVisibility(View.GONE);
                    }
                });
            } else {
                h.tvExpiry.setVisibility(View.GONE);
            }
        }

        // ── Long press — multi-select mode ya action sheet ─────────────────
        h.itemView.setOnLongClickListener(v -> {
            // FIX: Haptic feedback on long press — production apps always do this
            v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            if (!multiSelectMode) {
                enterMultiSelectMode(m);
            } else {
                if (actionListener != null) showActionBottomSheet(ctx, m);
            }
            return true;
        });
        h.itemView.setOnClickListener(v -> {
            if (multiSelectMode) {
                String id = m.messageId != null ? m.messageId : m.id;
                if (id != null) {
                    if (selectedMessageIds.contains(id)) {
                        selectedMessageIds.remove(id);
                    } else {
                        selectedMessageIds.add(id);
                    }
                    // FIX: same NO_POSITION race as the canvas path — update
                    // this row's highlight directly instead of going through
                    // notifyItemChanged(h.getAdapterPosition()).
                    applySelectionHighlight(h, m);
                    if (multiSelectListener != null)
                        multiSelectListener.onSelectionChanged(selectedMessageIds.size());
                    if (selectedMessageIds.isEmpty()) exitMultiSelectMode();
                }
            }
        });
        applySelectionHighlight(h, m);
    }

    // ──────────────────────────────────────────────────────────────
    // POLL BUBBLE (advanced) — icon-badge header, frosted card,
    // radio OR checkbox vote indicator (single- vs multi-choice),
    // animated fill bars, leading-option highlight, and a status chip
    // for Closed/Anonymous polls.
    // Layout (per-bubble): ll_poll > header(iv_poll_icon, tv_poll_icon_label,
    // tv_poll_status_badge), tv_poll_question, tv_poll_subtitle,
    // ll_poll_options, tv_poll_total_votes. Each option row is
    // item_poll_option_row.xml, inflated dynamically since option count
    // is variable (2–10).
    //
    // Multi-choice polls (Message#pollMultiChoice == true) let a voter
    // tick any number of options — tapping a ticked option un-ticks it,
    // tapping an un-ticked one adds it. Single-choice polls keep the
    // original radio behaviour: tapping any option replaces your vote.
    // ──────────────────────────────────────────────────────────────
    private void bindPoll(@NonNull VH h, @NonNull Message m, boolean sent) {
        Context ctx = h.itemView.getContext();
        h.llPoll.setVisibility(View.VISIBLE);

        int textColor = sent
                ? androidx.core.content.ContextCompat.getColor(ctx, R.color.bubble_sent_text)
                : androidx.core.content.ContextCompat.getColor(ctx, R.color.bubble_received_text);

        // Recolor the fixed-white header icon to match the bubble's text color
        // so it stays legible on both light and dark bubble/theme combinations.
        if (h.ivPollIcon != null) {
            h.ivPollIcon.setColorFilter(textColor, android.graphics.PorterDuff.Mode.SRC_IN);
        }

        if (h.tvPollQuestion != null) {
            h.tvPollQuestion.setText(m.pollQuestion != null ? m.pollQuestion : "");
        }

        boolean multiChoice = Boolean.TRUE.equals(m.pollMultiChoice);

        if (h.tvPollSubtitle != null) {
            h.tvPollSubtitle.setText(multiChoice ? "Select one or more answers" : "Select one answer");
            h.tvPollSubtitle.setTextColor((textColor & 0x00FFFFFF) | 0xAA000000);
        }

        java.util.List<String> options = m.pollOptions != null
                ? m.pollOptions : java.util.Collections.emptyList();
        java.util.Map<String, java.util.List<Integer>> votes = m.pollVotes != null
                ? m.pollVotes : java.util.Collections.emptyMap();
        int[] counts = com.callx.app.utils.PollJsonUtil.countVotes(votes, options.size());
        int total = com.callx.app.utils.PollJsonUtil.totalVotes(votes);
        java.util.List<Integer> myVotes = currentUid != null ? votes.get(currentUid) : null;
        if (myVotes == null) myVotes = java.util.Collections.emptyList();
        boolean closed = Boolean.TRUE.equals(m.pollClosed);
        boolean anonymous = Boolean.TRUE.equals(m.pollAnonymous);

        // Identify the leading option(s) so we can give them a subtle bold
        // treatment — but only when there IS a clear leader (skip when every
        // option is tied, including the all-zero-votes case).
        int maxCount = 0;
        for (int c : counts) if (c > maxCount) maxCount = c;
        boolean hasClearLeader = maxCount > 0;
        if (hasClearLeader) {
            int countAtMax = 0;
            for (int c : counts) if (c == maxCount) countAtMax++;
            if (countAtMax == options.size()) hasClearLeader = false;
        }

        if (h.llPollOptions != null) {
            // PERF: recycle existing option rows instead of removeAllViews + inflate
            int optCount = options.size();
            while (h.llPollOptions.getChildCount() > optCount) {
                h.llPollOptions.removeViewAt(h.llPollOptions.getChildCount() - 1);
            }
            for (int i = 0; i < optCount; i++) {
                final int optionIndex = i;
                View row;
                PollOptionRowViews rowViews;
                if (i < h.llPollOptions.getChildCount()) {
                    row = h.llPollOptions.getChildAt(i);
                    rowViews = (PollOptionRowViews) row.getTag();
                } else {
                    row = LayoutInflater.from(ctx)
                            .inflate(R.layout.item_poll_option_row, h.llPollOptions, false);
                    h.llPollOptions.addView(row);
                    rowViews = null;
                }
                // PERF: cache the 4 child views on the row itself via setTag().
                // findViewById() walks the row's view tree; with rows being
                // reused across binds (recycling, above) we'd otherwise pay
                // that walk cost every single bind even though the row's
                // structure never changes after first inflation.
                if (rowViews == null) {
                    rowViews = new PollOptionRowViews(row);
                    row.setTag(rowViews);
                }
                TextView  tvText  = rowViews.tvText;
                TextView  tvPct   = rowViews.tvPct;
                ImageView ivCheck = rowViews.ivCheck;
                View      vFill   = rowViews.vFill;

                int pct = total > 0 ? Math.round((counts[i] * 100f) / total) : 0;
                boolean isMyVote  = myVotes.contains(optionIndex);
                boolean isLeading = hasClearLeader && counts[i] == maxCount;

                if (tvText != null) {
                    tvText.setText(options.get(i));
                    tvText.setTextColor(textColor);
                    tvText.setTypeface(null, isLeading ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
                }
                if (tvPct != null) {
                    tvPct.setText(total > 0 ? (pct + "%") : "");
                    tvPct.setTextColor(textColor);
                }
                if (ivCheck != null) {
                    int filledRes     = multiChoice ? R.drawable.ic_poll_checkbox_filled     : R.drawable.ic_poll_check_filled;
                    int unselectedRes = multiChoice ? R.drawable.ic_poll_checkbox_unselected  : R.drawable.ic_poll_radio_unselected;
                    if (isMyVote) {
                        ivCheck.clearColorFilter();
                        ivCheck.setImageResource(filledRes);
                    } else {
                        ivCheck.setImageResource(unselectedRes);
                        int ringColor = (textColor & 0x00FFFFFF) | 0x80000000;
                        ivCheck.setColorFilter(ringColor, android.graphics.PorterDuff.Mode.SRC_IN);
                    }
                }

                row.setBackgroundResource(isMyVote
                        ? R.drawable.bg_poll_option_voted
                        : R.drawable.bg_poll_option);
                if (vFill != null) {
                    vFill.setBackgroundResource(isMyVote
                            ? R.drawable.bg_poll_option_fill_voted
                            : R.drawable.bg_poll_option_fill);

                    final View fillView = vFill;
                    final int fPct = pct;
                    vFill.post(() -> {
                        Object parentObj = fillView.getParent();
                        if (!(parentObj instanceof View)) return;
                        int parentWidth = ((View) parentObj).getWidth();
                        if (parentWidth <= 0) return;
                        int targetWidth = Math.round(parentWidth * (fPct / 100f));
                        android.view.ViewGroup.LayoutParams lp = fillView.getLayoutParams();
                        lp.width = targetWidth;
                        fillView.setLayoutParams(lp);
                        fillView.setTag(targetWidth);
                    });
                }

                row.setOnClickListener(v -> {
                    if (closed) {
                        android.widget.Toast.makeText(ctx, "This poll is closed", android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (actionListener != null) actionListener.onPollVote(m, optionIndex);
                });
            }
        }

        if (h.tvPollTotalVotes != null) {
            String label = total == 0 ? "No votes yet" : total + (total == 1 ? " person voted" : " people voted");
            h.tvPollTotalVotes.setText(label);
        }

        // Status chip — shows the single most relevant state: Closed takes
        // priority over Anonymous since it affects whether the user can vote.
        if (h.tvPollStatusBadge != null) {
            if (closed) {
                h.tvPollStatusBadge.setText("🔒 Closed");
                h.tvPollStatusBadge.setBackgroundResource(R.drawable.bg_poll_chip_closed);
                h.tvPollStatusBadge.setVisibility(View.VISIBLE);
            } else if (anonymous) {
                h.tvPollStatusBadge.setText("🙈 Anonymous");
                h.tvPollStatusBadge.setBackgroundResource(R.drawable.bg_poll_chip_neutral);
                h.tvPollStatusBadge.setVisibility(View.VISIBLE);
            } else {
                h.tvPollStatusBadge.setVisibility(View.GONE);
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Audio playback toggle
    // ──────────────────────────────────────────────────────────────
    private void toggleAudio(@NonNull VH h, String url, int position) {
        if (playingPos == position && player != null && isPlayerPlaying) {
            player.pause();
            isPlayerPlaying = false;
            setPlayPauseIcon(h, false);
            notifyPlaybackChanged(getItem(position), false);
            GlobalVoicePlaybackManager.getInstance().notifyToggled(midOf(getItem(position)), false);
            return;
        }
        if (playingPos != -1 && playingPos != position) {
            // Switching to a different bubble mid-playback — tell the
            // partner we stopped listening to the OLD one before the new
            // one's "started playing" callback fires.
            notifyPlaybackChanged(getItem(playingPos), false);
        }
        if (player != null) {
            try { player.stop(); player.release(); } catch (Exception ignored) {}
            player = null;
        }
        isPlayerPlaying = false;
        playingPos = position;
        setPlayPauseIcon(h, true);

        // Pehle local cache check — cached hai to seedha play (zero data use)
        java.io.File cached = MediaCache.getCached(h.itemView.getContext(), url);
        if (cached != null) {
            playAudioFromPath(h, cached.getAbsolutePath(), position);
            return;
        }

        // Media E2E (audio): a voice note received through the E2E path
        // is ciphertext at `url` — MediaStreamCache's streaming-partial
        // trick can't decrypt on the fly, so download+decrypt the whole
        // file first via MediaCache (voice notes are small; the extra
        // wait vs. partial-stream-start is negligible) instead of
        // MediaStreamCache.preloadPartial below.
        //
        // BUG FIX (Voice Caption on Photo — recorded audio never plays):
        // audioMsg.mediaKeyEnc belongs to the PHOTO's own E2E envelope —
        // it's set whenever the image is E2E, completely independent of
        // whether a voice caption is attached. The caption clip itself is
        // always uploaded plaintext (see
        // ChatMediaController#uploadVoiceCaptionThenFinalize's javadoc:
        // "E2E for this clip is a follow-up"). Without the type=="audio"
        // guard below, this used to grab the photo's decrypt key for a
        // combo image+voice-caption message and "decrypt" the already-
        // plaintext voice clip with it — corrupting the bytes into
        // unplayable garbage even though the download itself succeeded.
        // Only a genuine standalone voice-message bubble (type=="audio")
        // is ever actually E2E-encrypted at this URL.
        Message audioMsg = getItem(position);
        byte[] audioKey = (audioMsg != null && "audio".equals(audioMsg.type)
                && !currentUid.equals(audioMsg.senderId) && audioMsg.mediaKeyEnc != null)
                ? com.callx.app.utils.MediaE2ECrypto.decryptKeyOnly(h.itemView.getContext(), audioMsg.mediaKeyEnc,
                        audioMsg.senderId, (audioMsg.messageId != null ? audioMsg.messageId : audioMsg.id))
                : null;
        if (audioKey != null) {
            com.callx.app.utils.MediaCache.get(h.itemView.getContext(), url, audioKey,
                    new com.callx.app.utils.MediaCache.Callback() {
                @Override public void onReady(java.io.File file) {
                    playAudioFromPath(h, file.getAbsolutePath(), position);
                }
                @Override public void onError(String reason) {
                    android.util.Log.w("AudioPlay", "E2E audio decrypt/download failed: " + reason);
                }
            });
            return;
        }

        // FIX v14: MediaStreamCache use karo — pehle 512KB stream karo (fast start),
        // baaki background mein download hota rahe. User ko buffer nahi karega.
        com.callx.app.cache.MediaStreamCache.getInstance(h.itemView.getContext())
            .preloadPartial(url, new com.callx.app.cache.MediaStreamCache.DownloadCallback() {
                @Override public void onComplete(java.io.File file) {
                    // Partial/full file ready — play from local file (zero buffering)
                    android.util.Log.d("AudioPlay", "MediaStreamCache ready, playing: " + file.getName());
                    playAudioFromPath(h, file.getAbsolutePath(), position);
                }
                @Override public void onError(String error) {
                    // Fallback: stream directly from URL
                    android.util.Log.w("AudioPlay", "MediaStreamCache failed, streaming URL: " + error);
                    playAudioFromPath(h, url, position);
                }
                @Override public void onProgress(int percent) {
                    android.util.Log.v("AudioPlay", "Audio preload: " + percent + "%");
                }
            });
    }

    /** Sets the play/pause glyph on whichever UI this holder actually uses — the Canvas audio bubble (setAudioPlaying) if bound to one, else the legacy ImageButton, else (Feature: Voice Caption on Photo) the play-badge overlay on an image bubble. */
    private void setPlayPauseIcon(@NonNull VH h, boolean playing) {
        if (h.canvasView != null) {
            h.canvasView.setAudioPlaying(playing);
        } else if (h.btnPlayPause != null) {
            h.btnPlayPause.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        } else if (h.ivVoicePlayOnImage != null) {
            h.ivVoicePlayOnImage.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        }
    }

    /** Resets whichever UI this holder uses back to idle (icon → play, progress/elapsed → 0/blank) — mirrors the old per-field reset for both the legacy View path and the Canvas audio bubble. */
    private void resetAudioUi(@NonNull VH h) {
        if (h.canvasView != null) {
            h.canvasView.resetAudioPlayback();
        } else if (h.btnPlayPause != null) {
            h.btnPlayPause.setImageResource(R.drawable.ic_play);
            if (h.seekAudio != null) h.seekAudio.setProgress(0f);
        } else if (h.ivVoicePlayOnImage != null) {
            // Feature: Voice Caption on Photo — no seekbar on the compact
            // image overlay, just the glyph back to "play".
            h.ivVoicePlayOnImage.setImageResource(R.drawable.ic_play);
        }
    }

    private void playAudioFromPath(@NonNull VH h, String path, int position) {
        try {
            // FIX [P3-1]: Reset previous VH UI so two bubbles don't show "pause" at the same time
            if (playingVH != null && playingVH != h) {
                seekHandler.removeCallbacks(seekUpdater);
                resetAudioUi(playingVH);
            }
            if (player != null) { try { player.release(); } catch (Exception ignored) {} }
            player = new MediaPlayer();
            playingVH = h;

            // Snapshot of who/what this clip belongs to — handed to
            // GlobalVoicePlaybackManager below so the mini player can show
            // "You" (outgoing) or the sender's name+avatar (incoming) even
            // after this chat screen is gone.
            final Message __voiceMsg = getItem(position);
            final String __voiceMid = midOf(__voiceMsg);
            final boolean __outgoing = __voiceMsg != null && currentUid != null
                    && currentUid.equals(__voiceMsg.senderId);
            final String __voiceName = __outgoing
                    ? "You"
                    : (__voiceMsg != null && __voiceMsg.senderName != null && !__voiceMsg.senderName.isEmpty()
                        ? __voiceMsg.senderName : "Voice message");
            final String __voiceAvatar = __outgoing ? null : (__voiceMsg != null ? __voiceMsg.senderPhoto : null);
            // Always the OTHER party's uid (see partnerUid field javadoc) — NOT
            // __voiceMsg.senderId, which is our own uid for outgoing clips.
            final String __voicePartnerUid = this.partnerUid;
            
            // Agar local file hai to FileDescriptor se set karo (cache files ke liye)
            // Agar URL hai to directly
            if (path.startsWith("http")) {
                player.setDataSource(path);
            } else {
                java.io.File f = new java.io.File(path);
                if (f.exists()) {
                    try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
                        player.setDataSource(fis.getFD());
                    }
                } else {
                    // File nahi milti to URL ke bahawe try karo
                    player.setDataSource(path);
                }
            }
            
            player.prepareAsync();
            player.setOnPreparedListener(mp -> {
                // Feature 4: reset to 1x speed for each new audio playback
                currentPlaybackSpeed = 1.0f;
                if (h.btnAudioSpeed != null) h.btnAudioSpeed.setText("1×");
                mp.start();
                isPlayerPlaying = true;
                // Apply initial speed (API 23+) — usually 1x, but applied
                // to match any speed set before prepare completed.
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    try {
                        android.media.PlaybackParams pp = new android.media.PlaybackParams();
                        pp.setSpeed(currentPlaybackSpeed);
                        mp.setPlaybackParams(pp);
                    } catch (Exception ignored) {}
                }
                setPlayPauseIcon(h, true);
                notifyPlaybackChanged(getItem(position), true);
                // WhatsApp-style persistent mini player: register this MediaPlayer +
                // metadata as the app-wide "currently playing voice note" so it
                // survives leaving this chat screen (see onDetachedFromRecyclerView).
                GlobalVoicePlaybackManager.getInstance().notifyStarted(
                        mp, __voiceMid, chatId, __voicePartnerUid, __voiceName, __voiceAvatar, __outgoing);
                // FIX: SeekBar live progress update — runs every 250ms while playing
                if (h.seekAudio != null || h.canvasView != null) {
                    final int durationMs = mp.getDuration();
                    seekHandler.removeCallbacks(seekUpdater);
                    seekUpdater = new Runnable() {
                        @Override public void run() {
                            if (player != null && isPlayerPlaying) {
                                int cur = player.getCurrentPosition();
                                String elapsed = String.format(java.util.Locale.getDefault(),
                                        "%d:%02d", (cur / 1000) / 60, (cur / 1000) % 60);
                                if (h.canvasView != null) {
                                    if (durationMs > 0) h.canvasView.setAudioProgress((float) cur / durationMs);
                                    h.canvasView.setAudioElapsedText(elapsed);
                                } else {
                                    if (durationMs > 0) h.seekAudio.setProgress((float) cur / durationMs);
                                    if (h.tvAudioDur != null) h.tvAudioDur.setText(elapsed);
                                }
                                seekHandler.postDelayed(this, 250);
                            }
                        }
                    };
                    seekHandler.post(seekUpdater);
                    // Allow user to scrub — canvas bubbles report seeks via
                    // OnBubbleClickListener.onAudioSeek() instead (wired in
                    // bindCanvasMessage), so this listener is legacy-only.
                    if (h.seekAudio != null) {
                        h.seekAudio.setOnSeekListener(fraction -> {
                            if (player != null && durationMs > 0) player.seekTo((int) (fraction * durationMs));
                        });
                    }
                }
            });
            player.setOnCompletionListener(mp -> {
                isPlayerPlaying = false;
                notifyPlaybackChanged(getItem(position), false);
                GlobalVoicePlaybackManager.getInstance().notifyStopped(__voiceMid);
                playingPos = -1;
                seekHandler.removeCallbacks(seekUpdater);
                resetAudioUi(h);
                try { mp.release(); } catch (Exception ignored) {}
                player = null;
            });
            player.setOnErrorListener((mp, what, extra) -> {
                android.util.Log.e("AudioPlay", "Error: " + what + " extra: " + extra + " path: " + path);
                isPlayerPlaying = false;
                notifyPlaybackChanged(getItem(position), false);
                GlobalVoicePlaybackManager.getInstance().notifyStopped(__voiceMid);
                playingPos = -1;
                setPlayPauseIcon(h, false);
                return true;
            });
        } catch (Exception e) {
            android.util.Log.e("AudioPlay", "playAudioFromPath error: " + e.getMessage() + " path: " + path);
            isPlayerPlaying = false;
            if (player != null) { try { player.release(); } catch (Exception ignored) {} player = null; }
        }
    }

    /**
     * Feature: Voice Caption on Photo — play-badge + duration pill overlaid
     * on an image bubble that also carries a short attached voice note
     * (m.voiceUrl). Reuses the adapter's existing shared-MediaPlayer
     * toggleAudio()/playAudioFromPath() machinery (same one standalone
     * voice-message bubbles use) — tap toggles play/pause, and the glyph
     * is driven by setPlayPauseIcon()/resetAudioUi() (both already know
     * about h.ivVoicePlayOnImage). For the RECEIVER only, the voice note
     * auto-plays once (walkie-talkie style) the first time this message's
     * bubble is bound — tracked in autoPlayedVoiceOnImage so it never
     * replays on later scroll/rebind, and skipped if some other bubble is
     * already mid-playback so we don't step on the user's own listening.
     */
    private void bindVoiceOnImage(@NonNull VH h, @NonNull Message m, int position) {
        if (h.flVoiceOnImage == null) return;
        boolean hasVoice = m.voiceUrl != null && !m.voiceUrl.isEmpty();
        if (!hasVoice) {
            h.flVoiceOnImage.setVisibility(View.GONE);
            return;
        }
        h.flVoiceOnImage.setVisibility(View.VISIBLE);

        // Duration pill text — mirrors the audio bubble's mm:ss formatting.
        if (h.tvVoiceDurationOnImage != null) {
            long ms = m.voiceDuration != null ? m.voiceDuration : 0L;
            long secs = ms / 1000;
            h.tvVoiceDurationOnImage.setText(
                    String.format(java.util.Locale.US, "%d:%02d", secs / 60, secs % 60));
        }

        // Icon reflects whether THIS message's voice note is the one
        // currently playing (matches how the standalone audio bubble icon
        // is kept in sync across rebinds).
        boolean isThisPlaying = playingPos == position && player != null && isPlayerPlaying;
        if (h.ivVoicePlayOnImage != null) {
            h.ivVoicePlayOnImage.setImageResource(isThisPlaying ? R.drawable.ic_pause : R.drawable.ic_play);
        }

        h.flVoiceOnImage.setOnClickListener(v -> toggleAudio(h, m.voiceUrl, position));

        // Receiver-only, once-per-message auto-play.
        String msgKey = m.messageId != null ? m.messageId : m.id;
        boolean sentByMe = currentUid.equals(m.senderId);
        if (!sentByMe && msgKey != null && playingPos == -1
                && !autoPlayedVoiceOnImage.contains(msgKey)) {
            autoPlayedVoiceOnImage.add(msgKey);
            toggleAudio(h, m.voiceUrl, position);
        }
    }

    /**
     * WhatsApp-style manual download pill for a received image bubble.
     * - Already cached locally → hide overlay, swap the bubble to the
     *   full-res local file (sharper than the 200x200 thumb).
     * - Not cached → show the pill with the remote file size, wired to
     *   start the download (with live % progress) on tap.
     *
     * BUG FIX (Voice Caption on Photo — image can't be opened after send):
     * this is the ONLY entry point that downloads the full-res image for
     * the legacy image bubble used when m.voiceUrl is set (see
     * isCanvasEligible()) — plain images never reach this method, they go
     * through the Canvas path's own decrypt-aware download-gate instead.
     * For a Media-E2E chat, m.mediaUrl/fullUrl is CIPHERTEXT
     * (resource_type=raw) — this used to call MediaCache.getWithProgress()
     * with no decrypt key at all, so it downloaded and cached the raw
     * ciphertext bytes as if they were a plain image, then handed that
     * undecodable file straight to Glide. Result: the download pill
     * "succeeds" but the bubble (and every viewer opened from it, since
     * showMediaActionSheet's VIEW falls back to this same cached file)
     * shows a broken image — exactly the "photo open nahi ho raha" report.
     * Now mirrors the Canvas path (see onImageClick's aWarmKey /
     * decryptKeyOnly pattern): derive the full-res subkey from m.mediaKeyEnc
     * up front and pass it through so MediaCache decrypts on the way down,
     * same as every other E2E media type already does.
     */
    private void bindDownloadOverlay(Context ctx, VH h, String fullUrl, @NonNull Message m) {
        if (fullUrl == null || fullUrl.isEmpty()) {
            h.fl_download_overlay.setVisibility(View.GONE);
            return;
        }
        // Guards async callbacks below against the holder having been
        // recycled onto a different message by the time they fire.
        h.fl_download_overlay.setTag(fullUrl);

        // Media E2E (image): derive the full-res subkey once up front so
        // both the cache lookup key-space and the actual download below
        // agree on plaintext — see method doc above.
        final byte[] dlMediaKey = (m.mediaKeyEnc != null)
                ? com.callx.app.utils.MediaE2ECrypto.decryptKeyOnly(ctx, m.mediaKeyEnc,
                        m.senderId, (m.messageId != null ? m.messageId : m.id))
                : null;

        java.io.File cachedFile = com.callx.app.utils.MediaCache.getCached(ctx, fullUrl);
        if (cachedFile != null) {
            h.fl_download_overlay.setVisibility(View.GONE);
            glide(ctx).load(cachedFile).override(480, 480).centerCrop().into(h.ivImage);
            return;
        }

        h.fl_download_overlay.setVisibility(View.VISIBLE);
        boolean isDownloading = downloadingMediaUrls.contains(fullUrl);
        setDownloadPillState(h, isDownloading, isDownloading ? -1 : Integer.MIN_VALUE, "Photo");

        if (!isDownloading) {
            // Fetch just the size for the label — doesn't download the file.
            com.callx.app.utils.MediaCache.getRemoteSize(ctx, fullUrl, new com.callx.app.utils.MediaCache.SizeCallback() {
                @Override public void onSize(long bytes) {
                    if (!fullUrl.equals(h.fl_download_overlay.getTag())) return; // recycled
                    if (!downloadingMediaUrls.contains(fullUrl)) {
                        h.tv_download_size.setText(formatFileSize(bytes));
                    }
                }
                @Override public void onError(String reason) { /* keep "Photo" label */ }
            });
        }

        h.ll_download_pill.setOnClickListener(v -> {
            if (downloadingMediaUrls.contains(fullUrl)) return; // already in flight
            downloadingMediaUrls.add(fullUrl);
            setDownloadPillState(h, true, 0, null);

            com.callx.app.utils.MediaCache.getWithProgress(ctx, fullUrl, dlMediaKey,
                    new com.callx.app.utils.MediaCache.ProgressCallback() {
                @Override public void onProgress(int percent) {
                    if (!fullUrl.equals(h.fl_download_overlay.getTag())) return;
                    setDownloadPillState(h, true, percent, null);
                }
                @Override public void onReady(java.io.File file) {
                    downloadingMediaUrls.remove(fullUrl);
                    if (!fullUrl.equals(h.fl_download_overlay.getTag())) return;
                    h.fl_download_overlay.setVisibility(View.GONE);
                    glide(ctx).load(file).override(480, 480).centerCrop().into(h.ivImage);
                }
                @Override public void onError(String reason) {
                    downloadingMediaUrls.remove(fullUrl);
                    if (!fullUrl.equals(h.fl_download_overlay.getTag())) return;
                    setDownloadPillState(h, false, Integer.MIN_VALUE, "Tap to retry");
                }
            });
        });
    }

    /** downloading=true + percent>=0 → spinner + "NN%". downloading=false → icon + label. */
    private void setDownloadPillState(VH h, boolean downloading, int percent, String idleLabel) {
        if (downloading) {
            h.iv_download_icon.setVisibility(View.GONE);
            h.pb_download_spinner.setVisibility(View.VISIBLE);
            h.tv_download_size.setText(percent >= 0 ? (percent + "%") : "0%");
        } else {
            h.iv_download_icon.setVisibility(View.VISIBLE);
            h.pb_download_spinner.setVisibility(View.GONE);
            if (idleLabel != null) h.tv_download_size.setText(idleLabel);
        }
    }

    
    /** Derives a best-guess MIME type from a filename extension — used for file
     *  bubbles since Message does not carry a mimeType field. */
    private static String guessMimeFromFileName(@Nullable String name) {
        if (name == null) return "*/*";
        int dot = name.lastIndexOf('.');
        if (dot < 0) return "*/*";
        switch (name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT)) {
            case "pdf":  return "application/pdf";
            case "doc":  return "application/msword";
            case "docx": return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xls":  return "application/vnd.ms-excel";
            case "xlsx": return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "ppt":  return "application/vnd.ms-powerpoint";
            case "pptx": return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "zip":  return "application/zip";
            case "rar":  return "application/x-rar-compressed";
            case "mp3":  return "audio/mpeg";
            case "mp4":  return "video/mp4";
            case "jpg": case "jpeg": return "image/jpeg";
            case "png":  return "image/png";
            case "gif":  return "image/gif";
            case "sticker": return "image/webp";
            case "txt":  return "text/plain";
            default:     return "*/*";
        }
    }

    private static String formatFileSize(long bytes) {
        if (bytes <= 0) return "Photo";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return Math.round(bytes / 1024.0) + " kB";
        return String.format(java.util.Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /** Funnels a local audio play/pause/finish/error event out through
     *  ActionListener#onPlaybackStateChanged so ChatPlaybackPresenceController
     *  can publish (or clear) the chatPlayback/{chatId}/{uid} node. */
    private void notifyPlaybackChanged(Message m, boolean playing) {
        if (actionListener != null && m != null) actionListener.onPlaybackStateChanged(m, playing);
    }

    // ──────────────────────────────────────────────────────────────
    // Long-press bottom sheet actions
    // ──────────────────────────────────────────────────────────────
    // ── WhatsApp-style image action bottom sheet ──────────────────
    private void showImageActionSheet(Context ctx, Message m, String fullUrl, String thumbForViewer) {
        showImageActionSheet(ctx, m, fullUrl, thumbForViewer, null);
    }

    // srcRect — this bubble's on-screen image rect at tap time, threaded
    // through to MediaViewerActivity's "View"/"Edit" intents so it can
    // open/close with the Telegram-style shrink-into-thumbnail animation
    // (see MediaViewerSourceRect). Null is a safe no-op — falls back to
    // the plain fade/translate close.
    private void showImageActionSheet(Context ctx, Message m, String fullUrl, String thumbForViewer,
                                       @Nullable android.graphics.Rect srcRect) {
        showMediaActionSheet(ctx, m, fullUrl, thumbForViewer, "image", null, null, -1, srcRect);
    }

    /**
     * WhatsApp-style media action sheet — View / Edit / Save / Share / Forward /
     * Star / Delete. Originally image-only (see showImageActionSheet above,
     * kept as a thin "image" wrapper for the legacy call site); now shared
     * with single-video bubbles too so a 1:1 video tap gets the exact same
     * advanced-action menu a 1:1 image tap has always had, instead of just
     * jumping straight into the player with no other options reachable
     * except the long-press sheet (Reply/Copy/Star/Pin/Forward/Delete, no
     * View/Edit/Save there).
     *
     * @param mediaType   "image" or "video" — drives the MediaViewerActivity
     *                    "type" extra, MediaSaveHelper's save-as target
     *                    (Pictures/CallX vs Movies/CallX, correct MIME type),
     *                    and whether autoEdit routes into an image or video
     *                    edit session. MediaEditActivity already fully
     *                    supports video (see MediaViewerActivity's Edit
     *                    handler), so Edit stays available for both types —
     *                    no feature gap to work around there.
     * @param localPathHint local on-device path/URI for the media, if
     *                    already known by the caller (sender's own file, or
     *                    an already-downloaded receiver copy) — passed
     *                    straight through as MediaViewerActivity's
     *                    "localPath" extra for the "View" action so it opens
     *                    from disk at full quality instead of re-fetching
     *                    the remote URL. Falls back to m.mediaLocalPath when
     *                    null, same as the original image-only behavior.
     */
    private void showMediaActionSheet(Context ctx, Message m, String fullUrl, String thumbForViewer,
                                       String mediaType, @Nullable String localPathHint) {
        showMediaActionSheet(ctx, m, fullUrl, thumbForViewer, mediaType, localPathHint, null, -1, null);
    }

    private void showMediaActionSheet(Context ctx, Message m, String fullUrl, String thumbForViewer,
                                       String mediaType, @Nullable String localPathHint,
                                       @Nullable String mediaItemsJson, int startIndex) {
        showMediaActionSheet(ctx, m, fullUrl, thumbForViewer, mediaType, localPathHint,
                mediaItemsJson, startIndex, null);
    }

    /**
     * Group-aware overload — same sheet, same seven actions, used for a tap
     * on one cell inside a multi-media grid (bindMediaGroup) so grouped
     * photos/videos get the exact same View/Play·Edit·Save·Share·Forward·
     * Star·Delete menu a single image/video bubble already has, instead of
     * jumping straight into the swipeable gallery with no shortcut menu at
     * all (the gallery's own toolbar has equivalent buttons once you're
     * inside it — see MediaViewerActivity's btnEdit/btnShare/btnSave/
     * btnMoreOptions/select-mode row — but there was no entry point to any
     * of that without first swiping to the right item and hunting the
     * overflow menu).
     *
     * @param mediaItemsJson pre-serialized {@link com.callx.app.utils.MediaItemsJsonUtil}
     *                       payload for the whole group, or null for a
     *                       non-grouped single image/video. When present,
     *                       "View"/"Play" opens the full swipeable gallery
     *                       (same as the old direct-tap behavior) instead of
     *                       a single-item viewer, starting at startIndex —
     *                       Edit/Save/Share still act on just this one cell.
     * @param startIndex     index of the tapped cell within mediaItemsJson;
     *                       ignored when mediaItemsJson is null.
     */
    private void showMediaActionSheet(Context ctx, Message m, String fullUrl, String thumbForViewer,
                                       String mediaType, @Nullable String localPathHint,
                                       @Nullable String mediaItemsJson, int startIndex,
                                       @Nullable android.graphics.Rect srcRect) {
        final boolean isVideoSheet = "video".equals(mediaType);
        com.google.android.material.bottomsheet.BottomSheetDialog bsd =
                new com.google.android.material.bottomsheet.BottomSheetDialog(ctx);

        android.widget.LinearLayout root = new android.widget.LinearLayout(ctx);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setBackgroundColor(android.graphics.Color.parseColor("#1E1E1E"));

        // Drag handle
        android.widget.FrameLayout handleWrap = new android.widget.FrameLayout(ctx);
        android.view.View handle = new android.view.View(ctx);
        int dp4  = dp(ctx, 4);
        int dp36 = dp(ctx, 36);
        int dp5  = dp(ctx, 5);
        android.widget.FrameLayout.LayoutParams handleLp =
                new android.widget.FrameLayout.LayoutParams(dp36, dp4);
        handleLp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        handle.setLayoutParams(handleLp);
        handle.setBackgroundColor(android.graphics.Color.parseColor("#555555"));
        android.view.ViewGroup.MarginLayoutParams hlm = (android.view.ViewGroup.MarginLayoutParams) handle.getLayoutParams();
        hlm.topMargin = dp5;
        hlm.bottomMargin = dp5;
        handleWrap.setPadding(0, dp5, 0, dp5);
        handleWrap.addView(handle);
        root.addView(handleWrap);

        // Options: View, Edit, Save to Gallery, Share, Forward, Star, Delete
        // "Save to Gallery" appears for RECEIVED images that are already downloaded;
        // if not yet cached, it downloads first then saves — WhatsApp style.
        // BUG FIX: "Edit" was missing here entirely, and "View" never passed
        // chatId/messageId — so even opening the viewer and tapping ITS edit
        // pencil always hit MediaViewerActivity's "Can't edit — not opened
        // from a chat" guard (replyChatId/replyMessageId were always null
        // for single-image messages, since this bottom sheet is the only
        // entry point into MediaViewerActivity for them). Grouped-media taps
        // already passed chatId/messageId correctly — only this single-media
        // sheet was missing it.
        String viewLabel = isVideoSheet ? "▶  Play" : "🖼  View";
        String[] labels  = {viewLabel, "✏️  Edit", "💾  Save", "↗  Share", "↪  Forward", "⭐  Star", "🗑  Delete"};
        int[]    colors  = {0xFFFFFFFF,  0xFFFFFFFF,  0xFFFFFFFF,  0xFFFFFFFF,  0xFFFFFFFF,  0xFFFFFFFF,  0xFFFF5252 };

        boolean isOwnMsg = currentUid != null && currentUid.equals(m.senderId);
        final String sheetMessageId = (m.messageId != null && !m.messageId.isEmpty()) ? m.messageId : m.id;

        // BUG FIX (Voice Caption on Photo — image can't be opened after
        // send): a Media-E2E image/video's fullUrl is CIPHERTEXT
        // (resource_type=raw). VIEW/EDIT/SAVE below used to hand that
        // straight to MediaViewerActivity / MediaCache with no decrypt key
        // at all — only the sender's own mediaLocalPath fallback ever made
        // it viewable, so a RECEIVER tapping View/Save on an E2E image (the
        // legacy voice-caption bubble is the only bubble that reaches this
        // gap in practice, since the Canvas path decrypts inline before the
        // sheet ever opens) got a broken/undecodable file. Derive the
        // full-res subkey once here — same decryptKeyOnly() pattern already
        // used for E2E audio/video elsewhere — and thread it through.
        final byte[] sheetMediaKey = (!isOwnMsg && m.mediaKeyEnc != null)
                ? com.callx.app.utils.MediaE2ECrypto.decryptKeyOnly(ctx, m.mediaKeyEnc,
                        m.senderId, sheetMessageId)
                : null;
        final String sheetMediaKeyB64 = (sheetMediaKey != null)
                ? android.util.Base64.encodeToString(sheetMediaKey, android.util.Base64.NO_WRAP)
                : null;

        for (int idx = 0; idx < labels.length; idx++) {
            // Skip Delete if not own message; skip Edit if not own message
            if (labels[idx].contains("Delete") && !isOwnMsg) continue;
            if (labels[idx].contains("Edit") && !isOwnMsg) continue;

            android.widget.TextView tv = new android.widget.TextView(ctx);
            tv.setText(labels[idx]);
            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f);
            tv.setTextColor(colors[idx]);
            tv.setPadding(dp(ctx, 20), dp(ctx, 15), dp(ctx, 20), dp(ctx, 15));
            tv.setBackground(getRippleDrawable(ctx));

            final String label = labels[idx];
            final String action = (idx == 0) ? "VIEW"
                    : label.contains("Edit") ? "EDIT"
                    : label.contains("Save") ? "SAVE"
                    : label.contains("Share") ? "SHARE"
                    : label.contains("Forward") ? "FORWARD"
                    : label.contains("Star") ? "STAR"
                    : "DELETE";
            tv.setOnClickListener(v -> {
                bsd.dismiss();
                switch (action) {
                    case "VIEW": {
                        android.content.Intent i = new android.content.Intent()
                                .setClassName(ctx.getPackageName(),
                                        "com.callx.app.activities.MediaViewerActivity");
                        i.putExtra("url",      fullUrl);
                        i.putExtra("thumbUrl", thumbForViewer);
                        i.putExtra("type",     mediaType);
                        // Grouped-media tap: reopen as the full swipeable
                        // gallery at this cell's index, same as the old
                        // direct-tap behavior — Edit/Save/Share below still
                        // scope to just this one cell's fullUrl though.
                        if (mediaItemsJson != null) {
                            i.putExtra("mediaItemsJson", mediaItemsJson);
                            i.putExtra("startIndex", startIndex);
                        }
                        // WhatsApp-style local-first: hand the original local
                        // file/content Uri to the viewer too — it'll render
                        // from this (full quality) as long as it still exists
                        // on the device, falling back to `url` otherwise.
                        // Prefer the caller-supplied hint (e.g. a receiver's
                        // already-downloaded video cache path) over the raw
                        // message field, but fall back to it either way.
                        String localPath = localPathHint != null ? localPathHint : m.mediaLocalPath;
                        if (localPath != null && !localPath.isEmpty()) {
                            i.putExtra("localPath", localPath);
                        }
                        // FIX: chatId/messageId weren't passed before, so
                        // MediaViewerActivity's own Edit pencil couldn't work
                        // once inside the viewer either.
                        if (chatId != null && sheetMessageId != null) {
                            i.putExtra("chatId",    chatId);
                            i.putExtra("messageId", sheetMessageId);
                        }
                        // Media E2E — see sheetMediaKeyB64 derivation above.
                        if (sheetMediaKeyB64 != null) {
                            i.putExtra("mediaKeyB64", sheetMediaKeyB64);
                        }
                        // Telegram-style open/close animation — see
                        // MediaViewerSourceRect class doc. No-op if srcRect
                        // is null (grouped-canvas-cell taps don't have a
                        // precise per-cell rect yet).
                        com.callx.app.utils.MediaViewerSourceRect.attach(i, srcRect);
                        ctx.startActivity(i);
                        break;
                    }
                    case "EDIT": {
                        // WhatsApp-style: go straight into the full-screen
                        // editor instead of making the user View first, then
                        // tap Edit again inside the viewer. MediaEditActivity
                        // fully supports video (MediaViewerActivity's own
                        // Edit handler already relies on this), so this path
                        // is identical for image and video — only the
                        // "type" extra changes.
                        android.content.Intent ei = new android.content.Intent()
                                .setClassName(ctx.getPackageName(),
                                        "com.callx.app.activities.MediaViewerActivity");
                        ei.putExtra("url",      fullUrl);
                        ei.putExtra("thumbUrl", thumbForViewer);
                        ei.putExtra("type",     mediaType);
                        ei.putExtra("autoEdit", true);
                        String editLocalPath = localPathHint != null ? localPathHint : m.mediaLocalPath;
                        if (editLocalPath != null && !editLocalPath.isEmpty()) {
                            ei.putExtra("localPath", editLocalPath);
                        }
                        if (chatId != null && sheetMessageId != null) {
                            ei.putExtra("chatId",    chatId);
                            ei.putExtra("messageId", sheetMessageId);
                        } else {
                            android.widget.Toast.makeText(ctx, "Can't edit — not opened from a chat", android.widget.Toast.LENGTH_SHORT).show();
                            break;
                        }
                        // Media E2E — see sheetMediaKeyB64 derivation above.
                        if (sheetMediaKeyB64 != null) {
                            ei.putExtra("mediaKeyB64", sheetMediaKeyB64);
                        }
                        ctx.startActivity(ei);
                        break;
                    }
                    case "SAVE": {
                        // WhatsApp-style: save to gallery.
                        // If already cached → save immediately.
                        // If not cached → download first, then save.
                        final android.content.Context appCtx = ctx.getApplicationContext();
                        java.io.File alreadyCached = com.callx.app.utils.MediaCache.getCached(appCtx, fullUrl);
                        String savedMsg = isVideoSheet ? "Saved video to gallery" : "Saved to gallery";
                        if (alreadyCached != null) {
                            com.callx.app.utils.MediaSaveHelper.save(
                                    appCtx, alreadyCached, mediaType, fullUrl,
                                    new com.callx.app.utils.MediaSaveHelper.Callback() {
                                @Override public void onSaved(android.net.Uri uri) {
                                    android.widget.Toast.makeText(appCtx,
                                            savedMsg, android.widget.Toast.LENGTH_SHORT).show();
                                }
                                @Override public void onError(String reason) {
                                    android.widget.Toast.makeText(appCtx,
                                            "Save failed: " + reason, android.widget.Toast.LENGTH_SHORT).show();
                                }
                            });
                        } else {
                            android.widget.Toast.makeText(appCtx,
                                    "Downloading…", android.widget.Toast.LENGTH_SHORT).show();
                            // Media E2E — see sheetMediaKey derivation above; without
                            // this, a Media-E2E image/video saved to gallery via this
                            // path saved raw ciphertext bytes instead of the photo.
                            com.callx.app.utils.MediaCache.getWithProgress(appCtx, fullUrl, sheetMediaKey,
                                    new com.callx.app.utils.MediaCache.ProgressCallback() {
                                @Override public void onProgress(int percent) {}
                                @Override public void onReady(java.io.File file) {
                                    com.callx.app.utils.MediaSaveHelper.save(
                                            appCtx, file, mediaType, fullUrl,
                                            new com.callx.app.utils.MediaSaveHelper.Callback() {
                                        @Override public void onSaved(android.net.Uri uri) {
                                            android.widget.Toast.makeText(appCtx,
                                                    savedMsg, android.widget.Toast.LENGTH_SHORT).show();
                                        }
                                        @Override public void onError(String reason) {
                                            android.widget.Toast.makeText(appCtx,
                                                    "Save failed: " + reason, android.widget.Toast.LENGTH_SHORT).show();
                                        }
                                    });
                                }
                                @Override public void onError(String reason) {
                                    android.widget.Toast.makeText(appCtx,
                                            "Download failed", android.widget.Toast.LENGTH_SHORT).show();
                                }
                            });
                        }
                        break;
                    }
                    case "SHARE":
                        android.content.Intent share = new android.content.Intent(
                                android.content.Intent.ACTION_SEND);
                        share.setType("text/plain");
                        share.putExtra(android.content.Intent.EXTRA_TEXT, fullUrl);
                        ctx.startActivity(android.content.Intent.createChooser(share, "Share via"));
                        break;
                    case "FORWARD":
                        if (actionListener != null) actionListener.onForward(m);
                        break;
                    case "STAR":
                        if (actionListener != null) actionListener.onStar(m);
                        break;
                    case "DELETE":
                        if (actionListener != null) actionListener.onDelete(m);
                        break;
                }
            });
            root.addView(tv);

            // Divider (not after last)
            if (idx < labels.length - 1 && !(idx == labels.length - 2 && !isOwnMsg)) {
                android.view.View div = new android.view.View(ctx);
                android.widget.LinearLayout.LayoutParams dlp =
                        new android.widget.LinearLayout.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT, 1);
                dlp.setMarginStart(dp(ctx, 20));
                div.setLayoutParams(dlp);
                div.setBackgroundColor(android.graphics.Color.parseColor("#333333"));
                root.addView(div);
            }
        }

        root.setPadding(0, 0, 0, dp(ctx, 16));
        bsd.setContentView(root);
        // Dark bottom sheet
        if (bsd.getWindow() != null) {
            bsd.getWindow().setNavigationBarColor(android.graphics.Color.parseColor("#1E1E1E"));
        }
        bsd.show();
    }

    private int dp(Context ctx, int value) {
        return (int)(value * ctx.getResources().getDisplayMetrics().density);
    }

    private android.graphics.drawable.Drawable getRippleDrawable(Context ctx) {
        android.graphics.drawable.ColorDrawable content =
                new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT);
        android.content.res.ColorStateList rippleColor =
                android.content.res.ColorStateList.valueOf(
                        android.graphics.Color.parseColor("#33FFFFFF"));
        return new android.graphics.drawable.RippleDrawable(rippleColor, content, null);
    }

    private void showActionBottomSheet(Context ctx, Message m) {
        if (actionListener == null) return;

        // ── Step 1: Build emoji reaction row ──────────────────────────
        // PERF ADV: was 7 TextViews + 7 RippleDrawables + 2 LinearLayouts
        // rebuilt from scratch on EVERY long-press (measure+layout on the
        // main thread right as the chat list is settling from the touch).
        // Now a single custom View that paints everything in one onDraw()
        // pass — see ReactionQuickBarCanvasView for the full rationale.
        com.callx.app.conversation.canvas.ReactionQuickBarCanvasView emojiBar =
                new com.callx.app.conversation.canvas.ReactionQuickBarCanvasView(ctx);
        String alreadyReacted = currentUid != null && m.reactions != null
                ? m.reactions.get(currentUid) : null;
        emojiBar.bind(alreadyReacted);

        // Wrap in a container so AlertDialog can host it as a custom title
        android.widget.LinearLayout wrapper = new android.widget.LinearLayout(ctx);
        wrapper.setOrientation(android.widget.LinearLayout.VERTICAL);
        wrapper.setGravity(android.view.Gravity.CENTER);
        int vPad = (int)(12 * ctx.getResources().getDisplayMetrics().density);
        wrapper.setPadding(0, vPad, 0, vPad);
        wrapper.addView(emojiBar);

        // Keep a dialog reference so emoji tap can dismiss it
        final android.app.AlertDialog[] holder = new android.app.AlertDialog[1];

        emojiBar.setListener(new com.callx.app.conversation.canvas.ReactionQuickBarCanvasView.OnEmojiPickListener() {
            @Override public void onEmojiPicked(String emoji) {
                actionListener.onReact(m, emoji);
                if (holder[0] != null) holder[0].dismiss();
            }
            @Override public void onMoreTapped() {
                if (holder[0] != null) holder[0].dismiss();
                showFullEmojiPicker(ctx, m);
            }
        });

        // ── Step 2: Build action items list ───────────────────────────
        boolean isOwnMsg     = currentUid != null && currentUid.equals(m.senderId);
        boolean isTextMsg    = m.text != null && !m.text.trim().isEmpty()
                               && (m.type == null || "text".equals(m.type));
        boolean canEdit      = isOwnMsg && isTextMsg;
        boolean isStarred    = Boolean.TRUE.equals(m.starred);

        boolean isPinned = Boolean.TRUE.equals(m.pinned);
        boolean isPoll   = "poll".equals(m.type);
        boolean isPollClosed = Boolean.TRUE.equals(m.pollClosed);
        boolean hasEditHistory = Boolean.TRUE.equals(m.edited);

        // Feature 2: check if this message is currently saved in global bookmarks
        boolean isSaved = false;
        if (m.id != null) {
            try {
                isSaved = com.callx.app.db.AppDatabase.getInstance(ctx)
                        .savedMessageDao().isSaved(m.id) > 0;
            } catch (Exception ignored) {}
        }

        java.util.List<String> optList = new java.util.ArrayList<>();
        optList.add("Reply");
        optList.add("Copy");
        if (isTextMsg) optList.add("Translate");
        optList.add(isStarred ? "Unstar" : "Star");
        optList.add(isSaved  ? "Unsave" : "Save");    // Feature 2
        optList.add(isPinned ? "Unpin" : "Pin");
        optList.add("Forward");
        if (canEdit) optList.add("Edit");
        if (hasEditHistory) optList.add("Edit history");
        if (isPoll && isOwnMsg) optList.add(isPollClosed ? "Reopen Poll" : "Close Poll");
        optList.add("Delete");
        String[] options = optList.toArray(new String[0]);

        android.app.AlertDialog.Builder builder =
                new android.app.AlertDialog.Builder(ctx)
                    .setCustomTitle(wrapper)
                    .setItems(options, (d, which) -> {
                        String choice = options[which];
                        switch (choice) {
                            case "Reply":   actionListener.onReply(m);   break;
                            case "Copy":    actionListener.onCopy(m);    break;
                            case "Translate": actionListener.onTranslate(m); break;
                            case "Star":    // fall-through
                            case "Unstar":  actionListener.onStar(m);    break;
                            case "Save":    actionListener.onSaveMessage(m, true);  break;  // Feature 2
                            case "Unsave":  actionListener.onSaveMessage(m, false); break;  // Feature 2
                            case "Pin":     // fall-through
                            case "Unpin":   actionListener.onPin(m);     break;
                            case "Forward": actionListener.onForward(m); break;
                            case "Edit":    actionListener.onEdit(m);    break;
                            case "Edit history": actionListener.onShowEditHistory(m); break;
                            case "Close Poll":  // fall-through
                            case "Reopen Poll": actionListener.onPollToggleClose(m); break;
                            case "Delete":  actionListener.onDelete(m);  break;
                        }
                    });
        android.app.AlertDialog dlgLongPress = builder.create();
        com.callx.app.utils.AlertDialogStyler.showRounded(dlgLongPress);
        holder[0] = dlgLongPress;
    }

    // FIX [P3-3]: Full emoji picker — 8-column scrollable grid of common emojis
    // PERF ADV: was GridView + ArrayAdapter(android.R.layout.simple_list_item_1)
    // — ~24-32 concurrent TextView inflate+bind passes the instant the dialog
    // opened, on the main thread, right as the RecyclerView (which the
    // long-press interrupted) is settling. Now ReactionGridCanvasView draws
    // all 80 emojis in one onDraw() pass with zero child-View inflation;
    // wrapped in a plain ScrollView (cheap: one child, no recycler
    // machinery) so the scrollable-grid UX is unchanged.
    private void showFullEmojiPicker(Context ctx, Message m) {
        if (actionListener == null) return;

        com.callx.app.conversation.canvas.ReactionGridCanvasView grid =
                new com.callx.app.conversation.canvas.ReactionGridCanvasView(ctx);

        android.widget.ScrollView scrollHost = new android.widget.ScrollView(ctx);
        int pad = (int) (12 * ctx.getResources().getDisplayMetrics().density);
        scrollHost.setPadding(pad, pad, pad, pad);
        scrollHost.addView(grid, new android.widget.ScrollView.LayoutParams(
                android.widget.ScrollView.LayoutParams.MATCH_PARENT,
                android.widget.ScrollView.LayoutParams.WRAP_CONTENT));

        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(ctx)
                .setTitle("Pick an emoji")
                .setView(scrollHost)
                .create();

        grid.setListener(emoji -> {
            actionListener.onReact(m, emoji);
            dialog.dismiss();
        });

        com.callx.app.utils.AlertDialogStyler.showRounded(dialog,
                com.callx.app.utils.AlertDialogStyler.DialogSize.WIDE);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ViewStub inflation helpers — one-time inflate + child ref caching.
    //  Each method is a no-op after first call (h.flVideo != null, etc.).
    // ══════════════════════════════════════════════════════════════════════════

    private void ensureVideoInflated(@NonNull VH h) {
        if (h.flVideo != null || h.stubVideo == null) return;
        h.stubVideo.inflate(); h.stubVideo = null;
        h.flVideo      = h.itemView.findViewById(R.id.fl_video);
        h.ivVideoThumb = h.itemView.findViewById(R.id.iv_video_thumb);
        h.tvDuration   = h.itemView.findViewById(R.id.tv_duration);
    }

    private void ensureAudioInflated(@NonNull VH h, Context ctx, boolean sent) {
        if (h.llAudio != null || h.stubAudio == null) return;
        h.stubAudio.inflate(); h.stubAudio = null;
        h.llAudio        = h.itemView.findViewById(R.id.ll_audio);
        h.btnPlayPause   = h.itemView.findViewById(R.id.btn_play_pause);
        h.seekAudio      = h.itemView.findViewById(R.id.seek_audio);
        h.tvAudioDur     = h.itemView.findViewById(R.id.tv_audio_dur);
        h.btnAudioSpeed  = h.itemView.findViewById(R.id.btn_audio_speed); // Feature 4
        if (h.tvAudioDur != null) {
            try {
                int color = ctx.getResources().getColor(
                        sent ? R.color.bubble_sent_text : R.color.bubble_received_text);
                h.tvAudioDur.setTextColor(color);
            } catch (Exception ignored) {}
        }
    }

    private void ensureFileInflated(@NonNull VH h, Context ctx, boolean sent) {
        if (h.llFile != null || h.stubFile == null) return;
        h.stubFile.inflate(); h.stubFile = null;
        h.llFile      = h.itemView.findViewById(R.id.ll_file);
        h.tvFileName  = h.itemView.findViewById(R.id.tv_file_name);
        h.btnDownload = h.itemView.findViewById(R.id.btn_download);
    }

    private void ensurePollInflated(@NonNull VH h) {
        if (h.llPoll != null || h.stubPoll == null) return;
        h.stubPoll.inflate(); h.stubPoll = null;
        h.llPoll            = h.itemView.findViewById(R.id.ll_poll);
        h.llPollOptions     = h.itemView.findViewById(R.id.ll_poll_options);
        h.tvPollQuestion    = h.itemView.findViewById(R.id.tv_poll_question);
        h.tvPollTotalVotes  = h.itemView.findViewById(R.id.tv_poll_total_votes);
        h.tvPollStatusBadge = h.itemView.findViewById(R.id.tv_poll_status_badge);
        h.tvPollSubtitle    = h.itemView.findViewById(R.id.tv_poll_subtitle);
        h.ivPollIcon        = h.itemView.findViewById(R.id.iv_poll_icon);
    }

    private void ensureLinkPreviewInflated(@NonNull VH h, boolean sent) {
        if (h.llLinkPreview != null || h.stubLinkPreview == null) return;
        h.stubLinkPreview.inflate(); h.stubLinkPreview = null;
        h.llLinkPreview = h.itemView.findViewById(R.id.ll_link_preview);
        h.tvLinkTitle   = h.itemView.findViewById(R.id.tv_link_title);
        h.tvLinkDomain  = h.itemView.findViewById(R.id.tv_link_domain);
        h.ivLinkThumb   = h.itemView.findViewById(R.id.iv_link_thumb);
    }

    /**
     * PAYLOAD_POLL fast path — re-renders only the poll vote bars / percentages
     * inside an already-bound canvas or legacy poll bubble without touching
     * text, media, Glide loads, Linkify, or any other part of the bind.
     *
     * Canvas path: delegates to cv.bindPoll() which re-measures and invalidates
     * only the poll card section.  Legacy ViewStub path: re-calls bindPoll()
     * which updates the existing inflated option rows in place.
     */
    private void bindPollOnly(@NonNull VH h, @NonNull Message m) {
        if (!"poll".equals(m.type)) return;
        boolean sent = m.senderId != null && m.senderId.equals(currentUid);
        if (h.canvasView != null) {
            // Reconstruct full poll parameters — same logic as the isPoll branch
            // in bindCanvasMessage() but only called for vote-count changes, so
            // text/media/Glide paths are never touched.
            java.util.List<String> opts = m.pollOptions != null
                    ? m.pollOptions : java.util.Collections.emptyList();
            java.util.Map<String, java.util.List<Integer>> votesMap = m.pollVotes != null
                    ? m.pollVotes : java.util.Collections.emptyMap();
            int[] counts = com.callx.app.utils.PollJsonUtil.countVotes(votesMap, opts.size());
            int total    = com.callx.app.utils.PollJsonUtil.totalVotes(votesMap);
            boolean[] myVote = new boolean[opts.size()];
            if (currentUid != null) {
                java.util.List<Integer> mine = votesMap.get(currentUid);
                if (mine != null) {
                    for (int idx : mine) {
                        if (idx >= 0 && idx < myVote.length) myVote[idx] = true;
                    }
                }
            }
            String timeStr = (m.timestamp != null && m.timestamp > 0) ? formatTime(m.timestamp) : "";
            if (Boolean.TRUE.equals(m.edited)) timeStr = timeStr + "  \u270F\uFE0F edited";
            boolean isRead      = "read".equals(m.status);
            boolean isDelivered = isRead || "delivered".equals(m.status);
            h.canvasView.bindPoll(
                    m.pollQuestion, opts, counts, myVote, total,
                    Boolean.TRUE.equals(m.pollClosed),
                    Boolean.TRUE.equals(m.pollMultiChoice),
                    sent, timeStr, isRead, isDelivered);
            return;
        }
        // Legacy non-canvas path — defer to the full poll bind helper.
        bindPoll(h, m, sent);
    }

    /**
     * PAYLOAD_EDITED fast path — updates only the "✏️ edited" suffix on the
     * footer timestamp view.  No text re-layout, no Glide, no full rebind.
     *
     * Canvas path: calls cv.setEdited() which only flips a flag and triggers
     * an invalidate (no measure).  Legacy path: appends/strips the suffix on
     * tv_time.
     */
    private void bindEditedOnly(@NonNull VH h, @NonNull Message m) {
        boolean isEdited = Boolean.TRUE.equals(m.edited);
        if (h.canvasView != null) {
            h.canvasView.setEdited(isEdited);
            return;
        }
        if (h.tvTime == null) return;
        long ts = m.timestamp != null ? m.timestamp : 0;
        String base = ts > 0 ? formatTime(ts) : "";
        h.tvTime.setText(isEdited ? base + "  \u270F\uFE0F edited" : base);
    }

    /**
     * PERF fast path for PAYLOAD_SEARCH: repaints just the search
     * highlight, skipping the full bindMessage()/bindCanvasMessage() bind
     * (Glide reload, Linkify, footer, canvas re-measure, etc.) entirely.
     *
     * Canvas-rendered bubbles (the common case — see isCanvasEligible(),
     * "text" type is always canvas-eligible) just get a cheap
     * setSearchHighlight() call, which itself no-ops unless the query
     * actually changed and otherwise only invalidate()s — no re-layout.
     *
     * The legacy TextView path (item_message_sent/received.xml, used for
     * message types Canvas doesn't fully model yet) rebuilds its
     * BackgroundColorSpan overlay directly on the TextView's current
     * Spannable — reusing whatever mention/link spans a prior full bind
     * already applied — rather than rebuilding from scratch.
     */
    private void bindSearchHighlightOnly(@NonNull VH h, @NonNull Message m) {
        if (h.canvasView != null) {
            h.canvasView.setSearchHighlight(activeSearchQuery);
            return;
        }
        if (h.tvMessage == null) return;
        CharSequence current = h.tvMessage.getText();
        if (!(current instanceof android.text.Spannable)) return;
        android.text.Spannable sp = (android.text.Spannable) current;
        // Strip only our own old highlight spans, leaving mention/link
        // spans from the last full bind untouched.
        for (android.text.style.BackgroundColorSpan old :
                sp.getSpans(0, sp.length(), android.text.style.BackgroundColorSpan.class)) {
            sp.removeSpan(old);
        }
        if (activeSearchQuery != null && !activeSearchQuery.isEmpty()) {
            String lq = activeSearchQuery.toLowerCase(java.util.Locale.getDefault());
            String lt = sp.toString().toLowerCase(java.util.Locale.getDefault());
            int si = 0;
            while ((si = lt.indexOf(lq, si)) != -1) {
                int se = si + lq.length();
                sp.setSpan(new android.text.style.BackgroundColorSpan(0xFFFFEB3B),
                        si, se, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                si = se;
            }
        }
        h.tvMessage.invalidate();
    }

    @Override
    public void onViewRecycled(@NonNull VH holder) {
        super.onViewRecycled(holder);
        
        // WHATSAPP-STYLE HEIGHT CACHE: Cache this message's measured height
        // before the view gets recycled. When this message is bound again
        // (or a similar message), we can reuse this height to avoid re-measure.
        Message m = holder.boundMessage; // Track which message was bound to this holder
        if (m != null && m.messageId != null && holder.itemView.getHeight() > 0) {
            messagHeightCache.put(m.messageId, holder.itemView.getHeight());
        }
        
        // FIX #3: Clear ALL ImageViews on recycle — not just ivImage.
        // Missing clears on ivReplyThumb/ivLinkThumb/ivVideoThumb/ivStatusSeenThumb/ivReelSeenThumb
        // caused Glide memory leaks and stale image flicker on fast scrolling.
        Context ctx = holder.itemView.getContext();
        if (holder.ivImage           != null) glide(ctx).clear(holder.ivImage);
        if (holder.ivReplyThumb      != null) glide(ctx).clear(holder.ivReplyThumb);
        if (holder.ivLinkThumb       != null) glide(ctx).clear(holder.ivLinkThumb);
        if (holder.ivVideoThumb      != null) glide(ctx).clear(holder.ivVideoThumb);
        if (holder.ivStatusSeenThumb != null) glide(ctx).clear(holder.ivStatusSeenThumb);
        if (holder.ivReelSeenThumb   != null) glide(ctx).clear(holder.ivReelSeenThumb);
        // Same staleness-guard idea as bindCanvasMessage()'s Glide
        // CustomTarget calls — invalidate any in-flight canvas image/reply-
        // thumb load the instant this holder is recycled.
        holder.canvasBindToken++;
        // v59: reset GIF and file bubble state so a recycled holder can't
        // bleed stale badge/icon/progress into the next item it's bound to.
        if (holder.canvasView != null) {
            holder.canvasView.resetGif();
            holder.canvasView.resetSticker();
            holder.canvasView.clearFileBubble();
            holder.canvasView.clearRecycledBitmaps();
        }
        // Invalidate the download-overlay binding guard so an in-flight
        // getRemoteSize/getWithProgress callback for the old message
        // can't touch this holder after it's been recycled for a new one.
        if (holder.fl_download_overlay != null) holder.fl_download_overlay.setTag(null);
        // Stop any pending tick updates from the shared manager to prevent leaks on recycled views
        com.callx.app.utils.ExpiryTickManager.get().unregister(holder);
        // Invalidate any in-flight async PrecomputedText work for this
        // holder — it may still be running on TEXT_PRECOMPUTE_EXECUTOR
        // when the holder goes back into the pool. The posted callback
        // checks textBindToken before applying, so bumping it here makes
        // any such result a guaranteed no-op even if it lands while the
        // holder is sitting unused in the pool.
        holder.textBindToken++;
        // PERF ADV: reset hardware layer type on recycle so the view sitting
        // in the RecycledViewPool doesn't keep an allocated GPU texture alive
        // for content that may never be displayed again.  The next bindCanvasMessage()
        // call will set the correct layer type based on the new message's complexity.
        if (holder.canvasView != null) {
            holder.canvasView.setLayerType(android.view.View.LAYER_TYPE_NONE, null);
        }
        // TELEGRAM-STYLE SEND ANIMATION: cancel + snap back any in-flight
        // send-in springs before this holder goes back into the pool — a
        // fast-scroll recycle mid-bounce must never leave the row's
        // transform (or its GPU layer) bleeding onto the next message this
        // holder gets rebound to.
        resetSendAnimState(holder);
    }

    // PERF: onViewDetachedFromWindow — called when the ViewHolder scrolls off
    // the screen but hasn't been recycled yet. Stop audio playback for this
    // holder so a voice note playing in a bubble that scrolled off-screen stops
    // immediately rather than continuing silently (and tying up the MediaPlayer).
    @Override
    public void onViewDetachedFromWindow(@NonNull VH holder) {
        super.onViewDetachedFromWindow(holder);
        // If THIS holder was the playing one, stop audio
        if (player != null && playingVH == holder) {
            try { player.stop(); player.release(); } catch (Exception ignored) {}
            player = null;
            playingPos = -1;
            playingVH  = null;
            if (seekUpdater != null) { seekHandler.removeCallbacks(seekUpdater); seekUpdater = null; }
        }
        // Stop any in-progress link-preview load for this row — the URL tag
        // was already used as a stale-result guard in the fetch callback, but
        // clearing it here prevents a VISIBLE slot from showing a loading card
        // for a URL that belongs to a different (now-recycled) message.
        if (holder.llLinkPreview != null) holder.llLinkPreview.setTag(null);
    }

    // ── Disappearing messages — format remaining time ─────────────────────
    private static String formatRemaining(long ms) {
        long secs  = ms / 1000;
        long mins  = secs / 60;
        long hours = mins / 60;
        long days  = hours / 24;
        if (days  > 0) return days  + "d";
        if (hours > 0) return hours + "h";
        if (mins  > 0) return mins  + "m";
        return secs + "s";
    }

    // ──────────────────────────────────────────────────────────────
    // Font Style — always default (typing style system removed)
    private static void applyFontStyle(android.widget.TextView tv, int styleId) {
        tv.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.NORMAL));
    }

    // ──────────────────────────────────────────────────────────────
    // ViewHolder — covers all view IDs used in both item layouts
    // ──────────────────────────────────────────────────────────────
    /** Fast-path: rebind only the tick icon, called from payload-aware onBind. */
    private void bindStatusTick(@NonNull VH h, @NonNull Message m) {
        boolean sent = currentUid != null && currentUid.equals(m.senderId);
        if (!sent || h.tvStatus == null) {
            if (h.tvStatus != null) h.tvStatus.setVisibility(View.GONE);
            return;
        }
        h.tvStatus.setVisibility(View.VISIBLE);
        String s = m.status == null ? "" : m.status;
        if (("read".equals(s) || "seen".equals(s))
                && !new com.callx.app.utils.SecurityManager(h.itemView.getContext()).isReadReceiptsEnabled()) {
            s = "delivered";
        }
        switch (s) {
            case "read":
                h.tvStatus.setText("✓✓");
                h.tvStatus.setTextColor(0xFF4FC3F7);
                h.tvStatus.setOnClickListener(null);
                break;
            case "delivered":
                h.tvStatus.setText("✓✓");
                h.tvStatus.setTextColor(0xAAFFFFFF);
                h.tvStatus.setOnClickListener(null);
                break;
            case "sending":
            case "uploading":
                // "uploading" = WhatsApp-style local-first media bubble
                // (see ChatMessageSender#insertLocalPendingMedia) — same
                // clock-icon treatment as "sending"; the bubble's own
                // spinner ring shows the real upload progress.
                h.tvStatus.setText("🕐");
                h.tvStatus.setTextColor(0xFFAAAAAA);
                h.tvStatus.setOnClickListener(null);
                break;
            case "failed":
                h.tvStatus.setText("⚠");
                h.tvStatus.setTextColor(0xFFFF5555);
                break;
            default:
                h.tvStatus.setText("✓");
                h.tvStatus.setTextColor(0xAAFFFFFF);
                h.tvStatus.setOnClickListener(null);
                break;
        }
    }

    /** Fast-path: rebind ONLY the three presence-driven views — the
     *  "someone's viewing this" dot, the "someone's playing this" badge,
     *  and the "someone's replying to this" bubble glow. Called from the
     *  payload-aware onBind for PAYLOAD_PRESENCE, so a viewing/typing/
     *  playback broadcast no longer re-runs the whole bindMessage() (no
     *  Glide reload, no Linkify, no new GradientDrawable, no countdown
     *  restart) just to flip a dot. Logic mirrors the equivalent blocks
     *  inside bindMessage() exactly — keep both in sync if either changes. */
    private void bindPresenceOnly(@NonNull VH h, @NonNull Message m) {
        String mid = m.messageId != null ? m.messageId : m.id;

        if (h.viewSeenDot != null) {
            boolean viewing = mid != null && currentlyViewedMessageIds.contains(mid);
            h.viewSeenDot.setVisibility(viewing ? View.VISIBLE : View.GONE);
        }

        if (h.tvListeningBadge != null) {
            boolean playing = mid != null && currentlyPlayingMessageIds.contains(mid);
            if (playing) {
                boolean isVideoMsg = "video".equals(m.type);
                h.tvListeningBadge.setText(isVideoMsg ? "▶ watching…" : "🎧 listening…");
            }
            h.tvListeningBadge.setVisibility(playing ? View.VISIBLE : View.GONE);
        }

        if (h.llBubble != null) {
            boolean isReplyTarget = mid != null && replyTargetMessageIds.contains(mid);
            h.llBubble.setForeground(isReplyTarget
                    ? ContextCompat.getDrawable(h.itemView.getContext(), R.drawable.bg_reply_target_highlight)
                    : null);
        }
    }

    /** WhatsApp-style INSTANT reaction feedback. Previously a tapped emoji
     *  only became visible after the full round trip: Firebase write → Room
     *  mirror write (background thread) → Paging3 PagingSource invalidation
     *  → new page diffed → PAYLOAD_REACTIONS detected → rebind. That chain
     *  is correct for persistence/sync but is never instant — on a slow
     *  connection or a busy IO executor it could take a very visible beat.
     *  This mutates the already-bound Message object directly and rebinds
     *  right away, so the emoji appears the moment the user taps it. The
     *  Firebase + Room writes still happen (see ChatReactionController) to
     *  keep the reaction persisted and synced to the other device; when
     *  that round trip completes it diffs against what's already showing
     *  and is a no-op. */
    public void applyLocalReaction(String messageId, String uid, String emoji, boolean removing) {
        if (messageId == null || uid == null) return;
        for (int i = 0; i < getItemCount(); i++) {
            Message m = getItem(i);
            if (m == null) continue;
            String id = m.messageId != null ? m.messageId : m.id;
            if (!messageId.equals(id)) continue;
            if (m.reactions == null) m.reactions = new java.util.LinkedHashMap<>();
            if (removing) {
                m.reactions.remove(uid);
            } else {
                m.reactions.put(uid, emoji);
            }
            notifyItemChanged(i, PAYLOAD_REACTIONS);
            break;
        }
    }

    /** Fast-path: rebind ONLY the reactions row. Called both from the full
     *  bindMessage() path AND from the payload-aware onBind for
     *  PAYLOAD_REACTIONS (someone tapped/removed an emoji), so a reactions
     *  update no longer re-runs the whole bindMessage() (no Glide reload,
     *  no Linkify, no new GradientDrawable, no countdown restart) just to
     *  refresh a 1-line TextView. */
    private void bindReactionsOnly(@NonNull VH h, @NonNull Message m) {
        String formatted = formatReactions(m.reactions);
        if (h.canvasView != null) {
            // Canvas path: setReactions()/clearReactions() handles its own
            // requestLayout()+invalidate(), and the tap target is wired
            // via onReactionsClick() in bindCanvasMessage()'s click
            // listener (set once per full bind — no separate listener to
            // reattach here, unlike the legacy llReactions view).
            if (formatted != null) h.canvasView.setReactions(formatted);
            else h.canvasView.clearReactions();
            return;
        }
        if (h.llReactions == null || h.tvReactions == null) return;
        if (formatted != null) {
            h.tvReactions.setText(formatted);
            h.llReactions.setVisibility(View.VISIBLE);
            h.llReactions.setOnClickListener(v -> {
                if (actionListener != null) actionListener.onReactionTap(m);
            });
        } else {
            h.llReactions.setVisibility(View.GONE);
            h.llReactions.setOnClickListener(null);
        }
    }

    /** Shared emoji-counting/formatting logic for both the legacy
     *  tv_reactions TextView and MessageBubbleCanvasView's reaction badge.
     *  Returns null if there are no reactions to show. */
    @Nullable
    private String formatReactions(@Nullable java.util.Map<String, String> rxMap) {
        if (rxMap == null || rxMap.isEmpty()) return null;
        java.util.LinkedHashMap<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (String emoji : rxMap.values()) {
            counts.put(emoji, counts.containsKey(emoji) ? counts.get(emoji) + 1 : 1);
        }
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (java.util.Map.Entry<String, Integer> e : counts.entrySet()) {
            sb.append(e.getKey());
            if (e.getValue() > 1) sb.append(e.getValue());
            sb.append(" ");
            if (++shown >= 4) break; // max 4 distinct emojis shown
        }
        String result = sb.toString().trim();
        return result.isEmpty() ? null : result;
    }

    /**
     * DiffUtil helper — returns true when the readBy map sizes match.
     * A change in readBy count triggers PAYLOAD_READ_BY so only the
     * "Seen by" strip is redrawn instead of a full rebind.
     */
    /**
     * True for a "You reacted 😍 to their story/reel" message: a plain text
     * message whose replyToId points at a status/reel-as-status
     * (StatusReplyBottomSheet/StatusViewerActivity#sendReactionToChat stamp
     * replyToId = "status_"+id; ReelSocialController#sendReaction stamps
     * replyToId = "reel_"+id, same convention ReelStickerReplyHelper
     * already uses for reel DMs) and whose text is exactly one emoji (no
     * caption typed alongside it). Used to trigger the big Instagram-style
     * reaction badge instead of the plain small quote-box text.
     */
    private static boolean isStoryReactionEmojiMessage(Message m) {
        if (m.replyToId == null) return false;
        if (!m.replyToId.startsWith("status_") && !m.replyToId.startsWith("reel_")) return false;
        if (m.type != null && !"text".equals(m.type)) return false;
        return isSingleEmojiText(m.text);
    }

    /** Single-pass, allocation-free check that `raw` is exactly one emoji
     *  (optionally followed by a variation-selector/ZWJ sequence) and
     *  nothing else — mirrors ChatEmojiBurstController#isEmojiOnly's
     *  codepoint-range approach but requires exactly one emoji "unit". */
    private static boolean isSingleEmojiText(String raw) {
        if (raw == null) return false;
        int len = raw.length();
        if (len == 0 || len > 16) return false;
        int i = 0;
        int emojiUnits = 0;
        while (i < len) {
            int cp = raw.codePointAt(i);
            int charCount = Character.charCount(cp);
            if (cp == 0xFE0F || cp == 0x200D) { i += charCount; continue; }
            boolean isEmojiCp = (cp >= 0x1F300 && cp <= 0x1FAFF)
                    || (cp >= 0x2600 && cp <= 0x27BF)
                    || (cp >= 0x1F1E6 && cp <= 0x1F1FF)
                    || (cp >= 0x2190 && cp <= 0x21FF)
                    || (cp >= 0x2B00 && cp <= 0x2BFF);
            if (!isEmojiCp) return false;
            emojiUnits++;
            if (emojiUnits > 1) return false;
            i += charCount;
        }
        return emojiUnits == 1;
    }

    private static boolean readByCountEquals(@NonNull com.callx.app.models.Message a,
                                              @NonNull com.callx.app.models.Message b) {
        int cA = (a.readBy != null) ? a.readBy.size() : 0;
        int cB = (b.readBy != null) ? b.readBy.size() : 0;
        return cA == cB;
    }

    /**
     * Partial-rebind path for PAYLOAD_READ_BY: updates the "Seen by" strip
     * on a sent group-message bubble without triggering a full rebind.
     * Canvas views handle their own read-by rendering; legacy views are
     * updated here if they expose a readable state.
     */
    private void bindSeenByStrip(@NonNull VH h, @NonNull com.callx.app.models.Message m) {
        // Only sent messages show a read-by strip
        if (m.senderId == null || !m.senderId.equals(currentUid)) return;
        // Canvas view renders its own read-by overlay — no extra work needed here
        // The seenByClickListener is wired in bindCanvasMessage / bindMessage
    }

    /** PERF: tiny view-cache for a poll option row, stashed via row.setTag().
     *  See bindPoll() — avoids repeat findViewById() on every rebind of a
     *  recycled poll-option row. */
    private static final class PollOptionRowViews {
        final TextView  tvText;
        final TextView  tvPct;
        final ImageView ivCheck;
        final View      vFill;
        PollOptionRowViews(View row) {
            tvText  = row.findViewById(R.id.tv_poll_option_text);
            tvPct   = row.findViewById(R.id.tv_poll_option_pct);
            ivCheck = row.findViewById(R.id.iv_poll_option_check);
            vFill   = row.findViewById(R.id.v_poll_option_fill);
        }
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView     tvMessage, tvTime, tvSenderName, tvFileName;
        // ── TELEGRAM-STYLE SEND ANIMATION (single-spring, multi-property) ──
        // One physics simulation drives translationY + scale + alpha off a
        // shared 0→1 progress value (see playSendInAnimation) — cheaper
        // than running 3-4 independent SpringAnimations, since each spring
        // is its own Choreographer frame-callback registration. Live ref
        // kept here (not a local) so onViewRecycled() can cancel it the
        // instant this holder is reused for an unrelated message; otherwise
        // a fast-scroll recycle mid-bounce would leave a stray transform
        // bleeding onto whatever gets bound next.
        androidx.dynamicanimation.animation.SpringAnimation sendSpringProgress;
        // Phase 1 Canvas rendering — non-null ONLY for TYPE_CANVAS_SENT/
        // TYPE_CANVAS_RECEIVED holders (see onCreateViewHolder). When set,
        // onBindViewHolder routes to bindCanvasMessage() instead of the
        // normal findViewById-based fields below, all of which stay null
        // for this holder (harmless — a bare custom View has no ids to find).
        com.callx.app.conversation.canvas.MessageBubbleCanvasView canvasView;
        // Bumped on every bindCanvasMessage() call and checked before an
        // async Glide result (image bitmap / reply thumb) is applied — a
        // slow load that resolves after this holder has been recycled and
        // rebound to a different message must NOT paint onto the new one.
        // Unlike Glide's ImageView targets (which auto-cancel via view
        // attachment), the raw CustomTarget<Bitmap> used for canvas binds
        // has no such lifecycle tie-in, so this token is the only thing
        // preventing that race.
        volatile int canvasBindToken = 0;
        // PERF #8b: last hardware-layer type actually applied to canvasView.
        // -1 means "unknown / force re-apply" (fresh holder). Lets
        // bindCanvasMessage() skip the setLayerType() call entirely when
        // this holder's hw-layer need hasn't changed since its last bind —
        // the common case while scrolling (most adjacent bubbles share the
        // same image/video/reactions-or-not shape). Avoids redundant
        // texture-transition churn, which is real per-frame GPU work and,
        // on RecyclerView items that flip type on rapid successive rebinds
        // (e.g. several rows rebinding at once when a new message arrives),
        // was a source of the reaction-badge flicker/junk on send/receive.
        int lastCanvasLayerType = -1;
        // Canvas replacement for item_date_separator.xml's tv_date_label —
        // non-null only for TYPE_DATE_SEPARATOR holders. tvDateHeader below
        // is unused dead-fallback state, same status as tvMessage/etc. are
        // for TYPE_CANVAS_SENT/RECEIVED holders.
        com.callx.app.conversation.canvas.DateSeparatorCanvasView dateSeparatorView;
        TextView     tvDateHeader;   // date separator chip (Today / Yesterday / MMM d) — legacy fallback, unreachable
        ImageView    ivImage;
        // Feature: Voice Caption on Photo — overlay sitting exactly on top
        // of ivImage (same 180x180dp slot in content_frame's FrameLayout),
        // shown only for image messages carrying m.voiceUrl. See
        // isCanvasEligible()'s image branch for why these route here
        // instead of Canvas, and bindMessage()'s "image" case for the bind.
        View      flVoiceOnImage;
        ImageView ivVoicePlayOnImage;
        TextView  tvVoiceDurationOnImage;
        TextView     tvStatus;   // tv_status in both item layouts
        // Manual media download overlay (WhatsApp-style) — received images only.
        // Null in item_message_sent.xml (sender already has the local file).
        android.widget.FrameLayout fl_download_overlay;
        LinearLayout ll_download_pill;
        ImageView    iv_download_icon;
        ProgressBar  pb_download_spinner;
        TextView     tv_download_size;

        // ASYNC PrecomputedTextCompat staleness guard — bumped on every
        // full text-bind AND on recycle (see onViewRecycled). A pending
        // background precompute result is only applied if this still
        // matches the token it captured at dispatch time.
        volatile int textBindToken = 0;

        // WHATSAPP-STYLE HEIGHT CACHE: Track the Message object currently
        // bound to this holder so we can cache its measured height on recycle.
        Message boundMessage = null;

        // PERF: last bubble-background state actually applied to llBubble —
        // -1 means "unknown / force re-apply" (fresh holder, or last bind
        // was a bubbleless media message). Lets bindMessage() skip
        // GradientDrawable.mutate() + setBackground() entirely when this
        // holder's hasReply state hasn't changed since its last bind
        // (the common case while scrolling — sent/received view type never
        // changes for a given recycled holder, only hasReply can flip).
        int lastBubbleReplyState = -1;

        // ── ViewStub refs — each replaced in-place on first inflate ──────────
        // After inflate() the stub removes itself from the view tree;
        // we null the field to signal "already inflated" to ensure*Inflated().
        android.view.ViewStub stubVideo;
        android.view.ViewStub stubAudio;
        android.view.ViewStub stubFile;
        android.view.ViewStub stubPoll;
        android.view.ViewStub stubLinkPreview;
        android.view.ViewStub stubReelShare;
        android.view.ViewStub stubContact;
        android.view.ViewStub stubLocation;

        // ── Heavy view refs — null until their stub is inflated ──────────────
        LinearLayout llAudio, llFile;
        ImageButton  btnPlayPause;
        ImageView    btnDownload;
        // FIX: AudioWaveformView — pre-rendered bitmap waveform, wired for live progress updates
        com.callx.app.chat.ui.AudioWaveformView seekAudio;
        TextView     tvAudioDur;
        // Feature 4: playback speed chip (1x → 1.5x → 2x → 0.5x → 1x)
        TextView     btnAudioSpeed;
        // SwipeReplySystem v1: reply preview views
        LinearLayout llReplyPreview;
        TextView     tvReplySender, tvReplyText;
        ImageView    ivReplyThumb;
        // Reactions row (ll_reactions / tv_reactions in both item layouts)
        LinearLayout llReactions;
        TextView     tvReactions;
        // POLISH: Video — proper FrameLayout with thumbnail + play overlay
        android.widget.FrameLayout flVideo;
        ImageView    ivVideoThumb;
        TextView     tvDuration;
        // POLISH: Link preview card — visible only for text messages with URLs
        LinearLayout llLinkPreview;
        TextView     tvLinkTitle, tvLinkDomain;
        ImageView    ivLinkThumb;
        // ── Reel share card ──
        LinearLayout llReelShare;
        ImageView    ivReelShareThumb;
        ImageView    ivReelShareAvatar;
        TextView     tvReelShareUsername, tvReelShareCaption;
        // ── Contact share card ──
        android.view.View llContact;
        // ── Location share card ──
        android.view.View llLocation;
        // ── Disappearing messages ──
        TextView                  tvExpiry;
        // ── Polls ──
        LinearLayout llPoll, llPollOptions;
        TextView     tvPollQuestion, tvPollTotalVotes, tvPollStatusBadge, tvPollSubtitle;
        ImageView    ivPollIcon;
        // "Currently viewing this message" live dot (per-message granularity)
        View         viewSeenDot;
        // "Currently playing this voice note / video" live badge (sibling
        // of viewSeenDot above, but for playback instead of scroll position)
        TextView     tvListeningBadge;
        // PERF: bubble background container — was looked up via
        // itemView.findViewById() on every single bindMessage() call (i.e.
        // every text/image/audio row, every bind). Cached here instead since
        // it's the hottest path in the adapter.
        View         llBubble;
        // ── Multi-image grouping (WhatsApp-style grid) ──
        LinearLayout llMediaGroup;
        // ── Quick Forward Button ──
        android.widget.ImageButton btnQuickForward;
        // ── Forwarded label ("↪ Forwarded from X") ──
        TextView tvForwarded;

        // PERF: the following are for the rarer system-row layouts
        // (status_seen / reel_seen / call_entry). They used to be looked
        // up with itemView.findViewById() inside the bind*Bubble() methods
        // on every bind. Cached here instead — null on layouts that don't
        // contain them, which is fine since those bind methods are never
        // called for the wrong view type.
        de.hdodenhof.circleimageview.CircleImageView ivStatusSeenAvatar;
        View      flStatusSeenThumb;
        ImageView ivStatusSeenThumb, ivStatusSeenEye;
        TextView  tvStatusSeenName, tvStatusSeenTime;

        de.hdodenhof.circleimageview.CircleImageView ivReelSeenAvatar;
        View      flReelSeenThumb;
        ImageView ivReelSeenThumb, ivReelSeenPlay;
        TextView  tvReelSeenName, tvReelSeenTime;

        TextView  tvCallEntryIcon, tvCallEntryLabel, tvCallEntryTime;
        View      llCallEntryRoot, llCallEntryPill;

        VH(@NonNull View v) {
            super(v);
            tvMessage      = v.findViewById(R.id.tv_message);
            tvTime         = v.findViewById(R.id.tv_time);
            tvSenderName   = v.findViewById(R.id.tv_sender_name);
            tvDateHeader   = null; // removed from item layouts — date chip is now a separate ViewHolder type
            ivImage        = v.findViewById(R.id.iv_image);
            // Feature: Voice Caption on Photo — overlay views on top of ivImage
            flVoiceOnImage        = v.findViewById(R.id.fl_voice_on_image);
            ivVoicePlayOnImage    = v.findViewById(R.id.iv_voice_play_on_image);
            tvVoiceDurationOnImage = v.findViewById(R.id.tv_voice_duration_on_image);
            fl_download_overlay = v.findViewById(R.id.fl_download_overlay);
            ll_download_pill    = v.findViewById(R.id.ll_download_pill);
            iv_download_icon    = v.findViewById(R.id.iv_download_icon);
            pb_download_spinner = v.findViewById(R.id.pb_download_spinner);
            tv_download_size    = v.findViewById(R.id.tv_download_size);
            llMediaGroup   = v.findViewById(R.id.ll_media_group);
            tvStatus       = v.findViewById(R.id.tv_status);
            // ── ViewStub bindings — heavy child layouts inflate only on demand ──
            stubVideo       = v.findViewById(R.id.stub_video);
            stubAudio       = v.findViewById(R.id.stub_audio);
            stubFile        = v.findViewById(R.id.stub_file);
            stubPoll        = v.findViewById(R.id.stub_poll);
            stubLinkPreview = v.findViewById(R.id.stub_link_preview);
            stubReelShare   = v.findViewById(R.id.stub_reel_share);
            stubContact     = v.findViewById(R.id.stub_contact);
            stubLocation    = v.findViewById(R.id.stub_location);
            // Heavy child refs start null; populated by ensure*Inflated() below
            llAudio = null; btnPlayPause = null; seekAudio = null; tvAudioDur = null; btnAudioSpeed = null;
            llFile  = null; tvFileName   = null; btnDownload = null;
            flVideo = null; ivVideoThumb = null; tvDuration  = null;
            llLinkPreview = null; tvLinkTitle = null; tvLinkDomain = null; ivLinkThumb = null;
            llPoll = null; llPollOptions = null; tvPollQuestion = null;
            tvPollTotalVotes = null; tvPollStatusBadge = null; tvPollSubtitle = null;
            ivPollIcon = null;
            // SwipeReplySystem v1
            llReplyPreview = v.findViewById(R.id.ll_reply_preview);
            tvReplySender  = v.findViewById(R.id.tv_reply_sender);
            tvReplyText    = v.findViewById(R.id.tv_reply_text);
            ivReplyThumb   = v.findViewById(R.id.iv_reply_thumb);
            if (ivReplyThumb != null) {
                if (sReplyThumbCornerPx < 0f) {
                    sReplyThumbCornerPx = REPLY_THUMB_CORNER_DP
                            * v.getResources().getDisplayMetrics().density;
                }
                ivReplyThumb.setOutlineProvider(REPLY_THUMB_OUTLINE);
                ivReplyThumb.setClipToOutline(true);
            }
            // Reactions
            llReactions    = v.findViewById(R.id.ll_reactions);
            tvReactions    = v.findViewById(R.id.tv_reactions);
            // Disappearing messages
            tvExpiry       = v.findViewById(R.id.tv_expiry);
            // PERF: these are rarely shown — GONE by default avoids measure cost
            if (tvExpiry != null) tvExpiry.setVisibility(android.view.View.GONE);
            viewSeenDot       = v.findViewById(R.id.view_seen_dot);
            tvListeningBadge  = v.findViewById(R.id.tv_listening_badge);
            llBubble          = v.findViewById(R.id.ll_bubble);
            btnQuickForward   = v.findViewById(R.id.btn_quick_forward);
            tvForwarded       = v.findViewById(R.id.tv_forwarded);

            // System-row layouts (status_seen / reel_seen / call_entry) —
            // these IDs only exist on their respective layouts, so they'll
            // simply resolve to null on the other view types. That's fine:
            // the bind*Bubble() method for one type never runs against a
            // VH inflated from a different type's layout.
            ivStatusSeenAvatar = v.findViewById(R.id.iv_status_seen_avatar);
            flStatusSeenThumb  = v.findViewById(R.id.fl_status_seen_thumb);
            ivStatusSeenThumb  = v.findViewById(R.id.iv_status_seen_thumb);
            ivStatusSeenEye    = v.findViewById(R.id.iv_status_seen_eye);
            tvStatusSeenName   = v.findViewById(R.id.tv_status_seen_name);
            tvStatusSeenTime   = v.findViewById(R.id.tv_status_seen_time);

            ivReelSeenAvatar = v.findViewById(R.id.iv_reel_seen_avatar);
            flReelSeenThumb  = v.findViewById(R.id.fl_reel_seen_thumb);
            ivReelSeenThumb  = v.findViewById(R.id.iv_reel_seen_thumb);
            ivReelSeenPlay   = v.findViewById(R.id.iv_reel_seen_play);
            tvReelSeenName   = v.findViewById(R.id.tv_reel_seen_name);
            tvReelSeenTime   = v.findViewById(R.id.tv_reel_seen_time);

            tvCallEntryIcon  = v.findViewById(R.id.tv_call_entry_icon);
            tvCallEntryLabel = v.findViewById(R.id.tv_call_entry_label);
            tvCallEntryTime  = v.findViewById(R.id.tv_call_entry_time);
            llCallEntryRoot  = v.findViewById(R.id.ll_call_entry_root);
            llCallEntryPill  = v.findViewById(R.id.ll_call_entry_pill);
        }
    }
    // ── Feature 13: View Once bubble binding ─────────────────────────────

    /**
     * Binds the "Waiting to be opened" sender bubble (TYPE_VIEW_ONCE_SENT_WAITING).
     * Shown to the SENDER after sending a view-once message, while receiver hasn't opened it yet.
     * Shows a lock icon + "Waiting to be opened" label.
     * Feature 3: sender can LONG-PRESS to revoke (remove) the message before receiver opens it.
     */
    private void bindViewOnceSentWaiting(RecyclerView.ViewHolder holder, Message m) {
        android.view.View root = holder.itemView;
        android.widget.TextView tvTime = root.findViewById(com.callx.app.chat.R.id.tv_time);
        if (tvTime != null && m.timestamp != null) {
            // PERF: cached formatter — was `new SimpleDateFormat(...)` per bind
            tvTime.setText(formatViewOnceTime(m.timestamp));
        }
        android.view.View bubble = root.findViewById(com.callx.app.chat.R.id.ll_bubble);
        android.view.View tapTarget = bubble != null ? bubble : root;
        tapTarget.setOnClickListener(null); // sender cannot open their own view-once
        // Feature 3: long-press → revoke dialog
        tapTarget.setOnLongClickListener(v -> {
            if (viewOnceRevokeListener != null) {
                viewOnceRevokeListener.onRevokeViewOnce(m);
            }
            return true;
        });
    }

    /**
     * Binds the "View Once" tap bubble for the receiver.
     * Called from onBindViewHolder when viewType == TYPE_VIEW_ONCE_SENT.
     *
     * PERFORMANCE: SimpleViewHolder — no complex binding, just time + tap listener.
     * No Glide load, no media decode here. Media loads only in ViewOnceViewerActivity.
     */
    private void bindViewOnceSent(RecyclerView.ViewHolder holder, Message m) {
        android.view.View root = holder.itemView;

        // Sublabel: show media type hint
        android.widget.TextView tvSub = root.findViewById(com.callx.app.chat.R.id.tv_vo_sublabel);
        if (tvSub != null) {
            String hint = buildTypeHint(m.type);
            tvSub.setText(hint);
        }

        // Time
        android.widget.TextView tvTime = root.findViewById(com.callx.app.chat.R.id.tv_time);
        if (tvTime != null && m.timestamp != null) {
            // PERF: cached formatter — was `new SimpleDateFormat(...)` per bind
            tvTime.setText(formatViewOnceTime(m.timestamp));
        }

        // BUG FIX: the click listener must go on `ll_bubble` (the actual 200dp
        // clickable card with its own ripple foreground), NOT on `root`/itemView.
        // ll_bubble is clickable="true" itself, so it intercepts the touch and
        // a listener on the full-width root never fires — that's why "Tap to
        // open" was doing nothing.
        android.view.View bubble = root.findViewById(com.callx.app.chat.R.id.ll_bubble);
        android.view.View tapTarget = bubble != null ? bubble : root;
        // DEBOUNCE: tag stores last-click timestamp. Prevents spurious double-fires
        // from RecyclerView rebind or rapid multi-tap causing dialog to open twice.
        tapTarget.setOnClickListener(v -> {
            Object lastClick = v.getTag(com.callx.app.chat.R.id.ll_bubble);
            long now = System.currentTimeMillis();
            if (lastClick instanceof Long && now - (Long) lastClick < 800) return;
            v.setTag(com.callx.app.chat.R.id.ll_bubble, now);
            if (viewOnceOpenListener != null) viewOnceOpenListener.onOpenViewOnce(m);
        });
        tapTarget.setOnLongClickListener(null); // no long-press for view-once (no copy/forward)
    }

    /**
     * Binds the post-open expired bubble.
     * Shows "Opened", "Expired", or "Removed" depending on viewOnceState.
     * Called from onBindViewHolder when viewType == TYPE_VIEW_ONCE_EXPIRED.
     */
    private void bindViewOnceExpired(RecyclerView.ViewHolder holder, Message m) {
        android.view.View root = holder.itemView;

        // Message sent time (bottom-right)
        android.widget.TextView tvTime = root.findViewById(com.callx.app.chat.R.id.tv_time);
        if (tvTime != null && m.timestamp != null) {
            // PERF: cached formatter — was `new SimpleDateFormat(...)` per bind
            tvTime.setText(formatViewOnceTime(m.timestamp));
        }

        // Determine label based on state
        // Feature 7: expired timer → "Expired"
        // Feature 8: sender revoked → "Removed"
        // Default: receiver opened → "Opened"
        android.widget.TextView tvLabel = root.findViewById(com.callx.app.chat.R.id.tv_expired_label);
        if (tvLabel != null) {
            if (com.callx.app.conversation.controllers.ChatViewOnceController.isTimerExpired(m)) {
                tvLabel.setText("Expired");
            } else if (com.callx.app.conversation.controllers.ChatViewOnceController.isRevoked(m)) {
                tvLabel.setText("Removed");
            } else {
                tvLabel.setText("Opened");
            }
        }

        // "Opened on" date+time — shown only to sender (openedAt is set by receiver's device)
        // For Expired and Removed states there is no openedAt — hide this line.
        android.widget.TextView tvOpenedAt = root.findViewById(com.callx.app.chat.R.id.tv_opened_at);
        if (tvOpenedAt != null) {
            boolean isOpenedNormally = !com.callx.app.conversation.controllers.ChatViewOnceController.isTimerExpired(m)
                    && !com.callx.app.conversation.controllers.ChatViewOnceController.isRevoked(m);
            if (isOpenedNormally
                    && currentUid != null && currentUid.equals(m.senderId) && m.openedAt != null) {
                // Feature 6: format is "h:mm a" only — time, no date (e.g. "Opened · 3:45 PM")
                // PERF: cached formatter — was `new SimpleDateFormat(...)` per bind
                tvOpenedAt.setText("Opened · " + formatViewOnceTime(m.openedAt));
                tvOpenedAt.setVisibility(android.view.View.VISIBLE);
            } else {
                tvOpenedAt.setVisibility(android.view.View.GONE);
            }
        }

        android.view.View bubble = root.findViewById(com.callx.app.chat.R.id.ll_bubble);
        android.view.View tapTarget = bubble != null ? bubble : root;
        tapTarget.setOnClickListener(null);
        tapTarget.setOnLongClickListener(null);
    }

    private static String buildTypeHint(String type) {
        if (type == null) return "Tap to open";
        switch (type) {
            case "image": return "📷  Photo · Tap to open";
            case "multi_media": return "📷  Photos · Tap to open";
            case "video": return "🎬  Video · Tap to open";
            case "audio": return "🎵  Audio · Tap to open";
            case "file":  return "📄  File · Tap to open";
            case "contact":  return "📇  Contact · Tap to view";
            case "location": return "📍  Location · Tap to open";
            default:      return "Tap to open";
        }
    }

    /** Callback interface — ChatActivity implements this to wire ViewOnceController. */
    public interface ViewOnceOpenListener {
        void onOpenViewOnce(com.callx.app.models.Message message);
    }

    private ViewOnceOpenListener viewOnceOpenListener;

    public void setViewOnceOpenListener(ViewOnceOpenListener l) {
        this.viewOnceOpenListener = l;
    }

    /**
     * Feature 3: Callback interface — ChatActivity implements this to handle revoke confirmation dialog.
     * Called when sender long-presses their pending lock bubble.
     */
    public interface ViewOnceRevokeListener {
        void onRevokeViewOnce(com.callx.app.models.Message message);
    }

    private ViewOnceRevokeListener viewOnceRevokeListener;

    public void setViewOnceRevokeListener(ViewOnceRevokeListener l) {
        this.viewOnceRevokeListener = l;
    }



    public void setOnSeenByClickListener(java.util.function.Consumer<com.callx.app.models.Message> l) {
        this.seenByClickListener = l;
    }

    /**
     * PERF FIX (v341): this used to call notifyDataSetChanged() on every
     * call — a full rebind (destroy+recreate every visible ViewHolder,
     * losing in-flight item animations) triggered whenever GroupChatActivity
     * syncs member photos.
     *
     * Traced where `memberPhotos` is actually read: nowhere. bindSeenByStrip()
     * below is a no-op stub ("canvas view renders its own read-by overlay —
     * no extra work needed here") — the seen-by-strip avatar rendering this
     * field's javadoc/call-site comment ("Keep pagingAdapter in sync so
     * avatar circles in 'Seen by' strip stay fresh", see GroupChatActivity)
     * describes was never actually wired up. So the notify was forcing a
     * full-list rebind for a field the bind path never consumes — zero
     * visual effect, all of the cost.
     *
     * The field is still stored (and the public setter kept, so the
     * existing GroupChatActivity call site keeps compiling) in case a
     * future pass finishes that seen-by-avatar wiring — but that work
     * should call notifyItemChanged(pos, PAYLOAD) targeted at just the
     * affected sent-message rows once it reads memberPhotos in bind, not
     * reintroduce a blanket notifyDataSetChanged() here.
     */
    public void setMemberPhotos(java.util.Map<String, String> photos) {
        this.memberPhotos = photos;
    }
}
