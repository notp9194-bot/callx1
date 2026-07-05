package com.callx.app.conversation.canvas;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
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
 * SCOPE OF THIS FILE (deliberately limited)
 * ──────────────────────────────────────────
 * This view handles:
 *   • plain-text bubbles — sender's own text + timestamp + read/delivered tick
 *   • the reply-preview strip (quoted sender name + quoted text + optional
 *     thumbnail, matching ll_reply_preview in item_message_sent/received.xml)
 *   • a single fixed-size (180dp square) IMAGE bubble with optional caption,
 *     matching iv_image in item_message_sent/received.xml — rounded corners,
 *     centerCrop-equivalent scaling, optional caption text below the image,
 *     and (for captionless images) a translucent timestamp/tick pill
 *     overlaid on the image itself, WhatsApp-style.
 *
 * It intentionally does NOT (yet) handle:
 *   • multi-image grids (ll_media_group), video, GIF, audio, file, poll,
 *     link-preview, or reel-share bubbles
 *   • the download-progress overlay (pill/spinner/percentage) — the image
 *     slot shows a plain placeholder box until a decoded Bitmap is supplied
 *   • forwarded/pinned labels, reactions strip
 *   • swipe-to-reply gesture, long-press action menu
 *
 * Those all still render through the existing item_message_sent/received.xml
 * + MessagePagingAdapter path. Converting them is real, additional work —
 * each one needs its own hit-testing and its own Canvas drawing routine, and
 * getting media bubbles wrong is much easier to do than getting plain
 * text wrong (multi-touch targets, RTL runs, emoji spans, image aspect
 * ratios). Doing all of it in one pass and calling it "done" would trade a
 * working, heavily-tuned app for a broken one.
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

    public interface OnBubbleClickListener {
        void onBubbleClick();
        void onBubbleLongClick();
        /** Returns true if a link at (x,y) was hit and handled — caller should not treat as a normal click. */
        boolean onLinkClick(String url);
        /** Tapped the reply-preview strip — caller should scroll/jump to the quoted message. */
        void onReplyPreviewClick();
        /** Tapped the image itself (media bubbles only) — caller should open the full-screen media viewer. */
        void onImageClick();
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
        this.mediaBitmap = bitmap;
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

        } else {
            // ── Plain text bubble ──
            footerReserveWidth = footerPaint.measureText(footerTimeText)
                    + (sent ? (TICK_SIZE_DP + TICK_GAP_DP) * density : 0)
                    + FOOTER_GAP_DP * density;

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
        bubbleTop = 0;
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

        setMeasuredDimension(parentWidth, bubbleHeight);
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
        if (!isMedia && textLayout == null) return;

        bubbleDrawable.setBounds(
                (int) bubbleRect.left, (int) bubbleRect.top,
                (int) bubbleRect.right, (int) bubbleRect.bottom);
        bubbleDrawable.draw(canvas);

        int hPad = Math.round(H_PADDING_DP * density);
        int vPad = Math.round(V_PADDING_DP * density);

        if (hasReply) {
            drawReplyPreview(canvas);
        }

        if (isMedia) {
            drawMedia(canvas, hPad, vPad);
        } else {
            int replyGap = hasReply ? Math.round(REPLY_GAP_TO_MESSAGE_DP * density) : 0;
            canvas.save();
            canvas.translate(bubbleLeft + hPad, bubbleTop + replyBoxHeight + replyGap + vPad);
            textLayout.draw(canvas);
            canvas.restore();

            drawFooter(canvas, bubbleRect.bottom - vPad * 0.4f, bubbleRect.right - hPad);
        }
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

    private void drawFooter(Canvas canvas, float footerBaselineY, float footerRightX) {
        canvas.drawText(footerTimeText,
                footerRightX - footerPaint.measureText(footerTimeText)
                        - (sent ? (TICK_SIZE_DP + TICK_GAP_DP) * density : 0),
                footerBaselineY, footerPaint);

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
        if (isMedia && event.getActionMasked() == MotionEvent.ACTION_UP
                && mediaRect.contains(event.getX(), event.getY())) {
            if (clickListener != null) clickListener.onImageClick();
            return true;
        }
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event);
    }

    private float spToPx(float sp) {
        return sp * getContext().getResources().getDisplayMetrics().scaledDensity;
    }
}
