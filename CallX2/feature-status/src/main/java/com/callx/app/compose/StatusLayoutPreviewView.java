package com.callx.app.compose;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;

import java.util.ArrayList;
import java.util.List;

/**
 * StatusLayoutPreviewView — WhatsApp-style multi-photo layout preview.
 *
 * Supports 6 layout styles matching WhatsApp's story layout selector:
 *   STYLE_GRID_2X2   — 4-cell equal 2×2 grid
 *   STYLE_BIG_LEFT   — 1 large left cell + 2 stacked right
 *   STYLE_COLUMNS_2  — 2 equal vertical columns
 *   STYLE_BIG_TOP    — 1 large top cell + 2 bottom cells
 *   STYLE_BIG_RIGHT  — 2 stacked left + 1 large right cell
 *   STYLE_GRID_3     — 1 wide top cell + 2 bottom cells (3-photo collage)
 *
 * Dynamically lays out ImageViews filled via Glide based on the selected
 * media URIs and the chosen layout style. Empty slots show a placeholder
 * "+" icon for adding more photos.
 */
public class StatusLayoutPreviewView extends FrameLayout {

    public static final int STYLE_GRID_2X2   = 0; // 2×2 equal grid (4 cells)
    public static final int STYLE_BIG_LEFT   = 1; // 1 large left + 2 right stacked
    public static final int STYLE_COLUMNS_2  = 2; // 2 equal columns
    public static final int STYLE_BIG_TOP    = 3; // 1 big top + 2 bottom
    public static final int STYLE_BIG_RIGHT  = 4; // 2 left stacked + 1 large right
    public static final int STYLE_GRID_3     = 5; // 1 wide top + 2 equal bottom (3-cell)

    private static final int GAP_DP = 3;

    private int layoutStyle = STYLE_GRID_2X2;
    private final List<Uri> mediaUris = new ArrayList<>();
    private OnAddSlotClickListener addSlotListener;

    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;

    public interface OnAddSlotClickListener {
        void onAddSlotClick(int slotIndex);
    }

    public StatusLayoutPreviewView(@NonNull Context context) {
        this(context, null);
    }

