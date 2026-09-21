package com.callx.app.conversation.canvas;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.text.TextPaint;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * A row of small overlapping circular avatars (+ optional grey "+N" chip),
 * drawn straight onto the bubble's Canvas — used for the reaction-badge
 * reactors and the poll-voters strip (the older seen-by strip keeps its own
 * inline copy of this logic).
 *
 * State-only + draw: the host decides WHERE (left/top/size/overlap) and WHEN
 * to invalidate. Zero per-frame allocation: one cached BitmapShader per slot
 * (rebuilt only when that slot's bitmap changes), re-aimed through one shared
 * Matrix. A slot without a bitmap (no photo / not loaded yet) draws a flat
 * grey circle.
 *
 * {@code key} identifies the set currently shown (uids + photo hashes, built
 * by the adapter): set() returns false when nothing changed so a rebind can
 * skip re-requesting bitmaps, and setBitmap() drops a late async result whose
 * key no longer matches (recycled / rebound row) instead of pinning a wrong face.
 */
final class MiniAvatarStrip {

    static final int MAX = 5;
    private static final int PLACEHOLDER_COLOR = 0xFFBDBDBD;
    private static final int CHIP_COLOR        = 0xFF9E9E9E;
    private static final int RING_COLOR        = 0xB3FFFFFF;
    private static final float CHIP_TEXT_SP    = 7f;

    private final float density;
    private final Bitmap[] bitmaps = new Bitmap[MAX];
    private final BitmapShader[] shaders = new BitmapShader[MAX];
    private final Bitmap[] shaderBmps = new Bitmap[MAX];
    private final Matrix matrix = new Matrix();
    private final Paint avatarPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint chipTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint.FontMetrics chipFm = new Paint.FontMetrics();

    @Nullable private String key;
    private int count;
    private int overflow;
    private String overflowText = "";

    MiniAvatarStrip(float density) {
        this.density = density;
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(Math.max(1f, density));
        ringPaint.setColor(RING_COLOR);
        chipTextPaint.setTextSize(CHIP_TEXT_SP * density);
        chipTextPaint.setColor(0xFFFFFFFF);
        chipTextPaint.setFakeBoldText(true);
        chipTextPaint.setTextAlign(Paint.Align.CENTER);
        chipTextPaint.getFontMetrics(chipFm);
    }

    boolean isEmpty() { return count == 0 && overflow == 0; }

    /** @return true when the shown set changed (caller then requests one bitmap per slot). */
    boolean set(@NonNull String newKey, int avatarCount, int overflowCount) {
        int c = Math.max(0, Math.min(avatarCount, MAX));
        int of = Math.max(0, overflowCount);
        if (c == 0 && of == 0) { clear(); return false; }
        if (newKey.equals(key) && c == count && of == overflow) return false;
        for (int i = 0; i < MAX; i++) bitmaps[i] = null; // stale set's faces
        key = newKey;
        count = c;
        overflow = of;
        overflowText = of > 0 ? (of > 9 ? "9+" : "+" + of) : "";
        return true;
    }

    /** @return true when the bitmap was applied (key still current, slot valid). */
    boolean setBitmap(@NonNull String forKey, int slot, @Nullable Bitmap bmp) {
        if (isEmpty() || !forKey.equals(key)) return false;
        if (slot < 0 || slot >= count) return false;
        bitmaps[slot] = bmp;
        return true;
    }

    /** Drops everything (state + bitmaps + shaders). */
    void clear() {
        clearBitmaps();
        key = null;
        count = 0;
        overflow = 0;
        overflowText = "";
    }

    /** Recycle path: release bitmaps but reset the key so the next bind re-requests them. */
    void clearBitmaps() {
        for (int i = 0; i < MAX; i++) {
            bitmaps[i] = null;
            shaders[i] = null;
            shaderBmps[i] = null;
        }
        key = null;
    }

    /** Total drawn width for the current set (avatars + optional chip). */
    float width(float size, float overlap) {
        int circles = count + (overflow > 0 ? 1 : 0);
        return circles > 0 ? size + (circles - 1) * (size - overlap) : 0f;
    }

    /** Draws left→right starting at {@code left}, circles {@code size} px, overlapping by {@code overlap} px. */
    void draw(@NonNull Canvas canvas, float left, float top, float size, float overlap) {
        final float step = size - overlap;
        final float r = size / 2f;
        final float ringR = r - ringPaint.getStrokeWidth() / 2f;
        final float cy = top + r;
        float x = left;
        for (int i = 0; i < count; i++) {
            final float cx = x + r;
            Bitmap bmp = bitmaps[i];
            if (bmp == null || bmp.isRecycled()) {
                fillPaint.setColor(PLACEHOLDER_COLOR);
                canvas.drawCircle(cx, cy, r, fillPaint);
            } else {
                BitmapShader sh = shaders[i];
                if (sh == null || shaderBmps[i] != bmp) {
                    sh = new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                    shaders[i] = sh;
                    shaderBmps[i] = bmp;
                }
                float scale = Math.max(size / bmp.getWidth(), size / bmp.getHeight());
                float dx = x - (bmp.getWidth() * scale - size) / 2f;
                float dy = top - (bmp.getHeight() * scale - size) / 2f;
                matrix.reset();
                matrix.setScale(scale, scale);
                matrix.postTranslate(dx, dy);
                sh.setLocalMatrix(matrix);
                avatarPaint.setShader(sh);
                canvas.drawCircle(cx, cy, r, avatarPaint);
            }
            canvas.drawCircle(cx, cy, ringR, ringPaint); // separates the overlap
            x += step;
        }
        if (overflow > 0) {
            final float cx = x + r;
            fillPaint.setColor(CHIP_COLOR);
            canvas.drawCircle(cx, cy, r, fillPaint);
            canvas.drawCircle(cx, cy, ringR, ringPaint);
            float baseline = cy - (chipFm.ascent + chipFm.descent) / 2f;
            canvas.drawText(overflowText, cx, baseline, chipTextPaint);
        }
    }
}
