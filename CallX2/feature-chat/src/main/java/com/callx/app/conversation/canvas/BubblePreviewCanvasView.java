package com.callx.app.conversation.canvas;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.callx.app.utils.BubbleColorStore;

/**
 * Bubble-colour PREVIEW — ONE canvas view that paints a whole sample conversation (text, media,
 * voice note) instead of six real bubble views or a RecyclerView.
 *
 * Fidelity: bubbles are drawn with the very same {@link BubbleTailDrawable} and the same radius / tail
 * constants as the live chat, and the fill / text colour logic mirrors {@link BubbleColorStore} (custom
 * colour -> readable text, otherwise the theme resource), so what you see here is what the chat shows.
 *
 * PERF: no layout inflation, no images, no Glide, no network. Everything (paints, rects, path, gradient)
 * is created once; {@link #onDraw} allocates nothing. Changing a colour only calls invalidate().
 * Lives in the canvas package because BubbleTailDrawable and the dp constants are package-private.
 */
public class BubblePreviewCanvasView extends View {

    private static final int R_RECV_TEXT = 0, R_SENT_TEXT = 1, R_RECV_MEDIA = 2, R_SENT_AUDIO = 3;
    private static final int[] ROWS = {R_RECV_TEXT, R_SENT_TEXT, R_RECV_TEXT, R_SENT_TEXT, R_RECV_MEDIA, R_SENT_AUDIO};
    private static final String[] TEXTS = {
            "Hello! 👋", "Hi! How are you?", "I'm good, thanks 😊", "Great! 🎉"};
    private static final String[] TIMES = {"9:41 AM", "9:42 AM", "9:43 AM", "9:44 AM", "9:45 AM", "9:46 AM"};
    private static final float[] WAVE = {.35f, .6f, .45f, .8f, 1f, .7f, .5f, .9f, .6f, .4f, .75f, .55f, .3f, .65f,
            .85f, .5f, .4f, .7f, .35f, .6f};

    private final BubbleTailDrawable sentDrawable = new BubbleTailDrawable(true, true);
    private final BubbleTailDrawable recvDrawable = new BubbleTailDrawable(false, true);

    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint timePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint imgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path play = new Path();

    private final float density;
    private final float pad, gap, avatar, avatarGap, tailW, tailH, radius, mediaRadius, innerPadH;
    private final int rowTextH, rowMediaH, rowAudioH;
    private final int mediaW, audioW;

    private int sentFill, recvFill, sentText, recvText;   // resolved colours, 0 = theme default requested
    private int reqSent, reqRecv;
    private int avatarSentColor = 0xFF6C2BD9, avatarRecvColor = 0xFF9A8FB8;

    public BubblePreviewCanvasView(Context c) { this(c, null); }

    public BubblePreviewCanvasView(Context c, @Nullable AttributeSet a) {
        super(c, a);
        density = c.getResources().getDisplayMetrics().density;
        pad = 14f * density;
        gap = 10f * density;
        avatar = 30f * density;
        avatarGap = 6f * density;
        tailW = MessageBubbleCanvasView.TAIL_W_DP * density;
        tailH = MessageBubbleCanvasView.TAIL_H_DP * density;
        radius = MessageBubbleCanvasView.CORNER_RADIUS_DP * density;
        mediaRadius = MessageBubbleCanvasView.MEDIA_CORNER_RADIUS_DP * density;
        innerPadH = 12f * density;
        rowTextH = Math.round(50f * density);
        rowMediaH = Math.round(176f * density);
        rowAudioH = Math.round(54f * density);
        mediaW = Math.round(220f * density);
        audioW = Math.round(224f * density);

        textPaint.setTextSize(15f * c.getResources().getDisplayMetrics().scaledDensity);
        timePaint.setTextSize(10.5f * c.getResources().getDisplayMetrics().scaledDensity);
        fillPaint.setStyle(Paint.Style.FILL);
        imgPaint.setStyle(Paint.Style.FILL);
        setColors(0, 0);
    }

