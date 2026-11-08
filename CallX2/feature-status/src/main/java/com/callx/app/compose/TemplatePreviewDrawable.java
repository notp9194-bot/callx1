package com.callx.app.compose;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
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
 *
 * <p>v785 MULTI-SCENE: the root {@code "slots"} are scene 0, {@code "scenes"[i].slots} (i ≥ 1) are the other scenes
 * ({@link TemplateSpec}). {@link #photoCount()} is the TOTAL over all scenes; {@link #setScene(int)} picks the scene that is drawn.</p>
 *
 * <p>v785 SAMPLE MODE ({@link #setSampleMode(boolean)}): instead of empty dotted boxes every box shows a small built-in sample picture
 * (sun + hills) that goes through the box's real filter ({@link TemplateEffects#colorFilter}) and plays the box's real animation
 * ({@link TemplateEffects#animState}) at {@link #setTimeMs(long)}. Used by the Template Preview and the Template Editor so the
 * filter / animation is seen, not guessed. A template carries no photos, so the picture is a sample, never the creator's photo.</p>
 */
public final class TemplatePreviewDrawable extends Drawable {

    /** Preview aspect (w / h) — same 9:16 as the story canvas. */
    public static final float ASPECT = 9f / 16f;

    private static final int MAX_BOXES = 12;
    private static final int MAX_SCENES = TemplateSpec.MAX_SCENES;

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

    /** Second colour of the sample picture's sky gradient (first = the box colour). */
    private static final int[] SAMPLE_SKY = {
            0xFF1E3C72, 0xFF8E2DE2, 0xFF5B247A, 0xFF0F5C4A, 0xFF2B1B6E, 0xFFE65100
    };

    /** One scene: its boxes (fractions of the canvas) and the per-box effects. */
    private static final class Scn {
        final float[][] boxes = new float[MAX_BOXES][5];   // {cx, cy, w, h, rot(deg)}
        final int[] shapes = new int[MAX_BOXES];            // v750: TemplateBoxStyle shape per box
        final boolean[] video = new boolean[MAX_BOXES];
        final String[] flt = new String[MAX_BOXES];
        final String[] an = new String[MAX_BOXES];
        final int[] durMs = new int[MAX_BOXES];
        final ColorMatrixColorFilter[] cf = new ColorMatrixColorFilter[MAX_BOXES];
        int boxCount;
        final float[] baseFit = new float[5];
        boolean hasBaseFit;
        int sceneMs = TemplateSpec.DEF_SCENE_MS;

        /** @return false when {@code slots} has nothing usable. */
        boolean parse(@NonNull JSONArray slots) {
            for (int i = 0; i < slots.length(); i++) {
                final JSONObject o = slots.optJSONObject(i);
                if (o == null) continue;
                if (o.optBoolean("base", false)) {
                    if (o.optBoolean("fit", false) && o.has("w") && o.has("h")) {
                        hasBaseFit = true;
                        baseFit[0] = (float) o.optDouble("cx", 0.5);
                        baseFit[1] = (float) o.optDouble("cy", 0.5);
                        baseFit[2] = (float) o.optDouble("w", 1.0);
                        baseFit[3] = (float) o.optDouble("h", 1.0);
                        baseFit[4] = (float) o.optDouble("rot", 0.0);
                    }
                    continue;
                }
                if (boxCount >= MAX_BOXES) break;
                final int k = boxCount++;
                shapes[k] = TemplateBoxStyle.fromJson(o).shape;
                final TemplateSpec.Slot sp = TemplateSpec.Slot.fromJson(o);
                video[k] = sp.isVideo();
                flt[k] = sp.filter;
                an[k] = sp.anim;
                durMs[k] = sp.effectiveMs();
                cf[k] = TemplateEffects.colorFilter(sp.filter);
                final float[] b = boxes[k];
                b[0] = (float) o.optDouble("cx", 0.5);
                b[1] = (float) o.optDouble("cy", 0.5);
                b[2] = (float) o.optDouble("w", 0.5);
                b[3] = (float) o.optDouble("h", 0.3);
                b[4] = (float) o.optDouble("rot", 0.0);
            }
            return boxCount > 0 || hasBaseFit || slots.length() > 0;
        }
    }

    private final Scn[] scn = new Scn[MAX_SCENES];
    private int sceneCount = 1;
    private int curScene;

    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint plus = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF();
    // perf: everything bounds-dependent is built once in onBoundsChange(), draw() allocates nothing
    private final Path clipPath = new Path();
    private final Path shapePath = new Path();
    private float cCorner, cSw;
    private boolean boundsReady;
    private int alpha = 255;
    /** v769: animated preview — box i is "in focus" when the phase is i; < 0 = no emphasis (static preview, as before). */
    private float focusPhase = -1f;
    private final float[] animBox = new float[5];

    // v785 sample mode
    private boolean sampleMode;
    private long timeMs;
    private int animOnly = -1;                    // >= 0: only this box plays its animation (the others rest), < 0: all play
    private final float[] st = new float[4];     // animState scratch
    private final Paint sky = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sun = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Shader[] skyShader = new Shader[SLOT_COLORS.length];
    private final Path hillA = new Path(), hillB = new Path(), playPath = new Path();

    private TemplatePreviewDrawable() {
        stroke.setStyle(Paint.Style.STROKE);
        plus.setStyle(Paint.Style.STROKE);
        plus.setStrokeCap(Paint.Cap.ROUND);
        scn[0] = new Scn();
    }

    /** @return the preview, or null when the JSON has no photo box (bad / legacy template). */
    @Nullable
    public static TemplatePreviewDrawable fromJson(@Nullable String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            return fromRoot(new JSONObject(json));
        } catch (Exception e) {
            return null;
        }
    }

    /** Same as {@link #fromJson(String)} for an already parsed root (parse the JSON text once, reuse it). */
    @Nullable
    public static TemplatePreviewDrawable fromRoot(@Nullable JSONObject root) {
        if (root == null) return null;
        try {
            final JSONArray slots = root.getJSONArray("slots");
            final TemplatePreviewDrawable d = new TemplatePreviewDrawable();
            if (!d.scn[0].parse(slots)) return null;

            // v785: scenes 1.. (same rules as TemplateSpec: a broken scene → plain one-scene template)
            final JSONArray sc = root.optJSONArray("scenes");
            if (sc != null && sc.length() >= 2) {
                final Scn[] tmpScn = new Scn[MAX_SCENES];
                tmpScn[0] = d.scn[0];
                int n = 1;
                boolean broken = false;
                final JSONObject s0 = sc.optJSONObject(0);
                if (s0 != null) tmpScn[0].sceneMs = TemplateSpec.clampSceneMs(s0.optInt("d", TemplateSpec.DEF_SCENE_MS));
                for (int i = 1; i < sc.length() && i < MAX_SCENES; i++) {
                    final JSONObject so = sc.optJSONObject(i);
                    final JSONArray ss = so == null ? null : so.optJSONArray("slots");
                    if (ss == null || ss.length() == 0) { broken = true; break; }
                    final Scn s = new Scn();
                    s.sceneMs = TemplateSpec.clampSceneMs(so.optInt("d", TemplateSpec.DEF_SCENE_MS));
                    s.parse(ss);
                    tmpScn[n++] = s;
                }
                if (!broken && n >= 2) {
                    System.arraycopy(tmpScn, 0, d.scn, 0, n);
                    d.sceneCount = n;
                }
            }
            return d;
        } catch (Exception e) {
            return null;
        }
    }

    // ── scenes ───────────────────────────────────────────────────────────────

    /** Number of scenes (1 for an ordinary template). */
    public int sceneCount() { return sceneCount; }

    public int currentScene() { return curScene; }

    /** Picks the scene that is drawn (clamped). */
    public void setScene(int i) {
        final int n = Math.max(0, Math.min(sceneCount - 1, i));
        if (n == curScene) return;
        curScene = n;
        invalidateSelf();
    }

    /** Length of scene {@code i} in ms (a one-scene template: the template's own default scene length). */
    public int sceneMs(int i) { return scn[Math.max(0, Math.min(sceneCount - 1, i))].sceneMs; }

    /** Photo boxes of scene {@code i} (base photo NOT counted). */
    public int boxCount(int i) { return scn[Math.max(0, Math.min(sceneCount - 1, i))].boxCount; }

    /** Photos the viewer has to fill in ONE scene (base counts as one). */
    public int scenePhotoCount(int i) { return boxCount(i) + 1; }

    /**
     * Photos the viewer has to fill in the WHOLE template (v785: every scene counts — it used to count scene 0 only, so a
     * 3-scene template looked like a 1-scene one on the cards and in the sort order). Base photos count as one each.
     */
    public int photoCount() {
        int n = 0;
        for (int i = 0; i < sceneCount; i++) n += scn[i].boxCount + 1;
        return n;
    }

    // ── focus / sample ───────────────────────────────────────────────────────

    /** v769: drives the Preview screen's loop (boxes light up one after another). Pass a negative value to stop. */
    public void setFocusPhase(float phase) {
        focusPhase = phase;
        invalidateSelf();
    }

    /** v785: sample pictures with the real filter / animation instead of empty dotted boxes. */
    public void setSampleMode(boolean on) {
        if (sampleMode == on) return;
        sampleMode = on;
        invalidateSelf();
    }

    /** v785: playback position inside the current scene (ms) — animations are a function of this. */
    public void setTimeMs(long ms) {
        timeMs = Math.max(0L, ms);
        if (sampleMode) invalidateSelf();
    }

    /** v785: only box {@code i} of the current scene plays its animation (others rest); negative = all play. */
    public void setAnimOnly(int i) {
        animOnly = i;
        if (sampleMode) invalidateSelf();
    }

    /** v785: live-edit one box (the Template Editor) — {@code null} keeps the current value. */
    public void setBoxEffect(int scene, int box, @Nullable String filter, @Nullable String anim, int durMs) {
        if (scene < 0 || scene >= sceneCount) return;
        final Scn s = scn[scene];
        if (box < 0 || box >= s.boxCount) return;
        if (filter != null) { s.flt[box] = filter; s.cf[box] = TemplateEffects.colorFilter(filter); }
        if (anim != null) s.an[box] = anim;
        if (durMs > 0) s.durMs[box] = TemplateSpec.clampSlotMs(durMs);
        invalidateSelf();
    }

    /** v785: live-edit a scene's length (the Template Editor). */
    public void setSceneMs(int scene, int ms) {
        if (scene < 0 || scene >= sceneCount) return;
        scn[scene].sceneMs = TemplateSpec.clampSceneMs(ms);
    }

    // ── drawing ──────────────────────────────────────────────────────────────

    @Override
    protected void onBoundsChange(@NonNull Rect r) {
        super.onBoundsChange(r);
        final float w = r.width(), h = r.height();
        boundsReady = w > 0 && h > 0;
        if (!boundsReady) return;
        cCorner = Math.min(w, h) * 0.16f;
        cSw = Math.max(1f, w * 0.045f);
        bg.setShader(new LinearGradient(r.left, r.top, r.left, r.bottom, 0xFF2B1B3D, 0xFF0F0A18, Shader.TileMode.CLAMP));
        stroke.setStrokeWidth(cSw * 0.7f);
        stroke.setPathEffect(new DashPathEffect(new float[]{cSw * 1.8f, cSw * 1.2f}, 0f));
        plus.setStrokeWidth(Math.max(1f, cSw * 0.6f));
        tmp.set(r);
        clipPath.rewind();
        clipPath.addRoundRect(tmp, cCorner, cCorner, Path.Direction.CW);
    }

    @Override
    public void draw(@NonNull Canvas c) {
        final Rect r = getBounds();
        if (!boundsReady) { onBoundsChange(r); if (!boundsReady) return; }
        final float corner = cCorner;

        // backdrop (shader / clip path cached in onBoundsChange)
        bg.setAlpha(alpha);
        tmp.set(r);
        c.drawRoundRect(tmp, corner, corner, bg);

        final int save = c.save();
        c.clipPath(clipPath);
        final Scn s = scn[curScene];
        if (sampleMode) drawSample(c, r, s, corner); else drawStatic(c, r, s, corner);
        c.restoreToCount(save);
    }

    private void drawStatic(Canvas c, Rect r, Scn s, float corner) {
        // base photo
        fill.setColor(0x33FFFFFF);
        fill.setAlpha(Math.min(alpha, 0x55));
        if (s.hasBaseFit) {
            drawBox(c, r, s.baseFit, fill, null, 0f, false, TemplateBoxStyle.ROUND);
        } else {
            tmp.set(r);
            c.drawRect(tmp, fill);
        }

        // photo boxes
        for (int i = 0; i < s.boxCount; i++) {
            final int col = slotColor(i + 1);          // same colour the composer gives this box
            float k = 1f;                               // v769: 1 = in focus, 0 = idle (only while animating)
            float[] bx = s.boxes[i];
            if (focusPhase >= 0f && s.boxCount > 0) {
                float dd = Math.abs(focusPhase - i);
                dd = Math.min(dd, s.boxCount - dd);
                k = Math.max(0f, 1f - dd);
                final float sc = 1f + 0.07f * k;
                System.arraycopy(bx, 0, animBox, 0, 5);
                animBox[2] *= sc;
                animBox[3] *= sc;
                bx = animBox;
            }
            final float ka = focusPhase >= 0f ? 0.35f + 0.65f * k : 1f;
            stroke.setColor(col); stroke.setAlpha(Math.round(alpha * ka));
            plus.setColor(col);   plus.setAlpha(Math.round(alpha * ka));
            fill.setColor(col);   fill.setAlpha(Math.round(Math.min(alpha, focusPhase >= 0f ? (0x33 + (int) (0x66 * k)) : 0x55)));
            drawBox(c, r, bx, fill, stroke, corner * 0.45f, true, s.shapes[i]);
        }
    }

    // ── v785 sample mode ─────────────────────────────────────────────────────

    private void ensureSample() {
        if (skyShader[0] != null) return;
        for (int i = 0; i < skyShader.length; i++)
            skyShader[i] = new LinearGradient(-0.5f, -0.5f, 0.5f, 0.5f, SLOT_COLORS[i], SAMPLE_SKY[i], Shader.TileMode.CLAMP);
        // unit square centred on (0,0): -0.5 .. 0.5
        hillA.moveTo(-0.5f, 0.5f);
        hillA.lineTo(-0.5f, 0.12f);
        hillA.quadTo(-0.25f, -0.14f, 0.05f, 0.14f);
        hillA.quadTo(0.22f, 0.26f, 0.5f, 0.02f);
        hillA.lineTo(0.5f, 0.5f);
        hillA.close();
        hillB.moveTo(-0.5f, 0.5f);
        hillB.lineTo(-0.5f, 0.32f);
        hillB.quadTo(-0.1f, 0.14f, 0.3f, 0.34f);
        hillB.quadTo(0.42f, 0.4f, 0.5f, 0.3f);
        hillB.lineTo(0.5f, 0.5f);
        hillB.close();
        playPath.moveTo(-0.35f, -0.5f);
        playPath.lineTo(0.5f, 0f);
        playPath.lineTo(-0.35f, 0.5f);
        playPath.close();
        sun.setColor(0xFFFFF4C2);
        hill.setColor(0xFF000000);
        playP.setColor(0xFFFFFFFF);
    }

    private void drawSample(Canvas c, Rect r, Scn s, float corner) {
        ensureSample();
        // base photo: full canvas (or the fit rectangle), natural filter, no animation
        if (s.hasBaseFit) {
            final float w = r.width(), h = r.height();
            final float bw = Math.max(w * 0.08f, s.baseFit[2] * w), bh = Math.max(h * 0.05f, s.baseFit[3] * h);
            final float cx = r.left + s.baseFit[0] * w, cy = r.top + s.baseFit[1] * h;
            final int sv = c.save();
            c.rotate(s.baseFit[4], cx, cy);
            tmp.set(cx - bw / 2f, cy - bh / 2f, cx + bw / 2f, cy + bh / 2f);
            c.clipPath(TemplateBoxStyle.path(TemplateBoxStyle.ROUND, tmp, shapePath));
            drawContent(c, tmp.left, tmp.top, bw, bh, 0, null, 1f, 0f, 0f, 1f, false);
            c.restoreToCount(sv);
        } else {
            drawContent(c, r.left, r.top, r.width(), r.height(), 0, null, 1f, 0f, 0f, 0.9f, false);
        }

        final float w = r.width(), h = r.height();
        for (int i = 0; i < s.boxCount; i++) {
            final float[] b = s.boxes[i];
            final float bw = Math.max(w * 0.08f, b[2] * w), bh = Math.max(h * 0.05f, b[3] * h);
            final float cx = r.left + b[0] * w, cy = r.top + b[1] * h;

            // animation state at this box's own progress (all boxes, or only the selected one while editing)
            st[0] = 1f; st[1] = 0f; st[2] = 0f; st[3] = 1f;
            if (animOnly < 0 || animOnly == i) {
                final float dur = sceneCount > 1 ? (s.video[i] ? Math.min(s.sceneMs, s.durMs[i]) : s.sceneMs) : s.durMs[i];
                TemplateEffects.animState(s.an[i], dur <= 0 ? 1f : Math.min(1f, timeMs / dur), st);
            }
            float ka = 1f;
            if (focusPhase >= 0f) ka = Math.abs(focusPhase - i) < 0.5f ? 1f : 0.4f;   // editor: the other boxes dim

            final int sv = c.save();
            c.rotate(b[4], cx, cy);
            tmp.set(cx - bw / 2f, cy - bh / 2f, cx + bw / 2f, cy + bh / 2f);
            final int shape = s.shapes[i];
            c.clipPath(shape == TemplateBoxStyle.ROUND
                    ? roundPath(tmp, corner * 0.45f)
                    : TemplateBoxStyle.path(shape, tmp, shapePath));
            drawContent(c, tmp.left, tmp.top, bw, bh, i + 1, s.cf[i], st[0], st[1], st[2], st[3] * ka, s.video[i]);
            c.restoreToCount(sv);
        }
    }

    private Path roundPath(RectF rect, float rad) {
        shapePath.rewind();
        shapePath.addRoundRect(rect, rad, rad, Path.Direction.CW);
        return shapePath;
    }

    /** Sample picture in a box: sky gradient + sun + two hills, zoomed / panned / faded like the real animation. */
    private void drawContent(Canvas c, float left, float top, float bw, float bh, int colorIdx,
                             @Nullable ColorMatrixColorFilter cf, float zoom, float panX, float panY, float a, boolean video) {
        final float side = Math.max(bw, bh);
        final float cx = left + bw / 2f, cy = top + bh / 2f;
        final float freeX = Math.max(0f, (side * zoom - bw) / 2f), freeY = Math.max(0f, (side * zoom - bh) / 2f);
        final int ia = Math.max(0, Math.min(255, Math.round(alpha * a)));
        sky.setShader(skyShader[Math.floorMod(colorIdx, skyShader.length)]);
        sky.setColorFilter(cf); sun.setColorFilter(cf); hill.setColorFilter(cf);
        sky.setAlpha(ia);
        sun.setAlpha(Math.round(ia * 0.9f));
        final int sv = c.save();
        c.translate(cx - panX * freeX, cy - panY * freeY);   // window moves right → picture drifts left
        final float sc = side * zoom;
        c.scale(sc, sc);
        tmp.set(-0.5f, -0.5f, 0.5f, 0.5f);
        c.drawRect(tmp, sky);
        c.drawCircle(0.2f, -0.18f, 0.1f, sun);
        hill.setAlpha(Math.round(ia * 0.28f));
        c.drawPath(hillA, hill);
        hill.setAlpha(Math.round(ia * 0.45f));
        c.drawPath(hillB, hill);
        c.restoreToCount(sv);
        if (video) {   // small play mark so a video box is told apart from a photo box
            final float s = Math.min(bw, bh) * 0.22f;
            final int sv2 = c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            playP.setAlpha(Math.round(ia * 0.85f));
            c.drawPath(playPath, playP);
            c.restoreToCount(sv2);
        }
        tmp.set(left, top, left + bw, top + bh);
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
            final Path sp = TemplateBoxStyle.path(shape, tmp, shapePath);
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
