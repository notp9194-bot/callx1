package com.callx.app.stickers;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.ArrayList;
import java.util.List;

/**
 * "Music Sticker Options" sheet — one place for everything a music sticker can be adjusted by.
 * Every control acts LIVE on the {@link StatusStickerOverlayView} it was opened for, and every
 * value is saved in that sticker's JSON, so Status composer, Reels editors and all viewers
 * (which build the card from the same JSON) show exactly what was chosen.
 *
 *  1. Choose Style   — 8 looks (compact bar, card, floating icon, gradient bar, album-cover pill,
 *                      minimal icon, animated wave, big cover)
 *  2. Customize      — colour (swatches + the app's shared rainbow sheet), background
 *                      (Solid / Blur / Gradient / None), size, opacity, corner radius, rotate
 *     Toggles        — Auto Play, Loop, Show Artist, Shake Effect
 */
public final class MusicStickerOptionsSheet {

    private MusicStickerOptionsSheet() {}

    // palette (dark purple, like the reference design)
    private static final int C_SHEET   = 0xFF14101F;
    private static final int C_CARD    = 0xFF221B33;
    private static final int C_ACCENT  = 0xFF9B5CFF;
    private static final int C_TEXT    = 0xFFFFFFFF;
    private static final int C_SUBTEXT = 0xFFB8B0CC;
    private static final int C_LINE    = 0x33FFFFFF;

    private static final String[] SWATCHES = {
        "#9B5CFF", "#2D9CFF", "#FF3DA5", "#FF7A1A", "#FFB000", "#18C964", "#FFFFFF"
    };

    /** style index → {glyph, label}; order = grid order (4 per row). */
    private static final Object[][] STYLES = {
        {StatusStickerOverlayView.MUSIC_STYLE_COMPACT_BAR,    "\u2590\u258C\u2590", "Compact Bar"},
        {StatusStickerOverlayView.MUSIC_STYLE_CARD,           "\uD83D\uDDBC",       "Card Style"},
        {StatusStickerOverlayView.MUSIC_STYLE_FLOATING_ICON,  "\uD83C\uDFB5",       "Floating Icon"},
        {StatusStickerOverlayView.MUSIC_STYLE_GRADIENT_BAR,   "\u2590\u258C\u2590", "Gradient Bar"},
        {StatusStickerOverlayView.MUSIC_STYLE_ALBUM_PILL,     "\uD83D\uDCBF",       "Album Cover Pill"},
        {StatusStickerOverlayView.MUSIC_STYLE_MINIMAL_ICON,   "\u266B",             "Minimal Icon"},
        {StatusStickerOverlayView.MUSIC_STYLE_ANIMATED_WAVE,  "\u223F\u223F",       "Animated Wave"},
        {StatusStickerOverlayView.MUSIC_STYLE_BIG_COVER,      "\uD83C\uDF04",       "Big Cover"},
    };

    /**
     * @param onChanged called after every change (host can refresh its size bar / rebuild the JSON it posts).
     */
    public static void show(Context ctx, final StatusStickerOverlayView st, @Nullable final Runnable onChanged) {
        if (st == null || !"music".equals(st.getStickerType())) return;
        final List<Runnable> refreshers = new ArrayList<>();
        final Runnable changed = () -> { if (onChanged != null) onChanged.run(); };
        final Runnable refreshAll = () -> { for (Runnable r : refreshers) r.run(); };

        final BottomSheetDialog sheet = new BottomSheetDialog(ctx);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(ctx, 18), dp(ctx, 14), dp(ctx, 18), dp(ctx, 28));

        // ── title ──
        TextView title = text(ctx, "\uD83C\uDFB5  Music Sticker Options", 18, C_TEXT, true);
        title.setPadding(0, 0, 0, dp(ctx, 10));
        root.addView(title);

