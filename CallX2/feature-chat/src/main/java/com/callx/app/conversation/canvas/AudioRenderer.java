package com.callx.app.conversation.canvas;

import android.graphics.Canvas;

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
 */
final class AudioRenderer {

    private final MessageBubbleCanvasView host;

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

        // ── Waveform ── same shape/placement logic as AudioWaveformView's
        // drawBars(): fixed bar count, rounded bars centered vertically in
        // the track, drawn once per onDraw (cheap enough — only the
        // currently-playing bubble redraws every 250ms tick).
        int n = host.audioLevels.length;
        if (n > 0) {
            float slot = host.audioWaveformRect.width() / n;
            float barWidth = slot * (1f - MessageBubbleCanvasView.AUDIO_BAR_GAP_RATIO);
            float radius = barWidth / 2f;
            float centerY = host.audioWaveformRect.centerY();
            float trackH = host.audioWaveformRect.height();
            float playedRightEdge = host.audioWaveformRect.left + host.audioWaveformRect.width() * host.audioProgress;
            float x = host.audioWaveformRect.left;
            for (float lvl : host.audioLevels) {
                float barHeight = Math.max(barWidth, lvl * trackH);
                boolean played = (x + barWidth / 2f) <= playedRightEdge;
                canvas.drawRoundRect(x, centerY - barHeight / 2f, x + barWidth, centerY + barHeight / 2f,
                        radius, radius, played ? host.audioWaveformPlayedPaint : host.audioWaveformIdlePaint);
                x += slot;
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
