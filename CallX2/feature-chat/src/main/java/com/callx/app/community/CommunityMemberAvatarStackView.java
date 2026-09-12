package com.callx.app.community;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import com.bumptech.glide.request.target.Target;
import com.callx.app.cache.CommunityAvatarBinder;

import java.util.ArrayList;
import java.util.List;

/**
 * CommunityMemberAvatarStackView — custom Canvas View drawing overlapping
 * circular member avatars ("+N" overflow badge for the remainder), used
 * next to the member count in CommunityActivity's toolbar and in the chat
 * profile card. Mirrors CommunityPollView's Canvas-drawing approach (same
 * "reuse chat's canvas rendering" requirement) rather than stacking real
 * ImageViews, which is both heavier and harder to overlap precisely.
 */
public class CommunityMemberAvatarStackView extends View {

    private static final float AVATAR_SIZE_DP  = 26f;
    private static final float OVERLAP_DP      = 10f; // how much each avatar overlaps the previous
    private static final float BORDER_WIDTH_DP = 1.5f;
    private static final int   MAX_VISIBLE     = 4;

    private final Paint mBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFallbackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mOverflowBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mOverflowTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final List<Bitmap> mBitmaps = new ArrayList<>();
    private final List<Target<Bitmap>> mAvatarTargets = new ArrayList<>();
    private int mTotalCount = 0;
    private float mDensity;
    // Bumped on every bind() — lets an async callback from a PREVIOUS
    // bind() detect the view has since been rebound (recycled row) and
    // drop its stale result instead of overwriting a newer bind's data.
    private int mBindGeneration = 0;

    public CommunityMemberAvatarStackView(Context context) {
        this(context, null);
    }

    public CommunityMemberAvatarStackView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CommunityMemberAvatarStackView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mDensity = context.getResources().getDisplayMetrics().density;
        mBorderPaint.setColor(0xFFFFFFFF);
        mBorderPaint.setStyle(Paint.Style.STROKE);
        mBorderPaint.setStrokeWidth(BORDER_WIDTH_DP * mDensity);
        mFallbackPaint.setColor(0xFFB0BEC5);
        mOverflowBgPaint.setColor(0xFF64748B);
        mOverflowTextPaint.setColor(0xFFFFFFFF);
        mOverflowTextPaint.setTextAlign(Paint.Align.CENTER);
        mOverflowTextPaint.setTextSize(10f * mDensity);
    }

    /**
     * Loads up to MAX_VISIBLE avatars (as bitmaps, since Canvas drawing
     * needs raw Bitmaps, not Drawables/ImageViews) and redraws once each
     * has loaded. totalCount drives the "+N" overflow badge.
     *
     * FIX (avatar-pipeline parity): this used to hand-roll its own
     * L2-check -> Glide-decode -> L2/L3-write-through copy of exactly the
     * pipeline {@link CommunityAvatarBinder#bindBitmap} already provides —
     * a third copy of that logic per the class's own doc. Now delegates
     * straight to bindBitmap() at TIER_STACK (26dp), so this shares
     * L2/L3 cache entries with anything else resolving to that tier
     * (and the same responsive/version-tagged URL + AvatarCacheAnalytics
     * recording) instead of maintaining its own parallel copy of it.
     */
    public void bind(List<String> photoUrls, int totalCount) {
        for (Target<Bitmap> t : mAvatarTargets) CommunityAvatarBinder.cancelBitmap(getContext(), t);
        mAvatarTargets.clear();

        mTotalCount = totalCount;
        mBitmaps.clear();
        invalidate();
        final int generation = ++mBindGeneration;

        int visible = Math.min(photoUrls.size(), MAX_VISIBLE);
        for (int i = 0; i < visible; i++) {
            String rawUrl = photoUrls.get(i);
            if (rawUrl == null || rawUrl.isEmpty()) { mBitmaps.add(null); continue; }
            final int index = i;
            mBitmaps.add(null); // placeholder slot, filled in (possibly synchronously) below

            Target<Bitmap> target = CommunityAvatarBinder.bindBitmap(
                    getContext(), rawUrl, CommunityAvatarBinder.TIER_STACK, bmp -> {
                        if (generation != mBindGeneration) return; // recycled to a different bind() since
                        while (mBitmaps.size() <= index) mBitmaps.add(null);
                        mBitmaps.set(index, bmp);
                        invalidate();
                    });
            if (target != null) mAvatarTargets.add(target);
        }
        requestLayout();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        for (Target<Bitmap> t : mAvatarTargets) CommunityAvatarBinder.cancelBitmap(getContext(), t);
        mAvatarTargets.clear();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int visible = Math.min(Math.max(mBitmaps.size(), 0), MAX_VISIBLE);
        boolean hasOverflow = mTotalCount > MAX_VISIBLE;
        int slots = visible + (hasOverflow ? 1 : 0);
        float avatarSize = AVATAR_SIZE_DP * mDensity;
        float overlap = OVERLAP_DP * mDensity;
        float width = slots <= 0 ? 0 : avatarSize + (slots - 1) * (avatarSize - overlap);
        setMeasuredDimension((int) Math.ceil(width) + (int) (BORDER_WIDTH_DP * mDensity * 2),
                (int) Math.ceil(avatarSize) + (int) (BORDER_WIDTH_DP * mDensity * 2));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float avatarSize = AVATAR_SIZE_DP * mDensity;
        float radius = avatarSize / 2f;
        float overlap = OVERLAP_DP * mDensity;
        float step = avatarSize - overlap;
        float border = BORDER_WIDTH_DP * mDensity;

        int visible = Math.min(mBitmaps.size(), MAX_VISIBLE);
        // Draw right-to-left so the leftmost avatar is on top, matching typical avatar-stack UIs.
        for (int i = visible - 1; i >= 0; i--) {
            float cx = border + radius + i * step;
            float cy = border + radius;
            Bitmap bmp = mBitmaps.get(i);
            if (bmp != null) {
                BitmapShader shader = new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                mBitmapPaint.setShader(shader);
                canvas.drawCircle(cx, cy, radius, mBitmapPaint);
            } else {
                canvas.drawCircle(cx, cy, radius, mFallbackPaint);
            }
            canvas.drawCircle(cx, cy, radius - border / 2f, mBorderPaint);
        }

        int overflowCount = mTotalCount - MAX_VISIBLE;
        if (overflowCount > 0) {
            float cx = border + radius + visible * step;
            float cy = border + radius;
            canvas.drawCircle(cx, cy, radius, mOverflowBgPaint);
            canvas.drawCircle(cx, cy, radius - border / 2f, mBorderPaint);
            float textY = cy - (mOverflowTextPaint.ascent() + mOverflowTextPaint.descent()) / 2f;
            canvas.drawText("+" + overflowCount, cx, textY, mOverflowTextPaint);
        }
    }
}
