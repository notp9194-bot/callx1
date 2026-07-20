package com.callx.app.community.canvas;

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

import com.callx.app.chat.R;
import com.callx.app.community.CommunityPoll;
import com.callx.app.community.CommunityReaction;
import com.callx.app.db.entity.CommunityPostEntity;

import java.util.List;
import java.util.Map;

/**
 * CommunityPostCanvasView — one custom View per community feed post, mirroring
 * the chat module's MessageBubbleCanvasView + *Renderer split:
 *   • this host owns all measured geometry (Rects), Paint objects, and bind
 *     state as package-private fields, plus onMeasure/onDraw dispatch and
 *     touch hit-testing.
 *   • each *Renderer class owns the actual drawing (and, where relevant,
 *     sub-region hit-testing like "which poll option" / "which grid cell")
 *     for exactly one post-content-type, reading/writing only this host's
 *     fields — same relationship as ContactRenderer/MediaRenderer have to
 *     MessageBubbleCanvasView.
 *
 * Replaces item_community_post.xml's CardView+LinearLayout tree the same way
 * MessageBubbleCanvasView replaced item_message_sent/received.xml: no
 * inflate, no child-view measure/layout pass, everything painted directly.
 */
public class CommunityPostCanvasView extends View {

    static final String PINNED_LABEL_TEXT = "\uD83D\uDCCC Pinned";
    static final String ANNOUNCEMENT_LABEL_TEXT = "\uD83D\uDCE2 Announcement";
    static final String PERSON_GLYPH = "\uD83D\uDC64";

    // ── Dimensions (px, derived from density in the constructor) ──
    float density;
    float cardPadding, cardCornerRadius;
    float avatarSize, avatarTextGap, nameTimestampGap, badgeMarginBottom, optionsButtonSize;
    float textMarginTop;
    float mediaMarginTop, mediaCornerRadius, playIconRadius, singleMediaHeight;
    float mediaGroupGap, mediaGroupHeight;
    float pollMarginTop, pollQuestionGap, pollOptionHeight, pollOptionGap, pollOptionTextPadding;
    float reactionsMarginTop, reactionChipHeight, reactionChipPaddingH, reactionChipGap, reactionChipRowGap;
    float engagementMarginTop, engagementIconSize, engagementIconGap, engagementGroupGap;
    int mentionColor;

    // ── Paints (allocated once in the constructor, never per-draw) ──
    Paint cardBgPaint;
    TextPaint pinnedBadgePaint, announcementBadgePaint;
    TextPaint authorNamePaint, timestampPaint, avatarGlyphPaint;
    Paint avatarPaint, avatarPlaceholderBgPaint;
    Paint optionsDotPaint;
    TextPaint postTextPaint;
    Paint mediaBitmapPaint, mediaPlaceholderPaint, mediaVideoScrimPaint, playCirclePaint, playTrianglePaint;
    Paint mediaOverflowScrimPaint;
    TextPaint mediaOverflowTextPaint;
    TextPaint pollQuestionPaint, pollOptionPaint, pollPercentPaint;
    Paint pollTrackPaint, pollFillPaint, pollFillSelectedPaint, pollSelectedBorderPaint;
    Paint reactionChipBgPaint;
    TextPaint reactionChipTextPaint;
    Paint likeFilledPaint, likeOutlinePaint, commentGlyphPaint, shareGlyphPaint, bookmarkGlyphPaint;
    TextPaint engagementCountPaint;

    Matrix avatarShaderMatrix = new Matrix();
    Matrix mediaShaderMatrix = new Matrix();

    // ── PERF: reusable shader/path caches, rebuilt only when their inputs
    // actually change instead of being allocated fresh on every onDraw().
    // A community feed can render 50-100+ of these views per scroll fling;
    // allocating a BitmapShader/Path per frame per view was the single
    // biggest GC-pressure source during scroll (the shader/path `new` calls
    // previously lived inside every renderer's draw()).
    BitmapShader avatarShaderCache;
    Bitmap avatarShaderBitmap;
    float avatarShaderRectW = -1f, avatarShaderRectH = -1f;

    BitmapShader mediaShaderCache;
    Bitmap mediaShaderBitmap;
    float mediaShaderRectW = -1f, mediaShaderRectH = -1f;

    final BitmapShader[] mediaGroupShaderCache = new BitmapShader[4];
    final Bitmap[] mediaGroupShaderBitmap = new Bitmap[4];
    final float[] mediaGroupShaderW = new float[4];
    final float[] mediaGroupShaderH = new float[4];