        // ── 1. Choose Style ──
        root.addView(sectionLabel(ctx, "1. Choose Style"));
        LinearLayout gridHost = new LinearLayout(ctx);
        gridHost.setOrientation(LinearLayout.VERTICAL);
        final List<View> tileBoxes = new ArrayList<>();
        for (int rowStart = 0; rowStart < STYLES.length; rowStart += 4) {
            LinearLayout r = new LinearLayout(ctx);
            r.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = rowStart; i < Math.min(STYLES.length, rowStart + 4); i++) {
                final int style = (Integer) STYLES[i][0];
                LinearLayout tile = new LinearLayout(ctx);
                tile.setOrientation(LinearLayout.VERTICAL);
                tile.setGravity(Gravity.CENTER_HORIZONTAL);
                TextView box = text(ctx, (String) STYLES[i][1], 20, C_TEXT, true);
                box.setGravity(Gravity.CENTER);
                box.setTag(style);
                tile.addView(box, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 54)));
                TextView lbl = text(ctx, (String) STYLES[i][2], 11, C_SUBTEXT, false);
                lbl.setGravity(Gravity.CENTER);
                lbl.setPadding(0, dp(ctx, 4), 0, 0);
                tile.addView(lbl);
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                tlp.setMargins(dp(ctx, 3), dp(ctx, 3), dp(ctx, 3), dp(ctx, 8));
                r.addView(tile, tlp);
                tileBoxes.add(box);
                tile.setOnClickListener(v -> {
                    st.setMusicStyle(style);
                    if (style == StatusStickerOverlayView.MUSIC_STYLE_GRADIENT_BAR) {
                        st.setMusicBgMode(StatusStickerOverlayView.MUSIC_BG_GRADIENT);
                    }
                    refreshAll.run();
                    changed.run();
                });
            }
            gridHost.addView(r);
        }
        root.addView(gridHost);
        refreshers.add(() -> {
            for (View b : tileBoxes) {
                boolean sel = ((Integer) b.getTag()) == st.getMusicStyle();
                GradientDrawable g = new GradientDrawable();
                g.setCornerRadius(dp(ctx, 14));
                g.setColor(C_CARD);
                g.setStroke(dp(ctx, sel ? 2 : 1), sel ? C_ACCENT : C_LINE);
                b.setBackground(g);
            }
        });

        // ── 2. Customize ──
        root.addView(sectionLabel(ctx, "2. Customize Style"));

        // Color
        root.addView(smallLabel(ctx, "Color"));
        final LinearLayout swatchRow = new LinearLayout(ctx);
        swatchRow.setOrientation(LinearLayout.HORIZONTAL);
        swatchRow.setGravity(Gravity.CENTER_VERTICAL);
        final List<View> swatchViews = new ArrayList<>();
        // "Auto" (cover-tinted default)
        swatchViews.add(addSwatch(ctx, swatchRow, null, v -> {
            st.setMusicBgColor(null);
            refreshAll.run(); changed.run();
        }));
        for (final String hex : SWATCHES) {
            swatchViews.add(addSwatch(ctx, swatchRow, hex, v -> {
                st.setMusicBgColor(hex);
                refreshAll.run(); changed.run();
            }));
        }
        // Rainbow → the app's shared rainbow colour sheet
        final View rainbow = new View(ctx);
        GradientDrawable rg = new GradientDrawable();
        rg.setShape(GradientDrawable.OVAL);
        rg.setGradientType(GradientDrawable.SWEEP_GRADIENT);
        rg.setColors(new int[]{0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFF0000FF, 0xFFFF00FF, 0xFFFF0000});
        rainbow.setBackground(rg);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(dp(ctx, 30), dp(ctx, 30));
        rlp.setMargins(dp(ctx, 5), dp(ctx, 4), dp(ctx, 5), dp(ctx, 4));
        swatchRow.addView(rainbow, rlp);
        rainbow.setOnClickListener(v -> st.showMusicBgColorPicker(ctx, () -> { refreshAll.run(); changed.run(); }));
        android.widget.HorizontalScrollView swatchScroll = new android.widget.HorizontalScrollView(ctx);
        swatchScroll.setHorizontalScrollBarEnabled(false);
        swatchScroll.addView(swatchRow);
        root.addView(swatchScroll);
        refreshers.add(() -> {
            String cur = st.getMusicBgColor();
            for (View sv : swatchViews) {
                Object tag = sv.getTag();   // hex or "auto"
                boolean sel = (cur == null && "auto".equals(tag))
                        || (cur != null && tag instanceof String && ((String) tag).equalsIgnoreCase(cur));
                styleSwatch(ctx, sv, sel);
            }
        });

        // Background mode
        root.addView(smallLabel(ctx, "Background"));
        LinearLayout seg = new LinearLayout(ctx);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable segBg = new GradientDrawable();
        segBg.setCornerRadius(dp(ctx, 20));
        segBg.setColor(C_CARD);
        seg.setBackground(segBg);
        seg.setPadding(dp(ctx, 4), dp(ctx, 4), dp(ctx, 4), dp(ctx, 4));
        final String[] modes = {StatusStickerOverlayView.MUSIC_BG_SOLID, StatusStickerOverlayView.MUSIC_BG_BLUR,
                StatusStickerOverlayView.MUSIC_BG_GRADIENT, StatusStickerOverlayView.MUSIC_BG_NONE};
        final String[] modeNames = {"Solid", "Blur", "Gradient", "None"};
        final List<TextView> segViews = new ArrayList<>();
        for (int i = 0; i < modes.length; i++) {
            final String m = modes[i];
            TextView t = text(ctx, modeNames[i], 13, C_TEXT, false);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(ctx, 8), 0, dp(ctx, 8));
            t.setTag(m);
            t.setOnClickListener(v -> { st.setMusicBgMode(m); refreshAll.run(); changed.run(); });
            seg.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            segViews.add(t);
        }
        root.addView(seg);
        refreshers.add(() -> {
            for (TextView t : segViews) {
                boolean sel = t.getTag().equals(st.getMusicBgMode());
                GradientDrawable g = new GradientDrawable();
                g.setCornerRadius(dp(ctx, 16));
                g.setColor(sel ? C_ACCENT : Color.TRANSPARENT);
                t.setBackground(g);
                t.setTypeface(null, sel ? Typeface.BOLD : Typeface.NORMAL);
            }
        });

        // Sliders (two per row, like the reference)
        LinearLayout sliderRow1 = new LinearLayout(ctx);
        sliderRow1.setOrientation(LinearLayout.HORIZONTAL);
        sliderRow1.addView(sliderBlock(ctx, "Size", 30, 250, new SliderBinding() {
            @Override public int get() { return Math.round(st.getStickerScale() * 100f); }
            @Override public void set(int v) { st.applyScale(v / 100f); }
            @Override public String fmt(int v) { return v + "%"; }
        }, refreshers, changed), halfLp(ctx, true));
        sliderRow1.addView(sliderBlock(ctx, "Opacity", 0, 100, new SliderBinding() {
            @Override public int get() { return st.getMusicOpacity(); }
            @Override public void set(int v) { st.setMusicOpacity(v); }
            @Override public String fmt(int v) { return v + "%"; }
        }, refreshers, changed), halfLp(ctx, false));
        root.addView(sliderRow1);

        LinearLayout sliderRow2 = new LinearLayout(ctx);
        sliderRow2.setOrientation(LinearLayout.HORIZONTAL);
        sliderRow2.addView(sliderBlock(ctx, "Corner Radius", 0, 40, new SliderBinding() {
            @Override public int get() { return st.getMusicRadiusDp(); }
            @Override public void set(int v) { st.setMusicRadiusDp(v); }
            @Override public String fmt(int v) { return String.valueOf(v); }
        }, refreshers, changed), halfLp(ctx, true));
        sliderRow2.addView(sliderBlock(ctx, "Rotate", -180, 180, new SliderBinding() {
            @Override public int get() { return Math.round(st.getStickerRotationDeg()); }
            @Override public void set(int v) { st.applyRotation(v); }
            @Override public String fmt(int v) { return v + "\u00B0"; }
        }, refreshers, changed), halfLp(ctx, false));
        root.addView(sliderRow2);

        // Toggles
        LinearLayout toggles = new LinearLayout(ctx);
        toggles.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams tgp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tgp.topMargin = dp(ctx, 18);
        toggles.addView(toggle(ctx, "\u25B6", "Auto Play", new ToggleBinding() {
            @Override public boolean get() { return st.isMusicAutoPlay(); }
            @Override public void set(boolean on) { st.setMusicAutoPlay(on); }
        }, refreshers, changed));
        toggles.addView(toggle(ctx, "\uD83D\uDD01", "Loop", new ToggleBinding() {
            @Override public boolean get() { return st.isMusicLoop(); }
            @Override public void set(boolean on) { st.setMusicLoop(on); }
        }, refreshers, changed));
        toggles.addView(toggle(ctx, "\uD83D\uDC64", "Show Artist", new ToggleBinding() {
            @Override public boolean get() { return st.isMusicShowArtist(); }
            @Override public void set(boolean on) { st.setMusicShowArtist(on); }
        }, refreshers, changed));
        toggles.addView(toggle(ctx, "\uD83D\uDCF3", "Shake Effect", new ToggleBinding() {
            @Override public boolean get() { return st.isMusicShake(); }
            @Override public void set(boolean on) { st.setMusicShake(on); }
        }, refreshers, changed));
        root.addView(toggles, tgp);

        refreshAll.run();

        NestedScrollView scroll = new NestedScrollView(ctx);
        scroll.addView(root);
        sheet.setContentView(scroll);
        if (sheet.getWindow() != null) sheet.getWindow().setDimAmount(0f);   // keep the sticker visible behind the sheet
        FrameLayout bs = sheet.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (bs != null) {
            GradientDrawable sbg = new GradientDrawable();
            float r = dp(ctx, 26);
            sbg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
            sbg.setColor(C_SHEET);
            bs.setBackground(sbg);
            BottomSheetBehavior<FrameLayout> b = BottomSheetBehavior.from(bs);
            b.setPeekHeight((int) (ctx.getResources().getDisplayMetrics().heightPixels * 0.58f));
            b.setSkipCollapsed(false);
            b.setState(BottomSheetBehavior.STATE_COLLAPSED);
        }
        sheet.show();
    }

    // ── slider / toggle plumbing ─────────────────────────────────────────

    private interface SliderBinding { int get(); void set(int v); String fmt(int v); }
    private interface ToggleBinding { boolean get(); void set(boolean on); }

    private static View sliderBlock(Context ctx, String label, final int min, final int max,
                                    final SliderBinding b, List<Runnable> refreshers, final Runnable changed) {
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, dp(ctx, 14), 0, 0);

        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        TextView name = text(ctx, label, 13, C_TEXT, false);
        final TextView value = text(ctx, "", 13, C_SUBTEXT, false);
        value.setGravity(Gravity.END);
        head.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(value);
        col.addView(head);

        final SeekBar sb = new SeekBar(ctx);
        sb.setMax(max - min);
        sb.setProgressTintList(ColorStateList.valueOf(C_ACCENT));
        sb.setThumbTintList(ColorStateList.valueOf(C_TEXT));
        sb.setProgressBackgroundTintList(ColorStateList.valueOf(0x44FFFFFF));
        sb.setPadding(dp(ctx, 4), dp(ctx, 6), dp(ctx, 4), dp(ctx, 6));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                int v = p + min;
                value.setText(b.fmt(v));
                if (!fromUser) return;
                b.set(v);
                changed.run();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        col.addView(sb);
        refreshers.add(() -> {
            int v = Math.max(min, Math.min(max, b.get()));
            sb.setProgress(v - min);
            value.setText(b.fmt(v));
        });
        return col;
    }

    private static View toggle(Context ctx, String glyph, String label, final ToggleBinding b,
                               List<Runnable> refreshers, final Runnable changed) {
        final LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(ctx, 4), dp(ctx, 10), dp(ctx, 4), dp(ctx, 10));
        TextView g = text(ctx, glyph, 20, C_TEXT, false);
        g.setGravity(Gravity.CENTER);
        final TextView l = text(ctx, label, 11, C_TEXT, false);
        l.setGravity(Gravity.CENTER);
        l.setPadding(0, dp(ctx, 4), 0, 0);
        box.addView(g);
        box.addView(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(ctx, 3), 0, dp(ctx, 3), 0);
        box.setLayoutParams(lp);
        box.setOnClickListener(v -> {
            b.set(!b.get());
            for (Runnable r : refreshersOf(refreshers)) r.run();
            changed.run();
        });
        refreshers.add(() -> {
            boolean on = b.get();
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(ctx, 16));
            bg.setColor(on ? 0x449B5CFF : C_CARD);
            bg.setStroke(dp(ctx, on ? 2 : 1), on ? C_ACCENT : C_LINE);
            box.setBackground(bg);
            l.setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
            box.setAlpha(on ? 1f : 0.85f);
        });
        return box;
    }

    private static List<Runnable> refreshersOf(List<Runnable> r) { return new ArrayList<>(r); }

    // ── small view helpers ───────────────────────────────────────────────

    private static View addSwatch(Context ctx, LinearLayout row, @Nullable String hex, View.OnClickListener l) {
        TextView v = new TextView(ctx);
        v.setGravity(Gravity.CENTER);
        v.setTextColor(C_TEXT);
        v.setTextSize(11);
        v.setTag(hex == null ? "auto" : hex);
        if (hex == null) v.setText("A");
        v.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(ctx, 30), dp(ctx, 30));
        lp.setMargins(dp(ctx, 5), dp(ctx, 4), dp(ctx, 5), dp(ctx, 4));
        row.addView(v, lp);
        styleSwatch(ctx, v, false);
        return v;
    }

    private static void styleSwatch(Context ctx, View v, boolean selected) {
        Object tag = v.getTag();
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor("auto".equals(tag) ? 0xFF2A2340 : Color.parseColor((String) tag));
        g.setStroke(dp(ctx, selected ? 3 : 1), selected ? C_TEXT : C_LINE);
        v.setBackground(g);
    }

    private static LinearLayout.LayoutParams halfLp(Context ctx, boolean left) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (left) lp.rightMargin = dp(ctx, 12); else lp.leftMargin = dp(ctx, 12);
        return lp;
    }

    private static TextView sectionLabel(Context ctx, String s) {
        TextView t = text(ctx, s, 16, C_TEXT, true);
        t.setPadding(0, dp(ctx, 12), 0, dp(ctx, 8));
        return t;
    }

    private static TextView smallLabel(Context ctx, String s) {
        TextView t = text(ctx, s, 13, C_TEXT, false);
        t.setPadding(0, dp(ctx, 8), 0, dp(ctx, 6));
        return t;
    }

    private static TextView text(Context ctx, String s, int sp, int color, boolean bold) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    private static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
