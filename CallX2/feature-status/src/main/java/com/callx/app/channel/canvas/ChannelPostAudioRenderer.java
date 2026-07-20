package com.callx.app.channel.canvas;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/**
 * Draws the audio voice-note row for a channel post:
 *   • Circular play/pause button (solid primary-color circle + icon glyph)
 *   • Waveform bars (amplitude array or seeded placeholder)
 *   • Duration / elapsed-time label
 *
 * The play/pause state is driven by ChannelPostCanvasView.audioIsPlaying,
 * which the adapter toggles and calls invalidate() on.
 *
 * All geometry (RectFs, Paths) is computed in layout() and only the
 * play/pause icon path is rebuilt when playback state changes — zero
 * allocation in the per-frame draw() path.
 */
final class ChannelPostAudioRenderer {

    private static final int   BAR_COUNT    = 40;
    private static final float BAR_GAP_RATIO = 0.40f;

    private final ChannelPostCanvasView host;

    ChannelPostAudioRenderer(ChannelPostCanvasView host) {
        this.host = host;
    }

    // Geometry
    private final RectF btnRect     = new RectF();
    private final RectF waveRect    = new RectF();
    private float durationBaselineY;
    private float rowBottom;

    // Play/pause icon path (rebuilt on playback-state change)
    private final Path playPath  = new Path();
    private final Path pausePath = new Path();
    private float lastBtnRadius  = -1f;

    // Waveform samples — loaded from JSON or seeded from hash
    private float[] samples = null;
    private String  lastWaveJson;

    float layout(float left, float top, float right) {
        float btnSize = host.audioBtnSize;
        float y = top + (host.audioRowHeight - btnSize) / 2f;
        btnRect.set(left, y, left + btnSize, y + btnSize);

        float gap = host.audioRowGap;
        float durWidth = host.audioDurWidth;
        float waveLeft  = btnRect.right + gap;
        float waveRight = right - gap - durWidth;
        waveRect.set(waveLeft, top, waveRight, top + host.audioRowHeight);

        // Duration baseline
        Paint.FontMetrics dfm = host.audioDurPaint.getFontMetrics();
        durationBaselineY = top + host.audioRowHeight / 2f
                - (dfm.ascent + dfm.descent) / 2f;

        rowBottom = top + host.audioRowHeight;
        return rowBottom;
    }

    void draw(Canvas canvas) {
        // Play/pause button circle
        float cx = btnRect.centerX(), cy = btnRect.centerY();
        float r  = btnRect.width() / 2f;
        canvas.drawCircle(cx, cy, r, host.audioBtnBgPaint);

        // Rebuild icon paths only when button radius changes.
        if (lastBtnRadius != r) {
            buildPlayPath(r);
            buildPausePath(r);
            lastBtnRadius = r;
        }

        canvas.save();
        canvas.translate(cx, cy);
        canvas.drawPath(host.audioIsPlaying ? pausePath : playPath,
                host.audioBtnIconPaint);
        canvas.restore();

        // Waveform bars
        drawWaveform(canvas);

        // Duration
        String dur = host.audioFormattedDuration;
        if (dur != null && !dur.isEmpty()) {
            float durX = waveRect.right + host.audioRowGap;
            canvas.drawText(dur, durX, durationBaselineY, host.audioDurPaint);
        }
    }

    private void drawWaveform(Canvas canvas) {
        // Load/rebuild samples from JSON if changed.
        if (samples == null || !java.util.Objects.equals(host.audioWaveformJson, lastWaveJson)) {
            samples = parseSamples(host.audioWaveformJson, host.postId);
            lastWaveJson = host.audioWaveformJson;
        }

        float w = waveRect.width();
        float h = waveRect.height();
        float mid = waveRect.top + h / 2f;
        int count = samples.length;
        if (count == 0) return;

        float slotW = w / count;
        float barW  = slotW * (1f - BAR_GAP_RATIO);

        for (int i = 0; i < count; i++) {
            float amp  = Math.min(1f, Math.max(0.06f, samples[i]));
            float barH = amp * h * 0.9f / 2f;
            float x    = waveRect.left + i * slotW + slotW / 2f;
            // Played portion uses primary color, unplayed uses muted.
            Paint p = host.audioIsPlaying && i < count * host.audioPlayedFraction
                    ? host.waveBarPlayedPaint : host.waveBarUnplayedPaint;
            canvas.drawLine(x, mid - barH, x, mid + barH, p);
        }
    }

    private void buildPlayPath(float r) {
        float tri = r * 0.55f;
        playPath.reset();
        playPath.moveTo(-tri * 0.35f, -tri);
        playPath.lineTo(-tri * 0.35f,  tri);
        playPath.lineTo( tri * 0.75f,  0f);
        playPath.close();
    }

    private void buildPausePath(float r) {
        float barW = r * 0.22f;
        float barH = r * 0.6f;
        float gap  = r * 0.18f;
        pausePath.reset();
        pausePath.addRect(-gap - barW, -barH, -gap, barH, Path.Direction.CW);
        pausePath.addRect( gap,        -barH,  gap + barW, barH, Path.Direction.CW);
    }

    private static float[] parseSamples(String json, String seed) {
        if (json != null && !json.isEmpty()) {
            try {
                String s = json.trim().replaceAll("[\\[\\]]", "");
                String[] parts = s.split(",");
                float[] arr = new float[parts.length];
                for (int i = 0; i < parts.length; i++) {
                    arr[i] = Float.parseFloat(parts[i].trim());
                }
                // Normalize to [0,1]
                float max = 0f;
                for (float v : arr) if (v > max) max = v;
                if (max > 0f) {
                    for (int i = 0; i < arr.length; i++) arr[i] /= max;
                }
                return arr;
            } catch (Exception ignored) {}
        }
        // Seeded placeholder — deterministic per post so it doesn't change on scroll.
        float[] arr = new float[BAR_COUNT];
        int hash = seed != null ? seed.hashCode() : 0;
        java.util.Random rng = new java.util.Random(hash);
        for (int i = 0; i < BAR_COUNT; i++) {
            arr[i] = 0.1f + rng.nextFloat() * 0.9f;
        }
        return arr;
    }
}