    final Path playTrianglePath = new Path();
    float playTriangleForRadius = -1f;
    final Path[] mediaGroupTrianglePath = {new Path(), new Path(), new Path(), new Path()};
    final float[] mediaGroupTriangleForRadius = {-1f, -1f, -1f, -1f};

    // Media clip paths — rebuilt only on size change (avoids per-frame Path allocation).
    final Path mediaClipPath = new Path();
    float mediaClipW = -1f, mediaClipH = -1f;
    final Path[] mediaGroupClipPath = {new Path(), new Path(), new Path(), new Path()};
    final float[] mediaGroupClipW = {-1f, -1f, -1f, -1f};
    final float[] mediaGroupClipH = {-1f, -1f, -1f, -1f};

    // ── Geometry set during onMeasure, read by renderers during onDraw/touch ──
    final RectF avatarRect = new RectF();
    final RectF optionsButtonRect = new RectF();
    final RectF mediaRect = new RectF();
    final RectF likeIconRect = new RectF();
    final RectF commentIconRect = new RectF();
    final RectF shareIconRect    = new RectF();
    final RectF bookmarkIconRect = new RectF();
    float likeCountTextX, commentCountTextX;

    // ── Bind state ──
    String authorName, timestampText, postText;
    boolean hasPinned, hasAnnouncement, canModify;
    Bitmap authorAvatarBitmap;

    boolean hasMedia, mediaIsVideo;
    Bitmap mediaBitmap;

    boolean hasMediaGroup;
    List<Bitmap> mediaGroupBitmaps;
    List<Boolean> mediaGroupIsVideo;
    int mediaGroupOverflowCount;
    int mediaGroupCount;

    boolean hasPoll;
    CommunityPoll poll;
    Integer myVotedOptionIndex;

    boolean hasReactions;
    Map<String, Long> reactionCounts;
    boolean myReacted;
    boolean isBookmarked;
    String likeCountText = "";
    String commentCountText = "";

    private String currentPostId;

    // ── Renderers ──
    private final AuthorHeaderRenderer authorHeaderRenderer = new AuthorHeaderRenderer(this);
    private final PostTextRenderer postTextRenderer = new PostTextRenderer(this);
    private final PostMediaRenderer postMediaRenderer = new PostMediaRenderer(this);
    private final PostMediaGroupRenderer postMediaGroupRenderer = new PostMediaGroupRenderer(this);
    private final PostPollRenderer postPollRenderer = new PostPollRenderer(this);
    private final ReactionRowRenderer reactionRowRenderer = new ReactionRowRenderer(this);
    private final EngagementBarRenderer engagementBarRenderer = new EngagementBarRenderer(this);

    // ── Section y-offsets computed in onMeasure, reused by onDraw ──
    private float headerBottom, textTop, textHeight, mediaTop, mediaBottom;
    private float pollTop, pollBottom, reactionsTop, reactionsBottom, engagementTop, engagementBottom;

    private OnPostClickListener listener;

    // ── Touch state ──
    private float downX, downY;
    private long downTime;
    private static final int TAP_SLOP_DP = 8;
    private static final int LONG_PRESS_MS = 450;
    private final Runnable longPressRunnable = this::handleLongPress;
    private boolean longPressFired;

    public CommunityPostCanvasView(Context context) {
        super(context);
        init(context);
    }

