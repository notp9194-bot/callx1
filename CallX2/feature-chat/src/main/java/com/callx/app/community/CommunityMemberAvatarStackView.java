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

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.callx.app.cache.AvatarCacheAnalytics;
import com.callx.app.cache.ChatAvatarL2Cache;
import com.callx.app.cache.CommunityAvatarBinder;
import com.callx.app.utils.AvatarUrlBuilder;

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
    private int mTotalCount = 0;
    private float mDensity;
    // Bumped on every bind() — lets async L3-disk / Glide callbacks from a
    // PREVIOUS bind() detect the view has since been rebound (recycled row)
    // and drop their stale result instead of overwriting a newer bind's data.
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
     * FIX (avatar-pipeline parity): this used to key L2/L3 by the raw,
     * un-tiered photo URL and decode at a hardcoded override(96, 96) that
     * ignored this view's actual ~26dp draw size — so a member's photo
     * shown here NEVER shared a cache entry with the same photo shown as
     * a CommunityAvatarBinder-bound post author or member row (different
     * cache key, different pixel size, decoded/cached a 3rd time), and
     * every avatar in the stack over-decoded to 96px regardless of
     * density. Now goes through CommunityAvatarBinder.url()'s same
     * tier-bucketed/responsive CDN URL (TIER_STACK, 26dp) so this shares
     * L2/L3 entries with anything else that resolves to that tier, plus
     * the same AvatarCacheAnalytics recording every other avatar surface
     * feeds into.
     */
    public void bind(List<String> photoUrls, int totalCount) {
        mTotalCount = totalCount;
        mBitmaps.clear();
        invalidate();
        final int generation = ++mBindGeneration;

        int visible = Math.min(photoUrls.size(), MAX_VISIBLE);
        int size = AvatarUrlBuilder.tierPx(getContext(), CommunityAvatarBinder.TIER_STACK);
        for (int i = 0; i < visible; i++) {
            String rawUrl = photoUrls.get(i);
            if (rawUrl == null || rawUrl.isEmpty()) { mBitmaps.add(null); continue; }
            final int index = i;
            final String url = CommunityAvatarBinder.url(getContext(), rawUrl, CommunityAvatarBinder.TIER_STACK);
            if (url == null) { mBitmaps.add(null); continue; }

            // FIX #5 (onTrimMemory / L2 cache): per-module cache that
            // survives TRIM_MEMORY_MODERATE (see ChatAvatarL2Cache) — a
            // warm restart right after routine backgrounding often still
            // hits here even when Glide's own memory cache was trimmed,
            // so the stack repaints instantly with no Glide round-trip.
            Bitmap l2Hit = ChatAvatarL2Cache.get(getContext()).get(url);
            if (l2Hit != null) {
                mBitmaps.add(l2Hit);
                AvatarCacheAnalytics.getInstance(getContext()).record(AvatarCacheAnalytics.Tier.L2_MEMORY);
                continue;
            }

            mBitmaps.add(null); // placeholder slot, filled in asynchronously below

            // FIX (L3 disk tier): covers process death, which L2 (in-memory)
            // can't. Fired in parallel with the Glide load below — if the
            // disk read wins the race AND this row hasn't been recycled to
            // a different bind() since, paint it immediately; Glide is
            // still the source of truth and will simply overwrite it when
            // it resolves (a no-op visually if the bytes match).
            ChatAvatarL2Cache.l3(getContext()).getAsync(url, l3Bmp -> {
                if (l3Bmp == null || generation != mBindGeneration) return;
                while (mBitmaps.size() <= index) mBitmaps.add(null);
                mBitmaps.set(index, l3Bmp);
                ChatAvatarL2Cache.get(getContext()).put(url, l3Bmp); // warm L2 too
                invalidate();
            });

            Glide.with(getContext()).asBitmap().load(url)
                    .apply(new RequestOptions()
                            .format(CommunityAvatarBinder.AVATAR_FORMAT)
                            .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                            .override(size, size))
                    .circleCrop()
                    .listener(new com.bumptech.glide.request.RequestListener<Bitmap>() {
                        @Override
                        public boolean onLoadFailed(com.bumptech.glide.load.engine.GlideException e, Object model,
                                                     com.bumptech.glide.request.target.Target<Bitmap> target, boolean isFirstResource) {
                            return false;
                        }
                        @Override
                        public boolean onResourceReady(Bitmap resource, Object model,
                                                        com.bumptech.glide.request.target.Target<Bitmap> target,
                                                        com.bumptech.glide.load.DataSource dataSource, boolean isFirstResource) {
                            AvatarCacheAnalytics.getInstance(getContext())
                                    .record(AvatarCacheAnalytics.fromGlideDataSource(dataSource));
                            return false;
                        }
                    })
                    .into(new CustomTarget<Bitmap>(size, size) {
                        @Override
                        public void onResourceReady(@androidx.annotation.NonNull Bitmap resource,
                                                     @Nullable Transition<? super Bitmap> transition) {
                            if (generation != mBindGeneration) return; // recycled to a different bind() since
                            while (mBitmaps.size() <= index) mBitmaps.add(null);
                            mBitmaps.set(index, resource);
                            ChatAvatarL2Cache.get(getContext()).put(url, resource);
                            ChatAvatarL2Cache.l3(getContext()).put(url, resource); // persist for next cold start
                            invalidate();
                        }
                        @Override public void onLoadCleared(@Nullable android.graphics.drawable.Drawable placeholder) {}
                    });
        }
        requestLayout();
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
