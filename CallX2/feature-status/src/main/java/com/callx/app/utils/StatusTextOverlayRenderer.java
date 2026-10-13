package com.callx.app.utils;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * Status "text as an overlay layer" — same idea as Reels' ReelTextOverlayRenderer.
 *
 * The composer's text is NOT burned into the photo's pixels any more. It is saved as a
 * {"type":"overlaytext", ...} entry in StatusItem.stickersJson (position/size/rotation/style as
 * data) and the viewer draws it fresh, as a real TextView, on top of the photo. So the photo can
 * be compressed as hard as it needs to while the text stays sharp.
 *
 * Both sides live here so what the poster styled and what the viewer draws can never drift:
 * {@link #toJson} (composer) and {@link #build} / {@link #place} (viewer).
 */
public final class StatusTextOverlayRenderer {

    public static final String TYPE = "overlaytext";

    private StatusTextOverlayRenderer() {}

    /** True for a stickersJson entry that is a text overlay (not a StatusStickerOverlayView sticker). */
    public static boolean isOverlayText(JSONObject o) {
        return o != null && TYPE.equals(o.optString("type"));
    }

    // Typeface.create() goes through the font manager; five fixed looks -> create once, reuse forever.
    private static final Typeface[] TYPEFACES = new Typeface[5];

    public static Typeface typeface(int idx) {
        int i = idx >= 0 && idx < TYPEFACES.length ? idx : 1;
        Typeface t = TYPEFACES[i];
        if (t == null) {
            switch (i) {
                case 0:  t = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL); break;
                case 2:  t = Typeface.create(Typeface.SERIF, Typeface.BOLD); break;
                case 3:  t = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); break;
                case 4:  t = Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC); break;
                default: t = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); break;
            }
            TYPEFACES[i] = t; // benign race: worst case two equal Typefaces are created once
        }
        return t;
    }

    private static boolean isLight(int c) {
        return (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f > 0.6f;
    }

    /** Composer side: serialise the on-canvas text. Positions are fractions of the canvas, sizes are dp-based. */
    public static String toJson(Context ctx, TextView tv, int color, int bgMode, int sizeSp,
                                int fontIdx, int alignIdx, int frameWpx, int frameHpx, int maxWidthPx) {
        try {
            float d = ctx.getResources().getDisplayMetrics().density;
            JSONObject o = new JSONObject();
            o.put("type", TYPE);
            o.put("text", tv.getText().toString());
            o.put("color", color);
            o.put("bgMode", bgMode);
            o.put("sizeSp", sizeSp);
            o.put("font", fontIdx);
            o.put("align", alignIdx);
            o.put("cx", (tv.getX() + tv.getWidth() / 2f) / frameWpx);
            o.put("cy", (tv.getY() + tv.getHeight() / 2f) / frameHpx);
            o.put("scale", tv.getScaleX());
            o.put("rot", tv.getRotation());
            o.put("frameWdp", frameWpx / d);
            o.put("maxWdp", maxWidthPx / d);
            return o.toString();
        } catch (Exception e) {
            return null; // caller keeps baking the text into the photo instead
        }
    }

    /** Viewer side: a styled TextView, identical to the composer's applyEditorTextStyle(). */
    public static TextView build(Context ctx, JSONObject o) {
        float d = ctx.getResources().getDisplayMetrics().density;
        TextView tv = new TextView(ctx);
        int color = o.optInt("color", Color.WHITE);
        int bgMode = o.optInt("bgMode", 0);
        tv.setTypeface(typeface(o.optInt("font", 1)));
        tv.setTextSize(o.optInt("sizeSp", 30));
        int align = o.optInt("align", 0);
        int hg = align == 1 ? Gravity.START : align == 2 ? Gravity.END : Gravity.CENTER_HORIZONTAL;
        tv.setGravity(Gravity.CENTER_VERTICAL | hg);
        if (bgMode == 1) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(color);
            bg.setCornerRadius(10 * d);
            tv.setBackground(bg);
            tv.setTextColor(isLight(color) ? Color.BLACK : Color.WHITE);
        } else if (bgMode == 2) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0x99000000);
            bg.setCornerRadius(10 * d);
            tv.setBackground(bg);
            tv.setTextColor(color);
        } else {
            tv.setTextColor(color);
            tv.setShadowLayer(4 * d, 0, 2 * d, isLight(color) ? Color.BLACK : Color.WHITE);
        }
        tv.setPadding((int) (12 * d), (int) (8 * d), (int) (12 * d), (int) (8 * d));
        tv.setMaxWidth((int) (o.optDouble("maxWdp", 280) * d));
        tv.setText(o.optString("text", ""));
        tv.setClickable(false);
        tv.setFocusable(false);
        return tv;
    }

    /**
     * Adds the overlay to {@code frame}, positioned inside {@code fit} (the rectangle the photo
     * really occupies in the frame, so it lines up with the photo even when the viewer's card is
     * not the same shape as the composer's canvas) and scaled by fit width / composer width.
     */
    public static void place(FrameLayout frame, JSONObject o, float fitLeft, float fitTop,
                             float fitW, float fitH) {
        Context ctx = frame.getContext();
        float d = ctx.getResources().getDisplayMetrics().density;
        TextView tv = build(ctx, o);
        if (tv.getText().length() == 0) return;
        frame.addView(tv, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START));
        // maxWidth already bounds the text, so measure once against that bound (no 4000px worst case).
        tv.measure(View.MeasureSpec.makeMeasureSpec(tv.getMaxWidth(), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        float posterW = (float) o.optDouble("frameWdp", fitW / d);
        float k = posterW > 0f ? (fitW / d) / posterW : 1f;
        float s = (float) o.optDouble("scale", 1.0) * k;
        tv.setScaleX(s);
        tv.setScaleY(s);
        tv.setRotation((float) o.optDouble("rot", 0.0));
        tv.setTranslationX(fitLeft + (float) o.optDouble("cx", 0.5) * fitW - tv.getMeasuredWidth() / 2f);
        tv.setTranslationY(fitTop + (float) o.optDouble("cy", 0.5) * fitH - tv.getMeasuredHeight() / 2f);
    }
}
