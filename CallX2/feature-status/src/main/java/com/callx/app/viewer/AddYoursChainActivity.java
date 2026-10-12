package com.callx.app.viewer;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.callx.app.compose.NewStatusActivity;
import com.callx.app.stickers.AddYoursHelper;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.Query;
import com.google.firebase.database.ValueEventListener;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * v578 — "Add Yours" chain screen: the prompt, how many people added theirs, an "Add Yours" button (joins with the
 * starter's template pre-applied) and a paginated grid of every story in the chain, newest first.
 *
 * Reads (all written server-side, see functions/index.js onStatusCreatedAddYours):
 *   addYoursChains/{chainId}               prompt, originUid/originName, count, template
 *   addYoursChainEntries/{chainId}/{uid}   one row per participant (statusId, ts, expiresAt, thumb, ...)
 *
 * The grid only shows stories that are still live, publicly listed (privacy "everyone") or mine, and not by someone
 * I blocked. The counter counts everyone, including people whose story is private/expired.
 */
public class AddYoursChainActivity extends AppCompatActivity {

    private static final int PAGE = 24;
    private static final int GRID_COLS = 3;
    private static final int MAX_AUTO_PAGES = 5; // keep fetching when a page is mostly filtered out

    private static final class Entry {
        String uid, statusId, name, photo, thumb, type, bg, text;
        long ts;
        boolean isOrigin;
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Entry> entries = new ArrayList<>();
    private final Set<String> blocked = new HashSet<>();
    private final Set<String> seenUids = new HashSet<>();

    private String chainId, prompt = "", myUid;
    private String originUid = "", originName = "", templateJson = "";
    private boolean chainLoaded, joined, loading, endReached, blocksLoaded, awaitingComposer;
    private long oldestTs = Long.MAX_VALUE;
    private int autoPages;

    private TextView tvPrompt, tvOrigin, tvCount, tvEmpty;
    private TextView btnJoin;
    private RecyclerView rv;
    private TileAdapter adapter;
    private DatabaseReference chainRef;
    private ValueEventListener chainListener;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        chainId = getIntent().getStringExtra(AddYoursHelper.EXTRA_CHAIN_ID);
        String p = getIntent().getStringExtra(AddYoursHelper.EXTRA_PROMPT);
        if (p != null) prompt = p;
        myUid = FirebaseUtils.getCurrentUid();
        if (chainId == null || chainId.isEmpty()) { finish(); return; }
        buildUi();
        bindChain();
        refreshJoined();
        loadBlocksThenPage();
    }

    @Override protected void onResume() {
        super.onResume();
        if (awaitingComposer) { // came back from posting: the Cloud Function needs a moment to index the new story
            awaitingComposer = false;
            handler.postDelayed(this::reloadAll, 1800L);
        }
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (chainRef != null && chainListener != null) chainRef.removeEventListener(chainListener);
        super.onDestroy();
    }

    // ── UI (programmatic: no layout/binding dependency) ──────────────────────

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF121212);
        root.setFitsSystemWindows(true);

        // top bar
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = new TextView(this);
        back.setText("\u2190");
        back.setTextColor(Color.WHITE);
        back.setTextSize(24);
        back.setGravity(Gravity.CENTER);
        back.setContentDescription("Back");
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(dp(52), dp(52)));
        TextView title = new TextView(this);
        title.setText("Add Yours");
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setTypeface(null, Typeface.BOLD);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        // header card
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setGravity(Gravity.CENTER_HORIZONTAL);
        head.setPadding(dp(20), dp(8), dp(20), dp(14));

        tvPrompt = new TextView(this);
        tvPrompt.setText(prompt);
        tvPrompt.setTextColor(Color.WHITE);
        tvPrompt.setTextSize(22);
        tvPrompt.setTypeface(null, Typeface.BOLD);
        tvPrompt.setGravity(Gravity.CENTER);
        head.addView(tvPrompt);

        tvOrigin = new TextView(this);
        tvOrigin.setTextColor(0xFFBBBBBB);
        tvOrigin.setTextSize(12);
        tvOrigin.setGravity(Gravity.CENTER);
        tvOrigin.setVisibility(View.GONE);
        LinearLayout.LayoutParams oLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        oLp.topMargin = dp(4);
        head.addView(tvOrigin, oLp);

        tvCount = new TextView(this);
        tvCount.setTextColor(0xFFDDDDDD);
        tvCount.setTextSize(14);
        tvCount.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cLp.topMargin = dp(8);
        head.addView(tvCount, cLp);

        btnJoin = new TextView(this);
        btnJoin.setTextColor(Color.WHITE);
        btnJoin.setTextSize(15);
        btnJoin.setTypeface(null, Typeface.BOLD);
        btnJoin.setGravity(Gravity.CENTER);
        btnJoin.setPadding(dp(28), dp(12), dp(28), dp(12));
        btnJoin.setOnClickListener(v -> onJoinClicked());
        LinearLayout.LayoutParams jLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        jLp.topMargin = dp(14);
        head.addView(btnJoin, jLp);
        updateJoinButton();
        updateCountText(0);
        root.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // grid + empty state
        FrameLayout body = new FrameLayout(this);
        rv = new RecyclerView(this);
        rv.setLayoutManager(new GridLayoutManager(this, GRID_COLS));
        rv.setClipToPadding(false);
        rv.setPadding(dp(2), dp(2), dp(2), dp(12));
        adapter = new TileAdapter();
        rv.setAdapter(adapter);
        rv.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView r, int dx, int dy) {
                if (dy > 0 && !r.canScrollVertically(1)) { autoPages = 0; loadPage(); }
            }
        });
        body.addView(rv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        tvEmpty = new TextView(this);
        tvEmpty.setTextColor(0xFF9E9E9E);
        tvEmpty.setTextSize(14);
        tvEmpty.setGravity(Gravity.CENTER);
        tvEmpty.setPadding(dp(32), dp(32), dp(32), dp(32));
        tvEmpty.setVisibility(View.GONE);
        body.addView(tvEmpty, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private void updateCountText(long count) {
        tvCount.setText(count <= 0 ? "Be the first to add yours"
                : count == 1 ? "1 person added yours" : count + " people added yours");
    }

    private void updateJoinButton() {
        boolean enabled = chainLoaded && !joined;
        btnJoin.setText(joined ? "You added yours \u2713" : chainLoaded ? "Add Yours" : "Loading\u2026");
        btnJoin.setEnabled(enabled);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(24));
        bg.setColor(enabled ? 0xFF1E88E5 : 0xFF3A3A3A);
        btnJoin.setBackground(bg);
        btnJoin.setAlpha(enabled ? 1f : 0.85f);
    }

    // ── data ────────────────────────────────────────────────────────────────

    private void bindChain() {
        chainRef = FirebaseUtils.db().getReference(AddYoursHelper.CHAINS).child(chainId);
        chainListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                if (isFinishing() || isDestroyed()) return;
                if (!s.exists()) { // first story is still being indexed (or the chain is gone)
                    chainLoaded = false; updateJoinButton(); return;
                }
                String p = s.child("prompt").getValue(String.class);
                if (p != null && !p.isEmpty()) { prompt = p; tvPrompt.setText(p); }
                originUid  = str(s.child("originUid").getValue(String.class));
                originName = str(s.child("originName").getValue(String.class));
                if (!originName.isEmpty()) {
                    tvOrigin.setText("\u21B3 Started by " + originName);
                    tvOrigin.setVisibility(View.VISIBLE);
                }
                Long c = s.child("count").getValue(Long.class);
                updateCountText(c != null ? c : 0L);
                templateJson = readTemplate(s.child("template"));
                chainLoaded = true;
                updateJoinButton();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { }
        };
        chainRef.addValueEventListener(chainListener);
    }

    private static String str(String s) { return s == null ? "" : s; }

    /** template node -> JSON string ({bgColor,textColor,fontStyle,textAlign,scale,rotationDeg,posXRatio,posYRatio}). */
    private static String readTemplate(DataSnapshot t) {
        if (t == null || !t.exists()) return "";
        try {
            JSONObject o = new JSONObject();
            for (DataSnapshot c : t.getChildren()) {
                Object v = c.getValue();
                if (c.getKey() != null && (v instanceof String || v instanceof Number || v instanceof Boolean))
                    o.put(c.getKey(), v);
            }
            return o.length() == 0 ? "" : o.toString();
        } catch (Exception e) { return ""; }
    }

    private void refreshJoined() {
        AddYoursHelper.checkJoined(chainId, myUid, j -> {
            if (isFinishing() || isDestroyed()) return;
            joined = j;
            updateJoinButton();
        });
    }

    private void loadBlocksThenPage() {
        if (myUid == null || myUid.isEmpty()) { blocksLoaded = true; loadPage(); return; }
        FirebaseUtils.getBlocksRef(myUid).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                for (DataSnapshot c : s.getChildren()) if (c.getKey() != null) blocked.add(c.getKey());
                blocksLoaded = true; loadPage();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { blocksLoaded = true; loadPage(); }
        });
    }

    private void reloadAll() {
        if (isFinishing() || isDestroyed()) return;
        entries.clear(); seenUids.clear();
        oldestTs = Long.MAX_VALUE; endReached = false; loading = false; autoPages = 0;
        adapter.notifyDataSetChanged();
        refreshJoined();
        loadPage();
    }

    private void loadPage() {
        if (loading || endReached || !blocksLoaded) return;
        loading = true;
        Query q = FirebaseUtils.db().getReference(AddYoursHelper.ENTRIES).child(chainId).orderByChild("ts");
        if (oldestTs != Long.MAX_VALUE) q = q.endAt((double) (oldestTs - 1));
        q.limitToLast(PAGE).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                if (isFinishing() || isDestroyed()) return;
                loading = false;
                List<DataSnapshot> rows = new ArrayList<>();
                for (DataSnapshot c : s.getChildren()) rows.add(c);
                if (rows.size() < PAGE) endReached = true;
                final long now = System.currentTimeMillis();
                final int before = entries.size();
                for (int i = rows.size() - 1; i >= 0; i--) { // ascending by ts -> walk backwards = newest first
                    DataSnapshot r = rows.get(i);
                    Long ts = r.child("ts").getValue(Long.class);
                    if (ts != null && ts < oldestTs) oldestTs = ts;   // page cursor counts filtered rows too
                    String uid = r.getKey();
                    if (uid == null || seenUids.contains(uid) || blocked.contains(uid)) continue;
                    Long exp = r.child("expiresAt").getValue(Long.class);
                    if (exp != null && exp < now) continue;           // story already gone
                    boolean listed = Boolean.TRUE.equals(r.child("listed").getValue(Boolean.class));
                    if (!listed && !uid.equals(myUid)) continue;      // private audience: counted, not listed
                    Entry e = new Entry();
                    e.uid = uid;
                    e.statusId = str(r.child("statusId").getValue(String.class));
                    if (e.statusId.isEmpty()) continue;
                    e.name  = str(r.child("name").getValue(String.class));
                    e.photo = str(r.child("photo").getValue(String.class));
                    e.thumb = str(r.child("thumb").getValue(String.class));
                    e.type  = str(r.child("type").getValue(String.class));
                    e.bg    = str(r.child("bg").getValue(String.class));
                    e.text  = str(r.child("text").getValue(String.class));
                    e.ts    = ts != null ? ts : 0L;
                    e.isOrigin = Boolean.TRUE.equals(r.child("isOrigin").getValue(Boolean.class));
                    seenUids.add(uid);
                    entries.add(e);
                }
                if (entries.size() > before) adapter.notifyItemRangeInserted(before, entries.size() - before);
                updateEmpty();
                // a page that was mostly private/expired/blocked shouldn't leave the grid looking empty
                if (!endReached && entries.size() - before < PAGE / 3 && autoPages++ < MAX_AUTO_PAGES) loadPage();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                loading = false;
                updateEmpty();
            }
        });
    }

    private void updateEmpty() {
        boolean empty = entries.isEmpty() && (endReached || !loading);
        tvEmpty.setText("No stories to show yet.\nBe the first to add yours!");
        tvEmpty.setVisibility(empty && endReached ? View.VISIBLE : View.GONE);
    }

    // ── actions ─────────────────────────────────────────────────────────────

    private void onJoinClicked() {
        if (joined) {
            Toast.makeText(this, "You've already added yours", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!chainLoaded || originUid.isEmpty()) return;
        try {
            String json = AddYoursHelper.buildJoinJson(prompt, chainId, originUid, originName, templateJson);
            Intent i = new Intent(this, NewStatusActivity.class);
            i.putExtra(NewStatusActivity.EXTRA_PREFILL_STICKER_JSON, json);
            i.putExtra(NewStatusActivity.EXTRA_PREFILL_TOAST,
                    "Adding to " + (originName.isEmpty() ? "their" : originName) + "'s prompt");
            awaitingComposer = true;
            startActivity(i);
        } catch (Exception e) {
            awaitingComposer = false;
            Toast.makeText(this, "Couldn't open the composer", Toast.LENGTH_SHORT).show();
        }
    }

    private void openStory(Entry e) {
        Intent i = new Intent(this, StatusViewerActivity.class);
        i.putExtra(StatusViewerActivity.EXTRA_OWNER_UID, e.uid);
        i.putExtra(StatusViewerActivity.EXTRA_OWNER_NAME, e.name);
        i.putExtra(StatusViewerActivity.EXTRA_OWNER_PHOTO, e.photo);
        i.putExtra(StatusViewerActivity.EXTRA_TARGET_STATUS_ID, e.statusId);
        i.putExtra(StatusViewerActivity.EXTRA_SINGLE_STATUS_ONLY, true); // Back returns straight to the chain
        startActivity(i);
    }

    // ── grid ────────────────────────────────────────────────────────────────

    private final class TileAdapter extends RecyclerView.Adapter<TileAdapter.VH> {
        final class VH extends RecyclerView.ViewHolder {
            final FrameLayout tile; final ImageView img; final TextView text, name, badge;
            VH(FrameLayout t, ImageView i, TextView tx, TextView n, TextView bd) {
                super(t); tile = t; img = i; text = tx; name = n; badge = bd;
            }
        }

        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            int w = getResources().getDisplayMetrics().widthPixels / GRID_COLS - dp(4);
            FrameLayout t = new FrameLayout(AddYoursChainActivity.this);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(w, (int) (w * 1.6f));
            lp.setMargins(dp(2), dp(2), dp(2), dp(2));
            t.setLayoutParams(lp);
            t.setBackgroundColor(0xFF263238);

            ImageView img = new ImageView(AddYoursChainActivity.this);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            t.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            TextView tx = new TextView(AddYoursChainActivity.this);
            tx.setTextColor(Color.WHITE);
            tx.setTextSize(13);
            tx.setTypeface(null, Typeface.BOLD);
            tx.setGravity(Gravity.CENTER);
            tx.setPadding(dp(8), dp(8), dp(8), dp(8));
            tx.setMaxLines(6);
            t.addView(tx, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            TextView n = new TextView(AddYoursChainActivity.this);
            n.setTextColor(Color.WHITE);
            n.setTextSize(11);
            n.setSingleLine(true);
            n.setEllipsize(android.text.TextUtils.TruncateAt.END);
            n.setPadding(dp(6), dp(14), dp(6), dp(6));
            n.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                    new int[]{0xAA000000, 0x00000000}));
            t.addView(n, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

            TextView bd = new TextView(AddYoursChainActivity.this);
            bd.setText("ORIGINAL");
            bd.setTextColor(Color.WHITE);
            bd.setTextSize(9);
            bd.setTypeface(null, Typeface.BOLD);
            bd.setPadding(dp(6), dp(2), dp(6), dp(2));
            GradientDrawable bdBg = new GradientDrawable();
            bdBg.setCornerRadius(dp(8));
            bdBg.setColor(0xCC1E88E5);
            bd.setBackground(bdBg);
            FrameLayout.LayoutParams bdLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
            bdLp.setMargins(dp(6), dp(6), 0, 0);
            t.addView(bd, bdLp);
            return new VH(t, img, tx, n, bd);
        }

        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            final Entry e = entries.get(pos);
            h.name.setText(e.name);
            h.badge.setVisibility(e.isOrigin ? View.VISIBLE : View.GONE);
            if (!e.thumb.isEmpty()) {
                h.text.setVisibility(View.GONE);
                h.tile.setBackgroundColor(0xFF263238);
                Glide.with(h.img).load(e.thumb).centerCrop().into(h.img);
            } else { // text status (or no thumbnail): coloured tile with the first words
                Glide.with(h.img).clear(h.img);
                h.img.setImageDrawable(null);
                int c = 0xFF263238;
                try { if (!e.bg.isEmpty()) c = Color.parseColor(e.bg); } catch (Exception ignored) {}
                h.tile.setBackgroundColor(c);
                h.text.setText(e.text);
                h.text.setVisibility(View.VISIBLE);
            }
            h.tile.setOnClickListener(v -> openStory(e));
        }

        @Override public int getItemCount() { return entries.size(); }
    }
}
