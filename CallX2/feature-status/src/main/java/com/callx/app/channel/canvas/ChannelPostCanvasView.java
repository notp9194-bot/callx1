package com.callx.app.channel.canvas;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.callx.app.models.ChannelPost;
import com.callx.app.status.R;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * ChannelPostCanvasView — single custom View per channel feed post.
 *
 * WHY THIS EXISTS
 * ───────────────
 * The previous ChannelPostAdapter inflated 10 separate XML layouts per post
 * type, each containing a ConstraintLayout/LinearLayout subtree with multiple
 * child Views. Every RecyclerView bind paid the full measure-and-layout pass
 * for that whole tree even when only one or two values changed. For a channel
 * feed that renders text, image, video, poll, audio, link, document, broadcast,
 * and event posts in the same list, that is a large and non-uniform measure
 * pass per scroll position.
 *
 * This view eliminates ALL of that:
 *   • One custom View, one measure pass, one draw pass.
 *   • No child Views — everything painted directly onto the Canvas.
 *   • StaticLayout built once in bind() (or lazily in onMeasure when the
 *     available width first becomes known), then cached and reused across
 *     every subsequent draw/scroll at zero cost.
 *   • Glide bitmaps delivered via setXxxBitmap(); the BitmapShader + Matrix
 *     for centerCrop are cached and only rebuilt when the bitmap reference
 *     or the target rect size actually changes — so a fling over 50 visible
 *     posts does not allocate 50 BitmapShader objects.
 *   • All Paint objects are final fields initialized once in the constructor
 *     — onDraw() never allocates.
 *   • LAYER_TYPE_HARDWARE: lets the GPU composite the layer, and ensures
 *     hardware-accelerated clipPath for the rounded media rect and avatar.
 *
 * ARCHITECTURE
 * ────────────
 * This class (the "host") owns:
 *   • ALL measured geometry (Rects, baseline y-offsets, section y-coords)
 *   • ALL Paint and TextPaint objects
 *   • ALL bind state copied from ChannelPost
 *   • onMeasure() dispatch to renderers
 *   • onDraw() dispatch to renderers
 *   • onTouchEvent() hit-testing dispatch to OnPostClickListener
 *
 * Each *Renderer class (ChannelPostHeaderRenderer, ChannelPostTextRenderer,
 * ChannelPostMediaRenderer, etc.) owns the drawing logic for exactly one
 * content section and holds only geometry/cache state for that section,
 * reading/writing the host's fields — exactly the same division as
 * CommunityPostCanvasView and its renderers in the community module.
 *
 * POST TYPES SUPPORTED
 * ────────────────────
 *   text, image, video, link, poll, audio, document, broadcast, event, deleted
 * All rendered by the same single View instance, dispatched via the `postType`
 * field. This means RecyclerView uses a single item type (TYPE_CANVAS), with
 * no type-switch on inflation — all recycled holders are interchangeable.
 */
public class ChannelPostCanvasView extends View {

    // ── Constants ─────────────────────────────────────────────────────────
    static final String PINNED_LABEL  = "📌 Pinned";
    static final String PERSON_GLYPH  = "\uD83D\uDC64";  // 👤
    static final String DELETED_TEXT  = "This post was deleted";

    // ── Dimensions (dp → px, set once in init()) ──────────────────────────
    float density;
    float cardPadding, cardCornerRadius;
    float avatarSize, avatarTextGap, nameTimestampGap, badgeMarginBottom, optionsButtonSize;
    float textMarginTop;
    float mediaMarginTop, mediaCornerRadius, playIconRadius, singleMediaHeight;
    float sectionGap;
    int   mentionColor;

    // Audio
    float audioBtnSize, audioRowGap, audioRowHeight, audioDurWidth;
    float waveBarStrokeWidth;

    // Doc
    float docIconSize, docIconGap, docIconCornerRadius, docTextLineGap;

    // Link preview
    float linkThumbHeight, linkCardCornerRadius, linkCardPadH, linkCardPadTop, linkCardPadBottom;
    float linkTitleDomainGap;

    // Poll
    float pollQuestionGap, pollOptionHeight, pollOptionGap, pollOptionTextPad, pollOptionCornerRadius;

    // Reactions/footer
    float reactionChipHeight, reactionChipPadH, reactionChipGap, reactionsMarginBottom, statsMarginBottom;
    float footerBtnSize, footerBtnGap;

    // Event
    float eventBannerHeight, eventContentPad, eventLineGap;
    float eventRsvpBtnHeight, eventRsvpBtnGap, eventCardCornerRadius;

    // ── Paints (all final, allocated ONCE in init()) ───────────────────────
    Paint cardBgPaint;
    TextPaint pinnedBadgePaint;
    TextPaint authorNamePaint, timestampPaint;
    TextPaint avatarGlyphPaint;
    Paint avatarPaint, avatarPlaceholderBgPaint;
    Paint optionsDotPaint;
    TextPaint postTextPaint;
    Paint mediaBitmapPaint, mediaPlaceholderPaint, mediaVideoScrimPaint, playCirclePaint, playTrianglePaint;
    TextPaint pollQuestionPaint, pollOptionPaint, pollPctPaint, pollVoteCountPaint, pollExpiryPaint, pollClosedPaint;
    Paint pollTrackPaint, pollTrackVotedPaint, pollFillPaint, pollFillLeadingPaint;
    Paint pollLeaderStrokePaint;
    Paint reactionChipBgPaint;
    TextPaint reactionChipTextPaint;
    Paint reactFilledPaint, reactOutlinePaint;
    Paint forwardIconPaint, replyIconPaint;
    TextPaint statsPaint;
    Paint audioBtnBgPaint, audioBtnIconPaint;
    TextPaint audioDurPaint;
    Paint waveBarPlayedPaint, waveBarUnplayedPaint;
    Paint docIconBgPaint;
    TextPaint docTypeLabelPaint, docNamePaint, docSizePaint;
    Paint linkCardBgPaint, linkCardStrokePaint, linkThumbPaint, linkThumbPlaceholderPaint;
    TextPaint linkDomainPaint, linkTitlePaint;
    TextPaint eventTitlePaint, eventMetaPaint;
    Paint eventBannerPaint, eventBannerPlaceholderPaint;
    Paint eventRsvpBtnBgPaint, eventRsvpBtnStrokePaint;
    TextPaint eventRsvpBtnTextPaint;
    TextPaint deletedTextPaint;
    TextPaint broadcastPriorityBadgePaint;

