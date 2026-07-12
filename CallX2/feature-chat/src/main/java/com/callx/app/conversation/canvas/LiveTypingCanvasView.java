package com.callx.app.conversation.canvas;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * LiveTypingCanvasView — Canvas-rendered replacement for the
 * ll_live_typing_preview LinearLayout (tv_live_typing_label +
 * tv_live_typing_preview).
 *
 * WHY THIS ONE IS DIFFERENT FROM EmojiBurstCanvasView: the emoji burst
 * overlay could be made fully size-independent of its content (fixed
 * match_parent), so it never needs a layout pass at all. This bubble
 * genuinely can't be — it visually grows/shrinks with the partner's
 * in-progress draft text, exactly like the TextView it replaces, up to a
 * 220dp cap and 3 lines. That's inherent to the feature, not something
 * Canvas rendering can remove. What Canvas rendering DOES remove:
 *
 *  1. ONE VIEW INSTEAD OF THREE — the old markup was a LinearLayout with
 *     two child TextViews (label + preview), which meant a ViewGroup
 *     measure/layout pass PLUS two independent TextView measure/layout/
 *     draw passes on every keystroke the partner made. This is a single
 *     View: one onMeasure(), one onDraw(), both texts painted directly.
 *
 *  2. StaticLayout BUILT ONCE PER ACTUAL CHANGE, NOT PER PASS — a TextView
 *     re-derives/validates its internal Layout on measure AND on layout
 *     AND keeps extra bookkeeping (Editable, BoringLayout checks, ellipsize
 *     handling, accessibility text nodes) that has nothing to do with
 *     actually drawing pixels. setPreviewText() below builds the
 *     StaticLayout exactly once per genuinely new string and caches both
 *     it and its natural (shrink-to-fit) width — onMeasure() and onDraw()
 *     just read those cached fields.
 *
 *  3. DEDUPED UPDATES — Firebase's ValueEventListener can redeliver the
 *     same string (e.g. on reconnect/resync). setPreviewText() no-ops
 *     immediately on an unchanged value instead of rebuilding a layout and
 *     requesting a new pass for nothing.
 *
 * Growing/shrinking with content means this view DOES call requestLayout()
 * when the text genuinely changes — same as the TextView it replaces —
 * but only once per real change instead of the TextView's internal churn,
 * and it's an isolated FrameLayout sibling of the RecyclerView (see
 * activity_chat.xml), so that layout pass never touches the message list.
 */
public class LiveTypingCanvasView extends View {

    private static final String LABEL_TEXT = "typing live";
    private static final int MAX_LINES = 3;
    private static final float MAX_WIDTH_DP = 220f;
    private static final float LABEL_TEXT_SIZE_SP = 9f;
    private static final float PREVIEW_TEXT_SIZE_SP = 13f;
    private static final float LABEL_PREVIEW_GAP_DP = 2f;

    private final TextPaint labelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint previewPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint.FontMetrics labelMetrics = new Paint.FontMetrics();

    private int maxWidthPx;
    private int labelGapPx;
    private float labelWidth;
    private int labelHeightPx;

    private String currentText;
    /** Built once per genuine text change, reused across every subsequent
     *  measure/layout/draw call until the text changes again. */
    private StaticLayout previewLayout;
    private float previewNaturalWidth;

    // Last content footprint (padding excluded) this view actually
    // requested a layout pass for — lets setPreviewText() skip
    // requestLayout() on keystrokes that change the text but not the
    // rendered size (e.g. once a multi-line draft is already wrapped at
    // the 220dp cap, most further characters only add glyphs, not rows).
    private int lastContentWidth = -1;
    private int lastContentHeight = -1;

    public LiveTypingCanvasView(Context context) {
        super(context);
        init();
    }

    public LiveTypingCanvasView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public LiveTypingCanvasView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        labelPaint.setColor(Color.parseColor("#C7D2FE"));
        labelPaint.setFakeBoldText(true);
        labelPaint.setTextSize(spToPx(LABEL_TEXT_SIZE_SP));
        labelPaint.getFontMetrics(labelMetrics);
        labelWidth = labelPaint.measureText(LABEL_TEXT);
        labelHeightPx = (int) Math.ceil(labelMetrics.descent - labelMetrics.ascent);

        previewPaint.setColor(Color.WHITE);
        previewPaint.setTextSize(spToPx(PREVIEW_TEXT_SIZE_SP));

        maxWidthPx = dpToPx(MAX_WIDTH_DP);
        labelGapPx = dpToPx(LABEL_PREVIEW_GAP_DP);

        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
    }

    private float spToPx(float sp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, getResources().getDisplayMetrics());
    }

    private int dpToPx(float dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics());
    }

    /**
     * Sets the partner's live draft text. No-ops on a value identical to
     * what's already shown (Firebase can redeliver unchanged values), and
     * otherwise rebuilds the wrapped StaticLayout exactly once.
     */
    public void setPreviewText(@Nullable String text) {
        if (text != null && text.equals(currentText)) return;
        currentText = text;

        if (text == null || text.isEmpty()) {
            previewLayout = null;
            previewNaturalWidth = 0f;
        } else {
            previewLayout = StaticLayout.Builder
                    .obtain(text, 0, text.length(), previewPaint, maxWidthPx)
                    .setMaxLines(MAX_LINES)
                    .setEllipsize(null)
                    .setIncludePad(false)
                    .build();

            float natural = 0f;
            int lines = previewLayout.getLineCount();
            for (int i = 0; i < lines; i++) {
                natural = Math.max(natural, previewLayout.getLineWidth(i));
            }
            previewNaturalWidth = natural;
        }

        // Content footprint this text needs, independent of padding (which
        // never changes at runtime) — compare against the last size we
        // actually laid out for.
        int contentWidth = (int) Math.ceil(Math.max(labelWidth, previewNaturalWidth));
        int contentHeight = labelHeightPx + (previewLayout != null ? labelGapPx + previewLayout.getHeight() : 0);

        if (contentWidth != lastContentWidth || contentHeight != lastContentHeight) {
            lastContentWidth = contentWidth;
            lastContentHeight = contentHeight;
            // Unavoidable: this view's size is content-dependent (matches
            // the wrap_content TextView bubble it replaces) — but this only
            // fires when the rendered footprint itself actually changes,
            // not on every keystroke that leaves it unchanged.
            requestLayout();
        }
        invalidate();
    }

    /** Clears the preview entirely (partner stopped typing / sent). */
    public void clearPreview() {
        setPreviewText(null);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        float contentWidth = Math.max(labelWidth, previewNaturalWidth);
        int desiredWidth = getPaddingLeft() + getPaddingRight() + (int) Math.ceil(contentWidth);

        int desiredHeight = getPaddingTop() + getPaddingBottom() + labelHeightPx;
        if (previewLayout != null) {
            desiredHeight += labelGapPx + previewLayout.getHeight();
        }

        setMeasuredDimension(
                resolveSizeAndState(desiredWidth, widthMeasureSpec, 0),
                resolveSizeAndState(desiredHeight, heightMeasureSpec, 0));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int left = getPaddingLeft();
        int top = getPaddingTop();

        float labelBaseline = top - labelMetrics.ascent;
        canvas.drawText(LABEL_TEXT, left, labelBaseline, labelPaint);

        if (previewLayout != null) {
            int saved = canvas.save();
            canvas.translate(left, top + labelHeightPx + labelGapPx);
            previewLayout.draw(canvas);
            canvas.restoreToCount(saved);
        }
    }
}
