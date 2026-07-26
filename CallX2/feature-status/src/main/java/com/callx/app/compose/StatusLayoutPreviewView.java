package com.callx.app.compose;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.View.MeasureSpec;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    // v221: per-photo finger zoom/pan state, keyed by Uri so a user's
    // adjustment survives switching layout styles / rebuildCells().
    private final Map<Uri, PhotoAdjust> photoAdjustMap = new HashMap<>();

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
                // Filled slot — show photo via Glide, with finger zoom/pan
                // (v221: "adjust with finger" — ScaleType.MATRIX instead of
                // CENTER_CROP so we can drive the image's transform
                // ourselves from pinch + drag gestures).
                final Uri uri = mediaUris.get(i);
                final PhotoAdjust adjust = photoAdjustMap.computeIfAbsent(uri, u -> new PhotoAdjust());
                ImageView iv = new ImageView(getContext());
                iv.setScaleType(ImageView.ScaleType.MATRIX);
                Glide.with(getContext())
                        .load(uri)
                        .listener(new RequestListener<Drawable>() {
                            @Override public boolean onLoadFailed(@Nullable GlideException e, Object model,
                                                                   Target<Drawable> target, boolean isFirstResource) {
                                return false;
                            }
                            @Override public boolean onResourceReady(Drawable resource, Object model,
                                                                      Target<Drawable> target, DataSource dataSource,
                                                                      boolean isFirstResource) {
                                // View may not have its final size yet the
                                // instant the bitmap decodes — post() waits
                                // for the layout pass placeView() drives.
                                iv.post(() -> applyPhotoMatrix(iv, adjust));
                                return false; // let Glide still set the drawable
                            }
                        })
                        .into(iv);
                setupPinchPanTouch(iv, adjust);
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
        // BUG FIX: the cell FrameLayout is added with default WRAP_CONTENT
        // LayoutParams, and its inner ImageView is MATCH_PARENT. Since we
        // position cells manually here (onLayout), the framework's own
        // measure pass never gives the cell a real size to hand down —
        // it measures wrap-content-with-match-parent-child as 0x0, so the
        // photo ImageView inside gets laid out at 0x0 regardless of the
        // rect we place the outer cell at. This is the same class of bug
        // already fixed in MediaGridAdapter (item_layout_picker_media.xml
        // 0dp-height cells) — same fix here: explicitly measure the child
        // EXACTLY to the target rect before laying it out, so its own
        // FrameLayout.onMeasure() correctly hands MATCH_PARENT dimensions
        // down to the photo ImageView instead of collapsing to zero.
        int w = r - l;
        int h = b - t;
        child.measure(
                MeasureSpec.makeMeasureSpec(Math.max(w, 0), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.max(h, 0), MeasureSpec.EXACTLY));
        child.layout(l, t, r, b);
    }

    // ── v221: Finger adjust (pinch-zoom + drag-pan) for the photo inside a cell ──

    /** Per-photo user zoom/pan, keyed by Uri in {@link #photoAdjustMap}. */
    private static class PhotoAdjust {
        float scale = 1f; // extra zoom on top of cover-fit, clamped 1f..3f
        float panX  = 0f; // -1..1 — fraction of the max horizontal pan available at current scale
        float panY  = 0f; // -1..1 — fraction of the max vertical pan available at current scale
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    /** Rebuilds iv's image matrix from its drawable's intrinsic size + the cell's current size + adjust. */
    private void applyPhotoMatrix(ImageView iv, PhotoAdjust adjust) {
        Drawable d = iv.getDrawable();
        int vw = iv.getWidth(), vh = iv.getHeight();
        if (d == null || vw == 0 || vh == 0) return;
        int bw = d.getIntrinsicWidth(), bh = d.getIntrinsicHeight();
        if (bw <= 0 || bh <= 0) return;

        // Cover-fit base scale (like CENTER_CROP), then the user's extra pinch zoom on top.
        float baseScale = Math.max(vw / (float) bw, vh / (float) bh);
        float scale = baseScale * adjust.scale;
        float scaledW = bw * scale, scaledH = bh * scale;
        float maxPanX = Math.max(0f, (scaledW - vw) / 2f);
        float maxPanY = Math.max(0f, (scaledH - vh) / 2f);

        float dx = (vw - scaledW) / 2f + adjust.panX * maxPanX;
        float dy = (vh - scaledH) / 2f + adjust.panY * maxPanY;

        Matrix m = new Matrix();
        m.setScale(scale, scale);
        m.postTranslate(dx, dy);
        iv.setImageMatrix(m);
    }

    /** Attaches pinch-to-zoom + one-finger drag-to-pan on iv, updating adjust and re-applying the matrix live. */
    private void setupPinchPanTouch(ImageView iv, PhotoAdjust adjust) {
        ScaleGestureDetector scaleDetector = new ScaleGestureDetector(getContext(),
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override public boolean onScale(ScaleGestureDetector detector) {
                        adjust.scale = clamp(adjust.scale * detector.getScaleFactor(), 1f, 3f);
                        applyPhotoMatrix(iv, adjust);
                        return true;
                    }
                });

        final float[] lastTouch = new float[2];
        iv.setOnTouchListener((v, event) -> {
            scaleDetector.onTouchEvent(event);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN:
                    lastTouch[0] = event.getRawX();
                    lastTouch[1] = event.getRawY();
                    // Don't let the outer ScrollView/RecyclerView steal the
                    // gesture while the user is adjusting a photo.
                    if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                    break;
                case MotionEvent.ACTION_MOVE:
                    float nowX = event.getRawX(), nowY = event.getRawY();
                    if (!scaleDetector.isInProgress() && event.getPointerCount() == 1) {
                        float dx = nowX - lastTouch[0];
                        float dy = nowY - lastTouch[1];
                        Drawable d = iv.getDrawable();
                        int vw = iv.getWidth(), vh = iv.getHeight();
                        if (d != null && vw > 0 && vh > 0) {
                            float baseScale = Math.max(vw / (float) d.getIntrinsicWidth(),
                                    vh / (float) d.getIntrinsicHeight());
                            float scale = baseScale * adjust.scale;
                            float maxPanX = Math.max(0f, (d.getIntrinsicWidth() * scale - vw) / 2f);
                            float maxPanY = Math.max(0f, (d.getIntrinsicHeight() * scale - vh) / 2f);
                            if (maxPanX > 0f) adjust.panX = clamp(adjust.panX + dx / maxPanX, -1f, 1f);
                            if (maxPanY > 0f) adjust.panY = clamp(adjust.panY + dy / maxPanY, -1f, 1f);
                            applyPhotoMatrix(iv, adjust);
                        }
                    }
                    lastTouch[0] = nowX;
                    lastTouch[1] = nowY;
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(false);
                    break;
            }
            return true;
        });
    }
}
