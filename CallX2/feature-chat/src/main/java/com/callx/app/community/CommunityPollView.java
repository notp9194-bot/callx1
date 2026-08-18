package com.callx.app.community;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import com.callx.app.community.CommunityPoll;

import java.util.ArrayList;
import java.util.List;

/**
 * CommunityPollView — custom Canvas View that draws a poll's options
 * as animated horizontal progress bars with vote percentages.
 *
 * Public API:
 *   bind(CommunityPoll, Integer myVoteIndex, OnVoteListener) — call this
 *   to render/re-render the poll. Tapping an option fires OnVoteListener
 *   only when myVoteIndex is null (user hasn't voted yet).
 *
 * Animation: each bar animates from its previous percentage to the new
 * one over 400ms using ValueAnimator + invalidate(). Re-bind after a vote
 * animates from old → new, not from 0.
 */
public class CommunityPollView extends View {

    public interface OnVoteListener {
        void onVote(int optionIndex);
    }

    // ── Drawing constants ─────────────────────────────────────────────────
    private static final float BAR_HEIGHT_DP    = 44f;
    private static final float BAR_SPACING_DP   = 10f;
    private static final float CORNER_RADIUS_DP = 8f;
    private static final float TEXT_SIZE_DP     = 13f;
    private static final float PADDING_DP       = 8f;

