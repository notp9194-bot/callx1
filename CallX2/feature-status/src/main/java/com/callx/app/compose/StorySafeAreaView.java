package com.callx.app.compose;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.WindowManager;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.callx.app.status.R;
import com.callx.app.utils.StoryChromePrefs;
import com.callx.app.utils.StoryFrameSpec;

/**
 * Dotted "safe area" guide for the status editor: shades the strips the viewer's own header /
 * caption / screen edges will cover and draws a dashed safe rectangle (top, bottom, left, right).
 * Touch-transparent, never baked into the photo.
 *
 * v639: uses the REAL viewer window size + window insets (status bar, cutout) and the viewer's
 * measured header height (StoryChromePrefs) instead of dp constants + DisplayMetrics; draws the
 * vertical (left/right) guide; and always shows the safe rect (faint) even when nothing overlaps.
 */
public final class StorySafeAreaView extends View {
    private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dash  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] insets = new float[4];   // top, bottom, left, right (canvas px)
    private final int[] viewer = new int[2];
    private final float density;
    private final String topText, botText, okText;
    private boolean pinned;   // pinned = subtle always-on mode (faint border, light shade)

    // window insets (px) — refreshed via the insets listener
    private float sysTop, sysBottom, sysLeft, sysRight;

    public StorySafeAreaView(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        topText = c.getString(R.string.story_safe_top);
        botText = c.getString(R.string.story_safe_bottom);
        okText  = c.getString(R.string.story_safe_ok);
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
        sysTop = statusBarFallbackPx();
        // Observe (never consume) window insets so the guide follows notch / status bar / gestures.
        ViewCompat.setOnApplyWindowInsetsListener(this, (v, wi) -> {
            readInsets(wi);
            invalidate();
            return wi;
        });
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ViewCompat.requestApplyInsets(this);
    }

    private void readInsets(WindowInsetsCompat wi) {
        // The viewer hides system bars, so its card is positioned from the visibility-ignoring top inset
        // (status bar + cutout) but only a cutout / ime-less bottom inset (see StatusViewerActivity#setupEdgeToEdge).
        Insets topIns = wi.getInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
        Insets cut = wi.getInsets(WindowInsetsCompat.Type.displayCutout());
        sysTop = topIns.top;
        sysBottom = cut.bottom;
        sysLeft = cut.left;
        sysRight = cut.right;
    }

    private float statusBarFallbackPx() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 24f * density;
    }

    /** Real viewer window size: the full display in the current rotation (the viewer is immersive). */
    private void resolveViewerSize() {
        WindowManager wm = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            viewer[0] = dm.widthPixels; viewer[1] = dm.heightPixels;
            return;
        }
        if (Build.VERSION.SDK_INT >= 30) {
            Rect b = wm.getMaximumWindowMetrics().getBounds();
            viewer[0] = b.width(); viewer[1] = b.height();
        } else {
            DisplayMetrics real = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(real);
            viewer[0] = real.widthPixels; viewer[1] = real.heightPixels;
        }
    }

    /** Recomputes the 4 safe insets (canvas px) for the current size / window. */
    private void refreshInsets(int w, int h) {
        resolveViewerSize();
        StoryFrameSpec.safeInsets(w, h, viewer[0], viewer[1], density,
                sysTop, sysBottom, sysLeft, sysRight,
                StoryChromePrefs.topPx(getContext()), 0f, insets);
    }

    /** Pinned = subtle always-on mode: faint border + light shade so it never hides the canvas while editing. */
    public void setPinned(boolean pinned) {
        if (this.pinned == pinned) return;
        this.pinned = pinned;
        invalidate();
    }

    /** True when {@code v} (text / sticker in the same frame) lies fully inside the safe rect. */
    public boolean isInsideSafe(View v) {
        int w = getWidth(), h = getHeight();
        if (v == null || w <= 0 || h <= 0) return true;
        refreshInsets(w, h);
        return StoryFrameSpec.isInsideSafe(
                v.getX() + v.getWidth() / 2f, v.getY() + v.getHeight() / 2f,
                v.getWidth() * v.getScaleX(), v.getHeight() * v.getScaleY(),
                v.getRotation(), w, h, insets);
    }

    /**
     * (dx, dy) in canvas px that would bring {@code v}'s rotated + scaled bounds fully inside the safe rect
     * ({0,0} when already inside, or when it is larger than the safe rect on that axis).
     */
    public float[] nudgeIntoSafe(View v) {
        int w = getWidth(), h = getHeight();
        if (v == null || w <= 0 || h <= 0) return new float[]{0f, 0f};
        refreshInsets(w, h);
        float l = insets[2], t = insets[0], r = w - insets[3], b = h - insets[1];
        double rad = Math.toRadians(v.getRotation());
        float c = (float) Math.abs(Math.cos(rad)), sn = (float) Math.abs(Math.sin(rad));
        float sw = v.getWidth() * Math.abs(v.getScaleX()), sh = v.getHeight() * Math.abs(v.getScaleY());
        float hw = (sw * c + sh * sn) / 2f, hh = (sw * sn + sh * c) / 2f;
        float cx = v.getX() + v.getWidth() / 2f, cy = v.getY() + v.getHeight() / 2f;
        float dx = 0f, dy = 0f;
        if (hw * 2f <= r - l) {
            if (cx - hw < l) dx = l - (cx - hw);
            else if (cx + hw > r) dx = r - (cx + hw);
        }
        if (hh * 2f <= b - t) {
            if (cy - hh < t) dy = t - (cy - hh);
            else if (cy + hh > b) dy = b - (cy + hh);
        }
        return new float[]{dx, dy};
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        refreshInsets(w, h);
        float t = insets[0], b = h - insets[1], l = insets[2], r = w - insets[3];
        boolean any = insets[0] > 0f || insets[1] > 0f || insets[2] > 0f || insets[3] > 0f;

        shade.setColor(pinned ? 0x22000000 : 0x44000000);
        // shaded strips (left/right only between the horizontal bands so corners aren't shaded twice)
        if (t > 0f) c.drawRect(0, 0, w, t, shade);
        if (insets[1] > 0f) c.drawRect(0, b, w, h, shade);
        if (l > 0f) c.drawRect(0, t, l, b, shade);
        if (insets[3] > 0f) c.drawRect(r, t, w, b, shade);

        // dashed safe rectangle — dimmer when nothing overlaps or when pinned, so it never looks "broken/empty"
        float inset = dash.getStrokeWidth() / 2f;
        dash.setAlpha(pinned ? (any ? 0x80 : 0x50) : (any ? 0xE6 : 0x66));
        c.drawRect(Math.max(l, inset), Math.max(t, inset),
                Math.min(r, w - inset), Math.min(b, h - inset), dash);

        label.setAlpha(pinned ? 0x88 : 0xCC);
        if (t > 0f) c.drawText(topText, w / 2f, Math.max(12f * density, t - 6f * density), label);
        if (insets[1] > 0f) c.drawText(botText, w / 2f, Math.min(h - 6f * density, b + 16f * density), label);
        if (!any) c.drawText(okText, w / 2f, 18f * density, label);
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
