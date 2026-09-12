package com.callx.app.conversation.canvas;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;

/**
 * Draws the audio row — play/pause circle button, waveform track
 * (idle-colored bars, with the played fraction re-drawn in the played
 * color, clipped to audioProgress), elapsed-time label, and finally the
 * normal text-bubble footer (time/tick) below the whole row, since an
 * audio bubble is never captioned.
 *
 * Moved verbatim out of MessageBubbleCanvasView (feature-based file
 * split, no behavior change) — bind/measure/touch logic for the audio
 * bubble stays on the host view; this class only owns the draw() call.
 *
 * PERF (shared grayscale-mask cache): the waveform bar SHAPE is baked
 * once into a colorless ALPHA_8 coverage mask and shared across every
 * holder that needs the same (seed, width, height) — see
 * MessageBubbleCanvasView#getOrBuildAudioWaveformMask() /
 * #sAudioWaveformMaskCache for the actual cache. The idle-color and
 * played-color draws below both reuse that SAME mask bitmap — no
 * separate idle/played bitmaps at all anymore — tinted differently each
 * time via a PorterDuffColorFilter(color, SRC_IN) on the drawing Paint,
 * the same recoloring technique Android uses for tinted icons. That
 * halves the bitmaps per cache entry (one instead of two) AND drops each
 * remaining bitmap from ARGB_8888 (4 bytes/pixel) to ALPHA_8 (1 byte/
 * pixel) — an ~8x memory drop per entry versus keeping two full-color
 * bitmaps. The two tinting Paints below are reused across draws; their
 * colorFilter is only rebuilt when the actual color changes (a rebind or
 * a theme change), never on every scroll/playback-tick frame.
 */
final class AudioRenderer {

    private final MessageBubbleCanvasView host;

    // ── Mask-tinting paints, reused across every draw() call. Rebuilding
    // a PorterDuffColorFilter is cheap but still an allocation — these
    // fields let it happen only when the color actually changed since the
    // last draw, not on every fling frame or 250ms playback tick. ──
    private final Paint idleMaskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playedMaskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean maskPaintsInited = false;
    private int lastIdleColor;
    private int lastPlayedColor;

    AudioRenderer(MessageBubbleCanvasView host) {
        this.host = host;
    }