    /** 0 = theme default for that side (same meaning as BubbleColorStore). Cheap; invalidates only. */
    public void setColors(int sent, int recv) {
        reqSent = sent; reqRecv = recv;
        final Context c = getContext();
        sentFill = sent != 0 ? sent
                : ContextCompat.getColor(c, com.callx.app.core.R.color.bubble_sent);
        recvFill = recv != 0 ? recv
                : ContextCompat.getColor(c, com.callx.app.core.R.color.bubble_received);
        sentText = sent != 0 ? BubbleColorStore.readableOn(sent)
                : ContextCompat.getColor(c, com.callx.app.core.R.color.bubble_sent_text);
        recvText = recv != 0 ? BubbleColorStore.readableOn(recv)
                : ContextCompat.getColor(c, com.callx.app.core.R.color.bubble_received_text);
        sentDrawable.configure(sentFill, radius, tailW, tailH, density);
        recvDrawable.configure(recvFill, radius, tailW, tailH, density);
        invalidate();
    }

    /** Avatar dot colours (theme-supplied by the host screen). */
    public void setAvatarColors(int sentColor, int recvColor) {
        avatarSentColor = sentColor; avatarRecvColor = recvColor; invalidate();
    }

    private int rowHeight(int type) {
        return type == R_RECV_MEDIA ? rowMediaH : type == R_SENT_AUDIO ? rowAudioH : rowTextH;
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int total = Math.round(pad);
        for (int t : ROWS) total += rowHeight(t) + Math.round(gap);
        total += Math.round(pad - gap);
        setMeasuredDimension(resolveSize(getSuggestedMinimumWidth(), wSpec), total);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final int w = getWidth();
        float y = pad;
        int textIdx = 0;
        for (int i = 0; i < ROWS.length; i++) {
            final int type = ROWS[i];
            final int h = rowHeight(type);
            final boolean sent = type == R_SENT_TEXT || type == R_SENT_AUDIO;
            drawAvatar(canvas, w, y, sent);
            final float bodyLeftLimit = pad + avatar + avatarGap + tailW;
            final float bodyRightLimit = w - pad - avatar - avatarGap - tailW;
            if (type == R_RECV_MEDIA) {
                drawMedia(canvas, bodyLeftLimit, y, h, TIMES[i]);
            } else if (type == R_SENT_AUDIO) {
                drawAudio(canvas, bodyRightLimit, y, h, TIMES[i]);
            } else {
                drawText(canvas, sent, bodyLeftLimit, bodyRightLimit, y, h, TEXTS[textIdx++], TIMES[i]);
            }
            y += h + gap;
        }
    }

    private void drawAvatar(Canvas c, int w, float top, boolean sent) {
        fillPaint.setColor(sent ? avatarSentColor : avatarRecvColor);
        final float cx = sent ? w - pad - avatar / 2f : pad + avatar / 2f;
        c.drawCircle(cx, top + avatar / 2f, avatar / 2f, fillPaint);
    }

    private void drawText(Canvas c, boolean sent, float leftLimit, float rightLimit, float top, int h,
                          String msg, String time) {
        textPaint.setColor(sent ? sentText : recvText);
        timePaint.setColor(withAlpha(sent ? sentText : recvText, 170));
        final String foot = sent ? time + "  ✓✓" : time;
        final float bw = Math.max(textPaint.measureText(msg), timePaint.measureText(foot)) + innerPadH * 2f;
        final float l = sent ? rightLimit - bw : leftLimit;
        final float r = l + bw;
        final BubbleTailDrawable d = sent ? sentDrawable : recvDrawable;
        d.setBounds(Math.round(l), Math.round(top), Math.round(r), Math.round(top + h));
        d.draw(c);
        c.drawText(msg, l + innerPadH, top + 21f * density, textPaint);
        if (sent) {
            // v928: ticks use the same contrast-safe colour the real chat bubble uses
            final float fx = r - innerPadH - timePaint.measureText(foot);
            final String head = time + "  ";
            c.drawText(head, fx, top + h - 9f * density, timePaint);
            timePaint.setColor(sentTick());
            c.drawText("✓✓", fx + timePaint.measureText(head), top + h - 9f * density, timePaint);
        } else {
            c.drawText(foot, l + innerPadH, top + h - 9f * density, timePaint);
        }
    }

