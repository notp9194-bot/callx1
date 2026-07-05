package com.callx.app.conversation.canvas;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import com.callx.app.utils.ChatThemeManager;

/**
 * MessageBubbleCanvasView — Phase 1 of the Telegram-style rendering rewrite.
 *
 * WHY THIS EXISTS
 * ────────────────
 * Every item_message_sent/received.xml bind currently pays Android's normal
 * View cost even after all the RecyclerView-level tuning (Paging3, DiffUtil,
 * RecycledViewPool, item-view cache, prefetch, etc.):
 *   • inflate (first bind of a fresh holder)
 *   • measure + layout of the whole ConstraintLayout > LinearLayout(bubble)
 *     > TextView/TextView(footer) subtree, on every single bind
 *   • a TextView draw path (its own internal layout cache, but still a full
 *     View in the hierarchy with its own dispatch overhead)
 *
 * Telegram's message list never pays any of that: a message row is ONE
 * custom View whose onDraw() paints the bubble background, the message
 * text (via a plain StaticLayout — no child TextView at all), and the
 * timestamp/tick directly onto the Canvas. There is nothing to inflate and
 * nothing to measure-and-layout for the two most expensive parts (the bubble
 * shape and the text block) — they're computed once in onMeasure() and then
 * just drawn.
 *
 * SCOPE OF THIS FILE
 * ──────────────────
 * This view handles:
 *   • plain-text bubbles — sender's own text + timestamp + read/delivered tick
 *   • the reply-preview strip (quoted sender name + quoted text + optional
 *     thumbnail, matching ll_reply_preview in item_message_sent/received.xml)
 *   • a single fixed-size (180dp square) IMAGE bubble with optional caption,
 *     matching iv_image in item_message_sent/received.xml — rounded corners,
 *     centerCrop-equivalent scaling, optional caption text below the image,
 *     and (for captionless images) a translucent timestamp/tick pill
 *     overlaid on the image itself, WhatsApp-style.
 *   • a multi-image/video grid (bindMediaGroup, both SENT and RECEIVED) —
 *     mirrors MediaGroupLayoutHelper's 1/2/3/4/5-9/10+ layout rules, video
 *     play-icon + duration badge, "+N" overflow on the last cell, and a
 *     group caption scrim overlapping the grid's bottom edge. See
 *     isCanvasEligible() in MessagePagingAdapter for exactly what qualifies
 *     (plain image/video cells only, no per-item captions).
 *   • for RECEIVED groups specifically: the same manual download-gate
 *     treatment as MediaGroupLayoutHelper's old grid — a full-grid dim
 *     scrim + centered "Download N photos" pill while any image cell is
 *     still un-cached (setGroupDownloadGate), and a small per-cell
 *     dim+icon badge for an individual cell's in-flight download
 *     (setGroupCellDownloading/markGroupCellDownloaded) once the master
 *     pill has been dismissed. Video cells never gate (they stream
 *     directly, same precedent as the old helper).
 *   • a reaction badge (setReactions/clearReactions) — mirrors ll_reactions/
 *     tv_reactions: plain-emoji text floating over the bubble's bottom-end
 *     corner, no background box. Works alongside any of the modes above.
 *   • a pinned label (setPinned) — "📌 Pinned" text above the bubble's
 *     top-end corner, works for sent and received alike.
 *   • a group-chat sender-name row (setGroupSender/clearGroupSender) —
 *     mirrors tv_sender_name (11sp bold, brand_primary) above the bubble's
 *     top-start corner, received messages only. The sender AVATAR
 *     (iv_sender_avatar) is NOT modeled — it's dead markup in the legacy
 *     layout too, never actually bound anywhere. This same row is also
 *     used for the 📢 broadcast badge (either "📢 Broadcast" in a 1:1
 *     chat, or a "📢 " prefix on the group sender name) — the caller
 *     (MessagePagingAdapter) composes whichever string applies and passes
 *     it through setGroupSender(), same as the legacy tv_sender_name path.
 *   • a forwarded-message label (setForwardedFrom/clearForwarded) — mirrors
 *     tv_forwarded ("↪ Forwarded from X", 11sp italic, #888888), stacked
 *     directly below the pinned-label/group-sender row, same corner.
 *   • a deleted-message placeholder (setDeletedStyle) — once the caller
 *     substitutes "This message was deleted"/"You deleted this message"
 *     as the bind() text, this switches the text paint italic + 60% alpha
 *     to match bindMessage()'s tvMessage.setAlpha(0.6f) treatment.
 *   • a disappearing-message countdown (setExpiryText/clearExpiry) — mirrors
 *     tv_expiry ("⏳ mm:ss", 9sp, gold) inside the footer row, just before
 *     the timestamp. The caller re-calls this once a second via the shared
 *     ExpiryTickManager, same cadence as the legacy TextView path.
 *
 * It intentionally does NOT (yet) handle:
 *   • audio/file cells inside a media group, or per-item captions
 *   • GIF, audio, file, poll, link-preview, or reel-share bubbles
 *   • long-press action menu (long-press itself is wired — see
 *     OnBubbleClickListener.onBubbleLongClick — the menu it opens is the
 *     caller's job)
 *
 * It DOES now also handle:
 *   • a live download-progress overlay for a RECEIVED single-image bubble
 *     not yet cached locally — setMediaDownloadGate()/setMediaDownloadProgress()
 *     draw a dim scrim + centered pill (idle "⬇ <label>" or a real
 *     spinner/percentage ring while in flight), mirroring the legacy
 *     fl_download_overlay/pb_download_spinner treatment. The same live
 *     ring (drawProgressRing) also now drives the media-GROUP per-cell
 *     badge (setGroupCellProgress) instead of the old static partial-arc.
 *   • swipe-to-reply gesture — a horizontal drag past a threshold reveals a
 *     reply glyph and reports the gesture back via
 *     OnBubbleClickListener.onSwipeToReply(), same trigger point as a
 *     long-press → "Reply" menu action, just via a gesture instead.
 *
 * Those all still render through the existing item_message_sent/received.xml
 * + MessagePagingAdapter path.
 *
 * HOW TO WIRE THIS IN
 * ────────────────────
 * This class is intentionally standalone (not yet plugged into
 * MessagePagingAdapter). To use it for a message, MessagePagingAdapter would
 * need a new view-type branch that, for messages whose shape this view
 * covers (plain text or single-image, with or without a reply, no
 * forward/pin/reactions/multi-image/video/etc.), inflates/reuses a
 * MessageBubbleCanvasView instead of item_message_sent/received.xml, and
 * falls back to the existing ViewHolder path for every other message shape.
 * Per bind: call bind(...) OR bindMedia(...) first (whichever matches the
 * message), then EITHER setReply(sender, text, thumb) for replies OR
 * clearReply() for non-replies — always call one of the two, every bind,
 * since this view is reused across scroll and a recycled instance still
 * holds whatever state the previous message left in it. For images loaded
 * asynchronously (Glide), call setMediaBitmap(bitmap) once decoded instead
 * of a full rebind.
 * That branch is the next real step — ask when ready and it can be added
 * as its own reviewable change.
 */
public class MessageBubbleCanvasView extends View {

    private static final float CORNER_RADIUS_DP = 18f;
    private static final float TAIL_RADIUS_DP    = 4f;
    private static final float H_PADDING_DP      = 12f;
    private static final float V_PADDING_DP      = 8f;
    private static final float TEXT_SIZE_SP      = 15f;
    private static final float FOOTER_GAP_DP     = 4f;
    private static final float FOOTER_TEXT_SP    = 11f;
    private static final float TICK_GAP_DP       = 3f;
    private static final float TICK_SIZE_DP      = 12f;
    private static final float MAX_BUBBLE_WIDTH_FRACTION = 0.78f;

    // ── Reply-preview strip — mirrors ll_reply_preview in
    // item_message_sent/received.xml exactly (margins, sizes, colors) ──
    private static final float REPLY_MARGIN_DP       = 4f;   // outer margin around the whole strip
    private static final float REPLY_BAR_WIDTH_DP    = 3f;   // colored left bar
    private static final float REPLY_PADDING_H_DP    = 8f;   // inner horizontal padding (text column)
    private static final float REPLY_PADDING_V_DP    = 4f;   // inner vertical padding (text column)
    private static final float REPLY_MIN_HEIGHT_DP   = 36f;
    private static final float REPLY_CORNER_RADIUS_DP = 6f;
    private static final float REPLY_SENDER_SIZE_SP  = 11f;
    private static final float REPLY_TEXT_SIZE_SP    = 12f;
    private static final float REPLY_THUMB_SIZE_DP   = 44f;
    private static final float REPLY_THUMB_MARGIN_DP = 4f;
    private static final float REPLY_GAP_TO_MESSAGE_DP = 2f; // gap between reply strip and message text below it

    private static final int SENT_REPLY_BG     = 0x33000000;
    private static final int SENT_REPLY_BAR    = 0xFFFFD700;
    private static final int SENT_REPLY_SENDER = 0xFFFFD54F;
    private static final int SENT_REPLY_TEXT   = 0xDDFFFFFF;
    private static final int RECEIVED_REPLY_BG = 0x22000000;

    // ── Image media bubble — mirrors iv_image (180×180dp, centerCrop,
    // bg_media_card 12dp corner radius) in item_message_sent/received.xml ──
    private static final float MEDIA_SIZE_DP           = 180f;
    private static final float MEDIA_CORNER_RADIUS_DP  = 12f;
    private static final float MEDIA_MARGIN_DP         = 2f;   // gap between media edge and bubble edge
    private static final float MEDIA_CAPTION_GAP_DP    = 6f;   // gap between image and caption text
    private static final float MEDIA_PILL_PADDING_H_DP = 6f;
    private static final float MEDIA_PILL_PADDING_V_DP = 3f;
    private static final float MEDIA_PILL_MARGIN_DP    = 6f;   // pill inset from image's bottom-right corner
    private static final float MEDIA_PILL_CORNER_DP    = 8f;
    private static final int   MEDIA_PILL_BG           = 0x66000000; // translucent black, WhatsApp-style
    private static final int   MEDIA_PILL_TEXT         = 0xFFFFFFFF;
    private static final int   MEDIA_PLACEHOLDER_COLOR = 0xFFD9D9D9; // shown until Bitmap arrives — no theme lookup yet (see class doc)

    // ── Reaction badge — mirrors ll_reactions/tv_reactions in
    // item_message_sent/received.xml: a plain-emoji badge (no background
    // box) floating outside the bubble's bottom-end corner, pulled down by
    // a negative margin so it overlaps without covering the text/footer.
    // Same treatment for sent and received (both layouts constrain it
    // identically to the bubble's end/bottom). ──
    private static final float REACTIONS_TEXT_SP      = 28f;
    private static final float REACTIONS_MARGIN_END_DP = 4f;
    private static final float REACTIONS_OVERLAP_DP   = 14f; // how far the badge hangs below the bubble's bottom edge (matches layout_marginBottom="-14dp")
    private static final int   REACTIONS_SHADOW_COLOR = 0x66000000;

    // ── Pinned label — mirrors tv_pinned_label's text/size/color from
    // item_message_sent.xml ("📌 Pinned", 10sp, #E65100). That XML view was
    // never actually bound anywhere in MessagePagingAdapter (dead markup,
    // sent-layout-only, aligned to the parent row's end rather than the
    // bubble), so rather than reproduce that half-finished/sent-only
    // placement, this anchors the label to the BUBBLE's own top-end corner
    // — works symmetrically for sent and received alike. ──
    private static final float PINNED_LABEL_TEXT_SP = 10f;
    private static final int   PINNED_LABEL_COLOR   = 0xFFE65100;
    private static final String PINNED_LABEL_TEXT   = "📌 Pinned";
    private static final float PINNED_LABEL_GAP_DP  = 2f; // gap between label and bubble top
    private static final float GROUP_SENDER_TEXT_SP = 11f; // matches tv_sender_name in item_message_received.xml

