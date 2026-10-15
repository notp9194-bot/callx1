package com.callx.app.compose;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.util.DisplayMetrics;
import android.view.View;

import com.callx.app.utils.StoryFrameSpec;

/**
 * Dotted "safe area" guide for the status editor: shades the strips the viewer's own header /
 * caption will cover and draws dashed boundary lines. Touch-transparent, never baked into the photo.
 */
public final class StorySafeAreaView extends View {
    private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dash  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] insets = new float[2];
    private final float density;
    private final DisplayMetrics dm;
    private final String topText = "Yahan header dikhega";
    private final String botText = "Yahan caption dikhega";

    public StorySafeAreaView(Context c) {
        super(c);
        dm = c.getResources().getDisplayMetrics();
        density = dm.density;
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setWillNotDraw(false);
        setAlpha(0f);
        setVisibility(INVISIBLE);
        shade.setColor(0x44000000);
        dash.setColor(0xE6FFFFFF);
        dash.setStyle(Paint.Style.STROKE);
        dash.setStrokeWidth(1.5f * density);
        dash.setPathEffect(new DashPathEffect(new float[]{8f * density, 6f * density}, 0f));
        label.setColor(0xCCFFFFFF);
        label.setTextSize(10f * density);
        label.setTextAlign(Paint.Align.CENTER);
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        StoryFrameSpec.safeInsetsCanvasPx(w, h, dm.widthPixels, dm.heightPixels, density, insets);
        float top = insets[0], bottom = h - insets[1];
        if (top > 0f) {
            c.drawRect(0, 0, w, top, shade);
            c.drawLine(0, top, w, top, dash);
            c.drawText(topText, w / 2f, Math.max(12f * density, top - 6f * density), label);
        }
        if (insets[1] > 0f) {
            c.drawRect(0, bottom, w, h, shade);
            c.drawLine(0, bottom, w, bottom, dash);
            c.drawText(botText, w / 2f, Math.min(h - 6f * density, bottom + 16f * density), label);
        }
    }

    /** Fade in (and keep) the guide. */
    public void show() {
        animate().cancel();
        setVisibility(VISIBLE);
        animate().alpha(1f).setDuration(120).start();
    }

    /** Fade out after delayMs. */
    public void hideLater(long delayMs) {
        animate().cancel();
        animate().alpha(0f).setStartDelay(delayMs).setDuration(220)
                .withEndAction(() -> setVisibility(INVISIBLE)).start();
    }

    public void hideNow() {
        animate().cancel();
        setAlpha(0f);
        setVisibility(INVISIBLE);
    }
}
