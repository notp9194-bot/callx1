package com.callx.app.views;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
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
 * Renders a horizontal filmstrip of video-frame thumbnails inside a rounded
 * track, dims the portion outside the selected range, and shows two
 * draggable white "pill" handles (with a grip glyph) bracketing the
 * selection plus a thin white top/bottom bar connecting them. A blue
 * playhead line tracks current playback position inside the selection.
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

    private final Paint dimPaint       = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gripPaint      = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barPaint       = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playheadPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playheadGlow   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbBgPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF clipRect       = new RectF();
    private final Path  clipPath       = new Path();

    private final List<Bitmap> thumbnails = new ArrayList<>();

    private long durationMs   = 0;
    private long trimStartMs  = 0;
    private long trimEndMs    = 0;
    private long playheadMs   = 0;

    private final float density  = getResources().getDisplayMetrics().density;
    private final float handleWidthPx = 20 * density;
    private final float cornerRadiusPx = 8 * density;
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

        handlePaint.setColor(Color.WHITE);
        handlePaint.setStyle(Paint.Style.FILL);

        gripPaint.setColor(0xFF9E9E9E);
        gripPaint.setStyle(Paint.Style.FILL);

        barPaint.setColor(Color.WHITE);
        barPaint.setStyle(Paint.Style.FILL);

        playheadPaint.setColor(0xFF2F88FF);
        playheadPaint.setStyle(Paint.Style.FILL);

        playheadGlow.setColor(0x552F88FF);
        playheadGlow.setStyle(Paint.Style.FILL);

        thumbBgPaint.setColor(0xFF2B2B2B);
        thumbBgPaint.setStyle(Paint.Style.FILL);

        setClickable(true);
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

        // Top / bottom connecting bars across the selection
        canvas.drawRect(leftX, 0, rightX, barHeightPx, barPaint);
        canvas.drawRect(leftX, h - barHeightPx, rightX, h, barPaint);

        canvas.restoreToCount(save);

        // Playhead line (drawn slightly beyond the clipped strip, like CapCut)
        float playX = xForMs(Math.max(trimStartMs, Math.min(trimEndMs, playheadMs)));
        float glowHalfW = 3.5f * density;
        canvas.drawRect(playX - glowHalfW, -3 * density, playX + glowHalfW, h + 3 * density, playheadGlow);
        canvas.drawRect(playX - 1.5f * density, -3 * density, playX + 1.5f * density, h + 3 * density, playheadPaint);

        // Left handle
        drawHandle(canvas, leftX - handleWidthPx, leftX, h, true);
        // Right handle
        drawHandle(canvas, rightX, rightX + handleWidthPx, h, false);
    }

    private void drawHandle(Canvas canvas, float left, float right, int h, boolean isLeft) {
        RectF rect = new RectF(left, 0, right, h);
        Path path = new Path();
        float[] radii = isLeft
                ? new float[]{cornerRadiusPx, cornerRadiusPx, 0, 0, 0, 0, cornerRadiusPx, cornerRadiusPx}
                : new float[]{0, 0, cornerRadiusPx, cornerRadiusPx, cornerRadiusPx, cornerRadiusPx, 0, 0};
        path.addRoundRect(rect, radii, Path.Direction.CW);
        canvas.drawPath(path, handlePaint);

        // Grip glyph: three short vertical dashes centered in the handle
        float cx = (left + right) / 2f;
        float cy = h / 2f;
        float dashLen = 10 * density;
        float dashGap = 4 * density;
        float strokeW = 2 * density;
        Paint p = gripPaint;
        float startY = cy - dashLen / 2f;
        float endY   = cy + dashLen / 2f;
        for (int i = -1; i <= 1; i++) {
            float x = cx + i * dashGap;
            canvas.drawRoundRect(x - strokeW / 2f, startY, x + strokeW / 2f, endY,
                    strokeW / 2f, strokeW / 2f, p);
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
                    return true;
                } else if (Math.abs(x - rightX) <= hitSlop && x >= leftX) {
                    activeTouch = TOUCH_RIGHT;
                    touchDownDx = x - rightX;
                    getParent().requestDisallowInterceptTouchEvent(true);
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
