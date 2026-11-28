package com.callx.app.chatlist.canvas;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.callx.app.cache.AvatarBitmapPipeline;
import com.callx.app.cache.ChatAvatarBinder;
import com.callx.app.chat.R;
import com.callx.app.utils.StoryRingBitmapCache;

/**
 * ChatRowAvatarView — v947. ONE 58dp canvas view that replaces, per chat-list row:
 *   FrameLayout(58dp) + ChatListStoryRingView + CircleImageView + (ViewStub -> 3-view selection overlay).
 *
 * Draw order (all in one display list, zero allocation in onDraw):
 *   1. story ring      (STATE_UNSEEN: cached gradient bitmap blit, STATE_SEEN: flat oval stroke)
 *   2. avatar          (pre-circle-cropped L2 bitmap -> drawBitmap, or ic_person / ic_group vector placeholder)
 *   3. avatar border   (1.5dp brand_primary stroke — same geometry as CircleImageView civ_border_width=1.5dp)
 *   4. selection scrim (58dp #99000000 square) + 30dp accept-green check circle when selected
 *
 * The avatar bitmap comes straight from ChatAvatarBinder's bitmap pipeline (same url, same L1/L2 caches as the
 * old Glide-into-ImageView path) — no Glide Target, no BitmapShader, no ImageView per row. The bitmap is
 * deliberately KEPT across RecyclerView recycle: re-binding the same user to the same ViewHolder (tab reopen,
 * diff change) is a pure no-op — no decode, no invalidate, no re-record.
 *
 * Geometry is identical to the old tree so ChatListTextPrecompute needs no change:
 *   view 58dp, avatar box 50dp centred, border radius (50-1.5)/2, image radius (50-3)/2, ring stroke 1.5dp inset 0.5dp.
 */
public class ChatRowAvatarView extends View {

    // ── ring state (same constants/values as ChatListStoryRingView) ─────────────
    public static final int STATE_NONE   = ChatListStoryRingView.STATE_NONE;
    public static final int STATE_UNSEEN = ChatListStoryRingView.STATE_UNSEEN;
    public static final int STATE_SEEN   = ChatListStoryRingView.STATE_SEEN;

    // ── placeholder kinds ───────────────────────────────────────────────────────
    private static final int PH_PERSON      = 0; // ic_person, full circle (1:1 row, no photo / loading)
    private static final int PH_GROUP_FULL  = 1; // ic_group, full circle (group has a photo that is still loading)
    private static final int PH_GROUP_SMALL = 2; // ic_group, small ring (group without photo; old 12dp padding)

    private static final float VIEW_DP       = 58f;
    private static final float AVATAR_DP     = 50f;
    private static final float BORDER_DP     = 1.5f;
    private static final float RING_STROKE_DP = 1.5f;
    private static final float RING_INSET_DP  = 0.5f;
    private static final float GROUP_PAD_DP  = 12f;
    private static final float CHECK_DP      = 30f;

    private static final int COLOR_SEEN      = 0xFFCBD5E1;
    private static final int COLOR_UNSEEN_FALLBACK = 0xFFFF1493;
    private static final int COLOR_SCRIM     = 0x99000000;

    private final float density;
    private final float viewPx;
    private final float cx, cy;

    private final Paint ringPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringBmpPaint= new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint bmpPaint    = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scrimPaint  = new Paint();
    private final Paint checkPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF oval = new RectF();
    private final RectF dst  = new RectF();

    private final float ringStrokePx, ringInsetPx, borderPx;
    // precomputed circle geometry (normal / small-group variants)
    private final float borderR, imageR, smallBorderR, smallImageR, checkR;

    private Drawable personIcon, groupIcon;

    // ── state ───────────────────────────────────────────────────────────────────
    private int ringState = STATE_NONE;
    private Bitmap ringBitmap;
    private boolean ringDirty = true;

    private Bitmap avatar;          // pre-circle-cropped, shared with the L2 cache — never recycled/mutated here
    private String curUrl;          // url `avatar` (or the in-flight load) belongs to
    private boolean loading;
    private AvatarBitmapPipeline.Cancelable loadHandle;
    private int phKind = PH_PERSON;

    private boolean overlayOn;
    private boolean overlayChecked;

    private final AvatarBitmapPipeline.Callback loadCb = new AvatarBitmapPipeline.Callback() {
        @Override public void onBitmap(@Nullable Bitmap b) {
            loading = false;
            loadHandle = null;
            if (b == null || b.isRecycled()) return;
            avatar = b;
            invalidate();
        }
    };

    public ChatRowAvatarView(Context ctx) { this(ctx, null); }

    public ChatRowAvatarView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        density = ctx.getResources().getDisplayMetrics().density;
        viewPx  = VIEW_DP * density;
        cx = cy = viewPx / 2f;

        ringStrokePx = RING_STROKE_DP * density;
        ringInsetPx  = RING_INSET_DP * density;
        borderPx     = BORDER_DP * density;

        borderR      = (AVATAR_DP * density - borderPx) / 2f;
        imageR       = (AVATAR_DP * density - 2f * borderPx) / 2f;
        final float smallBox = (AVATAR_DP - 2f * GROUP_PAD_DP) * density;
        smallBorderR = (smallBox - borderPx) / 2f;
        smallImageR  = (smallBox - 2f * borderPx) / 2f;
        checkR       = CHECK_DP * density / 2f;

        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(ringStrokePx);
        ringPaint.setColor(COLOR_SEEN);

        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(borderPx);
        borderPaint.setColor(ContextCompat.getColor(ctx, R.color.brand_primary));

        scrimPaint.setColor(COLOR_SCRIM);
        checkPaint.setStyle(Paint.Style.FILL);
        checkPaint.setColor(ContextCompat.getColor(ctx, R.color.action_call_accept));

