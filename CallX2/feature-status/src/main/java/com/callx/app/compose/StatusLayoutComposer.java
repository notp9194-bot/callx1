package com.callx.app.compose;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * BUG FIX (status layout → 3 posts instead of 1): NewStatusActivity's
 * layoutPickerLauncher used to just forward the picked photos + edits
 * straight into the same postStatusBatch() path used for plain multi-select
 * (attach-sheet) posting, silently discarding the chosen layout style
 * (StatusLayoutPickerActivity.EXTRA_RESULT_LAYOUT was set by
 * StatusLayoutAdjustActivity but never read anywhere). Result: picking 2
 * photos for a "layout" produced 2 separate full-screen status posts
 * instead of the one combined collage the user actually arranged.
 *
 * This class renders that same collage — same slot geometry as
 * {@link StatusLayoutPreviewView}'s on-screen preview, just onto a Canvas
 * bitmap instead of child ImageViews — so NewStatusActivity can upload ONE
 * flattened image and save ONE status entry for a layout post.
 */
public class StatusLayoutComposer {

    private static final int CANVAS_W = 1080;
    private static final int CANVAS_H = 1920;
    private static final int GAP_PX   = 6;

    /** Renders uris (in slot order) into a single JPEG per the given layout style; returns the temp file. */
    public static File compose(Context context, List<Uri> uris, int style) throws Exception {
        List<RectF> rects = slotRects(style, CANVAS_W, CANVAS_H, GAP_PX);
        Bitmap out = Bitmap.createBitmap(CANVAS_W, CANVAS_H, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.drawColor(Color.BLACK);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

        int count = Math.min(uris.size(), rects.size());
        for (int i = 0; i < count; i++) {
            Bitmap cell = decodeSampled(context, uris.get(i), 720, 1280);
            if (cell == null) continue;
            drawCenterCrop(canvas, cell, rects.get(i), paint);
            cell.recycle();
        }

        File outFile = File.createTempFile("status_layout_", ".jpg", context.getCacheDir());
        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            out.compress(Bitmap.CompressFormat.JPEG, 90, fos);
        }
        out.recycle();
        return outFile;
    }

    private static void drawCenterCrop(Canvas canvas, Bitmap bmp, RectF rect, Paint paint) {
        float scale = Math.max(rect.width() / bmp.getWidth(), rect.height() / bmp.getHeight());
        float dw = bmp.getWidth() * scale;
        float dh = bmp.getHeight() * scale;
        float dx = rect.left + (rect.width() - dw) / 2f;
        float dy = rect.top + (rect.height() - dh) / 2f;
        Matrix m = new Matrix();
        m.postScale(scale, scale);
        m.postTranslate(dx, dy);
        int save = canvas.save();
        Path clip = new Path();
        clip.addRect(rect, Path.Direction.CW);
        canvas.clipPath(clip);
        canvas.drawBitmap(bmp, m, paint);
        canvas.restoreToCount(save);
    }

    private static Bitmap decodeSampled(Context context, Uri uri, int reqW, int reqH) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            try (InputStream is = context.getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(is, null, opts);
            }
            int sample = calculateInSampleSize(opts, reqW, reqH);
            BitmapFactory.Options opts2 = new BitmapFactory.Options();
            opts2.inSampleSize = sample;
            try (InputStream is2 = context.getContentResolver().openInputStream(uri)) {
                return BitmapFactory.decodeStream(is2, null, opts2);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        int height = options.outHeight, width = options.outWidth, inSampleSize = 1;
        if (height > reqHeight || width > reqWidth) {
            int halfHeight = height / 2, halfWidth = width / 2;
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }

    /** Mirrors StatusLayoutPreviewView's 8 layout styles as pixel rects instead of child views. */
    private static List<RectF> slotRects(int style, int w, int h, int gap) {
        List<RectF> r = new ArrayList<>();
        if (style == StatusLayoutPreviewView.STYLE_COLUMNS_2) {
            float cw = (w - gap) / 2f;
            r.add(new RectF(0, 0, cw, h));
            r.add(new RectF(cw + gap, 0, w, h));
        } else if (style == StatusLayoutPreviewView.STYLE_BIG_LEFT) {
            float leftW = w * 0.6f;
            float rightH = (h - gap) / 2f;
            r.add(new RectF(0, 0, leftW, h));
            r.add(new RectF(leftW + gap, 0, w, rightH));
            r.add(new RectF(leftW + gap, rightH + gap, w, h));
        } else if (style == StatusLayoutPreviewView.STYLE_BIG_TOP) {
            float topH = h * 0.6f;
            float botW = (w - gap) / 2f;
            r.add(new RectF(0, 0, w, topH));
            r.add(new RectF(0, topH + gap, botW, h));
            r.add(new RectF(botW + gap, topH + gap, w, h));
        } else if (style == StatusLayoutPreviewView.STYLE_BIG_RIGHT) {
            float rightW = w * 0.6f;
            float leftW = w - rightW - gap;
            float leftH = (h - gap) / 2f;
            r.add(new RectF(0, 0, leftW, leftH));
            r.add(new RectF(0, leftH + gap, leftW, h));
            r.add(new RectF(leftW + gap, 0, w, h));
        } else if (style == StatusLayoutPreviewView.STYLE_GRID_3) {
            float botW = (w - gap) / 2f;
            float topH = (h - gap) / 2f;
            r.add(new RectF(0, 0, w, topH));
            r.add(new RectF(0, topH + gap, botW, h));
            r.add(new RectF(botW + gap, topH + gap, w, h));
        } else if (style == StatusLayoutPreviewView.STYLE_GRID_5) {
            float topW = (w - gap) / 2f;
            float topH = (h - gap) / 2f;
            float botW = (w - 2 * gap) / 3f;
            r.add(new RectF(0, 0, topW, topH));
            r.add(new RectF(topW + gap, 0, w, topH));
            r.add(new RectF(0, topH + gap, botW, h));
            r.add(new RectF(botW + gap, topH + gap, botW * 2 + gap, h));
            r.add(new RectF(botW * 2 + gap * 2, topH + gap, w, h));
        } else if (style == StatusLayoutPreviewView.STYLE_GRID_6) {
            float cw = (w - 2 * gap) / 3f;
            float ch = (h - gap) / 2f;
            for (int row = 0; row < 2; row++) {
                for (int col = 0; col < 3; col++) {
                    float left = col * (cw + gap);
                    float top = row * (ch + gap);
                    r.add(new RectF(left, top, left + cw, top + ch));
                }
            }
        } else { // STYLE_GRID_2X2 (default)
            float cw = (w - gap) / 2f;
            float ch = (h - gap) / 2f;
            r.add(new RectF(0, 0, cw, ch));
            r.add(new RectF(cw + gap, 0, w, ch));
            r.add(new RectF(0, ch + gap, cw, h));
            r.add(new RectF(cw + gap, ch + gap, w, h));
        }
        return r;
    }
}
