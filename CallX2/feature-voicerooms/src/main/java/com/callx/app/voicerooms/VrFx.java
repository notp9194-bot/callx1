package com.callx.app.voicerooms;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.Random;

/**
 * VrFx — Voice Room ke chhote animations: floating hearts, gift burst,
 * button bounce, hand wave, speaking pulse. Sab "fx layer" (FrameLayout
 * overlay) pe chalte hain, isliye layout ko disturb nahi karte.
 */
final class VrFx {
    private VrFx() {}

    private static final Random RNG = new Random();

    // ───────── floating heart / emoji ─────────
    /** anchor view ke center se ek emoji upar tairta hua nikalta hai. */
    static void floatFrom(ViewGroup layer, View anchor, String emoji) {
        int[] a = new int[2], l = new int[2];
        anchor.getLocationInWindow(a);
        layer.getLocationInWindow(l);
        float x = a[0] - l[0] + anchor.getWidth() / 2f;
        float y = a[1] - l[1] + anchor.getHeight() / 2f;
        floatAt(layer, x, y, emoji);
    }

    static void floatAt(ViewGroup layer, float x, float y, String emoji) {
        float d = layer.getResources().getDisplayMetrics().density;
        TextView tv = new TextView(layer.getContext());
        tv.setText(emoji);
        tv.setTextSize(20 + RNG.nextInt(10));
        tv.setIncludeFontPadding(false);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        lp.leftMargin = (int) (x - 14 * d);
        lp.topMargin = (int) (y - 14 * d);
        layer.addView(tv, lp);

        float rise = (180 + RNG.nextInt(140)) * d;
        float drift = (RNG.nextInt(80) - 40) * d;
        long dur = 1100 + RNG.nextInt(600);

        ObjectAnimator ty = ObjectAnimator.ofFloat(tv, View.TRANSLATION_Y, 0, -rise);
        ObjectAnimator tx = ObjectAnimator.ofFloat(tv, View.TRANSLATION_X, 0, drift);
        ObjectAnimator sx = ObjectAnimator.ofFloat(tv, View.SCALE_X, 0.5f, 1.25f);
        ObjectAnimator sy = ObjectAnimator.ofFloat(tv, View.SCALE_Y, 0.5f, 1.25f);
        ObjectAnimator al = ObjectAnimator.ofFloat(tv, View.ALPHA, 1f, 0f);
        al.setStartDelay(dur / 2);
        AnimatorSet set = new AnimatorSet();
        set.playTogether(ty, tx, sx, sy, al);
        set.setDuration(dur);
        set.setInterpolator(new DecelerateInterpolator());
        set.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) { layer.removeView(tv); }
        });
        set.start();
    }

    /** Dil ki chhoti barsaat (reaction burst). */
    static void heartBurst(ViewGroup layer, View anchor, String emoji, int count) {
        for (int i = 0; i < count; i++) {
            final int delay = i * 70;
            layer.postDelayed(() -> floatFrom(layer, anchor, emoji), delay);
        }
    }

    // ───────── gift burst ─────────
    static void giftBurst(ViewGroup layer, String emoji) {
        float d = layer.getResources().getDisplayMetrics().density;
        int w = layer.getWidth(), h = layer.getHeight();
        if (w == 0 || h == 0) return;

        TextView big = new TextView(layer.getContext());
        big.setText(emoji);
        big.setTextSize(96);
        big.setIncludeFontPadding(false);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        layer.addView(big, lp);

        big.setScaleX(0.2f); big.setScaleY(0.2f); big.setAlpha(0f);
        ObjectAnimator in1 = ObjectAnimator.ofFloat(big, View.SCALE_X, 0.2f, 1.2f);
        ObjectAnimator in2 = ObjectAnimator.ofFloat(big, View.SCALE_Y, 0.2f, 1.2f);
        ObjectAnimator in3 = ObjectAnimator.ofFloat(big, View.ALPHA, 0f, 1f);
        AnimatorSet appear = new AnimatorSet();
        appear.playTogether(in1, in2, in3);
        appear.setDuration(420);
        appear.setInterpolator(new OvershootInterpolator(2f));

        ObjectAnimator up = ObjectAnimator.ofFloat(big, View.TRANSLATION_Y, 0, -120 * d);
        ObjectAnimator out = ObjectAnimator.ofFloat(big, View.ALPHA, 1f, 0f);
        AnimatorSet leave = new AnimatorSet();
        leave.playTogether(up, out);
        leave.setStartDelay(550);
        leave.setDuration(420);
        leave.setInterpolator(new AccelerateInterpolator());

        AnimatorSet all = new AnimatorSet();
        all.playSequentially(appear, leave);
        all.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) { layer.removeView(big); }
        });
        all.start();

        // radial particles
        for (int i = 0; i < 14; i++) {
            TextView p = new TextView(layer.getContext());
            p.setText(emoji);
            p.setTextSize(16 + RNG.nextInt(8));
            p.setIncludeFontPadding(false);
            layer.addView(p, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER));
            double ang = RNG.nextDouble() * Math.PI * 2;
            float dist = (110 + RNG.nextInt(110)) * d;
            ObjectAnimator px = ObjectAnimator.ofFloat(p, View.TRANSLATION_X, 0, (float) Math.cos(ang) * dist);
            ObjectAnimator py = ObjectAnimator.ofFloat(p, View.TRANSLATION_Y, 0, (float) Math.sin(ang) * dist);
            ObjectAnimator pa = ObjectAnimator.ofFloat(p, View.ALPHA, 1f, 0f);
            AnimatorSet ps = new AnimatorSet();
            ps.playTogether(px, py, pa);
            ps.setStartDelay(250);
            ps.setDuration(900);
            ps.setInterpolator(new DecelerateInterpolator());
            ps.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator animation) { layer.removeView(p); }
            });
            ps.start();
        }
    }

    // ───────── small view animations ─────────
    static void bounce(View v) {
        v.animate().cancel();
        v.setScaleX(1f); v.setScaleY(1f);
        v.animate().scaleX(0.82f).scaleY(0.82f).setDuration(90).withEndAction(() ->
                v.animate().scaleX(1f).scaleY(1f).setDuration(220)
                        .setInterpolator(new OvershootInterpolator(3f)).start()).start();
    }

    /** Raise-hand wave: ±18° 3 baar. */
    static void wave(View v) {
        ObjectAnimator a = ObjectAnimator.ofFloat(v, View.ROTATION, 0f, 18f, -14f, 12f, -8f, 0f);
        a.setDuration(650);
        a.start();
    }

    /**
     * Speaking pulse: ring ke peeche phailta hua halka circle. Caller ko
     * returned animator hold karna hai taaki recycle par cancel kar sake.
     */
    static ValueAnimator pulse(View ring) {
        ring.setVisibility(View.VISIBLE);
        ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(1200);
        va.setRepeatCount(ValueAnimator.INFINITE);
        va.setInterpolator(new LinearInterpolator());
        va.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            float sc = 1f + 0.28f * f;
            ring.setScaleX(sc);
            ring.setScaleY(sc);
            ring.setAlpha(0.55f * (1f - f));
        });
        va.start();
        return va;
    }

    static void stopPulse(ValueAnimator va, View ring) {
        if (va != null) va.cancel();
        ring.setAlpha(0f);
        ring.setVisibility(View.INVISIBLE);
    }
}
