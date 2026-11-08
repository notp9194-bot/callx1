package com.callx.app.compose;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.WindowCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.core.widget.NestedScrollView;

import com.callx.app.cache.StatusCacheManager;
import com.callx.app.models.StatusItem;
import com.callx.app.status.R;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.StatusTemplateUses;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * v770 — Screen 6 "My Templates": Created / Saved / Used.
 *
 *  - Created: your own templates (live ones from your status tray + your saved snapshots). Each row has an analytics icon
 *    (who used it) and a ⋮ menu (the owner sheet: who used, reuse on/off, link, remove).
 *  - Saved:   savedTemplates/{me}  (⋮: use, share, copy link, remove from saved).
 *  - Used:    userTemplateUsed/{me} (written when you post a status made from a template; ⋮: use again, share, remove).
 *  - "Create New Template" button, premium iOS look (white surface, purple accent, rounded cards), existing drawables only.
 *
 * Created > ⋮ > "Template edit karo" opens the Template Editor (v772). Drafts tab (v774): local Create Template drafts ({@link TemplateDrafts}) — tap / ⋮ > edit reopens the Create screen, ⋮ > delete.
 * Tapping a row opens the Preview screen (v769).
 */
public class MyTemplatesActivity extends AppCompatActivity {

    private static final int PURPLE = 0xFF6A3DE8, PURPLE_LIGHT = 0xFF9A7BFF;
    private static final int TAB_CREATED = 0, TAB_SAVED = 1, TAB_DRAFTS = 2, TAB_USED = 3;
    private static final int MAX_ROWS = 60;

    private static final class Row {
        String ownerUid, statusId, ownerName, json, stickers;
        long ts;
        long uses = -1;
        boolean usesAsked;
        boolean draft;   // v774: local Create Template draft (statusId = draft id)
        TemplatePreviewDrawable preview;
        String key() { return ownerUid + "/" + statusId; }
    }

    private final Map<String, Row> created = new LinkedHashMap<>();
    private final Map<String, Row> saved = new LinkedHashMap<>();
    private final Map<String, Row> used = new LinkedHashMap<>();
    private final Map<String, Row> drafts = new LinkedHashMap<>();

    private boolean dark;
    private int bg, surface, textColor, subColor;
    private String me;
    private int tab = TAB_CREATED;

    private final List<TextView> tabViews = new ArrayList<>();
    private LinearLayout listBox;
    private TextView empty;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable renderRun = this::render;

    private String editingId;   // declared before the launchers that use it (forward-reference fix)