    private void drawMedia(Canvas c, float left, float top, int h, String time) {
        final BubbleTailDrawable d = recvDrawable;
        d.setBounds(Math.round(left), Math.round(top), Math.round(left + mediaW), Math.round(top + h));
        d.draw(c);
        final float in = 6f * density;
        rect.set(left + in, top + in, left + mediaW - in, top + h - 26f * density);
        imgPaint.setShader(imageShader(rect));
        c.drawRoundRect(rect, mediaRadius * 0.7f, mediaRadius * 0.7f, imgPaint);
        timePaint.setColor(withAlpha(recvText, 170));
        c.drawText(time, left + in + 2f * density, top + h - 8f * density, timePaint);
    }

    private LinearGradient imageGradient;
    private float imgTop = Float.NaN, imgBottom = Float.NaN;

    private Shader imageShader(RectF r) {
        if (imageGradient == null || r.top != imgTop || r.bottom != imgBottom) {
            imgTop = r.top; imgBottom = r.bottom;
            imageGradient = new LinearGradient(0f, r.top, 0f, r.bottom,
                    new int[]{0xFF3B2A78, 0xFFB5568F, 0xFFF59E6C, 0xFF2A2B4A}, new float[]{0f, .45f, .72f, 1f},
                    Shader.TileMode.CLAMP);
        }
        return imageGradient;
    }

    private void drawAudio(Canvas c, float rightLimit, float top, int h, String time) {
        final float l = rightLimit - audioW;
        sentDrawable.setBounds(Math.round(l), Math.round(top), Math.round(rightLimit), Math.round(top + h));
        sentDrawable.draw(c);
        // v928: gold play button + played part of the waveform, contrast-safe like the real bubble
        final int gold = sentGold();
        final float cx = l + 24f * density, cy = top + h * 0.42f, s = 7f * density;
        fillPaint.setColor(gold);
        c.drawCircle(cx, cy, 13f * density, fillPaint);
        fillPaint.setColor(reqSent != 0 && gold != 0xFFD4AF37 ? BubbleColorStore.readableOn(gold) : 0xFFFFFFFF);
        play.rewind();
        play.moveTo(cx - s * 0.6f, cy - s);
        play.lineTo(cx - s * 0.6f, cy + s);
        play.lineTo(cx + s, cy);
        play.close();
        c.drawPath(play, fillPaint);
        final float bx = l + 44f * density, bw = 3f * density, bg = 2.2f * density, maxH = 22f * density;
        for (int i = 0; i < WAVE.length; i++) {
            fillPaint.setColor(i < WAVE.length * 0.35f ? gold : withAlpha(sentText, 200));
            final float bh = Math.max(3f * density, maxH * WAVE[i]);
            rect.set(bx + i * (bw + bg), cy - bh / 2f, bx + i * (bw + bg) + bw, cy + bh / 2f);
            c.drawRoundRect(rect, bw / 2f, bw / 2f, fillPaint);
        }
        timePaint.setColor(withAlpha(sentText, 170));
        final String dur = "0:12";
        c.drawText(dur, rightLimit - innerPadH - timePaint.measureText(dur), cy + 4f * density, timePaint);
        final String foot = time + "  ✓✓";
        final String head = time + "  ";
        final float fx = rightLimit - innerPadH - timePaint.measureText(foot);
        c.drawText(head, fx, top + h - 7f * density, timePaint);
        timePaint.setColor(sentTick());
        c.drawText("✓✓", fx + timePaint.measureText(head), top + h - 7f * density, timePaint);
    }

    private int sentTick() { return reqSent != 0 ? BubbleColorStore.tickReadOn(reqSent) : 0xFFD4AF37; }
    private int sentGold() { return reqSent != 0 ? BubbleColorStore.goldOn(reqSent) : 0xFFD4AF37; }

    private static int withAlpha(int color, int a) { return (color & 0x00FFFFFF) | (a << 24); }
}