    // ── Forwarded label — mirrors tv_forwarded's text/size/color/style from
    // item_message_received.xml ("↪ Forwarded from X", 11sp italic, #888888).
    // Stacked directly below the pinned-label/group-sender row (same
    // constraintTop_toBottomOf relationship the legacy layout uses). ──
    private static final float FORWARDED_LABEL_TEXT_SP = 11f;
    private static final int   FORWARDED_LABEL_COLOR   = 0xFF888888;

    // ── Deleted-message placeholder — mirrors bindMessage()'s
    // tvMessage.setAlpha(0.6f) treatment for "This message was deleted" /
    // "You deleted this message"; italic added for the same "ghost text"
    // effect most chat apps use. ──
    private static final int DELETED_TEXT_ALPHA = 153; // ~0.6 * 255

    // ── Disappearing-message countdown — mirrors tv_expiry's text/size/color
    // from item_message_sent/received.xml ("⏳ mm:ss", 9sp, gold), drawn in
    // the footer row just before the timestamp. ──
    private static final float EXPIRY_TEXT_SP = 9f;
    private static final int   EXPIRY_COLOR   = 0xFFFFCC00;

    // ── Media GROUP (multi-image/video grid) — mirrors MediaGroupLayoutHelper's
    // layout rules/constants exactly for visual parity, but is an entirely
    // separate mode from the single-image `isMedia` path above (own state,
    // own measure/draw/hit-test) so the already-working single-image path
    // can't regress. See MessagePagingAdapter.isCanvasEligible(): only
    // SENT groups of plain image/video items (no audio/file cells, no
    // per-item captions) reach this — received groups keep the manual
    // download-gate UI on the old path, same precedent as single images. ──
    private static final int GROUP_SINGLE_W    = 240;
    private static final int GROUP_SINGLE_H    = 200;
    private static final int GROUP_PAIR_CELL   = 118;
    private static final int GROUP_THREE_TOP_W = 240;
    private static final int GROUP_THREE_TOP_H = 140;
    private static final int GROUP_THREE_BOT   = 116;
    private static final int GROUP_GRID2_CELL  = 118;
    private static final int GROUP_GRID3_CELL  = 78;
    private static final int GROUP_GAP         = 2;
    private static final int GROUP_CORNER_R    = 4; // per-cell radius (matches buildCell's 4dp)
    private static final int GROUP_MAX_VISIBLE = 9; // 3x3 cap; beyond this, last cell shows "+N"
    private static final float GROUP_CAPTION_SCRIM_H_DP = 40f;
    private static final float GROUP_CAPTION_TEXT_SP    = 14f;
    private static final int   GROUP_PLAY_CIRCLE_DP      = 36;
    private static final int   GROUP_PLAY_TRIANGLE_DP    = 20;
    private static final int   GROUP_DURATION_TEXT_SP    = 10;
    // ── Per-item caption strip — mirrors MediaGroupLayoutHelper's per-cell
    // caption exactly: a 22dp gradient scrim pinned to the cell's bottom
    // edge, single-line ellipsized 10sp white text. When a video cell also
    // has a caption, its duration badge moves to the cell's top-end corner
    // instead (same conflict-avoidance the legacy helper uses). ──
    private static final float GROUP_ITEM_CAPTION_STRIP_H_DP = 22f;
    private static final float GROUP_ITEM_CAPTION_TEXT_SP    = 10f;
    private static final float GROUP_ITEM_CAPTION_MARGIN_DP  = 4f;
    private static final float GROUP_ITEM_CAPTION_BOTTOM_DP  = 2f;

    // ── Received-group manual download-gate — mirrors MediaGroupLayoutHelper's
    // addMasterDownloadOverlay()/buildCellDownloadOverlay() visuals exactly
    // (same colors/sizes), just Canvas-drawn instead of View-built. Only
    // reachable for RECEIVED groups — see setGroupDownloadGate(). ──
    private static final int   GROUP_GATE_SCRIM_COLOR      = 0x2E000000; // dims whole grid while gate is up
    private static final int   GROUP_GATE_PILL_BG          = 0x8A000000;
    private static final float GROUP_GATE_PILL_CORNER_DP   = 24f;
    private static final float GROUP_GATE_PILL_PAD_H_DP    = 14f;
    private static final float GROUP_GATE_PILL_PAD_V_DP    = 8f;
    private static final float GROUP_GATE_PILL_ICON_DP     = 18f;
    private static final float GROUP_GATE_PILL_ICON_GAP_DP = 8f;
    private static final float GROUP_GATE_PILL_TEXT_SP     = 13f;
    private static final int   GROUP_CELL_GATE_DIM_COLOR   = 0x40000000; // per-cell badge once master pill dismissed
    private static final int   GROUP_CELL_GATE_BADGE_BG    = 0x99000000;
    private static final float GROUP_CELL_GATE_BADGE_DP    = 26f;
    private static final float GROUP_CELL_GATE_ICON_DP     = 14f;

    // ── Live download-progress ring — shared by the single-media gate pill
    // and the per-cell group badge (see drawProgressRing()). Determinate
    // (percent 0-100): a clockwise arc from 12 o'clock. Indeterminate
    // (percent < 0, no progress events yet): a short arc that rotates on
    // wall-clock time via postInvalidateOnAnimation() — no Handler/
    // ValueAnimator bookkeeping needed, and every visible instance across
    // the RecyclerView stays in sync since they all read the same clock. ──
    private static final long  INDETERMINATE_PERIOD_MS = 900L;
    private static final float INDETERMINATE_SWEEP_DEG = 100f;

    // ── Single-media manual download gate — mirrors the legacy
    // fl_download_overlay/pb_download_spinner treatment for a RECEIVED
    // "image" message not yet cached locally: a dim scrim over the image
    // slot with a centered pill, idle "⬇ <label>" (tap to start) or a live
    // spinner/percentage while a download is in flight. Visually reuses the
    // GROUP_GATE_PILL_* constants above for parity with the group version. ──
    private static final int MEDIA_GATE_SCRIM_COLOR = 0x2E000000;

    public interface OnBubbleClickListener {
        void onBubbleClick();
        void onBubbleLongClick();
        /** Returns true if a link at (x,y) was hit and handled — caller should not treat as a normal click. */
        boolean onLinkClick(String url);
        /** Tapped the reply-preview strip — caller should scroll/jump to the quoted message. */
        void onReplyPreviewClick();
        /** Tapped the image itself (media bubbles only) — caller should open the full-screen media viewer. */
        void onImageClick();
        /** Tapped the single-media download gate pill (setMediaDownloadGate) while idle (not yet downloading) —
         *  caller should start the manual download and drive setMediaDownloadProgress() as it reports progress. */
        void onMediaDownloadClick();
        /** Tapped cell `index` inside a media-group grid (bindMediaGroup only) — caller should open the gallery viewer at that index. */
        void onMediaCellClick(int index);
        /** Tapped the master "Download N photos" pill on a RECEIVED group — caller should start every pending cell's download (view has already dismissed the pill locally). */
        void onGroupDownloadAllClick();
        /** Tapped an individual still-pending cell directly (gate already dismissed) — caller should start that one cell's download. */
        void onGroupCellDownloadClick(int index);
        /** Tapped the reaction badge — caller should open the reaction-details/picker sheet (same as ll_reactions' click listener on the legacy path). */
        void onReactionsClick();
    }

    /** Immutable per-cell descriptor for bindMediaGroup(); bitmaps are supplied
     *  later, one at a time, via setMediaGroupBitmap() as Glide decodes them. */
    public static final class GridItem {
        public final boolean isVideo;
        public final String duration; // e.g. "0:32"; null/empty if none or not a video
        public final String caption;  // per-item caption; null/empty for none — see setGroupCaptions doc
        public GridItem(boolean isVideo, @Nullable String duration) {
            this(isVideo, duration, null);
        }
        public GridItem(boolean isVideo, @Nullable String duration, @Nullable String caption) {
            this.isVideo = isVideo;
            this.duration = duration;
            this.caption = caption;
        }
    }

    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint footerPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bubbleRect = new RectF();

    private String messageText = "";
    private String footerTimeText = "";
    private boolean sent = false;
    private boolean read = false;
    private boolean delivered = false;

    private StaticLayout textLayout;
    private GradientDrawable bubbleDrawable;
    private int lastCacheKey = -1;

    private float density;
    private int bubbleLeft, bubbleTop;
    private float footerReserveWidth;

    // ── Reply-preview state ──────────────────────────────────────────
    private boolean hasReply = false;
    private String replySenderName = "";
    private String replyText = "";
    private Bitmap replyThumb;

    private final Paint replyBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint replyBarPaint = new Paint();
    private final TextPaint replySenderPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint replyTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Rect replyThumbSrcRect = new Rect();
    private final RectF replyThumbDstRect = new RectF();

    private StaticLayout replySenderLayout;
    private StaticLayout replyTextLayout;
    private final RectF replyBoxRect = new RectF();
    private int replyBoxHeight = 0;

    // ── Media (image) bubble state ───────────────────────────────────
    private boolean isMedia = false;
    private boolean mediaHasCaption = false;
    private Bitmap mediaBitmap;
    private final Paint mediaBitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint mediaPlaceholderPaint = new Paint();
    private final Paint mediaPillBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint mediaPillTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mediaRect = new RectF();
    private final RectF mediaPillRect = new RectF();
    private final android.graphics.Matrix mediaShaderMatrix = new android.graphics.Matrix();

    // ── Single-media manual download gate state — see setMediaDownloadGate(). ──
    private boolean mediaGated = false;
    private boolean mediaDownloading = false;
    private int mediaDownloadProgress = -1; // -1 = indeterminate, 0-100 = live percent
    private String mediaDownloadLabel = "";
    private final Paint mediaGateScrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mediaGatePillBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint mediaGatePillTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mediaGatePillIconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mediaGatePillRect = new RectF();

    // ── Reaction badge state — independent of text/media/group mode; any
    // of the three can have reactions overlaid. ──
    private boolean hasReactions = false;
    private String reactionsText = "";
    private final TextPaint reactionsTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF reactionsRect = new RectF();

    // ── Pinned label state ────────────────────────────────────────────
    private boolean isPinned = false;
    private final TextPaint pinnedLabelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private int pinnedLabelHeight = 0;
    private float pinnedLabelWidth = 0;

    // ── Group-chat sender-name state — mirrors tv_sender_name in
    // item_message_received.xml. Only meaningful for received messages;
    // the sender AVATAR (iv_sender_avatar) is intentionally not modeled —
    // it's dead markup in the legacy layout too (never bound anywhere in
    // MessagePagingAdapter), so there's nothing functional to mirror. ──
    private boolean hasGroupSender = false;
    private String groupSenderName = "";
    private final TextPaint groupSenderPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private int groupSenderTextHeight = 0;
    private float groupSenderWidth = 0;

    // ── Forwarded-label state — stacks below the pinned-label/group-sender
    // row above the bubble (own row, own baseline; see onMeasure). ──
    private boolean hasForwarded = false;
    private String forwardedText = "";
    private final TextPaint forwardedPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private int forwardedTextHeight = 0;
    private float forwardedTextWidth = 0;
    // Baselines for all three "above the bubble" rows — computed once per
    // onMeasure() pass, consumed by the matching drawXxx() in onDraw() so
    // the draw path never has to re-derive vertical position from scratch.
    private float pinnedBaselineY = 0;
    private float groupSenderBaselineY = 0;
    private float forwardedBaselineY = 0;

    // ── Deleted-message placeholder style — only affects the plain-text
    // path (bind()); caller substitutes the placeholder string itself. ──
    private boolean isDeletedStyle = false;

