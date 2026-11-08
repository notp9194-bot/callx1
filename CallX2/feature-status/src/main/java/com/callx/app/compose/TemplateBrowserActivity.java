package com.callx.app.compose;

import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.cache.StatusCacheManager;
import com.callx.app.models.StatusItem;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Templates screen.
 *  • v749 "Discover": every LIVE photo template from the people whose status you can see (+ your own), most used first.
 *    No new index / rules: it reads the status tray that is already cached, so it only shows what you could already open.
 *  • v751 "Saved": your favourites (savedTemplates/{me}, newest first). A heart on every card saves / removes a template.
 *    Discover hides cards whose owner switched reuse off or removed the template; Saved keeps them (dimmed) so you can
 *    still un-save them.
 * Tap a card → the normal template composer (TemplateActions.use re-checks the owner's switch).
 */
public class TemplateBrowserActivity extends AppCompatActivity {

    private static final int COLS = 3;
    private static final int TAB_DISCOVER = 0, TAB_SAVED = 1;
    private static final int MAX_SAVED = 60;

    private static final class Row {
        String tplJson, stickersJson;
        String originUid, originId, originName;
        boolean mine, original;
        long uses = -1;
        long ts;           // status time (Discover order tie-break)
        long savedTs;      // when it was saved (Saved order)
        boolean blocked;   // owner turned reuse off / removed it
        boolean metaAsked;
        TemplatePreviewDrawable preview;
        String key() { return originUid + "/" + originId; }
    }

    private final Map<String, Row> discover = new LinkedHashMap<>();
    private final Map<String, Row> saved = new LinkedHashMap<>();
    private final Set<String> savedKeys = new HashSet<>();
    private final List<Row> shown = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable rebuildRun = this::rebuild;

    private Adapter adapter;
    private TextView empty, tabDiscover, tabSaved;
    private int textColor, subColor;
    private boolean dark;
    private int tab = TAB_DISCOVER;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        final TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.colorBackground, tv, true);
        final int bg = tv.data;
        dark = ColorUtils.calculateLuminance(bg) < 0.5;
        textColor = dark ? 0xFFFFFFFF : 0xFF111111;
        subColor = dark ? 0xAAFFFFFF : 0x99000000;

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        root.setBackgroundColor(bg);

        final LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(8), dp(8), dp(16), dp(4));
        final TextView back = new TextView(this);
        back.setText("\u2190");
        back.setTextSize(24f);
        back.setTextColor(textColor);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(v -> finish());
        back.setContentDescription("Back");
        head.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        final TextView title = new TextView(this);
        title.setText("Templates");
        title.setTextSize(20f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(textColor);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // v751: Discover | Saved
        final LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(dp(14), dp(2), dp(14), dp(8));
        tabDiscover = makeTab("Discover", TAB_DISCOVER);
        tabSaved = makeTab("\u2665 Saved", TAB_SAVED);
        tabs.addView(tabDiscover, tabLp());
        tabs.addView(tabSaved, tabLp());
        root.addView(tabs, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final FrameLayout body = new FrameLayout(this);
        final RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new GridLayoutManager(this, COLS));
        rv.setClipToPadding(false);
        rv.setPadding(dp(6), dp(4), dp(6), dp(16));
        adapter = new Adapter();
        rv.setAdapter(adapter);
        body.addView(rv, new FrameLayout.LayoutParams(-1, -1));
        empty = new TextView(this);
        empty.setTextColor(subColor);
        empty.setTextSize(14f);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(32), 0, dp(32), 0);
        empty.setVisibility(View.GONE);
        body.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);

        styleTabs();
        loadDiscover();
        loadSaved();
        rebuild();
    }

    @Override protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private static boolean ne(String s) { return s != null && !s.isEmpty(); }

    private LinearLayout.LayoutParams tabLp() {
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp(8), 0);
        return lp;
    }

    private TextView makeTab(String label, final int which) {
        final TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(14f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(18), dp(8), dp(18), dp(8));
        t.setOnClickListener(v -> {
            if (tab == which) return;
            tab = which;
            styleTabs();
            rebuild();
        });
        return t;
    }

    private void styleTabs() {
        for (int i = 0; i < 2; i++) {
            final TextView t = i == TAB_DISCOVER ? tabDiscover : tabSaved;
            final boolean on = tab == i;
            final GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(20));
            g.setColor(on ? 0xFF6A3DE8 : (dark ? 0x22FFFFFF : 0x11000000));
            t.setBackground(g);
            t.setTextColor(on ? 0xFFFFFFFF : textColor);
        }
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

    private void rebuild() {
        if (isFinishing() || isDestroyed()) return;
        final List<Row> shownBefore = new ArrayList<>(shown);
        shown.clear();
        final Iterable<Row> src = tab == TAB_DISCOVER ? discover.values() : saved.values();
        for (Row r : src) {
            if (tab == TAB_DISCOVER && r.blocked) continue;   // owner turned reuse off / removed it → no dead card
            shown.add(r);
        }
        if (tab == TAB_DISCOVER) {
            Collections.sort(shown, (a, b) -> {                // most used first, newest as the tie-break
                final long ua = Math.max(0, a.uses), ub = Math.max(0, b.uses);
                if (ua != ub) return ub > ua ? 1 : -1;
                return Long.compare(b.ts, a.ts);
            });
        } else {
            Collections.sort(shown, (a, b) -> Long.compare(b.savedTs, a.savedTs)); // last saved first
        }
        com.callx.app.utils.AdapterDiff.dispatch(adapter, shownBefore, shown,
                r -> r.originUid + "/" + r.originId,
                r -> r.uses + "|" + r.blocked + "|" + r.savedTs + "|" + r.ts + "|" + r.originName + "|" + r.tplJson);
        empty.setText(tab == TAB_DISCOVER
                ? "Abhi koi template nahi.\nJab koi apni status ko template banayega, yahan dikhega."
                : "Abhi koi template save nahi.\nKisi card par \u2661 dabao, yahan aa jayega.");
        empty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
        for (Row r : new ArrayList<>(shown)) fetchMeta(r);
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

    // ── grid ─────────────────────────────────────────────────────────────────

    private final class Adapter extends RecyclerView.Adapter<Adapter.VH> {
        final class VH extends RecyclerView.ViewHolder {
            final ImageView preview; final TextView name, meta, heart;
            VH(LinearLayout v, ImageView p, TextView n, TextView m, TextView h) { super(v); preview = p; name = n; meta = m; heart = h; }
        }

        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final int cellW = (getResources().getDisplayMetrics().widthPixels - dp(12) - COLS * dp(8)) / COLS;
            final LinearLayout cell = new LinearLayout(parent.getContext());
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setPadding(dp(4), dp(4), dp(4), dp(8));

            final FrameLayout pf = new FrameLayout(parent.getContext());
            final ImageView p = new ImageView(parent.getContext());
            p.setScaleType(ImageView.ScaleType.FIT_XY);   // drawable takes the view bounds
            p.setClipToOutline(true);
            p.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(12)); }
            });
            pf.addView(p, new FrameLayout.LayoutParams(-1, -1));
            final TextView h = new TextView(parent.getContext());
            h.setTextSize(18f);
            h.setGravity(Gravity.CENTER);
            final GradientDrawable hb = new GradientDrawable();
            hb.setShape(GradientDrawable.OVAL);
            hb.setColor(0x66000000);
            h.setBackground(hb);
            final FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(dp(34), dp(34), Gravity.TOP | Gravity.END);
            hlp.setMargins(0, dp(6), dp(6), 0);
            pf.addView(h, hlp);
            cell.addView(pf, new LinearLayout.LayoutParams(cellW, Math.round(cellW * 16f / 9f)));

            final TextView n = new TextView(parent.getContext());
            n.setTextColor(textColor); n.setTextSize(13f); n.setTypeface(Typeface.DEFAULT_BOLD);
            n.setSingleLine(true); n.setEllipsize(android.text.TextUtils.TruncateAt.END);
            n.setPadding(dp(2), dp(6), dp(2), 0);
            cell.addView(n, new LinearLayout.LayoutParams(cellW, ViewGroup.LayoutParams.WRAP_CONTENT));
            final TextView m = new TextView(parent.getContext());
            m.setTextColor(subColor); m.setTextSize(12f); m.setSingleLine(true);
            m.setPadding(dp(2), 0, dp(2), 0);
            cell.addView(m, new LinearLayout.LayoutParams(cellW, ViewGroup.LayoutParams.WRAP_CONTENT));
            cell.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new VH(cell, p, n, m, h);
        }

        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            final Row r = shown.get(pos);
            h.preview.setImageDrawable(r.preview);
            h.name.setText(r.mine ? "Your template" : "@" + (ne(r.originName) ? r.originName : "user"));
            final boolean on = savedKeys.contains(r.key());
            h.heart.setText(on ? "\u2665" : "\u2661");
            h.heart.setTextColor(on ? 0xFFFF4D6D : 0xFFFFFFFF);
            h.heart.setContentDescription(on ? "Saved se hatao" : "Template save karo");
            h.heart.setOnClickListener(v -> toggleSave(r));
            final boolean dead = r.blocked && tab == TAB_SAVED;   // only reachable in Saved (Discover hides it)
            h.meta.setText(dead ? "Owner ne band kiya"
                    : r.preview.photoCount() + " photos" + (r.uses > 0 ? "  \u2022  " + r.uses + " used" : ""));
            h.preview.setAlpha(dead ? 0.45f : 1f);
            h.itemView.setOnClickListener(v -> open(r));
        }

        @Override public int getItemCount() { return shown.size(); }
    }
}
