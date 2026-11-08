package com.callx.app.compose;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.DashPathEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * v750: look of ONE template photo box — shape (square / rounded / circle / heart / arch) + frame (white border, soft shadow).
 *
 * Stored per box inside {@code templateJson} as {@code "shape":"circle","bd":1,"sd":1} (all optional: a box without them is the
 * old rounded rectangle, so v742–v749 templates keep working untouched). The same class draws the three places a box
 * shows up: the final photo ({@link #render}), the empty "+" box in the composer ({@link #placeholder}) and the mini
 * preview ({@link #path}).
 */
public final class TemplateBoxStyle {

    public static final int RECT = 0, ROUND = 1, CIRCLE = 2, HEART = 3, ARCH = 4;
    public static final String[] NAMES  = {"rect", "round", "circle", "heart", "arch"};
    public static final String[] LABELS = {"Square", "Rounded", "Circle", "Heart", "Arch"};

    public int shape = ROUND;
    public boolean border, shadow;

    public TemplateBoxStyle() { }

    public TemplateBoxStyle(int shape, boolean border, boolean shadow) {
        this.shape = clampShape(shape);
        this.border = border;
        this.shadow = shadow;
    }

    public TemplateBoxStyle copy() { return new TemplateBoxStyle(shape, border, shadow); }

    /** The old (≤ v749) look: rounded rectangle, no frame. */
    public boolean isPlain() { return shape == ROUND && !border && !shadow; }

    private static int clampShape(int s) { return s < 0 || s >= NAMES.length ? ROUND : s; }

    // ── JSON ─────────────────────────────────────────────────────────────────

    /** @param o one entry of {@code slots}; null or missing keys → the plain default. */
    @NonNull
    public static TemplateBoxStyle fromJson(@Nullable JSONObject o) {
        final TemplateBoxStyle s = new TemplateBoxStyle();
        if (o == null) return s;
        final String name = o.optString("shape", "");
        for (int i = 0; i < NAMES.length; i++) if (NAMES[i].equals(name)) { s.shape = i; break; }
        s.border = o.optInt("bd", 0) == 1;
        s.shadow = o.optInt("sd", 0) == 1;
        return s;
    }

    /** Writes only what differs from the default, so plain templates stay byte-identical to v749. */
    public void putJson(@NonNull JSONObject o) throws JSONException {
        if (shape != ROUND) o.put("shape", NAMES[shape]);
        if (border) o.put("bd", 1);
        if (shadow) o.put("sd", 1);
    }

    // ── geometry ─────────────────────────────────────────────────────────────

    /** Outline of this shape inside {@code r} (circle / heart use the largest centred square). */
    @NonNull
    public static Path path(int shape, @NonNull RectF r) {
        final Path p = new Path();
        final float w = r.width(), h = r.height(), m = Math.min(w, h);
        switch (clampShape(shape)) {
            case RECT:
                p.addRect(r, Path.Direction.CW);
                break;
            case CIRCLE:
                p.addCircle(r.centerX(), r.centerY(), m / 2f, Path.Direction.CW);
                break;
            case HEART:
                heart(p, r.centerX() - m / 2f, r.centerY() - m / 2f, m);
                break;
            case ARCH: {
                final float rad = Math.min(w / 2f, h / 2f);
                p.addRoundRect(r, new float[]{rad, rad, rad, rad, 0, 0, 0, 0}, Path.Direction.CW);
                break;
            }
            default: {
                final float rr = m * 0.06f;           // exactly the v732 "roundedLayerBitmap" corner
                p.addRoundRect(r, rr, rr, Path.Direction.CW);
            }
        }
        return p;
    }

    private static void heart(Path p, float x, float y, float s) {
        p.moveTo(x + s * 0.50f, y + s * 0.95f);
        p.cubicTo(x + s * 0.12f, y + s * 0.66f, x,            y + s * 0.42f, x,            y + s * 0.28f);
        p.cubicTo(x,            y + s * 0.10f, x + s * 0.14f, y + s * 0.02f, x + s * 0.27f, y + s * 0.02f);
        p.cubicTo(x + s * 0.40f, y + s * 0.02f, x + s * 0.47f, y + s * 0.09f, x + s * 0.50f, y + s * 0.18f);
        p.cubicTo(x + s * 0.53f, y + s * 0.09f, x + s * 0.60f, y + s * 0.02f, x + s * 0.73f, y + s * 0.02f);
        p.cubicTo(x + s * 0.86f, y + s * 0.02f, x + s,          y + s * 0.10f, x + s,          y + s * 0.28f);
        p.cubicTo(x + s,          y + s * 0.42f, x + s * 0.88f, y + s * 0.66f, x + s * 0.50f, y + s * 0.95f);
        p.close();
    }

    // ── final pixels ─────────────────────────────────────────────────────────

    /**
     * Shapes + frames an already box-cropped photo. Same size in / out (the box view keeps its size); a border / shadow
     * shrinks the picture a little inside it. Anti-aliased (shader fill, no clipPath) so circle / heart edges stay smooth.
     * Software canvas only — never call it on a hardware canvas (the shadow layer would be ignored).
     */
    @NonNull
    public Bitmap render(@NonNull Bitmap src) {
        final int w = src.getWidth(), h = src.getHeight();
        final Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        final Canvas c = new Canvas(out);
        final float m = Math.min(w, h);
        final float bw = border ? Math.max(2f, m * 0.028f) : 0f;
        final float pad = shadow ? m * 0.05f : 0f;
        final float inset = pad + bw / 2f;
        final RectF area = new RectF(inset, inset, w - inset, h - inset - (shadow ? pad * 0.6f : 0f));
        if (area.width() < 2f || area.height() < 2f) area.set(0, 0, w, h);
        final Path p = path(shape, area);

        if (shadow) {
            final Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG);
            sp.setColor(0xFFFFFFFF);
            sp.setShadowLayer(pad * 0.9f, 0f, pad * 0.45f, 0x99000000);
            c.drawPath(p, sp);
        }
        final Paint pp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        final BitmapShader sh = new BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        final Matrix mx = new Matrix();
        mx.setScale(area.width() / w, area.height() / h);
        mx.postTranslate(area.left, area.top);
        sh.setLocalMatrix(mx);
        pp.setShader(sh);
        c.drawPath(p, pp);
        if (border) {
            final Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG);
            bp.setStyle(Paint.Style.STROKE);
            bp.setStrokeWidth(bw);
            bp.setStrokeJoin(Paint.Join.ROUND);
            bp.setColor(0xFFFFFFFF);
            c.drawPath(p, bp);
        }
        return out;
    }

    // ── v766: video box pieces (used by TemplateVideoCompositor) ──────────────

    /** The picture area inside a {@code w}×{@code h} box — same inset / fallback as {@link #render}. */
    @NonNull
    private RectF pictureArea(int w, int h) {
        final float m = Math.min(w, h);
        final float bw = border ? Math.max(2f, m * 0.028f) : 0f;
        final float pad = shadow ? m * 0.05f : 0f;
        final float inset = pad + bw / 2f;
        final RectF area = new RectF(inset, inset, w - inset, h - inset - (shadow ? pad * 0.6f : 0f));
        if (area.width() < 2f || area.height() < 2f) area.set(0, 0, w, h);
        return area;
    }

    /** Opaque-white shape on transparent: the video is shown only where this is opaque (anti-aliased edge). */
    @NonNull
    public Bitmap maskBitmap(int w, int h) {
        final Bitmap out = Bitmap.createBitmap(Math.max(2, w), Math.max(2, h), Bitmap.Config.ARGB_8888);
        final Canvas c = new Canvas(out);
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFFFFFFF);
        c.drawPath(path(shape, pictureArea(out.getWidth(), out.getHeight())), p);
        return out;
    }

    /**
     * Frame drawn ABOVE the video: the soft shadow (outside the shape only) and the white border. Null when the box has
     * neither (then nothing is drawn over the clip).
     */
    @Nullable
    public Bitmap frameBitmap(int w, int h) {
        if (!border && !shadow) return null;
        final Bitmap out = Bitmap.createBitmap(Math.max(2, w), Math.max(2, h), Bitmap.Config.ARGB_8888);
        final int bw0 = out.getWidth(), bh0 = out.getHeight();
        final Canvas c = new Canvas(out);
        final float m = Math.min(bw0, bh0);
        final float bw = border ? Math.max(2f, m * 0.028f) : 0f;
        final float pad = shadow ? m * 0.05f : 0f;
        final Path p = path(shape, pictureArea(bw0, bh0));
        if (shadow) {
            final Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG);
            sp.setColor(0xFFFFFFFF);
            sp.setShadowLayer(pad * 0.9f, 0f, pad * 0.45f, 0x99000000);
            c.drawPath(p, sp);
            final Paint clear = new Paint(Paint.ANTI_ALIAS_FLAG);
            clear.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR));
            c.drawPath(p, clear);          // the clip itself fills the inside, so only the outside glow stays
        }
        if (border) {
            final Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG);
            bp.setStyle(Paint.Style.STROKE);
            bp.setStrokeWidth(bw);
            bp.setStrokeJoin(Paint.Join.ROUND);
            bp.setColor(0xFFFFFFFF);
            c.drawPath(p, bp);
        }
        return out;
    }

    // ── zoom / adjust dialog ─────────────────────────────────────────────────

    /**
     * v751: overlay for the "pinch to zoom, drag to move" dialog — dims everything OUTSIDE this shape (same area the final
     * photo gets, frame / shadow inset included) and draws a thin outline, so the viewer pans to what will really show.
     */
    @NonNull
    public Drawable outsideMask(int dimColor, float density) {
        return new OutsideMask(this, dimColor, density);
    }

    private static final class OutsideMask extends Drawable {
        private final TemplateBoxStyle st;
        private final Paint dim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Path outside = new Path(), edge = new Path();

        OutsideMask(TemplateBoxStyle st, int dimColor, float d) {
            this.st = st.copy();
            dim.setColor(dimColor);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(1.5f * d);
            ring.setColor(0xCCFFFFFF);
        }

        @Override protected void onBoundsChange(Rect b) {
            final float w = b.width(), h = b.height();
            final float m = Math.min(w, h);
            final float bw = st.border ? Math.max(2f, m * 0.028f) : 0f;
            final float pad = st.shadow ? m * 0.05f : 0f;
            final float inset = pad + bw / 2f;
            final RectF area = new RectF(b.left + inset, b.top + inset, b.right - inset,
                    b.bottom - inset - (st.shadow ? pad * 0.6f : 0f));
            if (area.width() < 2f || area.height() < 2f) area.set(b.left, b.top, b.right, b.bottom); // same fallback as render()
            edge = path(st.shape, area);
            outside = new Path();
            outside.setFillType(Path.FillType.EVEN_ODD);
            outside.addRect(new RectF(b), Path.Direction.CW);
            outside.addPath(edge);
        }

        @Override public void draw(@NonNull Canvas c) {
            c.drawPath(outside, dim);
            c.drawPath(edge, ring);
        }

        @Override public void setAlpha(int a) { dim.setAlpha(a); }
        @Override public void setColorFilter(@Nullable ColorFilter cf) { dim.setColorFilter(cf); ring.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    // ── empty "+" box ────────────────────────────────────────────────────────

    /** Tinted fill + dotted outline in this shape (the box's own colour, see {@link TemplatePreviewDrawable#slotColor}). */
    @NonNull
    public Drawable placeholder(int color, float density) {
        return new Placeholder(shape, color, density);
    }

    private static final class Placeholder extends Drawable {
        private final int shape;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float inset;
        private Path path = new Path();

        Placeholder(int shape, int color, float d) {
            this.shape = shape;
            fill.setColor((color & 0x00FFFFFF) | 0x38000000);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setColor(color);
            stroke.setStrokeWidth(3f * d);
            stroke.setPathEffect(new DashPathEffect(new float[]{9f * d, 6f * d}, 0f));
            inset = 1.5f * d;
        }

        @Override protected void onBoundsChange(Rect b) {
            path = path(shape, new RectF(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset));
        }

        @Override public void draw(@NonNull Canvas c) {
            c.drawPath(path, fill);
            c.drawPath(path, stroke);
        }

        @Override public void setAlpha(int a) { /* fixed look; the view's own alpha does the pulse */ }
        @Override public void setColorFilter(@Nullable ColorFilter cf) { fill.setColorFilter(cf); stroke.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
