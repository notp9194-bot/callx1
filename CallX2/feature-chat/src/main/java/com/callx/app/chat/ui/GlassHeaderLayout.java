package com.callx.app.chat.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;

import androidx.core.content.ContextCompat;

import com.callx.app.chat.R;

import java.util.ArrayList;

/**
 * Chat header that paints real glassmorphism plates behind its
 * {@link GlassImageButton} children.
 *
 * "Real" = live backdrop blur of whatever is behind the header (wallpaper +
 * scrolling messages), not just a translucent fill:
 *  - API 31+: content is recorded into a RenderNode with a blur+saturation
 *    RenderEffect (GPU, zero bitmap copies).
 *  - API 23-30: content is captured into a tiny bitmap, box-blurred, and
 *    upscaled (throttled). If that ever fails the plates degrade to tinted
 *    frosted fill instead of crashing.
 *
 * The backdrop is refreshed in a pre-draw listener WITHOUT calling
 * invalidate() (which would loop frames forever); it only runs in frames
 * something else already invalidated, so an idle chat costs nothing.
 */
public class GlassHeaderLayout extends LinearLayout {

    private static final float BLUR_DP = 18f;

    private final int sourceId;

    private final ArrayList<GlassImageButton> glassViews = new ArrayList<>(8);
    private RectF[] plateRects = new RectF[8];
    private GlassImageButton[] plateOwners = new GlassImageButton[8];

    private final Rect tmpRect = new Rect();
    private final RectF rimRect = new RectF();
    private final RectF ringRect = new RectF();
    private final Path allPlates = new Path();
    private final Matrix shaderMatrix = new Matrix();

    private final Paint tintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glossPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fallbackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final float rimWidth;
    private final GlassBackdrop backdrop;

    public GlassHeaderLayout(Context context) {
        this(context, null);
    }

    public GlassHeaderLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        int id = NO_ID;
        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.GlassHeaderLayout);
            try {
                id = a.getResourceId(R.styleable.GlassHeaderLayout_glassSource, NO_ID);
            } finally {
                a.recycle();
            }
        }
        sourceId = id;
        float d = context.getResources().getDisplayMetrics().density;
        rimWidth = 1.1f * d;
        backdrop = new GlassBackdrop(this, sourceId, BLUR_DP);

        setWillNotDraw(false);
        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setStrokeWidth(rimWidth);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(0.8f * d);

        setBackground(buildScrim(backdrop.baseColor()));
    }

    // ── Scrim ────────────────────────────────────────────────────────────

    /** Soft fade of the chat background behind the name/status text. Replaces the old solid bar. */
    public static void applyScrim(View header) {
        if (header == null) return;
        int base = ContextCompat.getColor(header.getContext(), R.color.chat_unified_bg);
        header.setBackground(buildScrim(base));
    }

    private static GradientDrawable buildScrim(int base) {
        int rgb = base & 0x00FFFFFF;
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{ (0xF2 << 24) | rgb, (0xCC << 24) | rgb, rgb });
    }

    // ── Registry ─────────────────────────────────────────────────────────

    void registerGlass(GlassImageButton g) {
        if (!glassViews.contains(g)) glassViews.add(g);
        backdrop.setEnabled(true);
        invalidate();
    }

    void unregisterGlass(GlassImageButton g) {
        glassViews.remove(g);
        backdrop.setEnabled(!glassViews.isEmpty());
    }

    // ── Lifecycle ────────────────────────────────────────────────────────

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        backdrop.attach();
    }

    @Override
    protected void onDetachedFromWindow() {
        backdrop.detach();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        backdrop.refreshTheme();
        applyScrim(this);
    }

    // ── Drawing ──────────────────────────────────────────────────────────

    @Override
    protected void dispatchDraw(Canvas canvas) {
        drawGlassPlates(canvas);
        super.dispatchDraw(canvas);   // icons on top of the glass
    }

    private void drawGlassPlates(Canvas canvas) {
        final int n = glassViews.size();
        if (n == 0) return;
        if (plateRects.length < n) {
            RectF[] nr = new RectF[n];
            System.arraycopy(plateRects, 0, nr, 0, plateRects.length);
            plateRects = nr;
            plateOwners = new GlassImageButton[n];
        }

        int count = 0;
        allPlates.rewind();
        for (int i = 0; i < n; i++) {
            GlassImageButton g = glassViews.get(i);
            if (!g.isShown() || g.getAlpha() < 0.02f || g.getWidth() <= 0) continue;
            tmpRect.set(0, 0, g.getWidth(), g.getHeight());
            try {
                offsetDescendantRectToMyCoords(g, tmpRect);
            } catch (IllegalArgumentException e) {
                continue;
            }
            RectF r = plateRects[count];
            if (r == null) r = plateRects[count] = new RectF();
            r.set(tmpRect);
            float in = g.getGlassInsetPx();
            r.inset(in, in);
            if (r.width() <= 1f || r.height() <= 1f) continue;
            float rad = g.resolveRadius(r);
            allPlates.addRoundRect(r, rad, rad, Path.Direction.CW);
            plateOwners[count] = g;
            count++;
        }
        if (count == 0) return;

        // 0) faint outer ring so the glass separates from a white background
        final boolean dark = backdrop.isDark();
        if (!dark) {
            ringPaint.setColor(0x1A0F172A);
            for (int i = 0; i < count; i++) {
                ringRect.set(plateRects[i]);
                ringRect.inset(-0.4f, -0.4f);
                float rad = plateOwners[i].resolveRadius(plateRects[i]) + 0.4f;
                canvas.drawRoundRect(ringRect, rad, rad, ringPaint);
            }
        }

        // 1) live blurred backdrop, drawn ONCE for all plates
        int save = canvas.save();
        canvas.clipPath(allPlates);
        boolean drew = backdrop.draw(canvas);
        if (!drew) {
            fallbackPaint.setColor((backdrop.baseColor() & 0x00FFFFFF) | 0xB0000000);
            canvas.drawPaint(fallbackPaint);
        }
        canvas.restoreToCount(save);

        // 2) per-plate tint, gloss, press, rim
        for (int i = 0; i < count; i++) {
            GlassImageButton g = plateOwners[i];
            RectF r = plateRects[i];
            float rad = g.resolveRadius(r);
            float w = r.width(), h = r.height();

            tintPaint.setColor(g.resolveTint(dark));
            canvas.drawRoundRect(r, rad, rad, tintPaint);

            shaderMatrix.setTranslate(r.left, r.top);
            android.graphics.Shader gloss = g.glossShader(w, h, dark);
            gloss.setLocalMatrix(shaderMatrix);
            glossPaint.setShader(gloss);
            canvas.drawRoundRect(r, rad, rad, glossPaint);

            if (g.isPressed()) {
                pressPaint.setColor(dark ? 0x33FFFFFF : 0x22000000);
                canvas.drawRoundRect(r, rad, rad, pressPaint);
            }

            android.graphics.Shader rim = g.rimShader(w, h, dark);
            rim.setLocalMatrix(shaderMatrix);
            rimPaint.setShader(rim);
            rimRect.set(r);
            rimRect.inset(rimWidth / 2f, rimWidth / 2f);
            canvas.drawRoundRect(rimRect, rad - rimWidth / 2f, rad - rimWidth / 2f, rimPaint);
        }
    }
}