        // Own (mutated) copies — the shared constant state is never touched.
        personIcon = loadIcon(ctx, R.drawable.ic_person);
        groupIcon  = loadIcon(ctx, R.drawable.ic_group);

        setWillNotDraw(false);
    }

    private static Drawable loadIcon(Context ctx, int res) {
        Drawable d = ContextCompat.getDrawable(ctx, res);
        return d == null ? null : d.mutate();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Public API (all setters are no-ops — no invalidate — when nothing changed)
    // ═════════════════════════════════════════════════════════════════════════

    public void setRingState(int s) {
        if (s == ringState) return;
        ringState = s;
        ringDirty = true;
        invalidate();
    }

    public int getRingState() { return ringState; }

    /**
     * Binds the avatar. {@code url} is the already-resolved row URL (ChatAvatarBinder.rowUrl) or null/empty for
     * "no photo". {@code group} picks the placeholder style. Same url + (bitmap or load in flight) = free no-op.
     */
    public void setAvatar(Context ctx, @Nullable String url, boolean group) {
        if (url == null || url.isEmpty()) {
            cancelLoad();
            final boolean changed = avatar != null || curUrl != null
                    || phKind != (group ? PH_GROUP_SMALL : PH_PERSON);
            avatar = null;
            curUrl = null;
            phKind = group ? PH_GROUP_SMALL : PH_PERSON;
            if (changed) invalidate();
            return;
        }
        if (url.equals(curUrl) && (avatar != null || loading)) return;   // already showing / loading this exact url

        cancelLoad();
        curUrl = url;
        avatar = null;
        phKind = group ? PH_GROUP_FULL : PH_PERSON;

        final Bitmap hit = ChatAvatarBinder.peekRow(ctx, url);            // L1/L2 memory: instant
        if (hit != null && !hit.isRecycled()) {
            avatar = hit;
            invalidate();
            return;
        }
        loading = true;
        invalidate();                                                      // placeholder until the bitmap lands
        loadHandle = ChatAvatarBinder.bindRow(ctx, url, loadCb);
        // bindRow() can complete synchronously (loadCb already ran) — don't keep a stale handle then.
        if (!loading) loadHandle = null;
    }

    /** Stops an in-flight load (RecyclerView recycle). The current bitmap/url are KEPT for a free same-user rebind. */
    public void cancelLoad() {
        final AvatarBitmapPipeline.Cancelable h = loadHandle;
        loadHandle = null;
        if (loading) {
            loading = false;
            if (h != null) h.cancel();
        }
    }

    /** Selection mode scrim + (optionally) the green check circle. */
    public void setSelectionOverlay(boolean on, boolean checked) {
        if (on == overlayOn && checked == overlayChecked) return;
        overlayOn = on;
        overlayChecked = on && checked;
        invalidate();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Measure / draw
    // ═════════════════════════════════════════════════════════════════════════

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int px = Math.round(viewPx);
        setMeasuredDimension(px, px);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        ringDirty = true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final int w = getWidth();
        final int h = getHeight();
        if (w <= 0 || h <= 0) return;
        final boolean hw = canvas.isHardwareAccelerated();

        // 1 — story ring
        if (ringState != STATE_NONE) {
            if (ringDirty) { refreshRing(w, h); ringDirty = false; }
            if (ringState == STATE_UNSEEN && ringBitmap != null && !ringBitmap.isRecycled()
                    && (hw || ringBitmap.getConfig() != Bitmap.Config.HARDWARE)) {
                canvas.drawBitmap(ringBitmap, ringInsetPx, ringInsetPx, ringBmpPaint);
            } else {
                final float half = ringStrokePx / 2f;
                oval.set(ringInsetPx + half, ringInsetPx + half, w - ringInsetPx - half, h - ringInsetPx - half);
                canvas.drawOval(oval, ringPaint);
            }
        }

        // 2 — avatar image / placeholder, 3 — border
        final Bitmap b = avatar;
        if (b != null && !b.isRecycled() && (hw || b.getConfig() != Bitmap.Config.HARDWARE)) {
            dst.set(cx - imageR, cy - imageR, cx + imageR, cy + imageR);
            canvas.drawBitmap(b, null, dst, bmpPaint);
            canvas.drawCircle(cx, cy, borderR, borderPaint);
        } else if (phKind == PH_GROUP_SMALL) {
            drawIcon(canvas, groupIcon, smallImageR);
            canvas.drawCircle(cx, cy, smallBorderR, borderPaint);
        } else {
            drawIcon(canvas, phKind == PH_GROUP_FULL ? groupIcon : personIcon, imageR);
            canvas.drawCircle(cx, cy, borderR, borderPaint);
        }

        // 4 — selection overlay
        if (overlayOn) {
            canvas.drawRect(0, 0, w, h, scrimPaint);
            if (overlayChecked) canvas.drawCircle(cx, cy, checkR, checkPaint);
        }
    }

    private void drawIcon(Canvas canvas, @Nullable Drawable d, float r) {
        if (d == null) return;
        d.setBounds(Math.round(cx - r), Math.round(cy - r), Math.round(cx + r), Math.round(cy + r));
        d.draw(canvas);
    }

    private void refreshRing(int w, int h) {
        if (ringState == STATE_UNSEEN) {
            final int rw = Math.round(w - 2 * ringInsetPx);
            final int rh = Math.round(h - 2 * ringInsetPx);
            ringBitmap = StoryRingBitmapCache.get(rw, rh, ringStrokePx);
            ringPaint.setColor(COLOR_UNSEEN_FALLBACK);
        } else {
            ringBitmap = null;
            ringPaint.setColor(COLOR_SEEN);
        }
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;   // single-layer content: lets the framework skip off-screen compositing on alpha animations
    }
}
