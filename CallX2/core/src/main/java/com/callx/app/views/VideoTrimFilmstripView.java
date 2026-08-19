package com.callx.app.views;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * VideoTrimFilmstripView — CapCut/Instagram-style video trim UI.
 *
 * Lives in :core so any feature module (reels editor, chat video trim, status,
 * etc.) can reuse the same trim widget instead of shipping its own copy.
 *
 * Renders a horizontal filmstrip of video-frame thumbnails inside a rounded
 * track, dims the portion outside the selected range, and shows two
 * draggable "premium" pill handles — brand pink→purple gradient fill, a
 * soft outer glow (brighter while actively dragged), a glossy top highlight,
 * and a crisp white grip glyph — bracketing the selection, plus a thin
 * gradient top/bottom bar connecting them. A blue playhead line tracks
 * current playback position inside the selection.
 *
 * Usage:
 *   trimView.setDuration(totalDurationMs);
 *   trimView.setTrimRange(startMs, endMs);
 *   trimView.loadThumbnails(context, videoUri, isFilePath, totalDurationMs);
 *   trimView.setOnTrimChangeListener(listener);
 *   trimView.setPlayheadPosition(currentMs); // call periodically during playback
 */
public class VideoTrimFilmstripView extends View {

    public interface OnTrimChangeListener {
        /** Fired continuously while a handle is being dragged. */
        void onTrimChanged(long trimStartMs, long trimEndMs, boolean fromUser);
        /** Fired once when the user lifts their finger off a handle. */
        void onTrimTouchEnd(long trimStartMs, long trimEndMs);
    }

    private static final int TOUCH_NONE  = 0;
    private static final int TOUCH_LEFT  = 1;
    private static final int TOUCH_RIGHT = 2;

    // Brand trim accent — matches core's trim_gradient_start / trim_gradient_end
    // (pink → purple) used across the rest of the trim UI (buttons, chips, etc.)
    // so the handles read as part of the same premium trim experience.
    private static final int GRADIENT_START = 0xFFFF3B5C;
    private static final int GRADIENT_END   = 0xFFA855F7;

    private final Paint dimPaint       = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleGlowPaint= new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shinePaint     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gripPaint      = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barPaint       = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playheadPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playheadGlow   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbBgPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF clipRect       = new RectF();
    private final RectF handleRect     = new RectF();
    private final RectF shineRect      = new RectF();
    private final Path  clipPath       = new Path();
    private final Path  handlePath     = new Path();

    private final List<Bitmap> thumbnails = new ArrayList<>();

    private long durationMs   = 0;
    private long trimStartMs  = 0;
    private long trimEndMs    = 0;
    private long playheadMs   = 0;

    private final float density  = getResources().getDisplayMetrics().density;
    private final float handleWidthPx = 22 * density;
    private final float cornerRadiusPx = 8 * density;
    private final float handleCornerRadiusPx = 10 * density;
    private final float barHeightPx = 4 * density;
    private final float minTrimMs = 1000;

    private int activeTouch = TOUCH_NONE;
    private float touchDownDx = 0f;

    private OnTrimChangeListener listener;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ExecutorService thumbExecutor;
    private int thumbLoadToken = 0;

    public VideoTrimFilmstripView(Context context) { this(context, null); }

    public VideoTrimFilmstripView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        dimPaint.setColor(0xB3000000); // ~70% black
        dimPaint.setStyle(Paint.Style.FILL);

        // Premium gradient handle fill (brand pink → purple), reused for both
        // handles via a local matrix translate at draw time.
        handlePaint.setStyle(Paint.Style.FILL);
        handlePaint.setShader(new LinearGradient(
                0, 0, 0, 1, // vertical, sized/positioned per-draw via local matrix
                GRADIENT_START, GRADIENT_END, Shader.TileMode.CLAMP));

        // Soft colored glow behind each handle so it reads clearly against any
        // thumbnail — brightens further while the handle is actively dragged.
        handleGlowPaint.setStyle(Paint.Style.FILL);
        handleGlowPaint.setColor(GRADIENT_END);
        handleGlowPaint.setAlpha(110);

