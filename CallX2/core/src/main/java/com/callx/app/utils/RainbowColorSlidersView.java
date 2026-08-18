package com.callx.app.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import androidx.annotation.Nullable;

/**
 * RainbowColorSlidersView — the "SLIDERS" mode for the shared core rainbow
 * picker: precise RED/GREEN/BLUE sliders (each with a live numeric box and
 * a track gradient that reflects the other two channels, iOS-style), a
 * live HEX field, and an alpha/opacity slider on a checkerboard track.
 *
 * Fully two-way bound: dragging a slider updates its number box + the hex
 * field + the alpha preview; typing a number or a hex string updates the
 * sliders. {@link #setColor(int)} / {@link #getColor()} work in ARGB, so
 * callers that don't care about transparency can just ignore the alpha
 * channel (it defaults to fully opaque).
 *
 * Used from {@link RainbowStripColorPickerBottomSheet} (feature-reels strip
 * colors, feature-chat media/wallpaper colors) and, where wired in,
 * feature-status's highlight ring picker — same widget everywhere so the
 * "advanced" picker behaves identically across the app.
 */
public class RainbowColorSlidersView extends LinearLayout {

    public interface OnColorChangeListener { void onColorChanged(int argbColor); }

    private OnColorChangeListener listener;
    private boolean programmatic = false;

    private SeekBar sbR, sbG, sbB;
    private EditText etR, etG, etB, etHex;
    private AlphaSliderView alphaSlider;
    private View previewSwatch;

    private int currentColor = Color.argb(255, 91, 91, 246); // matches the default rainbow-sheet start color

    public RainbowColorSlidersView(Context ctx) { super(ctx); init(ctx); }
    public RainbowColorSlidersView(Context ctx, @Nullable AttributeSet attrs) { super(ctx, attrs); init(ctx); }