    void draw(Canvas canvas, int hPad, int vPad) {
        // ── Play/pause button ──
        canvas.drawCircle(host.audioBtnRect.centerX(), host.audioBtnRect.centerY(),
                host.audioBtnRect.width() / 2f, host.audioBtnBgPaint);
        float cx = host.audioBtnRect.centerX(), cy = host.audioBtnRect.centerY();
        if (host.audioPlaying) {
            float barW = MessageBubbleCanvasView.AUDIO_PAUSE_BAR_W_DP * host.density;
            float barH = MessageBubbleCanvasView.AUDIO_PAUSE_BAR_H_DP * host.density;
            float gap = MessageBubbleCanvasView.AUDIO_PAUSE_BAR_GAP_DP * host.density;
            canvas.drawRoundRect(cx - gap / 2f - barW, cy - barH / 2f, cx - gap / 2f, cy + barH / 2f,
                    barW / 3f, barW / 3f, host.audioBtnIconPaint);
            canvas.drawRoundRect(cx + gap / 2f, cy - barH / 2f, cx + gap / 2f + barW, cy + barH / 2f,
                    barW / 3f, barW / 3f, host.audioBtnIconPaint);
        } else {
            float triR = (MessageBubbleCanvasView.AUDIO_PLAY_TRIANGLE_DP * host.density) / 2f;
            host.audioPlayTrianglePath.reset();
            host.audioPlayTrianglePath.moveTo(cx - triR * 0.5f, cy - triR * 0.85f);
            host.audioPlayTrianglePath.lineTo(cx - triR * 0.5f, cy + triR * 0.85f);
            host.audioPlayTrianglePath.lineTo(cx + triR * 0.95f, cy);
            host.audioPlayTrianglePath.close();
            canvas.drawPath(host.audioPlayTrianglePath, host.audioBtnIconPaint);
        }

        // ── Waveform ── PERF: bar shape comes from the shared static
        // grayscale MASK cache (MessageBubbleCanvasView#getOrBuildAudioWaveformMask)
        // — the SAME mask bitmap is drawn twice below, tinted idle-color
        // then played-color via colorFilter, instead of two separate
        // pre-colored bitmaps. Not recomputed per onDraw. Every fling
        // frame / non-playing bubble now costs one cache lookup + two
        // drawBitmap() calls instead of AUDIO_BAR_COUNT drawRoundRect()
        // calls.
        int n = host.audioLevels.length;
        if (n > 0 && !host.audioWaveformRect.isEmpty()) {
            int w = Math.round(host.audioWaveformRect.width());
            int h = Math.round(host.audioWaveformRect.height());
            Bitmap mask = null;
            if (w > 0 && h > 0) {
                mask = MessageBubbleCanvasView.getOrBuildAudioWaveformMask(
                        host.audioSeed, host.audioLevels, w, h);
            }
            float left = host.audioWaveformRect.left;
            float top = host.audioWaveformRect.top;
            if (mask != null) {
                int idleColor = host.audioWaveformIdlePaint.getColor();
                int playedColor = host.audioWaveformPlayedPaint.getColor();
                // Only rebuild a colorFilter when its color actually
                // changed since the last draw (rebind/theme change) —
                // never on a plain re-scroll or a playback progress tick,
                // both of which redraw this bubble without touching color.
                if (!maskPaintsInited || idleColor != lastIdleColor) {
                    idleMaskPaint.setColorFilter(new PorterDuffColorFilter(idleColor, PorterDuff.Mode.SRC_IN));
                    lastIdleColor = idleColor;
                }
                if (!maskPaintsInited || playedColor != lastPlayedColor) {
                    playedMaskPaint.setColorFilter(new PorterDuffColorFilter(playedColor, PorterDuff.Mode.SRC_IN));
                    lastPlayedColor = playedColor;
                }
                maskPaintsInited = true;

                canvas.drawBitmap(mask, left, top, idleMaskPaint);
                if (host.audioProgress > 0f) {
                    float playedWidth = w * host.audioProgress;
                    canvas.save();
                    canvas.clipRect(left, top, left + playedWidth, top + h);
                    canvas.drawBitmap(mask, left, top, playedMaskPaint);
                    canvas.restore();
                }
            } else {
                // OOM fallback for this one frame only — same math as
                // drawBars(), drawn straight to the visible canvas.
                float slot = host.audioWaveformRect.width() / n;
                float barWidth = slot * (1f - MessageBubbleCanvasView.AUDIO_BAR_GAP_RATIO);
                float radius = barWidth / 2f;
                float centerY = host.audioWaveformRect.centerY();
                float trackH = host.audioWaveformRect.height();
                float playedRightEdge = left + host.audioWaveformRect.width() * host.audioProgress;
                float x = left;
                for (float lvl : host.audioLevels) {
                    float barHeight = Math.max(barWidth, lvl * trackH);
                    boolean played = (x + barWidth / 2f) <= playedRightEdge;
                    canvas.drawRoundRect(x, centerY - barHeight / 2f, x + barWidth, centerY + barHeight / 2f,
                            radius, radius, played ? host.audioWaveformPlayedPaint : host.audioWaveformIdlePaint);
                    x += slot;
                }
            }
        }

        // ── Elapsed-time label ── right-aligned in its fixed slot just
        // past the waveform; empty (idle) until playback actually starts,
        // same as the legacy tv_audio_dur.
        if (!host.audioElapsedText.isEmpty()) {
            float baselineY = host.audioWaveformRect.centerY()
                    - (host.audioDurPaint.ascent() + host.audioDurPaint.descent()) / 2f;
            canvas.drawText(host.audioElapsedText, host.bubbleRect.right - hPad, baselineY, host.audioDurPaint);
        }

        host.drawFooter(canvas, host.bubbleRect.bottom - vPad * 0.4f, host.bubbleRect.right - hPad);
    }
}