        // Glossy top highlight for a tactile, "premium" pill look.
        shinePaint.setStyle(Paint.Style.FILL);
        shinePaint.setColor(0x66FFFFFF);

        gripPaint.setColor(0xFFFFFFFF);
        gripPaint.setStyle(Paint.Style.FILL);

        barPaint.setStyle(Paint.Style.FILL);
        barPaint.setShader(new LinearGradient(
                0, 0, 1, 0, GRADIENT_START, GRADIENT_END, Shader.TileMode.CLAMP));

        playheadPaint.setColor(0xFF2F88FF);
        playheadPaint.setStyle(Paint.Style.FILL);

        playheadGlow.setColor(0x552F88FF);
        playheadGlow.setStyle(Paint.Style.FILL);

        thumbBgPaint.setColor(0xFF2B2B2B);
        thumbBgPaint.setStyle(Paint.Style.FILL);

        setClickable(true);

        // setShadowLayer() needs a software layer to render reliably across
        // API levels/hardware-accelerated canvases.
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    // ── Public API ──────────────────────────────────────────────────────

    public void setOnTrimChangeListener(OnTrimChangeListener l) { this.listener = l; }

    public void setDuration(long durationMs) {
        this.durationMs = Math.max(1, durationMs);
        invalidate();
    }

    public void setTrimRange(long startMs, long endMs) {
        this.trimStartMs = Math.max(0, startMs);
        this.trimEndMs   = Math.min(durationMs, endMs);
        invalidate();
    }

    public long getTrimStartMs() { return trimStartMs; }
    public long getTrimEndMs()   { return trimEndMs; }

    /** Call periodically during playback so the blue playhead line tracks the player. */
    public void setPlayheadPosition(long posMs) {
        this.playheadMs = posMs;
        invalidate();
    }

    /**
     * Kicks off background thumbnail extraction from the video and populates
     * the filmstrip once frames are decoded. Safe to call before the view is
     * laid out — waits for a non-zero width.
     */
    public void loadThumbnails(Context context, String videoUriOrPath, boolean isFilePath, long durationMs) {
        setDuration(durationMs);
        final int token = ++thumbLoadToken;
        if (thumbExecutor == null) thumbExecutor = Executors.newSingleThreadExecutor();

        post(() -> {
            int viewWidth = getWidth();
            if (viewWidth <= 0) viewWidth = 320; // reasonable fallback
            final int thumbCount = Math.max(4, Math.min(14, viewWidth / (int) (36 * density)));

            thumbExecutor.execute(() -> {
                List<Bitmap> frames = extractFrames(context, videoUriOrPath, isFilePath, durationMs, thumbCount);
                if (token != thumbLoadToken) return; // superseded by a newer load
                mainHandler.post(() -> {
                    thumbnails.clear();
                    thumbnails.addAll(frames);
                    invalidate();
                });
            });
        });
    }