    private void init(Context ctx) {
        setOrientation(VERTICAL);

        // ── live preview row: swatch + big hex readout ──────────────────
        LinearLayout previewRow = new LinearLayout(ctx);
        previewRow.setOrientation(HORIZONTAL);
        previewRow.setGravity(Gravity.CENTER_VERTICAL);
        previewRow.setPadding(0, 0, 0, dp(ctx, 14));

        previewSwatch = new View(ctx);
        LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(dp(ctx, 32), dp(ctx, 32));
        swLp.setMarginEnd(dp(ctx, 10));
        previewSwatch.setLayoutParams(swLp);
        previewRow.addView(previewSwatch);

        TextView hexPrefix = new TextView(ctx);
        hexPrefix.setText("HEX #");
        hexPrefix.setTextSize(13);
        hexPrefix.setTextColor(Color.GRAY);
        previewRow.addView(hexPrefix);

        etHex = new EditText(ctx);
        etHex.setSingleLine(true);
        etHex.setInputType(InputType.TYPE_CLASS_TEXT);
        etHex.setFilters(new InputFilter[]{ new InputFilter.LengthFilter(8) });
        etHex.setTextSize(15);
        LinearLayout.LayoutParams hexLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        hexLp.setMarginStart(dp(ctx, 4));
        etHex.setLayoutParams(hexLp);
        previewRow.addView(etHex);
        addView(previewRow);

        etHex.addTextChangedListener(simpleWatcher(s -> {
            if (programmatic) return;
            String clean = s.trim().replace("#", "");
            if (clean.length() == 6 || clean.length() == 8) {
                try {
                    int parsed = (int) Long.parseLong(clean.length() == 6 ? "FF" + clean : clean, 16);
                    applyColor(clean.length() == 6 ? (parsed | 0xFF000000) : parsed, etHex);
                } catch (NumberFormatException ignored) { /* keep typing */ }
            }
        }));

        // ── RGB sliders ───────────────────────────────────────────────
        sbR = new SeekBar(ctx); etR = new EditText(ctx);
        sbG = new SeekBar(ctx); etG = new EditText(ctx);
        sbB = new SeekBar(ctx); etB = new EditText(ctx);
        addView(channelRow(ctx, "RED", sbR, etR));
        addView(channelRow(ctx, "GREEN", sbG, etG));
        addView(channelRow(ctx, "BLUE", sbB, etB));

        sbR.setOnSeekBarChangeListener(channelListener());
        sbG.setOnSeekBarChangeListener(channelListener());
        sbB.setOnSeekBarChangeListener(channelListener());
        etR.addTextChangedListener(channelTextWatcher(etR));
        etG.addTextChangedListener(channelTextWatcher(etG));
        etB.addTextChangedListener(channelTextWatcher(etB));

        // ── Alpha / opacity slider ───────────────────────────────────
        TextView alphaLabel = new TextView(ctx);
        alphaLabel.setText("OPACITY");
        alphaLabel.setTextSize(12);
        alphaLabel.setTextColor(Color.GRAY);
        alphaLabel.setPadding(0, dp(ctx, 6), 0, dp(ctx, 4));
        addView(alphaLabel);

        alphaSlider = new AlphaSliderView(ctx);
        LinearLayout.LayoutParams alphaLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 32));
        alphaLp.bottomMargin = dp(ctx, 4);
        addView(alphaSlider, alphaLp);
        alphaSlider.setOnAlphaChangeListener(a -> {
            if (programmatic) return;
            int r = Color.red(currentColor), g = Color.green(currentColor), b = Color.blue(currentColor);
            applyColor(Color.argb(a, r, g, b), null);
        });

        refreshAllFromColor();
    }

    public void setOnColorChangeListener(OnColorChangeListener l) { this.listener = l; }

    /** Sets the color (ARGB) without notifying the listener. */
    public void setColor(int argb) {
        currentColor = argb;
        refreshAllFromColor();
    }

    public int getColor() { return currentColor; }

    // ── internal plumbing ────────────────────────────────────────────

    private void applyColor(int argb, @Nullable View skipRefreshOf) {
        currentColor = argb;
        refreshAllFromColor();
        if (listener != null) listener.onColorChanged(currentColor);
    }

    private void refreshAllFromColor() {
        programmatic = true;
        int a = Color.alpha(currentColor), r = Color.red(currentColor), g = Color.green(currentColor), b = Color.blue(currentColor);
        sbR.setProgress(r); sbG.setProgress(g); sbB.setProgress(b);
        etR.setText(String.valueOf(r));
        etG.setText(String.valueOf(g));
        etB.setText(String.valueOf(b));
        updateChannelTracks(r, g, b);
        String hex = a >= 255
                ? String.format("%06X", (0xFFFFFF & currentColor))
                : String.format("%08X", currentColor);
        if (!hex.equalsIgnoreCase(etHex.getText().toString())) etHex.setText(hex);
        alphaSlider.setBaseColor(r, g, b);
        alphaSlider.setAlpha255(a);
        GradientDrawable swBg = new GradientDrawable();
        swBg.setShape(GradientDrawable.OVAL);
        swBg.setColor(currentColor);
        swBg.setStroke(dp(getContext(), 1), 0x33000000);
        previewSwatch.setBackground(swBg);
        programmatic = false;
    }

    private void updateChannelTracks(int r, int g, int b) {
        setTrack(sbR, Color.rgb(0, g, b), Color.rgb(255, g, b));
        setTrack(sbG, Color.rgb(r, 0, b), Color.rgb(r, 255, b));
        setTrack(sbB, Color.rgb(r, g, 0), Color.rgb(r, g, 255));
    }

    private void setTrack(SeekBar sb, int colorMin, int colorMax) {
        GradientDrawable track = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, new int[]{ colorMin, colorMax });
        track.setCornerRadius(dp(getContext(), 6));
        sb.setProgressDrawable(track);
    }

    private SeekBar.OnSeekBarChangeListener channelListener() {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser || programmatic) return;
                applyColor(Color.argb(Color.alpha(currentColor), sbR.getProgress(), sbG.getProgress(), sbB.getProgress()), seekBar);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        };
    }

    private TextWatcher channelTextWatcher(EditText et) {
        return simpleWatcher(s -> {
            if (programmatic) return;
            int v;
            try { v = Math.max(0, Math.min(255, Integer.parseInt(s.trim()))); }
            catch (NumberFormatException e) { return; }
            int r = et == etR ? v : Color.red(currentColor);
            int g = et == etG ? v : Color.green(currentColor);
            int b = et == etB ? v : Color.blue(currentColor);
            applyColor(Color.argb(Color.alpha(currentColor), r, g, b), et);
        });
    }

    private interface TextCallback { void onText(String s); }

    private TextWatcher simpleWatcher(TextCallback cb) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { cb.onText(s.toString()); }
        };
    }

    private LinearLayout channelRow(Context ctx, String label, SeekBar sb, EditText et) {
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(VERTICAL);
        col.setPadding(0, 0, 0, dp(ctx, 8));

        TextView lbl = new TextView(ctx);
        lbl.setText(label);
        lbl.setTextSize(12);
        lbl.setTextColor(Color.GRAY);
        col.addView(lbl);

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        sb.setMax(255);
        LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        sb.setLayoutParams(sbLp);
        row.addView(sb);

        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setFilters(new InputFilter[]{ new InputFilter.LengthFilter(3) });
        et.setGravity(Gravity.CENTER);
        et.setTextSize(14);
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(dp(ctx, 56), LinearLayout.LayoutParams.WRAP_CONTENT);
        etLp.setMarginStart(dp(ctx, 8));
        et.setLayoutParams(etLp);
        row.addView(et);

        col.addView(row);
        return col;
    }

    private static int dp(Context ctx, int v) { return Math.round(v * ctx.getResources().getDisplayMetrics().density); }

    // ── Alpha/opacity slider: checkerboard track + transparent→color gradient ──
    static class AlphaSliderView extends View {
        interface OnAlphaChangeListener { void onAlphaChanged(int alpha255); }

        private OnAlphaChangeListener listener;
        private int baseR = 255, baseG = 255, baseB = 255;
        private int alpha255 = 255;
        private final Paint checkerPaint = new Paint();
        private final Paint gradientPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        AlphaSliderView(Context ctx) {
            super(ctx);
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeWidth(dp(2.5f));
            ringPaint.setColor(Color.WHITE);
            setWillNotDraw(false);
        }

        void setBaseColor(int r, int g, int b) { baseR = r; baseG = g; baseB = b; buildGradient(); invalidate(); }
        void setAlpha255(int a) { alpha255 = Math.max(0, Math.min(255, a)); invalidate(); }
        int getAlpha255() { return alpha255; }
        void setOnAlphaChangeListener(OnAlphaChangeListener l) { listener = l; }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            rect.set(0, 0, w, h);
            buildCheckerTile(h);
            buildGradient();
        }

        private void buildCheckerTile(int h) {
            int size = Math.max((int) dp(6f), h > 0 ? h / 2 : (int) dp(8f));
            Bitmap bmp = Bitmap.createBitmap(size * 2, size * 2, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            Paint light = new Paint(); light.setColor(0xFFE8E8E8);
            Paint dark = new Paint(); dark.setColor(0xFFBBBBBB);
            c.drawRect(0, 0, size, size, light);
            c.drawRect(size, 0, size * 2, size, dark);
            c.drawRect(0, size, size, size * 2, dark);
            c.drawRect(size, size, size * 2, size * 2, light);
            checkerPaint.setShader(new android.graphics.BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        }

        private void buildGradient() {
            int w = getWidth();
            if (w <= 0) return;
            int opaque = Color.rgb(baseR, baseG, baseB);
            int transparent = Color.argb(0, baseR, baseG, baseB);
            gradientPaint.setShader(new LinearGradient(0, 0, w, 0, transparent, opaque, Shader.TileMode.CLAMP));
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float radius = dp(8f);
            canvas.drawRoundRect(rect, radius, radius, checkerPaint);
            canvas.drawRoundRect(rect, radius, radius, gradientPaint);
            int w = getWidth();
            float x = w > 0 ? (alpha255 / 255f) * w : 0;
            float cy = getHeight() / 2f;
            fillPaint.setColor(Color.argb(255, baseR, baseG, baseB));
            canvas.drawCircle(x, cy, dp(11f), ringPaint);
            canvas.drawCircle(x, cy, dp(8f), fillPaint);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_MOVE:
                    int w = getWidth();
                    float x = Math.max(0, Math.min(event.getX(), w));
                    alpha255 = w > 0 ? Math.round((x / w) * 255f) : alpha255;
                    invalidate();
                    if (listener != null) listener.onAlphaChanged(alpha255);
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                default:
                    return true;
            }
        }

        private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
    }
}
