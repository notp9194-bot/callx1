package com.callx.app.compose;

import android.content.Intent;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.WindowCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.core.widget.NestedScrollView;
import android.content.res.ColorStateList;

import com.callx.app.cache.StatusCacheManager;
import com.callx.app.models.StatusItem;
import com.callx.app.status.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Templates home screen (v764 redesign, premium iOS look: white surface, purple accent, rounded cards).
 *  - Header (My Templates pill) + search + sort, chips (Trending / Popular / Collage / Love / Fun),
 *    auto-rotating "Trending This Week" banner, Trending grid (See All), Categories row, bottom nav.
 *  - Data layer is unchanged from v751: Discover = live photo templates from your cached status tray, Saved = savedTemplates/{me}.
 *    Tap a card -> TemplateActions.use (re-checks the owner's reuse switch).
 * Every icon is an existing project drawable (tinted in code); no new artwork.
 */
public class TemplateBrowserActivity extends AppCompatActivity {

    private static final int MAX_SAVED = 60;
    private static final int GRID_COLS = 3, HOME_ROWS = 2;
    private static final int PURPLE = 0xFF6A3DE8, PURPLE_LIGHT = 0xFF9A7BFF;
    private static final int MODE_HOME = 0, MODE_SAVED = 1, MODE_MINE = 2;
    private static final int CHIP_TRENDING = 0, CHIP_POPULAR = 1, CHIP_COLLAGE = 2, CHIP_LOVE = 3, CHIP_FUN = 4;
    private static final int SORT_DEFAULT = 0, SORT_USED = 1, SORT_NEWEST = 2, SORT_PHOTOS = 3;

    private static final class Row {
        String tplJson, stickersJson;
        String originUid, originId, originName;
        boolean mine, original;
        long uses = -1;
        int cat = -1;      // v765: TemplateCategory index
        long ts;           // status time (Discover order tie-break)
        long savedTs;      // when it was saved (Saved order)
        boolean blocked;   // owner turned reuse off / removed it
        boolean metaAsked;
        TemplatePreviewDrawable preview;
        String key() { return originUid + "/" + originId; }
        long time() { return ts > 0 ? ts : savedTs; }
    }

    private final Map<String, Row> discover = new LinkedHashMap<>();
    private final Map<String, Row> saved = new LinkedHashMap<>();
    private final Set<String> savedKeys = new HashSet<>();
    private final List<Row> shown = new ArrayList<>();
    private final List<Row> bannerRows = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable rebuildRun = this::rebuild;
    private final Runnable bannerRun = this::nextBanner;

    // palette
    private boolean dark;
    private int bg, surface, textColor, subColor;

    // state
    private int mode = MODE_HOME, chip = CHIP_TRENDING, sortOverride = SORT_DEFAULT, catFilter = -1;
    private String query = "";
    private boolean showAll;
    private int bannerIdx;

    // views
    private LinearLayout myPill, chipRow, gridBox, navTemplates, navSaved;
    private TextView sectionTitle, seeAll, empty, bannerTitle, bannerSub;
    private View bannerCard, categoriesBox;
    private ImageView bannerL, bannerC, bannerR;
    private LinearLayout dots;
    private final List<LinearLayout> chipViews = new ArrayList<>();
    private final List<View> catTiles = new ArrayList<>();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
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
        root.setClipChildren(false);

        final NestedScrollView scroll = new NestedScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        final LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, dp(24));
        scroll.addView(content, new ViewGroup.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        buildHeader(content);
        buildSearch(content);
        buildChips(content);
        buildBanner(content);
        buildSection(content);
        buildCategories(content);
        root.addView(buildNav(), new LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
        if (!dark) WindowCompat.getInsetsController(getWindow(), root).setAppearanceLightStatusBars(true);

        loadDiscover();
        loadSaved();
        rebuild();
    }

    @Override protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ── small helpers ────────────────────────────────────────────────────────

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private static boolean ne(String s) { return s != null && !s.isEmpty(); }

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

    private static String compact(long n) {
        if (n >= 1_000_000) return trimZero(n / 100_000 / 10.0) + "M";
        if (n >= 1_000) return trimZero(n / 100 / 10.0) + "K";
        return String.valueOf(n);
    }

    private static String trimZero(double d) {
        final String s = String.format(Locale.US, "%.1f", d);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    // ── header / search / chips ──────────────────────────────────────────────

    private void buildHeader(LinearLayout content) {
        final LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(16), dp(12), dp(16), dp(10));

        final FrameLayout tile = new FrameLayout(this);
        final GradientDrawable tg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{PURPLE_LIGHT, PURPLE});
        tg.setCornerRadius(dp(14));
        tile.setBackground(tg);
        final ImageView ti = icon(R.drawable.ic_video, 0xFFFFFFFF, 26);
        tile.addView(ti, new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER));
        head.addView(tile, new LinearLayout.LayoutParams(dp(46), dp(46)));
        tile.setOnClickListener(v -> finish());
        tile.setContentDescription(getString(R.string.tpl_home_title));

        final TextView title = text(getString(R.string.tpl_home_title), 26f, textColor, true);
        final LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1f);
        tlp.setMargins(dp(12), 0, dp(8), 0);
        head.addView(title, tlp);

        myPill = new LinearLayout(this);
        myPill.setOrientation(LinearLayout.HORIZONTAL);
        myPill.setGravity(Gravity.CENTER_VERTICAL);
        myPill.setPadding(dp(14), dp(10), dp(16), dp(10));
        myPill.addView(icon(R.drawable.ic_premium_badge, 0xFFFFC947, 20));
        final TextView mt = text(getString(R.string.tpl_home_my_templates), 14f, textColor, true);
        mt.setPadding(dp(6), 0, 0, 0);
        myPill.addView(mt);
        myPill.setOnClickListener(v -> setMode(mode == MODE_MINE ? MODE_HOME : MODE_MINE));
        head.addView(myPill, new LinearLayout.LayoutParams(-2, -2));
        content.addView(head, new LinearLayout.LayoutParams(-1, -2));
    }

    private void buildSearch(LinearLayout content) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(2), dp(16), dp(12));

        final LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), 0, dp(12), 0);
        bar.setBackground(round(surface, 26));
        bar.addView(icon(R.drawable.ic_search, subColor, 22));
        final EditText et = new EditText(this);
        et.setHint(R.string.tpl_home_search_hint);
        et.setHintTextColor(subColor);
        et.setTextColor(textColor);
        et.setTextSize(15f);
        et.setSingleLine(true);
        et.setBackground(null);
        et.setPadding(dp(10), 0, 0, 0);
        et.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b2, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b2, int c) { }
            @Override public void afterTextChanged(Editable s) {
                query = s == null ? "" : s.toString().trim().toLowerCase(Locale.ROOT);
                rebuild();
            }
        });
        bar.addView(et, new LinearLayout.LayoutParams(0, -1, 1f));
        row.addView(bar, new LinearLayout.LayoutParams(0, dp(52), 1f));

        final FrameLayout fb = new FrameLayout(this);
        fb.setBackground(oval(surface));
        fb.addView(icon(R.drawable.ic_filter, textColor, 24), new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        fb.setContentDescription(getString(R.string.tpl_home_filter));
        fb.setOnClickListener(v -> showSortMenu(v));
        final LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(dp(52), dp(52));
        flp.setMargins(dp(10), 0, 0, 0);
        row.addView(fb, flp);
        content.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private void showSortMenu(View anchor) {
        final PopupMenu pm = new PopupMenu(this, anchor);
        pm.getMenu().add(0, SORT_USED, 0, R.string.tpl_sort_used);
        pm.getMenu().add(0, SORT_NEWEST, 1, R.string.tpl_sort_newest);
        pm.getMenu().add(0, SORT_PHOTOS, 2, R.string.tpl_sort_photos);
        pm.setOnMenuItemClickListener(mi -> { sortOverride = mi.getItemId(); rebuild(); return true; });
        pm.show();
    }

    private void buildChips(LinearLayout content) {
        final HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipToPadding(false);
        hs.setPadding(dp(16), 0, dp(16), dp(14));
        chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        final int[] labels = {R.string.tpl_chip_trending, R.string.tpl_chip_popular, R.string.tpl_chip_collage, R.string.tpl_chip_love, R.string.tpl_chip_fun};
        final int[] icons = {R.drawable.ic_sound_flame_outline, R.drawable.ic_star_outline, R.drawable.ic_layout_grid_2x2, R.drawable.ic_heart_filled, R.drawable.ic_emoji};
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            final LinearLayout c = new LinearLayout(this);
            c.setOrientation(LinearLayout.HORIZONTAL);
            c.setGravity(Gravity.CENTER_VERTICAL);
            c.setPadding(dp(16), dp(10), dp(18), dp(10));
            c.addView(icon(icons[i], textColor, 18));
            final TextView t = text(getString(labels[i]), 14f, textColor, true);
            t.setPadding(dp(7), 0, 0, 0);
            c.addView(t);
            c.setOnClickListener(v -> {
                chip = idx;
                catFilter = -1;
                sortOverride = SORT_DEFAULT;
                showAll = false;
                if (mode != MODE_HOME) mode = MODE_HOME;
                rebuild();
            });
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(0, 0, dp(10), 0);
            chipRow.addView(c, lp);
            chipViews.add(c);
        }
        hs.addView(chipRow, new ViewGroup.LayoutParams(-2, -2));
        content.addView(hs, new LinearLayout.LayoutParams(-1, -2));
    }

    private void styleChips() {
        final int[] idleTint = {textColor, 0xFFFFB400, textColor, 0xFFFF3B5C, textColor};
        for (int i = 0; i < chipViews.size(); i++) {
            final LinearLayout c = chipViews.get(i);
            final boolean on = mode == MODE_HOME && catFilter < 0 && chip == i;
            c.setBackground(round(on ? PURPLE : surface, 24));
            final ImageView ic = (ImageView) c.getChildAt(0);
            final TextView t = (TextView) c.getChildAt(1);
            ImageViewCompat.setImageTintList(ic, ColorStateList.valueOf(on ? 0xFFFFFFFF : idleTint[i]));
            t.setTextColor(on ? 0xFFFFFFFF : textColor);
        }
    }

    // ── banner ───────────────────────────────────────────────────────────────

    private ImageView bannerCardView(FrameLayout holder, int wDp, int hDp, int gravity, float rot, int marginStartDp, int marginEndDp) {
        final FrameLayout card = new FrameLayout(this);
        card.setBackground(round(0xFFFFFFFF, 14));
        card.setPadding(dp(2), dp(2), dp(2), dp(2));
        card.setRotation(rot);
        card.setElevation(dp(6));
        final ImageView p = new ImageView(this);
        p.setScaleType(ImageView.ScaleType.FIT_XY);
        clipRound(p, 12);
        card.addView(p, new FrameLayout.LayoutParams(-1, -1));
        final FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(wDp), dp(hDp), gravity);
        lp.setMargins(dp(marginStartDp), 0, dp(marginEndDp), 0);
        holder.addView(card, lp);
        return p;
    }

    private void buildBanner(LinearLayout content) {
        final FrameLayout banner = new FrameLayout(this);
        banner.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF1A1033, 0xFF3A2290, 0xFF6A3DE8}));
        ((GradientDrawable) banner.getBackground()).setCornerRadius(dp(26));
        clipRound(banner, 26);
        bannerCard = banner;

        // right: 3 rotated cards (neighbour, hero, neighbour)
        final FrameLayout art = new FrameLayout(this);
        art.setClipChildren(false);
        bannerL = bannerCardView(art, 74, 108, Gravity.START | Gravity.CENTER_VERTICAL, -9f, 0, 0);
        bannerR = bannerCardView(art, 70, 100, Gravity.END | Gravity.TOP, 8f, 0, 4);
        bannerC = bannerCardView(art, 92, 140, Gravity.CENTER, 0f, 0, 0);
        final FrameLayout playHolder = (FrameLayout) bannerC.getParent();
        final FrameLayout playBadge = new FrameLayout(this);
        final GradientDrawable pg = new GradientDrawable();
        pg.setShape(GradientDrawable.OVAL);
        pg.setColor(0x66000000);
        pg.setStroke(dp(2), 0xFFFFFFFF);
        playBadge.setBackground(pg);
        playBadge.addView(icon(R.drawable.ic_status_card_play, 0xFFFFFFFF, 22), new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        playHolder.addView(playBadge, new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER));
        final FrameLayout.LayoutParams alp = new FrameLayout.LayoutParams(dp(170), dp(150), Gravity.END | Gravity.CENTER_VERTICAL);
        alp.setMargins(0, 0, dp(10), 0);
        banner.addView(art, alp);

        // left: pill, title, sub, CTA
        final LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setPadding(dp(18), dp(16), dp(8), dp(16));
        final LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(10), dp(6), dp(12), dp(6));
        pill.setBackground(round(0x26FFFFFF, 16));
        pill.addView(icon(R.drawable.ic_sound_flame_outline, 0xFFFF7A1A, 14));
        final TextView pt = text(getString(R.string.tpl_banner_pill), 11f, 0xFFFFFFFF, true);
        pt.setPadding(dp(5), 0, 0, 0);
        pill.addView(pt);
        left.addView(pill, new LinearLayout.LayoutParams(-2, -2));
        bannerTitle = text("", 21f, 0xFFFFFFFF, true);
        bannerTitle.setMaxLines(2);
        bannerTitle.setEllipsize(TextUtils.TruncateAt.END);
        final LinearLayout.LayoutParams ttl = new LinearLayout.LayoutParams(-2, -2);
        ttl.setMargins(0, dp(10), 0, 0);
        left.addView(bannerTitle, ttl);
        bannerSub = text("", 12f, 0xCCFFFFFF, false);
        bannerSub.setMaxLines(2);
        bannerSub.setEllipsize(TextUtils.TruncateAt.END);
        final LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2);
        sl.setMargins(0, dp(6), 0, 0);
        left.addView(bannerSub, sl);
        left.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));
        final LinearLayout cta = new LinearLayout(this);
        cta.setOrientation(LinearLayout.HORIZONTAL);
        cta.setGravity(Gravity.CENTER_VERTICAL);
        cta.setPadding(dp(16), dp(10), dp(12), dp(10));
        cta.setBackground(round(0xFFFFFFFF, 22));
        cta.addView(text(getString(R.string.tpl_banner_cta), 13f, 0xFF14142B, true));
        final ImageView ca = icon(R.drawable.ic_arrow_forward, 0xFF14142B, 18);
        cta.addView(ca);
        cta.setOnClickListener(v -> { if (!bannerRows.isEmpty()) open(bannerRows.get(bannerIdx % bannerRows.size())); });
        left.addView(cta, new LinearLayout.LayoutParams(-2, -2));
        banner.addView(left, new FrameLayout.LayoutParams(dp(190), -1, Gravity.START));

        dots = new LinearLayout(this);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        final FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        dlp.setMargins(0, 0, dp(70), dp(12));
        banner.addView(dots, dlp);

        final LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, dp(176));
        blp.setMargins(dp(16), 0, dp(16), dp(20));
        content.addView(banner, blp);
    }

    private void bindBanner() {
        if (bannerRows.isEmpty()) return;
        final int n = bannerRows.size();
        bannerIdx = ((bannerIdx % n) + n) % n;
        final Row c = bannerRows.get(bannerIdx);
        final Row l = bannerRows.get((bannerIdx + n - 1) % n);
        final Row r = bannerRows.get((bannerIdx + 1) % n);
        bannerC.setImageDrawable(TemplatePreviewDrawable.fromJson(c.tplJson));
        bannerL.setImageDrawable(TemplatePreviewDrawable.fromJson(l.tplJson));
        bannerR.setImageDrawable(TemplatePreviewDrawable.fromJson(r.tplJson));
        bannerTitle.setText(c.mine ? getString(R.string.tpl_banner_title_mine)
                : getString(R.string.tpl_banner_title_other, ne(c.originName) ? c.originName : "user"));
        bannerSub.setText(getString(R.string.tpl_banner_sub, c.preview.photoCount()));
        dots.removeAllViews();
        for (int i = 0; i < n; i++) {
            final View d = new View(this);
            d.setBackground(round(i == bannerIdx ? 0xFFFFFFFF : 0x66FFFFFF, 3));
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(i == bannerIdx ? 22 : 12), dp(4));
            lp.setMargins(dp(3), 0, dp(3), 0);
            final int to = i;
            d.setOnClickListener(v -> { bannerIdx = to; bindBanner(); });
            dots.addView(d, lp);
        }
        ui.removeCallbacks(bannerRun);
        if (n > 1) ui.postDelayed(bannerRun, 4000);
    }

    private void nextBanner() {
        if (isFinishing() || isDestroyed() || bannerRows.size() < 2) return;
        bannerIdx++;
        bindBanner();
    }

    // ── section (grid) / categories ──────────────────────────────────────────

    private void buildSection(LinearLayout content) {
        final LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(18), 0, dp(14), dp(12));
        head.addView(icon(R.drawable.ic_sound_flame_outline, 0xFFFF6A1A, 24));
        sectionTitle = text("", 19f, textColor, true);
        sectionTitle.setPadding(dp(8), 0, 0, 0);
        head.addView(sectionTitle, new LinearLayout.LayoutParams(0, -2, 1f));
        seeAll = text(getString(R.string.tpl_see_all), 14f, PURPLE, true);
        seeAll.setCompoundDrawablePadding(dp(4));
        seeAll.setPadding(dp(8), dp(6), dp(4), dp(6));
        seeAll.setOnClickListener(v -> { showAll = !showAll; rebuild(); });
        head.addView(seeAll);
        content.addView(head, new LinearLayout.LayoutParams(-1, -2));

        gridBox = new LinearLayout(this);
        gridBox.setOrientation(LinearLayout.VERTICAL);
        gridBox.setPadding(dp(16), 0, dp(16), 0);
        content.addView(gridBox, new LinearLayout.LayoutParams(-1, -2));

        empty = text("", 14f, subColor, false);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(32), dp(40), dp(32), dp(40));
        empty.setLineSpacing(0, 1.15f);
        empty.setVisibility(View.GONE);
        content.addView(empty, new LinearLayout.LayoutParams(-1, -2));
    }

    private void buildCategories(LinearLayout content) {
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        categoriesBox = box;

        final LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(18), dp(24), dp(14), dp(12));
        head.addView(icon(R.drawable.ic_layout_grid_2x2, PURPLE, 22));
        final TextView t = text(getString(R.string.tpl_section_categories), 19f, textColor, true);
        t.setPadding(dp(8), 0, 0, 0);
        head.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        box.addView(head, new LinearLayout.LayoutParams(-1, -2));

        final HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipToPadding(false);
        hs.setPadding(dp(16), 0, dp(16), 0);
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        final int[] names = {R.string.tpl_cat_love, R.string.tpl_cat_travel, R.string.tpl_cat_birthday, R.string.tpl_cat_festival, R.string.tpl_cat_aesthetic};
        final int[] icons = {R.drawable.ic_heart_filled, R.drawable.ic_video, R.drawable.ic_emoji, R.drawable.ic_music_note, R.drawable.ic_star_outline};
        final int[][] grads = {{0xFF5A0010, 0xFFD6203A}, {0xFF1D4F8F, 0xFF6FB1E8}, {0xFF5B3A1E, 0xFFE59A4C}, {0xFF3A1A08, 0xFFE0701E}, {0xFF5A3B52, 0xFFE59BC0}};
        final int[] tints = {0xFFFF4D6D, 0xFFFFFFFF, 0xFFFFE08A, 0xFFFFD27A, 0xFFFFC2DD};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            final FrameLayout tile = new FrameLayout(this);
            tile.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, grads[i]));
            ((GradientDrawable) tile.getBackground()).setCornerRadius(dp(18));
            clipRound(tile, 18);
            final ImageView ic = icon(icons[i], tints[i], 30);
            tile.addView(ic, new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER_HORIZONTAL | Gravity.TOP));
            ((FrameLayout.LayoutParams) ic.getLayoutParams()).topMargin = dp(14);
            final TextView lbl = text(getString(names[i]), 13f, 0xFFFFFFFF, true);
            final FrameLayout.LayoutParams ll = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
            ll.bottomMargin = dp(10);
            tile.addView(lbl, ll);
            tile.setOnClickListener(v -> onCategory(idx));
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(92), dp(86));
            lp.setMargins(0, 0, dp(10), 0);
            row.addView(tile, lp);
            catTiles.add(tile);
        }
        hs.addView(row, new ViewGroup.LayoutParams(-2, -2));
        box.addView(hs, new LinearLayout.LayoutParams(-1, -2));
        content.addView(box, new LinearLayout.LayoutParams(-1, -2));
    }

    /** v765: tap = show only templates tagged with that category; tap again = clear. */
    private void onCategory(int idx) {
        catFilter = catFilter == idx ? -1 : idx;
        mode = MODE_HOME;
        sortOverride = SORT_DEFAULT;
        showAll = false;
        rebuild();
    }

    private void styleCategories() {
        for (int i = 0; i < catTiles.size(); i++) catTiles.get(i).setAlpha(catFilter < 0 || catFilter == i ? 1f : 0.45f);
    }

    // ── bottom nav ───────────────────────────────────────────────────────────

    private LinearLayout navItem(@DrawableRes int res, int label) {
        final LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setPadding(0, dp(10), 0, dp(6));
        c.addView(icon(res, subColor, 26));
        final TextView t = text(getString(label), 12f, subColor, false);
        t.setPadding(0, dp(4), 0, dp(3));
        c.addView(t);
        final View bar = new View(this);
        bar.setBackground(round(PURPLE, 2));
        bar.setVisibility(View.INVISIBLE);
        c.addView(bar, new LinearLayout.LayoutParams(dp(40), dp(3)));
        return c;
    }

    private void styleNavItem(LinearLayout c, boolean on) {
        final int col = on ? PURPLE : subColor;
        ImageViewCompat.setImageTintList((ImageView) c.getChildAt(0), ColorStateList.valueOf(col));
        final TextView t = (TextView) c.getChildAt(1);
        t.setTextColor(col);
        t.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        c.getChildAt(2).setVisibility(on ? View.VISIBLE : View.INVISIBLE);
    }

    private View buildNav() {
        final LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setClipChildren(false);
        final View line = new View(this);
        line.setBackgroundColor(dark ? 0x22FFFFFF : 0x14000000);
        wrap.addView(line, new LinearLayout.LayoutParams(-1, 1));

        final LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setClipChildren(false);
        bar.setBackgroundColor(bg);

        navTemplates = navItem(R.drawable.ic_home, R.string.tpl_nav_templates);
        navSaved = navItem(R.drawable.ic_bookmark, R.string.tpl_nav_saved);
        final LinearLayout navNotif = navItem(R.drawable.ic_notifications, R.string.tpl_nav_notifications);
        final LinearLayout navProfile = navItem(R.drawable.ic_person, R.string.tpl_nav_profile);
        navTemplates.setOnClickListener(v -> setMode(MODE_HOME));
        navSaved.setOnClickListener(v -> setMode(mode == MODE_SAVED ? MODE_HOME : MODE_SAVED));
        navNotif.setOnClickListener(v -> openByName("com.callx.app.activities.NotificationCenterActivity"));
        navProfile.setOnClickListener(v -> openByName("com.callx.app.activities.ProfileActivity"));

        final FrameLayout fabCell = new FrameLayout(this);
        fabCell.setClipChildren(false);
        final FrameLayout fab = new FrameLayout(this);
        final GradientDrawable fg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{PURPLE_LIGHT, PURPLE});
        fg.setShape(GradientDrawable.OVAL);
        fab.setBackground(fg);
        fab.setElevation(dp(8));
        fab.addView(icon(R.drawable.ic_add, 0xFFFFFFFF, 30), new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER));
        fab.setContentDescription(getString(R.string.tpl_nav_create));
        fab.setOnClickListener(v -> startActivity(new Intent(this, NewStatusActivity.class)));
        final FrameLayout.LayoutParams fl = new FrameLayout.LayoutParams(dp(58), dp(58), Gravity.CENTER);
        fl.topMargin = -dp(16);
        fabCell.addView(fab, fl);

        bar.addView(navTemplates, new LinearLayout.LayoutParams(0, -2, 1f));
        bar.addView(navSaved, new LinearLayout.LayoutParams(0, -2, 1f));
        bar.addView(fabCell, new LinearLayout.LayoutParams(0, dp(58), 1f));
        bar.addView(navNotif, new LinearLayout.LayoutParams(0, -2, 1f));
        bar.addView(navProfile, new LinearLayout.LayoutParams(0, -2, 1f));
        wrap.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        return wrap;
    }

    private void openByName(String cls) {
        try {
            startActivity(new Intent(this, Class.forName(cls)));
        } catch (Throwable t) {
            Toast.makeText(this, R.string.tpl_nav_unavailable, Toast.LENGTH_SHORT).show();
        }
    }

    private void setMode(int m) {
        mode = m;
        catFilter = -1;
        sortOverride = SORT_DEFAULT;
        showAll = false;
        rebuild();
    }

    // ── data ─────────────────────────────────────────────────────────────────

    private void loadDiscover() {
        final String me = FirebaseUtils.getCurrentUid();
        final StatusCacheManager cm = StatusCacheManager.getInstance(getApplicationContext());
        final Map<String, List<StatusItem>> all = new LinkedHashMap<>(cm.getAllStatuses());
        if (ne(me) && !all.containsKey(me)) all.put(me, cm.getStatuses(me));

        discover.clear();
        for (List<StatusItem> list : all.values()) {
            if (list == null) continue;
            for (StatusItem it : list) {
                if (it == null || !ne(it.templateJson) || !ne(it.id)) continue;
                final boolean hasFrom = ne(it.templateFromUid) && ne(it.templateFromId);
                final Row r = new Row();
                r.tplJson = it.templateJson;
                r.stickersJson = it.stickersJson;
                r.original = !hasFrom;                                   // a repost only points at the original
                r.originUid = hasFrom ? it.templateFromUid : it.ownerUid;
                r.originId = hasFrom ? it.templateFromId : it.id;
                r.originName = hasFrom ? it.templateFromName : it.ownerName;
                if (!ne(r.originUid) || !ne(r.originId)) continue;
                r.mine = ne(me) && me.equals(r.originUid);
                r.ts = it.timestamp != null ? it.timestamp : 0L;
                r.preview = TemplatePreviewDrawable.fromJson(it.templateJson);
                r.cat = TemplateCategory.indexOf(it.templateJson);
                if (r.preview == null) continue;
                final Row old = discover.get(r.key());
                if (old == null || (!old.original && r.original)) discover.put(r.key(), r); // the original wins over a repost of it
            }
        }
    }

    /** savedTemplates/{me}: snapshots (they outlive the 24h status). Hearts everywhere follow {@link #savedKeys}. */
    private void loadSaved() {
        final String me = FirebaseUtils.getCurrentUid();
        if (!ne(me)) return;
        try {
            FirebaseUtils.db().getReference("savedTemplates").child(me).orderByChild("ts").limitToLast(MAX_SAVED)
                    .addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override public void onDataChange(@NonNull DataSnapshot s) {
                            if (isFinishing() || isDestroyed()) return;
                            for (DataSnapshot c : s.getChildren()) {
                                final String json = c.child("templateJson").getValue(String.class);
                                final String ou = c.child("ownerUid").getValue(String.class);
                                final String oi = c.child("statusId").getValue(String.class);
                                if (!ne(json) || !ne(ou) || !ne(oi)) continue;
                                final String key = ou + "/" + oi;
                                if (saved.containsKey(key)) continue;       // toggled while this read was in flight
                                final Row r = new Row();
                                r.tplJson = json;
                                r.stickersJson = c.child("stickersJson").getValue(String.class);
                                r.originUid = ou;
                                r.originId = oi;
                                r.originName = c.child("ownerName").getValue(String.class);
                                r.mine = ou.equals(me);
                                final Long ts = c.child("ts").getValue(Long.class);
                                r.savedTs = ts != null ? ts : 0L;
                                r.preview = TemplatePreviewDrawable.fromJson(json);
                                r.cat = TemplateCategory.indexOf(json);
                                if (r.preview == null) continue;
                                saved.put(key, r);
                                savedKeys.add(key);
                            }
                            scheduleRebuild();
                        }
                        @Override public void onCancelled(@NonNull DatabaseError e) { /* Saved just stays empty */ }
                    });
        } catch (Exception ignored) { }
    }

    /** "N used" + the owner's reuse switch, once per card. */
    private void fetchMeta(final Row r) {
        if (r.metaAsked) return;
        r.metaAsked = true;
        final int[] pending = {r.mine ? 1 : 2};
        FirebaseUtils.db().getReference("statusTemplateUses").child(r.originUid).child(r.originId)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot s) { r.uses = s.getChildrenCount(); done(); }
                    @Override public void onCancelled(@NonNull DatabaseError e) { r.uses = 0; done(); }
                    private void done() { if (--pending[0] <= 0) scheduleRebuild(); }
                });
        if (r.mine) return;   // the owner is never blocked from his own template
        TemplateActions.fetchControl(r.originUid, r.originId, (off, removed) -> {
            r.blocked = off || removed;
            if (--pending[0] <= 0) scheduleRebuild();
        });
    }

    private void scheduleRebuild() {
        ui.removeCallbacks(rebuildRun);
        ui.postDelayed(rebuildRun, 120);   // many cards answer at once → one redraw
    }

    private List<Row> union() {
        final Map<String, Row> m = new LinkedHashMap<>(saved);
        m.putAll(discover);                       // fresher discover row wins over its saved snapshot
        return new ArrayList<>(m.values());
    }

    private void rebuild() {
        if (isFinishing() || isDestroyed() || gridBox == null) return;
        List<Row> pool = new ArrayList<>();
        if (mode == MODE_SAVED) {
            pool.addAll(saved.values());
        } else if (mode == MODE_MINE) {
            for (Row r : union()) if (r.mine) pool.add(r);
        } else {
            switch (chip) {
                case CHIP_POPULAR: pool = union(); break;
                case CHIP_LOVE:
                    for (Row r : union()) if (savedKeys.contains(r.key())) pool.add(r);
                    break;
                case CHIP_COLLAGE:
                    for (Row r : discover.values()) if (r.preview.photoCount() >= 2) pool.add(r);
                    break;
                default: pool.addAll(discover.values());
            }
        }
        shown.clear();
        for (Row r : pool) {
            if (mode != MODE_SAVED && r.blocked) continue;   // owner turned reuse off / removed it -> no dead card
            if (catFilter >= 0 && r.cat != catFilter) continue;
            if (!query.isEmpty()) {
                final String nm = (r.mine ? "your template" : (ne(r.originName) ? r.originName : "user")).toLowerCase(Locale.ROOT);
                if (!nm.contains(query)) continue;
            }
            shown.add(r);
        }
        final int sort = sortOverride != SORT_DEFAULT ? sortOverride
                : (mode == MODE_SAVED ? SORT_NEWEST : (mode == MODE_HOME && chip == CHIP_FUN) || mode == MODE_MINE ? SORT_NEWEST : SORT_USED);
        Collections.sort(shown, (a, b) -> {
            if (sort == SORT_PHOTOS) {
                final int pa = a.preview.photoCount(), pb = b.preview.photoCount();
                if (pa != pb) return pb - pa;
            } else if (sort == SORT_USED) {
                final long ua = Math.max(0, a.uses), ub = Math.max(0, b.uses);
                if (ua != ub) return ub > ua ? 1 : -1;
            }
            return Long.compare(mode == MODE_SAVED ? b.savedTs : b.time(), mode == MODE_SAVED ? a.savedTs : a.time());
        });

        // banner: only on the plain home view
        final boolean home = mode == MODE_HOME && query.isEmpty() && catFilter < 0;
        bannerRows.clear();
        if (home) {
            final List<Row> top = new ArrayList<>();
            for (Row r : discover.values()) if (!r.blocked) top.add(r);
            Collections.sort(top, (a, b) -> {
                final long ua = Math.max(0, a.uses), ub = Math.max(0, b.uses);
                return ua != ub ? (ub > ua ? 1 : -1) : Long.compare(b.ts, a.ts);
            });
            for (int i = 0; i < top.size() && i < 3; i++) bannerRows.add(top.get(i));
        }
        bannerCard.setVisibility(bannerRows.isEmpty() ? View.GONE : View.VISIBLE);
        if (bannerRows.isEmpty()) ui.removeCallbacks(bannerRun); else bindBanner();
        categoriesBox.setVisibility(mode == MODE_HOME && query.isEmpty() ? View.VISIBLE : View.GONE);
        styleCategories();

        // titles + See All
        final int titleRes = mode == MODE_SAVED ? R.string.tpl_section_saved : mode == MODE_MINE ? R.string.tpl_section_mine
                : chip == CHIP_POPULAR ? R.string.tpl_section_popular : chip == CHIP_COLLAGE ? R.string.tpl_section_collage
                : chip == CHIP_LOVE ? R.string.tpl_section_love : chip == CHIP_FUN ? R.string.tpl_section_fun
                : R.string.tpl_section_trending;
        final int[] catNames = {R.string.tpl_cat_love, R.string.tpl_cat_travel, R.string.tpl_cat_birthday, R.string.tpl_cat_festival, R.string.tpl_cat_aesthetic};
        if (mode == MODE_HOME && catFilter >= 0) sectionTitle.setText(catNames[catFilter]); else sectionTitle.setText(titleRes);
        final boolean limited = mode == MODE_HOME && query.isEmpty() && catFilter < 0 && !showAll;
        final int cap = GRID_COLS * HOME_ROWS;
        final boolean canToggle = mode == MODE_HOME && query.isEmpty() && catFilter < 0 && shown.size() > cap;
        seeAll.setVisibility(canToggle ? View.VISIBLE : View.GONE);
        seeAll.setText(showAll ? R.string.tpl_see_less : R.string.tpl_see_all);

        styleChips();
        styleNavItem(navTemplates, mode == MODE_HOME || mode == MODE_MINE);
        styleNavItem(navSaved, mode == MODE_SAVED);
        myPill.setBackground(mode == MODE_MINE ? purpleStroke() : round(surface, 26));

        renderGrid(limited ? Math.min(cap, shown.size()) : shown.size());

        final int emptyRes = !query.isEmpty() ? R.string.tpl_empty_search
                : (mode == MODE_HOME && catFilter >= 0) ? R.string.tpl_empty_cat
                : mode == MODE_SAVED ? R.string.tpl_empty_saved : mode == MODE_MINE ? R.string.tpl_empty_mine
                : chip == CHIP_LOVE ? R.string.tpl_empty_love : R.string.tpl_empty_home;
        empty.setText(emptyRes);
        empty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
        for (Row r : new ArrayList<>(shown)) fetchMeta(r);
    }

    private GradientDrawable purpleStroke() {
        final GradientDrawable g = round(dark ? 0x336A3DE8 : 0x1A6A3DE8, 26);
        g.setStroke(dp(1), PURPLE);
        return g;
    }

    private void renderGrid(int count) {
        gridBox.removeAllViews();
        final int gap = dp(10);
        final int cellW = (getResources().getDisplayMetrics().widthPixels - dp(32) - gap * (GRID_COLS - 1)) / GRID_COLS;
        LinearLayout row = null;
        for (int i = 0; i < count; i++) {
            if (i % GRID_COLS == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                final LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
                rlp.setMargins(0, 0, 0, gap);
                gridBox.addView(row, rlp);
            }
            final LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(cellW, Math.round(cellW * 1.45f));
            clp.setMargins(0, 0, (i % GRID_COLS == GRID_COLS - 1) ? 0 : gap, 0);
            row.addView(makeCard(shown.get(i)), clp);
        }
    }

    private View makeCard(final Row r) {
        final FrameLayout card = new FrameLayout(this);
        card.setBackground(round(surface, 18));
        clipRound(card, 18);
        final ImageView p = new ImageView(this);
        p.setScaleType(ImageView.ScaleType.FIT_XY);   // drawable takes the view bounds
        p.setImageDrawable(r.preview);
        card.addView(p, new FrameLayout.LayoutParams(-1, -1));

        final View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0xCC000000}));
        card.addView(scrim, new FrameLayout.LayoutParams(-1, dp(72), Gravity.BOTTOM));

        final FrameLayout play = new FrameLayout(this);
        play.setBackground(oval(0x66000000));
        play.addView(icon(R.drawable.ic_status_card_play, 0xFFFFFFFF, 16), new FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER));
        final FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.TOP | Gravity.START);
        plp.setMargins(dp(8), dp(8), 0, 0);
        card.addView(play, plp);

        if (r.uses > 0) {
            final LinearLayout pill = new LinearLayout(this);
            pill.setOrientation(LinearLayout.HORIZONTAL);
            pill.setGravity(Gravity.CENTER_VERTICAL);
            pill.setPadding(dp(7), dp(5), dp(9), dp(5));
            pill.setBackground(round(0x66000000, 14));
            pill.addView(icon(R.drawable.ic_sound_flame_outline, 0xFFFF7A1A, 13));
            final TextView ut = text(compact(r.uses), 11f, 0xFFFFFFFF, true);
            ut.setPadding(dp(3), 0, 0, 0);
            pill.addView(ut);
            final FrameLayout.LayoutParams ulp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
            ulp.setMargins(0, dp(8), dp(8), 0);
            card.addView(pill, ulp);
        }

        final boolean on = savedKeys.contains(r.key());
        final FrameLayout heart = new FrameLayout(this);
        heart.setBackground(oval(0x66000000));
        heart.addView(icon(on ? R.drawable.ic_heart_filled : R.drawable.ic_heart, on ? 0xFFFF4D6D : 0xFFFFFFFF, 15),
                new FrameLayout.LayoutParams(dp(15), dp(15), Gravity.CENTER));
        heart.setContentDescription(on ? "Saved se hatao" : "Template save karo");
        heart.setOnClickListener(v -> toggleSave(r));
        final FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.TOP | Gravity.END);
        hlp.setMargins(0, dp(r.uses > 0 ? 38 : 8), dp(8), 0);
        card.addView(heart, hlp);

        final LinearLayout foot = new LinearLayout(this);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        final boolean dead = r.blocked && mode == MODE_SAVED;
        final TextView nm = text(dead ? getString(R.string.tpl_dead) : (r.mine ? getString(R.string.tpl_banner_title_mine)
                : "@" + (ne(r.originName) ? r.originName : "user")), 12f, 0xFFFFFFFF, true);
        nm.setSingleLine(true);
        nm.setEllipsize(TextUtils.TruncateAt.END);
        foot.addView(nm, new LinearLayout.LayoutParams(0, -2, 1f));
        final TextView pc = text(String.valueOf(r.preview.photoCount()), 11f, 0xFFFFFFFF, true);
        foot.addView(pc);
        foot.setPadding(dp(10), 0, dp(10), dp(10));
        card.addView(foot, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        p.setAlpha(dead ? 0.45f : 1f);
        card.setOnClickListener(v -> open(r));
        return card;
    }

    // ── actions ──────────────────────────────────────────────────────────────

    private void open(Row r) {
        // honours the owner's reuse switch / removal (TemplateActions.use re-checks it)
        TemplateActions.use(this, r.tplJson, r.stickersJson, r.originUid, r.originId, r.originName, null);
    }

    private void toggleSave(Row r) {
        final String k = r.key();
        if (savedKeys.contains(k)) {
            TemplateActions.unsave(r.originUid, r.originId);
            savedKeys.remove(k);
            saved.remove(k);
            Toast.makeText(this, "Saved se hata diya", Toast.LENGTH_SHORT).show();
        } else if (TemplateActions.save(r.originUid, r.originId, r.originName, r.tplJson, r.stickersJson)) {
            r.savedTs = System.currentTimeMillis();
            savedKeys.add(k);
            saved.put(k, r);
            Toast.makeText(this, "Template save ho gaya", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Ye template save nahi ho paya", Toast.LENGTH_SHORT).show();
        }
        rebuild();
    }

}
