package com.callx.app.editor;

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
 * Modern Instagram/CapCut-style video trim scrubber.
 *
 * Replaces the old WhatsApp-style pair of stacked SeekBars with a single
 * filmstrip timeline: real video thumbnails fill the track, the untrimmed
 * region is dimmed, two directly-draggable handle grips mark the trim
 * window, and a live playhead line tracks playback position.
 *
 * Usage:
 *   trimView.setVideo(uri, durationMs);
 *   trimView.setListener(new Listener() { ... });
 *   trimView.setPlayheadMs(currentPositionMs); // call during playback
 */
public class VideoTrimRangeView extends View {

    public interface Listener {
        /** Fired continuously while dragging either handle. */
        void onRangeChanging(long startMs, long endMs);
        /** Fired once the user lifts their finger off a handle. */
        void onRangeChanged(long startMs, long endMs);
        /** Fired when the user taps/drags the filmstrip itself to scrub. */
        void onScrub(long positionMs);
    }

    private static final int HANDLE_TOUCH_SLOP_DP = 28;
    private static final long MIN_TRIM_MS = 1000L;

    private final Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleGripPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playheadPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF trackRect = new RectF();
    private final Path clipPath = new Path();

    private final List<Bitmap> thumbnails = new ArrayList<>();
    private final ExecutorService bgExec = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private Uri  videoUri;
    private long durationMs = 1L;
    private long startMs = 0L;
    private long endMs   = 1L;
    private long playheadMs = -1L;

    private float handlePx;
    private float cornerRadiusPx;
    private int   trackHeightPx;

    private int accentColor    = Color.parseColor("#5B5BF6");
    private int accentColor2   = Color.parseColor("#22D3A6");

    private boolean draggingStart = false;
    private boolean draggingEnd   = false;
    private boolean draggingScrub = false;
    private float   downX;

    private Listener listener;

    public VideoTrimRangeView(Context ctx) { this(ctx, null); }
    public VideoTrimRangeView(Context ctx, @Nullable AttributeSet attrs) { this(ctx, attrs, 0); }
    public VideoTrimRangeView(Context ctx, @Nullable AttributeSet attrs, int defStyle) {
        super(ctx, attrs, defStyle);
        float density = ctx.getResources().getDisplayMetrics().density;
        handlePx        = 14f * density;
        cornerRadiusPx  = 10f * density;
        trackHeightPx   = (int) (64f * density);

        dimPaint.setColor(Color.parseColor("#B3000000")); // 70% black scrim over trimmed-out edges

        handlePaint.setColor(accentColor);
        handleGripPaint.setColor(Color.WHITE);
        handleGripPaint.setStrokeWidth(2.5f * density);
        handleGripPaint.setStrokeCap(Paint.Cap.ROUND);

        borderPaint.setColor(accentColor2);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(2.5f * density);

        playheadPaint.setColor(Color.WHITE);
        playheadPaint.setStrokeWidth(3f * density);

        thumbBgPaint.setColor(Color.parseColor("#1E1E1E"));
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        setMeasuredDimension(width, trackHeightPx + (int) (18f * getResources().getDisplayMetrics().density));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        layoutTrack();
        loadThumbnailsIfNeeded();
    }

    private void layoutTrack() {
        float handleMargin = handlePx * 0.6f;
        trackRect.set(handleMargin, 0, getWidth() - handleMargin, trackHeightPx);
        clipPath.reset();
        clipPath.addRoundRect(trackRect, cornerRadiusPx, cornerRadiusPx, Path.Direction.CW);
    }

    // ── Public API ───────────────────────────────────────────────────────

    public void setVideo(Uri uri, long durationMs) {
        this.videoUri = uri;
        this.durationMs = Math.max(1L, durationMs);
        this.startMs = 0L;
        this.endMs   = this.durationMs;
        thumbnails.clear();
        if (getWidth() > 0) loadThumbnailsIfNeeded();
        invalidate();
    }

    public void setListener(Listener l) { this.listener = l; }