    private void init(Context context) {
        density = context.getResources().getDisplayMetrics().density;

        cardPadding = 12 * density;
        cardCornerRadius = 12 * density;
        avatarSize = 40 * density;
        avatarTextGap = 10 * density;
        nameTimestampGap = 2 * density;
        badgeMarginBottom = 6 * density;
        optionsButtonSize = 24 * density;
        textMarginTop = 10 * density;
        mediaMarginTop = 10 * density;
        mediaCornerRadius = 10 * density;
        singleMediaHeight = 200 * density;
        playIconRadius = 28 * density;
        mediaGroupGap = 2 * density;
        mediaGroupHeight = 200 * density;
        pollMarginTop = 10 * density;
        pollQuestionGap = 6 * density;
        pollOptionHeight = 40 * density;
        pollOptionGap = 8 * density;
        pollOptionTextPadding = 12 * density;
        reactionsMarginTop = 8 * density;
        reactionChipHeight = 26 * density;
        reactionChipPaddingH = 10 * density;
        reactionChipGap = 6 * density;
        reactionChipRowGap = 6 * density;
        engagementMarginTop = 12 * density;
        engagementIconSize = 22 * density;
        engagementIconGap = 6 * density;
        engagementGroupGap = 20 * density;

        int textPrimary = ContextCompat.getColor(context, R.color.text_primary);
        int textMuted = ContextCompat.getColor(context, R.color.text_muted);
        int colorPrimary = ContextCompat.getColor(context, R.color.colorPrimary);
        int surfaceCard = ContextCompat.getColor(context, R.color.surface_card);
        int likeActive = ContextCompat.getColor(context, R.color.community_like_active);
        mentionColor = colorPrimary;

        cardBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        cardBgPaint.setColor(surfaceCard);
        cardBgPaint.setStyle(Paint.Style.FILL);

        pinnedBadgePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        pinnedBadgePaint.setTextSize(11 * density);
        pinnedBadgePaint.setColor(textMuted);

        announcementBadgePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        announcementBadgePaint.setTextSize(11 * density);
        announcementBadgePaint.setColor(colorPrimary);
        announcementBadgePaint.setTypeface(Typeface.DEFAULT_BOLD);

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
        avatarPlaceholderBgPaint.setColor(ColorUtil.lighten(colorPrimary));

        optionsDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        optionsDotPaint.setColor(textMuted);

        postTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        postTextPaint.setTextSize(14 * density);
        postTextPaint.setColor(textPrimary);

        mediaBitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mediaPlaceholderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mediaPlaceholderPaint.setColor(ContextCompat.getColor(context, R.color.surface_input));
        mediaVideoScrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mediaVideoScrimPaint.setColor(Color.argb(60, 0, 0, 0));
        playCirclePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        playCirclePaint.setColor(Color.argb(217, 255, 255, 255));
        playTrianglePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        playTrianglePaint.setColor(Color.DKGRAY);

        mediaOverflowScrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mediaOverflowScrimPaint.setColor(Color.argb(140, 0, 0, 0));
        mediaOverflowTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        mediaOverflowTextPaint.setTextSize(20 * density);
        mediaOverflowTextPaint.setColor(Color.WHITE);
        mediaOverflowTextPaint.setTextAlign(Paint.Align.CENTER);
        mediaOverflowTextPaint.setTypeface(Typeface.DEFAULT_BOLD);

        pollQuestionPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        pollQuestionPaint.setTextSize(14 * density);
        pollQuestionPaint.setColor(textPrimary);
        pollQuestionPaint.setTypeface(Typeface.DEFAULT_BOLD);

        pollOptionPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        pollOptionPaint.setTextSize(13 * density);
        pollOptionPaint.setColor(textPrimary);

        pollPercentPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        pollPercentPaint.setTextSize(12 * density);
        pollPercentPaint.setColor(textMuted);

        pollTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        pollTrackPaint.setColor(ContextCompat.getColor(context, R.color.surface_input));
        pollFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        pollFillPaint.setColor(ColorUtil.withAlpha(colorPrimary, 60));
        pollFillSelectedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        pollFillSelectedPaint.setColor(ColorUtil.withAlpha(colorPrimary, 110));
        pollSelectedBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        pollSelectedBorderPaint.setStyle(Paint.Style.STROKE);
        pollSelectedBorderPaint.setStrokeWidth(1.5f * density);
        pollSelectedBorderPaint.setColor(colorPrimary);

        reactionChipBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        reactionChipBgPaint.setColor(ContextCompat.getColor(context, R.color.surface_input));
        reactionChipTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        reactionChipTextPaint.setTextSize(12 * density);
        reactionChipTextPaint.setColor(textPrimary);

        likeFilledPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        likeFilledPaint.setColor(likeActive);
        likeOutlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        likeOutlinePaint.setStyle(Paint.Style.STROKE);
        likeOutlinePaint.setStrokeWidth(1.6f * density);
        likeOutlinePaint.setColor(textMuted);
        commentGlyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        commentGlyphPaint.setStyle(Paint.Style.STROKE);
        commentGlyphPaint.setStrokeWidth(1.6f * density);
        commentGlyphPaint.setColor(textMuted);
        shareGlyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        shareGlyphPaint.setStyle(Paint.Style.STROKE);
        shareGlyphPaint.setStrokeWidth(1.8f * density);
        shareGlyphPaint.setColor(textMuted);
        shareGlyphPaint.setStrokeCap(Paint.Cap.ROUND);
        shareGlyphPaint.setStrokeJoin(Paint.Join.ROUND);
        bookmarkGlyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bookmarkGlyphPaint.setStyle(Paint.Style.STROKE);
        bookmarkGlyphPaint.setStrokeWidth(1.8f * density);
        bookmarkGlyphPaint.setColor(textMuted);
        bookmarkGlyphPaint.setStrokeCap(Paint.Cap.ROUND);
        bookmarkGlyphPaint.setStrokeJoin(Paint.Join.ROUND);

        engagementCountPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        engagementCountPaint.setTextSize(13 * density);
        engagementCountPaint.setColor(textMuted);
    }