    private final ActivityResultLauncher<Intent> previewLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), res -> {
                if (res.getData() != null && res.getData().hasExtra(TemplatePreviewActivity.RESULT_SAVED)) loadSaved();
            });

    /** v772: Template Editor result — the saved json replaces the row's (the tray cache is older than the edit). */
    private final ActivityResultLauncher<Intent> editLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), res -> {
                final Intent d = res.getData();
                final String nj = d != null ? d.getStringExtra(TemplateEditorActivity.RESULT_JSON) : null;
                final String sid = editingId;
                editingId = null;
                if (!ne(nj) || !ne(sid)) return;
                final Row r = created.get(me + "/" + sid);
                if (r == null) return;
                r.json = nj;
                r.preview = TemplatePreviewDrawable.fromJson(nj);
                render();
            });

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        me = FirebaseUtils.getCurrentUid();
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
        buildTabs(root);

        final NestedScrollView scroll = new NestedScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        final LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(4), dp(16), dp(16));
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        content.addView(listBox, new LinearLayout.LayoutParams(-1, -2));
        empty = text("", 14f, subColor, false);
        empty.setGravity(Gravity.CENTER);
        empty.setLineSpacing(0, 1.15f);
        empty.setPadding(dp(32), dp(60), dp(32), dp(40));
        empty.setVisibility(View.GONE);
        content.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        scroll.addView(content, new ViewGroup.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        root.addView(buildCreateBar(), new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
        if (!dark) WindowCompat.getInsetsController(getWindow(), root).setAppearanceLightStatusBars(true);

        if (me == null || me.isEmpty()) { finish(); return; }
        loadCreatedFromTray();
        loadSaved();
        loadUsed();
        loadDrafts();
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        if (me != null && !me.isEmpty() && listBox != null) { loadDrafts(); render(); }   // v774: back from Create Template
    }

    @Override protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
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

    private static String compact(long n) {
        if (n >= 1_000_000) return trimZero(n / 100_000 / 10.0) + "M";
        if (n >= 1_000) return trimZero(n / 100 / 10.0) + "K";
        return String.valueOf(n);
    }

    private static String trimZero(double d) {
        final String s = String.format(Locale.US, "%.1f", d);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    // ── chrome ───────────────────────────────────────────────────────────────

    private void buildTopBar(LinearLayout root) {
        final FrameLayout bar = new FrameLayout(this);
        final FrameLayout back = new FrameLayout(this);
        back.setBackground(round(surface, 21));
        back.addView(icon(R.drawable.ic_arrow_back, textColor, 22), new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        back.setOnClickListener(v -> finish());
        final FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(dp(42), dp(42), Gravity.START | Gravity.CENTER_VERTICAL);
        blp.setMargins(dp(14), 0, 0, 0);
        bar.addView(back, blp);
        final TextView t = text(getString(R.string.mt_title), 19f, textColor, true);
        bar.addView(t, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        root.addView(bar, new LinearLayout.LayoutParams(-1, dp(58)));
    }

    private void buildTabs(LinearLayout root) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(16), dp(4), dp(16), dp(12));
        final int[] labels = {R.string.mt_tab_created, R.string.mt_tab_saved, R.string.mt_tab_drafts, R.string.mt_tab_used};
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            final TextView t = text(getString(labels[i]), 14f, textColor, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(6), dp(10), dp(6), dp(10));   // v774: 4 tabs
            t.setOnClickListener(v -> { tab = idx; render(); });
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            lp.setMargins(0, 0, i == labels.length - 1 ? 0 : dp(8), 0);
            row.addView(t, lp);
            tabViews.add(t);
        }
        root.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private View buildCreateBar() {
        final LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setBackgroundColor(bg);
        final View line = new View(this);
        line.setBackgroundColor(dark ? 0x22FFFFFF : 0x14000000);
        wrap.addView(line, new LinearLayout.LayoutParams(-1, 1));
        final LinearLayout cta = new LinearLayout(this);
        cta.setOrientation(LinearLayout.HORIZONTAL);
        cta.setGravity(Gravity.CENTER);
        final GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{PURPLE_LIGHT, PURPLE});
        g.setCornerRadius(dp(27));
        cta.setBackground(g);
        cta.addView(icon(R.drawable.ic_add, 0xFFFFFFFF, 22));
        final TextView t = text(getString(R.string.mt_create_new), 16f, 0xFFFFFFFF, true);
        t.setPadding(dp(8), 0, 0, 0);
        cta.addView(t);
        cta.setOnClickListener(v -> startActivity(new Intent(this, CreateTemplateActivity.class)));   // v773: Create Template screen
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(54));
        lp.setMargins(dp(16), dp(12), dp(16), dp(12));
        wrap.addView(cta, lp);
        return wrap;
    }

    // ── data ─────────────────────────────────────────────────────────────────

    private Row makeRow(String ownerUid, String statusId, String ownerName, String json, String stickers, long ts) {
        if (!ne(ownerUid) || !ne(statusId)) return null;
        final Row r = new Row();
        r.ownerUid = ownerUid;
        r.statusId = statusId;
        r.ownerName = ownerName;
        r.json = ne(json) ? json : null;
        r.stickers = stickers;
        r.ts = ts;
        r.preview = r.json != null ? TemplatePreviewDrawable.fromJson(r.json) : null;
        return r;
    }

    /** Live templates of mine from the cached status tray (a repost / made-from-template status is not "created"). */
    private void loadCreatedFromTray() {
        try {
            final List<StatusItem> list = StatusCacheManager.getInstance(getApplicationContext()).getStatuses(me);
            if (list == null) return;
            for (StatusItem it : list) {
                if (it == null || !ne(it.templateJson) || !ne(it.id)) continue;
                if (ne(it.templateFromUid) || ne(it.templateFromId)) continue;
                final Row r = makeRow(me, it.id, it.ownerName, it.templateJson, it.stickersJson, it.timestamp != null ? it.timestamp : 0L);
                if (r != null && r.preview != null) created.put(r.key(), r);
            }
        } catch (Exception ignored) { }
    }

    private void loadSaved() {
        try {
            FirebaseUtils.db().getReference("savedTemplates").child(me).orderByChild("ts").limitToLast(MAX_ROWS)
                    .addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override public void onDataChange(@NonNull DataSnapshot s) {
                            if (isFinishing() || isDestroyed()) return;
                            saved.clear();
                            for (DataSnapshot c : s.getChildren()) {
                                final Long ts = c.child("ts").getValue(Long.class);
                                final Row r = makeRow(c.child("ownerUid").getValue(String.class), c.child("statusId").getValue(String.class),
                                        c.child("ownerName").getValue(String.class), c.child("templateJson").getValue(String.class),
                                        c.child("stickersJson").getValue(String.class), ts != null ? ts : 0L);
                                if (r == null || r.preview == null) continue;
                                saved.put(r.key(), r);
                                // my own saved snapshots stay in Created after the 24h status is gone
                                if (me.equals(r.ownerUid) && !created.containsKey(r.key())) created.put(r.key(), r);
                            }
                            render();
                        }
                        @Override public void onCancelled(@NonNull DatabaseError e) { /* tab just stays empty */ }
                    });
        } catch (Exception ignored) { }
    }

    private void loadUsed() {
        try {
            FirebaseUtils.db().getReference("userTemplateUsed").child(me).orderByChild("ts").limitToLast(MAX_ROWS)
                    .addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override public void onDataChange(@NonNull DataSnapshot s) {
                            if (isFinishing() || isDestroyed()) return;
                            used.clear();
                            for (DataSnapshot c : s.getChildren()) {
                                final Long ts = c.child("ts").getValue(Long.class);
                                final Row r = makeRow(c.child("ownerUid").getValue(String.class), c.child("statusId").getValue(String.class),
                                        c.child("ownerName").getValue(String.class), c.child("templateJson").getValue(String.class),
                                        c.child("stickersJson").getValue(String.class), ts != null ? ts : 0L);
                                if (r != null) used.put(r.key(), r);
                            }
                            render();
                        }
                        @Override public void onCancelled(@NonNull DatabaseError e) { /* tab just stays empty */ }
                    });
        } catch (Exception ignored) { }
    }

    /** v774: local drafts of the Create Template screen. */
    private void loadDrafts() {
        drafts.clear();
        for (TemplateDrafts.Draft d : TemplateDrafts.list(this, me)) {
            final Row r = makeRow(me, d.id, null, d.json, null, d.ts);
            if (r == null) continue;
            r.draft = true;
            drafts.put(r.key(), r);
        }
    }

    // ── render ───────────────────────────────────────────────────────────────

    private List<Row> current() {
        final List<Row> l = new ArrayList<>((tab == TAB_SAVED ? saved : tab == TAB_USED ? used : tab == TAB_DRAFTS ? drafts : created).values());
        Collections.sort(l, (a, b) -> Long.compare(b.ts, a.ts));
        return l;
    }

    private void render() {
        if (isFinishing() || isDestroyed() || listBox == null) return;
        for (int i = 0; i < tabViews.size(); i++) {
            final TextView t = tabViews.get(i);
            final boolean on = i == tab;
            t.setBackground(round(on ? PURPLE : surface, 22));
            t.setTextColor(on ? 0xFFFFFFFF : textColor);
        }
        listBox.removeAllViews();
        final List<Row> rows = current();
        for (Row r : rows) {
            listBox.addView(makeRowView(r), rowLp());
            if (!r.draft) askUses(r);
        }
        empty.setText(tab == TAB_SAVED ? R.string.mt_empty_saved : tab == TAB_USED ? R.string.mt_empty_used
                : tab == TAB_DRAFTS ? R.string.mt_empty_drafts : R.string.mt_empty_created);
        empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private LinearLayout.LayoutParams rowLp() {
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, dp(10));
        return lp;
    }

    private void askUses(final Row r) {
        if (r.usesAsked) return;
        r.usesAsked = true;
        StatusTemplateUses.fetchCount(r.ownerUid, r.statusId, n -> {
            r.uses = n;
            if (isFinishing() || isDestroyed()) return;
            ui.removeCallbacks(renderRun);          // many rows answer at once -> one redraw
            ui.postDelayed(renderRun, 120);
        });
    }

    private View makeRowView(final Row r) {
        final boolean mine = me.equals(r.ownerUid);
        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(10), dp(10), dp(6), dp(10));
        card.setBackground(round(surface, 18));

        // thumbnail
        final FrameLayout th = new FrameLayout(this);
        final ImageView p = new ImageView(this);
        p.setScaleType(ImageView.ScaleType.FIT_XY);
        if (r.preview != null) {
            p.setImageDrawable(r.preview);
        } else {
            th.setBackground(round(dark ? 0x33FFFFFF : 0x1A6A3DE8, 12));
            th.addView(icon(R.drawable.ic_video, PURPLE, 24), new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        }
        th.addView(p, new FrameLayout.LayoutParams(-1, -1));
        card.addView(th, new LinearLayout.LayoutParams(dp(56), dp(100)));

        // text
        final LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        final String who = ne(r.ownerName) ? r.ownerName : "user";
        final TextView nm = text(r.draft ? getString(R.string.mt_draft_name) : mine ? getString(R.string.mt_your_template) : "@" + who, 16f, textColor, true);
        nm.setSingleLine(true);
        nm.setEllipsize(TextUtils.TruncateAt.END);
        mid.addView(nm);
        if (r.draft) {   // v774: drafts have no uses — show scene count + when it was saved
            final int n = TemplateSpec.parse(r.json).isMultiScene() ? TemplateSpec.parse(r.json).scenes.size() : 1;
            final String when = android.text.format.DateUtils.getRelativeTimeSpanString(r.ts, System.currentTimeMillis(),
                    android.text.format.DateUtils.MINUTE_IN_MILLIS).toString();
            final TextView dt = text(getString(R.string.mt_draft_sub, n, when), 13f, subColor, false);
            dt.setPadding(0, dp(6), 0, 0);
            mid.addView(dt);
        }
        final LinearLayout uses = new LinearLayout(this);
        uses.setOrientation(LinearLayout.HORIZONTAL);
        uses.setGravity(Gravity.CENTER_VERTICAL);
        uses.setPadding(0, dp(6), 0, 0);
        uses.addView(icon(R.drawable.ic_sound_flame_outline, 0xFFFF6A1A, 15));
        final TextView ut = text(r.uses > 0 ? getString(R.string.mt_uses, compact(r.uses)) : getString(R.string.mt_no_uses),
                13f, r.uses > 0 ? 0xFFFF6A1A : subColor, r.uses > 0);
        ut.setPadding(dp(4), 0, 0, 0);
        uses.addView(ut);
        if (!r.draft) mid.addView(uses);
        final LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0, -2, 1f);
        mlp.setMargins(dp(12), 0, dp(6), 0);
        card.addView(mid, mlp);

        // analytics (Created only) + more
        if (tab == TAB_CREATED && mine) {
            final FrameLayout an = new FrameLayout(this);
            an.addView(icon(R.drawable.ic_tpl_analytics, PURPLE, 22), new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
            an.setContentDescription(getString(R.string.mt_analytics));
            an.setOnClickListener(v -> startActivity(new Intent(this, TemplateAnalyticsActivity.class)   // v771
                    .putExtra(TemplateAnalyticsActivity.EXTRA_STATUS_ID, r.statusId)));
            card.addView(an, new LinearLayout.LayoutParams(dp(40), dp(40)));
        }
        final FrameLayout more = new FrameLayout(this);
        more.addView(icon(R.drawable.ic_more_vert, subColor, 22), new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        more.setContentDescription(getString(R.string.mt_more));
        more.setOnClickListener(v -> showMenu(v, r, mine));
        card.addView(more, new LinearLayout.LayoutParams(dp(40), dp(40)));

        card.setOnClickListener(v -> openPreview(r));
        return card;
    }

    // ── actions ──────────────────────────────────────────────────────────────

    /** Used rows made before the snapshot existed carry no json: try the live status once, else tell the user. */
    private void withJson(final Row r, final Runnable go) {
        if (r.json != null) { go.run(); return; }
        try {
            FirebaseUtils.db().getReference("status").child(r.ownerUid).child(r.statusId)
                    .addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override public void onDataChange(@NonNull DataSnapshot s) {
                            if (isFinishing() || isDestroyed()) return;
                            final String json = s.child("templateJson").getValue(String.class);
                            if (!ne(json)) { Toast.makeText(MyTemplatesActivity.this, R.string.mt_unavailable, Toast.LENGTH_SHORT).show(); return; }
                            r.json = json;
                            r.stickers = s.child("stickersJson").getValue(String.class);
                            r.preview = TemplatePreviewDrawable.fromJson(json);
                            go.run();
                        }
                        @Override public void onCancelled(@NonNull DatabaseError e) {
                            Toast.makeText(MyTemplatesActivity.this, R.string.mt_unavailable, Toast.LENGTH_SHORT).show();
                        }
                    });
        } catch (Exception e) {
            Toast.makeText(this, R.string.mt_unavailable, Toast.LENGTH_SHORT).show();
        }
    }

    private void openPreview(final Row r) {
        if (r.draft) { editDraft(r); return; }
        withJson(r, () -> {
            final Intent i = new Intent(this, TemplatePreviewActivity.class);
            i.putExtra(TemplatePreviewActivity.EXTRA_TPL_JSON, r.json);
            if (ne(r.stickers) && r.stickers.length() < 100_000) i.putExtra(TemplatePreviewActivity.EXTRA_STICKERS_JSON, r.stickers);
            i.putExtra(TemplatePreviewActivity.EXTRA_OWNER_UID, r.ownerUid);
            i.putExtra(TemplatePreviewActivity.EXTRA_STATUS_ID, r.statusId);
            i.putExtra(TemplatePreviewActivity.EXTRA_OWNER_NAME, r.ownerName != null ? r.ownerName : "");
            i.putExtra(TemplatePreviewActivity.EXTRA_USES, r.uses);
            i.putExtra(TemplatePreviewActivity.EXTRA_SAVED, saved.containsKey(r.key()));
            previewLauncher.launch(i);
        });
    }

    private void editDraft(final Row r) {
        startActivity(new Intent(this, CreateTemplateActivity.class).putExtra(CreateTemplateActivity.EXTRA_DRAFT_ID, r.statusId));
    }

    private void showMenu(View anchor, final Row r, boolean mine) {
        if (r.draft) {
            final PopupMenu dm = new PopupMenu(this, anchor);
            dm.getMenu().add(0, 1, 0, R.string.mt_draft_edit);
            dm.getMenu().add(0, 2, 1, R.string.mt_draft_delete);
            dm.setOnMenuItemClickListener(mi -> {
                if (mi.getItemId() == 1) editDraft(r);
                else if (mi.getItemId() == 2) {
                    TemplateDrafts.delete(this, me, r.statusId);
                    drafts.remove(r.key());
                    Toast.makeText(this, R.string.mt_draft_deleted, Toast.LENGTH_SHORT).show();
                    render();
                }
                return true;
            });
            dm.show();
            return;
        }
        if (tab == TAB_CREATED && mine) {
            // the owner sheet: who used it, reuse on / off, link, remove
            TemplateActions.showOwnerSheet(this, r.statusId, Math.max(0, r.uses), null, null, () -> {
                created.remove(r.key());
                render();
            }, () -> withJson(r, () -> {   // v772: Template Editor
                editingId = r.statusId;
                editLauncher.launch(new Intent(this, TemplateEditorActivity.class)
                        .putExtra(TemplateEditorActivity.EXTRA_TPL_JSON, r.json)
                        .putExtra(TemplateEditorActivity.EXTRA_STATUS_ID, r.statusId));
            }));
            return;
        }
        final PopupMenu pm = new PopupMenu(this, anchor);
        pm.getMenu().add(0, 1, 0, tab == TAB_USED ? R.string.mt_menu_use_again : R.string.mt_menu_use);
        pm.getMenu().add(0, 2, 1, R.string.mt_menu_share);
        pm.getMenu().add(0, 3, 2, R.string.mt_menu_copy);
        pm.getMenu().add(0, 4, 3, tab == TAB_USED ? R.string.mt_menu_remove_used : R.string.mt_menu_unsave);
        pm.setOnMenuItemClickListener(mi -> {
            switch (mi.getItemId()) {
                case 1:
                    withJson(r, () -> TemplateActions.use(this, r.json, r.stickers, r.ownerUid, r.statusId, r.ownerName, null));
                    break;
                case 2: TemplateActions.shareLink(this, r.ownerUid, r.statusId); break;
                case 3: TemplateActions.copyLink(this, r.ownerUid, r.statusId); break;
                case 4:
                    if (tab == TAB_USED) {
                        try { FirebaseUtils.db().getReference("userTemplateUsed").child(me).child(r.ownerUid + "_" + r.statusId).removeValue(); }
                        catch (Exception ignored) { }
                        used.remove(r.key());
                        Toast.makeText(this, R.string.mt_removed_toast, Toast.LENGTH_SHORT).show();
                    } else {
                        TemplateActions.unsave(r.ownerUid, r.statusId);
                        saved.remove(r.key());
                        Toast.makeText(this, R.string.mt_unsaved_toast, Toast.LENGTH_SHORT).show();
                    }
                    render();
                    break;
                default: break;
            }
            return true;
        });
        pm.show();
    }
}
