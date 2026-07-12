package com.callx.app.conversation.canvas;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

/**
 * ReactionGridCanvasView — replaces MessagePagingAdapter#showFullEmojiPicker's
 * old GridView + ArrayAdapter<String>(android.R.layout.simple_list_item_1).
 *
 * The old picker created a TextView per visible cell (recycled by GridView
 * as you scroll, but still 24-32 concurrent inflate+bind passes the moment
 * the dialog opened — a real cost tapping into MessageInfoActivity's
 * regression pattern: a burst of view inflation firing on the main thread
 * at the exact moment the chat list is settling from the long-press).
 *
 * This view draws all 80 emojis directly with a handful of drawText() calls
 * in a single onDraw() pass — no adapter, no recycler, no child Views at
 * all. It's meant to sit inside a plain ScrollView (ScrollView itself is
 * cheap — one child, no recycling machinery) so the "scrollable grid" UX
 * is unchanged but the per-open cost drops from ~30 inflations to zero.
 *
 * Hit-testing on tap is O(1) integer division (row/col from x/y), not a
 * per-child listener dispatch.
 */
public class ReactionGridCanvasView extends View {

    public interface OnEmojiPickListener {
        void onEmojiPicked(String emoji);
    }

    private static final String[] ALL_EMOJIS = {
            "\u2764\uFE0F","\uD83D\uDC4D","\uD83D\uDE02","\uD83D\uDE2E","\uD83D\uDE22","\uD83D\uDE21","\uD83D\uDE4F","\uD83D\uDD25","\u2705","\uD83D\uDCAF",
            "\uD83D\uDC4F","\uD83E\uDD23","\uD83D\uDE0D","\uD83D\uDE0E","\uD83E\uDD14","\uD83D\uDE34","\uD83E\uDD73","\uD83D\uDE05","\uD83E\uDD29","\uD83E\uDD70",
            "\uD83D\uDC80","\uD83E\uDD2F","\uD83D\uDE31","\uD83E\uDD17","\uD83D\uDE07","\uD83D\uDE44","\uD83D\uDE11","\uD83E\uDD10","\uD83E\uDEE1","\uD83D\uDCAA",
            "\uD83D\uDC40","\u270C\uFE0F","\uD83E\uDD1E","\uD83E\uDEF6","\u2764\uFE0F\u200D\uD83D\uDD25","\uD83D\uDC94","\uD83D\uDC95","\uD83D\uDC96","\uD83D\uDC98","\uD83E\uDEC2",
            "\uD83C\uDF89","\uD83C\uDF8A","\uD83C\uDF88","\uD83C\uDFC6","\u2B50","\uD83C\uDF1F","\uD83D\uDCAB","\u2728","\uD83C\uDF08","\u2600\uFE0F",
            "\uD83D\uDE01","\uD83D\uDE06","\uD83E\uDD2D","\uD83D\uDE1C","\uD83D\uDE1D","\uD83E\uDD79","\uD83E\uDD7A","\uD83D\uDE2D","\uD83D\uDE24","\uD83D\uDE20",
            "\uD83D\uDC4B","\uD83E\uDD19","\uD83D\uDD90\uFE0F","\u270B","\uD83D\uDC4A","\uD83E\uDEF8","\uD83D\uDC85","\uD83E\uDEF0","\uD83D\uDC4C","\uD83E\uDD0C",
            "\uD83D\uDE4C","\uD83E\uDD1C","\uD83E\uDD1B","\uD83E\uDEF5","\u261D\uFE0F","\uD83D\uDC48","\uD83D\uDC49","\uD83D\uDC46","\uD83D\uDC47","\uD83E\uDD37"
    };

    private static final int COLUMNS = 8;

    private final Paint emojiPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pressBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int cellSize;
    private int pressedRow = -1, pressedCol = -1;

    private OnEmojiPickListener listener;

    public ReactionGridCanvasView(Context ctx) {
        super(ctx);
        cellSize = (int) (44 * ctx.getResources().getDisplayMetrics().density);
        emojiPaint.setTextSize(spToPx(26));
        emojiPaint.setTextAlign(Paint.Align.CENTER);
        pressBgPaint.setColor(0x22FFFFFF);
        setWillNotDraw(false);
    }

    private float spToPx(float sp) {
        return sp * getResources().getDisplayMetrics().scaledDensity;
    }

    public void setListener(OnEmojiPickListener l) {
        this.listener = l;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        if (width <= 0) width = cellSize * COLUMNS;
        cellSize = width / COLUMNS;
        int rows = (int) Math.ceil(ALL_EMOJIS.length / (float) COLUMNS);
        int height = cellSize * rows;
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Paint.FontMetrics fm = emojiPaint.getFontMetrics();

        for (int i = 0; i < ALL_EMOJIS.length; i++) {
            int row = i / COLUMNS;
            int col = i % COLUMNS;
            float cx = col * cellSize + cellSize / 2f;
            float cy = row * cellSize + cellSize / 2f;

            if (row == pressedRow && col == pressedCol) {
                canvas.drawCircle(cx, cy, cellSize * 0.42f, pressBgPaint);
            }
            canvas.drawText(ALL_EMOJIS[i], cx, cy - (fm.ascent + fm.descent) / 2f, emojiPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int col = (int) (event.getX() / cellSize);
        int row = (int) (event.getY() / cellSize);
        int index = row * COLUMNS + col;
        boolean valid = col >= 0 && col < COLUMNS && index >= 0 && index < ALL_EMOJIS.length;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (valid) { pressedRow = row; pressedCol = col; invalidate(); }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!valid || row != pressedRow || col != pressedCol) {
                    if (pressedRow != -1) { pressedRow = -1; pressedCol = -1; invalidate(); }
                }
                return true;
            case MotionEvent.ACTION_UP:
                pressedRow = -1; pressedCol = -1;
                invalidate();
                if (valid && listener != null) listener.onEmojiPicked(ALL_EMOJIS[index]);
                return true;
            case MotionEvent.ACTION_CANCEL:
                pressedRow = -1; pressedCol = -1;
                invalidate();
                return true;
        }
        return super.onTouchEvent(event);
    }
}