    // ── Shader / Path caches (rebuilt only when inputs change) ─────────────
    // Avatar
    BitmapShader avatarShaderCache;
    Bitmap       avatarShaderBitmap;
    float        avatarShaderRectW = -1f, avatarShaderRectH = -1f;
    final Matrix avatarShaderMatrix = new Matrix();

    // Single media
    BitmapShader mediaShaderCache;
    Bitmap       mediaShaderBitmap;
    float        mediaShaderRectW = -1f, mediaShaderRectH = -1f;
    final Matrix mediaShaderMatrix = new Matrix();
    final Path   mediaClipPath = new Path();
    float        mediaClipW = -1f, mediaClipH = -1f;

    // Play triangle
    final Path  playTrianglePath = new Path();
    float       playTriangleForRadius = -1f;

    // ── Geometry set in onMeasure, consumed by onDraw ──────────────────────
    final RectF avatarRect        = new RectF();
    final RectF optionsButtonRect = new RectF();
    final RectF mediaRect         = new RectF();

    // Section y-offsets
    float headerBottom, textTop, textHeight, mediaTop, mediaBottom;
    float linkTop, linkBottom, pollTop, pollBottom, audioTop, audioBottom;
    float docTop, docBottom, eventTop, eventBottom, broadcastTop, broadcastBottom;
    float footerTop, footerBottom;

    // ── Bind state (set from ChannelPost in bind()) ────────────────────────
    String postId;
    String postType;          // "text"|"image"|"video"|"link"|"poll"|"audio"|"document"|"broadcast"|"event"
    boolean isDeleted;

    // Header
    String authorName;
    String authorIconUrl;
    String timestampText;
    boolean isPinned;
    boolean canModify;

    // Text / caption
    String postText;

    // Media
    boolean mediaIsVideo;
    String  mediaUrl;
    String  thumbnailUrl;

    // Link preview
    String linkUrl, linkTitle, linkDescription, linkDomain, linkImageUrl;

    // Poll
    String       pollQuestion;
    List<String> pollOptions;
    Map<String, Long> pollVotes;
    int          myVotedOption    = -1;
    long         pollExpiresAt;
    boolean      pollMultiSelect;

    // Audio
    String audioUrl;
    String audioWaveformJson;
    String audioFormattedDuration;
    boolean audioIsPlaying;
    float   audioPlayedFraction;  // 0-1

    // Document
    String docName, docSizeText, docTypeLabel, docUrl;

    // Event
    String  eventTitle, eventLocation, eventImageUrl, eventDateText;
    boolean eventRsvpEnabled;

    // Broadcast
    String broadcastPriority;

    // Reactions & engagement
    Map<String, String> reactions;
    boolean myReacted;
    long viewCount;
    long forwardCount;
    int  replyCount;
    boolean allowReactions;
    boolean allowForward;

    // Bitmaps (set by adapter after Glide decode)
    @Nullable Bitmap authorAvatarBitmap;
    @Nullable Bitmap mediaBitmap;
    @Nullable Bitmap linkThumbBitmap;
    @Nullable Bitmap eventBannerBitmap;

    // ── Renderers ──────────────────────────────────────────────────────────
    private final ChannelPostHeaderRenderer  headerRenderer  = new ChannelPostHeaderRenderer(this);
    private final ChannelPostTextRenderer    textRenderer    = new ChannelPostTextRenderer(this);
    private final ChannelPostMediaRenderer   mediaRenderer   = new ChannelPostMediaRenderer(this);
    private final ChannelPostLinkRenderer    linkRenderer    = new ChannelPostLinkRenderer(this);
    private final ChannelPostPollRenderer    pollRenderer    = new ChannelPostPollRenderer(this);
    private final ChannelPostAudioRenderer   audioRenderer   = new ChannelPostAudioRenderer(this);
    private final ChannelPostDocRenderer     docRenderer     = new ChannelPostDocRenderer(this);
    private final ChannelPostFooterRenderer  footerRenderer  = new ChannelPostFooterRenderer(this);
    private final ChannelPostEventRenderer   eventRenderer   = new ChannelPostEventRenderer(this);

    // ── Dirty measure tracking ─────────────────────────────────────────────
    private String lastMeasuredPostId;
    private int    lastMeasuredW = -1;
    int            lastMeasuredH = -1; // package-private: read by adapter for height cache

    // Prewarmed StaticLayouts injected before bind() — null if not ready yet.
    private ChannelPostLayoutPrewarmer.PrewarmResult prewarmedLayouts;

    // Reused in onDraw() for canvas.quickReject() — zero allocation per frame.
    private final RectF qrRect = new RectF();