    // ── Disappearing-message countdown state — an extra segment in the
    // footer row, drawn just before the timestamp/tick. ──
    private boolean hasExpiry = false;
    private String expiryText = "";
    private final TextPaint expiryPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    // ── Media GROUP (multi-image/video grid) state — entirely separate
    // from the single-image `isMedia` state above. ──
    private boolean isMediaGroup = false;
    private java.util.List<GridItem> groupItems = java.util.Collections.emptyList();
    private Bitmap[] groupBitmaps = new Bitmap[0];
    private final RectF[] groupRects = new RectF[GROUP_MAX_VISIBLE];
    private int groupVisibleCount = 0;
    private int groupRemaining = 0; // >0 only on the last visible cell when total > GROUP_MAX_VISIBLE
    private boolean groupHasCaption = false;
    private StaticLayout groupCaptionLayout;
    private final RectF groupContentRect = new RectF(); // whole grid area (no reply/caption)
    private final Paint groupCellBgPaint = new Paint();
    private final Paint groupBitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint groupScrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint groupMoreOverlayPaint = new Paint();
    private final TextPaint groupMoreTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint groupPlayCirclePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint groupPlayTrianglePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint groupDurationTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint groupDurationBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Matrix groupShaderMatrix = new android.graphics.Matrix();
    private final android.graphics.Path groupPlayTrianglePath = new android.graphics.Path();
    private final TextPaint groupCaptionTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint groupItemCaptionPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint groupItemCaptionScrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // ── Received-group manual download-gate state — only ever set for
    // RECEIVED groups (see setGroupDownloadGate()); stays all-false/inert
    // for SENT groups and for received groups with nothing left to
    // download. Recomputed fresh on every bindMediaGroup() so a recycled
    // view never carries a stale gate from the previous message. ──
    private boolean groupGateActive = false;
    private int groupGatePendingCount = 0;
    private boolean[] groupCellPending = new boolean[0];
    private boolean[] groupCellDownloading = new boolean[0];
    private int[] groupCellProgress = new int[0]; // parallel to groupCellDownloading; -1 = indeterminate
    private final Paint groupGateScrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint groupGatePillBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint groupGatePillTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint groupGatePillIconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint groupCellGateDimPaint = new Paint();
    private final Paint groupCellGateBadgeBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint groupCellGateIconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF groupGatePillRect = new RectF();
    private final android.graphics.Path groupGateIconPath = new android.graphics.Path();

    private OnBubbleClickListener clickListener;
    private final GestureDetector gestureDetector;

    public MessageBubbleCanvasView(Context ctx) {
        this(ctx, null);
    }