    /** Avatar diameter in px for this density — exposed so the adapter can
     *  size Glide's decode override to match what's actually drawn, instead
     *  of decoding avatar photos at their original (often much larger) size. */
    public static int avatarPx(android.content.Context ctx) {
        return Math.round(40 * ctx.getResources().getDisplayMetrics().density);
    }

    /** Single-media card height in px for this density (see mediaGroupHeight
     *  above), used the same way for the Glide media-bitmap override. */
    public static int mediaHeightPx(android.content.Context ctx) {
        return Math.round(200 * ctx.getResources().getDisplayMetrics().density);
    }

    float cardRight() {
        return getWidth();
    }

    public void setOnPostClickListener(OnPostClickListener listener) {
        this.listener = listener;
    }

    /** Main bind entry — sets all draw state from the entity and requests re-layout. */
    public void bind(CommunityPostEntity post, boolean isAdminOrOwner, String currentUid) {
        currentPostId = post.id;
        authorName = post.authorName != null ? post.authorName : "Member";
        timestampText = post.createdAt > 0
                ? android.text.format.DateUtils.getRelativeTimeSpanString(post.createdAt,
                System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS).toString() : "";
        postText = post.text;
        hasPinned = post.pinned;
        hasAnnouncement = post.isAnnouncement;
        boolean isAuthor = currentUid != null && currentUid.equals(post.authorUid);
        canModify = isAdminOrOwner || isAuthor;

        hasMedia = post.mediaUrl != null && !post.mediaUrl.isEmpty();
        mediaIsVideo = "video".equals(post.mediaType);
        hasMediaGroup = false; // single-media entity today; bindMediaGroup() below is for future multi-image posts
        mediaBitmap = null;

        poll = CommunityPoll.fromJson(post.pollJson);
        hasPoll = poll != null && poll.options != null && !poll.options.isEmpty();
        myVotedOptionIndex = poll != null && currentUid != null ? poll.votedOptionOf(currentUid) : null;

        reactionCounts = CommunityReaction.fromJson(post.reactionCountsJson);
        hasReactions = reactionCounts != null && !reactionCounts.isEmpty();
        myReacted    = post.myReactionType != null && !post.myReactionType.isEmpty();
        isBookmarked = com.callx.app.community.CommunityBookmarksActivity
                           .isBookmarked(getContext(), post.communityId, post.id);

        long totalReactions = CommunityReaction.totalCount(post.reactionCountsJson);
        long displayLikes = totalReactions > 0 ? totalReactions : post.likeCount;
        likeCountText = displayLikes > 0 ? String.valueOf(displayLikes) : "";
        commentCountText = post.commentCount > 0 ? String.valueOf(post.commentCount) : "";

        authorAvatarBitmap = null;
        avatarShaderBitmap = null;
        avatarShaderCache = null;
        mediaShaderBitmap = null;
        mediaShaderCache = null;
        requestLayout();
        invalidateEngagementBar();
    }

    /** Called once Glide resolves the author avatar (or with null to reset). */
    public void setAuthorAvatarBitmap(String forPostId, @Nullable Bitmap bmp) {
        if (!java.util.Objects.equals(forPostId, currentPostId)) return;
        authorAvatarBitmap = bmp;
        invalidateEngagementBar();
    }

    /** Called once Glide resolves the single-media bitmap. */
    public void setMediaBitmap(String forPostId, @Nullable Bitmap bmp) {
        if (!java.util.Objects.equals(forPostId, currentPostId)) return;
        mediaBitmap = bmp;
        invalidate();
    }

