package com.callx.app.compose;

import android.animation.ValueAnimator;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.WindowCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.core.widget.NestedScrollView;

import com.callx.app.status.R;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.StatusTemplateUses;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * v772 — Screen 4 "Template Editor": per-box Filter, Animation and Duration + the whole template's length and Music Sync.
 *
 *  - Owner only. Opened from My Templates > Created > ⋮ > "Template edit karo" with the template's json
 *    ({@link #EXTRA_TPL_JSON}) and its status id ({@link #EXTRA_STATUS_ID}).
 *  - Preview card (boxes of the selected slot are highlighted), slot tabs, option rows, Save.
 *  - Writes ONLY the optional {@link TemplateSpec} keys ({@code d}, {@code flt}, {@code an}, {@code tdur}, {@code msync});
 *    box geometry / shape / music / text are never touched. Saves to {@code status/{me}/{id}/templateJson} (if the status is still
 *    live) and to the owner's saved snapshot ({@code savedTemplates/{me}/{me}_{id}}, if there is one) — so the template keeps
 *    working after the 24h status is gone.
 *  - The filter / animation are applied by the composer and {@link TemplateVideoCompositor} when somebody uses the template.
 *  - Premium iOS look (white surface, purple accent, rounded cards); every icon is an existing project drawable.
 */
public class TemplateEditorActivity extends AppCompatActivity {

    public static final String EXTRA_TPL_JSON = "tedJson";
    public static final String EXTRA_STATUS_ID = "tedStatusId";
    /** Result extra: the saved templateJson (String). */
    public static final String RESULT_JSON = "tedJsonNow";

    private static final int PURPLE = 0xFF6A3DE8, PURPLE_LIGHT = 0xFF9A7BFF;

    private String json, statusId, me;
    private TemplateSpec spec;
    private final List<Integer> boxIdx = new ArrayList<>();   // indexes (into spec.slots) of the boxes, base photo excluded
    private int sel;
    private boolean dirty, saving;

    private boolean dark;
    private int bg, surface, textColor, subColor;

    private TemplatePreviewDrawable preview;
    private TextView saveBtn, durChip, fltVal, animVal, durVal, totVal, syncSub;
    private SeekBar durBar, totBar;
    private SwitchCompat syncSwitch;
    private final List<TextView> tabViews = new ArrayList<>();
    private final List<View> tabBars = new ArrayList<>();
    private boolean binding;   // true while the controls are being set from the model (no dirty / no feedback loops)

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        json = getIntent().getStringExtra(EXTRA_TPL_JSON);
        statusId = getIntent().getStringExtra(EXTRA_STATUS_ID);
        me = FirebaseUtils.getCurrentUid();
        preview = TemplatePreviewDrawable.fromJson(json);
        spec = TemplateSpec.parse(json);
        if (preview == null || !ne(statusId) || !ne(me)) {
            Toast.makeText(this, R.string.te_unavailable, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        for (int i = 0; i < spec.slots.size(); i++) if (!spec.slots.get(i).base) boxIdx.add(i);

        final TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.colorBackground, tv, true);
        bg = tv.data;
        dark = ColorUtils.calculateLuminance(bg) < 0.5;
        surface = dark ? 0x22FFFFFF : 0xFFF2F0F8;
        textColor = dark ? 0xFFFFFFFF : 0xFF14142B;
        subColor = dark ? 0xAAFFFFFF : 0xFF8A8AA3;

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        root.setBackgroundColor(bg);
        buildTopBar(root);
        root.addView(buildStage(), new LinearLayout.LayoutParams(-1, 0, 1f));
        if (!boxIdx.isEmpty()) root.addView(buildTabs(), new LinearLayout.LayoutParams(-1, -2));
        root.addView(buildOptions(), new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
        if (!dark) WindowCompat.getInsetsController(getWindow(), root).setAppearanceLightStatusBars(true);

        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { tryClose(); }
        });
        selectSlot(0);
        refreshSave();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private static boolean ne(@Nullable String s) { return s != null && !s.isEmpty(); }

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

    private TemplateSpec.Slot cur() { return boxIdx.isEmpty() ? null : spec.slots.get(boxIdx.get(sel)); }

    private static String secs(int ms) { return String.format(Locale.US, "%.1fs", ms / 1000f); }

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

    private void markDirty() {
        if (binding) return;
        dirty = true;
        refreshSave();
    }

    private void refreshSave() {
        if (saveBtn == null) return;
        saveBtn.setAlpha(dirty && !saving ? 1f : 0.4f);
        saveBtn.setEnabled(dirty && !saving);
    }

    // ── chrome ───────────────────────────────────────────────────────────────

    private void buildTopBar(LinearLayout root) {
        final FrameLayout bar = new FrameLayout(this);
        final FrameLayout back = new FrameLayout(this);
        back.setBackground(round(surface, 21));
        back.addView(icon(R.drawable.ic_arrow_back, textColor, 22), new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        back.setContentDescription(getString(R.string.te_back));
        back.setOnClickListener(v -> tryClose());
        final FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(dp(42), dp(42), Gravity.START | Gravity.CENTER_VERTICAL);
        blp.setMargins(dp(14), 0, 0, 0);
        bar.addView(back, blp);
        bar.addView(text(getString(R.string.te_title), 19f, textColor, true), new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        saveBtn = text(getString(R.string.te_save), 14f, 0xFFFFFFFF, true);
        saveBtn.setGravity(Gravity.CENTER);
        saveBtn.setPadding(dp(18), 0, dp(18), 0);
        final GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{PURPLE_LIGHT, PURPLE});
        g.setCornerRadius(dp(18));
        saveBtn.setBackground(g);
        saveBtn.setOnClickListener(v -> save());
        final FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(-2, dp(36), Gravity.END | Gravity.CENTER_VERTICAL);
        slp.setMargins(0, 0, dp(14), 0);
        bar.addView(saveBtn, slp);
        root.addView(bar, new LinearLayout.LayoutParams(-1, dp(58)));
    }

    private View buildStage() {
        final FrameLayout stage = new FrameLayout(this);
        final FrameLayout holder = new FrameLayout(this) {
            @Override protected void onMeasure(int wSpec, int hSpec) {
                final int aw = MeasureSpec.getSize(wSpec), ah = MeasureSpec.getSize(hSpec);
                int h = ah, w = Math.round(h * TemplatePreviewDrawable.ASPECT);
                if (w > aw) { w = aw; h = Math.round(w / TemplatePreviewDrawable.ASPECT); }
                super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
            }
        };
        final FrameLayout card = new FrameLayout(this);
        card.setBackground(round(0xFFFFFFFF, 24));
        card.setPadding(dp(3), dp(3), dp(3), dp(3));
        card.setElevation(dp(8));
        final ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.FIT_XY);
        img.setImageDrawable(preview);
        clipRound(img, 21);
        card.addView(img, new FrameLayout.LayoutParams(-1, -1));
        holder.addView(card, new FrameLayout.LayoutParams(-1, -1));

        // selected box's length (like the mock's "3.0s" chip)
        durChip = text("", 12f, 0xFFFFFFFF, true);
        durChip.setPadding(dp(10), dp(5), dp(10), dp(5));
        durChip.setBackground(round(0x99000000, 14));
        final FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        clp.setMargins(0, 0, dp(12), dp(12));
        holder.addView(durChip, clp);

        final FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        hlp.setMargins(dp(40), dp(4), dp(40), dp(8));
        stage.addView(holder, hlp);
        return stage;
    }

    private View buildTabs() {
        final HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(12), 0, dp(12), 0);
        for (int i = 0; i < boxIdx.size(); i++) {
            final int idx = i;
            final LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            final TextView t = text(getString(R.string.te_slot_n, i + 1), 15f, subColor, true);
            t.setPadding(dp(16), dp(12), dp(16), dp(10));
            final View bar = new View(this);
            bar.setBackground(round(PURPLE, 2));
            col.addView(t);
            col.addView(bar, new LinearLayout.LayoutParams(dp(28), dp(3)));
            col.setOnClickListener(v -> selectSlot(idx));
            row.addView(col);
            tabViews.add(t);
            tabBars.add(bar);
        }
        hs.addView(row, new ViewGroup.LayoutParams(-2, -2));
        return hs;
    }

    private View buildOptions() {
        final NestedScrollView scroll = new NestedScrollView(this) {
            @Override protected void onMeasure(int w, int h) {
                final int cap = Math.round(getResources().getDisplayMetrics().heightPixels * 0.42f);
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST));
            }
        };
        scroll.setVerticalScrollBarEnabled(false);
        final LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(8), dp(16), dp(16));

        if (!boxIdx.isEmpty()) {
            final LinearLayout card = card();
            fltVal = text("", 14f, subColor, false);
            final LinearLayout fr = makeRow(R.drawable.ic_filter, getString(R.string.te_filter), fltVal, true);
            fr.setOnClickListener(v -> showFilterSheet());
            card.addView(fr);
            card.addView(divider());

            animVal = text("", 14f, subColor, false);
            final LinearLayout ar = makeRow(R.drawable.ic_play_rounded, getString(R.string.te_animation), animVal, true);
            ar.setOnClickListener(v -> showAnimSheet());
            card.addView(ar);
            card.addView(divider());

            durVal = text("", 14f, subColor, false);
            card.addView(makeRow(R.drawable.ic_timer, getString(R.string.te_duration), durVal, false));
            durBar = seek((TemplateSpec.MAX_SLOT_MS - TemplateSpec.MIN_SLOT_MS) / 500, (p, user) -> {
                final TemplateSpec.Slot s = cur();
                if (s == null || !user) return;
                s.durMs = TemplateSpec.clampSlotMs(TemplateSpec.MIN_SLOT_MS + p * 500);
                markDirty();
                bindSlot();
            });
            final LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, dp(36));
            dlp.setMargins(dp(54), 0, 0, dp(8));
            card.addView(durBar, dlp);
            col.addView(card);
        } else {
            final TextView none = text(getString(R.string.te_no_boxes), 13f, subColor, false);
            none.setPadding(dp(4), dp(4), dp(4), dp(10));
            col.addView(none);
        }

        // whole template
        final TextView head = text(getString(R.string.te_whole_template), 13f, subColor, true);
        head.setPadding(dp(6), dp(14), 0, dp(8));
        col.addView(head);
        final LinearLayout card2 = card();
        totVal = text("", 14f, subColor, false);
        card2.addView(makeRow(R.drawable.ic_clock_small, getString(R.string.te_total), totVal, false));
        totBar = seek(TemplateSpec.MAX_TOTAL_MS / 500, (p, user) -> {
            if (!user) return;
            spec.totalMs = p == 0 ? 0 : Math.min(TemplateSpec.MAX_TOTAL_MS, p * 500);
            markDirty();
            bindTemplate();
        });
        final LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, dp(36));
        tlp.setMargins(dp(54), 0, 0, dp(8));
        card2.addView(totBar, tlp);
        card2.addView(divider());

        syncSwitch = new SwitchCompat(this);
        syncSwitch.setThumbTintList(ColorStateList.valueOf(0xFFFFFFFF));
        syncSwitch.setTrackTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{PURPLE, 0xFFC9C6D8}));
        final LinearLayout sr = makeRow(R.drawable.ic_music_note, getString(R.string.te_music_sync), null, false);
        sr.addView(syncSwitch);
        sr.setOnClickListener(v -> syncSwitch.toggle());
        syncSwitch.setOnCheckedChangeListener((cb, on) -> {
            if (binding) return;
            spec.musicSync = on;
            markDirty();
            bindTemplate();
        });
        card2.addView(sr);
        syncSub = text(getString(R.string.te_music_sync_sub), 12f, subColor, false);
        syncSub.setPadding(dp(54), 0, dp(14), dp(12));
        card2.addView(syncSub);
        col.addView(card2);

        scroll.addView(col, new ViewGroup.LayoutParams(-1, -2));
        return scroll;
    }

    private LinearLayout card() {
        final LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(round(surface, 18));
        return c;
    }

    private View divider() {
        final View d = new View(this);
        d.setBackgroundColor(dark ? 0x22FFFFFF : 0x14000000);
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 1);
        lp.setMargins(dp(54), 0, 0, 0);
        d.setLayoutParams(lp);
        return d;
    }

    /** icon circle · label · [value] · [chevron] */
    private LinearLayout makeRow(@DrawableRes int ic, String label, @Nullable TextView value, boolean chevron) {
        final LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(14), dp(10), dp(14), dp(10));
        final FrameLayout badge = new FrameLayout(this);
        badge.setBackground(oval(ColorUtils.setAlphaComponent(PURPLE, 0x22)));
        badge.addView(icon(ic, PURPLE, 18), new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER));
        r.addView(badge, new LinearLayout.LayoutParams(dp(34), dp(34)));
        final TextView l = text(label, 15f, textColor, true);
        l.setPadding(dp(12), 0, 0, 0);
        r.addView(l, new LinearLayout.LayoutParams(0, -2, 1f));
        if (value != null) {
            value.setSingleLine(true);
            r.addView(value);
        }
        if (chevron) {
            final ImageView ch = icon(R.drawable.ic_chevron_right, subColor, 20);
            ((LinearLayout.LayoutParams) ch.getLayoutParams()).setMargins(dp(4), 0, 0, 0);
            r.addView(ch);
        }
        return r;
    }

    private interface BarCb { void onChange(int progress, boolean fromUser); }

    private SeekBar seek(int max, final BarCb cb) {
        final SeekBar s = new SeekBar(this);
        s.setMax(max);
        s.setProgressTintList(ColorStateList.valueOf(PURPLE));
        s.setThumbTintList(ColorStateList.valueOf(PURPLE));
        s.setProgressBackgroundTintList(ColorStateList.valueOf(dark ? 0x33FFFFFF : 0x22000000));
        s.setPadding(dp(8), 0, dp(14), 0);
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) { cb.onChange(p, fromUser); }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });
        return s;
    }

    // ── model → controls ─────────────────────────────────────────────────────

    private void selectSlot(int i) {
        if (boxIdx.isEmpty()) { durChip.setVisibility(View.GONE); bindTemplate(); return; }
        sel = Math.max(0, Math.min(boxIdx.size() - 1, i));
        for (int k = 0; k < tabViews.size(); k++) {
            final boolean on = k == sel;
            tabViews.get(k).setTextColor(on ? PURPLE : subColor);
            tabBars.get(k).setVisibility(on ? View.VISIBLE : View.INVISIBLE);
        }
        preview.setFocusPhase(sel);   // the other boxes dim
        bindSlot();
        bindTemplate();
    }

    private void bindSlot() {
        final TemplateSpec.Slot s = cur();
        if (s == null) return;
        binding = true;
        fltVal.setText(filterName(s.filter));
        animVal.setText(animName(s.anim));
        durVal.setText(secs(s.effectiveMs()));
        durBar.setProgress((s.effectiveMs() - TemplateSpec.MIN_SLOT_MS) / 500);
        durChip.setText(secs(s.effectiveMs()));
        binding = false;
    }

    private void bindTemplate() {
        binding = true;
        totVal.setText(spec.totalMs > 0 ? TemplateSpec.formatDuration(spec.totalMs) : getString(R.string.te_auto));
        totBar.setProgress(spec.totalMs / 500);
        syncSwitch.setChecked(spec.musicSync);
        binding = false;
    }

    // ── pickers ──────────────────────────────────────────────────────────────

    private BottomSheetDialog sheet(String title, LinearLayout content, @Nullable SwitchCompat[] allOut) {
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(24));
        root.addView(text(title, 18f, textColor, true));
        final LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.setMargins(0, dp(14), 0, dp(6));
        root.addView(content, clp);
        if (allOut != null && boxIdx.size() > 1) {
            final SwitchCompat sw = new SwitchCompat(this);
            sw.setText(R.string.te_apply_all);
            sw.setTextSize(15f);
            sw.setTextColor(textColor);
            sw.setThumbTintList(ColorStateList.valueOf(0xFFFFFFFF));
            sw.setTrackTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{PURPLE, 0xFFC9C6D8}));
            sw.setPadding(0, dp(10), 0, dp(4));
            root.addView(sw, new LinearLayout.LayoutParams(-1, -2));
            allOut[0] = sw;
        }
        final BottomSheetDialog d = new BottomSheetDialog(this);
        d.setContentView(root);
        return d;
    }

    private void showFilterSheet() {
        final TemplateSpec.Slot s = cur();
        if (s == null) return;
        final LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.HORIZONTAL);
        final HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        final SwitchCompat[] all = new SwitchCompat[1];
        final LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        final BottomSheetDialog[] dlg = new BottomSheetDialog[1];
        for (final String key : TemplateSpec.FILTERS) {
            final LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            final boolean on = key.equals(s.filter);
            final FilterSwatch sw = new FilterSwatch(this, key, on);
            item.addView(sw, new LinearLayout.LayoutParams(dp(64), dp(64)));
            final TextView n = text(filterName(key), 12f, on ? PURPLE : textColor, on);
            n.setPadding(0, dp(6), 0, 0);
            item.addView(n);
            item.setOnClickListener(v -> {
                applyToSlots(all[0] != null && all[0].isChecked(), key, null);
                dlg[0].dismiss();
            });
            final LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(-2, -2);
            ilp.setMargins(0, 0, dp(14), 0);
            grid.addView(item, ilp);
        }
        hs.addView(grid);
        wrap.addView(hs);
        dlg[0] = sheet(getString(R.string.te_filter), wrap, all);
        dlg[0].show();
    }

    private void showAnimSheet() {
        final TemplateSpec.Slot s = cur();
        if (s == null) return;
        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        final SwitchCompat[] all = new SwitchCompat[1];
        final BottomSheetDialog[] dlg = new BottomSheetDialog[1];
        final List<View> movers = new ArrayList<>();
        final List<String> keys = new ArrayList<>();
        for (final String key : TemplateSpec.ANIMS) {
            final boolean on = key.equals(s.anim);
            final LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(0, dp(8), 0, dp(8));
            final FrameLayout tile = new FrameLayout(this);
            tile.setBackground(round(ColorUtils.setAlphaComponent(PURPLE, 0x22), 12));
            clipRound(tile, 12);
            final View mover = new View(this);
            final GradientDrawable mg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{PURPLE_LIGHT, PURPLE});
            mg.setCornerRadius(dp(8));
            mover.setBackground(mg);
            tile.addView(mover, new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER));
            r.addView(tile, new LinearLayout.LayoutParams(dp(48), dp(48)));
            movers.add(mover);
            keys.add(key);
            final TextView n = text(animName(key), 16f, on ? PURPLE : textColor, true);
            n.setPadding(dp(14), 0, 0, 0);
            r.addView(n, new LinearLayout.LayoutParams(0, -2, 1f));
            if (on) r.addView(icon(R.drawable.ic_check_circle, PURPLE, 22));
            r.setOnClickListener(v -> {
                applyToSlots(all[0] != null && all[0].isChecked(), null, key);
                dlg[0].dismiss();
            });
            list.addView(r, new LinearLayout.LayoutParams(-1, -2));
        }
        dlg[0] = sheet(getString(R.string.te_animation), list, all);
        // every row plays its own animation on a loop, so the choice is visible before tapping
        final float[] st = new float[4];
        final ValueAnimator loop = ValueAnimator.ofFloat(0f, 1f);
        loop.setDuration(1600L);
        loop.setRepeatCount(ValueAnimator.INFINITE);
        loop.setInterpolator(new LinearInterpolator());
        loop.addUpdateListener(a -> {
            final float p = (Float) a.getAnimatedValue();
            for (int i = 0; i < movers.size(); i++) {
                TemplateEffects.animState(keys.get(i), p, st);
                final View m = movers.get(i);
                m.setScaleX(st[0]);
                m.setScaleY(st[0]);
                m.setTranslationX(st[1] * dp(8));
                m.setAlpha(st[3]);
            }
        });
        dlg[0].setOnDismissListener(d -> loop.cancel());
        dlg[0].show();
        loop.start();
    }

    private void applyToSlots(boolean all, @Nullable String filter, @Nullable String anim) {
        final int from = all ? 0 : sel, to = all ? boxIdx.size() - 1 : sel;
        for (int i = from; i <= to; i++) {
            final TemplateSpec.Slot s = spec.slots.get(boxIdx.get(i));
            if (filter != null) s.filter = filter;
            if (anim != null) s.anim = anim;
        }
        markDirty();
        bindSlot();
    }

    /** Filter sample: a colourful gradient seen through the filter; ring when selected. */
    private final class FilterSwatch extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final boolean on;

        FilterSwatch(android.content.Context c, String key, boolean on) {
            super(c);
            this.on = on;
            p.setColorFilter(TemplateEffects.colorFilter(key));
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(dp(3));
            ring.setColor(PURPLE);
        }

        @Override protected void onDraw(Canvas c) {
            final float pad = dp(3);
            r.set(pad, pad, getWidth() - pad, getHeight() - pad);
            p.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom,
                    new int[]{0xFF4FC3F7, 0xFF81C784, 0xFFFFB74D, 0xFFF06292}, null, Shader.TileMode.CLAMP));
            final float rad = dp(14);
            c.drawRoundRect(r, rad, rad, p);
            if (on) c.drawRoundRect(r, rad, rad, ring);
        }
    }

    // ── save / close ─────────────────────────────────────────────────────────

    @Nullable
    private String buildJson() {
        try {
            final JSONObject root = new JSONObject(json);
            final JSONArray arr = root.getJSONArray("slots");
            for (int i = 0; i < arr.length() && i < spec.slots.size(); i++) {
                final JSONObject o = arr.getJSONObject(i);
                if (o.optBoolean("base", false)) continue;
                o.remove("d");
                o.remove("flt");
                o.remove("an");
                spec.slots.get(i).putJson(o);   // writes only the non-default values
            }
            root.remove("tdur");
            root.remove("msync");
            spec.putMeta(root);
            final String out = root.toString();
            return out.length() <= StatusTemplateUses.MAX_JSON ? out : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void save() {
        if (saving || !dirty) return;
        final String out = buildJson();
        if (out == null) {
            Toast.makeText(this, R.string.te_save_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        saving = true;
        refreshSave();
        try {
            final DatabaseReference live = FirebaseUtils.db().getReference("status").child(me).child(statusId).child("templateJson");
            final DatabaseReference snap = FirebaseUtils.db().getReference("savedTemplates").child(me)
                    .child(TemplateActions.savedKey(me, statusId)).child("templateJson");
            final int[] pending = {2};
            final boolean[] okAny = {false};
            final Runnable one = () -> {
                if (--pending[0] > 0) return;
                saving = false;
                if (isFinishing() || isDestroyed()) return;
                if (okAny[0]) {
                    dirty = false;
                    Toast.makeText(this, R.string.te_saved, Toast.LENGTH_SHORT).show();
                    setResult(RESULT_OK, new Intent().putExtra(RESULT_JSON, out));
                    finish();
                } else {
                    refreshSave();
                    Toast.makeText(this, R.string.te_save_failed, Toast.LENGTH_SHORT).show();
                }
            };
            writeIfExists(live, out, okAny, one);   // the 24h status, while it is live
            writeIfExists(snap, out, okAny, one);   // the owner's saved snapshot, if any
        } catch (Exception e) {
            saving = false;
            refreshSave();
            Toast.makeText(this, R.string.te_save_failed, Toast.LENGTH_SHORT).show();
        }
    }

    /** setValue only where the node already exists (a stray partial node would be rejected by the rules anyway). */
    private void writeIfExists(final DatabaseReference ref, final String value, final boolean[] okAny, final Runnable done) {
        ref.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                if (!s.exists()) { done.run(); return; }
                ref.setValue(value).addOnCompleteListener(t -> {
                    if (t.isSuccessful()) okAny[0] = true;
                    done.run();
                });
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { done.run(); }
        });
    }

    private void tryClose() {
        if (!dirty || saving) { finish(); return; }
        new AlertDialog.Builder(this)
                .setTitle(R.string.te_discard_title)
                .setMessage(R.string.te_discard_msg)
                .setPositiveButton(R.string.te_save, (d, w) -> save())
                .setNegativeButton(R.string.te_discard, (d, w) -> { dirty = false; finish(); })
                .setNeutralButton(R.string.te_keep_editing, null)
                .show();
    }
}
