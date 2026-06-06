package com.callx.app.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * EmojiRainView — background canvas jisme emojis upar se neeche girte hain.
 * 😭🙏💔😢 — slow, sad, dramatic.
 */
public class EmojiRainView extends View {

    private static final String[] EMOJIS = {"😭", "🙏", "💔", "😢", "😿"};
    private static final String[] HAPPY_EMOJIS = {"🎉", "🎊", "✨", "💚", "🥳", "🎈", "😄", "🙌"};

    private String[] activeEmojis = EMOJIS;
    private static final int PARTICLE_COUNT = 18;
    private static final int FRAME_MS = 40; // ~25fps — smooth enough

    private final List<EmojiParticle> particles = new ArrayList<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random rng = new Random();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean running = false;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!running) return;
            update();
            invalidate();
            handler.postDelayed(this, FRAME_MS);
        }
    };

    public EmojiRainView(Context ctx) { super(ctx); init(); }
    public EmojiRainView(Context ctx, AttributeSet a) { super(ctx, a); init(); }
    public EmojiRainView(Context ctx, AttributeSet a, int d) { super(ctx, a, d); init(); }

    private void init() {
        paint.setTextAlign(Paint.Align.CENTER);
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        particles.clear();
        if (w == 0 || h == 0) return;
        for (int i = 0; i < PARTICLE_COUNT; i++) {
            particles.add(newParticle(w, h, true));
        }
    }

    private EmojiParticle newParticle(int w, int h, boolean scattered) {
        EmojiParticle p = new EmojiParticle();
        p.emoji = activeEmojis[rng.nextInt(activeEmojis.length)];
        p.x     = rng.nextInt(Math.max(w, 1));
        p.y     = scattered ? rng.nextInt(Math.max(h, 1)) : -60 - rng.nextInt(200);
        p.speed = 1.2f + rng.nextFloat() * 2.0f;
        p.size  = 22f + rng.nextFloat() * 18f;
        p.alpha = 0.35f + rng.nextFloat() * 0.45f;
        p.sway  = (rng.nextFloat() - 0.5f) * 0.8f;
        return p;
    }

    private void update() {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        for (EmojiParticle p : particles) {
            p.y += p.speed;
            p.x += p.sway;
            if (p.y > h + 60) {
                EmojiParticle fresh = newParticle(w, h, false);
                p.emoji = fresh.emoji;
                p.x     = fresh.x;
                p.y     = fresh.y;
                p.speed = fresh.speed;
                p.size  = fresh.size;
                p.alpha = fresh.alpha;
                p.sway  = fresh.sway;
            }
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        for (EmojiParticle p : particles) {
            paint.setTextSize(p.size);
            paint.setAlpha((int)(p.alpha * 255));
            canvas.drawText(p.emoji, p.x, p.y, paint);
        }
    }

    public void setHappyMode(boolean happy) {
        activeEmojis = happy ? HAPPY_EMOJIS : EMOJIS;
    }

    public void startRain() {
        running = true;
        handler.post(ticker);
    }

    public void stopRain() {
        running = false;
        handler.removeCallbacks(ticker);
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopRain();
    }

    private static class EmojiParticle {
        String emoji;
        float x, y, speed, size, alpha, sway;
    }
}