    /** Switches this card into multi-image gallery mode (future-proofing — CommunityPostEntity
     *  only stores one mediaUrl today; callers with a media-list source can drive this instead
     *  of bind()'s single-media path). */
    public void bindMediaGroup(List<Bitmap> bitmaps, List<Boolean> isVideoFlags, int totalCount) {
        hasMedia = false;
        hasMediaGroup = bitmaps != null && !bitmaps.isEmpty();
        mediaGroupBitmaps = bitmaps;
        mediaGroupIsVideo = isVideoFlags;
        mediaGroupCount = bitmaps != null ? Math.min(bitmaps.size(), 4) : 0;
        mediaGroupOverflowCount = Math.max(0, totalCount - 4);
        java.util.Arrays.fill(mediaGroupShaderBitmap, null);
        java.util.Arrays.fill(mediaGroupShaderCache, null);
        requestLayout();
        invalidate();
    }

    /** Partial rebind — updates only like/comment/reaction state (used by the adapter's
     *  PAYLOAD_ENGAGEMENT path so avatar/media bitmaps aren't reset on every like tap). */
    public void updateEngagementOnly(CommunityPostEntity post) {
        reactionCounts = CommunityReaction.fromJson(post.reactionCountsJson);
        hasReactions = reactionCounts != null && !reactionCounts.isEmpty();
        myReacted = post.myReactionType != null && !post.myReactionType.isEmpty();
        long totalReactions = CommunityReaction.totalCount(post.reactionCountsJson);
        long displayLikes = totalReactions > 0 ? totalReactions : post.likeCount;
        likeCountText = displayLikes > 0 ? String.valueOf(displayLikes) : "";
        commentCountText = post.commentCount > 0 ? String.valueOf(post.commentCount) : "";
        requestLayout();
        invalidate();
    }

    /** Partial rebind — updates only poll vote state (used by the adapter's PAYLOAD_POLL path). */
    public void updatePollOnly(CommunityPostEntity post, String currentUid) {
        poll = CommunityPoll.fromJson(post.pollJson);
        hasPoll = poll != null && poll.options != null && !poll.options.isEmpty();
        myVotedOptionIndex = poll != null && currentUid != null ? poll.votedOptionOf(currentUid) : null;
        requestLayout();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        float contentLeft = cardPadding;
        float contentRight = width - cardPadding;
        float contentWidth = Math.max(1f, contentRight - contentLeft);

        float y = cardPadding;
        headerBottom = authorHeaderRenderer.measure(y);
        y = headerBottom;

        if (postText != null && !postText.isEmpty()) {
            y += textMarginTop;
            textTop = y;
            textHeight = postTextRenderer.measure((int) contentWidth);
            y += textHeight;
        } else {
            textTop = y;
            textHeight = 0;
        }

        if (hasMedia) {
            y += mediaMarginTop;
            mediaTop = y;
            mediaRect.set(contentLeft, y, contentRight, y + singleMediaHeight);
            mediaBottom = y + singleMediaHeight;
            y = mediaBottom;
        } else if (hasMediaGroup) {
            y += mediaMarginTop;
            mediaTop = y;
            mediaBottom = postMediaGroupRenderer.layout(contentLeft, y, contentRight, mediaGroupCount);
            y = mediaBottom;
        } else {
            mediaTop = y;
            mediaBottom = y;
        }

        if (hasPoll) {
            y += pollMarginTop;
            pollTop = y;
            pollBottom = postPollRenderer.layout(contentLeft, y, contentRight, poll);
            y = pollBottom;
        } else {
            pollTop = y;
            pollBottom = y;
        }

        if (hasReactions) {
            y += reactionsMarginTop;
            reactionsTop = y;
            reactionsBottom = reactionRowRenderer.layout(contentLeft, y, contentRight, reactionCounts);
            y = reactionsBottom;
        } else {
            reactionsTop = y;
            reactionsBottom = y;
        }

        y += engagementMarginTop;
        engagementTop = y;
        engagementBottom = engagementBarRenderer.layout(contentLeft, y, contentRight);
        y = engagementBottom;

        y += cardPadding;
        setMeasuredDimension(width, (int) y);
    }

