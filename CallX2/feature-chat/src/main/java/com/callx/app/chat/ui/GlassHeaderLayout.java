package com.callx.app.chat.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewTreeObserver;
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
    private static final float SATURATION = 1.5f;
    private static final float SOFT_SCALE = 1f / 8f;
    private static final long SOFT_MIN_INTERVAL_MS = 48L;

    private final int sourceId;
    private View source;

    private final ArrayList<GlassImageButton> glassViews = new ArrayList<>(8);
    private RectF[] plateRects = new RectF[8];
    private GlassImageButton[] plateOwners = new GlassImageButton[8];

    private final Rect tmpRect = new Rect();
    private final RectF rimRect = new RectF();
    private final RectF ringRect = new RectF();
    private final Path allPlates = new Path();
    private final Matrix shaderMatrix = new Matrix();
    private final int[] locHost = new int[2];
    private final int[] locSrc = new int[2];

    private final Paint tintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glossPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fallbackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    private final float blurPx;
    private final float rimWidth;
    private boolean dark;
    private int baseColor;

    // API 31+
    private GlassRenderNodeBackdrop nodeBackdrop;
    // API < 31
    private Bitmap softBitmap;
    private Canvas softCanvas;
    private int[] softPx, softTmp;
    private long lastSoftAt;
    private boolean softFailed;
    private boolean trailingPosted;
    private final RectF softDst = new RectF();

    private boolean backdropReady;

    private final Runnable trailingInvalidate = () -> {
        trailingPosted = false;
        invalidate();
    };

    private final ViewTreeObserver.OnPreDrawListener preDraw = () -> {
        updateBackdrop();
        return true;
    };

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
        blurPx = BLUR_DP * d;
        rimWidth = 1.1f * d;

        setWillNotDraw(false);
        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setStrokeWidth(rimWidth);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(0.8f * d);
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(SATURATION);
        bitmapPaint.setColorFilter(new ColorMatrixColorFilter(cm));

        refreshTheme();
        setBackground(buildScrim(baseColor));
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
        invalidate();
    }

    void unregisterGlass(GlassImageButton g) {
        glassViews.remove(g);
    }

    // ── Lifecycle ────────────────────────────────────────────────────────

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        refreshTheme();
        getViewTreeObserver().addOnPreDrawListener(preDraw);
    }

    @Override
    protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnPreDrawListener(preDraw);
        removeCallbacks(trailingInvalidate);
        trailingPosted = false;
        source = null;
        backdropReady = false;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        refreshTheme();
        applyScrim(this);
    }

    private void refreshTheme() {
        Context c = getContext();
        dark = (c.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        baseColor = ContextCompat.getColor(c, R.color.chat_unified_bg) | 0xFF000000;
    }

    private View resolveSource() {
        if (source == null && sourceId != NO_ID) {
            source = getRootView().findViewById(sourceId);
        }
        return source;
    }

    // ── Backdrop capture (runs in pre-draw, never invalidates) ───────────

    private void updateBackdrop() {
        if (glassViews.isEmpty() || !isShown()) return;
        final int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        View src = resolveSource();
        if (src == null || src.getWidth() <= 0) return;

        getLocationInWindow(locHost);
        src.getLocationInWindow(locSrc);
        float offX = locHost[0] - locSrc[0];
        float offY = locHost[1] - locSrc[1];

        if (Build.VERSION.SDK_INT >= 31 && isHardwareAccelerated()) {
            if (nodeBackdrop == null) nodeBackdrop = new GlassRenderNodeBackdrop(blurPx, SATURATION);
            backdropReady = nodeBackdrop.record(src, w, h, offX, offY, baseColor);
        } else {
            captureSoftware(src, w, h, offX, offY);
        }
    }

    private void captureSoftware(View src, int w, int h, float offX, float offY) {
        if (softFailed) return;
        long now = SystemClock.uptimeMillis();
        if (softBitmap != null && now - lastSoftAt < SOFT_MIN_INTERVAL_MS) {
            if (!trailingPosted) {
                trailingPosted = true;
                postDelayed(trailingInvalidate, SOFT_MIN_INTERVAL_MS);
            }
            return;
        }
        try {
            int sw = Math.max(2, Math.round(w * SOFT_SCALE));
            int sh = Math.max(2, Math.round(h * SOFT_SCALE));
            if (softBitmap == null || softBitmap.getWidth() != sw || softBitmap.getHeight() != sh) {
                softBitmap = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
                softCanvas = new Canvas(softBitmap);
                softPx = new int[sw * sh];
                softTmp = new int[sw * sh];
            }
            softBitmap.eraseColor(baseColor);
            int save = softCanvas.save();
            softCanvas.scale(sw / (float) w, sh / (float) h);
            softCanvas.translate(-offX, -offY);
            src.draw(softCanvas);
            softCanvas.restoreToCount(save);

            softBitmap.getPixels(softPx, 0, sw, 0, 0, sw, sh);
            int r = Math.max(1, Math.round(blurPx * SOFT_SCALE / 1.6f));
            for (int i = 0; i < 2; i++) {
                boxBlur(softPx, softTmp, sw, sh, r, true);
                boxBlur(softTmp, softPx, sw, sh, r, false);
            }
            softBitmap.setPixels(softPx, 0, sw, 0, 0, sw, sh);
            lastSoftAt = now;
            backdropReady = true;
        } catch (Throwable t) {
            // e.g. hardware bitmaps can't be drawn on a software canvas
            softFailed = true;
            backdropReady = false;
            softBitmap = null;
        }
    }

    private static int clamp(int v, int max) {
        return v < 0 ? 0 : (v > max ? max : v);
    }

    /** Edge-clamped sliding-window box blur, horizontal or vertical. */
    private static void boxBlur(int[] src, int[] dst, int w, int h, int r, boolean horizontal) {
        final int div = 2 * r + 1;
        final int lines = horizontal ? h : w;
        final int len = horizontal ? w : h;
        final int lineStride = horizontal ? w : 1;
        final int step = horizontal ? 1 : w;
        for (int l = 0; l < lines; l++) {
            final int base = l * lineStride;
            int sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int p = src[base + clamp(i, len - 1) * step];
                sr += (p >> 16) & 255; sg += (p >> 8) & 255; sb += p & 255;
            }
            for (int i = 0; i < len; i++) {
                dst[base + i * step] = 0xFF000000 | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
                int add = src[base + clamp(i + r + 1, len - 1) * step];
                int rem = src[base + clamp(i - r, len - 1) * step];
                sr += ((add >> 16) & 255) - ((rem >> 16) & 255);
                sg += ((add >> 8) & 255) - ((rem >> 8) & 255);
                sb += (add & 255) - (rem & 255);
            }
        }
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
        boolean drew = drawBackdrop(canvas);
        if (!drew) {
            fallbackPaint.setColor((baseColor & 0x00FFFFFF) | 0xB0000000);
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

    private boolean drawBackdrop(Canvas canvas) {
        if (!backdropReady) return false;
        if (Build.VERSION.SDK_INT >= 31 && nodeBackdrop != null && canvas.isHardwareAccelerated()) {
            nodeBackdrop.draw(canvas);
            return true;
        }
        if (softBitmap != null && !softFailed) {
            softDst.set(0, 0, getWidth(), getHeight());
            canvas.drawBitmap(softBitmap, null, softDst, bitmapPaint);
            return true;
        }
        return false;
    }
}