    public MessageBubbleCanvasView(Context ctx, @Nullable android.util.AttributeSet attrs) {
        super(ctx, attrs);
        density = ctx.getResources().getDisplayMetrics().density;
        textPaint.setTextSize(spToPx(TEXT_SIZE_SP));
        footerPaint.setTextSize(spToPx(FOOTER_TEXT_SP));
        footerPaint.setAlpha(180);
        replySenderPaint.setTextSize(spToPx(REPLY_SENDER_SIZE_SP));
        replySenderPaint.setFakeBoldText(true);
        replyTextPaint.setTextSize(spToPx(REPLY_TEXT_SIZE_SP));
        mediaPlaceholderPaint.setColor(MEDIA_PLACEHOLDER_COLOR);
        mediaPillBgPaint.setColor(MEDIA_PILL_BG);
        mediaPillTextPaint.setColor(MEDIA_PILL_TEXT);
        mediaPillTextPaint.setTextSize(spToPx(FOOTER_TEXT_SP));

        reactionsTextPaint.setTextSize(spToPx(REACTIONS_TEXT_SP));
        reactionsTextPaint.setShadowLayer(2f * density, 0f, 1f * density, REACTIONS_SHADOW_COLOR);

        pinnedLabelPaint.setTextSize(spToPx(PINNED_LABEL_TEXT_SP));
        pinnedLabelPaint.setColor(PINNED_LABEL_COLOR);

        groupSenderPaint.setTextSize(spToPx(GROUP_SENDER_TEXT_SP));
        groupSenderPaint.setFakeBoldText(true);

        forwardedPaint.setTextSize(spToPx(FORWARDED_LABEL_TEXT_SP));
        forwardedPaint.setColor(FORWARDED_LABEL_COLOR);
        forwardedPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.ITALIC));

        expiryPaint.setTextSize(spToPx(EXPIRY_TEXT_SP));
        expiryPaint.setColor(EXPIRY_COLOR);

        groupCellBgPaint.setColor(Color.DKGRAY);
        groupMoreOverlayPaint.setColor(0xBB000000);
        groupMoreTextPaint.setColor(Color.WHITE);
        groupMoreTextPaint.setFakeBoldText(true);
        groupMoreTextPaint.setTextSize(22f * density);
        groupMoreTextPaint.setTextAlign(Paint.Align.CENTER);
        groupPlayCirclePaint.setColor(0x99000000);
        groupPlayTrianglePaint.setColor(Color.WHITE);
        groupDurationTextPaint.setColor(Color.WHITE);
        groupDurationTextPaint.setFakeBoldText(true);
        groupDurationTextPaint.setTextSize(GROUP_DURATION_TEXT_SP * density);
        groupDurationBgPaint.setColor(0x99000000);
        groupCaptionTextPaint.setColor(Color.WHITE);
        groupCaptionTextPaint.setTextSize(GROUP_CAPTION_TEXT_SP * density);
        groupItemCaptionPaint.setColor(Color.WHITE);
        groupItemCaptionPaint.setTextSize(GROUP_ITEM_CAPTION_TEXT_SP * density);
        for (int i = 0; i < groupRects.length; i++) groupRects[i] = new RectF();

        groupGateScrimPaint.setColor(GROUP_GATE_SCRIM_COLOR);
        groupGatePillBgPaint.setColor(GROUP_GATE_PILL_BG);
        groupGatePillTextPaint.setColor(Color.WHITE);
        groupGatePillTextPaint.setFakeBoldText(true);
        groupGatePillTextPaint.setTextSize(GROUP_GATE_PILL_TEXT_SP * density);
        groupGatePillIconPaint.setColor(Color.WHITE);
        groupGatePillIconPaint.setStyle(Paint.Style.STROKE);
        groupGatePillIconPaint.setStrokeWidth(1.6f * density);
        groupGatePillIconPaint.setStrokeCap(Paint.Cap.ROUND);
        groupGatePillIconPaint.setStrokeJoin(Paint.Join.ROUND);
        groupCellGateDimPaint.setColor(GROUP_CELL_GATE_DIM_COLOR);
        groupCellGateBadgeBgPaint.setColor(GROUP_CELL_GATE_BADGE_BG);
        groupCellGateIconPaint.setColor(Color.WHITE);
        groupCellGateIconPaint.setStyle(Paint.Style.STROKE);
        groupCellGateIconPaint.setStrokeWidth(1.4f * density);
        groupCellGateIconPaint.setStrokeCap(Paint.Cap.ROUND);
        groupCellGateIconPaint.setStrokeJoin(Paint.Join.ROUND);

        mediaGateScrimPaint.setColor(MEDIA_GATE_SCRIM_COLOR);
        mediaGatePillBgPaint.setColor(GROUP_GATE_PILL_BG);
        mediaGatePillTextPaint.setColor(Color.WHITE);
        mediaGatePillTextPaint.setFakeBoldText(true);
        mediaGatePillTextPaint.setTextSize(GROUP_GATE_PILL_TEXT_SP * density);
        mediaGatePillIconPaint.setColor(Color.WHITE);
        mediaGatePillIconPaint.setStyle(Paint.Style.STROKE);
        mediaGatePillIconPaint.setStrokeWidth(1.8f * density);
        mediaGatePillIconPaint.setStrokeCap(Paint.Cap.ROUND);
        mediaGatePillIconPaint.setStrokeJoin(Paint.Join.ROUND);

        setWillNotDraw(false);

        gestureDetector = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                if (clickListener != null) clickListener.onBubbleClick();
                return true;
            }
            @Override public void onLongPress(MotionEvent e) {
                if (clickListener != null) clickListener.onBubbleLongClick();
            }
        });
    }

    public void setOnBubbleClickListener(OnBubbleClickListener l) {
        this.clickListener = l;
    }

    /**
     * Bind this view to a message. Call from the adapter's onBindViewHolder
     * in place of the old setText()/Glide/etc. calls for the plain-text case.
     */
    public void bind(String text, String timeText, boolean isSent, boolean isRead, boolean isDelivered) {
        this.isMedia = false;
        this.isMediaGroup = false;
        this.mediaBitmap = null;
        this.messageText = text != null ? text : "";
        this.footerTimeText = timeText != null ? timeText : "";
        this.sent = isSent;
        this.read = isRead;
        this.delivered = isDelivered;

        Context ctx = getContext();
        textPaint.setColor(ChatThemeManager.get(ctx).getTextColor(ctx, sent));
        footerPaint.setColor(textPaint.getColor());
        tickPaint.setColor(ChatThemeManager.get(ctx).getTickColor(read));

        // Bubble drawable cache — same 4-combo cache key scheme as
        // ChatThemeManager's View-based bubbles (sent<<1 | hasReply).
        int cacheKey = (sent ? 1 : 0) << 1 | (hasReply ? 1 : 0);
        if (cacheKey != lastCacheKey || bubbleDrawable == null) {
            bubbleDrawable = buildBubbleDrawable(ctx, sent);
            lastCacheKey = cacheKey;
        }
        resolveReplyColors(ctx);

        textLayout = null; // recomputed in onMeasure
        requestLayout();
        invalidate();
    }

    /**
     * Bind this view to a single-image media message (matches iv_image in
     * item_message_sent/received.xml — fixed 180dp square, centerCrop,
     * 12dp rounded corners). Pass bitmap=null if the image hasn't finished
     * decoding yet; a placeholder box is drawn instead and setMediaBitmap()
     * can swap in the real bitmap later without a full rebind.
     *
     * @param caption optional caption text below the image; null/empty for
     *                a captionless image (timestamp/tick then overlay the
     *                image itself as a translucent pill, WhatsApp-style)
     */
    public void bindMedia(@Nullable Bitmap bitmap, @Nullable String caption, String timeText,
                           boolean isSent, boolean isRead, boolean isDelivered) {
        this.isMedia = true;
        this.isMediaGroup = false;
        this.mediaBitmap = bitmap;
        // Cleared fresh on every bind — a recycled holder must not carry a
        // stale gate from whatever RECEIVED image the view last showed;
        // the caller re-arms it with setMediaDownloadGate() right after this
        // call if the new message actually needs a manual download.
        this.mediaGated = false;
        this.mediaDownloading = false;
        this.mediaDownloadProgress = -1;
        this.messageText = caption != null ? caption : "";
        this.mediaHasCaption = !this.messageText.isEmpty();
        this.footerTimeText = timeText != null ? timeText : "";
        this.sent = isSent;
        this.read = isRead;
        this.delivered = isDelivered;

        Context ctx = getContext();
        textPaint.setColor(ChatThemeManager.get(ctx).getTextColor(ctx, sent));
        footerPaint.setColor(textPaint.getColor());
        tickPaint.setColor(ChatThemeManager.get(ctx).getTickColor(read));

        int cacheKey = (sent ? 1 : 0) << 1 | (hasReply ? 1 : 0);
        if (cacheKey != lastCacheKey || bubbleDrawable == null) {
            bubbleDrawable = buildBubbleDrawable(ctx, sent);
            lastCacheKey = cacheKey;
        }
        resolveReplyColors(ctx);

        textLayout = null; // recomputed in onMeasure (only used if mediaHasCaption)
        requestLayout();
        invalidate();
    }

    /** Swap in a decoded Bitmap once an async (Glide) load finishes — no re-measure needed, same fixed image size. */
    public void setMediaBitmap(@Nullable Bitmap bitmap) {
        this.mediaBitmap = bitmap;
        invalidate();
    }

    /**
     * Arms (or updates) the manual download gate over a single-image bubble —
     * mirrors the legacy fl_download_overlay/pb_download_spinner treatment
     * for a RECEIVED "image" not yet cached locally. Call once right after
     * bindMedia() for a message that still needs downloading; skip entirely
     * (or call clearMediaDownloadGate()) for sent/already-cached images.
     *
     * @param downloading true once the download is actually in flight (shows
     *                     the spinner/percentage ring); false for the idle
     *                     "tap to download" pill state.
     * @param progressPercent 0-100 for a live percentage, or -1 for an
     *                        indeterminate spinner (e.g. before the first
     *                        progress callback, or while only fetching size).
     * @param idleLabel text shown next to the idle icon (e.g. a file-size
     *                  string or "Photo"/"Tap to retry"); ignored while downloading.
     */
    public void setMediaDownloadGate(boolean downloading, int progressPercent, @Nullable String idleLabel) {
        this.mediaGated = true;
        this.mediaDownloading = downloading;
        this.mediaDownloadProgress = progressPercent;
        this.mediaDownloadLabel = idleLabel != null ? idleLabel : "";
        invalidate();
    }

    /** Updates just the live percentage while a download is already in flight (call on every onProgress tick). */
    public void setMediaDownloadProgress(int progressPercent) {
        this.mediaGated = true;
        this.mediaDownloading = true;
        this.mediaDownloadProgress = progressPercent;
        invalidate();
    }

    /** Dismisses the gate entirely — call once the real bitmap has been supplied via setMediaBitmap(). */
    public void clearMediaDownloadGate() {
        this.mediaGated = false;
        this.mediaDownloading = false;
        this.mediaDownloadProgress = -1;
        invalidate();
    }

    /**
     * Bind this view to a SENT multi-image/video group (matches ll_media_group
     * + MediaGroupLayoutHelper's 1/2/3/4/5-9/10+ grid rules). Pass bitmap=null
     * per-cell placeholders first (thumbnails still loading); call
     * setMediaGroupBitmap(index, bitmap) as each Glide load finishes.
     *
     * Deliberately conservative like bindMedia(): only image/video cells, no
     * per-item captions, no manual-download gate — see isCanvasEligible() in
     * MessagePagingAdapter for exactly what qualifies.
     */
    public void bindMediaGroup(java.util.List<GridItem> items, @Nullable String caption, String timeText,
                                boolean isSent, boolean isRead, boolean isDelivered) {
        this.isMedia = false;
        this.isMediaGroup = true;
        this.groupItems = items != null ? items : java.util.Collections.emptyList();
        int total = this.groupItems.size();
        this.groupVisibleCount = Math.min(total, GROUP_MAX_VISIBLE);
        this.groupRemaining = total - GROUP_MAX_VISIBLE; // used only if > 0
        this.groupBitmaps = new Bitmap[groupVisibleCount];
        // Gate state is cleared here and only re-armed by an explicit
        // setGroupDownloadGate() call right after bind — for SENT groups
        // (or a RECEIVED group with nothing left to fetch) that call is
        // simply never made / made with an all-false array, so the gate
        // stays inert instead of showing a stale pill from a recycled view.
        this.groupGateActive = false;
        this.groupGatePendingCount = 0;
        this.groupCellPending = new boolean[groupVisibleCount];
        this.groupCellDownloading = new boolean[groupVisibleCount];
        this.groupCellProgress = new int[groupVisibleCount];
        java.util.Arrays.fill(this.groupCellProgress, -1);
        this.groupHasCaption = caption != null && !caption.isEmpty();
        this.messageText = groupHasCaption ? caption : "";
        this.footerTimeText = timeText != null ? timeText : "";
        this.sent = isSent;
        this.read = isRead;
        this.delivered = isDelivered;

        Context ctx = getContext();
        textPaint.setColor(ChatThemeManager.get(ctx).getTextColor(ctx, sent));
        footerPaint.setColor(textPaint.getColor());
        tickPaint.setColor(ChatThemeManager.get(ctx).getTickColor(read));

        int cacheKey = (sent ? 1 : 0) << 1 | (hasReply ? 1 : 0);
        if (cacheKey != lastCacheKey || bubbleDrawable == null) {
            bubbleDrawable = buildBubbleDrawable(ctx, sent);
            lastCacheKey = cacheKey;
        }
        resolveReplyColors(ctx);

        groupCaptionLayout = null; // recomputed in onMeasure
        textLayout = null;
        requestLayout();
        invalidate();
    }

    /** Swap in a decoded per-cell thumbnail once its Glide load finishes — no re-measure needed. */
    public void setMediaGroupBitmap(int index, @Nullable Bitmap bitmap) {
        if (index < 0 || index >= groupBitmaps.length) return;
        groupBitmaps[index] = bitmap;
        invalidate();
    }

    /**
     * Arms (or clears) the RECEIVED-group manual download-gate. Call once,
     * right after bindMediaGroup(), with one boolean per visible cell:
     * true = that cell is an un-cached image still needing a manual
     * download, false = already local (or a video/audio/file cell, which
     * never gates). Pass an all-false array (or simply never call this)
     * for SENT groups — the gate only ever applies to received media.
     *
     * While any entry is true, the whole grid shows a single dimmed
     * overlay with a centered "Download N photos" pill (mirrors
     * MediaGroupLayoutHelper's addMasterDownloadOverlay) that swallows
     * every tap in the grid until the person taps it — same as the old
     * View-based master overlay blocking the per-cell overlays beneath it.
     */
    public void setGroupDownloadGate(@Nullable boolean[] cellNeedsDownload) {
        int n = groupVisibleCount;
        this.groupCellPending = new boolean[n];
        this.groupCellDownloading = new boolean[n];
        this.groupCellProgress = new int[n];
        java.util.Arrays.fill(this.groupCellProgress, -1);
        int pending = 0;
        if (cellNeedsDownload != null) {
            for (int i = 0; i < n && i < cellNeedsDownload.length; i++) {
                groupCellPending[i] = cellNeedsDownload[i];
                if (cellNeedsDownload[i]) pending++;
            }
        }
        this.groupGatePendingCount = pending;
        this.groupGateActive = pending > 0;
        invalidate();
    }

    /** Marks one cell as actively downloading (or not) — draws a small dim+icon
     *  badge on just that cell once the master gate has been dismissed. Resets
     *  its progress to indeterminate; call setGroupCellProgress() as real
     *  percentages start arriving. */
    public void setGroupCellDownloading(int index, boolean downloading) {
        if (index < 0 || index >= groupCellDownloading.length) return;
        groupCellDownloading[index] = downloading;
        if (downloading && index < groupCellProgress.length) groupCellProgress[index] = -1;
        invalidate();
    }

    /** Updates the live download percentage for one in-flight group cell (call on every onProgress
     *  tick); pass -1 for an indeterminate spinner. Implies the cell is downloading. */
    public void setGroupCellProgress(int index, int percent) {
        if (index < 0 || index >= groupCellDownloading.length) return;
        groupCellDownloading[index] = true;
        if (index < groupCellProgress.length) groupCellProgress[index] = percent;
        invalidate();
    }

    /** Call once a cell's manual download finishes and its full-res bitmap has
     *  been supplied via setMediaGroupBitmap() — clears its pending/downloading
     *  badge so the cell renders as a plain image again. */
    public void markGroupCellDownloaded(int index) {
        if (index < 0 || index >= groupCellPending.length) return;
        groupCellPending[index] = false;
        if (index < groupCellDownloading.length) groupCellDownloading[index] = false;
        if (index < groupCellProgress.length) groupCellProgress[index] = -1;
        invalidate();
    }

    /**
     * Attach a reply-preview strip (quoted sender + quoted text + optional
     * thumbnail) to this bubble. Call AFTER bind() (bind() must have already
     * set `sent`, since reply-strip colors differ for sent vs received —
     * matching bg_reply_preview_sent/received.xml + the tv_reply_sender/
     * tv_reply_text color attrs in the two item_message_*.xml layouts).
     *
     * @param senderName quoted message's sender display name (1 line, ellipsized)
     * @param text       quoted message's text/caption (up to 2 lines, ellipsized)
     * @param thumb      optional small bitmap (e.g. Glide .asBitmap() result already
     *                   decoded by the caller) shown at 44dp on the right, same as
     *                   iv_reply_thumb. Pass null for text-only replies.
     */
    public void setReply(String senderName, String text, @Nullable Bitmap thumb) {
        this.hasReply = true;
        this.replySenderName = senderName != null ? senderName : "";
        this.replyText = text != null ? text : "";
        this.replyThumb = thumb;
        resolveReplyColors(getContext());
        // Bubble corner treatment doesn't actually depend on hasReply in this
        // view (unlike the legacy bg_reply_preview backgrounds, the outer
        // bubble shape is unaffected), but keep cache key in sync in case
        // that changes later.
        int cacheKey = (sent ? 1 : 0) << 1 | 1;
        if (cacheKey != lastCacheKey) {
            bubbleDrawable = buildBubbleDrawable(getContext(), sent);
            lastCacheKey = cacheKey;
        }
        replySenderLayout = null; // recomputed in onMeasure
        replyTextLayout = null;
        requestLayout();
        invalidate();
    }

    /** Call when a message has no reply — clears any previous reply-strip state so a recycled view doesn't show stale data. */
    public void clearReply() {
        this.hasReply = false;
        this.replySenderName = "";
        this.replyText = "";
        this.replyThumb = null;
        this.replySenderLayout = null;
        this.replyTextLayout = null;
        this.replyBoxHeight = 0;
        requestLayout();
        invalidate();
    }

    /**
     * Attach a reaction badge to this bubble — mirrors ll_reactions/
     * tv_reactions in item_message_sent/received.xml: plain emoji text
     * (e.g. "😂2 👍"), no background box, floating over the bubble's
     * bottom-end corner. Works the same regardless of bind()/bindMedia()/
     * bindMediaGroup() mode. Call setReactions(text) when the message has
     * reactions, clearReactions() otherwise — always call one of the two,
     * every bind, since a recycled view holds whatever the previous
     * message left in it.
     *
     * @param text already-formatted reaction summary (caller does the
     *             emoji-counting/truncation, same as bindReactionsOnly()
     *             does for the legacy tv_reactions TextView); null/empty
     *             is treated the same as clearReactions().
     */
    public void setReactions(@Nullable String text) {
        this.hasReactions = text != null && !text.isEmpty();
        this.reactionsText = text != null ? text : "";
        requestLayout();
        invalidate();
    }

    /** Call when a message has no reactions — clears any previous badge state so a recycled view doesn't show a stale reaction. */
    public void clearReactions() {
        this.hasReactions = false;
        this.reactionsText = "";
        requestLayout();
        invalidate();
    }

    /**
     * Show/hide the "📌 Pinned" label above the bubble's top-end corner.
     * Works alongside any bind mode (text/media/group) and with or without
     * a reply/reactions. Always call this every bind — true or false —
     * since a recycled view holds whatever the previous message left in it.
     */
    public void setPinned(boolean pinned) {
        this.isPinned = pinned;
        requestLayout();
        invalidate();
    }

    /**
     * Show the sender-name row above the bubble's top-start corner, for a
     * received message in a group chat — mirrors tv_sender_name (11sp,
     * bold, brand_primary). Caller (MessagePagingAdapter) only calls this
     * for `!sent && isGroup` messages, same gate the legacy path uses;
     * this view itself doesn't re-check sent/group, it just draws
     * whatever name it's given.
     *
     * @param name display name; null/empty is treated as clearGroupSender().
     */
    public void setGroupSender(@Nullable String name) {
        this.hasGroupSender = name != null && !name.isEmpty();
        this.groupSenderName = name != null ? name : "";
        if (hasGroupSender) {
            groupSenderPaint.setColor(androidx.core.content.ContextCompat.getColor(
                    getContext(), com.callx.app.core.R.color.brand_primary));
        }
        requestLayout();
        invalidate();
    }

    /** Call for sent messages, or received messages outside a group chat — clears any stale sender-name state on a recycled view. */
    public void clearGroupSender() {
        this.hasGroupSender = false;
        this.groupSenderName = "";
        requestLayout();
        invalidate();
    }

    /**
     * Show the italic "↪ Forwarded from X" label, stacked directly below
     * the pinned-label/group-sender row (mirrors tv_forwarded's
     * constraintTop_toBottomOf tv_sender_name in item_message_received.xml —
     * same top-start corner, works for sent and received alike since this
     * view anchors it to the bubble's own left edge rather than a shared
     * avatar column). Always call this every bind (or clearForwarded()) —
     * a recycled view otherwise keeps the previous message's label.
     *
     * @param originalSenderName original sender's display name; null/empty
     *                           is treated as clearForwarded().
     */
    public void setForwardedFrom(@Nullable String originalSenderName) {
        boolean fwd = originalSenderName != null && !originalSenderName.isEmpty();
        this.hasForwarded = fwd;
        this.forwardedText = fwd ? ("\u21AA Forwarded from " + originalSenderName) : "";
        requestLayout();
        invalidate();
    }

    /** Call when a message wasn't forwarded — clears any stale label state on a recycled view. */
    public void clearForwarded() {
        setForwardedFrom(null);
    }

    /**
     * Switch the plain-text bubble into the deleted-message placeholder
     * look — italic + 60% alpha, matching bindMessage()'s
     * tvMessage.setAlpha(0.6f) treatment for "This message was deleted" /
     * "You deleted this message". Call bind() with the already-substituted
     * placeholder text FIRST, then this every time (true or false) — a
     * recycled view otherwise keeps the previous message's italic/alpha
     * state. Only meaningful for the plain-text bind() path: a deleted
     * message never reaches bindMedia()/bindMediaGroup() (see
     * isCanvasEligible() in MessagePagingAdapter).
     */
    public void setDeletedStyle(boolean deleted) {
        this.isDeletedStyle = deleted;
        textPaint.setTypeface(deleted ? Typeface.create(Typeface.DEFAULT, Typeface.ITALIC) : Typeface.DEFAULT);
        textPaint.setAlpha(deleted ? DELETED_TEXT_ALPHA : 255);
        textLayout = null; // rebuild with the new typeface/alpha before the next draw
        requestLayout();
        invalidate();
    }

    /**
     * Show/update the "⏳ mm:ss" disappearing-message countdown in the
     * footer row, just before the timestamp — mirrors tv_expiry in
     * item_message_sent/received.xml's ll_msg_footer. Caller
     * (MessagePagingAdapter) re-calls this once a second via the shared
     * ExpiryTickManager while the message has an active expiresAt, and
     * clearExpiry() once it fires (or immediately, for a message that
     * never had one). Already-formatted text (caller does the mm:ss
     * formatting, same as formatRemaining() does for the legacy TextView).
     */
    public void setExpiryText(@Nullable String text) {
        this.hasExpiry = text != null && !text.isEmpty();
        this.expiryText = text != null ? text : "";
        requestLayout();
        invalidate();
    }

    /** Call once the countdown finishes, or for a message with no expiry at all. */
    public void clearExpiry() {
        setExpiryText(null);
    }

    private void resolveReplyColors(Context ctx) {
        if (sent) {
            replyBgPaint.setColor(SENT_REPLY_BG);
            replyBarPaint.setColor(SENT_REPLY_BAR);
            replySenderPaint.setColor(SENT_REPLY_SENDER);
            replyTextPaint.setColor(SENT_REPLY_TEXT);
        } else {
            replyBgPaint.setColor(RECEIVED_REPLY_BG);
            int brand = androidx.core.content.ContextCompat.getColor(ctx, com.callx.app.core.R.color.brand_primary);
            replyBarPaint.setColor(brand);
            replySenderPaint.setColor(brand);
            replyTextPaint.setColor(androidx.core.content.ContextCompat.getColor(ctx, com.callx.app.core.R.color.bubble_received_text));
        }
    }

    private GradientDrawable buildBubbleDrawable(Context ctx, boolean sent) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(androidx.core.content.ContextCompat.getColor(ctx, sent
                ? com.callx.app.core.R.color.bubble_sent
                : com.callx.app.core.R.color.bubble_received));
        float r = CORNER_RADIUS_DP * density;
        float tail = TAIL_RADIUS_DP * density;
        if (sent) {
            gd.setCornerRadii(new float[]{r, r, r, r, tail, tail, r, r});
        } else {
            gd.setCornerRadii(new float[]{tail, tail, r, r, r, r, r, r});
        }
        return gd;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int parentWidth = MeasureSpec.getSize(widthMeasureSpec);
        int maxBubbleWidth = Math.round(parentWidth * MAX_BUBBLE_WIDTH_FRACTION);
        int hPad = Math.round(H_PADDING_DP * density);
        int vPad = Math.round(V_PADDING_DP * density);
        int footerHeight = Math.round(spToPx(FOOTER_TEXT_SP) + FOOTER_GAP_DP * density);
        int maxTextWidth = Math.max(1, maxBubbleWidth - hPad * 2);

        // ── Pinned label / group sender-name (+ broadcast badge, which
        // reuses the same row via setGroupSender) / forwarded label — all
        // sit above the bubble entirely, so they shift bubbleTop down
        // rather than participating in bubbleHeight. Row 1 (pinned at
        // top-end, sender-name at top-start) shares one reserved band
        // sized to whichever of the two is taller; forwarded stacks in
        // its own row directly below row 1 — mirrors tv_forwarded's
        // constraintTop_toBottomOf tv_sender_name in the legacy layout. ──
        int labelGap = Math.round(PINNED_LABEL_GAP_DP * density);
        int row1Height = 0;
        if (isPinned) {
            Paint.FontMetrics pfm = pinnedLabelPaint.getFontMetrics();
            pinnedLabelHeight = Math.round(pfm.descent - pfm.ascent);
            pinnedLabelWidth = pinnedLabelPaint.measureText(PINNED_LABEL_TEXT);
            row1Height = Math.max(row1Height, pinnedLabelHeight);
        } else {
            pinnedLabelHeight = 0;
            pinnedLabelWidth = 0;
        }
        if (hasGroupSender) {
            Paint.FontMetrics gfm = groupSenderPaint.getFontMetrics();
            groupSenderTextHeight = Math.round(gfm.descent - gfm.ascent);
            groupSenderWidth = groupSenderPaint.measureText(groupSenderName);
            row1Height = Math.max(row1Height, groupSenderTextHeight);
        } else {
            groupSenderTextHeight = 0;
            groupSenderWidth = 0;
        }

        int aboveExtra = 0;
        if (row1Height > 0) {
            pinnedBaselineY = row1Height - pinnedLabelPaint.getFontMetrics().descent;
            groupSenderBaselineY = row1Height - groupSenderPaint.getFontMetrics().descent;
            aboveExtra = row1Height + labelGap;
        }

        if (hasForwarded) {
            Paint.FontMetrics ffm = forwardedPaint.getFontMetrics();
            forwardedTextHeight = Math.round(ffm.descent - ffm.ascent);
            forwardedTextWidth = forwardedPaint.measureText(forwardedText);
            forwardedBaselineY = aboveExtra + forwardedTextHeight - ffm.descent;
            aboveExtra += forwardedTextHeight + labelGap;
        } else {
            forwardedTextHeight = 0;
            forwardedTextWidth = 0;
        }
        int pinnedTopExtra = aboveExtra;

        // ── Reply-preview box — measured the same way regardless of
        // isMedia; a reply strip can sit above either a text or an image
        // bubble. ──
        int replyBoxContentWidth = 0;
        if (hasReply) {
            int replyMargin = Math.round(REPLY_MARGIN_DP * density);
            int replyBar = Math.round(REPLY_BAR_WIDTH_DP * density);
            int replyPadH = Math.round(REPLY_PADDING_H_DP * density);
            int replyPadV = Math.round(REPLY_PADDING_V_DP * density);
            int replyThumbSize = replyThumb != null ? Math.round(REPLY_THUMB_SIZE_DP * density) : 0;
            int replyThumbMargin = replyThumb != null ? Math.round(REPLY_THUMB_MARGIN_DP * density) : 0;

            int replyTextColMaxWidth = Math.max(1, maxTextWidth - replyBar - replyPadH * 2
                    - replyThumbSize - replyThumbMargin * 2);

            replySenderLayout = StaticLayout.Builder
                    .obtain(replySenderName, 0, replySenderName.length(), replySenderPaint, replyTextColMaxWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setMaxLines(1)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .setIncludePad(false)
                    .build();
            replyTextLayout = StaticLayout.Builder
                    .obtain(replyText, 0, replyText.length(), replyTextPaint, replyTextColMaxWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setMaxLines(2)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .setIncludePad(false)
                    .build();

            int replyTextColWidth = Math.max(maxLineWidth(replySenderLayout), maxLineWidth(replyTextLayout));
            int replyTextColHeight = replySenderLayout.getHeight() + replyTextLayout.getHeight();

            int replyInnerHeight = Math.max(replyTextColHeight + replyPadV * 2, replyThumbSize);
            replyBoxHeight = Math.max(Math.round(REPLY_MIN_HEIGHT_DP * density), replyInnerHeight) + replyMargin * 2;
            replyBoxContentWidth = replyBar + replyTextColWidth + replyPadH * 2
                    + (replyThumb != null ? replyThumbSize + replyThumbMargin * 2 : 0);
        } else {
            replyBoxHeight = 0;
        }
        int replyGap = hasReply ? Math.round(REPLY_GAP_TO_MESSAGE_DP * density) : 0;

        int bubbleContentWidth;
        int bubbleHeight;

        if (isMedia) {
            // ── Image bubble (fixed 180dp square) ──
            int mediaSize = Math.round(MEDIA_SIZE_DP * density);
            int captionWidth = 0;
            int captionBlockHeight = 0;

            if (mediaHasCaption) {
                float captionFooterReserve = footerPaint.measureText(footerTimeText)
                        + (sent ? (TICK_SIZE_DP + TICK_GAP_DP) * density : 0)
                        + FOOTER_GAP_DP * density;
                textLayout = StaticLayout.Builder
                        .obtain(messageText, 0, messageText.length(), textPaint, maxTextWidth)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                        .setLineSpacing(0f, 1f)
                        .setIncludePad(false)
                        .build();
                captionWidth = maxLineWidth(textLayout);
                int lastLineW = (int) Math.ceil(textLayout.getLineWidth(textLayout.getLineCount() - 1));
                captionWidth = Math.max(captionWidth, (int) (lastLineW + captionFooterReserve));
                captionBlockHeight = Math.round(MEDIA_CAPTION_GAP_DP * density)
                        + textLayout.getHeight() + vPad + footerHeight;
                footerReserveWidth = captionFooterReserve;
            } else {
                textLayout = null;
            }

            bubbleContentWidth = Math.min(
                    Math.max(mediaSize, Math.max(replyBoxContentWidth, captionWidth)), maxTextWidth);

            // Captionless images get a tight vPad margin around the square;
            // captioned images get the same margin plus the caption+footer
            // block below (footer overlay is skipped when there's a caption
            // — see onDraw — matching the text-bubble footer placement).
            bubbleHeight = replyBoxHeight + replyGap + vPad + mediaSize + vPad + captionBlockHeight;

        } else if (isMediaGroup) {
            // ── Media-group grid (multi-image/video) ──
            int[] dims = computeGroupGridDims(groupVisibleCount);
            int gridW = dims[0];
            int gridH = dims[1];

            if (groupHasCaption) {
                groupCaptionLayout = StaticLayout.Builder
                        .obtain(messageText, 0, messageText.length(), groupCaptionTextPaint,
                                Math.max(1, Math.min(gridW - dp(8), maxTextWidth)))
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                        .setMaxLines(3)
                        .setEllipsize(TextUtils.TruncateAt.END)
                        .setIncludePad(false)
                        .build();
            } else {
                groupCaptionLayout = null;
            }

            bubbleContentWidth = Math.min(Math.max(gridW, replyBoxContentWidth), maxTextWidth);
            bubbleHeight = replyBoxHeight + replyGap + vPad + gridH + vPad;

        } else {
            // ── Plain text bubble ──
            footerReserveWidth = footerPaint.measureText(footerTimeText)
                    + (sent ? (TICK_SIZE_DP + TICK_GAP_DP) * density : 0)
                    + FOOTER_GAP_DP * density
                    + expiryReserveWidth();

            textLayout = StaticLayout.Builder
                    .obtain(messageText, 0, messageText.length(), textPaint, maxTextWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(0f, 1f)
                    .setIncludePad(false)
                    .build();

            int textWidth = maxLineWidth(textLayout);
            int lastLineWidth = (int) Math.ceil(textLayout.getLineWidth(textLayout.getLineCount() - 1));
            int neededWidth = Math.max(textWidth, (int) (lastLineWidth + footerReserveWidth));

            bubbleContentWidth = Math.min(Math.max(neededWidth, replyBoxContentWidth), maxTextWidth);
            bubbleHeight = replyBoxHeight + replyGap + textLayout.getHeight() + vPad * 2 + footerHeight;
        }

        int bubbleWidth = bubbleContentWidth + hPad * 2;

        bubbleLeft = sent ? (parentWidth - bubbleWidth) : 0;
        bubbleTop = pinnedTopExtra;
        bubbleRect.set(bubbleLeft, bubbleTop, bubbleLeft + bubbleWidth, bubbleTop + bubbleHeight);

        if (hasReply) {
            int replyMargin = Math.round(REPLY_MARGIN_DP * density);
            replyBoxRect.set(
                    bubbleLeft + replyMargin,
                    bubbleTop + replyMargin,
                    bubbleLeft + bubbleWidth - replyMargin,
                    bubbleTop + replyBoxHeight - replyMargin);
        }

        if (isMedia) {
            int mediaSize = Math.round(MEDIA_SIZE_DP * density);
            float mediaTop = bubbleTop + replyBoxHeight + replyGap + vPad;
            mediaRect.set(bubbleLeft + hPad, mediaTop, bubbleLeft + hPad + mediaSize, mediaTop + mediaSize);

            if (!mediaHasCaption) {
                float pillPadH = MEDIA_PILL_PADDING_H_DP * density;
                float pillPadV = MEDIA_PILL_PADDING_V_DP * density;
                float pillMargin = MEDIA_PILL_MARGIN_DP * density;
                float pillTextW = mediaPillTextPaint.measureText(footerTimeText)
                        + (sent ? (TICK_SIZE_DP + TICK_GAP_DP) * density : 0);
                float pillH = spToPx(FOOTER_TEXT_SP) + pillPadV * 2;
                mediaPillRect.set(
                        mediaRect.right - pillMargin - pillTextW - pillPadH * 2,
                        mediaRect.bottom - pillMargin - pillH,
                        mediaRect.right - pillMargin,
                        mediaRect.bottom - pillMargin);
            }
        }

        if (isMediaGroup) {
            float gridTop = bubbleTop + replyBoxHeight + replyGap + vPad;
            layoutGroupCells(bubbleLeft + hPad, gridTop);
            groupContentRect.set(bubbleLeft + hPad, gridTop,
                    bubbleLeft + bubbleWidth - hPad, bubbleTop + bubbleHeight - vPad);

            if (!groupHasCaption) {
                float pillPadH = MEDIA_PILL_PADDING_H_DP * density;
                float pillPadV = MEDIA_PILL_PADDING_V_DP * density;
                float pillMargin = MEDIA_PILL_MARGIN_DP * density;
                float pillTextW = mediaPillTextPaint.measureText(footerTimeText)
                        + (sent ? (TICK_SIZE_DP + TICK_GAP_DP) * density : 0);
                float pillH = spToPx(FOOTER_TEXT_SP) + pillPadV * 2;
                mediaPillRect.set(
                        groupContentRect.right - pillMargin - pillTextW - pillPadH * 2,
                        groupContentRect.bottom - pillMargin - pillH,
                        groupContentRect.right - pillMargin,
                        groupContentRect.bottom - pillMargin);
            }
        }

        // ── Reaction badge — floats past the bubble's bottom edge, so the
        // view's total height must grow to fit it or it'd be clipped by
        // this View's own canvas bounds (unlike the old ConstraintLayout,
        // which just grew wrap_content to fit an overlapping sibling). ──
        int totalHeight = bubbleTop + bubbleHeight;
        if (hasReactions) {
            float marginEnd = REACTIONS_MARGIN_END_DP * density;
            float overlap = REACTIONS_OVERLAP_DP * density;
            Paint.FontMetrics fm = reactionsTextPaint.getFontMetrics();
            float badgeW = reactionsTextPaint.measureText(reactionsText);
            float badgeH = fm.descent - fm.ascent;
            float right = bubbleRect.right - marginEnd;
            float bottom = bubbleRect.bottom + overlap;
            reactionsRect.set(right - badgeW, bottom - badgeH, right, bottom);
            totalHeight = Math.max(totalHeight, Math.round(bottom));
        } else {
            reactionsRect.setEmpty();
        }

        setMeasuredDimension(parentWidth, totalHeight);
    }

    private int dp(int v) {
        return Math.round(v * density);
    }

    /**
     * Grid outer dimensions [width, height] in px for `n` visible cells,
     * following MediaGroupLayoutHelper's exact rules: 1 → wide single card,
     * 2 → side-by-side pair, 3 → wide top + 2 below, 4 → 2x2, 5-9 → 3x3
     * (rows may be partial; each row still reserves full 3-column width —
     * a deliberate simplification vs. the old WRAP_CONTENT-per-row helper,
     * traded for simpler/robust hit-testing in a Canvas-drawn grid).
     */
    private int[] computeGroupGridDims(int n) {
        int gap = dp(GROUP_GAP);
        if (n <= 0) return new int[]{0, 0};
        if (n == 1) return new int[]{dp(GROUP_SINGLE_W), dp(GROUP_SINGLE_H)};
        if (n == 2) return new int[]{dp(GROUP_PAIR_CELL) * 2 + gap, dp(GROUP_PAIR_CELL)};
        if (n == 3) return new int[]{dp(GROUP_THREE_TOP_W), dp(GROUP_THREE_TOP_H) + gap + dp(GROUP_THREE_BOT)};
        if (n == 4) return new int[]{dp(GROUP_GRID2_CELL) * 2 + gap, dp(GROUP_GRID2_CELL) * 2 + gap};
        int rows = (int) Math.ceil(n / 3f);
        return new int[]{dp(GROUP_GRID3_CELL) * 3 + gap * 2, dp(GROUP_GRID3_CELL) * rows + gap * (rows - 1)};
    }

    /** Fills groupRects[0..groupVisibleCount) with absolute on-screen cell
     *  positions, anchored at (originX, originY) = top-left of the grid area. */
    private void layoutGroupCells(float originX, float originY) {
        int n = groupVisibleCount;
        int gap = dp(GROUP_GAP);
        if (n <= 0) return;
        if (n == 1) {
            groupRects[0].set(originX, originY, originX + dp(GROUP_SINGLE_W), originY + dp(GROUP_SINGLE_H));
        } else if (n == 2) {
            int c = dp(GROUP_PAIR_CELL);
            groupRects[0].set(originX, originY, originX + c, originY + c);
            groupRects[1].set(originX + c + gap, originY, originX + c + gap + c, originY + c);
        } else if (n == 3) {
            int tw = dp(GROUP_THREE_TOP_W), th = dp(GROUP_THREE_TOP_H), bc = dp(GROUP_THREE_BOT);
            groupRects[0].set(originX, originY, originX + tw, originY + th);
            float rowTop = originY + th + gap;
            groupRects[1].set(originX, rowTop, originX + bc, rowTop + bc);
            groupRects[2].set(originX + bc + gap, rowTop, originX + bc + gap + bc, rowTop + bc);
        } else if (n == 4) {
            int c = dp(GROUP_GRID2_CELL);
            for (int i = 0; i < 4; i++) {
                int row = i / 2, col = i % 2;
                float left = originX + col * (c + gap);
                float top  = originY + row * (c + gap);
                groupRects[i].set(left, top, left + c, top + c);
            }
        } else {
            int c = dp(GROUP_GRID3_CELL);
            for (int i = 0; i < n; i++) {
                int row = i / 3, col = i % 3;
                float left = originX + col * (c + gap);
                float top  = originY + row * (c + gap);
                groupRects[i].set(left, top, left + c, top + c);
            }
        }
    }

    private static int maxLineWidth(StaticLayout layout) {
        int w = 0;
        for (int i = 0; i < layout.getLineCount(); i++) {
            w = Math.max(w, (int) Math.ceil(layout.getLineWidth(i)));
        }
        return w;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bubbleDrawable == null) return;
        if (!isMedia && !isMediaGroup && textLayout == null) return;

        bubbleDrawable.setBounds(
                (int) bubbleRect.left, (int) bubbleRect.top,
                (int) bubbleRect.right, (int) bubbleRect.bottom);
        bubbleDrawable.draw(canvas);

        if (isPinned) {
            drawPinnedLabel(canvas);
        }

        if (hasGroupSender) {
            drawGroupSenderName(canvas);
        }

        if (hasForwarded) {
            drawForwardedLabel(canvas);
        }

        int hPad = Math.round(H_PADDING_DP * density);
        int vPad = Math.round(V_PADDING_DP * density);

        if (hasReply) {
            drawReplyPreview(canvas);
        }

        if (isMediaGroup) {
            drawMediaGroup(canvas);
        } else if (isMedia) {
            drawMedia(canvas, hPad, vPad);
        } else {
            int replyGap = hasReply ? Math.round(REPLY_GAP_TO_MESSAGE_DP * density) : 0;
            canvas.save();
            canvas.translate(bubbleLeft + hPad, bubbleTop + replyBoxHeight + replyGap + vPad);
            textLayout.draw(canvas);
            canvas.restore();

            drawFooter(canvas, bubbleRect.bottom - vPad * 0.4f, bubbleRect.right - hPad);
        }

        if (hasReactions) {
            drawReactionsBadge(canvas);
        }
    }

    private void drawReactionsBadge(Canvas canvas) {
        float baselineY = reactionsRect.bottom - reactionsTextPaint.getFontMetrics().descent;
        canvas.drawText(reactionsText, reactionsRect.left, baselineY, reactionsTextPaint);
    }

    private void drawPinnedLabel(Canvas canvas) {
        // Sits in the [0, bubbleTop) strip reserved above the bubble in
        // onMeasure, right-aligned to the bubble's own right edge (works
        // for sent AND received, since each bubble's own right edge is
        // used rather than the parent row's right edge).
        canvas.drawText(PINNED_LABEL_TEXT, bubbleRect.right - pinnedLabelWidth, pinnedBaselineY, pinnedLabelPaint);
    }

    private void drawGroupSenderName(Canvas canvas) {
        // Same reserved row as the pinned label, but left-aligned to the
        // bubble's own left edge (opposite corner) — received bubbles
        // start at bubbleLeft=0, so this sits at the row's start, matching
        // tv_sender_name's constraintStart_toEndOf the (unused) avatar.
        // Also used for the 📢 broadcast badge — the caller composes
        // whichever string applies (see setGroupSender()'s doc).
        canvas.drawText(groupSenderName, bubbleRect.left, groupSenderBaselineY, groupSenderPaint);
    }

    private void drawForwardedLabel(Canvas canvas) {
        // Own row directly below row 1 (pinned/group-sender), left-aligned
        // to the bubble's own left edge — mirrors tv_forwarded's
        // constraintTop_toBottomOf tv_sender_name + constraintStart_toEndOf
        // the (unused) avatar in item_message_received.xml.
        canvas.drawText(forwardedText, bubbleRect.left, forwardedBaselineY, forwardedPaint);
    }

    private void drawMedia(Canvas canvas, int hPad, int vPad) {
        float r = MEDIA_CORNER_RADIUS_DP * density;
        if (mediaBitmap != null) {
            // Rounded-corner centerCrop: scale a BitmapShader so the source
            // bitmap fills mediaRect exactly (matching ImageView's
            // centerCrop), then clip to a round rect with drawRoundRect —
            // avoids clipPath (which can force a software layer on some
            // Android versions) while still giving true rounded corners.
            float scale = Math.max(mediaRect.width() / mediaBitmap.getWidth(),
                    mediaRect.height() / mediaBitmap.getHeight());
            float dx = mediaRect.left - (mediaBitmap.getWidth() * scale - mediaRect.width()) / 2f;
            float dy = mediaRect.top - (mediaBitmap.getHeight() * scale - mediaRect.height()) / 2f;
            mediaShaderMatrix.reset();
            mediaShaderMatrix.setScale(scale, scale);
            mediaShaderMatrix.postTranslate(dx, dy);

            android.graphics.BitmapShader shader = new android.graphics.BitmapShader(
                    mediaBitmap, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP);
            shader.setLocalMatrix(mediaShaderMatrix);
            mediaBitmapPaint.setShader(shader);
            canvas.drawRoundRect(mediaRect, r, r, mediaBitmapPaint);
        } else {
            // Not decoded yet — plain placeholder box, same rounded shape.
            canvas.drawRoundRect(mediaRect, r, r, mediaPlaceholderPaint);
        }

        if (mediaGated) {
            // Manual-download gate covers the whole slot (idle pill or live
            // spinner/percentage) — same precedent as the group gate: while
            // it's up, the timestamp/tick pill below is skipped entirely
            // (nothing meaningful to show over an unfetched image yet).
            drawMediaDownloadGate(canvas);
            return;
        }

        if (mediaHasCaption && textLayout != null) {
            float captionTop = mediaRect.bottom + MEDIA_CAPTION_GAP_DP * density;
            canvas.save();
            canvas.translate(bubbleLeft + hPad, captionTop);
            textLayout.draw(canvas);
            canvas.restore();
            drawFooter(canvas, bubbleRect.bottom - vPad * 0.4f, bubbleRect.right - hPad);
        } else {
            // Captionless image: translucent timestamp/tick pill overlaid
            // on the image's bottom-right corner, WhatsApp-style.
            float rr = MEDIA_PILL_CORNER_DP * density;
            canvas.drawRoundRect(mediaPillRect, rr, rr, mediaPillBgPaint);
            float pillPadH = MEDIA_PILL_PADDING_H_DP * density;
            float textBaselineY = mediaPillRect.bottom - (mediaPillRect.height()
                    - (mediaPillTextPaint.descent() - mediaPillTextPaint.ascent())) / 2f
                    - mediaPillTextPaint.descent();
            float tickReserve = sent ? (TICK_SIZE_DP + TICK_GAP_DP) * density : 0;
            canvas.drawText(footerTimeText,
                    mediaPillRect.right - pillPadH - tickReserve - mediaPillTextPaint.measureText(footerTimeText),
                    textBaselineY, mediaPillTextPaint);
            if (sent) {
                // Reuse the same tick paint as the text-bubble footer, but
                // white (matches mediaPillTextPaint) so it reads on the pill.
                Paint saved = new Paint(tickPaint);
                tickPaint.setColor(MEDIA_PILL_TEXT);
                drawTick(canvas, mediaPillRect.right - pillPadH - TICK_SIZE_DP * density, textBaselineY);
                tickPaint.set(saved);
            }
        }
    }

    /**
     * Draws the single-media download gate: a dim scrim over mediaRect plus
     * a centered pill — idle "⬇ <label>" (tap to start) when !mediaDownloading,
     * or a live spinner/percentage ring while mediaDownloading is true. See
     * setMediaDownloadGate()/setMediaDownloadProgress().
     */
    private void drawMediaDownloadGate(Canvas canvas) {
        float r = MEDIA_CORNER_RADIUS_DP * density;
        canvas.drawRoundRect(mediaRect, r, r, mediaGateScrimPaint);

        String label = mediaDownloading
                ? (mediaDownloadProgress >= 0 ? mediaDownloadProgress + "%" : "")
                : (mediaDownloadLabel.isEmpty() ? "Photo" : mediaDownloadLabel);

        float iconSize = GROUP_GATE_PILL_ICON_DP * density;
        float iconGap = GROUP_GATE_PILL_ICON_GAP_DP * density;
        float padH = GROUP_GATE_PILL_PAD_H_DP * density;
        float padV = GROUP_GATE_PILL_PAD_V_DP * density;
        float textW = label.isEmpty() ? 0 : mediaGatePillTextPaint.measureText(label);
        float contentH = Math.max(iconSize, mediaGatePillTextPaint.descent() - mediaGatePillTextPaint.ascent());
        float pillW = padH * 2 + iconSize + (label.isEmpty() ? 0 : iconGap + textW);
        float pillH = padV * 2 + contentH;
        float cx = mediaRect.centerX(), cy = mediaRect.centerY();
        mediaGatePillRect.set(cx - pillW / 2f, cy - pillH / 2f, cx + pillW / 2f, cy + pillH / 2f);

        float pillR = GROUP_GATE_PILL_CORNER_DP * density;
        canvas.drawRoundRect(mediaGatePillRect, pillR, pillR, mediaGatePillBgPaint);

        float iconCx = mediaGatePillRect.left + padH + iconSize / 2f;
        float iconCy = mediaGatePillRect.centerY();

        if (mediaDownloading) {
            drawProgressRing(canvas, iconCx, iconCy, iconSize, mediaGatePillIconPaint, mediaDownloadProgress);
        } else {
            drawGateIcon(canvas, iconCx, iconCy, iconSize, mediaGatePillIconPaint);
        }

        if (!label.isEmpty()) {
            float textBaselineY = mediaGatePillRect.centerY()
                    - (mediaGatePillTextPaint.ascent() + mediaGatePillTextPaint.descent()) / 2f;
            canvas.drawText(label, iconCx + iconSize / 2f + iconGap, textBaselineY, mediaGatePillTextPaint);
        }
    }

    private void drawMediaGroup(Canvas canvas) {
        float cellR = GROUP_CORNER_R * density;
        for (int i = 0; i < groupVisibleCount; i++) {
            RectF rect = groupRects[i];
            boolean isLastOverlay = (i == groupVisibleCount - 1) && groupRemaining > 0;
            GridItem item = i < groupItems.size() ? groupItems.get(i) : null;
            Bitmap bmp = groupBitmaps[i];

            if (bmp != null) {
                float scale = Math.max(rect.width() / bmp.getWidth(), rect.height() / bmp.getHeight());
                float dx = rect.left - (bmp.getWidth() * scale - rect.width()) / 2f;
                float dy = rect.top - (bmp.getHeight() * scale - rect.height()) / 2f;
                groupShaderMatrix.reset();
                groupShaderMatrix.setScale(scale, scale);
                groupShaderMatrix.postTranslate(dx, dy);
                android.graphics.BitmapShader shader = new android.graphics.BitmapShader(
                        bmp, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP);
                shader.setLocalMatrix(groupShaderMatrix);
                groupBitmapPaint.setShader(shader);
                canvas.drawRoundRect(rect, cellR, cellR, groupBitmapPaint);
            } else {
                canvas.drawRoundRect(rect, cellR, cellR, groupCellBgPaint);
            }

            if (item != null && item.isVideo && !isLastOverlay) {
                float cx = rect.centerX(), cy = rect.centerY();
                float circleR = (GROUP_PLAY_CIRCLE_DP * density) / 2f;
                canvas.drawCircle(cx, cy, circleR, groupPlayCirclePaint);

                float triR = (GROUP_PLAY_TRIANGLE_DP * density) / 2f;
                groupPlayTrianglePath.reset();
                groupPlayTrianglePath.moveTo(cx - triR * 0.5f, cy - triR * 0.8f);
                groupPlayTrianglePath.lineTo(cx - triR * 0.5f, cy + triR * 0.8f);
                groupPlayTrianglePath.lineTo(cx + triR * 0.9f, cy);
                groupPlayTrianglePath.close();
                canvas.drawPath(groupPlayTrianglePath, groupPlayTrianglePaint);

                boolean hasItemCaption = item.caption != null && !item.caption.isEmpty();
                if (item.duration != null && !item.duration.isEmpty()) {
                    float durPadH = 3 * density, durPadV = 1 * density;
                    float textW = groupDurationTextPaint.measureText(item.duration);
                    float textH = groupDurationTextPaint.descent() - groupDurationTextPaint.ascent();
                    RectF durBg;
                    if (hasItemCaption) {
                        // Duration moves to the top-end corner so it doesn't
                        // collide with the caption strip pinned to the bottom
                        // (same conflict-avoidance MediaGroupLayoutHelper uses).
                        float right = rect.right - 4 * density;
                        float top = rect.top + 4 * density;
                        durBg = new RectF(right - textW - durPadH * 2, top, right, top + textH + durPadV * 2);
                    } else {
                        float left = rect.left + 4 * density;
                        float bottom = rect.bottom - 4 * density;
                        durBg = new RectF(left, bottom - textH - durPadV * 2, left + textW + durPadH * 2, bottom);
                    }
                    canvas.drawRoundRect(durBg, 3 * density, 3 * density, groupDurationBgPaint);
                    float textBaseline = hasItemCaption
                            ? durBg.top + durPadV - groupDurationTextPaint.ascent()
                            : durBg.bottom - durPadV - groupDurationTextPaint.descent();
                    canvas.drawText(item.duration, durBg.left + durPadH, textBaseline, groupDurationTextPaint);
                }
            }

            // Per-item caption: small gradient strip + single-line ellipsized
            // text pinned to this cell's bottom edge — mirrors
            // MediaGroupLayoutHelper's per-item caption exactly, just Canvas-
            // drawn. Skipped on the "+N" overflow cell (nothing legible fits
            // under the dark overlay+count already covering it).
            if (item != null && !isLastOverlay && item.caption != null && !item.caption.isEmpty()) {
                float stripH = GROUP_ITEM_CAPTION_STRIP_H_DP * density;
                float stripTop = rect.bottom - stripH;
                android.graphics.Shader grad = new android.graphics.LinearGradient(
                        0, stripTop, 0, rect.bottom, 0x00000000, 0x99000000, android.graphics.Shader.TileMode.CLAMP);
                groupItemCaptionScrimPaint.setShader(grad);
                canvas.drawRect(rect.left, stripTop, rect.right, rect.bottom, groupItemCaptionScrimPaint);

                float margin = GROUP_ITEM_CAPTION_MARGIN_DP * density;
                float maxTextW = rect.width() - margin * 2;
                CharSequence ellipsized = TextUtils.ellipsize(item.caption, groupItemCaptionPaint, maxTextW, TextUtils.TruncateAt.END);
                float baseline = rect.bottom - GROUP_ITEM_CAPTION_BOTTOM_DP * density - groupItemCaptionPaint.descent();
                canvas.drawText(ellipsized, 0, ellipsized.length(), rect.left + margin, baseline, groupItemCaptionPaint);
            }

            if (isLastOverlay) {
                canvas.drawRoundRect(rect, cellR, cellR, groupMoreOverlayPaint);
                canvas.drawText("+" + groupRemaining, rect.centerX(),
                        rect.centerY() - (groupMoreTextPaint.ascent() + groupMoreTextPaint.descent()) / 2f,
                        groupMoreTextPaint);
            }

            // Per-cell download badge — only drawn once the master gate
            // pill (below) has been dismissed; while the gate is up it
            // alone covers the whole grid, same as the old View-based
            // master overlay sitting on top of every per-cell overlay.
            if (!groupGateActive && !isLastOverlay
                    && i < groupCellPending.length && groupCellPending[i]) {
                canvas.drawRoundRect(rect, cellR, cellR, groupCellGateDimPaint);
                float cx = rect.centerX(), cy = rect.centerY();
                float badgeR = (GROUP_CELL_GATE_BADGE_DP * density) / 2f;
                canvas.drawCircle(cx, cy, badgeR, groupCellGateBadgeBgPaint);
                boolean downloading = i < groupCellDownloading.length && groupCellDownloading[i];
                if (downloading) {
                    int prog = i < groupCellProgress.length ? groupCellProgress[i] : -1;
                    drawProgressRing(canvas, cx, cy, GROUP_CELL_GATE_ICON_DP * density, groupCellGateIconPaint, prog);
                } else {
                    drawGateIcon(canvas, cx, cy, GROUP_CELL_GATE_ICON_DP * density, groupCellGateIconPaint);
                }
            }
        }

        if (groupGateActive) {
            // Master "Download N photos" pill — mirrors
            // MediaGroupLayoutHelper.addMasterDownloadOverlay(): a single
            // dim scrim over the whole grid with a centered pill, tap
            // anywhere in the grid to dismiss + start every pending cell.
            canvas.drawRect(groupContentRect, groupGateScrimPaint);

            String label = "Download " + groupGatePendingCount
                    + (groupGatePendingCount == 1 ? " photo" : " photos");
            float iconSize = GROUP_GATE_PILL_ICON_DP * density;
            float iconGap = GROUP_GATE_PILL_ICON_GAP_DP * density;
            float padH = GROUP_GATE_PILL_PAD_H_DP * density;
            float padV = GROUP_GATE_PILL_PAD_V_DP * density;
            float textW = groupGatePillTextPaint.measureText(label);
            float contentH = Math.max(iconSize, groupGatePillTextPaint.descent() - groupGatePillTextPaint.ascent());
            float pillW = padH * 2 + iconSize + iconGap + textW;
            float pillH = padV * 2 + contentH;
            float cx = groupContentRect.centerX(), cy = groupContentRect.centerY();
            groupGatePillRect.set(cx - pillW / 2f, cy - pillH / 2f, cx + pillW / 2f, cy + pillH / 2f);

            float pillR = GROUP_GATE_PILL_CORNER_DP * density;
            canvas.drawRoundRect(groupGatePillRect, pillR, pillR, groupGatePillBgPaint);

            float iconCx = groupGatePillRect.left + padH + iconSize / 2f;
            float iconCy = groupGatePillRect.centerY();
            drawGateIcon(canvas, iconCx, iconCy, iconSize, groupGatePillIconPaint);

            float textBaselineY = groupGatePillRect.centerY()
                    - (groupGatePillTextPaint.ascent() + groupGatePillTextPaint.descent()) / 2f;
            canvas.drawText(label, iconCx + iconSize / 2f + iconGap, textBaselineY, groupGatePillTextPaint);
        }

        if (groupHasCaption && groupCaptionLayout != null) {
            float scrimTop = groupContentRect.bottom - GROUP_CAPTION_SCRIM_H_DP * density;
            android.graphics.Shader grad = new android.graphics.LinearGradient(
                    0, scrimTop, 0, groupContentRect.bottom,
                    0x00000000, 0xAA000000, android.graphics.Shader.TileMode.CLAMP);
            groupScrimPaint.setShader(grad);
            canvas.drawRect(groupContentRect.left, scrimTop, groupContentRect.right, groupContentRect.bottom, groupScrimPaint);

            canvas.save();
            canvas.translate(groupContentRect.left + 4 * density,
                    groupContentRect.bottom - groupCaptionLayout.getHeight() - 4 * density);
            groupCaptionLayout.draw(canvas);
            canvas.restore();
        } else {
            // Captionless group: translucent timestamp/tick pill overlaid
            // on the grid's bottom-right corner — same treatment as the
            // single-image bubble's captionless pill.
            float rr = MEDIA_PILL_CORNER_DP * density;
            canvas.drawRoundRect(mediaPillRect, rr, rr, mediaPillBgPaint);
            float pillPadH = MEDIA_PILL_PADDING_H_DP * density;
            float textBaselineY = mediaPillRect.bottom - (mediaPillRect.height()
                    - (mediaPillTextPaint.descent() - mediaPillTextPaint.ascent())) / 2f
                    - mediaPillTextPaint.descent();
            float tickReserve = sent ? (TICK_SIZE_DP + TICK_GAP_DP) * density : 0;
            canvas.drawText(footerTimeText,
                    mediaPillRect.right - pillPadH - tickReserve - mediaPillTextPaint.measureText(footerTimeText),
                    textBaselineY, mediaPillTextPaint);
            if (sent) {
                drawTick(canvas, mediaPillRect.right - pillPadH - TICK_SIZE_DP * density, textBaselineY);
            }
        }
    }

    /** Extra width (text + trailing gap) the "⏳ mm:ss" countdown reserves in
     *  the footer row, ahead of the timestamp — 0 when there's no active expiry. */
    private float expiryReserveWidth() {
        return hasExpiry ? expiryPaint.measureText(expiryText) + FOOTER_GAP_DP * density : 0f;
    }

    private void drawFooter(Canvas canvas, float footerBaselineY, float footerRightX) {
        float timeX = footerRightX - footerPaint.measureText(footerTimeText)
                - (sent ? (TICK_SIZE_DP + TICK_GAP_DP) * density : 0);
        canvas.drawText(footerTimeText, timeX, footerBaselineY, footerPaint);

        if (hasExpiry) {
            canvas.drawText(expiryText, timeX - expiryReserveWidth(), footerBaselineY, expiryPaint);
        }

        if (sent) {
            drawTick(canvas, footerRightX - TICK_SIZE_DP * density, footerBaselineY);
        }
    }

    private void drawReplyPreview(Canvas canvas) {
        float r = REPLY_CORNER_RADIUS_DP * density;
        canvas.drawRoundRect(replyBoxRect, r, r, replyBgPaint);

        int replyBar = Math.round(REPLY_BAR_WIDTH_DP * density);
        canvas.drawRect(replyBoxRect.left, replyBoxRect.top,
                replyBoxRect.left + replyBar, replyBoxRect.bottom, replyBarPaint);

        int replyPadH = Math.round(REPLY_PADDING_H_DP * density);
        int replyPadV = Math.round(REPLY_PADDING_V_DP * density);
        float textColLeft = replyBoxRect.left + replyBar + replyPadH;
        float textColTop = replyBoxRect.top
                + (replyBoxRect.height() - (replySenderLayout.getHeight() + replyTextLayout.getHeight())) / 2f;

        canvas.save();
        canvas.translate(textColLeft, Math.max(textColTop, replyBoxRect.top + replyPadV));
        if (replySenderLayout != null) {
            replySenderLayout.draw(canvas);
            canvas.translate(0, replySenderLayout.getHeight());
        }
        if (replyTextLayout != null) {
            replyTextLayout.draw(canvas);
        }
        canvas.restore();

        if (replyThumb != null) {
            int thumbSize = Math.round(REPLY_THUMB_SIZE_DP * density);
            int thumbMargin = Math.round(REPLY_THUMB_MARGIN_DP * density);
            float thumbLeft = replyBoxRect.right - thumbMargin - thumbSize;
            float thumbTop = replyBoxRect.top + (replyBoxRect.height() - thumbSize) / 2f;
            replyThumbSrcRect.set(0, 0, replyThumb.getWidth(), replyThumb.getHeight());
            replyThumbDstRect.set(thumbLeft, thumbTop, thumbLeft + thumbSize, thumbTop + thumbSize);
            canvas.drawBitmap(replyThumb, replyThumbSrcRect, replyThumbDstRect, null);
        }
    }

    private final RectF gateIconArcRect = new RectF();

    /**
     * Draws the IDLE download-gate glyph at (cx, cy) sized to `size`: a
     * simple download arrow (vertical stroke + arrowhead + tray). Used for
     * the "tap to download" state, before anything is in flight. Once a
     * download starts, drawProgressRing() takes over instead (live
     * spinner/percentage) — see setMediaDownloadGate()/setGroupCellProgress().
     */
    private void drawGateIcon(Canvas canvas, float cx, float cy, float size, Paint paint) {
        float r = size / 2f;
        float shaftTop = cy - r;
        float shaftBottom = cy + r * 0.25f;
        canvas.drawLine(cx, shaftTop, cx, shaftBottom, paint);
        canvas.drawLine(cx - r * 0.5f, shaftBottom - r * 0.5f, cx, shaftBottom, paint);
        canvas.drawLine(cx + r * 0.5f, shaftBottom - r * 0.5f, cx, shaftBottom, paint);
        canvas.drawLine(cx - r, cy + r, cx + r, cy + r, paint);
    }

    /**
     * Live download-progress ring — replaces the old static partial-arc
     * "spinner" with one driven by real progress. percent >= 0 draws a
     * determinate clockwise arc from 12 o'clock (e.g. 42% ⇒ ~151° swept);
     * percent < 0 (no progress reported yet) draws a short arc that spins
     * continuously based on wall-clock time, requesting the next frame via
     * postInvalidateOnAnimation() — the standard technique for an
     * indeterminate spinner on a custom View without a Handler/ValueAnimator.
     */
    private void drawProgressRing(Canvas canvas, float cx, float cy, float size, Paint paint, int percent) {
        float r = size / 2f;
        gateIconArcRect.set(cx - r, cy - r, cx + r, cy + r);
        if (percent >= 0) {
            canvas.drawArc(gateIconArcRect, -90, 360f * (Math.min(percent, 100) / 100f), false, paint);
        } else {
            long now = android.os.SystemClock.uptimeMillis();
            float rotation = (now % INDETERMINATE_PERIOD_MS) / (float) INDETERMINATE_PERIOD_MS * 360f;
            canvas.drawArc(gateIconArcRect, rotation - 90, INDETERMINATE_SWEEP_DEG, false, paint);
            postInvalidateOnAnimation();
        }
    }

    private void drawTick(Canvas canvas, float x, float baselineY) {
        // Simple two-stroke check mark; double check mark for delivered/read.
        float size = TICK_SIZE_DP * density;
        float y = baselineY - size * 0.4f;
        tickPaint.setStyle(Paint.Style.STROKE);
        tickPaint.setStrokeWidth(density * 1.2f);
        tickPaint.setStrokeCap(Paint.Cap.ROUND);

        drawSingleTick(canvas, x, y, size);
        if (delivered || read) {
            drawSingleTick(canvas, x + size * 0.35f, y, size);
        }
    }

    private void drawSingleTick(Canvas canvas, float x, float y, float size) {
        canvas.drawLine(x, y + size * 0.5f, x + size * 0.35f, y + size * 0.8f, tickPaint);
        canvas.drawLine(x + size * 0.35f, y + size * 0.8f, x + size, y + size * 0.1f, tickPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // NOTE (known simplification): reply-box/image hit-tests intercept
        // ACTION_UP directly without feeding it back through gestureDetector,
        // so GestureDetector's internal down/up bookkeeping is slightly out
        // of sync after such a tap. Harmless in practice (each tap is a
        // fresh down/up pair) but worth revisiting if double-tap-to-zoom or
        // similar multi-event gestures are added to this view later.
        if (hasReply && event.getActionMasked() == MotionEvent.ACTION_UP
                && replyBoxRect.contains(event.getX(), event.getY())) {
            if (clickListener != null) clickListener.onReplyPreviewClick();
            return true;
        }
        if (hasReactions && event.getActionMasked() == MotionEvent.ACTION_UP
                && reactionsRect.contains(event.getX(), event.getY())) {
            if (clickListener != null) clickListener.onReactionsClick();
            return true;
        }
        if (isMedia && event.getActionMasked() == MotionEvent.ACTION_UP
                && mediaRect.contains(event.getX(), event.getY())) {
            if (mediaGated) {
                // Idle pill → start the download; already-in-flight → swallow
                // the tap (same "ignore while downloading" precedent the
                // group-cell gate uses).
                if (!mediaDownloading && clickListener != null) clickListener.onMediaDownloadClick();
            } else if (clickListener != null) {
                clickListener.onImageClick();
            }
            return true;
        }
        if (isMediaGroup && event.getActionMasked() == MotionEvent.ACTION_UP) {
            float x = event.getX(), y = event.getY();
            if (groupGateActive) {
                // Master pill swallows every tap in the grid area, exactly
                // like the old View-based overlay sitting on top of the
                // whole wrapper. Dismiss it locally right away (same
                // synchronous removeView() the old helper did) — the
                // adapter's onGroupDownloadAllClick() then kicks off each
                // pending cell's actual download.
                if (groupContentRect.contains(x, y)) {
                    groupGateActive = false;
                    invalidate();
                    if (clickListener != null) clickListener.onGroupDownloadAllClick();
                    return true;
                }
            } else {
                for (int i = 0; i < groupVisibleCount; i++) {
                    if (groupRects[i].contains(x, y)) {
                        boolean pending = i < groupCellPending.length && groupCellPending[i];
                        boolean downloading = i < groupCellDownloading.length && groupCellDownloading[i];
                        if (pending && !downloading) {
                            if (clickListener != null) clickListener.onGroupCellDownloadClick(i);
                        } else if (!pending) {
                            if (clickListener != null) clickListener.onMediaCellClick(i);
                        }
                        // pending && downloading → ignore tap, matches the
                        // old per-cell overlay disabling clicks in-flight.
                        return true;
                    }
                }
            }
        }
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event);
    }

    private float spToPx(float sp) {
        return sp * getContext().getResources().getDisplayMetrics().scaledDensity;
    }
}
