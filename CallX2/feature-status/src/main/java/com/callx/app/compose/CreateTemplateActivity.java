package com.callx.app.compose;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.WindowCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.core.widget.NestedScrollView;

import com.bumptech.glide.Glide;
import com.callx.app.status.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * v773 (part 1) — Screen 3 "Create Template": Layout / Music / Effects tabs + Frames + Next.
 *
 *  - Layout: 6 ready box layouts (every layout = the poster's base photo full-screen + 1..3 boxes on top). Each tile is drawn live
 *    by {@link TemplatePreviewDrawable} from the SAME templateJson the template will really get, so frame / shape changes show at once.
 *  - Frames: box shape (square / rounded / circle / heart / arch) + Border + Shadow ({@link TemplateBoxStyle}). Category chip row.
 *  - Music: real track from Trending Audio ({@code ReelTrendingAudioActivity}, by class name) + Music Sync ({@code msync}).
 *  - Effects: default filter + animation for every box ({@link TemplateSpec} {@code flt} / {@code an}, same engine as the v772 Editor).
 *  - Next: opens the normal composer in "create" mode ({@link NewStatusActivity#EXTRA_CREATE_TEMPLATE}): the poster drops sample photos
 *    into the boxes and posts; the status is saved as a template carrying exactly this json (no "used from" credit, no use counted).
 *  - v773 part 2 — Multi-Scene: toggle + scene strip (layout + length per scene, add / remove). Saved as {@code scenes[]} in templateJson
 *    ({@link TemplateSpec}: scene 0 stays the root {@code slots}); the composer plays the scenes one after another into one mp4.
 *
 * Premium iOS look (white surface, purple accent, rounded cards); every icon is an existing project drawable.
 */
public class CreateTemplateActivity extends AppCompatActivity {

    /** v774: open an existing draft (My Templates > Drafts) — String draft id. */
    public static final String EXTRA_DRAFT_ID = "draftId";

    private static final int PURPLE = 0xFF6A3DE8, PURPLE_LIGHT = 0xFF9A7BFF;
    private static final int TAB_LAYOUT = 0, TAB_MUSIC = 1, TAB_EFFECTS = 2;

    /** Boxes of each layout on the 9:16 canvas: {cx, cy, w, h, rotDeg} (fractions of the canvas). The base photo is always full-screen. */
    private static final float[][][] LAYOUTS = {
            {{0.70f, 0.76f, 0.50f, 0.28f, -5f}},
            {{0.30f, 0.80f, 0.42f, 0.22f, -4f}, {0.72f, 0.22f, 0.42f, 0.22f, 4f}},
            {{0.27f, 0.82f, 0.42f, 0.20f, 0f}, {0.73f, 0.82f, 0.42f, 0.20f, 0f}},
            {{0.18f, 0.84f, 0.28f, 0.17f, 0f}, {0.50f, 0.84f, 0.28f, 0.17f, 0f}, {0.82f, 0.84f, 0.28f, 0.17f, 0f}},
            {{0.50f, 0.55f, 0.68f, 0.38f, -6f}},
            {{0.80f, 0.24f, 0.34f, 0.19f, 3f}, {0.80f, 0.50f, 0.34f, 0.19f, -3f}, {0.80f, 0.76f, 0.34f, 0.19f, 3f}},
    };
    private static final int[] LAYOUT_NAMES = {R.string.ct_lay_corner, R.string.ct_lay_duo, R.string.ct_lay_pair,
            R.string.ct_lay_strip, R.string.ct_lay_polaroid, R.string.ct_lay_column};
    private static final int[] CAT_NAMES = {R.string.tpl_cat_love, R.string.tpl_cat_travel, R.string.tpl_cat_birthday,
            R.string.tpl_cat_festival, R.string.tpl_cat_aesthetic};

    private int tab = TAB_LAYOUT, category = -1;
    /** v773 part 2: one scene = a layout + how long it plays. A single-scene template just uses scene 0 (no "scenes" key written). */
    private static final class Sc { int layout; int ms = TemplateSpec.DEF_SCENE_MS; }
    private static final int[] SCENE_MS = {2000, 3000, 4000, 5000, 8000, 10000};
    private final ArrayList<Sc> scenes = new ArrayList<>();
    private int curScene = 0;
    private boolean multi;
    private String draftId;          // v774: set once this work has been saved as a draft
    private String cleanState = "";  // state at open / last save — differs => "save draft?" on back
    private final TemplateBoxStyle frame = new TemplateBoxStyle();
    private String filter = TemplateSpec.DEF_FILTER, anim = TemplateSpec.DEF_ANIM;
    private boolean musicSync;
    private String mId = "", mTitle = "", mArtist = "", mCover = "", mUrl = "";

    private boolean dark;
    private int bg, surface, textColor, subColor;

    private NestedScrollView scroll;
    private LinearLayout content;
    private final List<TextView> tabPills = new ArrayList<>();
    private ActivityResultLauncher<Intent> musicLauncher;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        final TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.colorBackground, tv, true);
        bg = tv.data;
        dark = ColorUtils.calculateLuminance(bg) < 0.5;
        surface = dark ? 0x22FFFFFF : 0xFFF2F0F8;
        textColor = dark ? 0xFFFFFFFF : 0xFF14142B;
        subColor = dark ? 0xAAFFFFFF : 0xFF8A8AA3;

        scenes.add(new Sc());
        draftId = getIntent().getStringExtra(EXTRA_DRAFT_ID);
        if (draftId != null) restoreDraft();
        cleanState = stateJson();
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { confirmLeave(); }
        });

        musicLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
            final Intent d = r.getData();
            if (r.getResultCode() != RESULT_OK || d == null) return;
            final String title = d.getStringExtra("audio_title");
            if (title == null || title.trim().isEmpty()) return;
            mTitle = title.trim();
            mId = nz(d.getStringExtra("audio_id"));
            mArtist = nz(d.getStringExtra("audio_artist"));
            mCover = nz(d.getStringExtra("audio_cover_url"));
            final String prev = nz(d.getStringExtra("audio_preview_url"));
            mUrl = !prev.isEmpty() ? prev : nz(d.getStringExtra("audio_url"));
            showTab(TAB_MUSIC);
        });

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        root.setBackgroundColor(bg);
        buildTopBar(root);
        root.addView(buildTabs(), new LinearLayout.LayoutParams(-1, -2));

        scroll = new NestedScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(6), dp(16), dp(16));
        scroll.addView(content, new ViewGroup.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        root.addView(buildNextBar(), new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
        if (!dark) WindowCompat.getInsetsController(getWindow(), root).setAppearanceLightStatusBars(true);
        showTab(TAB_LAYOUT);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private Sc cur() { return scenes.get(Math.max(0, Math.min(curScene, scenes.size() - 1))); }

    private static String nz(@Nullable String s) { return s == null ? "" : s; }

    private static double r3(float v) { return Math.round(v * 1000f) / 1000.0; }

    private GradientDrawable round(int color, int radiusDp) {
        final GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private GradientDrawable oval(int color) {
        final GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    private ImageView icon(@DrawableRes int res, int tint, int sizeDp) {
        final ImageView v = new ImageView(this);
        v.setImageResource(res);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        ImageViewCompat.setImageTintList(v, ColorStateList.valueOf(tint));
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return v;
    }

    private TextView text(CharSequence s, float sp, int color, boolean bold) {
        final TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setIncludeFontPadding(false);
        return t;
    }

    private void clipRound(View v, final int radiusDp) {
        v.setClipToOutline(true);
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline o) { o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(radiusDp)); }
        });
    }

    private TextView sectionLabel(int res) {
        final TextView t = text(getString(res), 16f, textColor, true);
        t.setPadding(dp(4), dp(18), 0, dp(10));
        return t;
    }

    private LinearLayout card() {
        final LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(round(surface, 18));
        return c;
    }

    /** Rounded chip; selected = purple fill. */
    private TextView chip(String label, boolean on, View.OnClickListener l) {
        final TextView c = text(label, 14f, on ? 0xFFFFFFFF : textColor, on);
        c.setPadding(dp(16), dp(10), dp(16), dp(10));
        c.setBackground(round(on ? PURPLE : surface, 20));
        c.setContentDescription(label);
        c.setOnClickListener(l);
        return c;
    }

    // ── chrome ───────────────────────────────────────────────────────────────

    private void buildTopBar(LinearLayout root) {
        final FrameLayout bar = new FrameLayout(this);
        final FrameLayout back = new FrameLayout(this);
        back.setBackground(round(surface, 21));
        back.addView(icon(R.drawable.ic_arrow_back, textColor, 22), new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        back.setContentDescription(getString(R.string.ct_back));
        back.setOnClickListener(v -> confirmLeave());
        final FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(dp(42), dp(42), Gravity.START | Gravity.CENTER_VERTICAL);
        blp.setMargins(dp(14), 0, 0, 0);
        bar.addView(back, blp);
        bar.addView(text(getString(R.string.ct_title), 19f, textColor, true), new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        final TextView help = text("?", 16f, PURPLE, true);
        help.setGravity(Gravity.CENTER);
        final GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(0x00000000);
        ring.setStroke(dp(2), PURPLE);
        help.setBackground(ring);
        help.setContentDescription(getString(R.string.ct_help));
        help.setOnClickListener(v -> new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.ct_help_title).setMessage(R.string.ct_help_msg).setPositiveButton(R.string.ct_ok, null).show());
        final FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(dp(28), dp(28), Gravity.END | Gravity.CENTER_VERTICAL);
        hlp.setMargins(0, 0, dp(18), 0);
        bar.addView(help, hlp);
        root.addView(bar, new LinearLayout.LayoutParams(-1, dp(58)));
    }

    private View buildTabs() {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(16), dp(4), dp(16), dp(10));
        final int[] names = {R.string.ct_tab_layout, R.string.ct_tab_music, R.string.ct_tab_effects};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            final TextView t = text(getString(names[i]), 15f, textColor, true);
            t.setGravity(Gravity.CENTER);
            t.setOnClickListener(v -> showTab(idx));
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1f);
            lp.setMargins(i == 0 ? 0 : dp(5), 0, i == names.length - 1 ? 0 : dp(5), 0);
            row.addView(t, lp);
            tabPills.add(t);
        }
        return row;
    }

    private View buildNextBar() {
        final FrameLayout bar = new FrameLayout(this);
        bar.setPadding(dp(16), dp(8), dp(16), dp(14));
        final TextView next = text(getString(R.string.ct_next), 17f, 0xFFFFFFFF, true);
        next.setGravity(Gravity.CENTER);
        final GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{PURPLE_LIGHT, PURPLE});
        g.setCornerRadius(dp(26));
        next.setBackground(g);
        next.setOnClickListener(v -> goNext());
        bar.addView(next, new FrameLayout.LayoutParams(-1, dp(52)));
        return bar;
    }

    private void showTab(int t) {
        tab = t;
        final int keep = scroll != null ? scroll.getScrollY() : 0;
        for (int i = 0; i < tabPills.size(); i++) {
            final boolean on = i == tab;
            final TextView p = tabPills.get(i);
            p.setTextColor(on ? 0xFFFFFFFF : PURPLE);
            p.setBackground(round(on ? PURPLE : ColorUtils.setAlphaComponent(PURPLE, 0x1A), 20));
        }
        content.removeAllViews();
        if (tab == TAB_LAYOUT) buildLayoutTab();
        else if (tab == TAB_MUSIC) buildMusicTab();
        else buildEffectsTab();
        scroll.post(() -> scroll.scrollTo(0, keep));
    }

    // ── Layout tab ───────────────────────────────────────────────────────────

    private void buildLayoutTab() {
        content.addView(sectionLabel(R.string.ct_layouts));
        LinearLayout row = null;
        for (int i = 0; i < LAYOUTS.length; i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                content.addView(row, new LinearLayout.LayoutParams(-1, -2));
            }
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            lp.setMargins(dp(5), dp(4), dp(5), dp(10));
            row.addView(layoutTile(i), lp);
        }

        buildMultiSceneSection();

        content.addView(sectionLabel(R.string.ct_frames));
        final HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        final LinearLayout shapes = new LinearLayout(this);
        shapes.setOrientation(LinearLayout.HORIZONTAL);
        for (int s = 0; s < TemplateBoxStyle.LABELS.length; s++) {
            final int shape = s;
            final LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            final boolean on = frame.shape == shape;
            final ShapeTile tile = new ShapeTile(shape, on);
            col.addView(tile, new LinearLayout.LayoutParams(dp(66), dp(66)));
            final TextView name = text(TemplateBoxStyle.LABELS[s], 12f, on ? PURPLE : subColor, on);
            name.setPadding(0, dp(6), 0, 0);
            col.addView(name);
            col.setOnClickListener(v -> { frame.shape = shape; showTab(TAB_LAYOUT); });
            final LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
            clp.setMarginEnd(dp(12));
            shapes.addView(col, clp);
        }
        hs.addView(shapes);
        content.addView(hs, new LinearLayout.LayoutParams(-1, -2));

        final LinearLayout toggles = new LinearLayout(this);
        toggles.setOrientation(LinearLayout.HORIZONTAL);
        toggles.setPadding(0, dp(12), 0, 0);
        final LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.setMarginEnd(dp(8));
        toggles.addView(chip(getString(R.string.ct_frame_border), frame.border, v -> { frame.border = !frame.border; showTab(TAB_LAYOUT); }), tlp);
        toggles.addView(chip(getString(R.string.ct_frame_shadow), frame.shadow, v -> { frame.shadow = !frame.shadow; showTab(TAB_LAYOUT); }), new LinearLayout.LayoutParams(-2, -2));
        content.addView(toggles);

        content.addView(sectionLabel(R.string.ct_category));
        final HorizontalScrollView ch = new HorizontalScrollView(this);
        ch.setHorizontalScrollBarEnabled(false);
        final LinearLayout cr = new LinearLayout(this);
        cr.setOrientation(LinearLayout.HORIZONTAL);
        for (int c = -1; c < CAT_NAMES.length; c++) {
            final int cat = c;
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMarginEnd(dp(8));
            cr.addView(chip(getString(c < 0 ? R.string.tpl_opt_category_none : CAT_NAMES[c]), category == c,
                    v -> { category = cat; showTab(TAB_LAYOUT); }), lp);
        }
        ch.addView(cr);
        content.addView(ch, new LinearLayout.LayoutParams(-1, -2));
    }

    private View layoutTile(final int idx) {
        final LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        final boolean on = idx == cur().layout;

        final RatioFrame card = new RatioFrame(this);
        final GradientDrawable cbg = round(surface, 16);
        if (on) cbg.setStroke(dp(2), PURPLE);
        card.setBackground(cbg);
        card.setPadding(dp(4), dp(4), dp(4), dp(4));
        final ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.FIT_XY);
        img.setImageDrawable(TemplatePreviewDrawable.fromJson(buildJson(idx)));
        clipRound(img, 12);
        card.addView(img, new FrameLayout.LayoutParams(-1, -1));
        if (on) {
            final TextView tick = text("\u2713", 13f, 0xFFFFFFFF, true);
            tick.setGravity(Gravity.CENTER);
            tick.setBackground(oval(PURPLE));
            final FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.BOTTOM | Gravity.END);
            tp.setMargins(0, 0, dp(6), dp(6));
            card.addView(tick, tp);
        }
        card.setContentDescription(getString(LAYOUT_NAMES[idx]));
        card.setOnClickListener(v -> { cur().layout = idx; showTab(TAB_LAYOUT); });
        col.addView(card, new LinearLayout.LayoutParams(-1, -2));

        final TextView name = text(getString(LAYOUT_NAMES[idx]), 13f, on ? PURPLE : textColor, true);
        name.setPadding(0, dp(6), 0, 0);
        col.addView(name);
        final TextView sub = text(getString(R.string.ct_lay_photos, LAYOUTS[idx].length + 1), 11f, subColor, false);
        sub.setPadding(0, dp(2), 0, 0);
        col.addView(sub);
        return col;
    }

    /** Card that keeps the template canvas ratio (9:16) whatever the column width. */
    private static final class RatioFrame extends FrameLayout {
        RatioFrame(android.content.Context c) { super(c); }
        @Override protected void onMeasure(int wSpec, int hSpec) {
            final int w = MeasureSpec.getSize(wSpec);
            super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(Math.round(w / TemplatePreviewDrawable.ASPECT), MeasureSpec.EXACTLY));
        }
    }

    /** One box shape drawn with the same {@link TemplateBoxStyle#path} the composer uses. */
    private final class ShapeTile extends View {
        private final int shape;
        private final boolean on;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        ShapeTile(int shape, boolean on) {
            super(CreateTemplateActivity.this);
            this.shape = shape;
            this.on = on;
            fill.setStyle(Paint.Style.FILL);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(dp(2));
            setBackground(round(on ? ColorUtils.setAlphaComponent(PURPLE, 0x1A) : surface, 16));
            setContentDescription(TemplateBoxStyle.LABELS[shape]);
        }

        @Override protected void onDraw(@NonNull Canvas c) {
            final float m = Math.min(getWidth(), getHeight()) * 0.26f;
            r.set(m, m, getWidth() - m, getHeight() - m);
            final Path p = TemplateBoxStyle.path(shape, r);
            fill.setColor(on ? PURPLE : (dark ? 0x55FFFFFF : 0x33000000));
            c.drawPath(p, fill);
            if (on) { line.setColor(PURPLE); } else { line.setColor(0x00000000); }
            if (on) c.drawPath(p, line);
        }
    }

    /** Boxes of layout {@code idx} (first = the full-canvas base) with the current frame / effects applied. */
    private JSONArray buildSlots(int idx) throws Exception {
        final JSONArray slots = new JSONArray();
        slots.put(new JSONObject().put("base", true));
        final boolean square = frame.shape == TemplateBoxStyle.CIRCLE || frame.shape == TemplateBoxStyle.HEART;
        final TemplateSpec.Slot fx = new TemplateSpec.Slot();
        fx.filter = filter;
        fx.anim = anim;
        for (float[] b : LAYOUTS[idx]) {
            float w = b[2], h = b[3];
            if (square) {   // circle / heart need a square box in pixels: w * W == h * H, H = W / ASPECT
                final float side = Math.min(w, h / TemplatePreviewDrawable.ASPECT);
                w = side;
                h = side * TemplatePreviewDrawable.ASPECT;
            }
            final JSONObject o = new JSONObject();
            o.put("cx", r3(b[0])).put("cy", r3(b[1])).put("w", r3(w)).put("h", r3(h)).put("rot", r3(b[4]));
            frame.putJson(o);
            fx.putJson(o);
            slots.put(o);
        }
        return slots;
    }

    /** One-layout templateJson (what the tiles draw) with the current frame / effects / music-sync / category. */
    private String buildJson(int idx) {
        try {
            final JSONObject tpl = new JSONObject().put("v", 1).put("slots", buildSlots(idx));
            if (category >= 0 && category < TemplateCategory.KEYS.length) tpl.put("cat", TemplateCategory.KEYS[category]);
            final TemplateSpec meta = new TemplateSpec();
            meta.musicSync = musicSync && !mTitle.isEmpty();
            meta.putMeta(tpl);
            return tpl.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    /** The templateJson that is really saved: {@link #buildJson} of scene 0 + (multi-scene only) {@code scenes[]} and {@code tdur}. */
    private String buildFinalJson() {
        try {
            final JSONObject tpl = new JSONObject(buildJson(scenes.get(0).layout));
            if (multi && scenes.size() >= 2) {
                final JSONArray sc = new JSONArray();
                int sum = 0;
                for (int i = 0; i < scenes.size(); i++) {
                    final JSONObject o = new JSONObject().put("d", TemplateSpec.clampSceneMs(scenes.get(i).ms));
                    if (i > 0) o.put("slots", buildSlots(scenes.get(i).layout));   // scene 0 = the root "slots"
                    sc.put(o);
                    sum += TemplateSpec.clampSceneMs(scenes.get(i).ms);
                }
                tpl.put("scenes", sc);
                tpl.put("tdur", Math.min(TemplateSpec.MAX_TOTAL_MS, sum));
            }
            return tpl.toString();
        } catch (Exception e) {
            return buildJson(scenes.get(0).layout);
        }
    }

    // ── Multi-Scene (Layout tab) ─────────────────────────────────────────────

    private String secs(int ms) { return String.format(java.util.Locale.US, "%.1fs", ms / 1000f); }

    private void buildMultiSceneSection() {
        // header row: title + (i) + switch
        final LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(4), dp(18), 0, dp(8));
        head.addView(text(getString(R.string.ct_ms_title), 16f, textColor, true));
        final TextView info = text("i", 12f, subColor, true);
        info.setGravity(Gravity.CENTER);
        final GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(0x00000000);
        ring.setStroke(dp(1), subColor);
        info.setBackground(ring);
        info.setContentDescription(getString(R.string.ct_help));
        info.setOnClickListener(v -> new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.ct_ms_title).setMessage(R.string.ct_ms_info).setPositiveButton(R.string.ct_ok, null).show());
        final LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(18), dp(18));
        ilp.setMarginStart(dp(8));
        head.addView(info, ilp);
        head.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        final SwitchCompat sw = new SwitchCompat(this);
        sw.setThumbTintList(ColorStateList.valueOf(0xFFFFFFFF));
        sw.setTrackTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{PURPLE, 0xFFC9C6D8}));
        sw.setChecked(multi);
        sw.setOnCheckedChangeListener((cb, on) -> {
            multi = on;
            if (on && scenes.size() < 2) {   // switching on gives a real 2nd scene to work with
                final Sc second = new Sc();
                second.layout = (scenes.get(0).layout + 1) % LAYOUTS.length;
                scenes.add(second);
            }
            curScene = 0;
            showTab(TAB_LAYOUT);
        });
        head.addView(sw);
        content.addView(head, new LinearLayout.LayoutParams(-1, -2));
        if (!multi) {
            final TextView h = text(getString(R.string.ct_ms_off_hint), 12f, subColor, false);
            h.setPadding(dp(4), 0, dp(4), 0);
            content.addView(h);
            return;
        }

        // scene strip: mini preview + length, "+" to add
        final HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < scenes.size(); i++) {
            final int idx = i;
            final boolean on = i == curScene;
            final LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            final RatioFrame tile = new RatioFrame(this);
            final GradientDrawable tbg = round(surface, 14);
            if (on) tbg.setStroke(dp(2), PURPLE);
            tile.setBackground(tbg);
            tile.setPadding(dp(3), dp(3), dp(3), dp(3));
            final ImageView img = new ImageView(this);
            img.setScaleType(ImageView.ScaleType.FIT_XY);
            img.setImageDrawable(TemplatePreviewDrawable.fromJson(buildJson(scenes.get(i).layout)));
            clipRound(img, 10);
            tile.addView(img, new FrameLayout.LayoutParams(-1, -1));
            final TextView num = text(String.valueOf(i + 1), 11f, 0xFFFFFFFF, true);
            num.setGravity(Gravity.CENTER);
            num.setBackground(oval(on ? PURPLE : 0x99000000));
            final FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.TOP | Gravity.START);
            nlp.setMargins(dp(5), dp(5), 0, 0);
            tile.addView(num, nlp);
            tile.setContentDescription(getString(R.string.ct_scene_n, i + 1));
            col.addView(tile, new LinearLayout.LayoutParams(dp(62), -2));
            final TextView len = text(secs(scenes.get(i).ms), 12f, on ? PURPLE : subColor, true);
            len.setPadding(0, dp(5), 0, 0);
            col.addView(len);
            col.setOnClickListener(v -> { curScene = idx; showTab(TAB_LAYOUT); });
            final LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
            clp.setMarginEnd(dp(10));
            row.addView(col, clp);
        }
        if (scenes.size() < TemplateSpec.MAX_SCENES) {
            final LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            final RatioFrame add = new RatioFrame(this);
            add.setBackground(round(ColorUtils.setAlphaComponent(PURPLE, 0x1A), 14));
            final TextView plus = text("+", 26f, PURPLE, true);
            plus.setGravity(Gravity.CENTER);
            add.addView(plus, new FrameLayout.LayoutParams(-1, -1));
            add.setContentDescription(getString(R.string.ct_scene_add));
            col.addView(add, new LinearLayout.LayoutParams(dp(62), -2));
            col.setOnClickListener(v -> {
                final Sc n = new Sc();
                n.layout = cur().layout;
                n.ms = cur().ms;
                scenes.add(n);
                curScene = scenes.size() - 1;
                showTab(TAB_LAYOUT);
            });
            row.addView(col, new LinearLayout.LayoutParams(-2, -2));
        }
        hs.addView(row);
        content.addView(hs, new LinearLayout.LayoutParams(-1, -2));

        // length of the selected scene + remove + total
        final TextView lenLabel = text(getString(R.string.ct_scene_len, curScene + 1), 14f, textColor, true);
        lenLabel.setPadding(dp(4), dp(14), 0, dp(8));
        content.addView(lenLabel);
        final HorizontalScrollView lh = new HorizontalScrollView(this);
        lh.setHorizontalScrollBarEnabled(false);
        final LinearLayout lr = new LinearLayout(this);
        lr.setOrientation(LinearLayout.HORIZONTAL);
        for (final int ms : SCENE_MS) {
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMarginEnd(dp(8));
            lr.addView(chip(secs(ms), cur().ms == ms, v -> { cur().ms = ms; showTab(TAB_LAYOUT); }), lp);
        }
        lh.addView(lr);
        content.addView(lh, new LinearLayout.LayoutParams(-1, -2));

        final LinearLayout foot = new LinearLayout(this);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        foot.setPadding(dp(4), dp(12), dp(4), 0);
        int total = 0;
        for (Sc sc : scenes) total += sc.ms;
        foot.addView(text(getString(R.string.ct_scene_total, secs(Math.min(TemplateSpec.MAX_TOTAL_MS, total))), 13f, subColor, false),
                new LinearLayout.LayoutParams(0, -2, 1f));
        if (scenes.size() > 2) {   // keep at least 2 scenes while Multi-Scene is on (switch it off for one)
            final TextView rm = text(getString(R.string.ct_scene_remove), 13f, 0xFFE5484D, true);
            rm.setPadding(dp(12), dp(6), dp(4), dp(6));
            rm.setOnClickListener(v -> {
                scenes.remove(curScene);
                curScene = Math.max(0, Math.min(curScene, scenes.size() - 1));
                showTab(TAB_LAYOUT);
            });
            foot.addView(rm);
        }
        content.addView(foot, new LinearLayout.LayoutParams(-1, -2));
        final TextView hint = text(getString(R.string.ct_ms_hint), 12f, subColor, false);
        hint.setPadding(dp(4), dp(8), dp(4), 0);
        content.addView(hint);
    }

    // ── Music tab ────────────────────────────────────────────────────────────

    private void buildMusicTab() {
        final boolean has = !mTitle.isEmpty();
        content.addView(sectionLabel(R.string.ct_tab_music));
        final LinearLayout card = card();
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(14), dp(14), dp(14));

        final FrameLayout art = new FrameLayout(this);
        if (has) {
            final ImageView cover = new ImageView(this);
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cover.setBackgroundResource(R.drawable.bg_music_cover_placeholder);
            clipRound(cover, 12);
            art.addView(cover, new FrameLayout.LayoutParams(-1, -1));
            if (!mCover.isEmpty()) Glide.with(this).load(mCover).into(cover);
            else cover.setImageResource(R.drawable.ic_music_note);
        } else {
            art.setBackground(oval(ColorUtils.setAlphaComponent(PURPLE, 0x22)));
            art.addView(icon(R.drawable.ic_music_note, PURPLE, 26), new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER));
        }
        row.addView(art, new LinearLayout.LayoutParams(dp(56), dp(56)));

        final LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(dp(12), 0, dp(8), 0);
        final TextView title = text(has ? mTitle : getString(R.string.ct_music_none_title), 16f, textColor, true);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mid.addView(title);
        final String subStr = has ? (mArtist.isEmpty() ? " " : mArtist) : getString(R.string.ct_music_none_sub);
        final TextView sub = text(subStr, 13f, subColor, false);
        sub.setSingleLine(true);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        sub.setPadding(0, dp(4), 0, 0);
        mid.addView(sub);
        row.addView(mid, new LinearLayout.LayoutParams(0, -2, 1f));

        final TextView action = text(getString(has ? R.string.ct_music_change : R.string.ct_tab_music), 13f, 0xFFFFFFFF, true);
        action.setPadding(dp(14), dp(8), dp(14), dp(8));
        action.setBackground(round(PURPLE, 16));
        row.addView(action);
        row.setOnClickListener(v -> openMusicPicker());
        card.addView(row);

        if (has) {
            final View div = new View(this);
            div.setBackgroundColor(dark ? 0x22FFFFFF : 0x14000000);
            card.addView(div, new LinearLayout.LayoutParams(-1, 1));
            final TextView rm = text(getString(R.string.ct_music_remove), 14f, 0xFFE5484D, true);
            rm.setGravity(Gravity.CENTER);
            rm.setPadding(0, dp(12), 0, dp(12));
            rm.setOnClickListener(v -> {
                mId = mTitle = mArtist = mCover = mUrl = "";
                musicSync = false;
                showTab(TAB_MUSIC);
            });
            card.addView(rm, new LinearLayout.LayoutParams(-1, -2));
        }
        content.addView(card);

        // Music Sync
        final LinearLayout card2 = card();
        card2.setAlpha(has ? 1f : 0.45f);
        final LinearLayout sr = new LinearLayout(this);
        sr.setOrientation(LinearLayout.HORIZONTAL);
        sr.setGravity(Gravity.CENTER_VERTICAL);
        sr.setPadding(dp(14), dp(12), dp(14), dp(12));
        final FrameLayout badge = new FrameLayout(this);
        badge.setBackground(oval(ColorUtils.setAlphaComponent(PURPLE, 0x22)));
        badge.addView(icon(R.drawable.ic_music_note, PURPLE, 18), new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER));
        sr.addView(badge, new LinearLayout.LayoutParams(dp(34), dp(34)));
        final TextView sl = text(getString(R.string.te_music_sync), 15f, textColor, true);
        sl.setPadding(dp(12), 0, 0, 0);
        sr.addView(sl, new LinearLayout.LayoutParams(0, -2, 1f));
        final SwitchCompat sw = new SwitchCompat(this);
        sw.setThumbTintList(ColorStateList.valueOf(0xFFFFFFFF));
        sw.setTrackTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{PURPLE, 0xFFC9C6D8}));
        sw.setChecked(musicSync && has);
        sw.setEnabled(has);
        sw.setOnCheckedChangeListener((cb, on) -> musicSync = on);
        sr.addView(sw);
        sr.setOnClickListener(v -> { if (has) sw.toggle(); });
        card2.addView(sr);
        final TextView ss = text(getString(has ? R.string.te_music_sync_sub : R.string.ct_music_sync_off_hint), 12f, subColor, false);
        ss.setPadding(dp(60), 0, dp(14), dp(12));
        card2.addView(ss);
        final LinearLayout.LayoutParams c2 = new LinearLayout.LayoutParams(-1, -2);
        c2.setMargins(0, dp(12), 0, 0);
        content.addView(card2, c2);

        final TextView hint = text(getString(R.string.ct_music_hint), 12f, subColor, false);
        hint.setPadding(dp(6), dp(12), dp(6), 0);
        content.addView(hint);
    }

    private void openMusicPicker() {
        try {
            final Class<?> cls = Class.forName("com.callx.app.music.ReelTrendingAudioActivity");   // same picker the Music sticker uses
            musicLauncher.launch(new Intent(this, cls));
        } catch (Exception e) {
            Toast.makeText(this, R.string.ct_music_unavailable, Toast.LENGTH_SHORT).show();
        }
    }

    // ── Effects tab ──────────────────────────────────────────────────────────

    private void buildEffectsTab() {
        content.addView(sectionLabel(R.string.ct_effects_filter));
        final HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (final String key : TemplateSpec.FILTERS) {
            final boolean on = key.equals(filter);
            final LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            final View sw = new View(this);
            final GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{0xFFFFB36B, 0xFFE8478B, 0xFF4A6CF7});
            g.setCornerRadius(dp(16));
            if (on) g.setStroke(dp(3), PURPLE);
            final ColorFilter cf = TemplateEffects.colorFilter(key);   // exactly the matrix the composer / GL shader use
            g.setColorFilter(cf);
            sw.setBackground(g);
            col.addView(sw, new LinearLayout.LayoutParams(dp(70), dp(70)));
            final TextView name = text(filterName(key), 12f, on ? PURPLE : subColor, on);
            name.setPadding(0, dp(6), 0, 0);
            col.addView(name);
            col.setContentDescription(filterName(key));
            col.setOnClickListener(v -> { filter = key; showTab(TAB_EFFECTS); });
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMarginEnd(dp(12));
            row.addView(col, lp);
        }
        hs.addView(row);
        content.addView(hs, new LinearLayout.LayoutParams(-1, -2));

        content.addView(sectionLabel(R.string.ct_effects_anim));
        final HorizontalScrollView ah = new HorizontalScrollView(this);
        ah.setHorizontalScrollBarEnabled(false);
        final LinearLayout ar = new LinearLayout(this);
        ar.setOrientation(LinearLayout.HORIZONTAL);
        for (final String key : TemplateSpec.ANIMS) {
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMarginEnd(dp(8));
            ar.addView(chip(animName(key), key.equals(anim), v -> { anim = key; showTab(TAB_EFFECTS); }), lp);
        }
        ah.addView(ar);
        content.addView(ah, new LinearLayout.LayoutParams(-1, -2));

        final TextView hint = text(getString(R.string.ct_effects_hint), 12f, subColor, false);
        hint.setPadding(dp(6), dp(16), dp(6), 0);
        content.addView(hint);
    }

    private String filterName(String key) {
        switch (key) {
            case "warm": return getString(R.string.te_flt_warm);
            case "cool": return getString(R.string.te_flt_cool);
            case "bw": return getString(R.string.te_flt_bw);
            case "vivid": return getString(R.string.te_flt_vivid);
            case "fade": return getString(R.string.te_flt_fade);
            default: return getString(R.string.te_flt_natural);
        }
    }

    private String animName(String key) {
        switch (key) {
            case "zoom_in": return getString(R.string.te_an_zoom_in);
            case "zoom_out": return getString(R.string.te_an_zoom_out);
            case "pan_l": return getString(R.string.te_an_pan_l);
            case "pan_r": return getString(R.string.te_an_pan_r);
            case "fade": return getString(R.string.te_an_fade);
            default: return getString(R.string.te_an_none);
        }
    }

    // ── Drafts (v774) ────────────────────────────────────────────────────────

    /** Every choice of this screen as json (for drafts + the "changed?" check). */
    private String stateJson() {
        try {
            final JSONObject o = new JSONObject();
            final JSONArray sc = new JSONArray();
            for (Sc c : scenes) sc.put(new JSONObject().put("l", c.layout).put("ms", c.ms));
            o.put("scenes", sc).put("cur", curScene).put("multi", multi)
                    .put("shape", frame.shape).put("border", frame.border).put("shadow", frame.shadow)
                    .put("cat", category).put("flt", filter).put("an", anim).put("msync", musicSync)
                    .put("mId", mId).put("mTitle", mTitle).put("mArtist", mArtist).put("mCover", mCover).put("mUrl", mUrl);
            return o.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private void restoreDraft() {
        final String uid = com.callx.app.utils.FirebaseUtils.getCurrentUid();
        final TemplateDrafts.Draft d = uid == null ? null : TemplateDrafts.get(this, uid, draftId);
        if (d == null) { draftId = null; return; }
        try {
            final JSONObject o = new JSONObject(d.state);
            final JSONArray sc = o.optJSONArray("scenes");
            scenes.clear();
            if (sc != null) for (int i = 0; i < sc.length() && i < TemplateSpec.MAX_SCENES; i++) {
                final JSONObject so = sc.optJSONObject(i);
                if (so == null) continue;
                final Sc c = new Sc();
                c.layout = Math.max(0, Math.min(LAYOUTS.length - 1, so.optInt("l", 0)));
                c.ms = TemplateSpec.clampSceneMs(so.optInt("ms", TemplateSpec.DEF_SCENE_MS));
                scenes.add(c);
            }
            if (scenes.isEmpty()) scenes.add(new Sc());
            multi = o.optBoolean("multi", false) && scenes.size() >= 2;
            curScene = Math.max(0, Math.min(scenes.size() - 1, o.optInt("cur", 0)));
            frame.shape = Math.max(0, Math.min(TemplateBoxStyle.LABELS.length - 1, o.optInt("shape", frame.shape)));
            frame.border = o.optBoolean("border", false);
            frame.shadow = o.optBoolean("shadow", false);
            category = Math.max(-1, Math.min(CAT_NAMES.length - 1, o.optInt("cat", -1)));
            filter = knownKey(TemplateSpec.FILTERS, o.optString("flt", filter), TemplateSpec.DEF_FILTER);
            anim = knownKey(TemplateSpec.ANIMS, o.optString("an", anim), TemplateSpec.DEF_ANIM);
            mId = o.optString("mId", ""); mTitle = o.optString("mTitle", ""); mArtist = o.optString("mArtist", "");
            mCover = o.optString("mCover", ""); mUrl = o.optString("mUrl", "");
            musicSync = o.optBoolean("msync", false) && !mTitle.isEmpty();
        } catch (Exception ignored) { }
    }

    private static String knownKey(String[] keys, String v, String def) {
        for (String k : keys) if (k.equals(v)) return v;
        return def;
    }

    private boolean saveDraft() {
        final String uid = com.callx.app.utils.FirebaseUtils.getCurrentUid();
        final String st = stateJson();
        if (uid == null || uid.isEmpty() || st.isEmpty()) return false;
        final String id = TemplateDrafts.save(this, uid, draftId, buildFinalJson(), st);
        if (id == null) return false;
        draftId = id;
        cleanState = st;
        Toast.makeText(this, R.string.ct_draft_saved, Toast.LENGTH_SHORT).show();
        return true;
    }

    /** Back: nothing changed -> leave; else Save draft / Discard / Keep editing. */
    private void confirmLeave() {
        if (stateJson().equals(cleanState)) { finish(); return; }
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.ct_draft_title).setMessage(R.string.ct_draft_msg)
                .setPositiveButton(R.string.ct_draft_save, (d, w) -> { if (saveDraft()) finish(); else Toast.makeText(this, R.string.ct_draft_fail, Toast.LENGTH_SHORT).show(); })
                .setNegativeButton(R.string.ct_draft_discard, (d, w) -> finish())
                .setNeutralButton(R.string.ct_draft_keep, null)
                .show();
    }

    // ── Next → composer in create mode ───────────────────────────────────────

    private void goNext() {
        final String json = buildFinalJson();
        final Intent i = new Intent(this, NewStatusActivity.class);
        i.putExtra(NewStatusActivity.EXTRA_TEMPLATE_JSON, json);
        i.putExtra(NewStatusActivity.EXTRA_CREATE_TEMPLATE, true);
        if (!mTitle.isEmpty()) {
            try {   // same sticker json as StatusStickerPickerSheet's Music option; the composer restores it like a poster's music
                final JSONObject m = new JSONObject();
                m.put("type", "music");
                m.put("song", mTitle);
                m.put("artist", mArtist);
                m.put("albumArt", mCover);
                m.put("soundId", mId);
                m.put("soundUrl", mUrl);
                i.putExtra(NewStatusActivity.EXTRA_TEMPLATE_STICKERS_JSON, new JSONArray().put(m).toString());
            } catch (Exception ignored) { }
        }
        startActivity(i);
        finish();
    }
}
