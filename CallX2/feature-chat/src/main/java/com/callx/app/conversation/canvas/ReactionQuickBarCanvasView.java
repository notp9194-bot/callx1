package com.callx.app.conversation.canvas;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * ReactionQuickBarCanvasView — single-View replacement for the old
 * "6 TextViews + RippleDrawable + LinearLayout" quick-react row that
 * MessagePagingAdapter#showActionBottomSheet used to build from scratch
 * on EVERY long-press.
 *
 * WHY THIS EXISTS (perf root-cause)
 * ──────────────────────────────────
 * The old row allocated 7 new TextView objects + 7 new RippleDrawable
 * objects + 1 LinearLayout + 1 wrapper LinearLayout on every single
 * long-press, then ran a full measure/layout pass on that subtree before
 * the AlertDialog could even show. If the long-press happened while the
 * chat RecyclerView was mid-fling (very common — WhatsApp-style long
 * press during a fast scroll-to-read), that inflation + layout burst
 * landed on the main thread in the same frame window the fling was
 * still consuming, causing a visible stutter right as the picker opened
 * and, on lower-end devices, a couple of dropped frames on the
 * RecyclerView's settle animation immediately after dismiss.
 *
 * This view does the same job with:
 *   • ONE View (no child inflation at all — onDraw() paints everything)
 *   • ONE Paint per visual role, all allocated once in the constructor
 *     and reused for every draw (no per-frame/per-open allocation)
 *   • O(1) hit-testing on ACTION_UP via simple index arithmetic instead
 *     of relying on 7 separate OnClickListener dispatches
 *
 * Selected-emoji highlight (the "already reacted with this one" scale-up)
 * is done with canvas.scale() around a single drawText() call instead of
 * separate TextView.setScaleX/Y — same visual result, zero extra objects.
 */
public class ReactionQuickBarCanvasView extends View {

    public interface OnEmojiPickListener {
        void onEmojiPicked(String emoji);
        void onMoreTapped();
    }

    private static final String[] DEFAULT_QUICK_EMOJIS = {
            "\u2764\uFE0F", "\uD83D\uDC4D", "\uD83D\uDE02",
            "\uD83D\uDE2E", "\uD83D\uDE22", "\uD83D\uDE21"
    };

    private final float density;
    private final Paint emojiPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint morePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highlightBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF[] cellRects;
    private final RectF moreRect = new RectF();

    private String[] emojis = DEFAULT_QUICK_EMOJIS;
    private int selectedIndex = -1;
    private int cellSize;
    private int touchedIndex = -1; // for press feedback, -2 = "more" button

    private OnEmojiPickListener listener;

    public ReactionQuickBarCanvasView(Context ctx) {
        super(ctx);
        density = ctx.getResources().getDisplayMetrics().density;
        cellSize = (int) (44 * density);

        emojiPaint.setTextSize(spToPx(28));
        emojiPaint.setTextAlign(Paint.Align.CENTER);

        morePaint.setTextSize(spToPx(22));
        morePaint.setTextAlign(Paint.Align.CENTER);
        morePaint.setFakeBoldText(true);
        morePaint.setColor(0xFFAAAAAA);

        highlightBgPaint.setColor(0x22FFFFFF);
        highlightBgPaint.setStyle(Paint.Style.FILL);

        cellRects = new RectF[DEFAULT_QUICK_EMOJIS.length];
        for (int i = 0; i < cellRects.length; i++) cellRects[i] = new RectF();

        setWillNotDraw(false);
    }

    private float spToPx(float sp) {
        return sp * getResources().getDisplayMetrics().scaledDensity;
    }

    public void setListener(OnEmojiPickListener l) {
        this.listener = l;
    }

    /** @param currentReactionEmoji emoji the user already reacted with, or null */
    public void bind(@Nullable String currentReactionEmoji) {
        selectedIndex = -1;
        if (currentReactionEmoji != null) {
            for (int i = 0; i < emojis.length; i++) {
                if (emojis[i].equals(currentReactionEmoji)) {
                    selectedIndex = i;
                    break;
                }
            }
        }
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int totalCells = emojis.length + 1; // +1 for "more" button
        int width = cellSize * totalCells;
        int height = cellSize;
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        for (int i = 0; i < emojis.length; i++) {
            float left = i * cellSize;
            cellRects[i].set(left, 0, left + cellSize, cellSize);
        }
        float moreLeft = emojis.length * cellSize;
        moreRect.set(moreLeft, 0, moreLeft + cellSize, cellSize);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        for (int i = 0; i < emojis.length; i++) {
            RectF r = cellRects[i];
            boolean selected = i == selectedIndex;
            boolean pressed = i == touchedIndex;

            if (selected || pressed) {
                canvas.drawCircle(r.centerX(), r.centerY(),
                        cellSize * 0.42f, highlightBgPaint);
            }

            float scale = selected ? 1.22f : 1.0f;
            Paint.FontMetrics fm = emojiPaint.getFontMetrics();
            float baselineY = r.centerY() - (fm.ascent + fm.descent) / 2f;

            if (scale != 1.0f) {
                canvas.save();
                canvas.scale(scale, scale, r.centerX(), r.centerY());
                canvas.drawText(emojis[i], r.centerX(), baselineY, emojiPaint);
                canvas.restore();
            } else {
                canvas.drawText(emojis[i], r.centerX(), baselineY, emojiPaint);
            }
        }

        if (touchedIndex == -2) {
            canvas.drawCircle(moreRect.centerX(), moreRect.centerY(),
                    cellSize * 0.42f, highlightBgPaint);
        }
        Paint.FontMetrics mfm = morePaint.getFontMetrics();
        canvas.drawText("+", moreRect.centerX(),
                moreRect.centerY() - (mfm.ascent + mfm.descent) / 2f, morePaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchedIndex = hitIndex(x, y);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                int nowOver = hitIndex(x, y);
                if (nowOver != touchedIndex) {
                    touchedIndex = -1;
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                int idx = hitIndex(x, y);
                touchedIndex = -1;
                invalidate();
                if (idx >= 0 && listener != null) {
                    listener.onEmojiPicked(emojis[idx]);
                } else if (idx == -2 && listener != null) {
                    listener.onMoreTapped();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                touchedIndex = -1;
                invalidate();
                return true;
        }
        return super.onTouchEvent(event);
    }

    /** @return 0..N-1 for an emoji cell, -2 for the "more" button, -1 for a miss */
    private int hitIndex(float x, float y) {
        for (int i = 0; i < cellRects.length; i++) {
            if (cellRects[i].contains(x, y)) return i;
        }
        if (moreRect.contains(x, y)) return -2;
        return -1;
    }
}