    public void setRange(long startMs, long endMs) {
        this.startMs = Math.max(0, startMs);
        this.endMs   = Math.min(durationMs, endMs);
        invalidate();
    }

    public long getStartMs() { return startMs; }
    public long getEndMs()   { return endMs; }

    /** Call during playback to move the live playhead line; pass -1 to hide it. */
    public void setPlayheadMs(long posMs) {
        this.playheadMs = posMs;
        invalidate();
    }

    // ── Thumbnails ───────────────────────────────────────────────────────

    private void loadThumbnailsIfNeeded() {
        if (videoUri == null || getWidth() <= 0 || !thumbnails.isEmpty()) return;
        int approxThumbW = trackHeightPx * 9 / 16; // portrait-ish cells, filmstrip ok either way
        final int count = Math.max(4, Math.min(14, getWidth() / Math.max(1, approxThumbW)));
        final long dur = durationMs;
        final Uri uri = videoUri;

        bgExec.execute(() -> {
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            List<Bitmap> result = new ArrayList<>();
            try {
                mmr.setDataSource(getContext(), uri);
                for (int i = 0; i < count; i++) {
                    long t = (dur * i) / count;
                    Bitmap frame = mmr.getFrameAtTime(t * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    if (frame != null) result.add(frame);
                }
            } catch (Exception ignored) {
            } finally {
                try { mmr.release(); } catch (Exception ignored) {}
            }
            if (!result.isEmpty()) {
                mainHandler.post(() -> {
                    thumbnails.clear();
                    thumbnails.addAll(result);
                    invalidate();
                });
            }
        });
    }

    // ── Drawing ──────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (trackRect.width() <= 0) return;

        canvas.save();
        canvas.clipPath(clipPath);
        canvas.drawRoundRect(trackRect, cornerRadiusPx, cornerRadiusPx, thumbBgPaint);

        if (!thumbnails.isEmpty()) {
            float cellW = trackRect.width() / thumbnails.size();
            for (int i = 0; i < thumbnails.size(); i++) {
                Bitmap bmp = thumbnails.get(i);
                if (bmp == null || bmp.isRecycled()) continue;
                float left = trackRect.left + i * cellW;
                RectF dst = new RectF(left, trackRect.top, left + cellW + 1, trackRect.bottom);
                canvas.save();
                canvas.clipRect(dst);
                // center-crop the frame into the cell
                float srcAr = (float) bmp.getWidth() / bmp.getHeight();
                float dstAr = dst.width() / dst.height();
                RectF srcRect;
                if (srcAr > dstAr) {
                    float scaledW = bmp.getHeight() * dstAr;
                    float xOff = (bmp.getWidth() - scaledW) / 2f;
                    srcRect = new RectF(xOff, 0, xOff + scaledW, bmp.getHeight());
                } else {
                    float scaledH = bmp.getWidth() / dstAr;
                    float yOff = (bmp.getHeight() - scaledH) / 2f;
                    srcRect = new RectF(0, yOff, bmp.getWidth(), yOff + scaledH);
                }
                canvas.drawBitmap(bmp,
                    new android.graphics.Rect((int) srcRect.left, (int) srcRect.top, (int) srcRect.right, (int) srcRect.bottom),
                    dst, null);
                canvas.restore();
            }
        }

        // Dim the trimmed-out edges
        float startX = xForMs(startMs);
        float endX   = xForMs(endMs);
        if (startX > trackRect.left) {
            canvas.drawRect(trackRect.left, trackRect.top, startX, trackRect.bottom, dimPaint);
        }
        if (endX < trackRect.right) {
            canvas.drawRect(endX, trackRect.top, trackRect.right, trackRect.bottom, dimPaint);
        }
        canvas.restore();

        // Selection border around the kept window
        RectF selRect = new RectF(startX, trackRect.top + 1.5f, endX, trackRect.bottom - 1.5f);
        canvas.drawRoundRect(selRect, cornerRadiusPx, cornerRadiusPx, borderPaint);

        // Handles
        drawHandle(canvas, startX, true);
        drawHandle(canvas, endX, false);

        // Playhead
        if (playheadMs >= 0) {
            float px = xForMs(playheadMs);
            canvas.drawLine(px, -6f, px, trackRect.bottom + 6f, playheadPaint);
            canvas.drawCircle(px, -6f, 5f, playheadPaint);
        }
    }