    // Reused broadcast badge paints — allocated once, never inside onDraw().
    private final Paint broadcastBadgeBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF broadcastBadgeRect    = new RectF();

    // ── Touch state ────────────────────────────────────────────────────────
    private float downX, downY;
    private long  downTime;
    private static final int TAP_SLOP_DP    = 8;
    private static final int LONG_PRESS_MS  = 420;
    private final Runnable longPressRunnable = this::handleLongPress;
    private boolean longPressFired;

    private OnPostClickListener listener;

    // ── Constructor ────────────────────────────────────────────────────────

    public ChannelPostCanvasView(Context context) {
        super(context);
        init(context);
        // PERF: hardware layer — GPU composite, hardware-accelerated clipPath for
        // avatar circle and media rounded corners; zero View overhead per scroll frame.
        setLayerType(LAYER_TYPE_HARDWARE, null);
        // PERF: software drawing cache is never consulted when LAYER_TYPE_HARDWARE
        // is active — disabling it explicitly saves the memory it would otherwise occupy.
        setWillNotCacheDrawing(true);
    }

    private void init(Context context) {
        density = context.getResources().getDisplayMetrics().density;

        // ── Dimensions ────────────────────────────────────────────────────
        cardPadding        = 14 * density;
        cardCornerRadius   = 14 * density;
        avatarSize         = 38 * density;
        avatarTextGap      = 10 * density;
        nameTimestampGap   = 2  * density;
        badgeMarginBottom  = 6  * density;
        optionsButtonSize  = 24 * density;
        textMarginTop      = 10 * density;
        mediaMarginTop     = 10 * density;
        mediaCornerRadius  = 12 * density;
        playIconRadius     = 28 * density;
        singleMediaHeight  = 220 * density;
        sectionGap         = 10 * density;

        audioBtnSize       = 40 * density;
        audioRowGap        = 8  * density;
        audioRowHeight     = 48 * density;
        audioDurWidth      = 40 * density;
        waveBarStrokeWidth = 2.5f * density;

        docIconSize          = 44 * density;
        docIconGap           = 12 * density;
        docIconCornerRadius  = 8  * density;
        docTextLineGap       = 3  * density;

        linkThumbHeight      = 130 * density;
        linkCardCornerRadius = 10 * density;
        linkCardPadH         = 12 * density;
        linkCardPadTop       = 8  * density;
        linkCardPadBottom    = 10 * density;
        linkTitleDomainGap   = 2  * density;

        pollQuestionGap      = 8  * density;
        pollOptionHeight     = 42 * density;
        pollOptionGap        = 6  * density;
        pollOptionTextPad    = 12 * density;
        pollOptionCornerRadius = 10 * density;

        reactionChipHeight   = 26 * density;
        reactionChipPadH     = 8  * density;
        reactionChipGap      = 5  * density;
        reactionsMarginBottom = 6 * density;
        statsMarginBottom    = 6  * density;
        footerBtnSize        = 32 * density;
        footerBtnGap         = 16 * density;

        eventBannerHeight    = 160 * density;
        eventContentPad      = 12 * density;
        eventLineGap         = 4  * density;
        eventRsvpBtnHeight   = 34 * density;
        eventRsvpBtnGap      = 8  * density;
        eventCardCornerRadius = 12 * density;

        // ── Colours ────────────────────────────────────────────────────────
        int textPrimary  = ContextCompat.getColor(context, R.color.text_primary);
        int textMuted    = ContextCompat.getColor(context, R.color.text_muted);
        int colorPrimary = ContextCompat.getColor(context, R.color.colorPrimary);
        int surfaceCard  = ContextCompat.getColor(context, R.color.surface_card);
        int surfaceInput = ContextCompat.getColor(context, R.color.surface_input);
        mentionColor = colorPrimary;

        // ── Paints ─────────────────────────────────────────────────────────
        cardBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        cardBgPaint.setColor(surfaceCard);

        pinnedBadgePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        pinnedBadgePaint.setTextSize(11 * density);
        pinnedBadgePaint.setColor(textMuted);

        authorNamePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        authorNamePaint.setTextSize(14 * density);
        authorNamePaint.setColor(textPrimary);
        authorNamePaint.setTypeface(Typeface.DEFAULT_BOLD);

        timestampPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        timestampPaint.setTextSize(11 * density);
        timestampPaint.setColor(textMuted);

        avatarGlyphPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        avatarGlyphPaint.setTextSize(18 * density);
        avatarGlyphPaint.setColor(Color.WHITE);
        avatarGlyphPaint.setTextAlign(Paint.Align.CENTER);

        avatarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        avatarPlaceholderBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        avatarPlaceholderBgPaint.setColor(withAlpha(colorPrimary, 80));

        optionsDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        optionsDotPaint.setColor(textMuted);

        postTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        postTextPaint.setTextSize(14 * density);
        postTextPaint.setColor(textPrimary);

        mediaBitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        mediaPlaceholderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mediaPlaceholderPaint.setColor(surfaceInput);
        mediaVideoScrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mediaVideoScrimPaint.setColor(Color.argb(60, 0, 0, 0));
        playCirclePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        playCirclePaint.setColor(Color.argb(217, 255, 255, 255));
        playTrianglePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        playTrianglePaint.setColor(Color.DKGRAY);

        pollQuestionPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        pollQuestionPaint.setTextSize(14 * density);
        pollQuestionPaint.setColor(textPrimary);
        pollQuestionPaint.setTypeface(Typeface.DEFAULT_BOLD);

        pollOptionPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        pollOptionPaint.setTextSize(13 * density);
        pollOptionPaint.setColor(textPrimary);

        pollPctPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        pollPctPaint.setTextSize(12 * density);
        pollPctPaint.setColor(textMuted);

        pollVoteCountPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        pollVoteCountPaint.setTextSize(12 * density);
        pollVoteCountPaint.setColor(textMuted);

        pollExpiryPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        pollExpiryPaint.setTextSize(12 * density);
        pollExpiryPaint.setColor(textMuted);

        pollClosedPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        pollClosedPaint.setTextSize(12 * density);
        pollClosedPaint.setColor(0xFFFF3B30);

        pollTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        pollTrackPaint.setColor(surfaceInput);

        pollTrackVotedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        pollTrackVotedPaint.setColor(withAlpha(colorPrimary, 30));

        pollFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        pollFillPaint.setColor(withAlpha(colorPrimary, 55));

        pollFillLeadingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        pollFillLeadingPaint.setColor(withAlpha(colorPrimary, 90));

        pollLeaderStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        pollLeaderStrokePaint.setStyle(Paint.Style.STROKE);
        pollLeaderStrokePaint.setStrokeWidth(1.5f * density);
        pollLeaderStrokePaint.setColor(colorPrimary);

        reactionChipBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        reactionChipBgPaint.setColor(surfaceInput);

        reactionChipTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        reactionChipTextPaint.setTextSize(12 * density);
        reactionChipTextPaint.setColor(textPrimary);

        reactFilledPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        reactFilledPaint.setColor(0xFFFF4081);

        reactOutlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        reactOutlinePaint.setStyle(Paint.Style.STROKE);
        reactOutlinePaint.setStrokeWidth(1.6f * density);
        reactOutlinePaint.setColor(textMuted);

        forwardIconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        forwardIconPaint.setStyle(Paint.Style.STROKE);
        forwardIconPaint.setStrokeWidth(1.8f * density);
        forwardIconPaint.setStrokeCap(Paint.Cap.ROUND);
        forwardIconPaint.setStrokeJoin(Paint.Join.ROUND);
        forwardIconPaint.setColor(textMuted);

        replyIconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        replyIconPaint.setStyle(Paint.Style.STROKE);
        replyIconPaint.setStrokeWidth(1.6f * density);
        replyIconPaint.setColor(textMuted);

        statsPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        statsPaint.setTextSize(12 * density);
        statsPaint.setColor(textMuted);

        audioBtnBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        audioBtnBgPaint.setColor(colorPrimary);

        audioBtnIconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        audioBtnIconPaint.setColor(Color.WHITE);

        audioDurPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        audioDurPaint.setTextSize(11 * density);
        audioDurPaint.setColor(textMuted);

        waveBarPlayedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        waveBarPlayedPaint.setColor(colorPrimary);
        waveBarPlayedPaint.setStrokeWidth(waveBarStrokeWidth);
        waveBarPlayedPaint.setStrokeCap(Paint.Cap.ROUND);

        waveBarUnplayedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        waveBarUnplayedPaint.setColor(withAlpha(colorPrimary, 90));
        waveBarUnplayedPaint.setStrokeWidth(waveBarStrokeWidth);
        waveBarUnplayedPaint.setStrokeCap(Paint.Cap.ROUND);

        docIconBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        docIconBgPaint.setColor(withAlpha(colorPrimary, 25));

        docTypeLabelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        docTypeLabelPaint.setTextSize(10 * density);
        docTypeLabelPaint.setColor(colorPrimary);
        docTypeLabelPaint.setTypeface(Typeface.DEFAULT_BOLD);
        docTypeLabelPaint.setTextAlign(Paint.Align.CENTER);

        docNamePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        docNamePaint.setTextSize(14 * density);
        docNamePaint.setColor(textPrimary);
        docNamePaint.setTypeface(Typeface.DEFAULT_BOLD);

        docSizePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        docSizePaint.setTextSize(12 * density);
        docSizePaint.setColor(textMuted);

        linkCardBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linkCardBgPaint.setColor(surfaceInput);

        linkCardStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linkCardStrokePaint.setStyle(Paint.Style.STROKE);
        linkCardStrokePaint.setStrokeWidth(1f * density);
        linkCardStrokePaint.setColor(withAlpha(colorPrimary, 50));

        linkThumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

        linkThumbPlaceholderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linkThumbPlaceholderPaint.setColor(0xFF2A2A2A);

        linkDomainPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        linkDomainPaint.setTextSize(10 * density);
        linkDomainPaint.setColor(colorPrimary);

        linkTitlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        linkTitlePaint.setTextSize(13 * density);
        linkTitlePaint.setColor(ContextCompat.getColor(context, R.color.text_primary));
        linkTitlePaint.setTypeface(Typeface.DEFAULT_BOLD);

        eventTitlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        eventTitlePaint.setTextSize(16 * density);
        eventTitlePaint.setColor(textPrimary);
        eventTitlePaint.setTypeface(Typeface.DEFAULT_BOLD);

        eventMetaPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        eventMetaPaint.setTextSize(13 * density);
        eventMetaPaint.setColor(textMuted);

        eventBannerPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

        eventBannerPlaceholderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        eventBannerPlaceholderPaint.setColor(withAlpha(colorPrimary, 40));

        eventRsvpBtnBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        eventRsvpBtnBgPaint.setColor(withAlpha(colorPrimary, 20));

        eventRsvpBtnStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        eventRsvpBtnStrokePaint.setStyle(Paint.Style.STROKE);
        eventRsvpBtnStrokePaint.setStrokeWidth(1.2f * density);
        eventRsvpBtnStrokePaint.setColor(colorPrimary);

        eventRsvpBtnTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        eventRsvpBtnTextPaint.setTextSize(12 * density);
        eventRsvpBtnTextPaint.setColor(colorPrimary);

        deletedTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        deletedTextPaint.setTextSize(13 * density);
        deletedTextPaint.setColor(withAlpha(textMuted, 200));
        deletedTextPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.ITALIC));

        broadcastPriorityBadgePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        broadcastPriorityBadgePaint.setTextSize(12 * density);
        broadcastPriorityBadgePaint.setColor(Color.WHITE);
        broadcastPriorityBadgePaint.setTypeface(Typeface.DEFAULT_BOLD);
    }

    // ── Static helpers ──────────────────────────────────────────────────────

    /** Returns avatar px size for Glide decode override — avoids decoding at full resolution. */
    public static int avatarPx(Context ctx) {
        return Math.round(38 * ctx.getResources().getDisplayMetrics().density);
    }

    /** Returns media height px for Glide decode override. */
    public static int mediaHeightPx(Context ctx) {
        return Math.round(220 * ctx.getResources().getDisplayMetrics().density);
    }

    static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    // ── Public bind API ─────────────────────────────────────────────────────

    /**
     * Main bind entry — called by ChannelPostAdapter.onBindViewHolder().
     * Copies all post data into host fields, resets bitmap state, and
     * requests a full re-layout. Never allocates — field assignments only.
     */
    public void bind(ChannelPost p, boolean isAdminOrOwner, String currentUid) {
        postId   = p.id;
        postType = p.isDeleted ? "deleted" : (p.type != null ? p.type : "text");
        isDeleted = p.isDeleted;

        // Header
        authorName    = p.authorName;
        authorIconUrl = p.authorIconUrl;
        isPinned      = p.isPinned;
        canModify     = isAdminOrOwner
                || (currentUid != null && currentUid.equals(p.authorUid));
        timestampText = formatTimestamp(p.timestamp);

        // Text / caption
        postText = p.text;

        // Media
        mediaUrl     = p.mediaUrl;
        thumbnailUrl = p.thumbnailUrl;
        mediaIsVideo = "video".equals(p.type);

        // Link
        linkUrl         = p.linkUrl;
        linkTitle       = p.linkTitle;
        linkDescription = p.linkDescription;
        linkDomain      = p.linkDomain != null ? p.linkDomain
                : extractDomain(p.linkUrl);
        linkImageUrl    = p.linkImageUrl;

        // Poll
        pollQuestion   = p.pollQuestion;
        pollOptions    = p.pollOptions;
        pollVotes      = p.pollVotes;
        pollExpiresAt  = p.pollExpiresAt;
        pollMultiSelect = p.pollMultiSelect;
        myVotedOption  = resolveMyVote(p.pollVotes, currentUid);

        // Audio
        audioUrl              = p.audioUrl;
        audioWaveformJson     = p.audioWaveformJson;
        audioFormattedDuration = p.getFormattedDuration();
        // audioIsPlaying stays as-is (toggled externally)

        // Document
        docName     = p.documentName;
        docSizeText = p.getFormattedDocumentSize();
        docTypeLabel = mimeToLabel(p.documentMimeType);
        docUrl      = p.documentUrl;

        // Event
        eventTitle      = p.eventTitle;
        eventLocation   = p.eventLocation;
        eventImageUrl   = p.eventImageUrl;
        eventRsvpEnabled = p.eventRsvpEnabled;
        eventDateText   = p.eventStartAt > 0
                ? new SimpleDateFormat("EEE, MMM d 'at' h:mm a", Locale.getDefault())
                        .format(new Date(p.eventStartAt))
                : null;

        // Broadcast
        broadcastPriority = p.broadcastPriority;

        // Reactions
        reactions    = p.reactions;
        myReacted    = p.getMyReaction(currentUid) != null;
        viewCount    = p.viewCount;
        forwardCount = p.forwardCount;
        replyCount   = p.replyCount;
        allowReactions = p.allowReactions;
        allowForward   = p.allowForward;

        // Reset bitmaps
        authorAvatarBitmap = null;
        avatarShaderBitmap = null;
        avatarShaderCache  = null;
        mediaBitmap        = null;
        mediaShaderBitmap  = null;
        mediaShaderCache   = null;
        linkThumbBitmap    = null;
        eventBannerBitmap  = null;

        requestLayout();
        invalidate();
    }

    /** Partial rebind — update only engagement counts (reactions, views, etc.)
     *  without resetting bitmaps.
     *  PERF: reactions/views never change the measured height — call only invalidate(),
     *  NOT requestLayout(). Skips the full onMeasure() + RecyclerView re-layout pass. */
    public void updateEngagement(ChannelPost p, String currentUid) {
        reactions    = p.reactions;
        myReacted    = p.getMyReaction(currentUid) != null;
        viewCount    = p.viewCount;
        forwardCount = p.forwardCount;
        replyCount   = p.replyCount;
        invalidate(); // no requestLayout() — height unchanged
    }

    /** Partial rebind — update poll vote state only.
     *  PERF: vote changes only alter fill-bar width inside fixed-height option tracks —
     *  no height change, so skip requestLayout(). */
    public void updatePollVotes(ChannelPost p, String currentUid) {
        pollVotes     = p.pollVotes;
        myVotedOption = resolveMyVote(p.pollVotes, currentUid);
        invalidate(); // no requestLayout() — height unchanged
    }

    /** Called by Glide when author avatar is decoded. Guard on postId prevents stale delivery. */
    public void setAuthorAvatarBitmap(String forPostId, @Nullable Bitmap bmp) {
        if (!Objects.equals(forPostId, postId)) return;
        authorAvatarBitmap = bmp;
        avatarShaderBitmap = null;
        avatarShaderCache  = null;
        // PERF: partial invalidate — only redraw the header row where the avatar lives.
        // A full invalidate() on a 220dp media card costs ~5× more pixels than this.
        if (headerBottom > 0) {
            invalidate(0, 0, getWidth(), (int)(headerBottom + 1f));
        } else {
            invalidate();
        }
    }

    /** Called by Glide when the post media (image or video thumbnail) is decoded. */
    public void setMediaBitmap(String forPostId, @Nullable Bitmap bmp) {
        if (!Objects.equals(forPostId, postId)) return;
        mediaBitmap       = bmp;
        mediaShaderBitmap = null;
        mediaShaderCache  = null;
        // PERF: partial invalidate — only the media rect, not header/text/footer.
        if (!mediaRect.isEmpty()) {
            invalidate((int) mediaRect.left,  (int) mediaRect.top,
                       (int) mediaRect.right, (int)(mediaRect.bottom + 1f));
        } else {
            invalidate();
        }
    }

    /** Called by Glide for link preview thumbnail. */
    public void setLinkThumbBitmap(String forPostId, @Nullable Bitmap bmp) {
        if (!Objects.equals(forPostId, postId)) return;
        linkThumbBitmap = bmp;
        // PERF: partial invalidate — only the link card region.
        if (linkBottom > linkTop && linkTop > 0) {
            invalidate(0, (int) linkTop, getWidth(), (int)(linkBottom + 1f));
        } else {
            invalidate();
        }
    }

    /** Called by Glide for event banner image. */
    public void setEventBannerBitmap(String forPostId, @Nullable Bitmap bmp) {
        if (!Objects.equals(forPostId, postId)) return;
        eventBannerBitmap = bmp;
        // PERF: partial invalidate — only the event card region.
        if (eventBottom > eventTop && eventTop > 0) {
            invalidate(0, (int) eventTop, getWidth(), (int)(eventBottom + 1f));
        } else {
            invalidate();
        }
    }

    /** Toggle audio playback indicator (called by adapter; triggers only invalidate, no layout). */
    public void setAudioPlaying(boolean playing, float playedFraction) {
        audioIsPlaying      = playing;
        audioPlayedFraction = playedFraction;
        invalidate();
    }

    public void setOnPostClickListener(OnPostClickListener l) { this.listener = l; }

    /**
     * Inject pre-built StaticLayouts from ChannelPostLayoutPrewarmer.
     * Call from the adapter BEFORE bind() so that bind()'s requestLayout() fires
     * onMeasure() with the layouts already cached — text shaping cost = 0.
     */
    public void acceptPrewarmed(ChannelPostLayoutPrewarmer.PrewarmResult result) {
        prewarmedLayouts = result;
        if (result != null) {
            // Push pre-built layouts into renderers right away.
            if (result.textLayout != null) {
                textRenderer.setPrebuiltLayout(result.textLayout);
            }
            if (result.linkTitleLayout != null) {
                linkRenderer.setPrebuiltTitleLayout(result.linkTitleLayout);
            }
        }
    }

    /** Returns the last height this view measured itself at. Used by height cache. */
    public int getLastMeasuredHeight() { return lastMeasuredH; }

    // ── Expose reaction counts as a compact map for FooterRenderer ──────────
    Map<String, Integer> getReactionCountsMap() {
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        if (reactions == null) return counts;
        for (String emoji : reactions.values()) {
            counts.put(emoji, counts.getOrDefault(emoji, 0) + 1);
        }
        return counts;
    }

    // ── onMeasure ─────────────────────────────────────────────────────────

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);

        // ── DIRTY CHECK: same post, same width → return cached height immediately.
        // This fires on engagement-only partial rebinds that still call requestLayout()
        // from other code paths, and on re-measure after the RV size is stable.
        // We intentionally do NOT apply this on the first measure after bind() because
        // bind() clears lastMeasuredPostId, forcing a full geometry recompute.
        if (w == lastMeasuredW && lastMeasuredH > 0
                && Objects.equals(postId, lastMeasuredPostId)) {
            setMeasuredDimension(w, lastMeasuredH);
            return;
        }

        float contentL = cardPadding;
        float contentR = w - cardPadding;
        float contentW = Math.max(1f, contentR - contentL);

        float y = cardPadding;

        // Header (always)
        headerBottom = headerRenderer.measure(y);
        y = headerBottom;

        if (isDeleted) {
            // Deleted: just a line of italic placeholder text
            y += textMarginTop;
            footerTop    = y + 24 * density;
            footerBottom = footerTop;
            y = footerBottom + cardPadding;
            setMeasuredDimension(w, (int) y);
            return;
        }

        // Broadcast priority badge row
        if ("broadcast".equals(postType)) {
            y += sectionGap;
            broadcastTop = y;
            Paint.FontMetrics bfm = broadcastPriorityBadgePaint.getFontMetrics();
            broadcastBottom = y + (bfm.descent - bfm.ascent) + sectionGap;
            y = broadcastBottom;
        }

        // Text / caption
        if (postText != null && !postText.isEmpty()) {
            y += textMarginTop;
            textTop    = y;
            textHeight = textRenderer.measure((int) contentW);
            y += textHeight;
        } else {
            textTop    = y;
            textHeight = 0;
        }

        // Type-specific content
        switch (postType != null ? postType : "text") {
            case "image":
            case "video": {
                y += mediaMarginTop;
                mediaTop = y;
                float aspect = (mediaBitmap != null && mediaBitmap.getWidth() > 0)
                        ? mediaBitmap.getWidth() / (float) mediaBitmap.getHeight()
                        : 16f / 9f;
                float mediaH = Math.min(singleMediaHeight, contentW / aspect);
                mediaH = Math.max(120 * density, mediaH);
                mediaRect.set(contentL, y, contentR, y + mediaH);
                mediaBottom = y + mediaH;
                y = mediaBottom;
                break;
            }
            case "link": {
                y += sectionGap;
                linkTop    = y;
                linkBottom = linkRenderer.layout(contentL, y, contentR);
                y = linkBottom;
                break;
            }
            case "poll": {
                y += sectionGap;
                pollTop    = y;
                int totalVotes = pollVotes != null ? pollVotes.size() : 0;
                pollBottom = pollRenderer.layout(
                        contentL, y, contentR,
                        pollOptions, myVotedOption,
                        totalVotes, pollExpiresAt, pollMultiSelect);
                y = pollBottom;
                break;
            }
            case "audio": {
                y += sectionGap;
                audioTop    = y;
                audioBottom = audioRenderer.layout(contentL, y, contentR);
                y = audioBottom;
                break;
            }
            case "document": {
                y += sectionGap;
                docTop    = y;
                docBottom = docRenderer.layout(contentL, y, contentR);
                y = docBottom;
                break;
            }
            case "event": {
                y += sectionGap;
                eventTop    = y;
                eventBottom = eventRenderer.layout(contentL, y, contentR);
                y = eventBottom;
                break;
            }
            default:
                break;
        }

        // Footer (always, except deleted)
        y += sectionGap;
        footerTop    = y;
        footerBottom = footerRenderer.layout(contentL, y, contentR);
        y = footerBottom + cardPadding;

        int totalH = (int) y;
        // Cache so dirty-check can short-circuit future re-measures.
        lastMeasuredPostId = postId;
        lastMeasuredW      = w;
        lastMeasuredH      = totalH;
        setMeasuredDimension(w, totalH);
    }

    // ── onDraw ────────────────────────────────────────────────────────────

    @Override
    protected void onDraw(android.graphics.Canvas canvas) {
        super.onDraw(canvas);

        // Card background — single drawRoundRect, no clipPath on the outer card.
        canvas.drawRoundRect(0, 0, getWidth(), getHeight(),
                cardCornerRadius, cardCornerRadius, cardBgPaint);

        // Header — always visible at the top of the card, never skip.
        headerRenderer.draw(canvas);

        if (isDeleted) {
            float ty = footerTop - 24 * density;
            canvas.drawText(DELETED_TEXT, cardPadding, ty, deletedTextPaint);
            return;
        }

        // ── canvas.quickReject() per section ───────────────────────────────
        // Hardware-accelerated: the GPU driver short-circuits these checks with
        // a single scissor-rect compare. Sections fully outside the clip rect
        // (common during fast flings where only 1-2 sections are on-screen) are
        // skipped entirely — no draw calls, no GPU state changes.

        // Broadcast badge
        if ("broadcast".equals(postType) && broadcastTop > 0) {
            qrRect.set(0, broadcastTop, getWidth(), broadcastBottom);
            if (!canvas.quickReject(qrRect, android.graphics.Canvas.EdgeType.BW)) {
                drawBroadcastBadge(canvas);
            }
        }

        // Text / caption
        if (postText != null && !postText.isEmpty()) {
            qrRect.set(0, textTop, getWidth(), textTop + textHeight);
            if (!canvas.quickReject(qrRect, android.graphics.Canvas.EdgeType.BW)) {
                textRenderer.draw(canvas, textTop, cardPadding);
            }
        }

        // Type-specific content
        switch (postType != null ? postType : "text") {
            case "image":
            case "video":
                qrRect.set(mediaRect);
                if (!canvas.quickReject(qrRect, android.graphics.Canvas.EdgeType.BW)) {
                    mediaRenderer.draw(canvas);
                }
                break;
            case "link":
                qrRect.set(0, linkTop, getWidth(), linkBottom);
                if (!canvas.quickReject(qrRect, android.graphics.Canvas.EdgeType.BW)) {
                    linkRenderer.draw(canvas);
                }
                break;
            case "poll":
                qrRect.set(0, pollTop, getWidth(), pollBottom);
                if (!canvas.quickReject(qrRect, android.graphics.Canvas.EdgeType.BW)) {
                    pollRenderer.draw(canvas, myVotedOption);
                }
                break;
            case "audio":
                qrRect.set(0, audioTop, getWidth(), audioBottom);
                if (!canvas.quickReject(qrRect, android.graphics.Canvas.EdgeType.BW)) {
                    audioRenderer.draw(canvas);
                }
                break;
            case "document":
                qrRect.set(0, docTop, getWidth(), docBottom);
                if (!canvas.quickReject(qrRect, android.graphics.Canvas.EdgeType.BW)) {
                    docRenderer.draw(canvas);
                }
                break;
            case "event":
                qrRect.set(0, eventTop, getWidth(), eventBottom);
                if (!canvas.quickReject(qrRect, android.graphics.Canvas.EdgeType.BW)) {
                    eventRenderer.draw(canvas);
                }
                break;
        }

        // Footer
        qrRect.set(0, footerTop, getWidth(), footerBottom);
        if (!canvas.quickReject(qrRect, android.graphics.Canvas.EdgeType.BW)) {
            footerRenderer.draw(canvas);
        }
    }

    private void drawBroadcastBadge(android.graphics.Canvas canvas) {
        // PERF: broadcastBadgeBgPaint and broadcastBadgeRect are reusable fields
        // (allocated in field init) — no per-draw allocation.
        String label;
        int bgColor;
        switch (broadcastPriority != null ? broadcastPriority : "normal") {
            case "urgent":    label = "🚨 URGENT";    bgColor = 0xFFFF3B30; break;
            case "important": label = "⚠️ IMPORTANT"; bgColor = 0xFFFF9500; break;
            default:          label = "📢 Broadcast"; bgColor = 0xFF25D366; break;
        }
        float pad = 8 * density;
        float textW = broadcastPriorityBadgePaint.measureText(label);
        float bW = textW + pad * 2f;
        float bH = broadcastBottom - broadcastTop;
        broadcastBadgeRect.set(cardPadding, broadcastTop, cardPadding + bW, broadcastTop + bH);
        broadcastBadgeBgPaint.setColor(bgColor);
        canvas.drawRoundRect(broadcastBadgeRect, 6 * density, 6 * density, broadcastBadgeBgPaint);
        float baselineY = broadcastTop + bH / 2f
                - (broadcastPriorityBadgePaint.ascent()
                   + broadcastPriorityBadgePaint.descent()) / 2f;
        canvas.drawText(label, cardPadding + pad, baselineY, broadcastPriorityBadgePaint);
    }

    // ── onTouchEvent ──────────────────────────────────────────────────────

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                downTime = System.currentTimeMillis();
                longPressFired = false;
                postDelayed(longPressRunnable, LONG_PRESS_MS);
                return true;
            case MotionEvent.ACTION_MOVE: {
                float slop = TAP_SLOP_DP * density;
                if (Math.abs(event.getX() - downX) > slop
                        || Math.abs(event.getY() - downY) > slop) {
                    removeCallbacks(longPressRunnable);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
                removeCallbacks(longPressRunnable);
                if (!longPressFired) handleTap(event.getX(), event.getY());
                return true;
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPressRunnable);
                return true;
        }
        return super.onTouchEvent(event);
    }

    private void handleLongPress() {
        longPressFired = true;
        if (listener != null) listener.onPostLongClick();
    }

    private void handleTap(float x, float y) {
        if (listener == null) return;

        // Options button
        if (canModify && !optionsButtonRect.isEmpty() && optionsButtonRect.contains(x, y)) {
            listener.onOptionsClick(); return;
        }
        // Author avatar
        if (avatarRect.contains(x, y)) {
            listener.onAuthorClick(); return;
        }
        // Media tap
        if (("image".equals(postType) || "video".equals(postType))
                && mediaRect.contains(x, y)) {
            listener.onMediaClick(); return;
        }
        // Poll option
        if ("poll".equals(postType)) {
            int opt = pollRenderer.hitTestOption(x, y);
            if (opt >= 0) { listener.onPollOptionClick(opt); return; }
        }
        // Event RSVP
        if ("event".equals(postType)) {
            String rsvp = eventRenderer.hitTestRsvp(x, y);
            if (rsvp != null) { listener.onRsvpClick(rsvp); return; }
        }
        // Link tap
        if ("link".equals(postType) && linkUrl != null) {
            listener.onLinkClick(linkUrl); return;
        }
        // Text mention
        if (postText != null && !postText.isEmpty()
                && y >= textTop && y <= textTop + textHeight) {
            String mention = textRenderer.mentionAt(x - cardPadding, y - textTop);
            if (mention != null) { listener.onMentionClick(mention); return; }
        }
        // Footer hit-test
        int region = footerRenderer.hitTest(x, y);
        switch (region) {
            case ChannelPostFooterRenderer.REGION_REACT:
                listener.onReactClick(); return;
            case ChannelPostFooterRenderer.REGION_FORWARD:
                listener.onForwardClick(); return;
            case ChannelPostFooterRenderer.REGION_REPLY:
                listener.onReplyClick(); return;
            case ChannelPostFooterRenderer.REGION_REACTIONS_DETAIL:
                listener.onReactionsClick(); return;
        }

        listener.onPostClick();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(longPressRunnable);
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static String formatTimestamp(long ts) {
        if (ts <= 0) return "";
        return android.text.format.DateUtils.getRelativeTimeSpanString(
                ts, System.currentTimeMillis(),
                android.text.format.DateUtils.MINUTE_IN_MILLIS).toString();
    }

    private static int resolveMyVote(Map<String, Long> votes, String uid) {
        if (votes == null || uid == null) return -1;
        Long v = votes.get(uid);
        return v != null ? v.intValue() : -1;
    }

    private static String extractDomain(String url) {
        if (url == null || url.isEmpty()) return "";
        try { return new java.net.URL(url).getHost().replaceAll("^www\\.", ""); }
        catch (Exception e) { return ""; }
    }

    private static String mimeToLabel(String mime) {
        if (mime == null)                  return "FILE";
        if (mime.contains("pdf"))          return "PDF";
        if (mime.contains("word"))         return "DOC";
        if (mime.contains("sheet"))        return "XLS";
        if (mime.contains("presentation")) return "PPT";
        if (mime.contains("zip"))          return "ZIP";
        if (mime.contains("text"))         return "TXT";
        return "FILE";
    }
}
