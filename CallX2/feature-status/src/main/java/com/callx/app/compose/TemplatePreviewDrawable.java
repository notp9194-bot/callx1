package com.callx.app.compose;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * v742: tiny 9:16 preview of a photo-slot template ({@code StatusItem.templateJson}).
 *
 * Drawn straight from the slot geometry (fractions of the story canvas) — no bitmap, no upload, no extra read — so the
 * "Use this template" chip and the composer's "Make template" pill can show the layout in a few dp. A base photo that
 * was saved in fit/blur mode (see {@code NewStatusActivity#buildTemplateJson}) is drawn as its own lighter rectangle on a
 * dark backdrop; a full-cover base fills the whole canvas.
 */
public final class TemplatePreviewDrawable extends Drawable {

    /** Preview aspect (w / h) — same 9:16 as the story canvas. */
    public static final float ASPECT = 9f / 16f;

    private static final int MAX_BOXES = 12;

    /** v747: every empty template box gets its own colour (dotted outline + "+" + tint) so boxes are told apart at a glance. */
    private static final int[] SLOT_COLORS = {
            0xFF4FC3F7, // sky blue
            0xFFFFB74D, // orange
            0xFFF06292, // pink
            0xFF81C784, // green
            0xFFBA68C8, // violet
            0xFFFFF176  // yellow
    };
    /** @param index 0 = base photo, 1.. = boxes in template order */
    public static int slotColor(int index) { return SLOT_COLORS[Math.floorMod(index, SLOT_COLORS.length)]; }

    // {cx, cy, w, h, rot} per photo box, fractions of the canvas (rot in degrees)
    private final float[][] boxes = new float[MAX_BOXES][5];
    private final int[] shapes = new int[MAX_BOXES];   // v750: TemplateBoxStyle shape per box
    private int boxCount;
    private final float[] baseFit = new float[5];
    private boolean hasBaseFit;

    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint plus = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF();
    private int alpha = 255;

    private TemplatePreviewDrawable() {
        stroke.setStyle(Paint.Style.STROKE);
        plus.setStyle(Paint.Style.STROKE);
        plus.setStrokeCap(Paint.Cap.ROUND);
    }

    /** @return the preview, or null when the JSON has no photo box (bad / legacy template). */
    @Nullable
    public static TemplatePreviewDrawable fromJson(@Nullable String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            final JSONArray slots = new JSONObject(json).getJSONArray("slots");
            final TemplatePreviewDrawable d = new TemplatePreviewDrawable();
            for (int i = 0; i < slots.length(); i++) {
                final JSONObject o = slots.getJSONObject(i);
                if (o.optBoolean("base", false)) {
                    if (o.optBoolean("fit", false) && o.has("w") && o.has("h")) {
                        d.hasBaseFit = true;
                        d.baseFit[0] = (float) o.optDouble("cx", 0.5);
                        d.baseFit[1] = (float) o.optDouble("cy", 0.5);
                        d.baseFit[2] = (float) o.optDouble("w", 1.0);
                        d.baseFit[3] = (float) o.optDouble("h", 1.0);
                        d.baseFit[4] = (float) o.optDouble("rot", 0.0);
                    }
                    continue;
                }
                if (d.boxCount >= MAX_BOXES) break;
                d.shapes[d.boxCount] = TemplateBoxStyle.fromJson(o).shape;
                final float[] b = d.boxes[d.boxCount++];
                b[0] = (float) o.optDouble("cx", 0.5);
                b[1] = (float) o.optDouble("cy", 0.5);
                b[2] = (float) o.optDouble("w", 0.5);
                b[3] = (float) o.optDouble("h", 0.3);
                b[4] = (float) o.optDouble("rot", 0.0);
            }
            return d.boxCount > 0 || d.hasBaseFit || slots.length() > 0 ? d : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Number of photo boxes the viewer has to fill (base photo counts as one). */
    public int photoCount() {
        return boxCount + 1;
    }

    @Override
    public void draw(@NonNull Canvas c) {
        final Rect r = getBounds();
        final float w = r.width(), h = r.height();
        if (w <= 0 || h <= 0) return;
        final float corner = Math.min(w, h) * 0.16f;
        final float sw = Math.max(1f, w * 0.045f);

        // backdrop
        bg.setShader(new LinearGradient(r.left, r.top, r.left, r.bottom, 0xFF2B1B3D, 0xFF0F0A18, Shader.TileMode.CLAMP));
        bg.setAlpha(alpha);
        tmp.set(r);
        c.drawRoundRect(tmp, corner, corner, bg);

        final int save = c.save();
        final android.graphics.Path clip = new android.graphics.Path();
        clip.addRoundRect(tmp, corner, corner, android.graphics.Path.Direction.CW);
        c.clipPath(clip);

        // base photo
        fill.setColor(0x33FFFFFF);
        fill.setAlpha(Math.min(alpha, 0x55));
        if (hasBaseFit) {
            drawBox(c, r, baseFit, fill, null, 0f, false, TemplateBoxStyle.ROUND);
        } else {
            tmp.set(r);
            c.drawRect(tmp, fill);
        }

        // photo boxes
        stroke.setStrokeWidth(sw * 0.7f);
        stroke.setPathEffect(new DashPathEffect(new float[]{sw * 1.8f, sw * 1.2f}, 0f));
        plus.setStrokeWidth(Math.max(1f, sw * 0.6f));
        for (int i = 0; i < boxCount; i++) {
            final int col = slotColor(i + 1);          // same colour the composer gives this box
            stroke.setColor(col); stroke.setAlpha(alpha);
            plus.setColor(col);   plus.setAlpha(alpha);
            fill.setColor(col);   fill.setAlpha(Math.min(alpha, 0x55));
            drawBox(c, r, boxes[i], fill, stroke, corner * 0.45f, true, shapes[i]);
        }
        c.restoreToCount(save);
    }

    private void drawBox(Canvas c, Rect r, float[] b, Paint fillP, @Nullable Paint strokeP, float rad, boolean withPlus, int shape) {
        final float w = r.width(), h = r.height();
        final float bw = Math.max(w * 0.08f, b[2] * w), bh = Math.max(h * 0.05f, b[3] * h);
        final float cx = r.left + b[0] * w, cy = r.top + b[1] * h;
        final int s = c.save();
        c.rotate(b[4], cx, cy);
        tmp.set(cx - bw / 2f, cy - bh / 2f, cx + bw / 2f, cy + bh / 2f);
        if (shape == TemplateBoxStyle.ROUND) {
            c.drawRoundRect(tmp, rad, rad, fillP);
            if (strokeP != null) c.drawRoundRect(tmp, rad, rad, strokeP);
        } else {   // v750: square / circle / heart / arch
            final android.graphics.Path sp = TemplateBoxStyle.path(shape, tmp);
            c.drawPath(sp, fillP);
            if (strokeP != null) c.drawPath(sp, strokeP);
        }
        if (withPlus) {
            final float arm = Math.min(bw, bh) * 0.18f;
            c.drawLine(cx - arm, cy, cx + arm, cy, plus);
            c.drawLine(cx, cy - arm, cx, cy + arm, plus);
        }
        c.restoreToCount(s);
    }

    @Override public int getIntrinsicWidth() { return -1; }
    @Override public int getIntrinsicHeight() { return -1; }
    @Override public void setAlpha(int a) { alpha = a; invalidateSelf(); }
    @Override public void setColorFilter(@Nullable ColorFilter cf) { bg.setColorFilter(cf); fill.setColorFilter(cf); stroke.setColorFilter(cf); plus.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