    @Override
    protected void onDraw(android.graphics.Canvas canvas) {
        super.onDraw(canvas);

        // PERF: was clipPath(roundRect) + drawRect + restore on every single
        // frame — clipPath is one of the most expensive Canvas ops, and here
        // it was only ever used to paint a plain rounded-rect fill (nothing
        // else escapes the card bounds; media/avatar have their own local
        // clips). A single drawRoundRect() produces the identical pixels for
        // a fraction of the cost, with zero per-frame allocation.
        canvas.drawRoundRect(0, 0, getWidth(), getHeight(), cardCornerRadius, cardCornerRadius, cardBgPaint);

        authorHeaderRenderer.draw(canvas);

        if (postText != null && !postText.isEmpty()) {
            postTextRenderer.draw(canvas, textTop, cardPadding);
        }

        if (hasMedia) {
            postMediaRenderer.draw(canvas);
        } else if (hasMediaGroup) {
            postMediaGroupRenderer.draw(canvas);
        }

        if (hasPoll) {
            postPollRenderer.draw(canvas, cardPadding, pollTop, getWidth() - cardPadding, poll, myVotedOptionIndex);
        }

        if (hasReactions) {
            reactionRowRenderer.draw(canvas);
        }

        engagementBarRenderer.draw(canvas);
    }

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
                if (Math.abs(event.getX() - downX) > slop || Math.abs(event.getY() - downY) > slop) {
                    removeCallbacks(longPressRunnable);
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                removeCallbacks(longPressRunnable);
                if (!longPressFired) {
                    handleTap(event.getX(), event.getY());
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPressRunnable);
                return true;
        }
        return super.onTouchEvent(event);
    }

    private void handleLongPress() {
        longPressFired = true;
        if (listener == null) return;
        if (likeIconRect.contains(downX, downY)) {
            listener.onLikeLongClick(this);
        } else {
            listener.onPostLongClick();
        }
    }

    private void handleTap(float x, float y) {
        if (listener == null) return;

        if (canModify && optionsButtonRect.contains(x, y)) {
            listener.onOptionsClick();
            return;
        }
        if (avatarRect.contains(x, y)) {
            listener.onAuthorClick();
            return;
        }
        if (hasMedia && mediaRect.contains(x, y)) {
            listener.onMediaClick();
            return;
        }
        if (hasMediaGroup) {
            int cell = postMediaGroupRenderer.hitTestCell(x, y);
            if (cell >= 0) {
                boolean isOverflowCell = cell == mediaGroupCount - 1 && mediaGroupOverflowCount > 0;
                if (isOverflowCell) {
                    listener.onMediaGroupOverflowClick();
                } else {
                    listener.onMediaCellClick(cell);
                }
                return;
            }
        }
        if (hasPoll) {
            int option = postPollRenderer.hitTestOption(x, y);
            if (option >= 0) {
                listener.onPollOptionClick(option);
                return;
            }
        }
        if (postText != null && !postText.isEmpty() && y >= textTop && y <= textTop + textHeight) {
            String mention = postTextRenderer.mentionAt(x - cardPadding, y - textTop);
            if (mention != null) {
                listener.onMentionClick(mention);
                return;
            }
        }
        if (hasReactions && reactionRowRenderer.hitTest(x, y)) {
            listener.onReactionsClick();
            return;
        }
        int engagementRegion = engagementBarRenderer.hitTest(x, y);
        if (engagementRegion == EngagementBarRenderer.REGION_LIKE) {
            listener.onLikeClick();
            return;
        }
        if (engagementRegion == EngagementBarRenderer.REGION_COMMENT) {
            listener.onCommentClick();
            return;
        }
        if (engagementRegion == EngagementBarRenderer.REGION_SHARE) {
            listener.onShareClick();
            return;
        }
        if (engagementRegion == EngagementBarRenderer.REGION_BOOKMARK) {
            listener.onBookmarkClick();
            return;
        }

        listener.onPostClick();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(longPressRunnable);
    }

    // ── Partial dirty-rect invalidation ──────────────────────────────────────────

    /**
     * Invalidates only the engagement bar region (like count, comment count,
     * bookmark, share glyph row) instead of the full view.
     *
     * Called by updateLikeCount(), updateBookmarkState(), setReactions(), and
     * setCommentCount() so a Firebase real-time counter tick repaints only ~40dp
     * at the bottom of the card rather than re-rasterizing the whole post
     * (author header, image/media, poll, text body, reaction chips).
     *
     * Falls back to full invalidate() if onMeasure hasn't run yet (engagementTop == 0).
     */
    public void invalidateEngagementBar() {
        if (engagementTop <= 0f || engagementBottom <= engagementTop) {
            invalidateEngagementBar();
            return;
        }
        invalidate(0, (int) (engagementTop - 1f), getWidth(), (int) (engagementBottom + 2f));
    }

    /** Convenience alias — same as invalidateEngagementBar(). */
    public void invalidateLikeState() {
        invalidateEngagementBar();
    }

}