    private List<Bitmap> extractFrames(Context context, String uriOrPath, boolean isFilePath,
                                        long durationMs, int count) {
        List<Bitmap> out = new ArrayList<>();
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            if (isFilePath) mmr.setDataSource(uriOrPath);
            else            mmr.setDataSource(context, Uri.parse(uriOrPath));

            long safeDuration = Math.max(1, durationMs);
            for (int i = 0; i < count; i++) {
                long timeUs = (safeDuration * i / count) * 1000L;
                Bitmap frame = null;
                try {
                    frame = mmr.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                } catch (Exception ignored) {}
                if (frame != null) out.add(frame);
            }
        } catch (Exception ignored) {
        } finally {
            try { mmr.release(); } catch (Exception ignored) {}
        }
        return out;
    }

    // ── Drawing ─────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        clipRect.set(0, 0, w, h);
        clipPath.reset();
        clipPath.addRoundRect(clipRect, cornerRadiusPx, cornerRadiusPx, Path.Direction.CW);

        int save = canvas.save();
        canvas.clipPath(clipPath);

        // Thumbnails tiled across the full width
        if (thumbnails.isEmpty()) {
            canvas.drawRect(clipRect, thumbBgPaint);
        } else {
            float tileW = (float) w / thumbnails.size();
            for (int i = 0; i < thumbnails.size(); i++) {
                Bitmap bmp = thumbnails.get(i);
                float left = i * tileW;
                RectF dest = new RectF(left, 0, left + tileW + 1, h);
                drawCenterCropped(canvas, bmp, dest);
            }
        }

        float leftX  = xForMs(trimStartMs);
        float rightX = xForMs(trimEndMs);

        // Dim the un-selected portions
        if (leftX > 0) canvas.drawRect(0, 0, leftX, h, dimPaint);
        if (rightX < w) canvas.drawRect(rightX, 0, w, h, dimPaint);

        // Top / bottom connecting bars across the selection — brand gradient
        // instead of plain white, so the selected range itself reads as premium.
        barPaint.getShader().setLocalMatrix(scaleTranslateMatrixX(leftX, rightX));
        canvas.drawRect(leftX, 0, rightX, barHeightPx, barPaint);
        canvas.drawRect(leftX, h - barHeightPx, rightX, h, barPaint);

        canvas.restoreToCount(save);

        // Playhead line (drawn slightly beyond the clipped strip, like CapCut)
        float playX = xForMs(Math.max(trimStartMs, Math.min(trimEndMs, playheadMs)));
        float glowHalfW = 3.5f * density;
        canvas.drawRect(playX - glowHalfW, -3 * density, playX + glowHalfW, h + 3 * density, playheadGlow);
        canvas.drawRect(playX - 1.5f * density, -3 * density, playX + 1.5f * density, h + 3 * density, playheadPaint);

        // Left / right premium handles — drawn outside the clip so their glow
        // and shadow aren't cropped by the rounded filmstrip edges.
        drawHandle(canvas, leftX - handleWidthPx, leftX, h, true, activeTouch == TOUCH_LEFT);
        drawHandle(canvas, rightX, rightX + handleWidthPx, h, false, activeTouch == TOUCH_RIGHT);
    }

    private final android.graphics.Matrix gradientMatrix = new android.graphics.Matrix();

    private android.graphics.Matrix scaleTranslateMatrixX(float leftX, float rightX) {
        float span = Math.max(1f, rightX - leftX);
        gradientMatrix.reset();
        gradientMatrix.setScale(span, 1f);
        gradientMatrix.postTranslate(leftX, 0);
        return gradientMatrix;
    }

    private void drawHandle(Canvas canvas, float left, float right, int h, boolean isLeft, boolean pressed) {
        handleRect.set(left, 0, right, h);
        float[] radii = isLeft
                ? new float[]{handleCornerRadiusPx, handleCornerRadiusPx, 0, 0, 0, 0, handleCornerRadiusPx, handleCornerRadiusPx}
                : new float[]{0, 0, handleCornerRadiusPx, handleCornerRadiusPx, handleCornerRadiusPx, handleCornerRadiusPx, 0, 0};

        // Soft outer glow — clearly signals "draggable" and brightens on touch
        // for tactile feedback, like a premium editor's active-handle state.
        handleGlowPaint.setAlpha(pressed ? 190 : 110);
        handlePaint.setShadowLayer(pressed ? 14 * density : 8 * density, 0, 0,
                pressed ? 0xFFA855F7 : 0x99A855F7);

        handlePath.reset();
        handlePath.addRoundRect(handleRect, radii, Path.Direction.CW);

        // Position the gradient shader over this handle's bounds.
        gradientMatrix.reset();
        gradientMatrix.setScale(1f, Math.max(1f, h));
        handlePaint.getShader().setLocalMatrix(gradientMatrix);

        // Wider glow footprint behind the pill.
        canvas.drawRoundRect(handleRect.left - 2 * density, handleRect.top,
                handleRect.right + 2 * density, handleRect.bottom,
                handleCornerRadiusPx + 2 * density, handleCornerRadiusPx + 2 * density, handleGlowPaint);

        canvas.drawPath(handlePath, handlePaint);

        // Glossy top highlight for a rounded, tactile pill look.
        shineRect.set(handleRect.left + 2 * density, handleRect.top + 1.5f * density,
                handleRect.right - 2 * density, handleRect.top + h * 0.32f);
        canvas.drawRoundRect(shineRect, handleCornerRadiusPx * 0.6f, handleCornerRadiusPx * 0.6f, shinePaint);

        // Grip glyph: two bold rounded dashes centered in the handle, crisp
        // white against the gradient for maximum visibility.
        float cx = (left + right) / 2f;
        float cy = h / 2f;
        float dashLen = 12 * density;
        float dashGap = 4.5f * density;
        float strokeW = 2.5f * density;
        float startY = cy - dashLen / 2f;
        float endY   = cy + dashLen / 2f;
        for (int i = -1; i <= 1; i += 2) {
            float x = cx + i * (dashGap / 2f);
            canvas.drawRoundRect(x - strokeW / 2f, startY, x + strokeW / 2f, endY,
                    strokeW / 2f, strokeW / 2f, gripPaint);
        }
    }

    private void drawCenterCropped(Canvas canvas, Bitmap bmp, RectF dest) {
        if (bmp == null || bmp.getWidth() == 0 || bmp.getHeight() == 0) {
            canvas.drawRect(dest, thumbBgPaint);
            return;
        }
        float bmpAspect  = (float) bmp.getWidth() / bmp.getHeight();
        float destAspect = dest.width() / dest.height();
        android.graphics.Rect src;
        if (bmpAspect > destAspect) {
            int cropW = Math.round(bmp.getHeight() * destAspect);
            int x = (bmp.getWidth() - cropW) / 2;
            src = new android.graphics.Rect(x, 0, x + cropW, bmp.getHeight());
        } else {
            int cropH = Math.round(bmp.getWidth() / destAspect);
            int y = (bmp.getHeight() - cropH) / 2;
            src = new android.graphics.Rect(0, y, bmp.getWidth(), y + cropH);
        }
        canvas.drawBitmap(bmp, src, dest, null);
    }

    private float xForMs(long ms) {
        if (durationMs <= 0) return 0;
        float ratio = (float) ms / (float) durationMs;
        ratio = Math.max(0f, Math.min(1f, ratio));
        return ratio * getWidth();
    }

    private long msForX(float x) {
        int w = getWidth();
        if (w <= 0) return 0;
        float ratio = Math.max(0f, Math.min(1f, x / w));
        return Math.round(ratio * durationMs);
    }

    // ── Touch handling ──────────────────────────────────────────────────

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float leftX  = xForMs(trimStartMs);
        float rightX = xForMs(trimEndMs);
        float hitSlop = handleWidthPx * 1.4f;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (Math.abs(x - leftX) <= hitSlop && x <= rightX) {
                    activeTouch = TOUCH_LEFT;
                    touchDownDx = x - leftX;
                    getParent().requestDisallowInterceptTouchEvent(true);
                    invalidate();
                    return true;
                } else if (Math.abs(x - rightX) <= hitSlop && x >= leftX) {
                    activeTouch = TOUCH_RIGHT;
                    touchDownDx = x - rightX;
                    getParent().requestDisallowInterceptTouchEvent(true);
                    invalidate();
                    return true;
                }
                activeTouch = TOUCH_NONE;
                return false;

            case MotionEvent.ACTION_MOVE:
                if (activeTouch == TOUCH_NONE) return false;
                float adjX = x - touchDownDx;
                if (activeTouch == TOUCH_LEFT) {
                    long candidate = msForX(adjX);
                    candidate = Math.min(candidate, trimEndMs - (long) minTrimMs);
                    candidate = Math.max(candidate, 0);
                    trimStartMs = candidate;
                } else {
                    long candidate = msForX(adjX);
                    candidate = Math.max(candidate, trimStartMs + (long) minTrimMs);
                    candidate = Math.min(candidate, durationMs);
                    trimEndMs = candidate;
                }
                invalidate();
                if (listener != null) listener.onTrimChanged(trimStartMs, trimEndMs, true);
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (activeTouch != TOUCH_NONE) {
                    if (listener != null) listener.onTrimTouchEnd(trimStartMs, trimEndMs);
                }
                activeTouch = TOUCH_NONE;
                getParent().requestDisallowInterceptTouchEvent(false);
                invalidate();
                return true;

            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        thumbLoadToken++; // invalidate any in-flight thumbnail load
        if (thumbExecutor != null) {
            thumbExecutor.shutdownNow();
            thumbExecutor = null;
        }
    }
}
