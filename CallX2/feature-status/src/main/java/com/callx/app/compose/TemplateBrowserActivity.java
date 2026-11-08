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
import androidx.annotation.Nullable;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.WindowCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
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
 *    v769: tap a card -> TemplatePreviewActivity (screen 2) -> Use Template (re-checks the owner's reuse switch).
 *    v769: 'New' chip (newest first) + duration badge on every card.
 * Every icon is an existing project drawable (tinted in code); no new artwork.
 *
 * v785: the whole screen is ONE {@link RecyclerView} (3-column grid): header (title / search / chips / banner / section title) = first
 * full-width item, template cards = recycled grid items (a card view is bound, never rebuilt), footer (empty text / categories) =
 * last full-width item. While the first template parse is running the grid shows pulsing skeleton cards instead of a blank area.
 * Cards count the photos / length of ALL scenes of a multi-scene template and show a scene badge.
 */
public class TemplateBrowserActivity extends AppCompatActivity {

    private static final int MAX_SAVED = 60;
    private static final int GRID_COLS = 3, HOME_ROWS = 2;
    private static final int PURPLE = 0xFF6A3DE8, PURPLE_LIGHT = 0xFF9A7BFF;
    private static final int MODE_HOME = 0, MODE_SAVED = 1, MODE_MINE = 2;
    private static final int CHIP_TRENDING = 0, CHIP_POPULAR = 1, CHIP_COLLAGE = 2, CHIP_LOVE = 3, CHIP_NEW = 4;   // v769: 'Fun' became 'New' (newest first)
    private static final long SEARCH_DEBOUNCE_MS = 250L;
    private static final int SORT_DEFAULT = 0, SORT_USED = 1, SORT_NEWEST = 2, SORT_PHOTOS = 3;

    private static final class Row {
        String tplJson, stickersJson;
        String originUid, originId, originName;
        boolean mine, original, fromFeed;   // fromFeed: row came from the server templateFeed (v788)
        long uses = -1;
        int cat = -1;      // v765: TemplateCategory index
        long ts;           // status time (Discover order tie-break)
        long savedTs;      // when it was saved (Saved order)
        boolean blocked;   // owner turned reuse off / removed it
        boolean metaAsked;
        int durMs = -1;    // v769: TemplateSpec.displayMs(), parsed once
        TemplatePreviewDrawable preview;
        String key() { return originUid + "/" + originId; }
        int dur() { if (durMs < 0) durMs = TemplateSpec.parse(tplJson).displayMs(); return durMs; }
        long time() { return ts > 0 ? ts : savedTs; }

        /** perf: ONE JSON parse fills preview + category + duration (was 3 separate parses per template). @return false for a bad template. */
        boolean parseOnce() {
            try {
                final org.json.JSONObject root = new org.json.JSONObject(tplJson);
                preview = TemplatePreviewDrawable.fromRoot(root);
                if (preview == null) return false;
                cat = TemplateCategory.indexOfRoot(root);
                durMs = TemplateSpec.parseRoot(root).displayMs();
                return true;
            } catch (Exception e) {
                return false;
            }
        }
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
    private Row previewRow;
    private final ActivityResultLauncher<Intent> previewLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), res -> onPreviewResult(res.getData()));

    // views
    private LinearLayout myPill, chipRow, headerBox, footerBox, navTemplates, navSaved;
    private RecyclerView rv;
    private GridAdapter adapter;
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

        headerBox = new LinearLayout(this);
        headerBox.setOrientation(LinearLayout.VERTICAL);
        footerBox = new LinearLayout(this);
        footerBox.setOrientation(LinearLayout.VERTICAL);
        footerBox.setPadding(0, 0, 0, dp(24));

        buildHeader(headerBox);
        buildSearch(headerBox);
        buildChips(headerBox);
        buildBanner(headerBox);
        buildSection(headerBox, footerBox);
        buildCategories(footerBox);

        rv = new RecyclerView(this);
        rv.setVerticalScrollBarEnabled(false);
        rv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        final GridLayoutManager glm = new GridLayoutManager(this, GRID_COLS);
        glm.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override public int getSpanSize(int position) {
                final int t = adapter.getItemViewType(position);
                return t == T_HEADER || t == T_FOOTER ? GRID_COLS : 1;
            }
        });
        rv.setLayoutManager(glm);
        adapter = new GridAdapter();
        adapter.setHasStableIds(true);
        rv.setAdapter(adapter);
        rv.getRecycledViewPool().setMaxRecycledViews(T_HEADER, 1);
        rv.getRecycledViewPool().setMaxRecycledViews(T_FOOTER, 1);
        rv.getRecycledViewPool().setMaxRecycledViews(T_CARD, 12);
        rv.setItemAnimator(null);   // cards swap on filter / sort — no fly-in per keystroke
        rv.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override public void getItemOffsets(@NonNull android.graphics.Rect out, @NonNull View v, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
                final int pos = parent.getChildAdapterPosition(v);
                if (pos == RecyclerView.NO_POSITION) return;
                final int t = adapter.getItemViewType(pos);
                if (t == T_HEADER || t == T_FOOTER) return;
                final int col = ((GridLayoutManager.LayoutParams) v.getLayoutParams()).getSpanIndex();
                final float m = dp(16), gap = dp(10);
                final float x = (2f * gap - m) / 3f;     // equal card widths: L+R is the same in every column
                final float l, r2;
                if (col == 0) { l = m; r2 = x; }
                else if (col == 1) { l = gap - x; r2 = m + 2f * x - gap; }
                else { l = 2f * gap - m - 2f * x; r2 = m; }
                out.left = Math.round(l);
                out.right = Math.round(r2);
                out.bottom = Math.round(gap);
            }
        });
        root.addView(rv, new LinearLayout.LayoutParams(-1, 0, 1f));
        root.addView(buildNav(), new LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
        if (!dark) WindowCompat.getInsetsController(getWindow(), root).setAppearanceLightStatusBars(true);

        loadDiscover();
        loadFeed();   // v788: server-side trending/newest on top of the locally cached statuses
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
        myPill.setOnClickListener(v -> startActivity(new Intent(this, MyTemplatesActivity.class)));   // v770: own screen (Created / Saved / Used)
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
                ui.removeCallbacks(rebuildRun);          // perf: wait for a typing pause instead of a full rebuild per keystroke
                ui.postDelayed(rebuildRun, SEARCH_DEBOUNCE_MS);
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
        final int[] labels = {R.string.tpl_chip_trending, R.string.tpl_chip_popular, R.string.tpl_chip_collage, R.string.tpl_chip_love, R.string.tpl_chip_new};
        final int[] icons = {R.drawable.ic_sound_flame_outline, R.drawable.ic_star_outline, R.drawable.ic_layout_grid_2x2, R.drawable.ic_heart_filled, R.drawable.ic_clock_small};
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

    // perf: banner drawables are built once per (slot, row) — no JSON re-parse every 4 s; each ImageView keeps its own drawable
    private final Map<Row, TemplatePreviewDrawable> bannerPvC = new java.util.HashMap<>(),
            bannerPvL = new java.util.HashMap<>(), bannerPvR = new java.util.HashMap<>();
    private boolean started;

    private static void setBannerImage(ImageView iv, Map<Row, TemplatePreviewDrawable> cache, Row row) {
        if (cache.size() > 6) cache.clear();
        TemplatePreviewDrawable d = cache.get(row);
        if (d == null) {
            d = TemplatePreviewDrawable.fromJson(row.tplJson);
            if (d == null) { iv.setImageDrawable(null); return; }
            cache.put(row, d);
        }
        if (iv.getDrawable() != d) iv.setImageDrawable(d);
    }

    private void bindBanner() {
        if (bannerRows.isEmpty()) return;
        final int n = bannerRows.size();
        bannerIdx = ((bannerIdx % n) + n) % n;
        final Row c = bannerRows.get(bannerIdx);
        final Row l = bannerRows.get((bannerIdx + n - 1) % n);
        final Row r = bannerRows.get((bannerIdx + 1) % n);
        setBannerImage(bannerC, bannerPvC, c);
        setBannerImage(bannerL, bannerPvL, l);
        setBannerImage(bannerR, bannerPvR, r);
        bannerTitle.setText(c.mine ? getString(R.string.tpl_banner_title_mine)
                : getString(R.string.tpl_banner_title_other, ne(c.originName) ? c.originName : "user"));
        bannerSub.setText(getString(R.string.tpl_banner_sub, c.preview.photoCount()));
        // dots: views are created once per count, afterwards only restyled
        if (dots.getChildCount() != n) {
            dots.removeAllViews();
            for (int i = 0; i < n; i++) {
                final View d = new View(this);
                final int to = i;
                d.setOnClickListener(v -> { bannerIdx = to; bindBanner(); });
                final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(12), dp(4));
                lp.setMargins(dp(3), 0, dp(3), 0);
                dots.addView(d, lp);
            }
            dotsIdx = -1;
        }
        if (dotsIdx != bannerIdx) {
            for (int i = 0; i < n; i++) {
                final View d = dots.getChildAt(i);
                final boolean on = i == bannerIdx;
                d.setBackground(round(on ? 0xFFFFFFFF : 0x66FFFFFF, 3));
                final LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) d.getLayoutParams();
                lp.width = dp(on ? 22 : 12);
                d.setLayoutParams(lp);
            }
            dotsIdx = bannerIdx;
        }
        ui.removeCallbacks(bannerRun);
        if (n > 1 && started) ui.postDelayed(bannerRun, 4000);
    }

    private int dotsIdx = -1;

    @Override protected void onStart() {
        super.onStart();
        started = true;
        if (bannerRows.size() > 1) { ui.removeCallbacks(bannerRun); ui.postDelayed(bannerRun, 4000); }
    }

    @Override protected void onStop() {
        started = false;
        ui.removeCallbacks(bannerRun);   // no banner ticks while the screen is hidden
        super.onStop();
    }

    private void nextBanner() {
        if (isFinishing() || isDestroyed() || bannerRows.size() < 2) return;
        bannerIdx++;
        bindBanner();
    }

    // ── section (grid) / categories ──────────────────────────────────────────

    private void buildSection(LinearLayout content, LinearLayout footer) {
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

        empty = text("", 14f, subColor, false);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(32), dp(40), dp(32), dp(40));
        empty.setLineSpacing(0, 1.15f);
        empty.setVisibility(View.GONE);
        footer.addView(empty, new LinearLayout.LayoutParams(-1, -2));
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
        // v776: Templates | Saved | (+) | Analytics | Notifications — Analytics / Notifications are about MY templates (pick one if several)
        final LinearLayout navAnalytics = navItem(R.drawable.ic_tpl_analytics, R.string.tpl_nav_analytics);
        final LinearLayout navNotif = navItem(R.drawable.ic_notifications, R.string.tpl_nav_notifications);
        navTemplates.setOnClickListener(v -> setMode(MODE_HOME));
        navSaved.setOnClickListener(v -> setMode(mode == MODE_SAVED ? MODE_HOME : MODE_SAVED));
        navAnalytics.setOnClickListener(v -> OwnTemplatePicker.show(this, R.string.tpl_pick_analytics,
                (id, json) -> startActivity(new Intent(this, TemplateAnalyticsActivity.class)
                        .putExtra(TemplateAnalyticsActivity.EXTRA_STATUS_ID, id)
                        .putExtra(TemplateAnalyticsActivity.EXTRA_TPL_JSON, json))));
        navNotif.setOnClickListener(v -> OwnTemplatePicker.show(this, R.string.tpl_pick_users,
                (id, json) -> startActivity(new Intent(this, TemplateUsesActivity.class)
                        .putExtra(TemplateUsesActivity.EXTRA_STATUS_ID, id))));

        final FrameLayout fabCell = new FrameLayout(this);
        fabCell.setClipChildren(false);
        final FrameLayout fab = new FrameLayout(this);
        final GradientDrawable fg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{PURPLE_LIGHT, PURPLE});
        fg.setShape(GradientDrawable.OVAL);
        fab.setBackground(fg);
        fab.setElevation(dp(8));
        fab.addView(icon(R.drawable.ic_add, 0xFFFFFFFF, 30), new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER));
        fab.setContentDescription(getString(R.string.tpl_nav_create));
        fab.setOnClickListener(v -> startActivity(new Intent(this, CreateTemplateActivity.class)));   // v773: Create Template screen
        final FrameLayout.LayoutParams fl = new FrameLayout.LayoutParams(dp(58), dp(58), Gravity.CENTER);
        fl.topMargin = -dp(16);
        fabCell.addView(fab, fl);

        bar.addView(navTemplates, new LinearLayout.LayoutParams(0, -2, 1f));
        bar.addView(navSaved, new LinearLayout.LayoutParams(0, -2, 1f));
        bar.addView(fabCell, new LinearLayout.LayoutParams(0, dp(58), 1f));
        bar.addView(navAnalytics, new LinearLayout.LayoutParams(0, -2, 1f));
        bar.addView(navNotif, new LinearLayout.LayoutParams(0, -2, 1f));
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

    /**
     * perf: only cheap field copies + dedupe run on the main thread; the JSON parse (preview / category / duration) runs on
     * {@link com.callx.app.utils.StatusBg#IO}. The grid shows no "empty" message until this first pass is applied.
     */
    private void loadDiscover() {
        final String me = FirebaseUtils.getCurrentUid();
        final StatusCacheManager cm = StatusCacheManager.getInstance(getApplicationContext());
        final Map<String, List<StatusItem>> all = new LinkedHashMap<>(cm.getAllStatuses());
        if (ne(me) && !all.containsKey(me)) all.put(me, cm.getStatuses(me));

        final Map<String, Row> cand = new LinkedHashMap<>();
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
                final Row old = cand.get(r.key());
                if (old != null && !(!old.original && r.original)) continue;   // a repost of a kept original costs nothing
                cand.put(r.key(), r);                                          // the original wins over a repost of it
            }
        }
        if (cand.isEmpty()) { dropLocalDiscover(); discoverLoading = false; return; }
        discoverLoading = true;
        final int gen = ++discoverGen;
        com.callx.app.utils.StatusBg.IO.execute(() -> {
            final List<Row> ok = new ArrayList<>(cand.size());
            for (Row r : cand.values()) if (r.parseOnce()) ok.add(r);
            ui.post(() -> {
                if (isFinishing() || isDestroyed() || gen != discoverGen) return;
                dropLocalDiscover();
                for (Row r : ok) discover.put(r.key(), r);   // a cached status row replaces a feed row of the same template
                discoverLoading = false;
                rebuild();
            });
        });
    }

    /** Clears the rows built from cached statuses but keeps what the server feed already delivered. */
    private void dropLocalDiscover() {
        for (java.util.Iterator<Map.Entry<String, Row>> it = discover.entrySet().iterator(); it.hasNext(); ) {
            if (!it.next().getValue().fromFeed) it.remove();
        }
    }

    private volatile boolean discoverLoading, savedLoading;
    private int discoverGen;

    private static final int FEED_TRENDING = 60, FEED_NEWEST = 40;

    /**
     * v788 (item 22): server-side Discover. {@code templateFeed} (filled by Cloud Functions from every PUBLIC original template,
     * not swept with the 24h status) gives trending (by uses) + newest rows, so Discover no longer depends on the statuses this
     * phone happened to cache. Rows merge into {@link #discover}; a locally cached (fresher) row of the same template wins.
     */
    private void loadFeed() {
        final String me = FirebaseUtils.getCurrentUid();
        if (!ne(me)) return;
        final int[] left = {2};
        final Map<String, Row> got = new LinkedHashMap<>();
        final Runnable done = () -> {
            if (--left[0] > 0) return;
            if (got.isEmpty()) return;
            final int gen = discoverGen;
            com.callx.app.utils.StatusBg.IO.execute(() -> {
                final List<Row> ok = new ArrayList<>(got.size());
                for (Row r : got.values()) if (r.parseOnce()) ok.add(r);
                ui.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    boolean changed = false;
                    for (Row r : ok) {
                        if (discover.containsKey(r.key())) continue;   // cached status row (fresher) wins
                        discover.put(r.key(), r);
                        changed = true;
                    }
                    if (changed || gen != discoverGen) scheduleRebuild();
                });
            });
        };
        final ValueEventListener l = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                for (DataSnapshot c : s.getChildren()) {
                    if (Boolean.TRUE.equals(c.child("hidden").getValue(Boolean.class))) continue;   // owner turned reuse off / removed it
                    final String json = c.child("templateJson").getValue(String.class);
                    final String ou = c.child("ownerUid").getValue(String.class);
                    final String oi = c.child("statusId").getValue(String.class);
                    if (!ne(json) || !ne(ou) || !ne(oi)) continue;
                    final Row r = new Row();
                    r.tplJson = json;
                    r.stickersJson = c.child("stickersJson").getValue(String.class);
                    r.originUid = ou;
                    r.originId = oi;
                    r.originName = c.child("ownerName").getValue(String.class);
                    r.original = true;
                    r.fromFeed = true;
                    r.mine = me.equals(ou);
                    final Long ts = c.child("ts").getValue(Long.class);
                    r.ts = ts != null ? ts : 0L;
                    final Long uses = c.child("uses").getValue(Long.class);
                    if (uses != null) r.uses = uses;   // the feed row already carries the counter → no extra read for this card
                    got.putIfAbsent(r.key(), r);
                }
                done.run();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { done.run(); /* offline / rules: Discover just stays local */ }
        };
        try {
            final com.google.firebase.database.DatabaseReference feed = FirebaseUtils.db().getReference("templateFeed");
            feed.orderByChild("score").limitToLast(FEED_TRENDING).addListenerForSingleValueEvent(l);
            feed.orderByChild("ts").limitToLast(FEED_NEWEST).addListenerForSingleValueEvent(l);
        } catch (Exception e) {
            left[0] = 0;
        }
    }

    /** savedTemplates/{me}: snapshots (they outlive the 24h status). Hearts everywhere follow {@link #savedKeys}. */
    private void loadSaved() {
        final String me = FirebaseUtils.getCurrentUid();
        if (!ne(me)) return;
        savedLoading = true;   // v785: the Saved tab shows skeleton cards until this read answers
        try {
            FirebaseUtils.db().getReference("savedTemplates").child(me).orderByChild("ts").limitToLast(MAX_SAVED)
                    .addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override public void onDataChange(@NonNull DataSnapshot s) {
                            if (isFinishing() || isDestroyed()) return;
                            final List<Row> cand = new ArrayList<>();
                            for (DataSnapshot c : s.getChildren()) {
                                final String json = c.child("templateJson").getValue(String.class);
                                final String ou = c.child("ownerUid").getValue(String.class);
                                final String oi = c.child("statusId").getValue(String.class);
                                if (!ne(json) || !ne(ou) || !ne(oi)) continue;
                                if (saved.containsKey(ou + "/" + oi)) continue;       // toggled while this read was in flight
                                final Row r = new Row();
                                r.tplJson = json;
                                r.stickersJson = c.child("stickersJson").getValue(String.class);
                                r.originUid = ou;
                                r.originId = oi;
                                r.originName = c.child("ownerName").getValue(String.class);
                                r.mine = ou.equals(me);
                                final Long ts = c.child("ts").getValue(Long.class);
                                r.savedTs = ts != null ? ts : 0L;
                                cand.add(r);
                            }
                            if (cand.isEmpty()) { savedLoading = false; scheduleRebuild(); return; }
                            com.callx.app.utils.StatusBg.IO.execute(() -> {
                                final List<Row> ok = new ArrayList<>(cand.size());
                                for (Row r : cand) if (r.parseOnce()) ok.add(r);
                                ui.post(() -> {
                                    savedLoading = false;
                                    if (isFinishing() || isDestroyed()) return;
                                    for (Row r : ok) {
                                        final String key = r.key();
                                        if (saved.containsKey(key)) continue;     // toggled while parsing
                                        saved.put(key, r);
                                        savedKeys.add(key);
                                    }
                                    scheduleRebuild();
                                });
                            });
                        }
                        @Override public void onCancelled(@NonNull DatabaseError e) { savedLoading = false; scheduleRebuild(); /* Saved just stays empty */ }
                    });
        } catch (Exception ignored) { savedLoading = false; }
    }

    /** "N used" + the owner's reuse switch, once per card. */
    private void fetchMeta(final Row r) {
        if (r.metaAsked) return;
        r.metaAsked = true;
        final boolean followLatest = !r.mine && saved.containsKey(r.key());   // v788: my saved snapshot of someone else's template
        final int[] pending = {(r.mine ? 1 : 2) + (followLatest ? 1 : 0)};
        if (followLatest) {
            TemplateActions.fetchLatest(r.originUid, r.originId, r.tplJson, true, (json, stickers) -> {
                if (json == null || json.equals(r.tplJson)) { if (--pending[0] <= 0) scheduleRebuild(); return; }
                com.callx.app.utils.StatusBg.IO.execute(() -> {
                    final String old = r.tplJson, oldSt = r.stickersJson;
                    r.tplJson = json;
                    if (stickers != null) r.stickersJson = stickers;
                    if (!r.parseOnce()) { r.tplJson = old; r.stickersJson = oldSt; r.parseOnce(); }   // bad newest version: keep the old one
                    ui.post(() -> { if (--pending[0] <= 0) scheduleRebuild(); });
                });
            });
        }
        com.callx.app.utils.StatusTemplateUses.fetchCount(r.originUid, r.originId, n -> {   // v767: counter, not a row download
            r.uses = n;
            if (--pending[0] <= 0) scheduleRebuild();
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
        if (isFinishing() || isDestroyed() || rv == null) return;
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
                : (mode == MODE_SAVED ? SORT_NEWEST : (mode == MODE_HOME && chip == CHIP_NEW) || mode == MODE_MINE ? SORT_NEWEST : SORT_USED);
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
                : chip == CHIP_LOVE ? R.string.tpl_section_love : chip == CHIP_NEW ? R.string.tpl_section_fun
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

        final boolean loading = shown.isEmpty() && (mode == MODE_SAVED ? savedLoading : discoverLoading);
        submitGrid(limited ? Math.min(cap, shown.size()) : shown.size(), loading);

        final int emptyRes = !query.isEmpty() ? R.string.tpl_empty_search
                : (mode == MODE_HOME && catFilter >= 0) ? R.string.tpl_empty_cat
                : mode == MODE_SAVED ? R.string.tpl_empty_saved : mode == MODE_MINE ? R.string.tpl_empty_mine
                : chip == CHIP_LOVE ? R.string.tpl_empty_love : R.string.tpl_empty_home;
        empty.setText(emptyRes);
        empty.setVisibility(shown.isEmpty() && !loading ? View.VISIBLE : View.GONE);
        for (Row r : new ArrayList<>(shown)) fetchMeta(r);
    }

    private GradientDrawable purpleStroke() {
        final GradientDrawable g = round(dark ? 0x336A3DE8 : 0x1A6A3DE8, 26);
        g.setStroke(dp(1), PURPLE);
        return g;
    }

    // ── v785: grid (one RecyclerView: header · cards · footer) ───────────────

    private static final int T_HEADER = 0, T_CARD = 1, T_SKEL = 2, T_FOOTER = 3;

    private static final class Item {
        final int type;
        final Row row;
        final String sig;
        final long id;
        Item(int type, Row row, String sig, long id) { this.type = type; this.row = row; this.sig = sig; this.id = id; }
    }

    private final List<Item> items = new ArrayList<>();
    private final Map<String, Long> idMap = new java.util.HashMap<>();   // stable, collision-free ids (a hash could collide)
    private long nextId = 1000L;

    private long idFor(String key) {
        Long id = idMap.get(key);
        if (id == null) { id = nextId++; idMap.put(key, id); }
        return id;
    }

    /** What a card shows (a change here = that one card is re-bound, nothing else). */
    private String cardSig(Row r) {
        final boolean dead = r.blocked && mode == MODE_SAVED;
        return r.uses + "|" + savedKeys.contains(r.key()) + "|" + dead + "|" + r.mine + "|" + r.originName;
    }

    /** Header + {@code count} cards (or skeleton cards while loading) + footer; only the differences touch the RecyclerView. */
    private void submitGrid(int count, boolean loading) {
        final List<Item> next = new ArrayList<>(count + 3);
        next.add(new Item(T_HEADER, null, "", 1L));
        if (loading) {
            for (int i = 0; i < GRID_COLS * HOME_ROWS; i++) next.add(new Item(T_SKEL, null, "", 100L + i));
        } else {
            for (int i = 0; i < count; i++) {
                final Row r = shown.get(i);
                next.add(new Item(T_CARD, r, cardSig(r), idFor(r.key())));
            }
        }
        next.add(new Item(T_FOOTER, null, "", 2L));
        final List<Item> old = new ArrayList<>(items);
        final DiffUtil.DiffResult dr = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return old.size(); }
            @Override public int getNewListSize() { return next.size(); }
            @Override public boolean areItemsTheSame(int o, int n) { return old.get(o).id == next.get(n).id && old.get(o).type == next.get(n).type; }
            @Override public boolean areContentsTheSame(int o, int n) {
                final Item a = old.get(o), b = next.get(n);
                return a.row == b.row && a.sig.equals(b.sig);   // same Row object + same visible state → no re-bind
            }
        }, false);
        items.clear();
        items.addAll(next);
        dr.dispatchUpdatesTo(adapter);
    }

    private int cellH() {
        final int gap = dp(10);
        final int cellW = (getResources().getDisplayMetrics().widthPixels - dp(32) - gap * (GRID_COLS - 1)) / GRID_COLS;
        return Math.round(cellW * 1.45f);
    }

    private static final class StaticHolder extends RecyclerView.ViewHolder {
        StaticHolder(View v) { super(v); }
    }

    private final class SkelHolder extends RecyclerView.ViewHolder {
        android.animation.ObjectAnimator pulse;
        SkelHolder(View v) { super(v); }
        void start() {
            if (pulse != null) return;
            pulse = android.animation.ObjectAnimator.ofFloat(itemView, View.ALPHA, 0.5f, 1f);
            pulse.setDuration(850L);
            pulse.setRepeatMode(android.animation.ValueAnimator.REVERSE);
            pulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            pulse.start();
        }
        void stop() {
            if (pulse != null) { pulse.cancel(); pulse = null; }
            itemView.setAlpha(1f);
        }
    }

    /** A card is built ONCE per holder; {@link #bind} only sets text / icons / drawable. */
    private final class CardHolder extends RecyclerView.ViewHolder {
        final ImageView p, heartIc;
        final LinearLayout pill, sceneChip;
        final TextView usesTx, nm, pc, sceneTx;
        final FrameLayout heart;
        Row row;

        CardHolder(FrameLayout card) {
            super(card);
            card.setBackground(round(surface, 18));
            clipRound(card, 18);
            p = new ImageView(TemplateBrowserActivity.this);
            p.setScaleType(ImageView.ScaleType.FIT_XY);   // drawable takes the view bounds
            card.addView(p, new FrameLayout.LayoutParams(-1, -1));

            final View scrim = new View(TemplateBrowserActivity.this);
            scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0xCC000000}));
            card.addView(scrim, new FrameLayout.LayoutParams(-1, dp(72), Gravity.BOTTOM));

            final FrameLayout play = new FrameLayout(TemplateBrowserActivity.this);
            play.setBackground(oval(0x66000000));
            play.addView(icon(R.drawable.ic_status_card_play, 0xFFFFFFFF, 16), new FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER));
            final FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.TOP | Gravity.START);
            plp.setMargins(dp(8), dp(8), 0, 0);
            card.addView(play, plp);

            // v785: multi-scene badge under the play mark
            sceneChip = new LinearLayout(TemplateBrowserActivity.this);
            sceneChip.setOrientation(LinearLayout.HORIZONTAL);
            sceneChip.setGravity(Gravity.CENTER_VERTICAL);
            sceneChip.setPadding(dp(6), dp(4), dp(8), dp(4));
            sceneChip.setBackground(round(0x66000000, 12));
            sceneChip.addView(icon(R.drawable.ic_layout_grid_2x2, 0xFFFFFFFF, 12));
            sceneTx = text("", 10f, 0xFFFFFFFF, true);
            sceneTx.setPadding(dp(3), 0, 0, 0);
            sceneChip.addView(sceneTx);
            sceneChip.setVisibility(View.GONE);
            final FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
            slp.setMargins(dp(8), dp(44), 0, 0);
            card.addView(sceneChip, slp);

            // top-right column: uses pill (only when > 0) + heart
            final LinearLayout col = new LinearLayout(TemplateBrowserActivity.this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.END);
            pill = new LinearLayout(TemplateBrowserActivity.this);
            pill.setOrientation(LinearLayout.HORIZONTAL);
            pill.setGravity(Gravity.CENTER_VERTICAL);
            pill.setPadding(dp(7), dp(5), dp(9), dp(5));
            pill.setBackground(round(0x66000000, 14));
            pill.addView(icon(R.drawable.ic_sound_flame_outline, 0xFFFF7A1A, 13));
            usesTx = text("", 11f, 0xFFFFFFFF, true);
            usesTx.setPadding(dp(3), 0, 0, 0);
            pill.addView(usesTx);
            col.addView(pill, new LinearLayout.LayoutParams(-2, -2));
            heart = new FrameLayout(TemplateBrowserActivity.this);
            heart.setBackground(oval(0x66000000));
            heartIc = icon(R.drawable.ic_heart, 0xFFFFFFFF, 15);
            heart.addView(heartIc, new FrameLayout.LayoutParams(dp(15), dp(15), Gravity.CENTER));
            heart.setOnClickListener(v -> { if (row != null) toggleSave(row); });
            final LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(dp(30), dp(30));
            hlp.topMargin = dp(8);
            col.addView(heart, hlp);
            final FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
            clp.setMargins(0, dp(8), dp(8), 0);
            card.addView(col, clp);

            final LinearLayout foot = new LinearLayout(TemplateBrowserActivity.this);
            foot.setOrientation(LinearLayout.HORIZONTAL);
            foot.setGravity(Gravity.CENTER_VERTICAL);
            nm = text("", 12f, 0xFFFFFFFF, true);
            nm.setSingleLine(true);
            nm.setEllipsize(TextUtils.TruncateAt.END);
            foot.addView(nm, new LinearLayout.LayoutParams(0, -2, 1f));
            pc = text("", 11f, 0xFFFFFFFF, true);
            foot.addView(pc);
            foot.setPadding(dp(10), 0, dp(10), dp(10));
            card.addView(foot, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
            card.setOnClickListener(v -> { if (row != null) open(row); });
        }

        void bind(Row r) {
            row = r;
            final boolean dead = r.blocked && mode == MODE_SAVED;
            if (p.getDrawable() != r.preview) p.setImageDrawable(r.preview);
            p.setAlpha(dead ? 0.45f : 1f);

            final boolean hasUses = r.uses > 0;
            pill.setVisibility(hasUses ? View.VISIBLE : View.GONE);
            if (hasUses) usesTx.setText(compact(r.uses));
            ((LinearLayout.LayoutParams) heart.getLayoutParams()).topMargin = hasUses ? dp(8) : 0;
            heart.requestLayout();

            final boolean on = savedKeys.contains(r.key());
            heartIc.setImageResource(on ? R.drawable.ic_heart_filled : R.drawable.ic_heart);
            ImageViewCompat.setImageTintList(heartIc, ColorStateList.valueOf(on ? 0xFFFF4D6D : 0xFFFFFFFF));
            heart.setContentDescription(on ? getString(R.string.tpl_prev_unsave) : getString(R.string.tpl_prev_save));

            nm.setText(dead ? getString(R.string.tpl_dead) : (r.mine ? getString(R.string.tpl_banner_title_mine)
                    : "@" + (ne(r.originName) ? r.originName : "user")));
            final String durTxt = TemplateSpec.formatDuration(r.dur());   // v769: duration badge ("" when the template has none)
            final int photos = r.preview.photoCount();                    // v785: ALL scenes
            pc.setText(durTxt.isEmpty() ? String.valueOf(photos) : durTxt + " \u00B7 " + photos);

            final int scenes = r.preview.sceneCount();
            sceneChip.setVisibility(scenes > 1 ? View.VISIBLE : View.GONE);
            if (scenes > 1) {
                sceneTx.setText(String.valueOf(scenes));
                sceneChip.setContentDescription(getString(R.string.tpl_card_scenes, scenes));
            }
        }
    }

    private View detached(View v) {
        if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
        v.setLayoutParams(new RecyclerView.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT));
        return v;
    }

    private View newSkeleton() {
        final FrameLayout f = new FrameLayout(this);
        f.setBackground(round(surface, 18));
        clipRound(f, 18);
        final int bar = dark ? 0x22FFFFFF : 0x14000000;
        final View b1 = new View(this);
        b1.setBackground(round(bar, 6));
        final FrameLayout.LayoutParams l1 = new FrameLayout.LayoutParams(dp(54), dp(10), Gravity.BOTTOM | Gravity.START);
        l1.setMargins(dp(10), 0, 0, dp(28));
        f.addView(b1, l1);
        final View b2 = new View(this);
        b2.setBackground(round(bar, 6));
        final FrameLayout.LayoutParams l2 = new FrameLayout.LayoutParams(dp(34), dp(8), Gravity.BOTTOM | Gravity.START);
        l2.setMargins(dp(10), 0, 0, dp(12));
        f.addView(b2, l2);
        final View dot = new View(this);
        dot.setBackground(oval(bar));
        final FrameLayout.LayoutParams l3 = new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.TOP | Gravity.START);
        l3.setMargins(dp(8), dp(8), 0, 0);
        f.addView(dot, l3);
        f.setLayoutParams(new RecyclerView.LayoutParams(-1, cellH()));
        return f;
    }

    private final class GridAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override public int getItemCount() { return items.size(); }
        @Override public int getItemViewType(int pos) { return items.get(pos).type; }
        @Override public long getItemId(int pos) { return items.get(pos).id; }

        @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            if (type == T_HEADER) return new StaticHolder(detached(headerBox));
            if (type == T_FOOTER) return new StaticHolder(detached(footerBox));
            if (type == T_SKEL) return new SkelHolder(newSkeleton());
            final FrameLayout card = new FrameLayout(TemplateBrowserActivity.this);
            card.setLayoutParams(new RecyclerView.LayoutParams(-1, cellH()));
            return new CardHolder(card);
        }

        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
            if (h instanceof CardHolder) ((CardHolder) h).bind(items.get(pos).row);
        }

        @Override public void onViewAttachedToWindow(@NonNull RecyclerView.ViewHolder h) {
            if (h instanceof SkelHolder) ((SkelHolder) h).start();
        }

        @Override public void onViewDetachedFromWindow(@NonNull RecyclerView.ViewHolder h) {
            if (h instanceof SkelHolder) ((SkelHolder) h).stop();
        }
    }

    // ── actions ──────────────────────────────────────────────────────────────

    /** v769: card / banner tap -> Preview screen (Use Template there re-checks the owner's reuse switch / removal). */
    private void open(Row r) {
        previewRow = r;
        final Intent i = new Intent(this, TemplatePreviewActivity.class);
        i.putExtra(TemplatePreviewActivity.EXTRA_TPL_JSON, r.tplJson);
        TemplatePayloadStore.put(i, TemplatePreviewActivity.EXTRA_STICKERS_JSON, r.stickersJson);
        i.putExtra(TemplatePreviewActivity.EXTRA_OWNER_UID, r.originUid);
        i.putExtra(TemplatePreviewActivity.EXTRA_STATUS_ID, r.originId);
        i.putExtra(TemplatePreviewActivity.EXTRA_OWNER_NAME, r.originName != null ? r.originName : "");
        i.putExtra(TemplatePreviewActivity.EXTRA_USES, r.uses);
        i.putExtra(TemplatePreviewActivity.EXTRA_SAVED, savedKeys.contains(r.key()));
        previewLauncher.launch(i);
    }

    /** The Preview's bookmark may have changed the saved state -> keep hearts / Saved tab in sync. */
    private void onPreviewResult(@Nullable Intent data) {
        final Row r = previewRow;
        previewRow = null;
        if (r == null || data == null || !data.hasExtra(TemplatePreviewActivity.RESULT_SAVED)) return;
        final boolean nowSaved = data.getBooleanExtra(TemplatePreviewActivity.RESULT_SAVED, false);
        final String k = r.key();
        if (nowSaved) {
            r.savedTs = System.currentTimeMillis();
            savedKeys.add(k);
            saved.put(k, r);
        } else {
            savedKeys.remove(k);
            saved.remove(k);
        }
        rebuild();
    }

    private void toggleSave(Row r) {
        final String k = r.key();
        if (savedKeys.contains(k)) {
            TemplateActions.unsave(r.originUid, r.originId);
            savedKeys.remove(k);
            saved.remove(k);
            Toast.makeText(this, getString(R.string.tpl_act_unsaved_toast), Toast.LENGTH_SHORT).show();
        } else if (TemplateActions.save(r.originUid, r.originId, r.originName, r.tplJson, r.stickersJson)) {
            r.savedTs = System.currentTimeMillis();
            savedKeys.add(k);
            saved.put(k, r);
            Toast.makeText(this, getString(R.string.tpl_act_saved_toast), Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, getString(R.string.tpl_act_save_failed), Toast.LENGTH_SHORT).show();
        }
        rebuild();
    }

}