    public StatusLayoutPreviewView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
        density = context.getResources().getDisplayMetrics().density;
        borderPaint.setColor(Color.parseColor("#1A1A1A"));
        borderPaint.setStyle(Paint.Style.FILL);
    }

    public void setOnAddSlotClickListener(OnAddSlotClickListener l) {
        this.addSlotListener = l;
    }

    public void setLayoutStyle(int style) {
        this.layoutStyle = style;
        rebuildCells();
    }

    public void setMediaUris(List<Uri> uris) {
        this.mediaUris.clear();
        this.mediaUris.addAll(uris);
        rebuildCells();
    }

    /** Returns number of media slots for the current layout style. */
    public int getSlotCount() {
        switch (layoutStyle) {
            case STYLE_COLUMNS_2:  return 2;
            case STYLE_BIG_LEFT:
            case STYLE_BIG_TOP:
            case STYLE_BIG_RIGHT:
            case STYLE_GRID_3:     return 3;
            case STYLE_GRID_2X2:
            default:               return 4;
        }
    }

    private void rebuildCells() {
        removeAllViews();
        int slots = getSlotCount();
        for (int i = 0; i < slots; i++) {
            final int slotIdx = i;
            FrameLayout cell = new FrameLayout(getContext());
            cell.setClipToOutline(true);

            if (i < mediaUris.size()) {
                // Filled slot — show photo via Glide
                ImageView iv = new ImageView(getContext());
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                Glide.with(getContext())
                        .load(mediaUris.get(i))
                        .centerCrop()
                        .into(iv);
                cell.addView(iv, new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
            } else {
                // Empty slot — dark bg + "+" icon
                cell.setBackgroundColor(Color.parseColor("#1E1E1E"));
                ImageView plus = new ImageView(getContext());
                plus.setImageResource(android.R.drawable.ic_input_add);
                plus.setColorFilter(Color.parseColor("#888888"));
                int iconSizePx = (int) (32 * density);
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(iconSizePx, iconSizePx);
                lp.gravity = android.view.Gravity.CENTER;
                cell.addView(plus, lp);
                cell.setOnClickListener(v -> {
                    if (addSlotListener != null) addSlotListener.onAddSlotClick(slotIdx);
                });
            }
            addView(cell);
        }
        requestLayout();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int w = right - left;
        int h = bottom - top;
        int gap = (int) (GAP_DP * density);

        switch (layoutStyle) {
            case STYLE_GRID_2X2:   layoutGrid2x2(w, h, gap);   break;
            case STYLE_BIG_LEFT:   layoutBigLeft(w, h, gap);    break;
            case STYLE_COLUMNS_2:  layoutColumns2(w, h, gap);   break;
            case STYLE_BIG_TOP:    layoutBigTop(w, h, gap);     break;
            case STYLE_BIG_RIGHT:  layoutBigRight(w, h, gap);   break;
            case STYLE_GRID_3:     layoutGrid3(w, h, gap);      break;
            default:               layoutGrid2x2(w, h, gap);    break;
        }
    }

    // ── Layout style implementations ─────────────────────────────────────

    /** 2×2 equal grid: cells [0,1,2,3] */
    private void layoutGrid2x2(int w, int h, int gap) {
        int hw = (w - gap) / 2;
        int hh = (h - gap) / 2;
        placeView(0, 0,       0,       hw,       hh);
        placeView(1, hw+gap,  0,       w,        hh);
        placeView(2, 0,       hh+gap,  hw,       h);
        placeView(3, hw+gap,  hh+gap,  w,        h);
    }

    /** Large left cell + 2 stacked right: cells [0=big-left, 1=top-right, 2=bot-right] */
    private void layoutBigLeft(int w, int h, int gap) {
        int leftW = (int)(w * 0.55f) - gap / 2;
        int rightW = w - leftW - gap;
        int hh = (h - gap) / 2;
        placeView(0, 0,         0,       leftW,  h);
        placeView(1, leftW+gap, 0,       w,      hh);
        placeView(2, leftW+gap, hh+gap,  w,      h);
    }

    /** 2 equal vertical columns: cells [0=left, 1=right] */
    private void layoutColumns2(int w, int h, int gap) {
        int hw = (w - gap) / 2;
        placeView(0, 0,      0, hw,  h);
        placeView(1, hw+gap, 0, w,   h);
    }

    /** 1 big top + 2 equal bottom: cells [0=big-top, 1=bot-left, 2=bot-right] */
    private void layoutBigTop(int w, int h, int gap) {
        int topH = (int)(h * 0.55f) - gap / 2;
        int botH = h - topH - gap;
        int hw = (w - gap) / 2;
        placeView(0, 0,      0,       w,   topH);
        placeView(1, 0,      topH+gap, hw, h);
        placeView(2, hw+gap, topH+gap, w,  h);
    }

    /** 2 stacked left + 1 large right: cells [0=top-left, 1=bot-left, 2=big-right] */
    private void layoutBigRight(int w, int h, int gap) {
        int rightW = (int)(w * 0.55f);
        int leftW = w - rightW - gap;
        int hh = (h - gap) / 2;
        placeView(0, 0,        0,      leftW, hh);
        placeView(1, 0,        hh+gap, leftW, h);
        placeView(2, leftW+gap,0,      w,     h);
    }

    /** 1 wide top + 2 equal bottom (classic 3-cell): cells [0=top, 1=bot-left, 2=bot-right] */
    private void layoutGrid3(int w, int h, int gap) {
        int topH = (int)(h * 0.5f) - gap / 2;
        int botH = h - topH - gap;
        int hw = (w - gap) / 2;
        placeView(0, 0,      0,       w,   topH);
        placeView(1, 0,      topH+gap, hw, h);
        placeView(2, hw+gap, topH+gap, w,  h);
    }

    private void placeView(int index, int l, int t, int r, int b) {
        if (index >= getChildCount()) return;
        View child = getChildAt(index);
        child.layout(l, t, r, b);
    }
}
