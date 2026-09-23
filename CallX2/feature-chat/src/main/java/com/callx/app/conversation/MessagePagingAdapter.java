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
import com.bumptech.glide.Priority;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.resource.bitmap.Downsampler;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.chat.R;

import com.callx.app.models.Message;
import com.callx.app.audio.GlobalVoicePlaybackManager;
import com.callx.app.utils.FileUtils;
import com.callx.app.utils.MediaCache;
import com.callx.app.utils.MediaAutoDownloadPolicy;
import com.callx.app.utils.MediaSaveHelper;
import com.callx.app.utils.ThumbHashPlaceholder;
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
    // Reusable CustomTarget slots. A RecyclerView row can be rebound many
    // times during a fling; allocating a new target for every cache miss
    // leaves the old decode/network request alive until Glide finishes it.
    // Slots are owned by the holder, cancelled before re-arming, and cancelled
    // again when the holder enters the recycled pool.
    private static final int TARGET_STATUS_SEEN       = 0;
    private static final int TARGET_REEL_SEEN         = 1;
    private static final int TARGET_CANVAS_PRIMARY    = 2;
    private static final int TARGET_CANVAS_SEEN       = 3;
    private static final int TARGET_REEL_THUMB        = 4;
    private static final int TARGET_REEL_AVATAR       = 5;
    private static final int TARGET_AUTO_IMAGE        = 6;
    private static final int TARGET_REPLY             = 7;
    private static final int TARGET_MEDIA_GRID_BASE   = 8;
    private static final int TARGET_GROUP_DOWNLOAD_BASE = 17;
    private static final int TARGET_SLOT_COUNT        = 26;

    private interface BitmapReadyCallback {
        void onReady(@NonNull Bitmap resource);
    }

    private interface BitmapClearedCallback {
        void onCleared();
    }

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
        // v425 PERF: this used to allocate a brand-new capturing lambda on
        // EVERY image/caption/album bind (every visible row on chat open and
        // on every scroll rebind). The listener is now built ONCE per VH and
        // reads the message id + owning adapter at CLICK time (taps are rare,
        // binds are per-frame) — same pattern as the per-VH voice-badge
        // listener in onCreateViewHolder. It deliberately does not capture
        // `this`: the adapter is read from h.canvasListenerOwner so a pooled
        // holder reused by a different chat's adapter (shared RecycledViewPool)
        // never fires the previous chat's callback.
        h.readMoreMsgId = msgId;
        if (h.readMoreListenerCached == null) {
            h.readMoreListenerCached = nowExpanded -> {
                final MessagePagingAdapter ad = h.canvasListenerOwner;
                if (ad == null) return;
                final String id = h.readMoreMsgId;
                if (nowExpanded) ad.expandedMessageIds.add(id);
                else             ad.expandedMessageIds.remove(id);

                int pos = h.getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return;

                final RecyclerView rv = (h.itemView.getParent() instanceof RecyclerView)
                        ? (RecyclerView) h.itemView.getParent() : null;
                final int savedTop = (rv != null) ? h.itemView.getTop() : 0;

                ad.notifyItemChanged(pos);

                if (rv != null) {
                    rv.post(() -> {
                        RecyclerView.LayoutManager lm = rv.getLayoutManager();
                        if (lm instanceof androidx.recyclerview.widget.LinearLayoutManager) {
                            ((androidx.recyclerview.widget.LinearLayoutManager) lm)
                                    .scrollToPositionWithOffset(pos, savedTop);
                        }
                    });
                }
            };
        }
        cv.setReadMoreListener(h.readMoreListenerCached);
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
                if (!same && com.callx.app.core.BuildConfig.DEBUG) {
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

    // PERF (group sender decoration): the sender NAME (shown on the first
    // bubble of a sender run) and the 20dp AVATAR (shown on the last) are
    // decided by a row's NEIGHBORS and by the live groupMemberPhotos map —
    // neither is part of the Message row itself, so DIFF above can't see
    // them change (a neighbor being inserted/removed leaves the row's own
    // content "the same"). This targeted payload is the equivalent fast
    // path: bindGroupSenderOnly() re-evaluates name + avatar for just that
    // row — no text/media/reply rebind, no Glide reload of the bubble's
    // other content. Fired by onMemberPhotosChanged() (photo arrived /
    // changed) and by the AdapterDataObserver (neighbor inserted/removed).
    static final String PAYLOAD_GROUP_SENDER = "group_sender";

    // PERF: RGB_565 for thumbnail-sized images — half the memory of ARGB_8888.
    // Thumbnails (avatars, video covers, reply previews, status/reel chips) have
    // no alpha channel, so the extra byte per pixel in ARGB_8888 is pure waste.
    // Full-size image loads (720×720) keep ARGB_8888 for quality.
    //
    // PERF (hardware bitmaps, one step past RGB_565 — matches MediaViewer's
    // GalleryPagerAdapter fix): ALLOW_HARDWARE_CONFIG lets Glide hand back a
    // GPU-backed HARDWARE Bitmap (API 26+) for these thumbnails instead of a
    // Java-heap one — during a fast chat scroll this is many small bubble
    // thumbnails binding per second, so skipping the heap allocation +
    // CPU-to-GPU upload per bubble adds up. Safe here: these bubble
    // thumbnails are only ever drawn (ImageView/Canvas draw), never read
    // back pixel-by-pixel, so Glide can use hardware config wherever the
    // device/API supports it and falls back to RGB_565 automatically
    // elsewhere (pre-O, or the rare transformation that needs software
    // pixels).
    private static final RequestOptions THUMB_RGB565 = new RequestOptions()
            .format(DecodeFormat.PREFER_RGB_565)
            .set(Downsampler.ALLOW_HARDWARE_CONFIG, true)
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
    // The general decoded pools used to be keyed by raw URL alone. The SAME
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

    // ── FIX (decode reel-share thumbnail at its actual render size) ──────
    // Root cause: the reel-share card's thumbnail was decoded at a
    // hardcoded 330x474px regardless of device density — that pair isn't
    // even this card's real 9:16 aspect (165dp x 293dp, see
    // MessageBubbleCanvasView.REEL_CARD_WIDTH_DP/HEIGHT_DP), so on a
    // high-density phone (xxxhdpi, density=4 → card renders at 660x1173px)
    // it under-decoded and looked soft, while on a low-density device it
    // over-decoded and wasted memory/bandwidth for pixels never shown.
    // This computes the card's real density-scaled pixel size once and
    // decodes/caches at exactly that — sharp on every density, never
    // bigger than what's actually drawn.
    private static int[] reelCardPx(android.content.Context ctx) {
        float density = ctx.getResources().getDisplayMetrics().density;
        int w = Math.round(com.callx.app.conversation.canvas.MessageBubbleCanvasView.REEL_CARD_WIDTH_DP * density);
        int h = Math.round(com.callx.app.conversation.canvas.MessageBubbleCanvasView.REEL_CARD_HEIGHT_DP * density);
        return new int[]{w, h};
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
    // same trust assumption the decoded pools make) — so only the
    // positive (hit) result is cached; a not-yet-downloaded URL keeps
    // checking disk each bind exactly as before, which is correct since
    // that state can change at any time from a background download.
    private static final android.util.LruCache<String, java.io.File> CACHED_FILE_CHECK =
            new android.util.LruCache<>(300);

    // v426: MediaCache bumps generation() whenever it deletes cache files
    // (invalidate / clearAll / LRU eviction). Dropping the positive memo then
    // is what makes it safe to use this on every bind, not just GIF/sticker.
    private static volatile int cachedFileGenSeen = -1;

    private static java.io.File getCachedFileFast(android.content.Context ctx, String url) {
        if (url == null || url.isEmpty()) return null;
        final int gen = com.callx.app.utils.MediaCache.generation();
        if (gen != cachedFileGenSeen) {
            CACHED_FILE_CHECK.evictAll();
            cachedFileGenSeen = gen;
        }
        java.io.File hit = CACHED_FILE_CHECK.get(url);
        if (hit != null) return hit; // trust — no repeat disk stat
        java.io.File f = com.callx.app.utils.MediaCache.getCached(ctx, url);
        if (f != null) CACHED_FILE_CHECK.put(url, f);
        return f;
    }

    // ── PERF #1: purpose-partitioned decoded-Bitmap pools ─────────────────
    // Independent of Glide's disk cache: these pools keep already-decoded
    // Bitmap objects in RAM so a scroll-back to a message never triggers a
    // re-decode.  The old implementation put every chat bitmap into one
    // maxMemory/8 LRU.  A 3x3 media grid or a GIF could therefore evict
    // reply/status/location thumbnails that are much more likely to be
    // rebound immediately.
    //
    // Every pool is byte-sized (not entry-sized), synchronized by LruCache,
    // and receives a fixed slice of the former general-pool budget.  This
    // keeps the total bounded while preventing unrelated media classes from
    // evicting one another.  The larger media pool intentionally gets the
    // largest slice; tiny UI thumbnails get smaller, protected pools.
    private static android.util.LruCache<String, android.graphics.Bitmap> newBitmapPool(int heapDivisor) {
        int maxMemKiB = (int) (Runtime.getRuntime().maxMemory() / 1024L);
        return new android.util.LruCache<String, android.graphics.Bitmap>(
                Math.max(256, maxMemKiB / heapDivisor)) {
            @Override
            protected int sizeOf(String key, android.graphics.Bitmap value) {
                return value == null ? 0 : Math.max(1, value.getByteCount() / 1024);
            }
        };
    }

    // Main image/video/reel media surfaces, including local-file and
    // full-media thumbnail decodes.
    private static final android.util.LruCache<String, android.graphics.Bitmap> MEDIA_BITMAP_CACHE =
            newBitmapPool(16);
    // Status-seen and reel-seen thumbnails share a visual slot and workload,
    // but are isolated from large media and grids.
    private static final android.util.LruCache<String, android.graphics.Bitmap> SEEN_THUMB_BITMAP_CACHE =
            newBitmapPool(64);
    // Each grid cell is small, but one bind can request up to nine cells.
    private static final android.util.LruCache<String, android.graphics.Bitmap> MEDIA_GRID_BITMAP_CACHE =
            newBitmapPool(64);
    private static final android.util.LruCache<String, android.graphics.Bitmap> GIF_BITMAP_CACHE =
            newBitmapPool(64);
    private static final android.util.LruCache<String, android.graphics.Bitmap> STICKER_BITMAP_CACHE =
            newBitmapPool(128);
    private static final android.util.LruCache<String, android.graphics.Bitmap> LOCATION_BITMAP_CACHE =
            newBitmapPool(128);
    private static final android.util.LruCache<String, android.graphics.Bitmap> REPLY_THUMB_BITMAP_CACHE =
            newBitmapPool(128);

    // ── PERF ADV: dedicated link-preview thumbnail pool ────────────────────
    // Link-preview thumbnails used to share the general media pool with every
    // other thumbnail type in this adapter (GIFs, stickers, reel/status-seen
    // thumbs, media-grid cells, reply thumbs...). In a media-heavy chat that
    // shared LRU budget gets churned by bigger/more numerous media bitmaps,
    // evicting the much smaller, much rarer link-preview thumbs and forcing
    // a re-decode flash every time a link-heavy chat is scrolled back into.
    // Small dedicated budget (1/32 heap, vs 1/8 for the general pool) so
    // link previews can't be evicted by unrelated media traffic and can't
    // themselves crowd out the general pool.
    private static final android.util.LruCache<String, android.graphics.Bitmap> LINK_PREVIEW_BITMAP_CACHE;
    static {
        int maxMem = (int) (Runtime.getRuntime().maxMemory() / 1024); // KiB
        LINK_PREVIEW_BITMAP_CACHE = new android.util.LruCache<String, android.graphics.Bitmap>(maxMem / 32) {
            @Override
            protected int sizeOf(String key, android.graphics.Bitmap value) {
                return value.getByteCount() / 1024; // KiB
            }
        };
    }

    // ── WhatsApp-level: off-main-thread decode for embedded base64 thumbnails ──
    // PERF FIX (v_jank1): Base64.decode() + BitmapFactory.decodeByteArray() for
    // the embedded-thumbnail fast path (status-seen, reel-seen, reel-share,
    // reply-thumb bubbles) used to run SYNCHRONOUSLY inside bindCanvasMessage()/
    // onBindViewHolder() on every cache miss — i.e. the first time each such
    // row scrolls on screen, or after a process-cold LruCache eviction. A JPEG
    // decode on the main thread during a fling is exactly the kind of frame
    // it takes to jank a scroll. Decoded-pool hits still resolve
    // synchronously (no thread hop needed for the common repeat-bind case);
    // only a genuine miss goes to this single-thread executor, with the
    // result posted back to the main thread. Callers are responsible for
    // their own staleness check inside the callback (h.canvasBindToken for
    // canvas-bound rows, h.getBindingAdapterPosition()/tag compare for plain
    // ViewHolder rows) before touching a view, same convention already used
    // by every other async bitmap path in this file.
    private static final java.util.concurrent.ExecutorService B64_DECODE_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final android.os.Handler B64_DECODE_MAIN_HANDLER =
            new android.os.Handler(android.os.Looper.getMainLooper());

    // ── Lazy video first-frame load ──────────────────────────────────────
    // The ThumbHash placeholder (m.blurHash, ~1ms local decode) shows the
    // instant the bubble binds. The real poster frame (vThumbUrl, a
    // 300-480px extracted video frame — see VideoCompressor.makeThumbnail)
    // used to start fetching in the very same bind pass, right on top of
    // the placeholder. Delaying that fetch by a short beat gives the
    // placeholder a moment to actually be the thing the user sees first
    // during a fast scroll, instead of the network/decrypt fetch firing for
    // every video bubble that merely flies past — same "small thing now,
    // real thing lazily after" shape as the image path's ThumbHash-before-
    // full-image flow. A decoded-pool hit (already-decoded,
    // zero network cost) is intentionally NOT delayed — only the genuine
    // fetch-miss path below is.
    private static final long VIDEO_FRAME_LAZY_DELAY_MS = 220L;
    private static final android.os.Handler VIDEO_FRAME_LAZY_HANDLER =
            new android.os.Handler(android.os.Looper.getMainLooper());

    /** Callback for {@link #decodeB64ThumbAsync}; bitmap is null on decode failure. */
    private interface B64ThumbCallback {
        void onDecoded(android.graphics.Bitmap bitmap);
    }

    /**
     * Cache-hit fast path resolves synchronously (no thread hop). On a miss,
     * decodes off the main thread on a single background executor and posts
     * the result (caching it first) back via the main-thread handler. Never
     * blocks the calling thread.
     */
    private static void decodeB64ThumbAsync(
            String base64,
            String poolKey,
            android.util.LruCache<String, android.graphics.Bitmap> cache,
            B64ThumbCallback cb) {
        android.graphics.Bitmap hit = cache.get(poolKey);
        if (hit != null && !hit.isRecycled()) {
            cb.onDecoded(hit);
            return;
        }
        B64_DECODE_EXECUTOR.execute(() -> {
            android.graphics.Bitmap decoded = null;
            try {
                byte[] bytes = android.util.Base64.decode(base64, android.util.Base64.NO_WRAP);
                decoded = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            } catch (Exception ignored) { /* cb receives null below */ }
            final android.graphics.Bitmap result = decoded;
            if (result != null) cache.put(poolKey, result);
            B64_DECODE_MAIN_HANDLER.post(() -> cb.onDecoded(result));
        });
    }

    // ── DASHBOARD WIRING (Settings → Storage & Cache / CacheStatsActivity) ──
    // That screen only reads com.callx.app.cache.CacheManager's MemoryCache/
    // DiskCache — a completely separate tier from the decoded bitmap pools and
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
        // PERF (low-end tier): a RAM-constrained device (see DeviceTier)
        // decodes+holds the same bubble thumbnails at a smaller target —
        // fewer pixels to decode, less native-heap/GC pressure while
        // scrolling, at a still-perfectly-readable chat-bubble size.
        boolean lowRam = com.callx.app.utils.DeviceTier.isLowRamDevice(ctx);
        float marginMultiplier = lowRam ? 0.75f : 1.10f; // 260dp + margin (or shrink, on low-RAM)
        int floorPx = lowRam ? 240 : 320;
        int computed = (int) Math.min(
                260f * dm.density * marginMultiplier,
                dm.widthPixels * 0.80f);             // cap at 80% screen width
        sThumbPx = Math.max(computed, floorPx);
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

    /** ChatAvatarBinder.bindBitmap()'s tier for the seen-bubble's ~36dp
     *  avatar -- default TIER_INLINE (24dp) would under-resolve this. */
    private static final com.callx.app.utils.AvatarSizeTier SEEN_AVATAR_TIER =
            com.callx.app.utils.AvatarSizeTier.forViewSizeDp(36);

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
    // PERF ADV: media-group grid cells (multi_media bubbles) decoded a
    // hardcoded .override(240, 240) for EVERY cell regardless of the actual
    // on-screen slot — same class of bug thumbPx()/gifStickerPx()/
    // seenThumbPx() above already fixed for other bubble types, just not
    // yet applied here. The grid's real per-cell size varies a lot by item
    // count (see MediaGroupLayoutHelper / MessageBubbleCanvasView's GROUP_*
    // dp constants):
    //   - 2 items (pair)      → 118dp square
    //   - 3 items             → top cell 240×140dp, bottom two 116dp square
    //   - 4 items (2×2)       → 118dp square
    //   - 5+ items (3×3 grid) → only 78dp square
    // A flat 240px decode is ~2-3x oversized for the dense 3×3 case (78dp)
    // — wasted native-heap memory and slower decode on every image in
    // every dense grid — while under-sampling the bigger pair/3-item/2×2
    // slots on high-density phones (blurrier than it should be).
    // groupCellPx() computes the real px target per (total, index) — the
    // 3-item layout is the one case where index matters, since its top
    // cell isn't the same size as the two below it.
    // NOTE: mirrors MessageBubbleCanvasView's package-private GROUP_PAIR_CELL /
    // GROUP_THREE_TOP_W / GROUP_THREE_TOP_H / GROUP_THREE_BOT / GROUP_GRID2_CELL /
    // GROUP_GRID3_CELL dp constants — duplicated here (same pattern as the
    // SEEN_*_DP_MIRROR constants above) rather than widening their
    // visibility just for this. Keep in sync if those layout constants change.
    private static final float GROUP_PAIR_CELL_DP_MIRROR   = 118f;
    private static final float GROUP_THREE_TOP_W_DP_MIRROR = 240f;
    private static final float GROUP_THREE_TOP_H_DP_MIRROR = 140f;
    private static final float GROUP_THREE_BOT_DP_MIRROR   = 116f;
    private static final float GROUP_GRID2_CELL_DP_MIRROR  = 118f;
    private static final float GROUP_GRID3_CELL_DP_MIRROR  = 78f;

    /** Real px size for one media-group grid cell, given the group's total
     *  item count and this cell's index. Same 15% headroom margin as
     *  thumbPx()/gifStickerPx()/seenThumbPx() above, floored so a very
     *  low-density device never decodes below a sane minimum. */
    static int[] groupCellPx(android.content.Context ctx, int total, int index) {
        float density = ctx.getResources().getDisplayMetrics().density;
        float wDp, hDp;
        if (total == 2) {
            wDp = hDp = GROUP_PAIR_CELL_DP_MIRROR;
        } else if (total == 3) {
            if (index == 0) {
                wDp = GROUP_THREE_TOP_W_DP_MIRROR;
                hDp = GROUP_THREE_TOP_H_DP_MIRROR;
            } else {
                wDp = hDp = GROUP_THREE_BOT_DP_MIRROR;
            }
        } else if (total == 4) {
            wDp = hDp = GROUP_GRID2_CELL_DP_MIRROR;
        } else {
            wDp = hDp = GROUP_GRID3_CELL_DP_MIRROR; // 5+ items → dense 3×3
        }
        int w = Math.max((int) (wDp * density * 1.15f), 60);
        int h = Math.max((int) (hDp * density * 1.15f), 60);
        return new int[]{w, h};
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
    /**
     * Selection-only repaint. The selection state changes alpha/background on
     * the holder, not message content, so a full bind would unnecessarily
     * reload media, relink text and rebuild canvas state.
     */
    static final String PAYLOAD_SELECTION = "selection";
    /**
     * Theme-only repaint. Canvas holders can refresh their paints in place;
     * legacy holders use the existing full bind as a correctness fallback for
     * their many theme-dependent child views.
     */
    static final String PAYLOAD_THEME = "theme";
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
    // Bounded because content:// paths can be unique for every attachment
    // opened in a long-lived process. android.util.LruCache synchronizes its
    // get/put operations, so the background availability worker and the main
    // thread can share it without retaining an unbounded path set.
    private static final android.util.LruCache<String, Boolean> LOCAL_AVAIL_CACHE =
            new android.util.LruCache<>(256);
    private static final java.util.concurrent.ExecutorService LOCAL_AVAIL_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    // Avoid queueing the same ContentResolver probe once per recycled bind
    // while the first probe is still in flight.
    private static final java.util.Set<String> LOCAL_AVAIL_IN_FLIGHT =
            java.util.Collections.newSetFromMap(
                    new java.util.concurrent.ConcurrentHashMap<>());

    // Telegram-style chat-wide media gallery (see showMediaActionSheet's
    // VIEW case) — builds the swipeable all-media list off the UI thread
    // right as the viewer is about to open. Separate single-thread pool
    // from LOCAL_AVAIL_EXECUTOR above so a slow local-file-availability
    // check never blocks a gallery open (or vice versa).
    private static final java.util.concurrent.ExecutorService GALLERY_BUILD_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    /**
     * #3 Precompute on chat open (silent warm-up): called by ChatActivity
     * right after chatId is known, so the chat-wide gallery list is
     * already sitting in ChatMediaGalleryBuilder's cache by the time the
     * user actually taps a photo/video — tap-to-open then hits the same
     * peek() fast path openChatMediaViewer() uses, with zero extra
     * latency. Runs on the same single-thread GALLERY_BUILD_EXECUTOR as
     * the real tap path (so a warm-up in flight and a genuine tap never
     * race each other onto two DB reads at once — the tap's own peek()
     * afterwards will simply see the now-populated cache), and skips
     * itself entirely if the chat is already cached and fresh.
     */
    public static void warmUpChatMediaGallery(Context ctx, @Nullable String chatId) {
        if (chatId == null || ctx == null) return;
        GALLERY_BUILD_EXECUTOR.execute(() -> {
            try {
                com.callx.app.db.dao.MessageDao dao =
                        com.callx.app.db.AppDatabase.getInstance(ctx).messageDao();
                com.callx.app.db.ChatMediaFreshness freshness = dao.getChatMediaFreshness(chatId);
                // Already cached & fresh — nothing to warm up.
                if (com.callx.app.utils.ChatMediaGalleryBuilder.peek(chatId, freshness, null, -1) != null) {
                    return;
                }
                java.util.List<com.callx.app.db.ChatMediaRow> rows = dao.getChatMediaRows(chatId);
                com.callx.app.utils.ChatMediaGalleryBuilder.resolve(chatId, rows, freshness, null, -1);
            } catch (Exception ignored) {
                // Silent by design — a failed warm-up just means the next
                // real tap falls back to its own normal cache-miss path.
            }
        });
    }

    /** Tiny local stand-in for java.util.function.Consumer&lt;Intent&gt; (API 24+) — this module's minSdk is 23. */
    private interface MediaViewerExtrasAttacher {
        void accept(android.content.Intent intent);
    }
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
        if (!LOCAL_AVAIL_IN_FLIGHT.add(mediaLocalPath)) return null;
        final Context appCtx = ctx.getApplicationContext();
        LOCAL_AVAIL_EXECUTOR.execute(() -> {
            try {
                boolean avail = com.callx.app.utils.LocalMediaAvailability.isAvailable(
                        appCtx, mediaLocalPath);
                LOCAL_AVAIL_CACHE.put(mediaLocalPath, avail);
                if (avail && messageId != null) {
                    // Refresh only the affected row. The id-position index
                    // avoids walking the complete loaded history on every
                    // async local-file result.
                    LOCAL_AVAIL_MAIN_HANDLER.post(() -> {
                        int position = findMessagePositionById(messageId);
                        if (position != RecyclerView.NO_POSITION) {
                            notifyItemChanged(position);
                        }
                    });
                }
            } finally {
                LOCAL_AVAIL_IN_FLIGHT.remove(mediaLocalPath);
            }
        });
        return null; // not known yet this frame — caller falls back to fullUrl
    }

    // PERF ADV (v375): the three media-key-envelope decrypt call sites below
    // (image auto-download, video-thumb decrypt, audio warm-download) used
    // to call MediaE2ECrypto.decrypt*() SYNCHRONOUSLY inline in
    // bindCanvasMessage() — i.e. on the main thread, inside onBindViewHolder.
    // On a cache miss that's a full Double-Ratchet decrypt; even on a cache
    // HIT it's still a `synchronized(lockFor(partnerUid))` lock acquisition
    // plus an EncryptedSharedPreferences (disk-backed, AES-encrypted) read —
    // real disk I/O, on the main thread, once per visible image/video/audio
    // bubble that hasn't been auto-downloaded yet. Same root cause class as
    // the v150 fix for incoming message text.
    // Routed onto E2eeDecryptExecutor rather than a fresh/shared generic pool
    // deliberately: E2EEncryptionManager#decrypt() for a media-key envelope
    // walks the exact same per-partner ratchet as message-text decrypt (same
    // `decrypt(..., partnerUid, cacheKey)` method, same `lockFor(partnerUid)`
    // lock). Before this fix, message-text decrypts ran on E2eeDecryptExecutor
    // (v150) while these media-key decrypts ran on the main thread — two
    // different threads racing to acquire the same per-partner lock, with no
    // guarantee the ratchet advanced in wire order. Moving media-key decrypt
    // onto the SAME dedicated FIFO bucket for that partner doesn't just get
    // this work off the main thread, it also closes that pre-existing
    // two-thread race.
    // ULTRA-OPT (v2): E2eeDecryptExecutor.execute(Runnable) used to be one
    // single global thread for every partner. All three call sites below
    // pass `senderId` now — the message's sender is always the partner here
    // (each call is guarded by `if (sent ...) return;` above, so this only
    // ever runs for RECEIVED messages) — so each partner's media-key
    // decrypts get their own FIFO bucket instead of queueing behind every
    // other open/background conversation's decrypt work.
    private static final android.os.Handler MEDIA_KEY_MAIN_HANDLER =
            new android.os.Handler(android.os.Looper.getMainLooper());

    private interface FullKeyEnvelopeCallback {
        void onResolved(byte[] key, byte[] digest);
    }

    /** Async counterpart of {@code MediaE2ECrypto.decryptEnvelopeForMessage()}
     *  followed by {@code .fullKey()}/{@code .fullDigest}. `token` must be the
     *  `h.canvasBindToken` snapshot taken at the top of bindCanvasMessage —
     *  the callback is dropped (never touches `h`/`cv`) if the holder was
     *  recycled or rebound to a different message before the decrypt lands. */
    private void resolveFullMediaKeyAsync(Context ctx, Message m, boolean sent, VH h, int token,
            FullKeyEnvelopeCallback cb, @Nullable Runnable onStale) {
        if (sent || m.mediaKeyEnc == null) { cb.onResolved(null, null); return; }
        final String encKey = m.mediaKeyEnc;
        final String senderId = m.senderId;
        final String msgId = m.messageId != null ? m.messageId : m.id;
        com.callx.app.utils.E2eeDecryptExecutor.execute(senderId, () -> {
            com.callx.app.utils.MediaE2ECrypto.KeyEnvelope env =
                    com.callx.app.utils.MediaE2ECrypto.decryptEnvelopeForMessage(ctx, encKey, senderId, msgId);
            byte[] key = env != null ? env.fullKey() : null;
            byte[] digest = env != null ? env.fullDigest : null;
            MEDIA_KEY_MAIN_HANDLER.post(() -> {
                if (h.canvasBindToken != token) { // recycled/rebound meanwhile
                    // v426 FIX: the caller marked this url as "downloading"
                    // BEFORE this async hop. Dropping the callback silently
                    // left it in downloadingMediaUrls forever, so the next
                    // bind of that message showed a spinner with no download
                    // behind it. Let the caller undo its marker.
                    if (onStale != null) onStale.run();
                    return;
                }
                cb.onResolved(key, digest);
            });
        });
    }

    /** Async counterpart of {@code MediaE2ECrypto.decryptThumbKeyOnly()}. */
    private void resolveThumbMediaKeyAsync(Context ctx, Message m, boolean sent, VH h, int token,
            java.util.function.Consumer<byte[]> cb) {
        if (sent || m.mediaKeyEnc == null) { cb.accept(null); return; }
        final String encKey = m.mediaKeyEnc;
        final String senderId = m.senderId;
        final String msgId = m.messageId != null ? m.messageId : m.id;
        com.callx.app.utils.E2eeDecryptExecutor.execute(senderId, () -> {
            byte[] key = com.callx.app.utils.MediaE2ECrypto.decryptThumbKeyOnly(ctx, encKey, senderId, msgId);
            MEDIA_KEY_MAIN_HANDLER.post(() -> {
                if (h.canvasBindToken != token) return;
                cb.accept(key);
            });
        });
    }

    /** ULTRA-FAST THUMBNAIL FIX (video BlurHash parity): video's BlurHash
     *  string travels inside the same encrypted key envelope as the thumb
     *  key (see ChatMediaController#doStartVideoUploadWork) rather than in
     *  the clear on m.blurHash, exactly like the image path already does.
     *  Reading it therefore needs the same off-main-thread ratchet decrypt
     *  as {@link #resolveThumbMediaKeyAsync} — never decrypt envelopes
     *  synchronously on the main thread (v375 rule, see that method's
     *  javadoc). Plaintext (non-E2E) videos skip this entirely since their
     *  BlurHash is already sitting in m.blurHash in the clear. */
    private void resolveVideoBlurHashAsync(Context ctx, Message m, boolean sent, VH h, int token,
            java.util.function.Consumer<String> cb) {
        if (sent || m.mediaKeyEnc == null) { cb.accept(null); return; }
        final String encKey = m.mediaKeyEnc;
        final String senderId = m.senderId;
        final String msgId = m.messageId != null ? m.messageId : m.id;
        com.callx.app.utils.E2eeDecryptExecutor.execute(senderId, () -> {
            com.callx.app.utils.MediaE2ECrypto.KeyEnvelope env =
                    com.callx.app.utils.MediaE2ECrypto.decryptEnvelopeForMessage(ctx, encKey, senderId, msgId);
            String hash = env != null ? env.blurHash : null;
            MEDIA_KEY_MAIN_HANDLER.post(() -> {
                if (h.canvasBindToken != token) return;
                cb.accept(hash);
            });
        });
    }

    /** Async counterpart of {@code MediaE2ECrypto.decryptKeyOnly()}. */
    private void resolveFullMediaKeyOnlyAsync(Context ctx, Message m, boolean sent, VH h, int token,
            java.util.function.Consumer<byte[]> cb) {
        if (sent || m.mediaKeyEnc == null) { cb.accept(null); return; }
        final String encKey = m.mediaKeyEnc;
        final String senderId = m.senderId;
        final String msgId = m.messageId != null ? m.messageId : m.id;
        com.callx.app.utils.E2eeDecryptExecutor.execute(senderId, () -> {
            byte[] key = com.callx.app.utils.MediaE2ECrypto.decryptKeyOnly(ctx, encKey, senderId, msgId);
            MEDIA_KEY_MAIN_HANDLER.post(() -> {
                if (h.canvasBindToken != token) return;
                cb.accept(key);
            });
        });
    }

    // PERF ADV: header-only (no pixel decode) aspect-ratio resolution for
    // images with no known width/height metadata — see the call site in
    // the isImage branch above for the full rationale. Same
    // single-thread-executor + main-Handler + result-cache shape as
    // LOCAL_AVAIL_EXECUTOR just above, kept as its own instance since it's
    // a different, unrelated background task (image header parse vs. a
    // file-existence check).
    private static final java.util.concurrent.ConcurrentHashMap<String, Float> ASPECT_BOUNDS_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ExecutorService ASPECT_BOUNDS_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final android.os.Handler ASPECT_BOUNDS_MAIN_HANDLER =
            new android.os.Handler(android.os.Looper.getMainLooper());

    /**
     * Resolves (or reuses a cached) aspect ratio for a locally-available
     * image via a header-only dimension read, then applies it to `cv`
     * early — well before the full Glide thumbnail decode would otherwise
     * report it — so the bubble can relayout to its correct proportions
     * immediately instead of sitting in the square placeholder until the
     * full decode finishes. Never touches the main thread with disk I/O;
     * `h`/`myToken` guard against the holder having been recycled/rebound
     * to a different message while the read was in flight.
     */
    private void resolveAspectRatioEarly(Context ctx, Object loadSrc, String aspectKey,
                                          com.callx.app.conversation.canvas.MessageBubbleCanvasView cv,
                                          VH h, int myToken) {
        if (loadSrc == null || aspectKey == null) return;
        Float cachedBounds = ASPECT_BOUNDS_CACHE.get(aspectKey);
        if (cachedBounds != null) {
            cv.applyKnownAspectRatioEarly(aspectKey, cachedBounds);
            return;
        }
        final Context appCtx = ctx.getApplicationContext();
        ASPECT_BOUNDS_EXECUTOR.execute(() -> {
            int[] wh = decodeBoundsOnly(appCtx, loadSrc);
            if (wh == null || wh[0] <= 0 || wh[1] <= 0) return;
            float ratio = (float) wh[0] / wh[1];
            ASPECT_BOUNDS_CACHE.put(aspectKey, ratio);
            ASPECT_BOUNDS_MAIN_HANDLER.post(() -> {
                if (h.canvasBindToken != myToken) return;
                cv.applyKnownAspectRatioEarly(aspectKey, ratio);
            });
        });
    }

    /** Reads only the image's dimension header (BitmapFactory.Options
     *  .inJustDecodeBounds) — no pixel decode, no bitmap allocation.
     *  Accepts a local File or a local (content://, file://) Uri; returns
     *  null on any failure. Must be called off the main thread. */
    private static int[] decodeBoundsOnly(Context appCtx, Object loadSrc) {
        android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        try {
            if (loadSrc instanceof java.io.File) {
                android.graphics.BitmapFactory.decodeFile(((java.io.File) loadSrc).getAbsolutePath(), opts);
            } else if (loadSrc instanceof android.net.Uri) {
                try (java.io.InputStream is = appCtx.getContentResolver().openInputStream((android.net.Uri) loadSrc)) {
                    if (is == null) return null;
                    android.graphics.BitmapFactory.decodeStream(is, null, opts);
                }
            } else {
                return null; // remote URL string — not worth a network fetch just for bounds
            }
        } catch (Exception ignored) {
            return null;
        }
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null;
        return new int[]{opts.outWidth, opts.outHeight};
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
        int position = findMessagePositionById(messageId);
        if (position != RecyclerView.NO_POSITION) {
            notifyItemChanged(position, PAYLOAD_MEDIA_PROGRESS);
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
        // PERF: target only currently attached children. A range notification
        // still walks the adapter's entire loaded window and can enqueue work
        // for prefetched holders; search only needs the pixels on screen.
        RecyclerView rv = attachedRecyclerView;
        if (rv == null) return;
        RecyclerView.LayoutManager lm = rv.getLayoutManager();
        if (lm == null) return;
        for (int childIndex = 0; childIndex < lm.getChildCount(); childIndex++) {
            View child = lm.getChildAt(childIndex);
            int position = child == null ? RecyclerView.NO_POSITION
                    : rv.getChildAdapterPosition(child);
            if (position != RecyclerView.NO_POSITION && position < getItemCount()) {
                notifyItemChanged(position, PAYLOAD_SEARCH);
            }
        }
    }

    // PERF: Linkify.addLinks() runs several regex passes (URL/phone/email)
    // over the full message text — a real cost on every onBindViewHolder,
    // paid again and again for the very common "scroll away, scroll back"
    // case where the same message gets rebound to a recycled holder
    // repeatedly. Cache the finished CharSequence (SpannableString with
    // link spans, or the plain String when there's no link) keyed by
    // messageId+text-hash, so a repeat bind is a HashMap lookup instead
    // of a fresh regex scan + allocation.
    // PERF FIX: these were per-adapter instance fields — cold (empty) every
    // single time a chat is opened, even though every key here (messageId,
    // or a pure timestamp/day bucket) has nothing chat-specific about it and
    // is already capped with real LRU eviction below. Static/process-wide —
    // same treatment as reelOwnerAvatarCache/reelThumbCache further down —
    // means reopening a chat (or opening a different one) reuses whatever's
    // already warm instead of starting from zero every time.
    // PERF FIX (v_jank2): access is NO LONGER main-thread only — see
    // prewarmLinkifyCache() below, called from ChatActivity's
    // e2eeDecryptExecutor background thread right after decrypt, so the
    // regex work is done and cached before bindMessage() ever needs it.
    // Both writers (background prewarm + main-thread bind fallback) and the
    // main-thread reader go through the same precomputeCacheLock, so this
    // stays correct with two threads touching it instead of one.
    private static final Object precomputeCacheLock = new Object();
    private static final java.util.LinkedHashMap<String, CharSequence> linkifiedTextCache =
            new java.util.LinkedHashMap<String, CharSequence>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        java.util.Map.Entry<String, CharSequence> eldest) {
                    return size() > 120;
                }
            };

    /**
     * WhatsApp-level: pre-computes and caches the Linkify pass for a plain
     * text/emoji message OFF the main thread, so that by the time this
     * message scrolls into view, bindMessage()'s cache lookup at the
     * linkifiedTextCache.get(linkCacheKey) call site is a guaranteed hit —
     * no regex scan on the UI thread, ever, for a message that went through
     * this path first.
     * <p>
     * Call this from a background thread (e.g. ChatActivity's
     * e2eeDecryptExecutor, right after decryptIncomingIfNeeded() has put the
     * real plaintext on m.text) for every newly-received/changed message.
     * A no-op for non-text types, spoiler messages (they use a separate
     * reveal-on-tap span, not this cache), and messages already cached —
     * cheap enough to call unconditionally.
     */
    public static void prewarmLinkifyCache(Message m) {
        if (m == null) return;
        String type = m.type != null ? m.type : "text";
        if (!"text".equals(type) && !"emoji".equals(type)) return;
        String txt = m.text != null ? m.text : "";
        if (txt.isEmpty()) return;
        if (com.callx.app.utils.SpoilerTextHelper.hasSpoiler(txt)) return;
        if (Boolean.TRUE.equals(m.edited)) txt += " (edited)";
        String linkCacheKey = (m.messageId != null ? m.messageId : m.id) + "#" + txt.hashCode();
        synchronized (precomputeCacheLock) {
            if (linkifiedTextCache.containsKey(linkCacheKey)) return; // already warm
        }
        boolean mightHaveLink = txt.contains("http://")
                || txt.contains("https://")
                || txt.contains("www.")
                || txt.contains("@")
                || (txt.length() >= 7 && txt.contains("+"));
        CharSequence spanned;
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

    // WHATSAPP-STYLE HEIGHT CACHE: LRU cache of measured message row heights
    // keyed by messageId. When a new message is bound, if we've previously
    // measured a similar message (same sender type, rough text length), reuse
    // that height. This prevents the RecyclerView from re-measuring and
    // re-laying-out the entire list when a new message arrives at the bottom.
    // The height is cached in onViewRecycled() after the view has been
    // laid out once, so it's accurate.
    private static final java.util.LinkedHashMap<String, Integer> messagHeightCache =
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
    private static final java.util.LinkedHashMap<String, Message> messageObjectCache =
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
    /**
     * uid -> photoUrl for the group's current members — the SAME
     * java.util.Map instance GroupChatActivity's memberPresence/members
     * Firebase listeners already keep up to date (memberPhotos field, fed
     * by setupGroupMembersAndPresence()). Passed once via
     * setGroupMemberPhotos(); since it's the live map being mutated
     * in-place by those listeners (not a fresh map per update), reads here
     * automatically see fresh values without any extra network/DB call or
     * repeated wiring. Empty map for 1:1 chat (isGroup=false) or before
     * the activity wires it up.
     */
    private java.util.Map<String, String> groupMemberPhotos = java.util.Collections.emptyMap();

    /** Wire the group's live uid->photoUrl map (see field doc above) — call
     *  once from GroupChatActivity right after the members/presence
     *  listeners are set up. No-op for 1:1 chat. */
    public void setGroupMemberPhotos(java.util.Map<String, String> photos) {
        this.groupMemberPhotos = photos != null ? photos : java.util.Collections.emptyMap();
        bumpSeenByEpoch();
        bumpPhotosEpoch();
    }

    /**
     * uid -> role ("creator" | "admin" | "member") for the group's current
     * members — the SAME live map GroupChatActivity's membersRef listener
     * mutates in place (memberRoles), wired once like groupMemberPhotos, so
     * reads at bind time always see fresh roles with no extra network/DB
     * call. Drives the admin/creator pill after the sender name.
     */
    private java.util.Map<String, String> groupMemberRoles = java.util.Collections.emptyMap();

    public void setGroupMemberRoles(java.util.Map<String, String> roles) {
        this.groupMemberRoles = roles != null ? roles : java.util.Collections.emptyMap();
    }

    /** Pill text for {@code uid}'s role, or null for a plain member / unknown / broadcast. */
    @Nullable
    private String groupBadgeFor(@Nullable String uid) {
        if (uid == null) return null;
        String role = groupMemberRoles.get(uid);
        if ("creator".equals(role)) return "Creator";
        if ("admin".equals(role)) return "Admin";
        return null;
    }

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
    private static final android.util.LongSparseArray<String> timeStringCache =
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
    private static final android.util.LongSparseArray<String> viewOnceTimeCache =
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
    // PERF FIX: static — see the shared-cache block up top for why this is
    // safe (formatDateLabel() below never caches the day-relative "Today"/
    // "Yesterday" labels, only absolute date strings which never go stale).
    private static final android.util.LongSparseArray<String> dateLabelCache =
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
    // PERF FIX: static — pure function of two timestamps, no current-date
    // relativity at all, so sharing across chats/reopens is always correct.
    private static final android.util.LongSparseArray<Boolean> sameDayCache =
            new android.util.LongSparseArray<>(32);

    // ── PERF ULTRA: voice-caption duration text cache (LongSparseArray) ─────
    // Feature: Voice Caption on Photo. Every bind of an image message that
    // carries an attached voice note used to build its "m:ss" label with a
    // fresh String.format(Locale.US, "%d:%02d", ...) call — Formatter
    // construction + locale lookup + String allocation, on every single
    // bind (including recycled-holder rebinds while scrolling past the
    // same message repeatedly). Voice-note duration is fixed once recorded,
    // so the same voiceMs always produces the same label — cache it.
    // LongSparseArray (not LruCache<Long,String>) for the same reason as
    // timeStringCache above: keys are primitive longs, so lookups on a
    // scroll-heavy chat screen no longer box a Long per row.
    private static final android.util.LongSparseArray<String> voiceDurationTextCache =
            new android.util.LongSparseArray<>(128);

    // v425/v426 PERF: message-id -> blurHash carried inside the Media-E2E key
    // envelope. The envelope is immutable per message, but it used to be
    // decrypted + JSON-parsed again on every bind — and on the MAIN thread,
    // which breaks the v375 "never decrypt envelopes on the main thread" rule
    // (and the per-partner FIFO ratchet ordering it protects). Now:
    //   • peekEnvelopeBlurHash()       — LRU hit, zero work, used at bind time
    //   • resolveImageBlurHashAsync()  — miss: decrypt on the partner's
    //                                    E2eeDecryptExecutor bucket, fill the LRU
    // "" in the LRU = envelope had no hash. Failures (env == null: key not
    // ready yet) are deliberately NOT cached.
    private static final android.util.LruCache<String, String> ENVELOPE_BLURHASH_CACHE =
            new android.util.LruCache<>(256);

    /** @return null = not cached yet; "" = cached, no hash; else the hash. */
    @Nullable
    private static String peekEnvelopeBlurHash(@NonNull Message m) {
        final String mid = m.messageId != null ? m.messageId : m.id;
        return mid != null ? ENVELOPE_BLURHASH_CACHE.get(mid) : null;
    }

    private void resolveImageBlurHashAsync(Context ctx, Message m, VH h, int token,
            java.util.function.Consumer<String> cb) {
        final String encKey = m.mediaKeyEnc;
        final String senderId = m.senderId;
        final String msgId = m.messageId != null ? m.messageId : m.id;
        com.callx.app.utils.E2eeDecryptExecutor.execute(senderId, () -> {
            com.callx.app.utils.MediaE2ECrypto.KeyEnvelope env =
                    com.callx.app.utils.MediaE2ECrypto.decryptEnvelopeForMessage(ctx, encKey, senderId, msgId);
            final String hash = env != null ? env.blurHash : null;
            if (env != null && msgId != null) {
                ENVELOPE_BLURHASH_CACHE.put(msgId, hash != null ? hash : "");
            }
            MEDIA_KEY_MAIN_HANDLER.post(() -> {
                if (h.canvasBindToken != token) return; // recycled/rebound meanwhile
                cb.accept(hash);
            });
        });
    }

    private static String formatVoiceDuration(long voiceMs) {
        long voiceSecs = voiceMs / 1000;
        String s = voiceDurationTextCache.get(voiceSecs);
        if (s != null) return s;
        s = String.format(java.util.Locale.US, "%d:%02d", voiceSecs / 60, voiceSecs % 60);
        if (voiceDurationTextCache.size() >= 128) voiceDurationTextCache.clear();
        voiceDurationTextCache.put(voiceSecs, s);
        return s;
    }

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
    // PERF ADV: showActionBottomSheet()/showFullEmojiPicker() used to `new`
    // a fresh ReactionQuickBarCanvasView/ReactionGridCanvasView on EVERY
    // long-press — reallocating each view's Paint/RectF fields for a widget
    // whose visual content is fully re-derived via bind() anyway. Both
    // views are stateless enough to be built once and reused across every
    // open (see reuseInWrapper() below, which detaches from the previous
    // dialog's wrapper before re-adding).
    private com.callx.app.conversation.canvas.ReactionQuickBarCanvasView cachedEmojiBar;
    private com.callx.app.conversation.canvas.ReactionGridCanvasView cachedEmojiGrid;
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
        /** Feature: group sender-avatar tap. Called when the user taps a
         *  received group message's WhatsApp-style sender-avatar circle —
         *  implementor should open the full-screen avatar viewer for
         *  {@code m.senderId} (e.g. via DialogFullscreenHelper.showAvatarZoom()). */
        default void onGroupSenderAvatarClick(Message m) {}
        /** Feature: group sender-avatar long-press. Called when the user
         *  long-presses the same avatar circle — implementor should insert
         *  an "@Name " mention for {@code m.senderId}/{@code m.senderName}
         *  into the compose box (e.g. via GroupMentionController.insertMention()). */
        default void onGroupSenderAvatarLongClick(Message m) {}
        /** Feature: poll-voters strip tap (group, non-anonymous polls only). Implementor
         *  should show who voted for which option. */
        default void onPollVotersTap(Message m) {}
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
        notifyVisiblePresenceChanges(changed);
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
        notifyVisiblePresenceChanges(changed);
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
        notifyVisiblePresenceChanges(changed);
    }

    /**
     * Refresh only attached rows whose presence state changed.
     * PagingDataAdapter positions can shift as pages load or invalidate, so a
     * permanent id-to-position index would add stale-position risk. The
     * RecyclerView already owns the authoritative attached-holder set; scanning
     * that small window keeps this O(visible children), not O(all messages).
     *
     * Rows outside the attached window read the latest presence sets on their
     * next normal bind, so they do not need an immediate notification.
     */
    private void notifyVisiblePresenceChanges(java.util.Set<String> changedIds) {
        if (changedIds == null || changedIds.isEmpty()) return;
        RecyclerView rv = attachedRecyclerView;
        if (rv == null) return;

        for (int childIndex = 0; childIndex < rv.getChildCount(); childIndex++) {
            android.view.View child = rv.getChildAt(childIndex);
            RecyclerView.ViewHolder rawHolder = rv.getChildViewHolder(child);
            if (!(rawHolder instanceof VH)) continue;

            VH holder = (VH) rawHolder;
            Message message = holder.boundMessage;
            if (message == null) continue;
            String id = message.messageId != null ? message.messageId : message.id;
            if (id == null || !changedIds.contains(id)) continue;

            int position = holder.getBindingAdapterPosition();
            if (position != RecyclerView.NO_POSITION) {
                // Payload-only refresh: no Glide reload, Linkify pass, full
                // bubble redraw or countdown restart for a dot/badge change.
                notifyItemChanged(position, PAYLOAD_PRESENCE);
            }
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
        int position = findMessagePositionById(id);
        return position == RecyclerView.NO_POSITION ? null : getItem(position);
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
        notifyVisibleRangeChanged(PAYLOAD_SELECTION);
        if (multiSelectListener != null) multiSelectListener.onSelectionChanged(selectedMessageIds.size());
    }

    public void exitMultiSelectMode() {
        multiSelectMode = false;
        selectedMessageIds.clear();
        // PERF ADV: see enterMultiSelectMode() above — same visible-range-only fix.
        notifyVisibleRangeChanged(PAYLOAD_SELECTION);
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
    private void notifyVisibleRangeChanged(Object payload) {
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
                notifyItemRangeChanged(start, end - start + 1, payload);
                return;
            }
        }
        // Defensive fallback — layout not measured yet, or a different
        // LayoutManager type. Correct either way, just not the optimized path.
        notifyItemRangeChanged(0, getItemCount(), payload);
    }

    /**
     * PERF: theme (night-mode) toggle — every bubble's colors need to be
     * re-resolved (they're picked up automatically via
     * ContextCompat.getColor()/theme attrs during a normal bind), but that
     * only has to happen for rows actually on screen. Reuses the same
     * visible-range-only path as multi-select instead of
     * notifyDataSetChanged(), which would force a full-list rebind burst on
     * every theme switch regardless of chat length.
     */
    public void notifyThemeChanged() {
        notifyVisibleRangeChanged(PAYLOAD_THEME);
    }

    /** Rows kept warm either side of the viewport — matches the largest
     *  itemViewCacheSize() AdaptiveChatScrollPolicy hands out (16), so a
     *  cached-but-offscreen holder can't come back with a stale placeholder. */
    private static final int MEMBER_AVATAR_NOTIFY_BUFFER_ROWS = 16;

    /**
     * PERF (group avatar rebind churn): call when one or more members'
     * profile-photo URL actually changed in the live groupMemberPhotos map
     * (first arrival OR a replaced photo — callers must diff before calling,
     * see GroupChatActivity#putMemberPhoto). Previously nothing at all
     * happened here (setMemberPhotos() is a stored-only no-op), so a photo
     * that resolved AFTER a row was bound left that row on the placeholder
     * circle until the user scrolled it out and back in.
     *
     * The old-school fix would be notifyItemChanged(pos) / notifyDataSetChanged()
     * — a full bind: re-measure, Linkify, media/reply/Glide rebinds, all to
     * swap one 20dp bitmap. Instead this scans ONLY the on-screen rows (+
     * MEMBER_AVATAR_NOTIFY_BUFFER_ROWS either side for cached holders) and
     * fires notifyItemChanged(pos, PAYLOAD_GROUP_SENDER) for just the rows
     * whose sender is in {@code changedUids}. onBindViewHolder(payloads)
     * routes that to bindGroupSenderOnly() → setGroupSenderAvatarBitmap()
     * → invalidate() — the bubble is NOT re-measured.
     *
     * Rows outside the scanned window aren't touched: they haven't been
     * bound yet, so their first bind reads the already-updated map.
     * Uses peek() (not getItem()) so this scan never triggers Paging
     * page-load hints. Main thread only (same as every notify*()).
     */
    public void onMemberPhotosChanged(@Nullable java.util.Collection<String> changedUids) {
        if (!isGroup || changedUids == null || changedUids.isEmpty()) return;
        bumpSeenByEpoch(); // photo map changed → every cached strip (key embeds photo hashes) is stale
        bumpPhotosEpoch(); // v444: same for cached reactor / poll-voter strips
        // Batch-warm the visible members' avatars (coalesced — a burst of
        // per-member photo arrivals becomes one preload batch).
        prefetchSenderAvatars();
        RecyclerView rv = attachedRecyclerView;
        if (rv == null) return; // not attached yet — first bind reads the fresh map
        int count = getItemCount();
        if (count == 0) return;

        int start = 0;
        int end = count - 1;
        RecyclerView.LayoutManager lm = rv.getLayoutManager();
        if (lm instanceof androidx.recyclerview.widget.LinearLayoutManager) {
            androidx.recyclerview.widget.LinearLayoutManager llm =
                    (androidx.recyclerview.widget.LinearLayoutManager) lm;
            int first = llm.findFirstVisibleItemPosition();
            int last = llm.findLastVisibleItemPosition();
            if (first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION) {
                return; // nothing laid out yet — first bind reads the fresh map
            }
            start = Math.max(0, first - MEMBER_AVATAR_NOTIFY_BUFFER_ROWS);
            end = Math.min(count - 1, last + MEMBER_AVATAR_NOTIFY_BUFFER_ROWS);
        }
        // else: unexpected LayoutManager — scan the whole (already-loaded) list.

        for (int i = start; i <= end; i++) {
            Message m = peek(i);
            if (m == null || m.senderId == null) continue;
            // Reactor / voter faces on ANY row (mine or not). Integer payloads
            // first — RecyclerView merges payloads in notify order and the
            // combined-flags path only looks at element 0.
            int faceFlags = 0; // ONE combined Integer payload (the flags path only reads element 0)
            if (m.reactions != null && !m.reactions.isEmpty()) {
                for (String cu : changedUids) {
                    if (m.reactions.containsKey(cu)) { faceFlags |= FLAG_REACTIONS; break; }
                }
            }
            if (m.pollVotes != null && !m.pollVotes.isEmpty() && !Boolean.TRUE.equals(m.pollAnonymous)) {
                for (String cu : changedUids) {
                    if (m.pollVotes.containsKey(cu)) { faceFlags |= FLAG_POLL; break; }
                }
            }
            if (faceFlags != 0) notifyItemChanged(i, Integer.valueOf(faceFlags));
            if (currentUid != null && currentUid.equals(m.senderId)) {
                // My own row: its "Seen by" strip shows readers' photos. The
                // strip key embeds each photo, so a plain re-eval swaps just
                // the circles that changed.
                if (m.readBy != null && !m.readBy.isEmpty()) {
                    for (String cu : changedUids) {
                        if (m.readBy.containsKey(cu)) { notifyItemChanged(i, PAYLOAD_READ_BY); break; }
                    }
                }
                continue;
            }
            // Only run-tail rows draw an avatar — skip the rest (no payload bind).
            if (changedUids.contains(m.senderId) && isGroupAvatarRunTail(i, m)) {
                notifyItemChanged(i, PAYLOAD_GROUP_SENDER);
            }
        }
    }

    // ── Batch prefetch of group sender avatars ───────────────────────────
    private static final int SENDER_AVATAR_PREFETCH_MAX = 24;       // distinct photos per run
    private static final int SENDER_AVATAR_PREFETCH_TAIL_ROWS = 40; // newest rows scanned when nothing's laid out yet
    private static final long SENDER_AVATAR_PREFETCH_COALESCE_MS = 100L;
    /** Photo URLs already handed to prefetchBatch() this session — keeps
     *  repeated triggers (every photo arrival, first pages update) idempotent. */
    private final java.util.HashSet<String> prefetchedSenderPhotoUrls = new java.util.HashSet<>();
    private boolean senderAvatarPrefetchQueued;
    private final Runnable senderAvatarPrefetchRunnable = () -> {
        senderAvatarPrefetchQueued = false;
        runSenderAvatarPrefetch();
    };

    /**
     * PERF: warm every avatar the group screen is about to need in ONE batch
     * instead of letting each row discover its own on bind. Call when the
     * first pages land and whenever member photos arrive (onMemberPhotosChanged
     * does this itself). Coalesced: any number of calls inside
     * SENDER_AVATAR_PREFETCH_COALESCE_MS produce a single batch, so
     * per-member profile reads trickling in still batch up. Idempotent.
     * No-op for 1:1 chats or before the RecyclerView is attached.
     */
    public void prefetchSenderAvatars() {
        if (!isGroup || senderAvatarPrefetchQueued) return;
        RecyclerView rv = attachedRecyclerView;
        if (rv == null) return;
        senderAvatarPrefetchQueued = true;
        rv.postDelayed(senderAvatarPrefetchRunnable, SENDER_AVATAR_PREFETCH_COALESCE_MS);
    }

    private void runSenderAvatarPrefetch() {
        RecyclerView rv = attachedRecyclerView;
        if (rv == null || !isGroup) return;
        int count = getItemCount();
        if (count == 0) return;

        // Which rows count as "visible members": the laid-out viewport (±cache
        // buffer) when there is one; otherwise the newest rows (the list is
        // stackFromEnd, so right after open that's what's about to show).
        int start = Math.max(0, count - SENDER_AVATAR_PREFETCH_TAIL_ROWS);
        int end = count - 1;
        RecyclerView.LayoutManager lm = rv.getLayoutManager();
        if (lm instanceof androidx.recyclerview.widget.LinearLayoutManager) {
            androidx.recyclerview.widget.LinearLayoutManager llm =
                    (androidx.recyclerview.widget.LinearLayoutManager) lm;
            int first = llm.findFirstVisibleItemPosition();
            int last = llm.findLastVisibleItemPosition();
            if (first != RecyclerView.NO_POSITION && last != RecyclerView.NO_POSITION) {
                start = Math.max(0, first - MEMBER_AVATAR_NOTIFY_BUFFER_ROWS);
                end = Math.min(count - 1, last + MEMBER_AVATAR_NOTIFY_BUFFER_ROWS);
            }
        }

        java.util.LinkedHashSet<String> photos = new java.util.LinkedHashSet<>();
        for (int i = end; i >= start && photos.size() < SENDER_AVATAR_PREFETCH_MAX; i--) { // newest first
            Message m = peek(i);
            if (m == null) continue;

            // Feature 8 — join/leave system rows: eventPhoto rides on the
            // message itself (set once at post time), so no groupMemberPhotos
            // lookup is needed — warm it directly at the same TIER_INLINE
            // bindBitmap() reads (see onBindViewHolder's system-row branch).
            if ("system".equals(m.type) && m.eventUid != null && !m.eventUid.isEmpty()) {
                String ep = m.eventPhoto;
                if (ep != null && !ep.isEmpty() && prefetchedSenderPhotoUrls.add(ep)) photos.add(ep);
                continue;
            }

            if (m.senderId == null) continue;

            // Feature 6 — reactor faces (first MINI_STRIP_REACTORS in reaction
            // order, matching bindReactionAvatars()) and non-anonymous poll
            // voter faces (up to SEEN_BY_MAX_CIRCLES, matching bindPollVoters()) —
            // warmed on ANY row (mine or not), same as their bind-time source.
            if (m.reactions != null && !m.reactions.isEmpty()) {
                int shown = 0;
                for (String ruid : m.reactions.keySet()) {
                    if (++shown > MINI_STRIP_REACTORS || photos.size() >= SENDER_AVATAR_PREFETCH_MAX) break;
                    if (ruid == null) continue;
                    String rp = groupMemberPhotos.get(ruid);
                    if (rp != null && !rp.isEmpty() && prefetchedSenderPhotoUrls.add(rp)) photos.add(rp);
                }
            }
            if (m.pollVotes != null && !m.pollVotes.isEmpty() && !Boolean.TRUE.equals(m.pollAnonymous)) {
                int shown = 0;
                for (String vuid : m.pollVotes.keySet()) {
                    if (++shown > SEEN_BY_MAX_CIRCLES || photos.size() >= SENDER_AVATAR_PREFETCH_MAX) break;
                    if (vuid == null) continue;
                    String vp = groupMemberPhotos.get(vuid);
                    if (vp != null && !vp.isEmpty() && prefetchedSenderPhotoUrls.add(vp)) photos.add(vp);
                }
            }

            if (m.senderId.equals(currentUid)) {
                // My own row: warm the (few) readers its "Seen by" strip will draw.
                if (m.readBy != null) {
                    int seen = 0;
                    for (String ruid : m.readBy.keySet()) {
                        if (++seen > SEEN_BY_MAX_CIRCLES || photos.size() >= SENDER_AVATAR_PREFETCH_MAX) break;
                        if (ruid == null || ruid.equals(currentUid)) continue;
                        String rp = groupMemberPhotos.get(ruid);
                        if (rp != null && !rp.isEmpty() && prefetchedSenderPhotoUrls.add(rp)) photos.add(rp);
                    }
                }
                continue;
            }
            String photo = groupMemberPhotos.get(m.senderId);
            if (photo == null || photo.isEmpty()) continue;
            if (prefetchedSenderPhotoUrls.add(photo)) photos.add(photo);
        }
        if (!photos.isEmpty()) {
            com.callx.app.cache.ChatAvatarBinder.prefetchBatch(rv.getContext(), photos);
        }
    }

    /**
     * Feature 7 — "Messages from <member>" filter: called by
     * {@code GroupChatActivity#openMemberMessageSearch} the instant the
     * filter opens. The filter jumps straight to that member's messages,
     * which are very often scrolled well outside {@link #runSenderAvatarPrefetch}'s
     * normal ± viewport window (that's the whole point of the filter — finding
     * older messages) — so without this, the first jumped-to row would cold-decode
     * its run-tail avatar. One photo, same TIER_INLINE {@code prefetchBatch()}
     * pipeline every other avatar in this adapter already shares — practically
     * always an instant L2 hit anyway, since the same member's avatar was almost
     * certainly already bound somewhere in the currently-loaded window.
     */
    public void prefetchMemberFilterAvatar(String senderUid) {
        RecyclerView rv = attachedRecyclerView;
        if (rv == null || !isGroup || senderUid == null || senderUid.isEmpty()) return;
        String photo = groupMemberPhotos.get(senderUid);
        if (photo == null || photo.isEmpty() || !prefetchedSenderPhotoUrls.add(photo)) return;
        com.callx.app.cache.ChatAvatarBinder.prefetchBatch(rv.getContext(), java.util.Collections.singleton(photo));
    }

    /**
     * A member's role changed (first arrival of the members snapshot, promoted,
     * demoted, creator set). Re-binds only the on-screen (± buffer) rows that
     * currently show that member's NAME row — the pill is part of it, so only
     * run-head rows (or broadcasts, which never carry a pill) matter — via the
     * cheap PAYLOAD_GROUP_SENDER path: no re-measure of the bubble body, no
     * Glide reload. Rows outside the window read the fresh map on first bind.
     * Callers must diff before calling (see GroupChatActivity's members listener).
     */
    public void onMemberRolesChanged(@Nullable java.util.Collection<String> changedUids) {
        if (!isGroup || changedUids == null || changedUids.isEmpty()) return;
        RecyclerView rv = attachedRecyclerView;
        if (rv == null) return; // not attached yet — first bind reads the fresh map
        int count = getItemCount();
        if (count == 0) return;
        int start = 0;
        int end = count - 1;
        RecyclerView.LayoutManager lm = rv.getLayoutManager();
        if (lm instanceof androidx.recyclerview.widget.LinearLayoutManager) {
            androidx.recyclerview.widget.LinearLayoutManager llm =
                    (androidx.recyclerview.widget.LinearLayoutManager) lm;
            int first = llm.findFirstVisibleItemPosition();
            int last = llm.findLastVisibleItemPosition();
            if (first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION) return;
            start = Math.max(0, first - MEMBER_AVATAR_NOTIFY_BUFFER_ROWS);
            end = Math.min(count - 1, last + MEMBER_AVATAR_NOTIFY_BUFFER_ROWS);
        }
        for (int i = start; i <= end; i++) {
            Message m = peek(i);
            if (m == null || m.senderId == null || !changedUids.contains(m.senderId)) continue;
            if (Boolean.TRUE.equals(m.broadcast)) continue;
            if (isGroupNameRunHead(i, m)) notifyItemChanged(i, PAYLOAD_GROUP_SENDER);
        }
    }

    /** Single-uid convenience for {@link #onMemberPhotosChanged(java.util.Collection)}. */
    public void onMemberPhotoChanged(@Nullable String uid) {
        if (uid == null) return;
        onMemberPhotosChanged(java.util.Collections.singleton(uid));
    }

    /**
     * PAYLOAD_GROUP_SENDER fast path: re-resolve this row's sender photo
     * from groupMemberPhotos and swap ONLY the 20dp avatar bitmap. Runs the
     * exact same ChatAvatarBinder.bindBitmap() (L2 memory hit = same frame,
     * else async Glide) the full bind uses, so it shares the same cache
     * entries. Deliberately does NOT bump h.canvasBindToken — that would
     * cancel every other in-flight callback for this row (image/reply/
     * reel thumbs); it just snapshots the current token so a recycle/rebind
     * that happens before the async load finishes still drops the result.
     */
    private void bindGroupSenderOnly(@NonNull VH h, int position, @NonNull Message m) {
        final com.callx.app.conversation.canvas.MessageBubbleCanvasView cv = h.canvasView;
        // Not a canvas row / not a received-group row with the avatar column
        // reserved (sent, 1:1, broadcast pseudo-label) — nothing to swap.
        if (cv == null || !cv.isGroupSenderAvatarVisible()) return;

        // This payload is also what the observer fires when a neighbor was
        // inserted/removed, so first re-evaluate the parts that depend on
        // neighbors: the NAME (first bubble of the run) and — below — whether
        // this row is still the AVATAR tail. Both setters are no-ops when
        // nothing changed (the photo-changed case), and the name setter
        // re-measures only when the row's size signature actually changes.
        if (Boolean.TRUE.equals(m.broadcast) || isGroupNameRunHead(position, m)) {
            cv.setGroupSender(groupSenderLabel(m), m.senderId);
            cv.setGroupSenderBadge(Boolean.TRUE.equals(m.broadcast) ? null : groupBadgeFor(m.senderId));
        } else {
            cv.clearGroupSender();
        }
        // Name visibility also drives the tight/full gap above the bubble.
        applyGroupedSpacing(h, position, m);

        final boolean tail = isGroupAvatarRunTail(position, m);
        cv.setGroupSenderAvatarShown(tail);
        if (!tail) return; // avatar now belongs to a later bubble — nothing to load

        final String url = groupMemberPhotos.get(m.senderId);
        if (url == null || url.isEmpty()) {
            cv.setGroupSenderAvatarBitmap(null); // photo removed → placeholder
            return;
        }
        // v445: L2 hit → set inline (no lambda / token / callback allocation).
        final android.graphics.Bitmap l2 = com.callx.app.cache.ChatAvatarBinder.peekInline(h.itemView.getContext(), url);
        if (l2 != null) { cv.setGroupSenderAvatarBitmap(l2); return; }
        final int token = h.canvasBindToken;
        com.callx.app.cache.ChatAvatarBinder.bindBitmap(h.itemView.getContext(), url, 0L, resource -> {
            if (h.canvasBindToken != token) return; // recycled/rebound meanwhile
            cv.setGroupSenderAvatarBitmap(resource);
        });
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
            if ("system".equals(m.type) && m.eventUid != null && !m.eventUid.isEmpty()) continue;
            String id = m.messageId != null ? m.messageId : m.id;
            if (id != null && selectedMessageIds.contains(id)) result.add(m);
        }
        return result;
    }

    private static final int TAG_KEY_SELECTED = 0x7F_0B_0001;

    /** v448: unregister only when this holder actually registered (skips the map op on ~every bind). */
    private static void expiryUnregister(@NonNull VH h) {
        if (h.expiryRegistered) {
            com.callx.app.utils.ExpiryTickManager.get().unregister(h);
            h.expiryRegistered = false;
        }
    }

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

        if (isGroup) {
            // Run-boundary staleness fix: whether a row shows the group
            // AVATAR depends on the NEXT row (isGroupAvatarRunTail) and
            // whether it shows the sender NAME depends on the PREVIOUS row
            // (isGroupNameRunHead), but DiffUtil only rebinds a row whose OWN
            // content changed. A new same-sender message appended after row
            // N would leave N drawing an avatar it should now hide (two
            // avatars in one run); an older page prepended above the oldest
            // row would leave its name showing under a same-sender message.
            // Re-evaluate the rows on both sides of every insert/remove
            // point via the cheap payload path.
            registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
                // Row ABOVE the change point: its NEXT row changed (avatar tail).
                // Row BELOW it: its PREVIOUS row changed (name run-head) — this
                // is also what fixes the oldest row when an older page is
                // prepended, and the row after a deleted message.
                @Override public void onItemRangeChanged(int positionStart, int itemCount) {
                    // v443: a changed row below may be some own row's "next own row".
                    bumpSeenByEpoch();
                }
                @Override public void onItemRangeChanged(int positionStart, int itemCount, @Nullable Object payload) {
                    bumpSeenByEpoch();
                }
                @Override public void onItemRangeInserted(int positionStart, int itemCount) {
                    bumpSeenByEpoch();
                    refreshGroupSenderRow(positionStart - 1);
                    refreshGroupSenderRow(positionStart + itemCount);
                    // A new own row below changes which readers the own row above shows.
                    if (rangeHasOwnRow(positionStart, itemCount)) refreshSeenByOwnRowAbove(positionStart - 1);
                }
                @Override public void onItemRangeRemoved(int positionStart, int itemCount) {
                    bumpSeenByEpoch();
                    refreshGroupSenderRow(positionStart - 1);
                    refreshGroupSenderRow(positionStart);
                    refreshSeenByOwnRowAbove(positionStart - 1); // removed row may have been the "next own row"
                }
                @Override public void onItemRangeMoved(int fromPosition, int toPosition, int itemCount) {
                    bumpSeenByEpoch();
                    int lo = Math.min(fromPosition, toPosition) - 1;
                    int hi = Math.max(fromPosition, toPosition) + itemCount;
                    for (int p = Math.max(0, lo); p <= hi && p - lo < 30; p++) {
                        refreshGroupSenderRow(p);
                    }
                }
            });
        }
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

    // ── O(1) message-id lookup for live row updates ────────────────────────
    // Reaction/upload/local-media callbacks used to scan every item in the
    // Paging snapshot. That is especially expensive for long chats because
    // those callbacks can arrive repeatedly while a row is being recycled.
    // Positions are cached opportunistically as rows bind. Paging can shift
    // positions when a page is inserted, so every hit is validated against
    // the current item and the small map is cleared on a count change.
    private final java.util.HashMap<String, Integer> messagePositionIndex =
            new java.util.HashMap<>(128);
    private int messagePositionIndexItemCount = -1;

    private void ensureMessagePositionIndexGeneration() {
        int currentCount = getItemCount();
        if (currentCount != messagePositionIndexItemCount) {
            messagePositionIndex.clear();
            messagePositionIndexItemCount = currentCount;
        }
    }

    private static boolean messageHasId(@Nullable Message message, String id) {
        return message != null && id != null
                && (id.equals(message.messageId) || id.equals(message.id));
    }

    private void indexMessagePosition(int position, @Nullable Message message) {
        if (message == null || position < 0) return;
        ensureMessagePositionIndexGeneration();
        if (message.messageId != null) messagePositionIndex.put(message.messageId, position);
        if (message.id != null) messagePositionIndex.put(message.id, position);
    }

    /**
     * Resolves a currently loaded message id without making normal live
     * updates walk the entire Paging snapshot. A stale position (possible
     * after a same-size refresh) is validated and falls back to one repair
     * scan; subsequent callbacks use the repaired entry.
     */
    private int findMessagePositionById(String id) {
        if (id == null || id.isEmpty()) return RecyclerView.NO_POSITION;
        ensureMessagePositionIndexGeneration();

        Integer indexed = messagePositionIndex.get(id);
        if (indexed != null && indexed >= 0 && indexed < getItemCount()) {
            if (messageHasId(getItem(indexed), id)) return indexed;
            messagePositionIndex.remove(id);
        }

        // Reaction taps and upload ticks normally hit a bound holder. Check
        // that small attached set before the defensive snapshot fallback.
        if (attachedRecyclerView != null) {
            for (int childIndex = 0;
                    childIndex < attachedRecyclerView.getChildCount();
                    childIndex++) {
                View child = attachedRecyclerView.getChildAt(childIndex);
                RecyclerView.ViewHolder raw = attachedRecyclerView.getChildViewHolder(child);
                if (!(raw instanceof VH)) continue;
                VH holder = (VH) raw;
                if (!messageHasId(holder.boundMessage, id)) continue;
                int position = holder.getBindingAdapterPosition();
                if (position != RecyclerView.NO_POSITION) {
                    indexMessagePosition(position, holder.boundMessage);
                    return position;
                }
            }
        }

        // Defensive path for an id that has not been bound yet (for example,
        // an off-screen gallery reply target). This is now a rare miss rather
        // than the cost paid by every live callback.
        androidx.paging.ItemSnapshotList<Message> current = snapshot();
        for (int i = 0; i < current.size(); i++) {
            Message message = current.get(i);
            if (messageHasId(message, id)) {
                indexMessagePosition(i, message);
                return i;
            }
        }
        return RecyclerView.NO_POSITION;
    }

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
        recyclerView.removeCallbacks(senderAvatarPrefetchRunnable);
        senderAvatarPrefetchQueued = false;
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
    // ── PERF ADV #3: disk-backed link-preview thumbnail cache ──────────────
    // LinkPreviewCacheEntity (Room) only ever stored URL/title/domain/
    // imageUrl metadata — the actual decoded thumbnail bitmap was never
    // persisted at the app level, only opportunistically by Glide's own
    // disk cache (a cache Glide is free to evict independently, with no
    // guarantee tied to what this app actually wants kept warm). Reuses
    // the same generic AvatarL3DiskCache (core module) every avatar
    // pipeline already relies on for its own cold-start disk tier — own
    // subdir/budget, independent of every *AvatarL2Cache's L3 instance.
    private static final long LINK_PREVIEW_L3_MAX_BYTES = 3L * 1024 * 1024; // 3 MB
    private static volatile com.callx.app.cache.AvatarL3DiskCache sLinkPreviewL3;

    private static com.callx.app.cache.AvatarL3DiskCache linkPreviewL3(Context ctx) {
        com.callx.app.cache.AvatarL3DiskCache instance = sLinkPreviewL3;
        if (instance == null) {
            synchronized (MessagePagingAdapter.class) {
                instance = sLinkPreviewL3;
                if (instance == null) {
                    instance = new com.callx.app.cache.AvatarL3DiskCache(ctx, "link_preview", LINK_PREVIEW_L3_MAX_BYTES);
                    sLinkPreviewL3 = instance;
                }
            }
        }
        return instance;
    }

    private void bindLinkPreviewResult(
            @NonNull VH h,
            com.callx.app.conversation.canvas.MessageBubbleCanvasView cv,
            android.content.Context ctx,
            String previewUrl,
            com.callx.app.utils.LinkPreviewFetcher.Result r) {
        boolean hasThumb = r.imageUrl != null && !r.imageUrl.isEmpty();
        cv.setLinkPreview(r.url, r.title, r.domain, hasThumb);
        if (!hasThumb) return;
        // PERF #4: density-aware width; keep 2:1 aspect for link-preview card.
        // Check the dedicated link-preview pool synchronously first — a hit
        // renders the thumb in the very same frame as the title/domain text,
        // zero flash. Only a genuine miss falls back to async Glide.
        final int lpW = thumbPx(ctx), lpH = thumbPx(ctx) / 2;
        final String poolKey = poolKey(r.imageUrl, lpW, lpH);
        android.graphics.Bitmap lpHit = LINK_PREVIEW_BITMAP_CACHE.get(poolKey);
        if (lpHit != null && !lpHit.isRecycled()) {
            cv.setLinkPreviewThumbBitmap(lpHit);
            return;
        }
        h.linkPreviewFireToken = h.canvasBindToken;
        h.linkPreviewPoolKey = poolKey;
        h.linkPreviewTagAtFire = previewUrl;
        // L3 disk check BEFORE the network round-trip: process-death/cold-
        // start case (memory pool empty, but this thumbnail was decoded and
        // persisted on a previous app run) paints from disk with no network
        // hit at all. Genuine miss (or a token/tag change while the disk
        // read was in flight) falls through to the same reusable Glide
        // target the network path always used.
        final int fireToken = h.linkPreviewFireToken;
        linkPreviewL3(ctx).getAsync(poolKey, bitmap -> {
            if (h.canvasBindToken != fireToken) return; // recycled/rebound while disk read was in flight
            if (bitmap != null && !bitmap.isRecycled()) {
                LINK_PREVIEW_BITMAP_CACHE.put(poolKey, bitmap);
                if (previewUrl.equals(cv.getTag())) cv.setLinkPreviewThumbBitmap(bitmap);
                return;
            }
            // PERF ADV: reusable per-holder target (same pattern as
            // getOrCreateImageBindTarget()) instead of a fresh anonymous
            // CustomTarget on every bind, PLUS the canvasBindToken staleness
            // guard already used elsewhere in this file. Explicitly cleared
            // in onViewRecycled() below so a recycled/off-screen row's
            // in-flight network+decode is actually cancelled — not just
            // ignored on arrival like the old per-call CustomTarget was.
            glide(ctx).asBitmap().load(r.imageUrl).apply(THUMB_RGB565)
                    .override(lpW, lpH).centerCrop()
                    .into(h.getOrCreateLinkPreviewTarget());
        });
    }

    // ── PERF ADV #4: velocity-based link-preview prefetch ──────────────────
    // Mirrors CallAvatarBinder.prefetch() / every other *AvatarBinder.prefetch()
    // in the app: fast fling → skip entirely (wasted work, user blows past
    // the row before a decode would even finish); slow/deliberate scroll →
    // warm a few rows ahead. Bytes-only (DiskCacheStrategy.DATA) + Priority.LOW
    // preload of the thumbnail — no speculative CPU decode, and never
    // competes with whatever row is actually visible right now. Only fires
    // for messages whose link metadata is ALREADY known (LinkPreviewFetcher
    // cache hit) — deliberately does NOT speculatively trigger a fresh
    // metadata fetch (title/domain/OG-scrape) for a row the user may never
    // actually reach; that stays a real-bind-time-only cost, same as before.
    private static final float LINK_PREVIEW_FAST_FLING_THRESHOLD = 3.5f;  // px/ms
    private static final float LINK_PREVIEW_SLOW_SCROLL_THRESHOLD = 1.0f; // px/ms
    private static final int LINK_PREVIEW_PREFETCH_DEPTH_DEFAULT = 1;
    private static final int LINK_PREVIEW_PREFETCH_DEPTH_SLOW    = 4;
    private static final int LINK_PREVIEW_PREFETCH_DEPTH_FAST    = 0;

    public void prefetchLinkPreviews(@NonNull android.content.Context ctx, int fromIndex, float velocityPxPerMs) {
        int depth;
        if (velocityPxPerMs >= LINK_PREVIEW_FAST_FLING_THRESHOLD) depth = LINK_PREVIEW_PREFETCH_DEPTH_FAST;
        else if (velocityPxPerMs <= LINK_PREVIEW_SLOW_SCROLL_THRESHOLD) depth = LINK_PREVIEW_PREFETCH_DEPTH_SLOW;
        else depth = LINK_PREVIEW_PREFETCH_DEPTH_DEFAULT;
        if (depth == 0) return;
        android.content.Context appCtx = ctx.getApplicationContext();
        int count = getItemCount();
        int lpW = thumbPx(appCtx), lpH = lpW / 2;
        for (int i = Math.max(0, fromIndex); i < fromIndex + depth && i < count; i++) {
            Message m = peek(i);
            if (m == null || m.text == null) continue;
            String url = com.callx.app.utils.LinkPreviewFetcher.extractFirstUrl(m.text);
            if (url == null) continue;
            com.callx.app.utils.LinkPreviewFetcher.Result cached = com.callx.app.utils.LinkPreviewFetcher.peek(url);
            if (cached == null || cached.imageUrl == null || cached.imageUrl.isEmpty()) continue; // metadata not resolved yet — real bind will fetch it
            String poolKey = poolKey(cached.imageUrl, lpW, lpH);
            if (LINK_PREVIEW_BITMAP_CACHE.get(poolKey) != null) continue; // already warm in memory
            glide(appCtx).load(cached.imageUrl)
                    .diskCacheStrategy(DiskCacheStrategy.DATA) // bytes only — decode deferred to a real bind
                    .priority(Priority.LOW)                     // never competes with a visible row's own request
                    .preload(lpW, lpH);
        }
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
        return viewTypeOf(getItem(position));
    }

    /**
     * Body of getItemViewType(), split out so neighbor-aware logic (see
     * isGroupAvatarRunTail) can ask "what view type would THAT message get"
     * from a peek()ed Message without a position-based getItem() call.
     *
     * PERF (ultra-advanced pass): the same Message instance gets asked this
     * up to 3x per scroll — its own getItemViewType(), the row ABOVE it
     * peeking ahead in isGroupAvatarRunTail()/willShowGroupSenderAvatar(),
     * and (for the user's own sent rows) canShowSeenBy() on every "seen by"
     * strip rebind. The result is a pure function of this object's own
     * (immutable-once-set — see Message#cachedAdapterViewType's doc) fields,
     * so it's memoized directly on the object: first call runs the full
     * branch chain below, every later call on the SAME instance is a field
     * read. A content change never mutates this instance in place — DiffUtil
     * hands the adapter a fresh Message object instead (see DIFF's
     * areContentsTheSame doc), which starts with a fresh, uncached slot.
     */
    private int viewTypeOf(@Nullable Message m) {
        if (m == null) return TYPE_RECEIVED;
        if (m.cachedAdapterViewType != 0) return m.cachedAdapterViewType;
        int type = computeViewTypeOf(m);
        m.cachedAdapterViewType = type;
        return type;
    }

    /** The actual branch chain — see {@link #viewTypeOf} for the memoizing wrapper. */
    private int computeViewTypeOf(@NonNull Message m) {
        // "security_event" (E2EE security-code-change notice) reuses the
        // same standalone pill-chip rendering as date separators — see
        // ChatMessageSender#insertSecurityEventIfPending. Both are
        // synthetic/local-only rows, never real messages.
        if ("date_separator".equals(m.type) || "security_event".equals(m.type)) return TYPE_DATE_SEPARATOR;
        // Feature 8: a "system" row that's ABOUT a single member (someone
        // joined/left — m.eventUid set at post time) reuses the same
        // standalone pill-chip rendering, now with a small avatar of that
        // member drawn next to the text (see DateSeparatorCanvasView#setAvatar).
        // Every other "system" row (rename, icon change, admin promote,
        // add/remove — no eventUid) is untouched and keeps falling through
        // to the legacy bubble path exactly as before.
        if ("system".equals(m.type) && m.eventUid != null && !m.eventUid.isEmpty()) return TYPE_DATE_SEPARATOR;
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
            // Feature: Voice Caption on Photo — Canvas now draws + hit-tests
            // its own play-badge overlay (MessageBubbleCanvasView#setVoiceCaption
            // / MediaRenderer#drawVoiceBadge), so an image that also carries
            // a short attached voice note (m.voiceUrl != null) no longer
            // needs to fall back to the legacy View-based bubble
            // (content_frame/fl_voice_on_image) — it's fully Canvas-eligible
            // like every other image now.
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

    // ── PERF ADV: static shared RecycledViewPool across chat opens ──────────
    // Previously ChatActivity created `new RecyclerView.RecycledViewPool()`
    // fresh on EVERY chat open, then immediately warmed it with 6 Canvas
    // ViewHolders (each ~30 Paint/TextPaint fields) — a real allocation cost
    // paid again even for reopening the SAME chat in the same process. A
    // single process-wide pool means the second (and every subsequent) chat
    // open in a session finds already-warm Canvas ViewHolders waiting.
    //
    // Correctness note: a pooled Canvas ViewHolder's OnBubbleClickListener
    // is built once (see onCreateViewHolder) and closes over the adapter
    // instance that created it. Reusing the SAME ViewHolder object under a
    // DIFFERENT MessagePagingAdapter (a different chat's activity) would
    // fire the OLD chat's listener if left unchecked — onBindViewHolder's
    // canvasListenerOwner check (see there) detects the mismatch and
    // rebuilds the listener for whichever adapter binds it, so this is
    // handled on the read side. See trimSharedCanvasPool() for the
    // corresponding write-side (leak) safeguard.
    private static RecyclerView.RecycledViewPool sSharedCanvasPool;

    /** Call once, from setupPagingRecyclerView(), instead of `new RecycledViewPool()`. */
    public static RecyclerView.RecycledViewPool getSharedCanvasPool() {
        if (sSharedCanvasPool == null) {
            sSharedCanvasPool = new RecyclerView.RecycledViewPool();
        }
        return sSharedCanvasPool;
    }

    /**
     * Call from ChatActivity.onDestroy(). A closing chat's Canvas
     * ViewHolders may still be sitting in the now-shared pool with their
     * click listener pointing back at THIS (dying) adapter/Activity. Since
     * the pool itself outlives any one chat, those references would
     * otherwise keep this Activity reachable from a static field until some
     * future chat happens to reuse those exact pooled slots. Trimming each
     * canvas type to 0 and immediately back up forces the pool to drop
     * (and let GC reclaim) every currently-held instance right now, instead
     * of leaving that to chance.
     */
    public static void trimSharedCanvasPool() {
        if (sSharedCanvasPool == null) return;
        sSharedCanvasPool.setMaxRecycledViews(TYPE_CANVAS_SENT, 0);
        sSharedCanvasPool.setMaxRecycledViews(TYPE_CANVAS_RECEIVED, 0);
        sSharedCanvasPool.setMaxRecycledViews(TYPE_CANVAS_SENT, 18);
        sSharedCanvasPool.setMaxRecycledViews(TYPE_CANVAS_RECEIVED, 18);
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
        // PERF ADV: pool is now shared/static across chat opens (see
        // getSharedCanvasPool()) — a second+ chat opened in the same
        // process usually finds it already warm from a previous chat.
        // Only top up whatever's actually missing instead of unconditionally
        // creating countPerType MORE holders on top of what's already there.
        int haveSent     = pool.getRecycledViewCount(TYPE_CANVAS_SENT);
        int haveReceived = pool.getRecycledViewCount(TYPE_CANVAS_RECEIVED);
        for (int i = haveSent; i < countPerType; i++) {
            pool.putRecycledView(onCreateViewHolder(parent, TYPE_CANVAS_SENT));
        }
        // Group chats: the received holders are the ones that draw the 20dp
        // sender avatar, and until a photo resolves that's the flat-gray
        // placeholder bitmap. Build the shared placeholder bitmap here, on
        // the pre-warm frame — even when the (shared) pool was already full
        // and no holder gets created below — and pre-set it on every
        // received holder we DO create, so the first unresolved avatar of the
        // first scroll draws with no build/lookup cost. 1:1 chat skips this.
        if (isGroup) {
            com.callx.app.conversation.canvas.MessageBubbleCanvasView
                    .prewarmGroupAvatarPlaceholder(parent.getContext());
        }
        for (int i = haveReceived; i < countPerType; i++) {
            VH vh = onCreateViewHolder(parent, TYPE_CANVAS_RECEIVED);
            if (isGroup && vh.canvasView != null) {
                vh.canvasView.presetGroupSenderAvatarPlaceholder();
            }
            pool.putRecycledView(vh);
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
            // WhatsApp-level fix: OnBubbleClickListener built ONCE per VH,
            // here at creation, instead of a brand-new ~570-line anonymous
            // class on every bindCanvasMessage() call. See
            // createBubbleClickListener() doc for details.
            cv.setOnBubbleClickListener(createBubbleClickListener(vh));
            vh.canvasListenerOwner = this;
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
        // NOTE: ivImage is intentionally NOT forced to maxW here anymore —
        // it now uses wrap_content + adjustViewBounds (see item_message_sent/
        // received.xml) so it sizes itself to the photo's own aspect ratio
        // instead of always rendering as a fixed-width square crop.
        // ── PERF: build the voice-on-image play-badge listener ONCE per VH ──
        // Same pattern as createBubbleClickListener() above (see its comment)
        // — this used to be a fresh lambda captured with (h, m.voiceUrl,
        // position) allocated on EVERY bindVoiceOnImage() call, i.e. every
        // scroll rebind of a combo photo+voice bubble. Reads h.boundMessage
        // + the live adapter position at CLICK time instead (taps are rare,
        // binds are per-frame) — same allocation-avoidance win, and a
        // correctness bonus: the position is never stale after a list
        // reorder, unlike the old captured-at-bind-time int.
        // v425 PERF: the voice-on-image badge click listener is no longer
        // wired here — flVoiceOnImage lives behind a ViewStub now and its
        // listener is attached when ensureLegacyVoiceOverlay() inflates it.
        return vh;
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position,
                                 @NonNull java.util.List<Object> payloads) {
        boolean hasReadByPayload = false;
        boolean hasMemberAvatarPayload = false;
        int handledPayloadCount = 0;
        for (Object payload : payloads) {
            if (PAYLOAD_READ_BY.equals(payload)) {
                hasReadByPayload = true;
                handledPayloadCount++;
            } else if (PAYLOAD_GROUP_SENDER.equals(payload)) {
                hasMemberAvatarPayload = true;
                handledPayloadCount++;
            }
        }
        if (hasReadByPayload || hasMemberAvatarPayload) {
            // The same Firebase snapshot can change a receipt map and a
            // delivery tick. Handle both payloads when RecyclerView merges
            // them instead of letting the first one hide the second.
            Message m = getItem(position);
            if (m != null) {
                if (hasReadByPayload) bindSeenByStrip(h, m, position);
                // Member-photo payload: swap ONLY the 20dp sender-avatar
                // bitmap (invalidate, no re-measure) — see
                // bindGroupSenderOnly().
                if (hasMemberAvatarPayload) bindGroupSenderOnly(h, position, m);
            }
            // Every merged payload was one of the two handled above
            // (duplicates of the same payload included) — nothing left
            // to route, skip the full-bind fallthrough.
            if (handledPayloadCount == payloads.size()) return;
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
        if (!payloads.isEmpty() && PAYLOAD_SELECTION.equals(payloads.get(0))) {
            // Selection changes only affect the holder chrome (alpha +
            // highlight background). Keep the already-bound message content,
            // Glide requests and Canvas layout untouched.
            Message m = getItem(position);
            if (m != null) {
                applySelectionHighlight(h, m);
            }
            return;
        }
        if (!payloads.isEmpty() && PAYLOAD_THEME.equals(payloads.get(0))) {
            Message m = getItem(position);
            if (m != null && h.canvasView != null) {
                // Canvas bubbles can swap their theme paints/drawable without
                // repeating media loads, linkification or measurement.
                h.canvasView.refreshThemeColors();
                applySelectionHighlight(h, m);
            } else {
                // Legacy holders have several theme-dependent child views
                // (polls, replies, status labels, etc.); retain the full bind
                // fallback for correctness, but only for visible/buffered rows.
                onBindViewHolder(h, position);
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

    /**
     * Applies a live status/tick update through the adapter's existing
     * message-id index. A linear snapshot scan is kept inside
     * findMessagePositionById() only as a rare repair path when Paging shifts
     * positions; normal Firebase status callbacks are O(1).
     */
    public boolean updateMessageStatus(String messageId, @Nullable String status) {
        if (messageId == null || messageId.isEmpty() || status == null) return false;
        int position = findMessagePositionById(messageId);
        if (position == RecyclerView.NO_POSITION) return false;
        Message message = getItem(position);
        if (!messageHasId(message, messageId)) return false;
        if (status.equals(message.status)) return true;
        message.status = status;
        notifyItemChanged(position, PAYLOAD_STATUS);
        return true;
    }

    /**
     * Applies a realtime update to an already loaded row without invalidating
     * the whole keyset PagingSource. Firebase sends the complete message node
     * for status/reaction/read-receipt changes, but those changes do not need a
     * new page or a full bubble bind. New/unknown ids return false so the
     * caller can request one structural Paging refresh.
     */
    public boolean applyRealtimeUpdate(@Nullable Message incoming) {
        if (incoming == null) return false;
        String incomingId = incoming.messageId != null
                ? incoming.messageId : incoming.id;
        if (incomingId == null || incomingId.isEmpty()) return false;
        int position = findMessagePositionById(incomingId);
        if (position == RecyclerView.NO_POSITION) return false;
        Message current = getItem(position);
        if (current == null || !messageHasId(current, incomingId)) return false;

        boolean contentChanged =
                !java.util.Objects.equals(current.text, incoming.text)
                || !java.util.Objects.equals(current.type, incoming.type)
                || !java.util.Objects.equals(current.mediaUrl, incoming.mediaUrl)
                || !java.util.Objects.equals(current.thumbnailUrl, incoming.thumbnailUrl)
                || !java.util.Objects.equals(current.caption, incoming.caption)
                || !java.util.Objects.equals(current.deleted, incoming.deleted)
                || !java.util.Objects.equals(current.edited, incoming.edited)
                || !java.util.Objects.equals(current.editedAt, incoming.editedAt);
        boolean statusChanged =
                !java.util.Objects.equals(current.status, incoming.status)
                || !java.util.Objects.equals(current.deliveredAt, incoming.deliveredAt)
                || !java.util.Objects.equals(current.readAt, incoming.readAt);
        boolean reactionsChanged = !java.util.Objects.equals(current.reactions, incoming.reactions);
        boolean readByChanged = !java.util.Objects.equals(current.readBy, incoming.readBy)
                || !java.util.Objects.equals(current.deliveredBy, incoming.deliveredBy);

        // Mutate the object PagingData already exposes. PagingDataAdapter's
        // differ cannot be replaced from here, but a targeted notify is safe
        // and avoids creating a second full Message model for every tick.
        //
        // PERF (ultra-advanced pass) CORRECTNESS NOTE: viewTypeOf()'s memoized
        // cachedAdapterViewType assumes a Message instance's type-relevant
        // fields never change in place — true everywhere EXCEPT here, the one
        // spot in the codebase that mutates an already-bound `current` object
        // instead of DiffUtil handing over a fresh one. `type` and `deleted`
        // both feed computeViewTypeOf(), so the cached slot MUST be reset
        // whenever this runs, or a deleted / retyped message would keep
        // rendering under its old (now-wrong) view type forever.
        current.cachedAdapterViewType = 0;
        current.text = incoming.text;
        current.type = incoming.type;
        current.mediaUrl = incoming.mediaUrl;
        current.thumbnailUrl = incoming.thumbnailUrl;
        current.caption = incoming.caption;
        current.deleted = incoming.deleted;
        current.edited = incoming.edited;
        current.editedAt = incoming.editedAt;
        current.status = incoming.status;
        current.deliveredAt = incoming.deliveredAt;
        current.readAt = incoming.readAt;
        current.reactions = incoming.reactions;
        current.readBy = incoming.readBy;
        current.deliveredBy = incoming.deliveredBy;
        bumpSeenByEpoch(); // v443: readBy replaced in place → cached seen-by selections are stale

        if (contentChanged) {
            notifyItemChanged(position);
        } else {
            int flags = 0;
            if (statusChanged) flags |= FLAG_STATUS;
            if (reactionsChanged) flags |= FLAG_REACTIONS;
            if (readByChanged) notifyItemChanged(position, PAYLOAD_READ_BY);
            if (flags != 0) notifyItemChanged(position, flags);
        }
        // The own row above shows only readers who have NOT read this one —
        // a receipt change here can move a face between the two strips.
        if (readByChanged && isGroup) refreshSeenByOwnRowAbove(position - 1);
        return true;
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

        // A full rebind can happen without RecyclerView calling
        // onViewRecycled() first (for example after a realtime content
        // update). Cancel every reusable bitmap slot here as well, so an old
        // request cannot keep decoding while this holder is rebound.
        h.cancelReusableBitmapTargets(glide(h.itemView.getContext()));
        if (h.imageBindTarget != null) glide(h.itemView.getContext()).clear(h.imageBindTarget);
        if (h.linkPreviewTarget != null) glide(h.itemView.getContext()).clear(h.linkPreviewTarget);

        indexMessagePosition(position, m);
        
        // Store reference for height caching on recycle
        h.boundMessage = m;

        // PERF ADV: cross-adapter pool sharing safety net.
        // createBubbleClickListener() closes over THIS adapter instance
        // (multiSelectMode, selectedMessageIds, actionListener, chatId —
        // all instance fields) and is normally set ONCE at
        // onCreateViewHolder time, never touched again on ordinary binds —
        // that's the whole point of the fix documented there. That's safe
        // as long as a VH only ever gets bound by the SAME adapter that
        // created it.
        // Now that the RecycledViewPool itself is a static/shared instance
        // (see getSharedCanvasPool()) so a freshly-opened chat can reuse
        // another chat's already-warm Canvas ViewHolders instead of paying
        // the ~30-Paint-field allocation again, a VH can arrive here having
        // been created (and last listener-bound) by a DIFFERENT
        // MessagePagingAdapter — a different, possibly-finished
        // ChatActivity. onViewRecycled() already nulls the listener out
        // before a holder goes back into the pool (breaks the reference so
        // the old Activity isn't leaked), so canvasListenerOwner != this
        // is exactly "this holder's listener is missing or stale" — cheap
        // to check, and only ever pays the small re-create cost on that
        // first cross-chat bind, never on ordinary same-chat scrolling.
        if (h.canvasView != null && h.canvasListenerOwner != this) {
            h.canvasView.setOnBubbleClickListener(createBubbleClickListener(h));
            h.canvasListenerOwner = this;
        }

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
            if (h.dateSeparatorView != null) {
                h.dateSeparatorView.setLabel(m.text);
                h.dateSeparatorView.setAvatar(null); // no avatar on these — clears any stale recycled bitmap
            }
            return;
        }
        // ── SYSTEM (join/leave) — same chip row, now with a small avatar of
        // the member the row is about (see viewTypeOf's Feature 8 comment). ──
        if ("system".equals(m.type) && m.eventUid != null && !m.eventUid.isEmpty()) {
            if (h.dateSeparatorView != null) {
                h.dateSeparatorView.setLabel(m.text);
                String photo = m.eventPhoto;
                // v446: L2 hit → set the avatar inline (no stale-clear pass, no tag String concat, no lambda).
                // Tag reset to null so an older in-flight async load for a previous row is ignored.
                if (photo != null && !photo.isEmpty()) {
                    final android.graphics.Bitmap l2 =
                            com.callx.app.cache.ChatAvatarBinder.peekInline(h.itemView.getContext(), photo);
                    if (l2 != null) {
                        h.dateSeparatorView.setTag(null);
                        h.dateSeparatorView.setAvatar(l2);
                        return;
                    }
                }
                h.dateSeparatorView.setAvatar(null); // clear any stale bitmap from a recycled holder first
                if (photo != null && !photo.isEmpty()) {
                    final android.view.View tagTarget = h.dateSeparatorView;
                    final String expectedTag = m.eventUid + "|" + m.messageId;
                    tagTarget.setTag(expectedTag);
                    com.callx.app.cache.ChatAvatarBinder.bindBitmap(h.itemView.getContext(), photo, 0L, resource -> {
                        if (!expectedTag.equals(tagTarget.getTag())) return; // recycled/rebound since this fetch started
                        h.dateSeparatorView.setAvatar(resource);
                    });
                }
            }
            return;
        }
        // ── CANVAS PATH — text/media/contact/location/poll/view-once/
        // seen-system-rows etc. all render through MessageBubbleCanvasView
        // now; checked before any of the legacy per-type branches below so
        // getItemViewType's TYPE_CANVAS_SENT/RECEIVED routing actually
        // takes effect for them (status_seen/reel_seen/view_once included).
        if (h.canvasView != null) {
            applyGroupedSpacing(h, position, m);
            bindCanvasMessage(h, m, position);
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
        bindSeenByStrip(h, m, position);
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
            // FIX (avatar-optimization — reuse core pipeline): was a flat,
            // un-tiered Glide load into the shared AVATAR_BITMAP_CACHE with
            // a hardcoded 96×96 override — now routed through
            // ChatAvatarBinder.bind() at SEEN_AVATAR_TIER (36dp, matches
            // the canvas seen-bubble fix above), so this shares
            // ChatAvatarL2Cache/L3 + CDN analytics with every other chat
            // avatar surface instead of its own private pool.
            String photo = m.senderPhoto != null ? m.senderPhoto : "";
            com.callx.app.cache.ChatAvatarBinder.bind(ctx, ivAvatar, photo, 0L,
                    R.drawable.ic_person, SEEN_AVATAR_TIER);
        }

        // Status thumbnail
        android.view.View flThumb = h.flStatusSeenThumb;
        android.widget.ImageView ivThumb = h.ivStatusSeenThumb;
        android.widget.ImageView ivEye   = h.ivStatusSeenEye;
        if (ivThumb != null && flThumb != null) {
            String thumbB64 = m.statusThumbBase64;
            String thumb = m.statusThumbUrl != null ? m.statusThumbUrl : "";
            if (thumbB64 != null && !thumbB64.isEmpty()) {
                // WhatsApp-level: this bubble carries its own copy of the
                // thumbnail (embedded at write time — see
                // StatusSeenTracker#doWriteSeenBubble via ThumbnailEmbedder),
                // so it renders straight from local bytes: no network call,
                // unaffected by the original status later expiring or being
                // deleted. Same in-memory pool as the URL path so repeat
                // rebinds don't re-decode the same JPEG.
                flThumb.setVisibility(View.VISIBLE);
                if (ivEye != null) ivEye.setVisibility(View.VISIBLE);
                // PERF: decode at the real 120×80dp display size (seenThumbPxW/H,
                // same fix already applied to the canvas-based bindSeenBubble()
                // path) instead of a hardcoded 240×240 square — that was
                // 2x-oversized on width and ~3x-oversized on height.
                final int seenThumbPxW = seenThumbPxW(ctx);
                final int seenThumbPxH = seenThumbPxH(ctx);
                String b64PoolKey = poolKey("b64:" + thumbB64.hashCode(), seenThumbPxW, seenThumbPxH);
                ivThumb.setImageResource(R.drawable.bg_skeleton_rect);
                decodeB64ThumbAsync(thumbB64, b64PoolKey, SEEN_THUMB_BITMAP_CACHE, decoded -> {
                    if (h.getBindingAdapterPosition() == RecyclerView.NO_POSITION) return;
                    if (decoded != null) {
                        ivThumb.setImageBitmap(decoded);
                    } else {
                        ivThumb.setImageResource(R.drawable.bg_skeleton_rect);
                    }
                });
            } else if (!thumb.isEmpty()) {
                flThumb.setVisibility(View.VISIBLE);
                if (ivEye != null) ivEye.setVisibility(View.VISIBLE);
                // PERF: same density-aware sizing as the b64 branch above.
                final int seenThumbPxW = seenThumbPxW(ctx);
                final int seenThumbPxH = seenThumbPxH(ctx);
                // Same fix as the avatar above — sync seen-thumb pool
                // check before falling back to an async load.
                android.graphics.Bitmap statusThumbHit = SEEN_THUMB_BITMAP_CACHE.get(poolKey(thumb, seenThumbPxW, seenThumbPxH));
                if (statusThumbHit != null && !statusThumbHit.isRecycled()) {
                    dashboardRecordHit(ctx, thumb);
                    ivThumb.setImageBitmap(statusThumbHit);
                } else {
                    ivThumb.setImageResource(R.drawable.bg_skeleton_rect);
                    glide(ctx).asBitmap()
                        .load(thumb)
                        .apply(THUMB_RGB565)
                        .override(seenThumbPxW, seenThumbPxH)
                        .centerCrop()
                        .listener(com.callx.app.cache.CacheDashboardStats.glideListener(
                                ctx, thumb))
                        .into(h.prepareBitmapTarget(glide(ctx), TARGET_STATUS_SEEN,
                                new BitmapReadyCallback() {
                            @Override public void onReady(@NonNull Bitmap resource) {
                                SEEN_THUMB_BITMAP_CACHE.put(poolKey(thumb, seenThumbPxW, seenThumbPxH), resource);
                                dashboardRecordDecoded(ctx, thumb, resource);
                                if (h.getBindingAdapterPosition() == RecyclerView.NO_POSITION) return;
                                ivThumb.setImageBitmap(resource);
                            }
                        }, null));
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
            // FIX (avatar-optimization — reuse core pipeline): same fix as
            // bindStatusSeenBubble above — routed through
            // ChatAvatarBinder.bind() at SEEN_AVATAR_TIER instead of a flat
            // 96×96 load into the private AVATAR_BITMAP_CACHE.
            String photo = m.senderPhoto != null ? m.senderPhoto : "";
            com.callx.app.cache.ChatAvatarBinder.bind(ctx, ivAvatar, photo, 0L,
                    R.drawable.ic_person, SEEN_AVATAR_TIER);
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
            String thumbB64 = m.reelThumbBase64;
            String thumb = m.reelThumbUrl != null ? m.reelThumbUrl : "";
            if (thumbB64 != null && !thumbB64.isEmpty()) {
                // WhatsApp-level: this bubble carries its own copy of the
                // thumbnail (embedded at write time — see
                // ReelSeenTracker#doWriteNew/updateExistingBubble via
                // ThumbnailEmbedder), so it renders straight from local
                // bytes: no network call, unaffected by the reel later
                // expiring or being deleted. Same in-memory pool as the URL
                // path so repeat rebinds don't re-decode the same JPEG.
                ivThumb.setVisibility(android.view.View.VISIBLE);
                if (ivPlay != null) ivPlay.setVisibility(android.view.View.VISIBLE);
                // PERF: decode at the real 120×80dp display size (seenThumbPxW/H,
                // same fix already applied to the canvas-based bindSeenBubble()
                // path) instead of a hardcoded 240×240 square.
                final int seenThumbPxW = seenThumbPxW(ctx);
                final int seenThumbPxH = seenThumbPxH(ctx);
                String b64PoolKey = poolKey("b64:" + thumbB64.hashCode(), seenThumbPxW, seenThumbPxH);
                ivThumb.setImageResource(R.drawable.bg_skeleton_rect);
                decodeB64ThumbAsync(thumbB64, b64PoolKey, SEEN_THUMB_BITMAP_CACHE, decoded -> {
                    if (h.getBindingAdapterPosition() == RecyclerView.NO_POSITION) return;
                    if (decoded != null) {
                        ivThumb.setImageBitmap(decoded);
                    } else {
                        ivThumb.setImageResource(R.drawable.bg_skeleton_rect);
                    }
                });
            } else if (!thumb.isEmpty()) {
                ivThumb.setVisibility(android.view.View.VISIBLE);
                if (ivPlay != null) ivPlay.setVisibility(android.view.View.VISIBLE);
                // PERF: same density-aware sizing as the b64 branch above.
                final int seenThumbPxW = seenThumbPxW(ctx);
                final int seenThumbPxH = seenThumbPxH(ctx);
                // Same fix — sync seen-thumb pool check before an
                // async load, same as the status-seen thumb above.
                android.graphics.Bitmap reelSeenThumbHit = SEEN_THUMB_BITMAP_CACHE.get(poolKey(thumb, seenThumbPxW, seenThumbPxH));
                if (reelSeenThumbHit != null && !reelSeenThumbHit.isRecycled()) {
                    ivThumb.setImageBitmap(reelSeenThumbHit);
                } else {
                    ivThumb.setImageResource(R.drawable.bg_skeleton_rect);
                    glide(ctx).asBitmap()
                        .load(thumb)
                        .apply(THUMB_RGB565)
                        .override(seenThumbPxW, seenThumbPxH)
                        .centerCrop()
                        .into(h.prepareBitmapTarget(glide(ctx), TARGET_REEL_SEEN,
                                new BitmapReadyCallback() {
                            @Override public void onReady(@NonNull Bitmap resource) {
                                SEEN_THUMB_BITMAP_CACHE.put(poolKey(thumb, seenThumbPxW, seenThumbPxH), resource);
                                if (h.getBindingAdapterPosition() == RecyclerView.NO_POSITION) return;
                                ivThumb.setImageBitmap(resource);
                            }
                        }, null));
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
                || "view_once".equals(type) || "system".equals(type);
    }

    /**
     * WhatsApp-style "same-uid consecutive skip": in a run of consecutive
     * messages from one sender, the 20dp avatar belongs on the LAST bubble
     * of the run only. True when the row at {@code position} is that tail —
     * i.e. it's the newest row, or the next row is a different sender / a
     * system row / a row that will not draw a group avatar itself (a legacy
     * non-canvas bubble has no avatar, so the canvas row before it must
     * keep the run's avatar or the run would end up with none).
     *
     * Uses peek() (never getItem()) so asking about position+1 can't trigger
     * Paging load hints. An unloaded neighbor (null) counts as "tail" —
     * showing an avatar too often is harmless, dropping it isn't.
     *
     * NOTE this depends on the NEXT row, which DiffUtil never rebinds when
     * only its neighbor changed — see the AdapterDataObserver in the
     * constructor (refreshGroupSenderRow), which re-evaluates the row
     * before every insert/remove via PAYLOAD_GROUP_SENDER.
     */
    private boolean isGroupAvatarRunTail(int position, @NonNull Message m) {
        int next = position + 1;
        if (next >= getItemCount()) return true;
        Message nm = peek(next);
        if (nm == null || nm.senderId == null || m.senderId == null) return true;
        if (!nm.senderId.equals(m.senderId)) return true;
        if (isNonGroupingRow(nm.type)) return true;
        return !willShowGroupSenderAvatar(nm);
    }

    /** Mirrors bindCanvasMessage()'s avatar gate (received + group + a
     *  sender name) AND that the row actually renders on the canvas path. */
    private boolean willShowGroupSenderAvatar(@NonNull Message nm) {
        if (!isGroup) return false;
        if (currentUid != null && currentUid.equals(nm.senderId)) return false;
        if (nm.senderName == null || nm.senderName.isEmpty()) return false;
        return viewTypeOf(nm) == TYPE_CANVAS_RECEIVED;
    }

    /**
     * WhatsApp-style name gating: the sender NAME is shown only on the FIRST
     * bubble of a run of consecutive messages from one sender (mirror image
     * of isGroupAvatarRunTail, which puts the avatar on the LAST). True when
     * the row at {@code position} starts a run: it's the oldest loaded row,
     * or the row above is a different sender / a system row (date
     * separator etc.) / a broadcast message / has no name of its own.
     *
     * Broadcast messages are handled by the caller (they always show their
     * name, since it carries the per-message 📢 badge) and also break a run
     * on either side, so a broadcast never hides — or hides under — a
     * neighbor's name.
     *
     * peek(), not getItem(): no Paging load hints. An unloaded row above
     * (null) counts as "head" — showing a name too often is harmless, and
     * when the older page later loads the observer re-evaluates this row.
     */
    private boolean isGroupNameRunHead(int position, @NonNull Message m) {
        if (position <= 0) return true;
        Message prev = peek(position - 1);
        if (prev == null || prev.senderId == null || m.senderId == null) return true;
        if (!prev.senderId.equals(m.senderId)) return true;
        if (isNonGroupingRow(prev.type)) return true;
        if (Boolean.TRUE.equals(prev.broadcast)) return true;
        return prev.senderName == null || prev.senderName.isEmpty();
    }

    /** The sender-name string for a received group row — 📢 prefix for a broadcast. */
    private static String groupSenderLabel(@NonNull Message m) {
        return Boolean.TRUE.equals(m.broadcast) ? "\uD83D\uDCE2 " + m.senderName : m.senderName;
    }

    /** Re-evaluate one row's sender name + avatar run state (payload path, no full bind). */
    private void refreshGroupSenderRow(int pos) {
        if (pos < 0) return;
        RecyclerView rv = attachedRecyclerView;
        if (rv == null) return; // nothing bound yet — first bind computes it fresh
        if (rv.isComputingLayout()) {
            rv.post(() -> refreshGroupSenderRow(pos));
            return;
        }
        if (pos >= getItemCount()) return;
        notifyItemChanged(pos, PAYLOAD_GROUP_SENDER);
    }

    // WhatsApp-level fix: this OnBubbleClickListener used to be a brand-new
    // anonymous class (15 overridden methods, ~570 lines of bytecode)
    // allocated on EVERY single bindCanvasMessage() call — i.e. on every
    // bind of every canvas-rendered bubble, which is now almost every
    // message in the chat (see isCanvasEligible()). That's the real
    // "chat item allocates on every open/bind" hot spot — far bigger than
    // the legacy bindMessage() view-based listeners, which isCanvasEligible()
    // routes around for the overwhelming majority of messages.
    //
    // Fix: build this listener exactly ONCE per VH, at onCreateViewHolder
    // time (see TYPE_CANVAS_SENT/TYPE_CANVAS_RECEIVED branch), same pattern
    // ChatListAdapter's installStaticListeners() uses. Every method below
    // reads the CURRENT message off h.boundMessage (already kept up to date
    // by onBindViewHolder — see "Store reference for height caching on
    // recycle") and re-derives whatever per-message state it needs (type
    // flags, sent/received, the current canvasBindToken) at CLICK time
    // instead of at BIND time — clicks are rare, binds happen on every
    // scroll frame, so recomputing a few booleans per tap is free while
    // skipping a 570-line object allocation per scroll-bound row is not.
    private com.callx.app.conversation.canvas.OnBubbleClickListener createBubbleClickListener(final VH h) {
        return new com.callx.app.conversation.canvas.OnBubbleClickListener() {
            @Override
            public void onBubbleClick() {
                Message m = h.boundMessage;
                if (m == null) return;
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
                Message m = h.boundMessage;
                if (m == null) return;
                Context ctx = h.itemView.getContext();
                com.callx.app.conversation.canvas.MessageBubbleCanvasView cv = h.canvasView;
                cv.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                if (!multiSelectMode) {
                    enterMultiSelectMode(m);
                    if (actionListener != null) showActionBottomSheet(ctx, m);
                } else {
                    h.itemView.performClick();
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
                Context ctx = h.itemView.getContext();
                android.content.Intent browserIntent = new android.content.Intent(
                        android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url));
                browserIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(browserIntent);
                return true;
            }

            @Override
            public void onReplyPreviewClick() {
                Message m = h.boundMessage;
                if (m == null) return;
                if (actionListener != null && m.replyToId != null) {
                    actionListener.onNavigateToOriginal(m.replyToId, m.senderId);
                }
            }

            @Override
            public void onReactionsClick() {
                Message m = h.boundMessage;
                if (m == null) return;
                if (actionListener != null) actionListener.onReactionTap(m);
            }

            @Override
            public void onForwardClick() {
                Message m = h.boundMessage;
                if (m == null) return;
                if (actionListener != null) actionListener.onForward(m);
            }

            @Override
            public void onGroupSenderAvatarClick() {
                Message m = h.boundMessage;
                if (m == null) return;
                if (actionListener != null) actionListener.onGroupSenderAvatarClick(m);
            }

            @Override
            public void onGroupSenderAvatarLongClick() {
                Message m = h.boundMessage;
                if (m == null) return;
                if (actionListener != null) actionListener.onGroupSenderAvatarLongClick(m);
            }

            @Override
            public void onPollVotersClick() {
                Message m = h.boundMessage;
                if (m == null || multiSelectMode) return;
                if (actionListener != null) actionListener.onPollVotersTap(m);
            }

            @Override
            public void onSeenByClick() {
                Message m = h.boundMessage;
                if (m == null || multiSelectMode) return;
                if (seenByClickListener != null) seenByClickListener.accept(m);
            }

            @Override
            public void onImageClick() {
                final Message m = h.boundMessage;
                if (m == null) return;
                final Context ctx = h.itemView.getContext();
                final com.callx.app.conversation.canvas.MessageBubbleCanvasView cv = h.canvasView;
                final boolean sent = currentUid != null && currentUid.equals(m.senderId);
                final String type = m.type != null ? m.type : "text";
                final boolean isImage = "image".equals(type);
                final boolean isVideo = "video".equals(type);
                final boolean isReelShare = "reel_share".equals(type) || "reel_link".equals(type);
                final int myToken = h.canvasBindToken;
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
                            : getCachedFileFast(ctx, vUrl2);

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
                                    MediaDownloadQueue.getInstance(ctx).markComplete(vUrl2);
                                    downloadingMediaUrls.remove(vUrl2);
                                    if (h.canvasBindToken != myToken) return;
                                    cv.clearMediaDownloadGate();
                                    ((android.app.Activity) ctx).runOnUiThread(() -> {
                                        // Telegram-style chat-wide gallery —
                                        // see openChatMediaViewer's doc.
                                        openChatMediaViewer(ctx, chatId,
                                                m.messageId != null ? m.messageId : m.id, -1,
                                                vUrl2, vUrl2, "video", null,
                                                file.getAbsolutePath(), null,
                                                cv.getMediaRectOnScreen(),
                                                currentUid != null && currentUid.equals(m.senderId));
                                    });
                                }
                                @Override public void onError(String reason) {
                                    MediaDownloadQueue.getInstance(ctx).markComplete(vUrl2);
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
                Message m = h.boundMessage;
                if (m == null) return;
                Context ctx = h.itemView.getContext();
                String type = m.type != null ? m.type : "text";
                boolean isReelShare = "reel_share".equals(type) || "reel_link".equals(type);
                if (isReelShare) {
                    ReelSharePeekBridge.show(ctx, m, sourceView);
                }
            }

            @Override
            public void onMediaDownloadClick() {
                final Message m = h.boundMessage;
                if (m == null) return;
                final Context ctx = h.itemView.getContext();
                final com.callx.app.conversation.canvas.MessageBubbleCanvasView cv = h.canvasView;
                final boolean sent = currentUid != null && currentUid.equals(m.senderId);
                final String type = m.type != null ? m.type : "text";
                final boolean isImage = "image".equals(type);
                final boolean isVideo = "video".equals(type);
                final boolean isGif = "gif".equals(type);
                final int myToken = h.canvasBindToken;
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
                            // PERF/UX: explicit user tap on the download gate =
                            // the same "manual save" moment WhatsApp/Telegram use
                            // — mirror the cached file into the public Gallery
                            // (Pictures/CallX) too. Fire-and-forget; failure here
                            // must never affect the in-chat render below.
                            com.callx.app.utils.MediaSaveHelper.save(ctx, file, "gif", gifUrl,
                                    new com.callx.app.utils.MediaSaveHelper.Callback() {
                                        @Override public void onSaved(android.net.Uri uri) {}
                                        @Override public void onError(String reason) {}
                                    });
                            if (h.canvasBindToken != myToken) return;
                            cv.clearMediaDownloadGate();
                            glide(ctx).asBitmap().load(file).apply(THUMB_RGB565)
                                    .override(gifStickerPx(ctx), gifStickerPx(ctx)) // PERF: match 180dp slot, avoid oversized decode
                                    .into(h.prepareBitmapTarget(glide(ctx), TARGET_CANVAS_PRIMARY,
                                            new BitmapReadyCallback() {
                                        @Override public void onReady(@NonNull android.graphics.Bitmap bmp) {
                                            if (h.canvasBindToken != myToken) return;
                                            cv.setGifBitmap(bmp);
                                        }
                                    }, null));
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
                                MediaDownloadQueue.getInstance(ctx).markComplete(vDlUrl);
                                downloadingMediaUrls.remove(vDlUrl);
                                // PERF/UX: see matching comment on the GIF path above.
                                com.callx.app.utils.MediaSaveHelper.save(ctx, file, "video", vDlUrl,
                                        new com.callx.app.utils.MediaSaveHelper.Callback() {
                                            @Override public void onSaved(android.net.Uri uri) {}
                                            @Override public void onError(String reason) {}
                                        });
                                if (h.canvasBindToken != myToken) return;
                                cv.clearMediaDownloadGate();
                                ((android.app.Activity) ctx).runOnUiThread(() -> {
                                    // Telegram-style chat-wide gallery —
                                    // see openChatMediaViewer's doc.
                                    openChatMediaViewer(ctx, chatId,
                                            m.messageId != null ? m.messageId : m.id, -1,
                                            vDlUrl, vDlUrl, "video", null,
                                            file.getAbsolutePath(), null,
                                            cv.getMediaRectOnScreen(),
                                            currentUid != null && currentUid.equals(m.senderId));
                                });
                            }
                            @Override public void onError(String reason) {
                                MediaDownloadQueue.getInstance(ctx).markComplete(vDlUrl);
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
                // Progressive-JPEG sharpen-while-downloading (see
                // CloudinaryUploader#deriveProgressiveFullUrl): only for
                // plaintext images (tapDlKey null) — a Media-E2E fullUrl is
                // ciphertext, not something Cloudinary can re-transform.
                // fullUrl itself stays untouched as the cache/dedupe key
                // (see MediaCache#getWithProgress's cacheKeyUrl/fetchUrl doc)
                // so nothing else in the file that looks this image up by
                // fullUrl (pool, MediaCache.getCached, the viewer intent...)
                // ever sees a mismatch.
                final String tapFetchUrl = (tapDlKey == null)
                        ? com.callx.app.utils.CloudinaryUploader.deriveProgressiveFullUrl(fullUrl)
                        : fullUrl;
                // Advance #5: race a tiny HTTP Range preview (first ~28KB)
                // against the real download below — plaintext images only
                // (tapDlKey == null), same gating as the progressive-JPEG
                // transform itself. fullyLoaded flags once onReady (or a
                // later, sharper 20/45/70% partial) has already applied a
                // better frame, so a slow-to-decode early preview can never
                // clobber something sharper that already landed.
                final boolean[] tapFullyLoaded = {false};
                if (tapDlKey == null) {
                    com.callx.app.utils.MediaCache.fetchEarlyPreview(tapFetchUrl, partial -> {
                        if (h.canvasBindToken != myToken || tapFullyLoaded[0]) return;
                        cv.setMediaBitmap(partial);
                    });
                }
                com.callx.app.utils.MediaCache.getWithProgress(ctx, fullUrl, tapFetchUrl, tapDlKey, tapDlDigest,
                        new com.callx.app.utils.MediaCache.ProgressCallback() {
                    @Override public void onProgress(int percent) {
                        if (h.canvasBindToken != myToken) return;
                        cv.setMediaDownloadProgress(percent);
                    }
                    @Override public void onPartialBitmap(android.graphics.Bitmap partial) {
                        if (h.canvasBindToken != myToken) return;
                        tapFullyLoaded[0] = true;
                        // Coarse-to-sharp in-place preview while still
                        // downloading — the gate/percentage overlay stays up
                        // (cleared only in onReady below) on top of it.
                        cv.setMediaBitmap(partial);
                    }
                    @Override public void onReady(java.io.File file) {
                        downloadingMediaUrls.remove(fullUrl);
                        tapFullyLoaded[0] = true;
                        // PERF/UX: see matching comment on the GIF path above.
                        com.callx.app.utils.MediaSaveHelper.save(ctx, file, "image", fullUrl,
                                new com.callx.app.utils.MediaSaveHelper.Callback() {
                                    @Override public void onSaved(android.net.Uri uri) {}
                                    @Override public void onError(String reason) {}
                                });
                        if (h.canvasBindToken != myToken) return;
                        cv.clearMediaDownloadGate();
                        // PERF #4 + #1: density-aware size, store in pool on decode
                        glide(ctx).asBitmap().load(file).apply(THUMB_RGB565)
                                .override(thumbPx(ctx), thumbPx(ctx))
                                .into(h.prepareBitmapTarget(glide(ctx), TARGET_CANVAS_PRIMARY,
                                        new BitmapReadyCallback() {
                                    @Override public void onReady(@NonNull Bitmap resource) {
                                        if (resource.getHeight() > 0) {
                                            com.callx.app.conversation.canvas.MessageBubbleCanvasView
                                                    .cacheAspectRatio(fullUrl, (float) resource.getWidth() / resource.getHeight());
                                        }
                                        // PERF #1: pool the decoded bitmap
                                        if (fullUrl != null && !fullUrl.isEmpty())
                                            MEDIA_BITMAP_CACHE.put(fullUrl, resource);
                                        if (h.canvasBindToken != myToken) return;
                                        cv.setMediaBitmap(resource);
                                    }
                                }, null));
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
                Message m = h.boundMessage;
                if (m == null) return;
                Context ctx = h.itemView.getContext();
                String type = m.type != null ? m.type : "text";
                boolean isMultiMedia = "multi_media".equals(type);
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
                Message m = h.boundMessage;
                if (m == null) return;
                Context ctx = h.itemView.getContext();
                com.callx.app.conversation.canvas.MessageBubbleCanvasView cv = h.canvasView;
                String type = m.type != null ? m.type : "text";
                boolean isMultiMedia = "multi_media".equals(type);
                int myToken = h.canvasBindToken;
                if (!isMultiMedia || m.mediaItems == null) return;
                int visible = Math.min(m.mediaItems.size(), 9);
                final int totalForSize = m.mediaItems.size();
                for (int i = 0; i < visible; i++) {
                    downloadGroupCell(ctx, h, cv, myToken, m.mediaItems.get(i), i, totalForSize);
                }
            }

            @Override
            public void onGroupCellDownloadClick(int index) {
                Message m = h.boundMessage;
                if (m == null) return;
                Context ctx = h.itemView.getContext();
                com.callx.app.conversation.canvas.MessageBubbleCanvasView cv = h.canvasView;
                String type = m.type != null ? m.type : "text";
                boolean isMultiMedia = "multi_media".equals(type);
                int myToken = h.canvasBindToken;
                if (!isMultiMedia || m.mediaItems == null || index < 0 || index >= m.mediaItems.size()) return;
                downloadGroupCell(ctx, h, cv, myToken, m.mediaItems.get(index), index, m.mediaItems.size());
            }

            @Override
            public void onAudioPlayPauseClick() {
                Message m = h.boundMessage;
                if (m == null) return;
                String type = m.type != null ? m.type : "text";
                boolean isAudio = "audio".equals(type);
                if (!isAudio) return;
                String aUrl = m.mediaUrl != null ? m.mediaUrl : m.text;
                toggleAudio(h, aUrl, h.getAdapterPosition());
            }

            @Override
            public void onVoiceCaptionPlayPauseClick() {
                // Feature: Voice Caption on Photo (Canvas) — mirrors
                // onAudioPlayPauseClick() above, just for the play-badge
                // overlaid on an image bubble instead of a standalone
                // audio bubble. toggleAudio()/setPlayPauseIcon() are both
                // already type-agnostic (see setPlayPauseIcon's javadoc),
                // so no other wiring is needed.
                Message m = h.boundMessage;
                if (m == null || m.voiceUrl == null || m.voiceUrl.isEmpty()) return;
                toggleAudio(h, m.voiceUrl, h.getAdapterPosition());
            }

            @Override
            public void onVoiceCaptionSpeedClick() {
                // Feature: Playback speed on the voice caption badge —
                // mirrors the standalone audio bubble's btnAudioSpeed chip
                // (1x → 1.5x → 2x → 0.5x → 1x). currentPlaybackSpeed is the
                // SAME shared field that chip uses, so speed picked here
                // also applies if the user later plays a standalone voice
                // note in this same chat session — same one-speed-at-a-time
                // precedent the legacy chip already has.
                Message m = h.boundMessage;
                if (m == null || m.voiceUrl == null || m.voiceUrl.isEmpty()) return;
                if      (currentPlaybackSpeed == 1.0f)  currentPlaybackSpeed = 1.5f;
                else if (currentPlaybackSpeed == 1.5f)  currentPlaybackSpeed = 2.0f;
                else if (currentPlaybackSpeed == 2.0f)  currentPlaybackSpeed = 0.5f;
                else                                     currentPlaybackSpeed = 1.0f;
                String label = (currentPlaybackSpeed == 0.5f) ? "0.5×"
                             : (currentPlaybackSpeed == 1.0f) ? "1×"
                             : (currentPlaybackSpeed == 1.5f) ? "1.5×" : "2×";
                if (h.canvasView != null) h.canvasView.setVoiceSpeedLabel(label);
                // Apply immediately only if THIS message's clip is the one
                // currently playing — otherwise the label change is purely
                // cosmetic until playback actually starts (which resets it
                // to 1x anyway — see playAudioFromPath's onPreparedListener).
                boolean isThisPlaying = playingPos == h.getAdapterPosition() && player != null;
                if (isThisPlaying && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    try {
                        android.media.PlaybackParams pp = new android.media.PlaybackParams();
                        pp.setSpeed(currentPlaybackSpeed);
                        player.setPlaybackParams(pp);
                    } catch (Exception ignored) {}
                }
            }

            @Override
            public void onVoiceCaptionDownloadClick() {
                // Feature: Save-audio button — saves ONLY the attached
                // voice clip to the device (MediaStore, "Music/CallX2"),
                // independent of the photo (which already has its own Save
                // via MediaViewerActivity's "more options" menu). Mirrors
                // that same Save-to-gallery pattern, just for audio and
                // callable straight from the chat bubble instead of the
                // fullscreen viewer.
                Message m = h.boundMessage;
                if (m == null || m.voiceUrl == null || m.voiceUrl.isEmpty()) return;
                saveVoiceCaptionToDevice(h.itemView.getContext(), m);
            }

            @Override
            public void onAudioSeek(float fraction) {
                Message m = h.boundMessage;
                if (m == null) return;
                String type = m.type != null ? m.type : "text";
                boolean isAudio = "audio".equals(type);
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
                Message m = h.boundMessage;
                if (m == null) return;
                Context ctx = h.itemView.getContext();
                String type = m.type != null ? m.type : "text";
                boolean isContact = "contact".equals(type);
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
                Message m = h.boundMessage;
                if (m == null) return;
                Context ctx = h.itemView.getContext();
                String type = m.type != null ? m.type : "text";
                boolean isLocation = "location".equals(type);
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
                Message m = h.boundMessage;
                if (m == null) return;
                com.callx.app.conversation.canvas.MessageBubbleCanvasView cv = h.canvasView;
                boolean sent = currentUid != null && currentUid.equals(m.senderId);
                boolean isViewOnceMsg = Boolean.TRUE.equals(m.viewOnce);
                boolean isViewOnceExpiredState = isViewOnceMsg
                        && com.callx.app.conversation.controllers.ChatViewOnceController.isExpired(m);
                boolean isViewOnceWaiting = isViewOnceMsg && !isViewOnceExpiredState && sent;
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
                Message m = h.boundMessage;
                if (m == null) return;
                Context ctx = h.itemView.getContext();
                boolean isReelSeen = "reel_seen".equals(m.type);
                boolean isStatusSeen = "status_seen".equals(m.type);
                boolean isSeen = isStatusSeen || isReelSeen;
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
                Message m = h.boundMessage;
                if (m == null) return;
                Context ctx = h.itemView.getContext();
                com.callx.app.conversation.canvas.MessageBubbleCanvasView cv = h.canvasView;
                String type = m.type != null ? m.type : "text";
                boolean isGif = "gif".equals(type);
                if (!isGif) return;
                final String gifUrl = m.mediaUrl != null ? m.mediaUrl : "";
                android.content.Intent i = new android.content.Intent().setClassName(
                        ctx.getPackageName(), "com.callx.app.activities.MediaViewerActivity");
                i.putExtra("url", gifUrl);
                i.putExtra("type", "gif");
                i.putExtra("gifIsVideo", Boolean.TRUE.equals(m.gifIsVideo));
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
                final Message m = h.boundMessage;
                if (m == null) return;
                final Context ctx = h.itemView.getContext();
                final com.callx.app.conversation.canvas.MessageBubbleCanvasView cv = h.canvasView;
                String type = m.type != null ? m.type : "text";
                boolean isFile = "file".equals(type);
                final int myToken = h.canvasBindToken;
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
                Message m = h.boundMessage;
                if (m == null) return;
                Context ctx = h.itemView.getContext();
                String type = m.type != null ? m.type : "text";
                boolean isFile = "file".equals(type);
                if (!isFile) return;
                final String fileUrl = m.mediaUrl != null ? m.mediaUrl : "";
                java.io.File cached = MediaCache.getCached(ctx, fileUrl);
                if (cached == null) return;
                try {
                    final String fileNameForOpen = m.fileName != null ? m.fileName : "File";
                    final String mimeForOpen = guessMimeFromFileName(fileNameForOpen);
                    android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                            ctx, ctx.getPackageName() + ".fileprovider", cached);

                    // WhatsApp-level: .txt files open in an in-app reader
                    // instead of bouncing out to an external app chooser.
                    if ("text/plain".equalsIgnoreCase(mimeForOpen)
                            || fileNameForOpen.toLowerCase(java.util.Locale.ROOT).endsWith(".txt")) {
                        com.callx.app.conversation.TextFileViewerActivity.start(
                                ctx, uri, fileNameForOpen);
                        return;
                    }

                    android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW);
                    intent.setDataAndType(uri, mimeForOpen.isEmpty() ? "*/*" : mimeForOpen);
                    intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        ctx.startActivity(intent);
                    } catch (android.content.ActivityNotFoundException noApp) {
                        // No app can handle this mime type — offer a chooser so the
                        // user isn't left with a silent no-op tap (WhatsApp shows
                        // "No application can open this file" + a chooser prompt).
                        try {
                            android.content.Intent chooser = android.content.Intent.createChooser(
                                    intent, "Open " + fileNameForOpen + " with");
                            chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                            ctx.startActivity(chooser);
                        } catch (Exception ignored2) {
                            android.widget.Toast.makeText(ctx,
                                    "No app found to open this file", android.widget.Toast.LENGTH_SHORT).show();
                        }
                    }
                } catch (Exception ignored) { /* no app handles this type */ }
            }

            @Override
            public void onEditedTagClick() {
                // Mirrors the legacy tv_time click listener — tapping the
                // "✏️ edited" tag opens the edit-history sheet.
                Message m = h.boundMessage;
                if (m == null) return;
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
                Message m = h.boundMessage;
                if (m == null) return;
                Context ctx = h.itemView.getContext();
                String type = m.type != null ? m.type : "text";
                boolean isPoll = "poll".equals(type);
                if (!isPoll) return;
                if (Boolean.TRUE.equals(m.pollClosed)) {
                    android.widget.Toast.makeText(ctx, "This poll is closed", android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                if (actionListener != null) actionListener.onPollVote(m, optionIndex);
            }
        };
    }


    private void bindCanvasMessage(@NonNull VH h, @NonNull Message m, int position) {
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
            // WhatsApp-level: prefer the self-contained embedded copy (see
            // ThumbnailEmbedder / ReelSeenTracker / StatusSeenTracker) —
            // renders from local bytes, unaffected by the source reel/status
            // later expiring or being deleted. thumbUrl above stays as the
            // fallback for older messages that only carry a URL.
            final String thumbB64 = isReelSeen ? m.reelThumbBase64 : m.statusThumbBase64;
            final boolean hasThumbB64 = thumbB64 != null && !thumbB64.isEmpty();
            final boolean hasThumb = hasThumbB64 || !thumbUrl.isEmpty();
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
                // FIX (avatar-optimization — reuse core pipeline): was a
                // flat, un-tiered Glide load into this adapter's own
                // private AVATAR_BITMAP_CACHE — completely disconnected
                // from ChatAvatarBinder's ChatAvatarL2Cache/L3 + CDN
                // analytics every other chat avatar surface shares (same
                // fix already applied to the contact-share bubble above).
                // No avatarVersion tracked for senderPhoto, so unversioned
                // (0L), same as that fix. SEEN_AVATAR_TIER (36dp) keeps the
                // real display size instead of TIER_INLINE's default 24dp.
                com.callx.app.cache.ChatAvatarBinder.bindBitmap(ctx, avatarUrl, 0L, SEEN_AVATAR_TIER, resource -> {
                    if (h.canvasBindToken != myToken) return;
                    cv.setSeenAvatarBitmap(resource);
                });
            }
            if (hasThumb) {
                // FIX: same root cause as the reel-share 330×474 thumb flicker —
                // no cache check meant EVERY bind (including a plain scroll-recycle,
                // and every rebind of an on-screen seen-bubble row that a new
                // message send/receive elsewhere in the list triggers) unconditionally
                // re-fired an async Glide load, guaranteeing a blank/junk frame on
                // this thumbnail before the image popped back in. Now checks
                // Seen-thumb pool synchronously first, same as the other
                // seen-bubble path.
                // PERF: decode at the real 120×80dp display size (see
                // seenThumbPxW/H()) instead of a hardcoded 240×240 square —
                // that was 2x-oversized on width and ~3x-oversized on
                // height versus the actual non-square thumb slot.
                final int seenThumbPxW = seenThumbPxW(ctx);
                final int seenThumbPxH = seenThumbPxH(ctx);
                if (hasThumbB64) {
                    String b64PoolKey = poolKey("b64:" + thumbB64.hashCode(), seenThumbPxW, seenThumbPxH);
                    decodeB64ThumbAsync(thumbB64, b64PoolKey, SEEN_THUMB_BITMAP_CACHE, decoded -> {
                        if (h.canvasBindToken != myToken) return;
                        cv.setSeenThumbBitmap(decoded);
                    });
                } else {
                android.graphics.Bitmap seenThumbHit = SEEN_THUMB_BITMAP_CACHE.get(poolKey(thumbUrl, seenThumbPxW, seenThumbPxH));
                if (seenThumbHit != null && !seenThumbHit.isRecycled()) {
                    dashboardRecordHit(ctx, thumbUrl);
                    cv.setSeenThumbBitmap(seenThumbHit);
                } else {
                    glide(ctx).asBitmap().load(thumbUrl).apply(THUMB_RGB565)
                            .override(seenThumbPxW, seenThumbPxH).centerCrop()
                            .listener(com.callx.app.cache.CacheDashboardStats.glideListener(
                                    ctx, thumbUrl))
                            .into(h.prepareBitmapTarget(glide(ctx), TARGET_CANVAS_SEEN,
                                    new BitmapReadyCallback() {
                                @Override public void onReady(@NonNull Bitmap resource) {
                                    SEEN_THUMB_BITMAP_CACHE.put(poolKey(thumbUrl, seenThumbPxW, seenThumbPxH), resource);
                                    dashboardRecordDecoded(ctx, thumbUrl, resource);
                                    if (h.canvasBindToken != myToken) return;
                                    cv.setSeenThumbBitmap(resource);
                                }
                            }, new BitmapClearedCallback() {
                                @Override public void onCleared() {
                                    if (h.canvasBindToken != myToken) return;
                                    cv.setSeenThumbBitmap(null);
                                }
                            }));
                }
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
                    // PERF: explicit "webp" instead of "auto" — f_auto is a
                    // per-request content-negotiation guess (usually WebP/
                    // AVIF but not guaranteed), whereas forcing webp here
                    // gives a predictable, always-smaller (~30% vs JPEG)
                    // thumbnail payload for every chat bubble.
                    loadUrl = com.callx.app.utils.CloudinaryUploader.deriveThumbUrl(cellUrl, 200, "webp");
                    cellPending[i] = true;
                } else {
                    loadUrl = cellUrl;
                }
                if (!sent && isImageCell && cachedFile == null && !cellUrl.isEmpty() && !cellThumb.isEmpty()) {
                    cellPending[i] = true; // has a thumb to show, but full-res still needs downloading
                }

                // PERF ADV: real per-cell px target instead of a flat 240×240
                // — see groupCellPx() above. `total` (not `visible`) is the
                // actual item count so a >9-item group still decodes at the
                // true 3×3 78dp slot for its visible cells.
                final int[] gcPx = groupCellPx(ctx, total, cellIndex);

                if (cachedFile != null) {
                    // FIX: same flicker root cause as reel-share/seen-bubble —
                    // grid cells had zero cache check, so every rebind of a
                    // media-group row (scroll, or a new message elsewhere
                    // triggering a rebind of this visible row) blanked every
                    // cell in the grid for a frame before Glide redecoded it.
                    // Pool key now carries the target px size (it varies by
                    // cell now, not a constant 240×240) so the same file
                    // reused across different grid slots/layouts can't hit a
                    // wrong-size cached bitmap.
                    String cellPoolKey = cachedFile.getAbsolutePath() + "@" + gcPx[0] + "x" + gcPx[1];
                    android.graphics.Bitmap cellHit = MEDIA_GRID_BITMAP_CACHE.get(cellPoolKey);
                    if (cellHit != null && !cellHit.isRecycled()) {
                        cv.setMediaGroupBitmap(cellIndex, cellHit);
                    } else {
                        glide(ctx).asBitmap().load(cachedFile).apply(THUMB_RGB565).override(gcPx[0], gcPx[1])
                                .into(h.prepareBitmapTarget(glide(ctx), TARGET_MEDIA_GRID_BASE + cellIndex,
                                        new BitmapReadyCallback() {
                                    @Override public void onReady(@NonNull Bitmap resource) {
                                        MEDIA_GRID_BITMAP_CACHE.put(cellPoolKey, resource);
                                        if (h.canvasBindToken != myToken) return;
                                        cv.setMediaGroupBitmap(cellIndex, resource);
                                    }
                                }, new BitmapClearedCallback() {
                                    @Override public void onCleared() {
                                        if (h.canvasBindToken != myToken) return;
                                        cv.setMediaGroupBitmap(cellIndex, null);
                                    }
                                }));
                    }
                } else if (loadUrl != null && !loadUrl.isEmpty()) {
                    final String finalLoadUrl = loadUrl;
                    android.graphics.Bitmap cellHit = MEDIA_GRID_BITMAP_CACHE.get(poolKey(finalLoadUrl, gcPx[0], gcPx[1]));
                    if (cellHit != null && !cellHit.isRecycled()) {
                        cv.setMediaGroupBitmap(cellIndex, cellHit);
                    } else {
                        glide(ctx).asBitmap().load(loadUrl).apply(THUMB_RGB565).override(gcPx[0], gcPx[1])
                                .into(h.prepareBitmapTarget(glide(ctx), TARGET_MEDIA_GRID_BASE + cellIndex,
                                        new BitmapReadyCallback() {
                                    @Override public void onReady(@NonNull Bitmap resource) {
                                        MEDIA_GRID_BITMAP_CACHE.put(poolKey(finalLoadUrl, gcPx[0], gcPx[1]), resource);
                                        if (h.canvasBindToken != myToken) return;
                                        cv.setMediaGroupBitmap(cellIndex, resource);
                                    }
                                }, new BitmapClearedCallback() {
                                    @Override public void onCleared() {
                                        if (h.canvasBindToken != myToken) return;
                                        cv.setMediaGroupBitmap(cellIndex, null);
                                    }
                                }));
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
            // Advance #6: prefer the precomputed m.mediaAspectRatio (set once
            // at Room-insert time by MessageEntityMapper.fromModel — see that
            // field's javadoc) over re-deriving the division on every single
            // bind/rebind; fall back to computing it here only for a Message
            // that hasn't round-tripped through Room yet (e.g. this session's
            // own optimistic local-send object).
            float knownRatio = (m.mediaAspectRatio != null && m.mediaAspectRatio > 0f)
                    ? m.mediaAspectRatio
                    : (m.mediaWidth != null && m.mediaHeight != null
                            && m.mediaWidth > 0 && m.mediaHeight > 0)
                    ? (float) m.mediaWidth / m.mediaHeight : 0f;
            cv.bindMedia(null, m.caption, timeStr, sent, isRead, isDelivered, fullUrl, knownRatio);
            cv.setDeletedStyle(false); // clears any italic/dim state a recycled view carried from a deleted message
            wireCaptionReadMore(h, cv, m.messageId); // caption read-more/read-less

            // Feature: Voice Caption on Photo (Canvas) — attach/clear the
            // play-badge overlay. Icon state (isThisPlaying) mirrors
            // bindVoiceOnImage()'s own check: whether THIS message's voice
            // note is the one currently playing through the adapter's
            // shared MediaPlayer.
            boolean hasVoiceCaption = m.voiceUrl != null && !m.voiceUrl.isEmpty();
            if (hasVoiceCaption) {
                long voiceMs = m.voiceDuration != null ? m.voiceDuration : 0L;
                // PERF ULTRA: was String.format()'d fresh on every bind — see
                // formatVoiceDuration()'s cache doc above.
                String voiceDurText = formatVoiceDuration(voiceMs);
                cv.setVoiceCaption(m.voiceUrl, voiceDurText);
                boolean isThisVoicePlaying = playingPos == h.getAdapterPosition() && player != null && isPlayerPlaying;
                cv.setAudioPlaying(isThisVoicePlaying);

                // BUG FIX: this used to auto-play the receiver's voice
                // caption once per message (walkie-talkie style). Removed —
                // it was firing again on every fresh chat-screen open (the
                // dedupe set lived on the adapter instance, which gets
                // recreated each time ChatActivity is), so a caption the
                // user had already heard kept re-downloading/decrypting/
                // playing itself unprompted. Tap-to-play only now, same as
                // a standalone voice message.
            } else {
                cv.setVoiceCaption(null, null);
            }

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
                h.imageBindFireToken = myToken;
                h.imageBindAspectCacheKey = null; // local pending preview — no aspect caching here
                h.imageBindPoolKey = null;        // not pool-worthy — still uploading, url may change
                glide(ctx).asBitmap()
                        .load(android.net.Uri.parse(m.mediaLocalPath))
                        .apply(THUMB_RGB565)
                        .override(thumbPx(ctx), thumbPx(ctx))
                        .into(h.getOrCreateImageBindTarget());

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
                    ? getCachedFileFast(ctx, fullUrl) : null;

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
                            : MEDIA_BITMAP_CACHE.get(poolKey);
                    if (poolHit != null && !poolHit.isRecycled()) {
                        if (poolHit.getHeight() > 0) {
                            com.callx.app.conversation.canvas.MessageBubbleCanvasView
                                    .cacheAspectRatio(fullUrl, (float) poolHit.getWidth() / poolHit.getHeight());
                        }
                        dashboardRecordHit(ctx, poolKey);
                        cv.setMediaBitmap(poolHit);
                    } else {
                    // PERF ADV: this image has no known width/height metadata
                    // (legacy message, or resolution failed at send time) and
                    // MEDIA_ASPECT_CACHE has nothing either (bindMedia() above
                    // already checked both) — the bubble is currently showing
                    // the square/4:3 placeholder and will only relayout once
                    // the FULL Glide decode below (downsample + RGB565
                    // convert) finishes. loadSrc is already a local File/Uri
                    // here (sent, or received+cached), so a header-only
                    // dimension read resolves in a fraction of that time —
                    // fire it in parallel so the bubble can relayout to its
                    // correct proportions well before the full bitmap is
                    // ready, shrinking the visible square→real "pop" down to
                    // roughly a header read instead of a full decode. No-op
                    // (checked inside applyKnownAspectRatioEarly) if the ratio
                    // becomes known some other way first.
                    if (knownRatio <= 0f && (loadSrc instanceof java.io.File || loadSrc instanceof android.net.Uri)) {
                        resolveAspectRatioEarly(ctx, loadSrc, fullUrl, cv, h, myToken);
                    }
                    // PERF #4: use density-aware thumb size instead of hard-coded 480px
                    h.imageBindFireToken = myToken;
                    h.imageBindAspectCacheKey = fullUrl;
                    h.imageBindPoolKey = poolKey;
                    glide(ctx).asBitmap()
                            .load(loadSrc)
                            .apply(THUMB_RGB565)
                            .override(thumbPx(ctx), thumbPx(ctx))
                            .into(h.getOrCreateImageBindTarget());
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
                boolean blurHashPending = false;
                if (!sent && m.mediaKeyEnc != null) {
                    // v426 PERF: cache hit = free; miss = off-main-thread
                    // decrypt on the partner's FIFO bucket (see
                    // resolveImageBlurHashAsync). Never decrypt on the main thread.
                    String peek = peekEnvelopeBlurHash(m);
                    if (peek == null) blurHashPending = true;
                    else blurHash = peek.isEmpty() ? null : peek;
                }
                if (blurHashPending) {
                    resolveImageBlurHashAsync(ctx, m, h, myToken, hash -> {
                        if (hash == null || hash.isEmpty()) return;
                        // isLowResPlaceholder=true — see the sync path below.
                        ThumbHashPlaceholder.getAsync(hash, 32, 32, placeholder -> {
                            if (h.canvasBindToken != myToken) return;
                            if (placeholder != null) cv.setMediaBitmap(placeholder, true);
                        });
                    });
                } else if (blurHash != null && !blurHash.isEmpty()) {
                    // Migrated from BlurHash → ThumbHash; ThumbHashPlaceholder
                    // returns null (falls through, no crash) for any leftover
                    // BlurHash-format strings on old in-flight/history messages.
                    // PERF #4: getAsync — an L1 hit still applies inline/instant
                    // (same as before), a miss decodes off-main and posts back;
                    // canvasBindToken guard skips a stale result if this row
                    // got recycled/rebound before the decode finished.
                    ThumbHashPlaceholder.getAsync(blurHash, 32, 32, placeholder -> {
                        if (h.canvasBindToken != myToken) return;
                        // isLowResPlaceholder=true — lets MediaRenderer apply its adaptive
                        // extra-blur pass scaled to this bubble's actual size (see
                        // MediaRenderer#blurPlaceholderForBubble).
                        if (placeholder != null) cv.setMediaBitmap(placeholder, true);
                    });
                }

                // ── WebP thumb stage removed (v419) ────────────────────────
                // Used to Glide-load m.thumbnailUrl (or decrypt an inline
                // envelope thumb) here as a low-res upgrade over the
                // ThumbHash placeholder above. New sends never populate
                // either one anymore (see ChatMediaController), so there is
                // nothing to load — the ThumbHash placeholder stays up until
                // the full-res image finishes downloading (auto or on tap)
                // below. Old messages that still carry a thumbnailUrl/inline
                // thumb from before this migration just skip straight to the
                // same placeholder-then-full-image behavior.

                boolean isDownloading = downloadingMediaUrls.contains(fullUrl);
                if (isDownloading) {
                    cv.setMediaDownloadGate(true, -1, null);
                } else if (MediaAutoDownloadPolicy.shouldAutoDownload(ctx, "image")) {
                    // ── Auto-download on WiFi (or per user policy) ─────────────
                    // Enqueue through the network-aware receiver download queue so
                    // we cap at 3 concurrent downloads even when many images are
                    // visible at once.
                    downloadingMediaUrls.add(fullUrl);
                    h.autoDlUrl = fullUrl; // v426: lets onViewRecycled() drop this download if it never started
                    cv.setMediaDownloadGate(true, 0, null);
                    final String capturedUrl = fullUrl;
                    // Media E2E v2 (v375: moved OFF the main thread — see
                    // resolveFullMediaKeyAsync's javadoc). Resolves both the
                    // derived full-purpose key AND its ciphertext digest
                    // (WhatsApp-style file-hash check — see MediaE2ECrypto /
                    // MediaCache#getWithProgress); null (either from a
                    // pre-E2E legacy message or a plaintext/no-digest
                    // envelope) just means MediaCache.getWithProgress falls
                    // back to its old plaintext behavior / skips the digest
                    // check, same as before.
                    resolveFullMediaKeyAsync(ctx, m, sent, h, myToken, (autoDlKey, autoDlDigest) -> {
                    MediaDownloadQueue.getInstance(ctx).enqueue(capturedUrl, null, () -> {
                        // Progressive-JPEG sharpen-while-downloading — see the
                        // matching comment on the manual-tap path
                        // (onMediaDownloadClick) above. autoDlKey null means
                        // this image is plaintext (or a legacy pre-E2E
                        // message), so the Cloudinary progressive-delivery
                        // transform is safe to request; capturedUrl itself
                        // stays the cache/dedupe key either way.
                        final String autoFetchUrl = (autoDlKey == null)
                                ? com.callx.app.utils.CloudinaryUploader.deriveProgressiveFullUrl(capturedUrl)
                                : capturedUrl;
                        // Advance #5: same Range-preview race as the manual
                        // tap-to-download path above — plaintext only, and
                        // guarded against clobbering a sharper frame that
                        // already landed.
                        final boolean[] autoFullyLoaded = {false};
                        if (autoDlKey == null) {
                            com.callx.app.utils.MediaCache.fetchEarlyPreview(autoFetchUrl, partial -> {
                                if (h.canvasBindToken != myToken || autoFullyLoaded[0]) return;
                                cv.setMediaBitmap(partial);
                            });
                        }
                        com.callx.app.utils.MediaCache.getWithProgress(ctx, capturedUrl, autoFetchUrl, autoDlKey, autoDlDigest,
                                new com.callx.app.utils.MediaCache.ProgressCallback() {
                            @Override public void onProgress(int percent) {
                                if (h.canvasBindToken != myToken) return;
                                cv.setMediaDownloadGate(true, percent, null);
                            }
                            @Override public void onPartialBitmap(android.graphics.Bitmap partial) {
                                if (h.canvasBindToken != myToken) return;
                                autoFullyLoaded[0] = true;
                                cv.setMediaBitmap(partial);
                            }
                            @Override public void onReady(java.io.File file) {
                                MediaDownloadQueue.getInstance(ctx).markComplete(capturedUrl);
                                downloadingMediaUrls.remove(capturedUrl);
                                autoFullyLoaded[0] = true;
                                if (h.canvasBindToken != myToken) return;
                                cv.clearMediaDownloadGate();
                                android.graphics.Bitmap poolHit =
                                        MEDIA_BITMAP_CACHE.get(capturedUrl);
                                if (poolHit != null && !poolHit.isRecycled()) {
                                    cv.setMediaBitmap(poolHit);
                                } else {
                                    glide(ctx).asBitmap().load(file).apply(THUMB_RGB565)
                                            .override(thumbPx(ctx), thumbPx(ctx))
                                            .into(h.prepareBitmapTarget(glide(ctx), TARGET_AUTO_IMAGE,
                                                    new BitmapReadyCallback() {
                                        @Override public void onReady(@NonNull android.graphics.Bitmap resource) {
                                            MEDIA_BITMAP_CACHE.put(capturedUrl, resource);
                                            if (h.canvasBindToken != myToken) return;
                                            cv.setMediaBitmap(resource);
                                        }
                                    }, new BitmapClearedCallback() {
                                        @Override public void onCleared() {
                                            if (h.canvasBindToken != myToken) return;
                                            cv.setMediaBitmap(null);
                                        }
                                    }));
                                }
                            }
                            @Override public void onError(String err) {
                                MediaDownloadQueue.getInstance(ctx).markComplete(capturedUrl);
                                downloadingMediaUrls.remove(capturedUrl);
                                if (h.canvasBindToken != myToken) return;
                                cv.setMediaDownloadGate(false, Integer.MIN_VALUE,
                                        "Tap to download");
                            }
                        });
                    });
                    }, () -> downloadingMediaUrls.remove(capturedUrl)); // end resolveFullMediaKeyAsync
                } else {
                    // Manual download: show size label on the idle pill —
                    // PERF: use the size already captured at send time
                    // (m.fileSize, same pattern as the video branch above)
                    // instead of firing a network round-trip just to show
                    // a label. Only falls back to getRemoteSize() for
                    // messages sent before fileSize existed.
                    if (m.fileSize != null && m.fileSize > 0) {
                        cv.setMediaDownloadGate(false, Integer.MIN_VALUE, formatFileSize(m.fileSize));
                    } else {
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

            // Avatar — FIX (advance avatar optimization): reused/connected
            // to the same pipeline FollowConnectionsActivity's avatar rows
            // use (see ChatAvatarBinder.bindBitmap's doc) instead of a flat
            // un-tiered Glide load into a plain LruCache — responsive/
            // version-tagged URL, L2+L3 tier reuse (shared with the
            // legacy non-canvas reel-share row below), and CDN/cache-tier
            // analytics.
            String avatarUrl = m.reelShareOwnerPhoto != null ? m.reelShareOwnerPhoto : "";
            if (avatarUrl.isEmpty() && !rUsername.isEmpty()) {
                String cachedAvatar = reelOwnerAvatarCache.get(rUsername);
                if (cachedAvatar != null) avatarUrl = cachedAvatar;
            }
            if (!avatarUrl.isEmpty()) {
                com.callx.app.cache.ChatAvatarBinder.bindBitmap(ctx, avatarUrl, 0L, resource -> {
                    if (h.canvasBindToken != myToken) return;
                    cv.setReelShareAvatarBitmap(resource);
                });
            } else if (!rUsername.isEmpty() && reelAvatarFetchInFlight.add(rUsername)) {
                final String fUKey = rUsername;
                final android.content.Context fCtxA = ctx.getApplicationContext();
                com.google.firebase.database.FirebaseDatabase.getInstance()
                        .getReference("usernames").child(rUsername).get()
                        .addOnSuccessListener(idxSnap -> {
                            String fUid = idxSnap.getValue(String.class);
                            if (fUid == null || fUid.isEmpty()) { reelAvatarFetchInFlight.remove(fUKey); return; }
                            com.google.firebase.database.FirebaseDatabase.getInstance()
                                    .getReference("users").child(fUid).get()
                                    .addOnSuccessListener(child -> {
                                reelAvatarFetchInFlight.remove(fUKey);
                                if (!child.exists() || h.canvasBindToken != myToken) return;
                                String photo = child.child("profileImage").getValue(String.class);
                                if (photo == null || photo.isEmpty()) photo = child.child("photoUrl").getValue(String.class);
                                if (photo == null || photo.isEmpty()) photo = child.child("profilePhoto").getValue(String.class);
                                if (photo == null || photo.isEmpty()) return;
                                reelOwnerAvatarCache.put(fUKey, photo);
                                com.callx.app.cache.ChatAvatarBinder.bindBitmap(fCtxA, photo, 0L, resource -> {
                                    if (h.canvasBindToken != myToken) return;
                                    cv.setReelShareAvatarBitmap(resource);
                                });
                            }).addOnFailureListener(e -> reelAvatarFetchInFlight.remove(fUKey));
                        })
                        .addOnFailureListener(e -> reelAvatarFetchInFlight.remove(fUKey));
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
            // now fixed the same way: check the media pool first.
            String thumbB64 = m.reelShareThumbBase64;
            String thumb = m.reelShareThumb != null ? m.reelShareThumb : "";
            final String rKey = m.reelId != null ? m.reelId : "";
            if (thumb.isEmpty() && (thumbB64 == null || thumbB64.isEmpty()) && !rKey.isEmpty()) {
                String cachedThumb = reelThumbCache.get(rKey);
                if (cachedThumb != null) thumb = cachedThumb;
            }
            if (thumbB64 != null && !thumbB64.isEmpty()) {
                // WhatsApp-level: this card carries its own copy of the
                // thumbnail (embedded at send time — see
                // ReelShareSheetFragment via ThumbnailEmbedder), so it
                // renders straight from local bytes: no network call,
                // unaffected by the original reel later being deleted.
                // Same in-memory pool as the URL path so repeat rebinds
                // don't re-decode the same JPEG.
                final int[] cardPxB64 = reelCardPx(ctx);
                String b64PoolKey = poolKey("b64:" + thumbB64.hashCode(), cardPxB64[0], cardPxB64[1]);
                decodeB64ThumbAsync(thumbB64, b64PoolKey, MEDIA_BITMAP_CACHE, decoded -> {
                    if (h.canvasBindToken != myToken) return;
                    cv.setReelShareThumbBitmap(decoded);
                });
            } else if (!thumb.isEmpty()) {
                final String finalThumbUrl = thumb;
                final int[] cardPx = reelCardPx(ctx);
                android.graphics.Bitmap reelThumbHit = MEDIA_BITMAP_CACHE.get(poolKey(finalThumbUrl, cardPx[0], cardPx[1]));
                if (reelThumbHit != null && !reelThumbHit.isRecycled()) {
                    dashboardRecordHit(ctx, finalThumbUrl);
                    cv.setReelShareThumbBitmap(reelThumbHit);
                } else {
                glide(ctx).asBitmap().load(finalThumbUrl).apply(THUMB_RGB565).override(cardPx[0], cardPx[1]).centerCrop()
                        .into(h.prepareBitmapTarget(glide(ctx), TARGET_REEL_THUMB,
                                new BitmapReadyCallback() {
                            @Override public void onReady(@NonNull Bitmap resource) {
                                MEDIA_BITMAP_CACHE.put(poolKey(finalThumbUrl, cardPx[0], cardPx[1]), resource);
                                dashboardRecordDecoded(ctx, finalThumbUrl, resource);
                                if (h.canvasBindToken != myToken) return;
                                cv.setReelShareThumbBitmap(resource);
                            }
                        }, null));
                }
            } else if (!rKey.isEmpty() && reelThumbFetchInFlight.add(rKey)) {
                final android.content.Context fCtxT = ctx.getApplicationContext();
                final int[] cardPxFb = reelCardPx(ctx);
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
                                    glide(fCtxT).asBitmap().load(t).apply(THUMB_RGB565).override(cardPxFb[0], cardPxFb[1]).centerCrop()
                                            .into(h.prepareBitmapTarget(glide(fCtxT), TARGET_REEL_THUMB,
                                                    new BitmapReadyCallback() {
                                                @Override public void onReady(@NonNull Bitmap resource) {
                                                    if (h.canvasBindToken != myToken) return;
                                                    cv.setReelShareThumbBitmap(resource);
                                                }
                                            }, null));
                                }
                                String u = snap.child("ownerName").getValue(String.class);
                                if (u == null || u.isEmpty()) u = snap.child("username").getValue(String.class);
                                if (u != null && !u.isEmpty()) cv.setReelShareUsername(u);
                                String ap = snap.child("ownerPhoto").getValue(String.class);
                                if (ap == null || ap.isEmpty()) ap = snap.child("profileImage").getValue(String.class);
                                if (ap != null && !ap.isEmpty()) {
                                    if (u != null && !u.isEmpty()) reelOwnerAvatarCache.put(u, ap);
                                    glide(fCtxT).asBitmap().load(ap).apply(THUMB_RGB565).override(96, 96).circleCrop()
                                            .into(h.prepareBitmapTarget(glide(fCtxT), TARGET_REEL_AVATAR,
                                                    new BitmapReadyCallback() {
                                                @Override public void onReady(@NonNull Bitmap resource) {
                                                    if (h.canvasBindToken != myToken) return;
                                                    cv.setReelShareAvatarBitmap(resource);
                                                }
                                            }, null));
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
            // Advance #6: same precomputed-ratio preference as the image
            // block above.
            float vKnownRatio = (m.mediaAspectRatio != null && m.mediaAspectRatio > 0f)
                    ? m.mediaAspectRatio
                    : (m.mediaWidth != null && m.mediaHeight != null
                            && m.mediaWidth > 0 && m.mediaHeight > 0)
                    ? (float) m.mediaWidth / m.mediaHeight : 0f;
            cv.bindVideo(null, m.caption, durText, timeStr, sent, isRead, isDelivered, vThumbUrl, vKnownRatio);
            cv.setDeletedStyle(false);
            wireCaptionReadMore(h, cv, m.messageId); // caption read-more/read-less

            // BlurHash placeholder: show blurred color preview before the video thumb loads.
            // If thumbnailUrl is present we load from server (no full download needed for preview).
            // ULTRA-FAST THUMBNAIL FIX: plaintext videos keep BlurHash in m.blurHash
            // (apply instantly — zero network, and PERF #4 below keeps even a
            // cold decode off the main thread). E2E videos carry it inside the
            // encrypted envelope instead (see ChatMediaController), so it needs the
            // async ratchet-decrypt path — same pattern as the thumb key resolve
            // right below, just for the hash string instead of the key bytes.
            // Migrated from BlurHash → ThumbHash (see image block above for why);
            // ThumbHashPlaceholder returns null for old BlurHash-format strings.
            final String vBlurHash = m.blurHash;
            // PERF #4: getAsync for both branches below — an L1 hit still
            // applies inline/instant, a miss decodes off the main thread
            // and posts back; canvasBindToken guard skips a stale result
            // if the row got recycled/rebound before the decode finished.
            if (vBlurHash != null && !vBlurHash.isEmpty()) {
                ThumbHashPlaceholder.getAsync(vBlurHash, 32, 32, vBlurhashBmp -> {
                    if (h.canvasBindToken != myToken) return;
                    // isLowResPlaceholder=true — same adaptive-blur reasoning as the image block above.
                    if (vBlurhashBmp != null) cv.setMediaBitmap(vBlurhashBmp, true);
                });
            } else if (!sent && m.mediaKeyEnc != null) {
                resolveVideoBlurHashAsync(ctx, m, sent, h, myToken, decryptedHash -> {
                    if (decryptedHash == null || decryptedHash.isEmpty()) return;
                    ThumbHashPlaceholder.getAsync(decryptedHash, 32, 32, vBlurhashBmp -> {
                        if (h.canvasBindToken != myToken) return;
                        if (vBlurhashBmp != null) cv.setMediaBitmap(vBlurhashBmp, true);
                    });
                });
            }

            // Media E2E (video, thumbnail-only): m.thumbnailUrl is ciphertext
            // (uploaded as resource_type=raw) when mediaKeyEnc is set — see
            // ChatMediaController#doStartVideoUploadWork. The video file
            // itself (vUrl, below) is never encrypted.
            // Media E2E v2: video thumbnails are encrypted with the
            // thumb-purpose subkey (see MediaE2ECrypto.PURPOSE_THUMB) — use
            // the matching decrypt helper, not the full-purpose one.
            if (vThumbUrl != null && !vThumbUrl.isEmpty()) {
                // PERF #1: check decoded-Bitmap pool before Glide decode
                final String vPoolKey = vThumbUrl;
                android.graphics.Bitmap vPoolHit = MEDIA_BITMAP_CACHE.get(vPoolKey);
                if (vPoolHit != null && !vPoolHit.isRecycled()) {
                    if (vPoolHit.getHeight() > 0) {
                        com.callx.app.conversation.canvas.MessageBubbleCanvasView
                                .cacheAspectRatio(vThumbUrl, (float) vPoolHit.getWidth() / vPoolHit.getHeight());
                    }
                    dashboardRecordHit(ctx, vPoolKey);
                    cv.setMediaBitmap(vPoolHit);
                } else {
                // LAZY VIDEO FRAME LOAD (see VIDEO_FRAME_LAZY_HANDLER doc):
                // hold off starting the real-frame fetch for a short beat so
                // the ThumbHash placeholder is what's actually on screen
                // first. Re-checks the bind token once the delay elapses —
                // a rebind/recycle in the meantime just skips this fetch,
                // exactly like every other h.canvasBindToken guard below.
                final long lazyBindToken = myToken;
                VIDEO_FRAME_LAZY_HANDLER.postDelayed(() -> {
                if (h.canvasBindToken != lazyBindToken) return;
                // v375: thumb-key decrypt moved off the main thread (see
                // resolveThumbMediaKeyAsync's javadoc) AND moved to only
                // happen on this pool-MISS path — previously it ran
                // unconditionally before the pool-hit check above, wasting a
                // ratchet-cache lookup on every already-cached video thumb.
                resolveThumbMediaKeyAsync(ctx, m, sent, h, myToken, vThumbKey -> {
                if (vThumbKey != null) {
                    // Encrypted thumb — decrypt via MediaCache before handing to Glide.
                    final String vThumbUrlF = vThumbUrl;
                    com.callx.app.utils.MediaCache.get(ctx, vThumbUrl, vThumbKey,
                            new com.callx.app.utils.MediaCache.Callback() {
                        @Override public void onReady(java.io.File file) {
                            if (h.canvasBindToken != myToken) return;
                            // NOTE: no TinyThumbBlurTransformation here — vr.thumbFile is a
                            // real 300-480px extracted video frame (see VideoCompressor
                            // .makeThumbnail), not a blocky ImageCompressor micro-thumb, so
                            // it's already sharp; blurring it would only throw away quality.
                            glide(ctx).asBitmap().load(file).apply(THUMB_RGB565)
                                    .override(thumbPx(ctx), thumbPx(ctx))
                                    .into(h.prepareBitmapTarget(glide(ctx), TARGET_CANVAS_PRIMARY,
                                            new BitmapReadyCallback() {
                                        @Override public void onReady(@NonNull Bitmap resource) {
                                            if (resource.getHeight() > 0) {
                                                com.callx.app.conversation.canvas.MessageBubbleCanvasView
                                                        .cacheAspectRatio(vThumbUrlF, (float) resource.getWidth() / resource.getHeight());
                                            }
                                            MEDIA_BITMAP_CACHE.put(vThumbUrlF, resource);
                                            if (h.canvasBindToken != myToken) return;
                                            cv.setMediaBitmap(resource);
                                        }
                                    }, null));
                        }
                        @Override public void onError(String reason) { /* BlurHash/placeholder stays up */ }
                    });
                } else {
                // PERF #4: density-aware override size
                // NOTE: no TinyThumbBlurTransformation here — same reasoning as the
                // E2E branch above, this is a real extracted video frame, not a
                // micro-thumb.
                glide(ctx).asBitmap()
                        .load(vThumbUrl)
                        .apply(THUMB_RGB565)
                        .thumbnail(0.1f)
                        .override(thumbPx(ctx), thumbPx(ctx))
                        .into(h.prepareBitmapTarget(glide(ctx), TARGET_CANVAS_PRIMARY,
                                new BitmapReadyCallback() {
                            @Override public void onReady(@NonNull Bitmap resource) {
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
                                MEDIA_BITMAP_CACHE.put(vPoolKey, resource);
                                dashboardRecordDecoded(ctx, vPoolKey, resource);
                                if (h.canvasBindToken != myToken) return;
                                cv.setMediaBitmap(resource);
                            }
                        }, new BitmapClearedCallback() {
                            @Override public void onCleared() {
                                if (h.canvasBindToken != myToken) return;
                                cv.setMediaBitmap(null);
                            }
                        }));
                }
                }); // end resolveThumbMediaKeyAsync
                }, VIDEO_FRAME_LAZY_DELAY_MS); // end VIDEO_FRAME_LAZY_HANDLER.postDelayed
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
                        : getCachedFileFast(ctx, vUrl);
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
            // bindBubble) — just pushed through cv.bindContact()/
            // setContactAvatarBitmap() instead of CircleImageView/TextView
            // calls. No caption, no timestamp/tick footer for this type
            // (see MessageBubbleCanvasView's CONTACT_* doc).
            cv.bindContact(null, m.contactName, m.contactPhone, sent);
            cv.setDeletedStyle(false);

            final String contactPhotoUrl = m.contactPhotoUrl;
            if (contactPhotoUrl != null && !contactPhotoUrl.isEmpty()) {
                // FIX (avatar-optimization — reuse core pipeline): was a
                // flat un-tiered Glide load into this adapter's own private
                // decoded media pools — completely disconnected from
                // ChatAvatarBinder's ChatAvatarL2Cache/L3 + CDN analytics
                // every other chat avatar surface shares (same pattern the
                // reel-share avatar above already uses). No avatarVersion
                // tracked for a shared-contact photo, so unversioned (0L)
                // like the reel-share fallback path above.
                com.callx.app.cache.ChatAvatarBinder.bindBitmap(ctx, contactPhotoUrl, 0L, resource -> {
                    if (h.canvasBindToken != myToken) return;
                    cv.setContactAvatarBitmap(resource);
                });
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
                Bitmap locPoolHit = LOCATION_BITMAP_CACHE.get(thumbUrl);
                if (locPoolHit != null && !locPoolHit.isRecycled()) {
                    dashboardRecordHit(ctx, thumbUrl);
                    cv.setLocationMapBitmap(locPoolHit);
                } else {
                glide(ctx).asBitmap().load(thumbUrl).apply(THUMB_RGB565)
                        .override(720, 720)
                        .listener(com.callx.app.cache.CacheDashboardStats.glideListener(
                                ctx, thumbUrl))
                        .into(h.prepareBitmapTarget(glide(ctx), TARGET_CANVAS_PRIMARY,
                                new BitmapReadyCallback() {
                            @Override public void onReady(@NonNull Bitmap resource) {
                                // PERF: store for scroll-back reuse regardless
                                // of whether this holder still shows this bubble
                                LOCATION_BITMAP_CACHE.put(thumbUrl, resource);
                                dashboardRecordDecoded(ctx, thumbUrl, resource);
                                if (h.canvasBindToken != myToken) return;
                                cv.setLocationMapBitmap(resource);
                            }
                        }, new BitmapClearedCallback() {
                            @Override public void onCleared() {
                                if (h.canvasBindToken != myToken) return;
                                cv.setLocationMapBitmap(null);
                            }
                        }));
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
            java.io.File cachedAudio = getCachedFileFast(ctx, aUrl);
            if (cachedAudio == null && aUrl != null && !aUrl.isEmpty()) {
                // Media E2E (audio): MediaStreamCache doesn't know how to
                // decrypt, so an E2E voice note skips the "stream first
                // 512KB while downloading" fast-start trick and instead
                // warms the plain decrypting MediaCache download (full
                // file — voice notes are small, so the wait is
                // negligible). See ChatMediaController#doUpload's audio
                // branch / MediaE2ECrypto.
                // v375: moved off the main thread — see resolveFullMediaKeyOnlyAsync's javadoc.
                resolveFullMediaKeyOnlyAsync(ctx, m, sent, h, myToken, aWarmKey -> {
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
                }); // end resolveFullMediaKeyOnlyAsync
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
                android.graphics.Bitmap gifHit = GIF_BITMAP_CACHE.get(gifPoolKey);
                if (gifHit != null && !gifHit.isRecycled()) {
                    dashboardRecordHit(ctx, gifPoolKey);
                    cv.setGifBitmap(gifHit);
                } else {
                    // Already on disk — decode first-frame and display immediately.
                    glide(ctx).asBitmap().load(gifCached).apply(THUMB_RGB565)
                            .override(gifStickerPx(ctx), gifStickerPx(ctx)) // PERF: match 180dp slot, avoid oversized decode
                            .listener(com.callx.app.cache.CacheDashboardStats.glideListener(
                                    ctx, gifPoolKey))
                            .into(h.prepareBitmapTarget(glide(ctx), TARGET_CANVAS_PRIMARY,
                                    new BitmapReadyCallback() {
                                @Override public void onReady(@NonNull android.graphics.Bitmap resource) {
                                    GIF_BITMAP_CACHE.put(gifPoolKey, resource);
                                    dashboardRecordDecoded(ctx, gifPoolKey, resource);
                                    if (h.canvasBindToken != myToken) return;
                                    cv.setGifBitmap(resource);
                                }
                            }, null));
                }
            } else if (!gifUrl.isEmpty()) {
                // Not cached — show download gate. PERF: use m.fileSize
                // (captured at send time for uploaded GIFs/stickers — see
                // ChatMediaController/GroupChatActivity) instead of a
                // getRemoteSize() network round-trip when it's known.
                // Tenor-picker GIFs (direct CDN URL, never uploaded by us)
                // have no fileSize, so they still fall back to fetching it.
                if (m.fileSize != null && m.fileSize > 0) {
                    cv.setMediaDownloadGate(false, Integer.MIN_VALUE, formatFileSize(m.fileSize));
                } else {
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
                android.graphics.Bitmap stickerHit = STICKER_BITMAP_CACHE.get(stickerPoolKey);
                if (stickerHit != null && !stickerHit.isRecycled()) {
                    dashboardRecordHit(ctx, stickerPoolKey);
                    cv.setStickerBitmap(stickerHit);
                } else {
                    glide(ctx).asBitmap().load(stickerCached).apply(THUMB_RGB565)
                            .override(gifStickerPx(ctx), gifStickerPx(ctx)) // PERF: match 180dp slot, avoid oversized decode
                            .listener(com.callx.app.cache.CacheDashboardStats.glideListener(
                                    ctx, stickerPoolKey))
                            .into(h.prepareBitmapTarget(glide(ctx), TARGET_CANVAS_PRIMARY,
                                    new BitmapReadyCallback() {
                                @Override public void onReady(@NonNull android.graphics.Bitmap resource) {
                                    STICKER_BITMAP_CACHE.put(stickerPoolKey, resource);
                                    dashboardRecordDecoded(ctx, stickerPoolKey, resource);
                                    if (h.canvasBindToken != myToken) return;
                                    cv.setStickerBitmap(resource);
                                }
                            }, null));
                }
            } else if (!stickerUrl.isEmpty()) {
                // PERF: same m.fileSize-first pattern as the image/gif branches above.
                if (m.fileSize != null && m.fileSize > 0) {
                    cv.setMediaDownloadGate(false, Integer.MIN_VALUE, formatFileSize(m.fileSize));
                } else {
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

            boolean fileCached = !fileUrl.isEmpty() && getCachedFileFast(ctx, fileUrl) != null;
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
            int pollN = opts.size();
            // PERF: same reused-buffer pattern as bindPollOnly()'s live
            // vote fast path — this full-bind branch also runs on every
            // scroll-recycle of a poll row, not just the initial bind.
            if (h.pollCountsScratch == null || h.pollCountsScratch.length != pollN) {
                h.pollCountsScratch = new int[pollN];
            }
            if (h.pollMyVoteScratch == null || h.pollMyVoteScratch.length != pollN) {
                h.pollMyVoteScratch = new boolean[pollN];
            } else {
                java.util.Arrays.fill(h.pollMyVoteScratch, false);
            }
            int[] counts = com.callx.app.utils.PollJsonUtil.countVotes(votesMap, pollN, h.pollCountsScratch);
            int total    = com.callx.app.utils.PollJsonUtil.totalVotes(votesMap);
            boolean[] myVote = h.pollMyVoteScratch;
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
            bindPollVoters(cv, m, ctx, false); // group + non-anonymous only; clears itself otherwise
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
                    bindLinkPreviewResult(h, cv, ctx, previewUrl, cachedPreview);
                } else {
                    cv.clearLinkPreview(); // genuinely nothing to show yet — fetch in flight
                    com.callx.app.utils.LinkPreviewFetcher.fetch(previewUrl,
                            new com.callx.app.utils.LinkPreviewFetcher.Callback() {
                        @Override public void onResult(com.callx.app.utils.LinkPreviewFetcher.Result r) {
                            if (!previewUrl.equals(cv.getTag())) return; // recycled/rebound since this fetch started
                            bindLinkPreviewResult(h, cv, ctx, previewUrl, r);
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
            final String replyThumbB64 = m.replyToThumbBase64;
            if (replyThumbB64 != null && !replyThumbB64.isEmpty()) {
                // WhatsApp-level: this message carries its own copy of the thumbnail
                // (embedded at send time — see StatusReplyBottomSheet#embedReplyThumbnail),
                // so it renders straight from local bytes: no network call, and
                // completely unaffected by the original status later expiring, being
                // deleted, or being moved into a Highlight. Same in-memory pool as the
                // URL path below so repeat rebinds don't re-decode the same JPEG.
                String b64PoolKey = poolKey("b64:" + replyThumbB64.hashCode(), 88, 88);
                cv.setReply(m.replyToSenderName, m.replyToText, null);
                decodeB64ThumbAsync(replyThumbB64, b64PoolKey, REPLY_THUMB_BITMAP_CACHE, decoded -> {
                    if (h.canvasBindToken != myToken) return;
                    cv.setReply(m.replyToSenderName, m.replyToText, decoded);
                });
            } else if (replyThumbUrl != null && !replyThumbUrl.isEmpty()) {
                android.graphics.Bitmap replyPoolHit = REPLY_THUMB_BITMAP_CACHE.get(poolKey(replyThumbUrl, 88, 88));
                if (replyPoolHit != null && !replyPoolHit.isRecycled()) {
                    cv.setReply(m.replyToSenderName, m.replyToText, replyPoolHit);
                } else {
                    cv.setReply(m.replyToSenderName, m.replyToText, null);
                    glide(ctx).asBitmap()
                            .load(replyThumbUrl)
                            .apply(THUMB_RGB565)
                            .override(88, 88)
                            .into(h.prepareBitmapTarget(glide(ctx), TARGET_REPLY,
                                    new BitmapReadyCallback() {
                                @Override public void onReady(@NonNull Bitmap resource) {
                                    REPLY_THUMB_BITMAP_CACHE.put(poolKey(replyThumbUrl, 88, 88), resource);
                                    if (h.canvasBindToken != myToken) return; // holder recycled/rebound since this load started
                                    cv.setReply(m.replyToSenderName, m.replyToText, resource);
                                }
                            }, null));
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
        final ReactionBound rxb = reactionBoundFor(m); // v444: memoized text + reactor strip
        if (rxb != null && rxb.text != null) cv.setReactions(rxb.text);
        else cv.clearReactions();
        applyReactionAvatars(cv, rxb, ctx); // group: reactor faces after the emoji (clears itself otherwise)

        // ── Pinned label ──
        cv.setPinned(Boolean.TRUE.equals(m.pinned));

        // ── Group-chat sender name (received only — same gate bindMessage()
        // uses for tvSenderName) — also carries the 📢 broadcast badge,
        // exactly mirroring bindMessage()'s tv_sender_name block: a group
        // message gets a "📢 " prefix on the sender name, a 1:1 broadcast
        // shows the row solely for "📢 Broadcast". ──
        boolean isBroadcastMsg = Boolean.TRUE.equals(m.broadcast);
        if (!sent && isGroup && m.senderName != null && !m.senderName.isEmpty()) {
            // WhatsApp-style: the name shows only on the FIRST bubble of a
            // same-sender run (a broadcast always shows it — the 📢 badge is
            // per-message). Unlike the avatar below, hiding the name really
            // does shrink the bubble (its row isn't reserved), which is the
            // intended compact-run look; applyGroupedSpacing already tightens
            // the gap above non-head bubbles.
            if (isBroadcastMsg || isGroupNameRunHead(position, m)) {
                cv.setGroupSender(groupSenderLabel(m), m.senderId);
                // Admin/creator pill lives in the name row, so it follows the
                // name's run-head gate. A broadcast row is a system-style
                // announcement, not the member speaking — no pill.
                cv.setGroupSenderBadge(isBroadcastMsg ? null : groupBadgeFor(m.senderId));
            } else {
                cv.clearGroupSender(); // also drops any stale badge
            }

            // ── Group-sender avatar (WhatsApp-style, 20dp) — column
            // reserved synchronously regardless of whether the photo has
            // resolved yet (see setGroupSenderAvatarVisible()'s doc), so
            // the bubble's width is correct on the very first frame. photo
            // URL comes straight from groupMemberPhotos — the group's
            // already-loaded member/presence map (see field doc) — no
            // extra Firebase/Room read per message. Bind goes through
            // ChatAvatarBinder.bindBitmap() at its default TIER_INLINE
            // (24dp) so this shares L2/L3 cache entries with every other
            // canvas avatar at that tier (reel-share header, contact-share
            // avatar) for the same photo, same core pipeline the rest of
            // chat's avatars already use. ──
            cv.setGroupSenderAvatarVisible(true);
            // Same-uid consecutive skip (WhatsApp): the column above stays
            // reserved for EVERY bubble in a run (so the whole run stays
            // aligned — setGroupSenderAvatarVisible(false) here would shift
            // the bubble left and misalign it), but only the LAST bubble of
            // the run draws + binds the avatar. Earlier bubbles skip
            // bindBitmap() entirely and hold no bitmap.
            final boolean avatarShown = isGroupAvatarRunTail(position, m);
            cv.setGroupSenderAvatarShown(avatarShown);
            if (avatarShown) {
                String avatarPhotoUrl = groupMemberPhotos.get(m.senderId);
                if (avatarPhotoUrl != null && !avatarPhotoUrl.isEmpty()) {
                    // v447: L2 hit → set inline (no lambda / token capture / callback allocation).
                    final android.graphics.Bitmap l2 =
                            com.callx.app.cache.ChatAvatarBinder.peekInline(ctx, avatarPhotoUrl);
                    if (l2 != null) {
                        cv.setGroupSenderAvatarBitmap(l2);
                    } else {
                        com.callx.app.cache.ChatAvatarBinder.bindBitmap(ctx, avatarPhotoUrl, 0L, resource -> {
                            if (h.canvasBindToken != myToken) return; // recycled/rebound meanwhile
                            cv.setGroupSenderAvatarBitmap(resource);
                        });
                    }
                } else {
                    cv.setGroupSenderAvatarBitmap(null);
                }
            }
        } else if (!sent && isBroadcastMsg) {
            cv.setGroupSender("\uD83D\uDCE2 Broadcast");
            cv.clearGroupSenderAvatar();
        } else {
            cv.clearGroupSender();
            cv.clearGroupSenderAvatar();
        }

        // ── "Seen by" reader-avatar strip (sent group rows only; clears
        // itself on every other row so a recycled holder never keeps one) ──
        bindSeenByAvatars(h, m, position);

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
        expiryUnregister(h); // v448: skipped unless this holder had registered
        if (!isDeleted) {
            long expiresAt = m.expiresAt != null ? m.expiresAt : 0L;
            long remaining = expiresAt - System.currentTimeMillis();
            if (expiresAt > 0 && remaining > 0) {
                cv.setExpiryText("\u23F3 " + formatRemaining(remaining));
                h.expiryRegistered = true;
                com.callx.app.utils.ExpiryTickManager.get().register(h, expiresAt,
                        new com.callx.app.utils.ExpiryTickManager.Listener() {
                    @Override public void onTick(long ms) {
                        if (h.canvasView != null) h.canvasView.setExpiryText("\u23F3 " + formatRemaining(ms));
                    }
                    @Override public void onFinish() {
                        h.expiryRegistered = false; // manager already dropped the entry
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
                                    int myToken, java.util.Map<String, Object> item, int index, int total) {
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
                    MediaDownloadQueue.getInstance(ctx).markComplete(url);
                    downloadingMediaUrls.remove(url);
                    CACHED_FILE_CHECK.put(url, file);
                    if (h.canvasBindToken != myToken) return; // holder recycled/rebound since this started
                    // PERF ADV: real per-cell px target (see groupCellPx())
                    // instead of a flat 240×240 — this is the "tap this one
                    // cell's download badge" path, same grid the bulk-bind
                    // loop above already sizes correctly.
                    int[] gcPx = groupCellPx(ctx, total, index);
                    glide(ctx).asBitmap().load(file).apply(THUMB_RGB565).override(gcPx[0], gcPx[1])
                            .into(h.prepareBitmapTarget(glide(ctx), TARGET_GROUP_DOWNLOAD_BASE + index,
                                    new BitmapReadyCallback() {
                                @Override public void onReady(@NonNull Bitmap resource) {
                                    if (h.canvasBindToken != myToken) return;
                                    cv.setMediaGroupBitmap(index, resource);
                                    cv.markGroupCellDownloaded(index);
                                }
                            }, null));
                }
                @Override public void onError(String reason) {
                    MediaDownloadQueue.getInstance(ctx).markComplete(url);
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
                    // ULTRA PERF: skip applyBubbleOwned()+setBackground()
                    // entirely when this holder already has the right
                    // bubble state from its previous bind — avoids even
                    // the cheap setColor()/setCornerRadii() re-apply + an
                    // invalidate/draw pass on nearly every scroll-triggered
                    // rebind, since hasReply flips far less often than the
                    // row itself gets recycled. When it DOES need re-apply
                    // (including every row's first bind on chat open),
                    // applyBubbleOwned() writes into this holder's own
                    // private ownedBubbleDrawable — no GradientDrawable
                    // allocation, unlike the old shared+mutate() path.
                    if (h.lastBubbleReplyState != replyState) {
                        com.callx.app.utils.ChatThemeManager
                                .get(ctx)
                                .applyBubbleOwned(llBubble, h.ownedBubbleDrawable, sent, hasReply);
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
            applyReplyTargetHighlight(h, llBubble, isReplyTarget);
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

                    // BUG FIX (Voice Caption on Photo — combined send renders
                    // as blank grey placeholder): this legacy bubble is the
                    // ONLY path a SENT image ever reaches once m.voiceUrl is
                    // set (see isCanvasEligible), but unlike the Canvas path's
                    // "useLocalSent" local-first render (see bindMedia's
                    // localPendingMedia/useLocalSent handling), it never
                    // checked m.mediaLocalPath at all — it always went
                    // straight to a Glide load of the remote thumbUrl below.
                    // The item flips from Canvas type → this legacy type at
                    // the exact moment finalizeMediaMessage() runs (the
                    // instant m.voiceUrl gets set), i.e. the same frame the
                    // bubble is first shown as "sent" — so there was no time
                    // for the just-uploaded Cloudinary thumbnail to warm up,
                    // and the bubble sat on bg_skeleton_rect (grey) until
                    // that network fetch finally resolved. Mirror the Canvas
                    // path: a sent image whose original local file is still
                    // on the phone renders straight from it, same as every
                    // other sent-image bubble in this app.
                    String mid0 = m.messageId != null ? m.messageId : m.id;
                    boolean useLocalSent = sent && m.mediaLocalPath != null && !m.mediaLocalPath.isEmpty()
                            && Boolean.TRUE.equals(checkLocalAvailabilityAsync(ctx, m.mediaLocalPath, mid0));
                    if (useLocalSent) {
                        final String localPoolKey = m.mediaLocalPath;
                        Bitmap localPoolHit = MEDIA_BITMAP_CACHE.get(localPoolKey);
                        if (localPoolHit != null && !localPoolHit.isRecycled()) {
                            h.ivImage.setImageBitmap(localPoolHit);
                        } else {
                            glide(ctx)
                                    .asBitmap()
                                    .load(android.net.Uri.parse(m.mediaLocalPath))
                                    .apply(THUMB_RGB565)
                                    .override(200, 200)
                                    .placeholder(R.drawable.bg_skeleton_rect)
                                    .listener(new com.bumptech.glide.request.RequestListener<Bitmap>() {
                                        @Override
                                        public boolean onLoadFailed(@Nullable com.bumptech.glide.load.engine.GlideException e,
                                                Object model, com.bumptech.glide.request.target.Target<Bitmap> target,
                                                boolean isFirstResource) {
                                            return false; // fall through to Glide's own error drawable
                                        }
                                        @Override
                                        public boolean onResourceReady(Bitmap resource, Object model,
                                                com.bumptech.glide.request.target.Target<Bitmap> target,
                                                com.bumptech.glide.load.DataSource dataSource, boolean isFirstResource) {
                                            MEDIA_BITMAP_CACHE.put(localPoolKey, resource);
                                            return false;
                                        }
                                    })
                                    .into(h.ivImage);
                        }
                    } else
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
                        Bitmap imgPoolHit = MEDIA_BITMAP_CACHE.get(imgPoolKey);
                        if (imgPoolHit != null && !imgPoolHit.isRecycled()) {
                            h.ivImage.setImageBitmap(imgPoolHit);
                        } else {
                            glide(ctx)
                                .asBitmap()
                                .load(thumbUrl)
                                .apply(THUMB_RGB565)
                                .override(200, 200)
                                // FIX: source thumbUrl is now a 24×24px
                                // compressed thumb (~99% smaller, see
                                // ImageCompressor THUMB_SIZE) upscaled ~8x to
                                // this 200x200 bubble slot — blur it so it
                                // reads as a soft preview instead of hard
                                // blocky pixels (WhatsApp-style pre-download
                                // placeholder look).
                                // PERF (ultra): blur itself now runs at a
                                // 32px working size internally (downscale ->
                                // blur -> upscale, see
                                // TinyThumbBlurTransformation), so radius=3
                                // here is the correctly-rescaled equivalent
                                // of the old radius=16 full-res blur — do
                                // NOT bump this back up to 16, it'll over-
                                // blur at the smaller working resolution.
                                .transform(new com.callx.app.utils.TinyThumbBlurTransformation(3))
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
                                        MEDIA_BITMAP_CACHE.put(imgPoolKey, resource);
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
                        // PERF: force webp (see comment above) instead of
                        // f_auto's per-request negotiation guess.
                        String derivedThumb = com.callx.app.utils.CloudinaryUploader
                                .deriveThumbUrl(fullUrl, 200, "webp");
                        // FIX: same cache-first pattern as the thumbUrl branch
                        // above — otherwise this fallback path reintroduces
                        // the identical rebind-flicker for any image whose
                        // thumbnailUrl upload failed.
                        final String derivedPoolKey = poolKey(derivedThumb, 200, 200);
                        Bitmap derivedPoolHit = MEDIA_BITMAP_CACHE.get(derivedPoolKey);
                        if (derivedPoolHit != null && !derivedPoolHit.isRecycled()) {
                            h.ivImage.setImageBitmap(derivedPoolHit);
                        } else {
                            glide(ctx)
                                .asBitmap()
                                .load(derivedThumb)
                                .apply(THUMB_RGB565)
                                .override(200, 200)
                                // NOTE: no TinyThumbBlurTransformation here —
                                // derivedThumb is a real 200px Cloudinary
                                // webp transform (not the 24×24 ImageCompressor
                                // thumb), so it's already sharp; blurring it
                                // would just throw away quality for nothing.
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
                                        MEDIA_BITMAP_CACHE.put(derivedPoolKey, resource);
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
                            if (actionListener != null) showActionBottomSheet(ctx, m);
                        } else {
                            h.itemView.callOnClick();
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

                    // GAP FIX: this legacy bubble is the ONLY place a
                    // combined image+voice+caption send ever renders (see
                    // isCanvasEligible), but it had no caption view at all —
                    // Canvas's cv.bindMedia(...) draws m.caption itself, the
                    // legacy layout never did, so the caption text the user
                    // typed silently never appeared. Same WhatsApp-style
                    // bottom scrim + text as the multi-photo group caption
                    // (see MediaGroupLayoutHelper) — now applied here too.
                    bindImageCaptionOnLegacyBubble(h, m);
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
                            if (actionListener != null) showActionBottomSheet(ctx, m);
                        } else {
                            h.itemView.callOnClick();
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
                        // FIX: .transform() after .centerCrop() (or vice
                        // versa) overwrites the prior one in Glide — they
                        // must go in via a single MultiTransformation to
                        // both apply. Blur radius here matches the tiny
                        // 24×24-longest-side thumbUrl source (see
                        // ImageCompressor). ORDER FIX: blur now runs BEFORE
                        // centerCrop (was after) — blurring the still-tiny,
                        // aspect-preserved source is cheaper and correct;
                        // blurring AFTER centerCrop meant blurring an
                        // already-upscaled/cropped bubble bitmap, which is
                        // more work and can smear the crop's edges.
                        .transform(new com.bumptech.glide.load.MultiTransformation<>(
                                new com.callx.app.utils.TinyThumbBlurTransformation(3),
                                new com.bumptech.glide.load.resource.bitmap.CenterCrop()))
                        .placeholder(R.drawable.bg_skeleton_rect)
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
                        // Telegram-style chat-wide gallery — see
                        // openChatMediaViewer's doc. WhatsApp-style
                        // local-first still applies (renders from
                        // m.mediaLocalPath full-quality when present).
                        // Also still lets MediaViewerActivity publish
                        // playback presence (chatPlayback/{chatId}/{uid})
                        // via the chatId/messageId extras it sets internally.
                        openChatMediaViewer(ctx, chatId, vMid, -1,
                                vUrl, vUrl, "video", null,
                                m.mediaLocalPath, null,
                                com.callx.app.utils.MediaViewerSourceRect.ofView(h.flVideo),
                                currentUid != null && currentUid.equals(m.senderId));
                    });
                    h.flVideo.setOnLongClickListener(v -> {
                        v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                        if (!multiSelectMode) {
                            enterMultiSelectMode(m);
                            if (actionListener != null) showActionBottomSheet(ctx, m);
                        } else {
                            h.itemView.callOnClick();
                        }
                        return true;
                    });
                } else if (h.ivImage != null) {
                    // Fallback: layout without fl_video — show thumbnail in ivImage
                    h.ivImage.setVisibility(View.VISIBLE);
                    String vUrl     = m.mediaUrl != null ? m.mediaUrl : m.text;
                    // BUG FIX: was falling back to vUrl (the raw video file)
                    // when thumbnailUrl was missing — same mistake called out
                    // above at the flVideo branch (BUG FIX v44): Glide tries
                    // to decode an mp4 as an image and ends up downloading
                    // the whole file. Now: only load when a real thumbnailUrl
                    // exists, else leave the skeleton placeholder up.
                    String thumbUrl = (m.thumbnailUrl != null && !m.thumbnailUrl.isEmpty())
                            ? m.thumbnailUrl : null;
                    if (thumbUrl != null) {
                        glide(ctx).load(thumbUrl)
                            .apply(THUMB_RGB565)
                            .override(thumbPx(ctx), thumbPx(ctx)) // PERF #4: density-aware size
                            .transform(new com.callx.app.utils.TinyThumbBlurTransformation(3))
                            .placeholder(R.drawable.bg_skeleton_rect)
                            .into(h.ivImage);
                    } else {
                        h.ivImage.setImageResource(R.drawable.bg_skeleton_rect);
                    }
                    h.ivImage.setOnClickListener(v -> {
                        if (multiSelectMode) {
                            h.itemView.callOnClick();
                            return;
                        }
                        // Telegram-style chat-wide gallery — see
                        // openChatMediaViewer's doc.
                        openChatMediaViewer(ctx, chatId, vMid, -1,
                                vUrl, vUrl, "video", null,
                                m.mediaLocalPath, null,
                                com.callx.app.utils.MediaViewerSourceRect.ofView(h.ivImage),
                                currentUid != null && currentUid.equals(m.senderId));
                    });
                    // GAP FIX: same missing long-press wiring as the flVideo
                    // branch above — this fallback thumbnail had no way to
                    // reach multi-select/Message Info either.
                    h.ivImage.setOnLongClickListener(v -> {
                        v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                        if (!multiSelectMode) {
                            enterMultiSelectMode(m);
                            if (actionListener != null) showActionBottomSheet(ctx, m);
                        } else {
                            h.itemView.callOnClick();
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
                        // FIX (advance avatar optimization): reused/connected
                        // to the same ChatAvatarBinder pipeline
                        // FollowConnectionsActivity-style rows use — L2/L3
                        // tier reuse, responsive URL, CDN/cache analytics —
                        // instead of a flat un-tiered Glide load, and shares
                        // its L2/L3 cache entries with the canvas reel-share
                        // path above for the same photo.
                        com.callx.app.cache.ChatAvatarBinder.bind(ctx, h.ivReelShareAvatar,
                                avatarUrl, 0L, android.R.drawable.ic_menu_camera,
                                com.callx.app.utils.AvatarSizeTier.forViewSizeDp(24));
                    } else if (!uKey.isEmpty()) {
                        h.ivReelShareAvatar.setImageResource(android.R.drawable.ic_menu_camera);
                        if (reelAvatarFetchInFlight.add(uKey)) {
                            final String fUKey = uKey;
                            final VH fhA = h;
                            final android.content.Context fCtxA = ctx.getApplicationContext();
                            com.google.firebase.database.FirebaseDatabase.getInstance()
                                .getReference("usernames").child(uKey).get()
                                .addOnSuccessListener(idxSnap -> {
                                    String fUid = idxSnap.getValue(String.class);
                                    if (fUid == null || fUid.isEmpty()) { reelAvatarFetchInFlight.remove(fUKey); return; }
                                    com.google.firebase.database.FirebaseDatabase.getInstance()
                                        .getReference("users").child(fUid).get()
                                        .addOnSuccessListener(child -> {
                                            reelAvatarFetchInFlight.remove(fUKey);
                                            if (!child.exists()) return;
                                            String photo = child.child("profileImage").getValue(String.class);
                                            if (photo == null || photo.isEmpty())
                                                photo = child.child("photoUrl").getValue(String.class);
                                            if (photo == null || photo.isEmpty())
                                                photo = child.child("profilePhoto").getValue(String.class);
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
                                                com.callx.app.cache.ChatAvatarBinder.bind(fCtxA, fhA.ivReelShareAvatar,
                                                        photo, 0L, android.R.drawable.ic_menu_camera,
                                                        com.callx.app.utils.AvatarSizeTier.forViewSizeDp(24));
                                            }
                                        })
                                        .addOnFailureListener(e -> reelAvatarFetchInFlight.remove(fUKey));
                                })
                                .addOnFailureListener(e -> reelAvatarFetchInFlight.remove(fUKey));
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
                    String thumbB64 = m.reelShareThumbBase64;
                    String thumb = m.reelShareThumb != null ? m.reelShareThumb : "";
                    String rKey = m.reelId != null ? m.reelId : "";
                    // Tag the row with the reelId it's currently bound to, so an
                    // async Firebase callback returning after this row got
                    // recycled to a different message can detect the mismatch
                    // and skip touching views that no longer belong to it.
                    h.itemView.setTag(R.id.iv_reel_share_thumb, rKey);
                    if (thumb.isEmpty() && (thumbB64 == null || thumbB64.isEmpty()) && !rKey.isEmpty()) {
                        String cachedThumb = reelThumbCache.get(rKey);
                        if (cachedThumb != null) {
                            thumb = cachedThumb;
                            m.reelShareThumb = cachedThumb;
                        }
                    }
                    if (thumbB64 != null && !thumbB64.isEmpty()) {
                        // WhatsApp-level: local decode, off the main thread — see
                        // ReelShareSheetFragment / ThumbnailEmbedder. Was a
                        // synchronous decode with no cache check at all (worse
                        // than the other embedded-thumb spots — this one
                        // re-decoded on EVERY bind, not just cache misses).
                        h.ivReelShareThumb.setImageResource(android.R.color.darker_gray);
                        // PERF/consistency: decodeB64ThumbAsync() decodes the
                        // embedded bytes at full resolution regardless of this
                        // key — the dimensions here only affect the
                        // decoded-pool key, not the actual decode size.
                        // This branch hardcoded a 240×240 key while the URL and
                        // Firebase-fetch branches just below already key on the
                        // card's real density-scaled size via reelCardPx() — so
                        // the same reel's thumbnail could land in the cache
                        // under two different keys depending on which branch
                        // bound it first. Matching the key here lets both
                        // branches share one cache entry for the same reel.
                        int[] cardPxB64 = reelCardPx(ctx);
                        String b64PoolKey = poolKey("b64:" + thumbB64.hashCode(), cardPxB64[0], cardPxB64[1]);
                        final String fRKeyTag = rKey;
                        decodeB64ThumbAsync(thumbB64, b64PoolKey, MEDIA_BITMAP_CACHE, decoded -> {
                            if (h.getBindingAdapterPosition() == RecyclerView.NO_POSITION) return;
                            Object curTag = h.itemView.getTag(R.id.iv_reel_share_thumb);
                            if (!fRKeyTag.equals(curTag)) return; // recycled/rebound to a different row
                            if (decoded != null) {
                                h.ivReelShareThumb.setImageBitmap(decoded);
                            } else {
                                h.ivReelShareThumb.setImageResource(android.R.color.darker_gray);
                            }
                        });
                    } else if (!thumb.isEmpty()) {
                        int[] cardPx = reelCardPx(ctx);
                        glide(ctx)
                                .load(thumb)
                                .apply(THUMB_RGB565)
                                .override(cardPx[0], cardPx[1]) // PERF: decode at the card's real density-scaled size, not a fixed guess
                                .centerCrop()
                                .placeholder(android.R.color.darker_gray)
                                .into(h.ivReelShareThumb);
                    } else if (!rKey.isEmpty() && reelThumbFetchInFlight.add(rKey)) {
                        final VH fh = h;
                        final String fRKey = rKey;
                        final android.content.Context fCtxT = ctx.getApplicationContext();
                        final int[] cardPxFb = reelCardPx(ctx);
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
                                                .override(cardPxFb[0], cardPxFb[1])
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
                                            // FIX (avatar-optimization — reuse core pipeline): was a
                                            // flat, un-tiered 48x48 Glide load straight into the
                                            // ImageView — the one reel-share avatar spot that stayed
                                            // disconnected from ChatAvatarBinder/AvatarBinderCore.
                                            // Now shares the same responsive/version-tagged URL,
                                            // ChatAvatarL2Cache/L3 write-through, and
                                            // AvatarCacheAnalytics recording as the happy-path bind
                                            // above (24dp tier, same cache entries for this photo).
                                            com.callx.app.cache.ChatAvatarBinder.bind(fCtxT, fh.ivReelShareAvatar,
                                                    ap, 0L, android.R.drawable.ic_menu_camera,
                                                    com.callx.app.utils.AvatarSizeTier.forViewSizeDp(24));
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
                        // Keep the preview slot laid out from the first bind.
                        // Previously it stayed INVISIBLE until the async OG
                        // callback, then VISIBLE (and the thumbnail changed
                        // GONE -> VISIBLE), which could trigger two extra
                        // RecyclerView measure/layout passes during a fling.
                        // The slot is now a stable 120dp placeholder; async
                        // work only replaces text/bitmap content.
                        if (h.tvLinkDomain != null) h.tvLinkDomain.setText("");
                        if (h.tvLinkTitle != null) h.tvLinkTitle.setText("");
                        if (h.ivLinkThumb != null) {
                            glide(ctx).clear(h.ivLinkThumb);
                            h.ivLinkThumb.setBackgroundColor(0xFF2A2A2A);
                            h.ivLinkThumb.setImageDrawable(null);
                            h.ivLinkThumb.setVisibility(View.VISIBLE);
                        }
                        h.llLinkPreview.setVisibility(View.VISIBLE);
                        // Tag itemView with URL so we detect stale VH on recycle
                        h.llLinkPreview.setTag(previewUrl);
                        // FIX: must be final for use inside anonymous inner class
                        final String finalPreviewUrl = previewUrl;
                        com.callx.app.utils.LinkPreviewFetcher.fetch(finalPreviewUrl,
                                new com.callx.app.utils.LinkPreviewFetcher.Callback() {
                            @Override public void onResult(com.callx.app.utils.LinkPreviewFetcher.Result r) {
                                // Guard against recycled VH
                                if (!finalPreviewUrl.equals(h.llLinkPreview.getTag())) return;
                                if (h.tvLinkDomain != null) h.tvLinkDomain.setText(r.domain);
                                if (h.tvLinkTitle  != null) h.tvLinkTitle.setText(r.title);
                                if (h.ivLinkThumb  != null) {
                                    if (r.imageUrl != null && !r.imageUrl.isEmpty()) {
                                        glide(ctx)
                                            .load(r.imageUrl)
                                            .apply(THUMB_RGB565)
                                            .override(300, 300)
                                            .centerCrop()
                                            .into(h.ivLinkThumb);
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
            //
            // ULTRA-OPT (resurrected key-derivation bug): this used to call
            // `new SecurityManager(ctx)` directly instead of the singleton
            // accessor SecurityManager.get(ctx) — bypassing the exact fix
            // SecurityManager's own class doc describes (opening a chat
            // with N read/seen sent messages re-ran full AES256 key
            // derivation via EncryptedSharedPreferences.create(), ~100-300ms
            // EACH, once per visible tick instead of once per process).
            if (("read".equals(status) || "seen".equals(status))
                    && !com.callx.app.utils.SecurityManager.get(ctx).isReadReceiptsEnabled()) {
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
        expiryUnregister(h); // v448
        if (h.tvExpiry != null) {
            long expiresAt = m.expiresAt != null ? m.expiresAt : 0L;
            long remaining = expiresAt - System.currentTimeMillis();
            if (expiresAt > 0 && remaining > 0) {
                h.tvExpiry.setVisibility(View.VISIBLE);
                h.tvExpiry.setText("⏳ " + formatRemaining(remaining));
                h.expiryRegistered = true;
                com.callx.app.utils.ExpiryTickManager.get().register(h, expiresAt,
                        new com.callx.app.utils.ExpiryTickManager.Listener() {
                    @Override public void onTick(long ms) {
                        if (h.tvExpiry != null)
                            h.tvExpiry.setText("⏳ " + formatRemaining(ms));
                    }
                    @Override public void onFinish() {
                        h.expiryRegistered = false; // manager already dropped the entry
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
                // WhatsApp-style: select + show top toolbar + open the
                // reaction/action menu all in ONE long-press, not two.
                enterMultiSelectMode(m);
                if (actionListener != null) showActionBottomSheet(ctx, m);
            } else {
                // Already selecting — a long-press on another row just
                // toggles it, same as a normal tap would.
                v.performClick();
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
    /**
     * Feature: Save-audio button (voice-caption-on-photo). Downloads +
     * decrypts (if E2E'd) `m.voiceUrl` — completely independently of
     * whatever's happening with the photo's own mediaUrl/mediaKeyEnc — and
     * writes the plaintext clip into the device's public Music/CallX2
     * folder via MediaStore, exactly the same insert-then-stream-copy
     * pattern MediaViewerActivity#saveCurrentToGallery() already uses for
     * Save-to-gallery on the image/video. Runs entirely off the main
     * thread; only the two Toasts hop back to it.
     */
    private void saveVoiceCaptionToDevice(@NonNull Context ctx, @NonNull Message m) {
        final String url = m.voiceUrl;
        if (url == null || url.isEmpty()) return;
        boolean sent = currentUid != null && currentUid.equals(m.senderId);
        final String messageId = m.messageId != null ? m.messageId : m.id;
        // Own outgoing clip: MediaCache was already seeded with the
        // plaintext at send time (see ChatMediaController
        // #uploadVoiceCaptionThenFinalize's MediaCache.put call) — no key
        // needed, same "sender renders locally" precedent used everywhere
        // else in this file for own-sent media.
        final byte[] voiceKey = (!sent && m.voiceKeyEnc != null)
                ? com.callx.app.utils.MediaE2ECrypto.decryptVoiceCaptionKeyOnly(ctx, m.voiceKeyEnc, m.senderId, messageId)
                : null;
        android.widget.Toast.makeText(ctx, "Saving audio…", android.widget.Toast.LENGTH_SHORT).show();
        com.callx.app.utils.MediaCache.Callback cb = new com.callx.app.utils.MediaCache.Callback() {
            @Override public void onReady(java.io.File source) {
                new Thread(() -> {
                    try {
                        String ext = ".m4a";
                        String lowerUrl = url.toLowerCase(java.util.Locale.ROOT);
                        if (lowerUrl.contains(".mp3")) ext = ".mp3";
                        else if (lowerUrl.contains(".ogg")) ext = ".ogg";
                        else if (lowerUrl.contains(".wav")) ext = ".wav";
                        String displayName = "CallX2_voice_" + System.currentTimeMillis() + ext;
                        android.content.ContentValues values = new android.content.ContentValues();
                        values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, displayName);
                        values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "audio/*");
                        values.put(android.provider.MediaStore.Audio.Media.RELATIVE_PATH, "Music/CallX2");
                        android.net.Uri dest = ctx.getContentResolver()
                                .insert(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values);
                        if (dest == null) throw new java.io.IOException("MediaStore insert failed");
                        try (java.io.InputStream in = new java.io.FileInputStream(source);
                             java.io.OutputStream out = ctx.getContentResolver().openOutputStream(dest)) {
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                        }
                        new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                                android.widget.Toast.makeText(ctx, "Audio saved to Music/CallX2", android.widget.Toast.LENGTH_SHORT).show());
                    } catch (Exception e) {
                        new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                                android.widget.Toast.makeText(ctx, "Couldn't save audio", android.widget.Toast.LENGTH_SHORT).show());
                    }
                }).start();
            }
            @Override public void onError(String reason) {
                android.widget.Toast.makeText(ctx, "Couldn't download audio: " + reason, android.widget.Toast.LENGTH_SHORT).show();
            }
        };
        if (voiceKey != null) {
            com.callx.app.utils.MediaCache.get(ctx, url, voiceKey, cb);
        } else {
            com.callx.app.utils.MediaCache.get(ctx, url, cb);
        }
    }

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
        // A standalone voice-message bubble (type=="audio") is E2E'd under
        // its OWN Message#mediaKeyEnc. A Voice-Caption-on-Photo bubble
        // (type=="image" with voiceUrl set) is a SEPARATE clip with its
        // OWN envelope, Message#voiceKeyEnc — deliberately distinct from
        // the photo's mediaKeyEnc (which is set/unset independently of
        // whether a voice caption is attached; see
        // ChatMediaController#uploadVoiceCaptionThenFinalize). Grabbing the
        // wrong field here "decrypts" the clip with the wrong key and
        // corrupts it into unplayable garbage even though the download
        // itself succeeds — so each type reads its own matching field.
        Message audioMsg = getItem(position);
        boolean isVoiceCaptionClip = audioMsg != null && "image".equals(audioMsg.type)
                && audioMsg.voiceUrl != null && audioMsg.voiceUrl.equals(url);
        String audioMsgId = audioMsg != null
                ? (audioMsg.messageId != null ? audioMsg.messageId : audioMsg.id) : null;
        byte[] audioKey;
        if (audioMsg != null && "audio".equals(audioMsg.type)
                && !currentUid.equals(audioMsg.senderId) && audioMsg.mediaKeyEnc != null) {
            audioKey = com.callx.app.utils.MediaE2ECrypto.decryptKeyOnly(h.itemView.getContext(),
                    audioMsg.mediaKeyEnc, audioMsg.senderId, audioMsgId);
        } else if (isVoiceCaptionClip && !currentUid.equals(audioMsg.senderId) && audioMsg.voiceKeyEnc != null) {
            // BUG FIX: was decryptKeyOnly() (same cache slot as the photo's
            // mediaKeyEnc, keyed off the same messageId) — see
            // MediaE2ECrypto#decryptVoiceCaptionKeyOnly's javadoc for why
            // that silently broke playback with the photo's key instead.
            audioKey = com.callx.app.utils.MediaE2ECrypto.decryptVoiceCaptionKeyOnly(h.itemView.getContext(),
                    audioMsg.voiceKeyEnc, audioMsg.senderId, audioMsgId);
        } else {
            audioKey = null;
        }
        if (audioKey != null) {
            // Feature: "downloading…" badge state — only the voice-caption
            // canvas badge models this state right now; standalone audio
            // bubbles keep their existing "pause glyph shown early" look.
            if (isVoiceCaptionClip && h.canvasView != null) h.canvasView.setVoiceDownloading(true);
            com.callx.app.utils.MediaCache.get(h.itemView.getContext(), url, audioKey,
                    new com.callx.app.utils.MediaCache.Callback() {
                @Override public void onReady(java.io.File file) {
                    playAudioFromPath(h, file.getAbsolutePath(), position);
                }
                @Override public void onError(String reason) {
                    android.util.Log.w("AudioPlay", "E2E audio decrypt/download failed: " + reason);
                    if (isVoiceCaptionClip && h.canvasView != null) h.canvasView.setVoiceDownloading(false);
                }
            });
            return;
        }

        // FIX v14: MediaStreamCache use karo — pehle 512KB stream karo (fast start),
        // baaki background mein download hota rahe. User ko buffer nahi karega.
        if (isVoiceCaptionClip && h.canvasView != null) h.canvasView.setVoiceDownloading(true);
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
            // Feature: "downloading…" badge state — clear it here (single
            // choke point for every call site: cache-hit, E2E decrypt
            // success, and MediaStreamCache success/fallback) rather than
            // duplicating the clear in each caller.
            if (h.canvasView != null) h.canvasView.setVoiceDownloading(false);
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
            // Feature: elapsed/total on the voice-caption-on-photo badge —
            // distinguishes that case from a standalone audio bubble so
            // the seekUpdater below (shared by both) drives the right
            // canvas fields for each (see MessageBubbleCanvasView#
            // setVoiceElapsedText vs #setAudioElapsedText).
            final boolean __isVoiceCaptionOnPhoto = __voiceMsg != null && "image".equals(__voiceMsg.type)
                    && __voiceMsg.voiceUrl != null && !__voiceMsg.voiceUrl.isEmpty();
            
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
                if (h.canvasView != null) h.canvasView.setVoiceSpeedLabel("1×");
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
                                    if (__isVoiceCaptionOnPhoto) {
                                        // No waveform/seek-progress modeled for
                                        // this compact badge (see setVoiceCaption's
                                        // javadoc) — just the live elapsed label.
                                        h.canvasView.setVoiceElapsedText(elapsed);
                                    } else {
                                        if (durationMs > 0) h.canvasView.setAudioProgress((float) cur / durationMs);
                                        h.canvasView.setAudioElapsedText(elapsed);
                                    }
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
     * about h.ivVoicePlayOnImage). BUG FIX: this used to auto-play once
     * for the receiver (walkie-talkie style), tracked in a set on the
     * adapter instance — which meant every fresh chat-screen open (a new
     * adapter) forgot the set and replayed an already-heard caption the
     * moment its bubble scrolled into view. Removed; tap-to-play only now,
     * same as a standalone voice message.
     */
    private void bindVoiceOnImage(@NonNull VH h, @NonNull Message m, int position) {
        boolean hasVoice = m.voiceUrl != null && !m.voiceUrl.isEmpty();
        if (h.flVoiceOnImage == null) {
            // v425: overlay lives behind a ViewStub — only pay for it if this
            // bind actually has a voice caption to show.
            if (!hasVoice || !ensureLegacyVoiceOverlay(h)) return;
        }
        if (!hasVoice) {
            h.flVoiceOnImage.setVisibility(View.GONE);
            return;
        }
        h.flVoiceOnImage.setVisibility(View.VISIBLE);

        // Duration pill text — mirrors the audio bubble's mm:ss formatting.
        if (h.tvVoiceDurationOnImage != null) {
            long ms = m.voiceDuration != null ? m.voiceDuration : 0L;
            // v425 PERF: was String.format() per bind — shared cached formatter.
            h.tvVoiceDurationOnImage.setText(formatVoiceDuration(ms));
        }

        // Icon reflects whether THIS message's voice note is the one
        // currently playing (matches how the standalone audio bubble icon
        // is kept in sync across rebinds).
        boolean isThisPlaying = playingPos == position && player != null && isPlayerPlaying;
        if (h.ivVoicePlayOnImage != null) {
            h.ivVoicePlayOnImage.setImageResource(isThisPlaying ? R.drawable.ic_pause : R.drawable.ic_play);
        }

        // Click listener is set ONCE, when ensureLegacyVoiceOverlay() inflates
        // the overlay (v425) — no per-bind allocation here.
    }

    /**
     * v425 PERF: inflates the legacy voice-badge + caption-strip overlay
     * (layout_msg_voice_on_image.xml) the first time a legacy holder truly
     * needs it, wires the tap listener that used to be set eagerly in
     * onCreateViewHolder, and caches the child view refs on the VH.
     * @return true if the overlay views are now available.
     */
    private boolean ensureLegacyVoiceOverlay(@NonNull VH h) {
        if (h.flVoiceOnImage != null) return true;
        final android.view.ViewStub stub = h.stubVoiceOnImage;
        if (stub == null) return false;
        h.stubVoiceOnImage = null; // ViewStub.inflate() is one-shot
        final View root;
        try {
            root = stub.inflate();
        } catch (RuntimeException e) {
            return false;
        }
        h.flVoiceOnImage         = root.findViewById(R.id.fl_voice_on_image);
        h.ivVoicePlayOnImage     = root.findViewById(R.id.iv_voice_play_on_image);
        h.tvVoiceDurationOnImage = root.findViewById(R.id.tv_voice_duration_on_image);
        h.viewImageCaptionScrim  = root.findViewById(R.id.view_image_caption_scrim);
        h.tvImageCaption         = root.findViewById(R.id.tv_image_caption);
        if (h.flVoiceOnImage != null) {
            // Same listener onCreateViewHolder used to build eagerly: reads
            // h.boundMessage + the live adapter position at CLICK time.
            h.flVoiceOnImage.setOnClickListener(badgeView -> {
                Message cm = h.boundMessage;
                if (cm == null || cm.voiceUrl == null || cm.voiceUrl.isEmpty()) return;
                int pos = h.getBindingAdapterPosition();
                MessagePagingAdapter ad = h.canvasListenerOwner != null ? h.canvasListenerOwner : this;
                if (pos != RecyclerView.NO_POSITION) ad.toggleAudio(h, cm.voiceUrl, pos);
            });
        }
        return h.flVoiceOnImage != null;
    }

    /**
     * Feature: Voice Caption on Photo — WhatsApp-style caption strip for the
     * legacy image bubble (see the call site's comment for why this exists).
     * Mirrors MediaGroupLayoutHelper's group-caption: bottom-pinned gradient
     * scrim + white text, overlapping the photo. When a voice note is ALSO
     * attached (the only way this legacy bubble is ever reached), the
     * play-badge pill is nudged up above the caption strip so the two never
     * overlap — see the extra bottom margin added to fl_voice_on_image below.
     *
     * PERF: the scrim's gradient is @drawable/bg_image_caption_scrim, set
     * directly in item_message_sent/received.xml — no setBackground() call
     * here, so no per-bind Drawable work at all, just the two visibility
     * flips below.
     */
    private void bindImageCaptionOnLegacyBubble(@NonNull VH h, @NonNull Message m) {
        boolean hasCaption = m.caption != null && !m.caption.isEmpty();
        if (h.tvImageCaption == null || h.viewImageCaptionScrim == null) {
            // v425: overlay lives behind a ViewStub — only inflate it when
            // there is actually a caption to show.
            if (!hasCaption || !ensureLegacyVoiceOverlay(h)) return;
        }
        if (!hasCaption) {
            h.tvImageCaption.setVisibility(View.GONE);
            h.viewImageCaptionScrim.setVisibility(View.GONE);
            resetVoiceOnImageBottomMargin(h);
            return;
        }
        h.viewImageCaptionScrim.setVisibility(View.VISIBLE);
        h.tvImageCaption.setText(m.caption);
        h.tvImageCaption.setVisibility(View.VISIBLE);

        // Nudge the play-badge pill up above the caption strip (its own
        // bottom-gravity margin is 8dp normally — see fl_voice_on_image in
        // item_message_sent/received.xml) so a captioned voice-photo shows
        // both without the badge sitting on top of the caption text.
        if (h.flVoiceOnImage != null
                && h.flVoiceOnImage.getLayoutParams() instanceof android.widget.FrameLayout.LayoutParams) {
            android.widget.FrameLayout.LayoutParams lp = (android.widget.FrameLayout.LayoutParams) h.flVoiceOnImage.getLayoutParams();
            int marginPx = (int) (52 * h.itemView.getResources().getDisplayMetrics().density);
            if (lp.bottomMargin < marginPx) {
                lp.bottomMargin = marginPx;
                h.flVoiceOnImage.setLayoutParams(lp);
            }
        }
    }

    /** Restores fl_voice_on_image's default 8dp bottom margin for a
     *  recycled holder whose PREVIOUS bind had a caption (and so bumped the
     *  margin up) but this one doesn't — otherwise the badge stays
     *  incorrectly raised after scrolling past a captioned bubble. */
    private void resetVoiceOnImageBottomMargin(@NonNull VH h) {
        if (h.flVoiceOnImage == null
                || !(h.flVoiceOnImage.getLayoutParams() instanceof android.widget.FrameLayout.LayoutParams)) return;
        android.widget.FrameLayout.LayoutParams lp = (android.widget.FrameLayout.LayoutParams) h.flVoiceOnImage.getLayoutParams();
        int defaultPx = (int) (8 * h.itemView.getResources().getDisplayMetrics().density);
        if (lp.bottomMargin != defaultPx) {
            lp.bottomMargin = defaultPx;
            h.flVoiceOnImage.setLayoutParams(lp);
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
            // PERF: m.fileSize-first, same as the canvas media branches — see
            // MessagePagingAdapter's isImage/isGif/isSticker bind blocks.
            if (m.fileSize != null && m.fileSize > 0) {
                h.tv_download_size.setText(formatFileSize(m.fileSize));
            } else {
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
        }

        h.ll_download_pill.setOnClickListener(v -> {
            if (downloadingMediaUrls.contains(fullUrl)) return; // already in flight
            downloadingMediaUrls.add(fullUrl);
            setDownloadPillState(h, true, 0, null);

            // Progressive-JPEG sharpen-while-downloading — see the matching
            // comment in the canvas path's onMediaDownloadClick. This legacy
            // View-based bubble has no live media ImageView to update mid-
            // download (only the pill shows progress; the ImageView swaps in
            // once at onReady), so there's no onPartialBitmap hookup here —
            // just the safe fetch-URL swap for plaintext images.
            final String legacyFetchUrl = (dlMediaKey == null)
                    ? com.callx.app.utils.CloudinaryUploader.deriveProgressiveFullUrl(fullUrl)
                    : fullUrl;
            com.callx.app.utils.MediaCache.getWithProgress(ctx, fullUrl, legacyFetchUrl, dlMediaKey, null,
                    new com.callx.app.utils.MediaCache.ProgressCallback() {
                @Override public void onProgress(int percent) {
                    if (!fullUrl.equals(h.fl_download_overlay.getTag())) return;
                    setDownloadPillState(h, true, percent, null);
                }
                @Override public void onReady(java.io.File file) {
                    downloadingMediaUrls.remove(fullUrl);
                    // PERF/UX: see matching comment on the canvas download-gate
                    // path (onMediaDownloadClick) — same explicit-tap-to-save.
                    com.callx.app.utils.MediaSaveHelper.save(ctx, file, "image", fullUrl,
                            new com.callx.app.utils.MediaSaveHelper.Callback() {
                                @Override public void onSaved(android.net.Uri uri) {}
                                @Override public void onError(String reason) {}
                            });
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
    /**
     * Telegram-style chat-wide media gallery — shared by every entry point
     * that opens MediaViewerActivity for a real chat message (the action
     * sheet's View/Play, and the video-download-gate "download → play"
     * direct-open paths): builds the full ordered photo/video list for
     * {@code chatId} off the UI thread (see ChatMediaGalleryBuilder +
     * MessageDao#getChatMediaRows), so swipe left/right in the viewer walks
     * through every media item in the chat, not just the tapped message.
     * Falls back to the old single-item / single-group Intent (using
     * fallbackMediaItemsJson/tappedSubIndex if present) when there's no
     * chatId/messageId to key the lookup off, the query fails, this item
     * hasn't synced into Room yet, or the chat simply has only this one
     * media item.
     *
     * @param fallbackMediaItemsJson pre-built single-message-group JSON
     *                               (grouped-media grid tap), or null for a
     *                               single image/video tap — only used by
     *                               the fallback path.
     * @param tappedSubIndex         index within fallbackMediaItemsJson
     *                               (or -1 when it's null) — also doubles
     *                               as the tapped cell index used to locate
     *                               the right position once the chat-wide
     *                               list resolves.
     */
    private void openChatMediaViewer(Context ctx, @Nullable String chatId, @Nullable String tappedMessageId,
                                      int tappedSubIndex, String fallbackUrl, String fallbackThumb,
                                      String mediaType, @Nullable String fallbackMediaItemsJson,
                                      @Nullable String localPath, @Nullable String mediaKeyB64,
                                      @Nullable android.graphics.Rect srcRect, boolean isOwnMessage) {
        openChatMediaViewer(ctx, chatId, tappedMessageId, tappedSubIndex, fallbackUrl, fallbackThumb,
                mediaType, fallbackMediaItemsJson, localPath, mediaKeyB64, srcRect, isOwnMessage,
                null, null, null);
    }

    // Feature: Voice Caption on Photo — carries the attached voice note
    // through to MediaViewerActivity's fullscreen view too, not just the
    // chat bubble (was chat-only before this overload; the fullscreen
    // viewer had no way to know a voice note existed at all). voiceUrl/
    // voiceDurationText/voiceKeyB64 are null for any image without one —
    // showMediaActionSheet's hasVoiceCaption gate is the only place that
    // ever passes non-null here; every other call site (video, grouped
    // media) keeps going through the 12-arg overload above, which forwards
    // nulls, so nothing else changes behavior.
    private void openChatMediaViewer(Context ctx, @Nullable String chatId, @Nullable String tappedMessageId,
                                      int tappedSubIndex, String fallbackUrl, String fallbackThumb,
                                      String mediaType, @Nullable String fallbackMediaItemsJson,
                                      @Nullable String localPath, @Nullable String mediaKeyB64,
                                      @Nullable android.graphics.Rect srcRect, boolean isOwnMessage,
                                      @Nullable String voiceUrl, @Nullable String voiceDurationText,
                                      @Nullable String voiceKeyB64) {
        // (Plain local interface, not java.util.function.Consumer — minSdk
        // 23 here has no core-library desugaring set up.)
        final MediaViewerExtrasAttacher attachCommonExtras = i2 -> {
            if (localPath != null && !localPath.isEmpty()) {
                i2.putExtra("localPath", localPath);
            }
            // FIX: chatId/messageId weren't passed before, so
            // MediaViewerActivity's own Edit pencil couldn't work once
            // inside the viewer either.
            if (chatId != null && tappedMessageId != null) {
                i2.putExtra("chatId",    chatId);
                i2.putExtra("messageId", tappedMessageId);
            }
            i2.putExtra("isOwnMessage", isOwnMessage);
            if (mediaKeyB64 != null) {
                i2.putExtra("mediaKeyB64", mediaKeyB64);
            }
            // Feature: Voice Caption on Photo — same play-badge drawables
            // (bg_voice_duration_pill / bg_voice_play_badge / ic_play /
            // ic_pause) the chat bubble uses, reused as-is by
            // MediaViewerActivity's own fl_voice_on_image overlay.
            if (voiceUrl != null && !voiceUrl.isEmpty()) {
                i2.putExtra("voiceUrl", voiceUrl);
                i2.putExtra("voiceDurationText", voiceDurationText);
                if (voiceKeyB64 != null) {
                    i2.putExtra("voiceKeyB64", voiceKeyB64);
                }
            }
            // Telegram-style open/close animation — see
            // MediaViewerSourceRect class doc. No-op if srcRect is null.
            com.callx.app.utils.MediaViewerSourceRect.attach(i2, srcRect);
        };

        Runnable openSingleOrGroup = () -> {
            android.content.Intent i2 = new android.content.Intent()
                    .setClassName(ctx.getPackageName(),
                            "com.callx.app.activities.MediaViewerActivity");
            i2.putExtra("url",      fallbackUrl);
            i2.putExtra("thumbUrl", fallbackThumb);
            i2.putExtra("type",     mediaType);
            if (fallbackMediaItemsJson != null) {
                i2.putExtra("mediaItemsJson", fallbackMediaItemsJson);
                i2.putExtra("startIndex", tappedSubIndex);
            }
            attachCommonExtras.accept(i2);
            ctx.startActivity(i2);
        };

        // Telegram-style: swipe left/right walks through EVERY photo/video
        // in this chat, not just this one message's group — built fresh
        // from Room (cheap, (chatId,timestamp)-indexed query) on a
        // background thread so the tap itself never blocks the UI, and
        // completely decoupled from the chat screen's own scroll/bind
        // path, so normal chat performance is unaffected either way.
        if (chatId == null || tappedMessageId == null) {
            openSingleOrGroup.run();
            return;
        }
        GALLERY_BUILD_EXECUTOR.execute(() -> {
            com.callx.app.utils.ChatMediaGalleryBuilder.Window win;
            try {
                com.callx.app.db.dao.MessageDao dao =
                        com.callx.app.db.AppDatabase.getInstance(ctx).messageDao();
                com.callx.app.db.ChatMediaFreshness freshness = dao.getChatMediaFreshness(chatId);
                // #1 In-memory gallery cache: peek() answers instantly (no
                // row query) if this chat is cached and still fresh; only
                // on a miss do we pay for the real row fetch + flatten.
                com.callx.app.utils.ChatMediaGalleryBuilder.Result res =
                        com.callx.app.utils.ChatMediaGalleryBuilder.peek(
                                chatId, freshness, tappedMessageId, tappedSubIndex);
                if (res == null) {
                    java.util.List<com.callx.app.db.ChatMediaRow> rows = dao.getChatMediaRows(chatId);
                    res = com.callx.app.utils.ChatMediaGalleryBuilder.resolve(
                            chatId, rows, freshness, tappedMessageId, tappedSubIndex);
                }
                // #2 Windowed loading: only ±WINDOW_RADIUS items go through
                // the Intent, not the whole (possibly huge) chat gallery —
                // MediaViewerActivity pulls further windows from the same
                // cache as the user swipes toward either edge.
                win = com.callx.app.utils.ChatMediaGalleryBuilder.window(res);
            } catch (Exception e) {
                win = null;
            }
            final com.callx.app.utils.ChatMediaGalleryBuilder.Window finalWin = win;
            ((android.app.Activity) ctx).runOnUiThread(() -> {
                if (finalWin == null || finalWin.localStartIndex < 0 || finalWin.items.size() <= 1) {
                    // Query failed, this media hasn't synced into Room yet,
                    // or it's the only media item in the chat — single-item
                    // viewer is correct either way.
                    openSingleOrGroup.run();
                    return;
                }
                android.content.Intent i2 = new android.content.Intent()
                        .setClassName(ctx.getPackageName(),
                                "com.callx.app.activities.MediaViewerActivity");
                // #5 Serialization skip: hand the actual in-memory list
                // across via GalleryIntentHolder instead of JSON-encoding
                // it into a Binder-limited Intent extra — same process,
                // so no serialize/deserialize round-trip and zero
                // TransactionTooLargeException risk regardless of window
                // size. Plain-string fallback extras (url/thumbUrl/type)
                // still ride the Intent as normal so a stale/missed
                // token (e.g. process death between put() and onCreate())
                // degrades to the single-image view instead of a blank
                // screen — see GalleryIntentHolder's class doc.
                int galleryToken = com.callx.app.utils.GalleryIntentHolder.put(finalWin.items);
                i2.putExtra("galleryItemsToken", galleryToken);
                i2.putExtra("startIndex", finalWin.localStartIndex);
                i2.putExtra("url",      fallbackUrl);
                i2.putExtra("thumbUrl", fallbackThumb);
                i2.putExtra("type",     mediaType);
                // Lets MediaViewerActivity grow the window (from the same
                // in-memory cache, no extra DB hit) as the user nears
                // either edge — see ChatMediaGalleryBuilder#slice.
                i2.putExtra("galleryChatId", chatId);
                i2.putExtra("galleryWindowStart", finalWin.windowStartGlobal);
                i2.putExtra("galleryTotalCount", finalWin.totalCount);
                attachCommonExtras.accept(i2);
                ctx.startActivity(i2);
            });
        });
    }

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
        // REMOVED (WhatsApp-style direct open): this used to build a
        // BottomSheetDialog with View/Edit/Save/Share/Forward/Star/Delete
        // and make the user pick "View"/"Play" before the viewer actually
        // opened. Tapping media now opens MediaViewerActivity straight
        // away — View/Edit/Save/Share are already top-bar icons there, and
        // Forward/Star/Delete (the three actions that only existed in this
        // sheet) now live inside MediaViewerActivity's own "more options"
        // (ℹ) menu — see MediaViewerActivity#showMoreOptionsMenu().
        final String sheetMessageId = (m.messageId != null && !m.messageId.isEmpty()) ? m.messageId : m.id;
        boolean isOwnMsg = currentUid != null && currentUid.equals(m.senderId);

        // Media E2E — a Media-E2E image/video's fullUrl is CIPHERTEXT
        // (resource_type=raw); derive the full-res subkey the same way the
        // old sheet's View/Save actions did so the viewer can decrypt it.
        final byte[] sheetMediaKey = (!isOwnMsg && m.mediaKeyEnc != null)
                ? com.callx.app.utils.MediaE2ECrypto.decryptKeyOnly(ctx, m.mediaKeyEnc,
                        m.senderId, sheetMessageId)
                : null;
        final String sheetMediaKeyB64 = (sheetMediaKey != null)
                ? android.util.Base64.encodeToString(sheetMediaKey, android.util.Base64.NO_WRAP)
                : null;

        final String localPath = localPathHint != null ? localPathHint : m.mediaLocalPath;

        // Feature: Voice Caption on Photo — thread the attached voice note
        // through to MediaViewerActivity too (mediaItemsJson != null means
        // this tap opens the grouped gallery instead, which has its own
        // per-page message lookup — see GalleryPagerAdapter — so the voice
        // badge there is out of scope for this single-item path).
        final boolean hasVoiceCaption = mediaItemsJson == null && "image".equals(mediaType)
                && m.voiceUrl != null && !m.voiceUrl.isEmpty();
        String voiceUrlForViewer = null, voiceDurationTextForViewer = null, voiceKeyB64ForViewer = null;
        if (hasVoiceCaption) {
            voiceUrlForViewer = m.voiceUrl;
            voiceDurationTextForViewer = formatVoiceDuration(m.voiceDuration != null ? m.voiceDuration : 0L);
            // BUG FIX: same collision as toggleAudio() above — must use the
            // voice-specific cache-key variant, not the plain decryptKeyOnly
            // that the photo's own mediaKeyEnc (sheetMediaKey, above) uses.
            byte[] sheetVoiceKey = (!isOwnMsg && m.voiceKeyEnc != null)
                    ? com.callx.app.utils.MediaE2ECrypto.decryptVoiceCaptionKeyOnly(ctx, m.voiceKeyEnc,
                            m.senderId, sheetMessageId)
                    : null;
            voiceKeyB64ForViewer = (sheetVoiceKey != null)
                    ? android.util.Base64.encodeToString(sheetVoiceKey, android.util.Base64.NO_WRAP)
                    : null;
        }
        openChatMediaViewer(ctx, chatId, sheetMessageId, startIndex,
                fullUrl, thumbForViewer, mediaType, mediaItemsJson,
                localPath, sheetMediaKeyB64, srcRect, isOwnMsg,
                voiceUrlForViewer, voiceDurationTextForViewer, voiceKeyB64ForViewer);
    }


    private int dp(Context ctx, int value) {
        return (int)(value * ctx.getResources().getDisplayMetrics().density);
    }

    /** Pulls a reused View out of its previous parent (if any) so it can be
     *  re-added to a new wrapper — a View can only ever have one parent, and
     *  cachedEmojiBar/cachedEmojiGrid are now reused across dialog opens
     *  instead of being recreated each time. */
    private void detachFromParent(android.view.View v) {
        android.view.ViewParent p = v.getParent();
        if (p instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) p).removeView(v);
        }
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
        // PERF ADV 2: that single View is now built once and reused across
        // every long-press too, instead of `new`-ing a fresh instance (with
        // fresh Paint/RectF fields) each time — detachFromParent() below
        // pulls it out of whichever previous dialog's wrapper still holds
        // it before this call re-parents it.
        if (cachedEmojiBar == null) cachedEmojiBar = new com.callx.app.conversation.canvas.ReactionQuickBarCanvasView(ctx);
        com.callx.app.conversation.canvas.ReactionQuickBarCanvasView emojiBar = cachedEmojiBar;
        detachFromParent(emojiBar);
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

        // PERF ADV 2: reused across opens, same as the quick-react bar above
        // — detachFromParent() pulls it out of whichever previous
        // ScrollView still holds it.
        if (cachedEmojiGrid == null) cachedEmojiGrid = new com.callx.app.conversation.canvas.ReactionGridCanvasView(ctx);
        com.callx.app.conversation.canvas.ReactionGridCanvasView grid = cachedEmojiGrid;
        detachFromParent(grid);

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
            int n = opts.size();
            // PERF: WhatsApp-level zero-alloc for the live vote-count path —
            // reuse this holder's scratch buffers when the option count
            // hasn't changed (the overwhelming common case: a vote arriving
            // never changes how many options a poll has), only grow them
            // when it actually has. See countVotes(...,out) overload.
            if (h.pollCountsScratch == null || h.pollCountsScratch.length != n) {
                h.pollCountsScratch = new int[n];
            }
            if (h.pollMyVoteScratch == null || h.pollMyVoteScratch.length != n) {
                h.pollMyVoteScratch = new boolean[n];
            } else {
                java.util.Arrays.fill(h.pollMyVoteScratch, false);
            }
            int[] counts = com.callx.app.utils.PollJsonUtil.countVotes(votesMap, n, h.pollCountsScratch);
            int total    = com.callx.app.utils.PollJsonUtil.totalVotes(votesMap);
            boolean[] myVote = h.pollMyVoteScratch;
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
            bindPollVoters(h.canvasView, m, h.itemView.getContext(), true); // live-vote path: force recompute
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
        holder.cancelReusableBitmapTargets(glide(ctx));
        if (holder.imageBindTarget != null) glide(ctx).clear(holder.imageBindTarget);
        // PERF ADV #2: cancel any in-flight link-preview thumbnail
        // network+decode the instant this holder is recycled, instead of
        // letting it run to completion for an off-screen row (previously
        // only guarded by a tag check on arrival — the work itself kept
        // running). Safe no-op if no link-preview load was ever fired for
        // this holder (linkPreviewTarget stays null until first use).
        if (holder.linkPreviewTarget != null) glide(ctx).clear(holder.linkPreviewTarget);
        // Same staleness-guard idea as bindCanvasMessage()'s Glide
        // CustomTarget calls — invalidate any in-flight canvas image/reply-
        // thumb load the instant this holder is recycled.
        holder.canvasBindToken++;
        // v426 PERF: this row was scrolled away — if its auto-download is
        // still WAITING in the queue (not started), drop it so a fast fling
        // through 100 photos doesn't queue 100 downloads for rows nobody is
        // looking at. Neither onReady nor onError fires for a dropped job, so
        // clear the "downloading" marker here or a later rebind would show a
        // spinner with nothing behind it. A download that already started
        // keeps running (its result still warms the cache).
        if (holder.autoDlUrl != null) {
            final String dropUrl = holder.autoDlUrl;
            holder.autoDlUrl = null;
            if (MediaDownloadQueue.cancelPending(dropUrl)) downloadingMediaUrls.remove(dropUrl);
        }
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
        expiryUnregister(holder); // v448
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
        // ULTRA-OPT: same resurrected key-derivation bug as bindMessage()'s
        // twin block above — SecurityManager.get() (singleton), not `new`.
        // This fast-path runs on every live read-receipt broadcast, so the
        // old `new SecurityManager(...)` here was arguably worse than the
        // one in the full-bind path: it re-ran full AES256 key derivation
        // on every single incoming "seen" tick update, not just on bind.
        if (("read".equals(s) || "seen".equals(s))
                && !com.callx.app.utils.SecurityManager.get(h.itemView.getContext()).isReadReceiptsEnabled()) {
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

    /**
     * ULTRA-OPT: applies (or clears) the "someone is replying to this
     * message" bubble glow without re-inflating a Drawable on every call.
     * See the ownedReplyTargetDrawable/lastReplyTargetState field doc in VH
     * for the full reasoning. Shared by both bindMessage()'s full path and
     * bindPresenceOnly()'s payload-only fast path so the two can't drift.
     */
    private void applyReplyTargetHighlight(@NonNull VH h, @NonNull View llBubble, boolean isReplyTarget) {
        int state = isReplyTarget ? 1 : 0;
        if (h.lastReplyTargetState == state) return; // already showing the right thing
        h.lastReplyTargetState = state;
        if (isReplyTarget) {
            if (h.ownedReplyTargetDrawable == null) {
                h.ownedReplyTargetDrawable =
                        ContextCompat.getDrawable(llBubble.getContext(), R.drawable.bg_reply_target_highlight);
            }
            llBubble.setForeground(h.ownedReplyTargetDrawable);
        } else {
            llBubble.setForeground(null);
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

        if (h.canvasView != null) {
            // FEATURE PARITY: canvas-rendered bubbles previously had no
            // equivalent of the legacy h.viewSeenDot/h.tvListeningBadge
            // views below, so a viewing/playing presence broadcast just
            // silently did nothing for them. Wire the same two states onto
            // MessageBubbleCanvasView's own dirty-rect setters (see their
            // doc — presence updates never trigger a relayout).
            boolean viewing = mid != null && currentlyViewedMessageIds.contains(mid);
            h.canvasView.setViewingDot(viewing);

            boolean playing = mid != null && currentlyPlayingMessageIds.contains(mid);
            String badgeLabel = null;
            if (playing) {
                boolean isVideoMsg = "video".equals(m.type);
                badgeLabel = isVideoMsg ? "▶ watching…" : "🎧 listening…";
            }
            h.canvasView.setPlayingBadge(playing, badgeLabel);
            return;
        }

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
            applyReplyTargetHighlight(h, h.llBubble, isReplyTarget);
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
        int position = findMessagePositionById(messageId);
        if (position == RecyclerView.NO_POSITION) return;
        Message m = getItem(position);
        if (m == null) return;
        if (m.reactions == null) m.reactions = new java.util.LinkedHashMap<>();
        if (removing) {
            m.reactions.remove(uid);
        } else {
            m.reactions.put(uid, emoji);
        }
        m.cachedReactionBound = null; // v444: in-place mutation → drop the memoized badge/strip
        notifyItemChanged(position, PAYLOAD_REACTIONS);
    }

    /** Fast-path: rebind ONLY the reactions row. Called both from the full
     *  bindMessage() path AND from the payload-aware onBind for
     *  PAYLOAD_REACTIONS (someone tapped/removed an emoji), so a reactions
     *  update no longer re-runs the whole bindMessage() (no Glide reload,
     *  no Linkify, no new GradientDrawable, no countdown restart) just to
     *  refresh a 1-line TextView. */
    private void bindReactionsOnly(@NonNull VH h, @NonNull Message m) {
        if (h.canvasView != null) {
            final ReactionBound rxb = reactionBoundFor(m);
            final String formatted = rxb != null ? rxb.text : null;
            // Canvas path: setReactions()/clearReactions() handles its own
            // requestLayout()+invalidate(), and the tap target is wired
            // via onReactionsClick() in bindCanvasMessage()'s click
            // listener (set once per full bind — no separate listener to
            // reattach here, unlike the legacy llReactions view).
            if (formatted != null) h.canvasView.setReactions(formatted);
            else h.canvasView.clearReactions();
            applyReactionAvatars(h.canvasView, rxb, h.itemView.getContext());
            return;
        }
        String formatted = formatReactions(m.reactions);
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
     * Partial-rebind path for PAYLOAD_READ_BY (and the tail of a full canvas
     * bind): refreshes the "Seen by" reader-avatar strip under one of MY sent
     * group bubbles. Canvas rows only — a legacy (non-canvas) sent bubble has
     * no strip, which is also why canShowSeenBy() requires TYPE_CANVAS_SENT
     * (readers are moved down to the next own row only if THAT row can show them).
     */
    private void bindSeenByStrip(@NonNull VH h, @NonNull com.callx.app.models.Message m, int position) {
        if (h.canvasView == null) return; // legacy holder — no strip
        bindSeenByAvatars(h, m, position);
    }

    // ── "Seen by" avatar strip (v437) ─────────────────────────────────────
    // Under one of MY sent group bubbles: small overlapping circles of the
    // members who have read it — Messenger-style, i.e. each reader shows
    // under the LAST of my messages they've read (their "read up to here"
    // marker), not repeated under every older message they also read.
    // A reader is therefore dropped from row N as soon as the next own row
    // below it also lists them in readBy.
    //
    // Staleness (same trap as the run-tail avatar): row N's strip depends on
    // the NEXT own row's readBy, which DiffUtil never rebinds when only that
    // neighbour changed. Fixed by refreshSeenByOwnRowAbove(), fired whenever a
    // row's readBy changes (notifyReadByChanged / applyRealtimeUpdate) and
    // whenever own rows are inserted/removed (constructor observer).
    private static final int SEEN_BY_MAX_CIRCLES = 5;      // avatars + optional "+N" chip
    private static final int SEEN_BY_SCAN_ROWS   = 80;     // how far to look for the neighbouring own row

    private static final class SeenBySelection {
        final String[] uids; // earliest readers first, at most SEEN_BY_MAX_CIRCLES (or MAX-1 when a "+N" chip follows)
        final int total;     // all readers of this row (uids.length + overflow)
        SeenBySelection(String[] uids, int total) { this.uids = uids; this.total = total; }
    }

    /** True for a row that can carry a strip: my own real, canvas-rendered group message. */
    private boolean canShowSeenBy(@NonNull Message m) {
        if (!isGroup || currentUid == null || !currentUid.equals(m.senderId)) return false;
        if (isNonGroupingRow(m.type) || "call_entry".equals(m.type)) return false;
        if (Boolean.TRUE.equals(m.viewOnce) || Boolean.TRUE.equals(m.deleted)) return false;
        return viewTypeOf(m) == TYPE_CANVAS_SENT;
    }

    /**
     * Picks the readers to draw for the row at {@code position}: everyone in its
     * readBy (minus me) who is NOT also in the readBy of the next own row below
     * (they "moved down" to that row). Returns null when there is nobody to show.
     * peek() only — no Paging load hints; an unloaded row while scanning down
     * means "can't tell", so nothing is excluded (showing a face twice for a
     * moment is harmless; dropping it isn't).
     */
    @Nullable
    private SeenBySelection selectSeenByReaders(int position, @NonNull Message m) {
        java.util.Map<String, Long> rb = m.readBy;
        if (rb == null || rb.isEmpty()) return null;

        java.util.Map<String, Long> nextRb = null;
        int limit = Math.min(getItemCount() - 1, position + SEEN_BY_SCAN_ROWS);
        for (int i = position + 1; i <= limit; i++) {
            Message nm = peek(i);
            if (nm == null) break;
            if (canShowSeenBy(nm)) { nextRb = nm.readBy; break; }
        }

        int total = 0;
        for (String uid : rb.keySet()) {
            if (uid == null || uid.equals(currentUid)) continue;
            if (nextRb != null && nextRb.containsKey(uid)) continue;
            total++;
        }
        if (total == 0) return null;

        // ≤5 readers → all as avatars; more → 4 avatars + a "+N" chip (5 circles total).
        int k = total <= SEEN_BY_MAX_CIRCLES ? total : SEEN_BY_MAX_CIRCLES - 1;
        String[] uids = new String[k];
        long[] ts = new long[k];
        int n = 0;
        for (java.util.Map.Entry<String, Long> e : rb.entrySet()) {
            String uid = e.getKey();
            if (uid == null || uid.equals(currentUid)) continue;
            if (nextRb != null && nextRb.containsKey(uid)) continue;
            long t = e.getValue() != null ? e.getValue() : 0L;
            int j;
            if (n < k) {
                j = n++;
            } else if (t < ts[k - 1]) {
                j = k - 1; // displaces the current latest of the k earliest
            } else {
                continue;
            }
            while (j > 0 && ts[j - 1] > t) { ts[j] = ts[j - 1]; uids[j] = uids[j - 1]; j--; }
            ts[j] = t;
            uids[j] = uid;
        }
        return new SeenBySelection(uids, total);
    }

    /** Binds (or clears) the strip on a canvas holder. Reader photos come from the
     *  same live groupMemberPhotos map as the sender avatar, at the same 24dp
     *  TIER_INLINE (shares its L2/L3 entries — a 14dp circle just downsamples). */
    private void bindSeenByAvatars(@NonNull VH h, @NonNull Message m, int position) {
        final com.callx.app.conversation.canvas.MessageBubbleCanvasView cv = h.canvasView;
        if (cv == null) return;
        if (!canShowSeenBy(m)) { cv.clearSeenBy(); return; }

        // v443: per-bind memoization. The strip is a pure function of (this
        // row's readBy, the next own row's readBy, groupMemberPhotos). All
        // three change only through paths that call bumpSeenByEpoch(), so a
        // plain scroll re-bind (same epoch) skips the 80-row peek() scan,
        // the readBy iteration, the StringBuilder key, the String[] allocs
        // and the photo-map lookups — it just reuses the frozen result.
        final SeenByBound b;
        final Object cached = m.cachedSeenBy;
        if (m.cachedSeenByEpoch == seenByEpoch && cached != null) {
            b = (cached == SEEN_BY_NONE) ? null : (SeenByBound) cached;
        } else {
            b = computeSeenByBound(position, m);
            m.cachedSeenBy = (b != null) ? b : SEEN_BY_NONE;
            m.cachedSeenByEpoch = seenByEpoch;
        }
        if (b == null) { cv.clearSeenBy(); return; }

        final String key = b.key;
        if (!cv.setSeenBy(key, b.shown, b.overflow)) return; // same readers already shown, bitmaps held
        final android.content.Context ctx = h.itemView.getContext();
        for (int i = 0; i < b.shown; i++) {
            final String url = b.urls[i];
            if (url == null || url.isEmpty()) continue; // no photo → placeholder circle
            final int slot = i;
            final android.graphics.Bitmap l2 = com.callx.app.cache.ChatAvatarBinder.peekInline(ctx, url);
            if (l2 != null) { cv.setSeenByAvatarBitmap(key, slot, l2); continue; } // v445: no lambda on L2 hit
            com.callx.app.cache.ChatAvatarBinder.bindBitmap(ctx, url, 0L,
                    resource -> cv.setSeenByAvatarBitmap(key, slot, resource)); // key-guarded in the view
        }
    }

    // ── v443: seen-by per-bind memoization ────────────────────────────────
    /** Frozen result of one strip computation (immutable once built). */
    private static final class SeenByBound {
        final String key; final String[] urls; final int shown; final int overflow;
        SeenByBound(String key, String[] urls, int shown, int overflow) {
            this.key = key; this.urls = urls; this.shown = shown; this.overflow = overflow;
        }
    }
    /** Cached "nothing to show" marker (distinct from "not computed yet" = null). */
    private static final Object SEEN_BY_NONE = new Object();
    // Process-wide sequence so a Message instance that outlives an adapter
    // (message-model cache) can never match a NEW adapter's epoch by accident.
    private static int seenByEpochSeq = 0;
    private int seenByEpoch = ++seenByEpochSeq;
    /** Main thread only (same as every notify*()). Invalidates every cached strip. */
    private void bumpSeenByEpoch() { seenByEpoch = ++seenByEpochSeq; }
    /** For callers that mutate a bound Message's readBy without going through the adapter. */
    public void invalidateSeenByCache() { bumpSeenByEpoch(); }

    @Nullable
    private SeenByBound computeSeenByBound(int position, @NonNull Message m) {
        final SeenBySelection sel = selectSeenByReaders(position, m);
        if (sel == null) return null;
        final int shown = sel.uids.length;
        final String[] urls = new String[shown];
        // Key = who is shown + which photo each has, so a changed profile
        // photo alone is enough to re-request the bitmap.
        StringBuilder kb = new StringBuilder(shown * 24 + 8);
        for (int i = 0; i < shown; i++) {
            String url = groupMemberPhotos.get(sel.uids[i]);
            urls[i] = url;
            kb.append(sel.uids[i]).append('@').append(url != null ? url.hashCode() : 0).append(',');
        }
        final String key = kb.append('+').append(sel.total - shown).toString();
        return new SeenByBound(key, urls, shown, sel.total - shown);
    }

    /**
     * Call after a sent group row's readBy changed OUTSIDE applyRealtimeUpdate
     * (which already does this): refreshes that row's strip and the nearest own
     * row above it, whose strip depends on this row's readers.
     */
    public void notifyReadByChanged(int position) {
        bumpSeenByEpoch();
        if (position < 0 || position >= getItemCount()) return;
        notifyItemChanged(position, PAYLOAD_READ_BY);
        if (isGroup) refreshSeenByOwnRowAbove(position - 1);
    }

    /** Re-evaluates the strip of the nearest strip-capable own row at or above {@code fromPos}. */
    private void refreshSeenByOwnRowAbove(int fromPos) {
        if (!isGroup || fromPos < 0) return;
        RecyclerView rv = attachedRecyclerView;
        if (rv == null) return; // nothing bound yet — first bind computes it fresh
        if (rv.isComputingLayout()) {
            rv.post(() -> refreshSeenByOwnRowAbove(fromPos));
            return;
        }
        int count = getItemCount();
        if (count == 0) return;
        int lowest = Math.max(0, fromPos - SEEN_BY_SCAN_ROWS);
        for (int i = Math.min(fromPos, count - 1); i >= lowest; i--) {
            Message pm = peek(i);
            if (pm == null) return;
            if (canShowSeenBy(pm)) {
                notifyItemChanged(i, PAYLOAD_READ_BY);
                return;
            }
        }
    }

    /** True if any of the (up to 30) rows in [start, start+count) is one of my own messages. */
    private boolean rangeHasOwnRow(int start, int count) {
        if (currentUid == null) return false;
        int end = Math.min(getItemCount(), start + Math.min(count, 30));
        for (int i = Math.max(0, start); i < end; i++) {
            Message pm = peek(i);
            if (pm != null && currentUid.equals(pm.senderId)) return true;
        }
        return false;
    }

    // ── Mini avatar strips on reaction badges + poll footers (v439) ───────────
    // Same recipe as the seen-by strip: reader/reactor/voter photos come from the
    // live groupMemberPhotos map at TIER_INLINE (shared L2/L3 with every other
    // canvas avatar); a key of uid@photoHash lets a rebind with the same people
    // skip the loads, and lets the view drop a late bitmap for a stale set.
    private static final int MINI_STRIP_REACTORS = 3; // no "+N" chip — the badge text already carries counts

    private static final int MINI_KIND_REACTION = 0;
    private static final int MINI_KIND_POLL     = 1;

    // ── v444: memoized reaction badge text + reactor strip / poll-voter strip ─
    // Before: EVERY bind of a reacted row rebuilt the badge text (LinkedHashMap +
    // boxed Integers + StringBuilder), the uid[] array, the key StringBuilder,
    // N photo-map lookups + hashCodes, and two lambdas — for a result that only
    // changes when the reactions map / photo map change. Now frozen per Message.
    //
    // Validity signature = (source map IDENTITY, its size, photosEpoch):
    //  • DiffUtil / Room / Firebase / applyRealtimeUpdate hand over a NEW map → identity differs
    //  • member added/removed a reaction / vote in place → size differs
    //  • applyLocalReaction (only in-place emoji swap) explicitly nulls the cache
    //  • profile photo change → photosEpoch bumped (setGroupMemberPhotos / onMemberPhotosChanged)
    //  • live poll votes (bindPollOnly) pass force=true
    private static final class ReactionBound {
        final java.util.Map<String, String> src; final int size; final int photosEpoch;
        final String text;          // badge text ("😍2 👍"), null → nothing to show
        final String key;           // reactor-strip key (null when not group / no reactors)
        final String[] urls; final int n;
        ReactionBound(java.util.Map<String, String> src, int size, int photosEpoch,
                      String text, String key, String[] urls, int n) {
            this.src = src; this.size = size; this.photosEpoch = photosEpoch;
            this.text = text; this.key = key; this.urls = urls; this.n = n;
        }
    }

    private static final class PollVoterBound {
        final java.util.Map<String, java.util.List<Integer>> src; final int size; final int photosEpoch;
        final String key; final String[] urls; final int n; final int overflow;
        PollVoterBound(java.util.Map<String, java.util.List<Integer>> src, int size, int photosEpoch,
                       String key, String[] urls, int n, int overflow) {
            this.src = src; this.size = size; this.photosEpoch = photosEpoch;
            this.key = key; this.urls = urls; this.n = n; this.overflow = overflow;
        }
    }

    private int photosEpoch = ++seenByEpochSeq;
    private void bumpPhotosEpoch() { photosEpoch = ++seenByEpochSeq; }

    /** Builds the strip key (who + which photo) and fills {@code urlsOut}. */
    private String buildMiniKey(@NonNull String[] uids, int n, int overflow, @NonNull String[] urlsOut) {
        StringBuilder kb = new StringBuilder(n * 24 + 8);
        for (int i = 0; i < n; i++) {
            String url = groupMemberPhotos.get(uids[i]);
            urlsOut[i] = url;
            kb.append(uids[i]).append('@').append(url != null ? url.hashCode() : 0).append(',');
        }
        return kb.append('+').append(overflow).toString();
    }

    /** Memoized badge text + reactor strip for {@code m}; null when it has no reactions. */
    @Nullable
    private ReactionBound reactionBoundFor(@NonNull Message m) {
        final java.util.Map<String, String> rx = m.reactions;
        if (rx == null || rx.isEmpty()) return null;
        final Object c = m.cachedReactionBound;
        if (c != null) {
            final ReactionBound cb = (ReactionBound) c;
            if (cb.src == rx && cb.size == rx.size() && cb.photosEpoch == photosEpoch) return cb;
        }
        final String text = formatReactions(rx);
        String key = null; String[] urls = null; int n = 0;
        if (isGroup) {
            final String[] uids = new String[MINI_STRIP_REACTORS];
            for (java.util.Map.Entry<String, String> e : rx.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                uids[n++] = e.getKey();
                if (n == MINI_STRIP_REACTORS) break;
            }
            if (n > 0) { urls = new String[n]; key = buildMiniKey(uids, n, 0, urls); }
        }
        final ReactionBound nb = new ReactionBound(rx, rx.size(), photosEpoch, text, key, urls, n);
        m.cachedReactionBound = nb;
        return nb;
    }

    /** Applies (or clears) the reactor strip from a memoized bound. Zero alloc when the view already shows it. */
    private void applyReactionAvatars(@NonNull com.callx.app.conversation.canvas.MessageBubbleCanvasView cv,
                                      @Nullable ReactionBound rb, @NonNull android.content.Context ctx) {
        if (!isGroup || rb == null || rb.n == 0 || rb.key == null) { cv.clearReactionAvatars(); return; }
        applyMiniStrip(cv, MINI_KIND_REACTION, ctx, rb.key, rb.urls, rb.n, 0);
    }

    private void applyMiniStrip(@NonNull final com.callx.app.conversation.canvas.MessageBubbleCanvasView cv,
                                final int kind, @NonNull android.content.Context ctx,
                                @NonNull final String key, @NonNull String[] urls, int n, int overflow) {
        final boolean changed = (kind == MINI_KIND_REACTION)
                ? cv.setReactionAvatars(key, n)
                : cv.setPollVoters(key, n, overflow);
        if (!changed) return; // same people already shown, bitmaps held
        for (int i = 0; i < n; i++) {
            final String url = urls[i];
            if (url == null || url.isEmpty()) continue; // no photo → placeholder circle
            final int slot = i;
            final android.graphics.Bitmap l2 = com.callx.app.cache.ChatAvatarBinder.peekInline(ctx, url);
            if (l2 != null) { // v445: L2 hit → set inline, no lambda
                if (kind == MINI_KIND_REACTION) cv.setReactionAvatarBitmap(key, slot, l2);
                else cv.setPollVoterBitmap(key, slot, l2);
                continue;
            }
            com.callx.app.cache.ChatAvatarBinder.bindBitmap(ctx, url, 0L, resource -> {
                if (kind == MINI_KIND_REACTION) cv.setReactionAvatarBitmap(key, slot, resource);
                else cv.setPollVoterBitmap(key, slot, resource); // key-guarded in the view
            });
        }
    }

    /**
     * Voter avatars in a group poll's footer row. NEVER for anonymous polls —
     * showing who voted would defeat the point. ≤5 voters → all; more → 4 + "+N".
     * {@code force}: skip the memo (live-vote path, where the votes map may have
     * been mutated in place by the vote handler).
     */
    private void bindPollVoters(@NonNull com.callx.app.conversation.canvas.MessageBubbleCanvasView cv,
                                @NonNull Message m, @NonNull android.content.Context ctx, boolean force) {
        final java.util.Map<String, java.util.List<Integer>> votes = m.pollVotes;
        if (!isGroup || Boolean.TRUE.equals(m.pollAnonymous) || votes == null || votes.isEmpty()) {
            cv.clearPollVoters();
            return;
        }
        PollVoterBound b;
        final Object c = m.cachedPollVoterBound;
        if (!force && c != null && ((PollVoterBound) c).src == votes
                && ((PollVoterBound) c).size == votes.size()
                && ((PollVoterBound) c).photosEpoch == photosEpoch) {
            b = (PollVoterBound) c;
        } else {
            int total = 0;
            for (java.util.Map.Entry<String, java.util.List<Integer>> e : votes.entrySet()) {
                if (e.getKey() != null && e.getValue() != null && !e.getValue().isEmpty()) total++;
            }
            if (total == 0) {
                b = new PollVoterBound(votes, votes.size(), photosEpoch, "", null, 0, 0);
            } else {
                final int k = total <= SEEN_BY_MAX_CIRCLES ? total : SEEN_BY_MAX_CIRCLES - 1;
                final String[] uids = new String[k];
                int n = 0;
                for (java.util.Map.Entry<String, java.util.List<Integer>> e : votes.entrySet()) {
                    if (e.getKey() == null || e.getValue() == null || e.getValue().isEmpty()) continue;
                    uids[n++] = e.getKey();
                    if (n == k) break;
                }
                final String[] urls = new String[n];
                final String key = buildMiniKey(uids, n, total - n, urls);
                b = new PollVoterBound(votes, votes.size(), photosEpoch, key, urls, n, total - n);
            }
            m.cachedPollVoterBound = b;
        }
        if (b.n == 0) { cv.clearPollVoters(); return; }
        applyMiniStrip(cv, MINI_KIND_POLL, ctx, b.key, b.urls, b.n, b.overflow);
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
        /**
         * One Glide target instance can be re-armed for a holder slot. The
         * callback is deliberately detached before RequestManager.clear():
         * Glide invokes onLoadCleared synchronously, and an old callback must
         * never mutate the new message that is about to use this slot.
         */
        static final class ReusableBitmapTarget
                extends com.bumptech.glide.request.target.CustomTarget<Bitmap> {
            BitmapReadyCallback ready;
            BitmapClearedCallback cleared;

            ReusableBitmapTarget prepare(com.bumptech.glide.RequestManager manager,
                                         BitmapReadyCallback next,
                                         BitmapClearedCallback nextCleared) {
                ready = null;
                cleared = null;
                manager.clear(this);
                ready = next;
                cleared = nextCleared;
                return this;
            }

            void cancel(com.bumptech.glide.RequestManager manager) {
                ready = null;
                cleared = null;
                manager.clear(this);
            }

            @Override
            public void onResourceReady(@NonNull Bitmap resource,
                    @Nullable com.bumptech.glide.request.transition.Transition<? super Bitmap> transition) {
                BitmapReadyCallback cb = ready;
                if (cb != null) cb.onReady(resource);
            }

            @Override
            public void onLoadCleared(@Nullable android.graphics.drawable.Drawable placeholder) {
                BitmapClearedCallback cb = cleared;
                if (cb != null) cb.onCleared();
            }
        }

        private final ReusableBitmapTarget[] reusableBitmapTargets =
                new ReusableBitmapTarget[TARGET_SLOT_COUNT];

        ReusableBitmapTarget prepareBitmapTarget(com.bumptech.glide.RequestManager manager,
                int slot, BitmapReadyCallback ready, BitmapClearedCallback cleared) {
            ReusableBitmapTarget target = reusableBitmapTargets[slot];
            if (target == null) {
                target = new ReusableBitmapTarget();
                reusableBitmapTargets[slot] = target;
            }
            return target.prepare(manager, ready, cleared);
        }

        void cancelReusableBitmapTargets(com.bumptech.glide.RequestManager manager) {
            for (ReusableBitmapTarget target : reusableBitmapTargets) {
                if (target != null) target.cancel(manager);
            }
        }

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
        // PERF ADV: which MessagePagingAdapter instance last wired
        // canvasView's OnBubbleClickListener. Only relevant now that the
        // RecycledViewPool is shared/static across chat opens — see
        // getSharedCanvasPool()/canvasListenerOwner check in
        // onBindViewHolder for why this can legitimately differ from the
        // currently-binding adapter.
        MessagePagingAdapter canvasListenerOwner;
        // v425 PERF: per-VH caption read-more listener (built once) + the id
        // of the message it is currently bound to. See wireCaptionReadMore().
        com.callx.app.conversation.canvas.MessageBubbleCanvasView.ReadMoreListener readMoreListenerCached;
        String readMoreMsgId;
        // v426 PERF: url of the auto-download this holder queued (if any) —
        // see onViewRecycled().
        String autoDlUrl;
        // v425 PERF: lazily-inflated legacy voice/caption overlay — see
        // ensureLegacyVoiceOverlay(). Null once inflated (or if the layout
        // has no such stub, e.g. Canvas holders).
        android.view.ViewStub stubVoiceOnImage;
        // Bumped on every bindCanvasMessage() call and checked before an
        // async Glide result (image bitmap / reply thumb) is applied — a
        // slow load that resolves after this holder has been recycled and
        // rebound to a different message must NOT paint onto the new one.
        // Unlike Glide's ImageView targets (which auto-cancel via view
        // attachment), the raw CustomTarget<Bitmap> used for canvas binds
        // has no such lifecycle tie-in, so this token is the only thing
        // preventing that race.
        volatile int canvasBindToken = 0;
        // PERF ULTRA: reusable Glide target for the single-image (isImage,
        // non-group) bind path — previously a fresh anonymous
        // CustomTarget<Bitmap> was allocated on EVERY bind that fired a
        // Glide decode (cache miss), even though the vast majority of that
        // object's logic (aspect-ratio caching, bitmap-pool storage, the
        // canvasBindToken staleness guard) is identical bind to bind. This
        // holder now owns exactly one target instance for its whole
        // lifetime; each fire just restashes the small bits of per-load
        // state below immediately before calling .into(), instead of
        // capturing them in a new closure. See getOrCreateImageBindTarget().
        com.bumptech.glide.request.target.CustomTarget<android.graphics.Bitmap> imageBindTarget;
        int imageBindFireToken;
        String imageBindAspectCacheKey; // fullUrl to record decoded aspect ratio under, or null to skip
        String imageBindPoolKey;        // MEDIA_BITMAP_CACHE key to store the decoded bitmap under, or null/empty to skip

        com.bumptech.glide.request.target.CustomTarget<android.graphics.Bitmap> getOrCreateImageBindTarget() {
            if (imageBindTarget == null) {
                imageBindTarget = new com.bumptech.glide.request.target.CustomTarget<android.graphics.Bitmap>() {
                    @Override
                    public void onResourceReady(@NonNull android.graphics.Bitmap resource,
                            @Nullable com.bumptech.glide.request.transition.Transition<? super android.graphics.Bitmap> transition) {
                        // Record the real aspect ratio / pool entry regardless of
                        // whether this holder still shows this image — fast
                        // scrolling recycles/rebinds the view long before Glide's
                        // callback fires, and gating these on the token below
                        // would repeat the square-placeholder flash on every
                        // scroll-past instead of only an image's very first view.
                        if (resource.getHeight() > 0 && imageBindAspectCacheKey != null) {
                            com.callx.app.conversation.canvas.MessageBubbleCanvasView
                                    .cacheAspectRatio(imageBindAspectCacheKey, (float) resource.getWidth() / resource.getHeight());
                        }
                        if (imageBindPoolKey != null && !imageBindPoolKey.isEmpty()) {
                            MEDIA_BITMAP_CACHE.put(imageBindPoolKey, resource);
                            dashboardRecordDecoded(canvasView.getContext(), imageBindPoolKey, resource);
                        }
                        if (canvasBindToken != imageBindFireToken) return; // holder recycled/rebound since this load started
                        if (canvasView != null) canvasView.setMediaBitmap(resource);
                    }
                    @Override
                    public void onLoadCleared(@Nullable android.graphics.drawable.Drawable placeholder) {
                        if (canvasBindToken != imageBindFireToken) return;
                        if (canvasView != null) canvasView.setMediaBitmap(null);
                    }
                };
            }
            return imageBindTarget;
        }

        // PERF ADV: reusable Glide target for the link-preview thumbnail —
        // mirrors imageBindTarget above (one instance per holder lifetime,
        // small per-load state restashed right before .into()). See
        // bindLinkPreviewResult() and LINK_PREVIEW_BITMAP_CACHE. Cleared
        // explicitly in onViewRecycled() so a recycled row's in-flight
        // network+decode is actually cancelled, not just ignored on arrival.
        com.bumptech.glide.request.target.CustomTarget<android.graphics.Bitmap> linkPreviewTarget;
        int linkPreviewFireToken;
        String linkPreviewPoolKey;   // LINK_PREVIEW_BITMAP_CACHE key to store the decoded bitmap under
        String linkPreviewTagAtFire; // cv.getTag() value expected at delivery time (same guard bindLinkPreviewResult used to do inline)

        com.bumptech.glide.request.target.CustomTarget<android.graphics.Bitmap> getOrCreateLinkPreviewTarget() {
            if (linkPreviewTarget == null) {
                linkPreviewTarget = new com.bumptech.glide.request.target.CustomTarget<android.graphics.Bitmap>() {
                    @Override
                    public void onResourceReady(@NonNull android.graphics.Bitmap resource,
                            @Nullable com.bumptech.glide.request.transition.Transition<? super android.graphics.Bitmap> transition) {
                        if (linkPreviewPoolKey != null) {
                            LINK_PREVIEW_BITMAP_CACHE.put(linkPreviewPoolKey, resource);
                            if (canvasView != null) {
                                linkPreviewL3(canvasView.getContext()).put(linkPreviewPoolKey, resource);
                            }
                        }
                        if (canvasBindToken != linkPreviewFireToken) return; // holder recycled/rebound since this load started
                        if (canvasView != null && linkPreviewTagAtFire != null
                                && linkPreviewTagAtFire.equals(canvasView.getTag())) {
                            canvasView.setLinkPreviewThumbBitmap(resource);
                        }
                    }
                    @Override
                    public void onLoadCleared(@Nullable android.graphics.drawable.Drawable placeholder) {
                        if (canvasBindToken != linkPreviewFireToken) return;
                        if (canvasView != null) canvasView.setLinkPreviewThumbBitmap(null);
                    }
                };
            }
            return linkPreviewTarget;
        }
        // PERF: reused buffers for bindPollOnly()'s live vote-count fast
        // path (fires once per incoming vote on an active poll) — grown
        // only when the option count actually changes, instead of a fresh
        // int[]/boolean[] on every single vote tick. See countVotes(...,out)
        // overload and bindPollOnly() below.
        int[] pollCountsScratch;
        boolean[] pollMyVoteScratch;
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
        /** v448: true while this holder may have an entry in ExpiryTickManager. Set on every
         *  register(); cleared by our own unregister + onFinish. It is a SUPERSET of "manager
         *  has an entry" (the manager can drop an entry on its own, e.g. finish), so a stale
         *  true only costs one harmless no-op unregister — a false-while-registered is impossible. */
        boolean expiryRegistered = false;
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
        // Feature: Voice Caption on Photo — WhatsApp-style caption strip on
        // the legacy image bubble. See bindImageCaptionOnLegacyBubble().
        View      viewImageCaptionScrim;
        TextView  tvImageCaption;
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
        // applyBubbleOwned()+setBackground() entirely when this holder's
        // hasReply state hasn't changed since its last bind (the common
        // case while scrolling — sent/received view type never changes for
        // a given recycled holder, only hasReply can flip).
        int lastBubbleReplyState = -1;

        // ULTRA PERF (bubble drawable): this holder's own private
        // GradientDrawable, allocated ONCE here and never shared with any
        // other View. Wires up ChatThemeManager.applyBubbleOwned() (which
        // already existed but was never called from anywhere — the adapter
        // was still going through the old applyBubble()+mutate() path,
        // which allocates a brand-new GradientDrawable ConstantState copy
        // on every single bubble's first bind after recycle — i.e. on
        // every message visible the moment a chat screen opens). Because
        // this instance belongs exclusively to this holder for its entire
        // lifetime, rebinding it is just setColor()+setCornerRadii() on
        // the existing object — zero allocation, on chat-open and on
        // every scroll-triggered rebind alike.
        final android.graphics.drawable.GradientDrawable ownedBubbleDrawable =
                com.callx.app.utils.ChatThemeManager.newOwnedBubbleDrawable();

        // ULTRA-OPT ("someone is replying to this" glow): same shared-vs-
        // owned issue as the bubble background above. The old code called
        // ContextCompat.getDrawable(ctx, R.drawable.bg_reply_target_highlight)
        // fresh every single time isReplyTarget was true — a real inflate
        // (new Drawable + its ConstantState) on every bind where the glow
        // is active, unconditionally re-checked on every full bindMessage()
        // call (every recycle), not just when the state actually flips.
        // This holder now inflates the drawable ONCE, lazily (most holders
        // never show this glow at all, since it only lights up for the one
        // message currently being replied to — no reason to pay for it
        // up front), and reuses that same instance for the rest of the
        // holder's lifetime; the framework re-applies bounds automatically
        // whenever setForeground() runs, so — unlike the bubble background
        // — no per-View mutate() copy is needed here even though the
        // Drawable itself is stateful, because it's never shared with any
        // other View. A separate last-applied flag skips the setForeground()
        // call entirely when the reply-target state hasn't changed since
        // the previous bind, avoiding a redundant invalidate/draw pass on
        // the (very common) case of no reply-target activity at all.
        android.graphics.drawable.Drawable ownedReplyTargetDrawable;
        int lastReplyTargetState = -1; // -1 unknown, 0 = off, 1 = on

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
            // v425 PERF: these five are now behind a ViewStub (null until
            // ensureLegacyVoiceOverlay() inflates it) — see layout_msg_voice_on_image.xml.
            stubVoiceOnImage      = v.findViewById(R.id.stub_voice_on_image);
            flVoiceOnImage        = null;
            ivVoicePlayOnImage    = null;
            tvVoiceDurationOnImage = null;
            viewImageCaptionScrim = null;
            tvImageCaption        = null;
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
     *
     * NOTE: the 20dp group SENDER avatar on received rows does NOT go
     * through this setter — it reads groupMemberPhotos (setGroupMemberPhotos())
     * and refreshes via onMemberPhotosChanged() + PAYLOAD_GROUP_SENDER.
     *
     * UPDATE (v437): the seen-by avatar strip described above IS wired now
     * (bindSeenByAvatars), but it reads groupMemberPhotos too (same live map,
     * same TIER_INLINE bitmaps as the sender avatar) and refreshes through
     * onMemberPhotosChanged() + PAYLOAD_READ_BY — so this legacy field is
     * still unused, and this setter is still a stored-only no-op.
     */
    public void setMemberPhotos(java.util.Map<String, String> photos) {
        this.memberPhotos = photos;
    }
}