    private void drawHandle(Canvas canvas, float x, boolean isStart) {
        RectF r = isStart
            ? new RectF(x - handlePx, trackRect.top, x, trackRect.bottom)
            : new RectF(x, trackRect.top, x + handlePx, trackRect.bottom);
        Path p = new Path();
        float[] radii = isStart
            ? new float[]{cornerRadiusPx, cornerRadiusPx, 0, 0, 0, 0, cornerRadiusPx, cornerRadiusPx}
            : new float[]{0, 0, cornerRadiusPx, cornerRadiusPx, cornerRadiusPx, cornerRadiusPx, 0, 0};
        p.addRoundRect(r, radii, Path.Direction.CW);
        canvas.drawPath(p, handlePaint);

        // Grip lines (two short vertical strokes centered in the handle)
        float cx = r.centerX();
        float cy = r.centerY();
        float gripLen = trackHeightPx * 0.22f;
        canvas.drawLine(cx - 2.5f, cy - gripLen, cx - 2.5f, cy + gripLen, handleGripPaint);
        canvas.drawLine(cx + 2.5f, cy - gripLen, cx + 2.5f, cy + gripLen, handleGripPaint);
    }

    // ── Touch handling ──────────────────────────────────────────────────

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float slop = HANDLE_TOUCH_SLOP_DP * getResources().getDisplayMetrics().density;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = x;
                float startX = xForMs(startMs);
                float endX   = xForMs(endMs);
                if (Math.abs(x - startX) <= slop) {
                    draggingStart = true;
                } else if (Math.abs(x - endX) <= slop) {
                    draggingEnd = true;
                } else if (x > startX && x < endX) {
                    draggingScrub = true;
                    if (listener != null) listener.onScrub(msForX(x));
                }
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;

            case MotionEvent.ACTION_MOVE:
                if (draggingStart) {
                    long newStart = msForX(x);
                    newStart = Math.max(0, Math.min(newStart, endMs - MIN_TRIM_MS));
                    startMs = newStart;
                    invalidate();
                    if (listener != null) listener.onRangeChanging(startMs, endMs);
                } else if (draggingEnd) {
                    long newEnd = msForX(x);
                    newEnd = Math.min(durationMs, Math.max(newEnd, startMs + MIN_TRIM_MS));
                    endMs = newEnd;
                    invalidate();
                    if (listener != null) listener.onRangeChanging(startMs, endMs);
                } else if (draggingScrub) {
                    long pos = msForX(x);
                    pos = Math.max(startMs, Math.min(pos, endMs));
                    if (listener != null) listener.onScrub(pos);
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                boolean wasRangeDrag = draggingStart || draggingEnd;
                draggingStart = false;
                draggingEnd = false;
                draggingScrub = false;
                getParent().requestDisallowInterceptTouchEvent(false);
                if (wasRangeDrag && listener != null) listener.onRangeChanged(startMs, endMs);
                return true;
        }
        return super.onTouchEvent(event);
    }

    // ── Coordinate helpers ──────────────────────────────────────────────

    private float xForMs(long ms) {
        float frac = durationMs > 0 ? (float) ms / durationMs : 0f;
        return trackRect.left + frac * trackRect.width();
    }

    private long msForX(float x) {
        float frac = trackRect.width() > 0 ? (x - trackRect.left) / trackRect.width() : 0f;
        frac = Math.max(0f, Math.min(1f, frac));
        return (long) (frac * durationMs);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        bgExec.shutdownNow();
        for (Bitmap b : thumbnails) if (b != null && !b.isRecycled()) b.recycle();
        thumbnails.clear();
    }
}