    // ── Paint objects ─────────────────────────────────────────────────────
    private final Paint mTrackPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFillPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTextPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mPctPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);

    // ── State ─────────────────────────────────────────────────────────────
    private CommunityPoll        mPoll;
    private Integer              mMyVoteIndex;
    private OnVoteListener       mListener;

    /** Animated fill fraction per option (0.0 – 1.0) */
    private float[]              mAnimFractions = new float[0];
    /** Previous percentages — so re-bind animates from old → new */
    private float[]              mPrevFractions = new float[0];

    private final List<RectF>    mBarRects    = new ArrayList<>();
    private ValueAnimator        mAnimator;

    private final GestureDetector mGestureDetector;
    private float mDensity;

    public CommunityPollView(Context context) {
        this(context, null);
    }

    public CommunityPollView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CommunityPollView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mDensity = context.getResources().getDisplayMetrics().density;

        mTrackPaint.setColor(0xFFE2E8F0);
        mFillPaint.setColor(0xFF4CAF50);   // brand_primary
        mTextPaint.setColor(0xFF0F172A);   // text_primary
        mTextPaint.setTextSize(TEXT_SIZE_DP * mDensity);
        mPctPaint.setColor(0xFF64748B);    // text_secondary
        mPctPaint.setTextSize(TEXT_SIZE_DP * mDensity);
        mPctPaint.setTextAlign(Paint.Align.RIGHT);

        mGestureDetector = new GestureDetector(context,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onSingleTapUp(MotionEvent e) {
                        handleTap(e.getX(), e.getY());
                        return true;
                    }
                });
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Bind or re-bind this view to a new poll state.
     * myVoteIndex == null → user hasn't voted (bars are tappable).
     * myVoteIndex != null → results-only mode (bars not tappable).
     */
    public void bind(CommunityPoll poll, @Nullable Integer myVoteIndex,
                     @Nullable OnVoteListener listener) {
        if (poll == null) {
            setVisibility(GONE);
            return;
        }
        setVisibility(VISIBLE);

        int optCount = poll.options.size();

        // Capture previous fractions for animation start
        float[] prevFrac = new float[optCount];
        if (mAnimFractions.length == optCount) {
            System.arraycopy(mAnimFractions, 0, prevFrac, 0, optCount);
        }

        // Build target fractions
        float[] targetFrac = new float[optCount];
        for (int i = 0; i < optCount; i++) {
            targetFrac[i] = poll.percentFor(i) / 100f;
        }

        mPoll        = poll;
        mMyVoteIndex = myVoteIndex;
        mListener    = listener;
        mPrevFractions = prevFrac;

        // Stop any running animation
        if (mAnimator != null && mAnimator.isRunning()) mAnimator.cancel();

        // Initialise current fractions at "prev" — animator will move to target
        mAnimFractions = prevFrac.clone();

        mAnimator = ValueAnimator.ofFloat(0f, 1f);
        mAnimator.setDuration(400);
        final float[] from   = prevFrac;
        final float[] to     = targetFrac;
        final int     count  = optCount;
        mAnimator.addUpdateListener(anim -> {
            float t = (float) anim.getAnimatedValue();
            for (int i = 0; i < count; i++) {
                mAnimFractions[i] = from[i] + (to[i] - from[i]) * t;
            }
            invalidate();
        });
        mAnimator.start();

        requestLayout();
    }

    // ── Measurement ───────────────────────────────────────────────────────

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int optCount = (mPoll != null) ? mPoll.options.size() : 0;
        float barH    = BAR_HEIGHT_DP    * mDensity;
        float spacing = BAR_SPACING_DP   * mDensity;
        float pad     = PADDING_DP       * mDensity;

        float totalH = pad + optCount * barH + Math.max(0, optCount - 1) * spacing + pad;
        int h = (int) Math.ceil(totalH);
        int w = MeasureSpec.getSize(widthMeasureSpec);
        setMeasuredDimension(w, h);
    }

    // ── Drawing ───────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mPoll == null || mPoll.options.isEmpty()) return;

        float density  = mDensity;
        float barH     = BAR_HEIGHT_DP    * density;
        float spacing  = BAR_SPACING_DP   * density;
        float corner   = CORNER_RADIUS_DP * density;
        float pad      = PADDING_DP       * density;
        float w        = getWidth();

        mBarRects.clear();

        for (int i = 0; i < mPoll.options.size(); i++) {
            CommunityPoll.Option opt = mPoll.options.get(i);
            float top  = pad + i * (barH + spacing);
            float bot  = top + barH;

            // Track (background)
            RectF trackRect = new RectF(pad, top, w - pad, bot);
            mBarRects.add(new RectF(trackRect)); // store for hit-testing
            canvas.drawRoundRect(trackRect, corner, corner, mTrackPaint);

            // Fill
            float frac    = (i < mAnimFractions.length) ? mAnimFractions[i] : 0f;
            float fillW   = (w - 2 * pad) * frac;
            if (fillW > 0) {
                RectF fillRect = new RectF(pad, top, pad + fillW, bot);
                // Chosen option gets a slightly deeper tint
                if (mMyVoteIndex != null && mMyVoteIndex == i) {
                    mFillPaint.setColor(0xFF388E3C);
                } else {
                    mFillPaint.setColor(0xFF4CAF50);
                }
                canvas.drawRoundRect(fillRect, corner, corner, mFillPaint);
            }

            // Option text (left-aligned inside bar)
            String label = opt.text != null ? opt.text : "";
            float textY  = top + barH / 2f - (mTextPaint.ascent() + mTextPaint.descent()) / 2f;
            canvas.drawText(label, pad + 10 * density, textY, mTextPaint);

            // Percentage (right-aligned)
            int pct = (i < mAnimFractions.length) ? Math.round(mAnimFractions[i] * 100) : 0;
            canvas.drawText(pct + "%", w - pad - 6 * density, textY, mPctPaint);
        }
    }

    // ── Touch ─────────────────────────────────────────────────────────────

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Pass to GestureDetector; only handle taps if user hasn't voted
        return mGestureDetector.onTouchEvent(event) || super.onTouchEvent(event);
    }

    private void handleTap(float x, float y) {
        if (mMyVoteIndex != null || mListener == null || mPoll == null) return;
        for (int i = 0; i < mBarRects.size(); i++) {
            if (mBarRects.get(i).contains(x, y)) {
                mListener.onVote(i);
                return;
            }
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mAnimator != null) mAnimator.cancel();
    }
}
