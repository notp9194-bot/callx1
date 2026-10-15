package com.callx.app.viewer;

import android.animation.ObjectAnimator;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.bumptech.glide.Glide;
import com.callx.app.compose.NewStatusActivity;
import com.callx.app.status.R;
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
 * The grid only shows stories that are still live, publicly listed (privacy "everyone") and not by someone I
 * blocked. The counter counts everyone, including people whose story is private/expired, so when the two differ a
 * small note under the counter says so (v579). Non-public entries carry no name/photo/thumb on the server.
 *
 * v579: pre-v578 ("legacy") chains have no chain doc until the first person joins. The caller passes the sticker's
 * origin/template as intent extras so the button still works; if there is neither doc nor extras we show
 * "not available — tap to retry" instead of an endless "Loading…".
 *
 * v580: Toolbar with a real back button, skeleton tiles while the first page loads, pull-to-refresh, an error state
 * with Retry (+ a 10s timeout, because an offline Realtime Database read never fails, it just waits), content
 * descriptions on tiles, and every string/colour moved to resources (add_yours_strings.xml / add_yours_colors.xml in :core).
 */
public class AddYoursChainActivity extends AppCompatActivity {

    private static final int PAGE = 24;
    private static final int GRID_COLS = 3;
    private static final int MAX_AUTO_PAGES = 5;      // keep fetching when a page is mostly filtered out
    private static final int SKELETON_COUNT = 12;     // 4 rows of placeholder tiles while the first page loads
    private static final long CHAIN_WAIT_MS = 6000L;  // no chain doc after this long -> "not available, tap to retry"
    private static final long FIRST_PAGE_TIMEOUT_MS = 10000L;

    private static final int TYPE_TILE = 0, TYPE_SKELETON = 1;

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
    private boolean hasFallback;                 // origin came from the intent (no chain doc yet)
    private boolean chainLoaded, chainMissing, joined, loading, endReached, blocksLoaded, awaitingComposer;
    private boolean showSkeleton = true, loadError;
    private long lastCount;
    private long oldestTs = Long.MAX_VALUE;
    private int autoPages;
    private int gen;                             // bumped on every reload so stale page callbacks are ignored

    private TextView tvPrompt, tvOrigin, tvCount, tvNote, tvEmpty, tvError;
    private View errorBox;
    private TextView btnJoin;
    private RecyclerView rv;
    private SwipeRefreshLayout swipe;
    private TileAdapter adapter;
    private DatabaseReference chainRef;
    private ValueEventListener chainListener;

    private final Runnable chainMissingRunnable = () -> {
        if (isFinishing() || isDestroyed() || chainLoaded) return;
        chainMissing = true; updateJoinButton();
    };
    private final Runnable pageTimeoutRunnable = this::onFirstPageTimeout;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        chainId = getIntent().getStringExtra(AddYoursHelper.EXTRA_CHAIN_ID);
        String p = getIntent().getStringExtra(AddYoursHelper.EXTRA_PROMPT);
        if (p != null) prompt = p;
        myUid = FirebaseUtils.getCurrentUid();
        if (chainId == null || chainId.isEmpty()) { finish(); return; }
        String fo = getIntent().getStringExtra(AddYoursHelper.EXTRA_ORIGIN_UID);
        String fn = getIntent().getStringExtra(AddYoursHelper.EXTRA_ORIGIN_NAME);
        String ft = getIntent().getStringExtra(AddYoursHelper.EXTRA_TEMPLATE);
        if (fo != null && !fo.isEmpty()) {
            hasFallback = true; originUid = fo; originName = fn != null ? fn : ""; templateJson = ft != null ? ft : "";
        }
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
    private int color(@ColorRes int res) { return ContextCompat.getColor(this, res); }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(color(R.color.add_yours_bg));
        root.setFitsSystemWindows(true);

        // top bar: a real Toolbar (back arrow gets proper touch target, ripple and TalkBack label)
        Toolbar bar = new Toolbar(this);
        bar.setTitle(R.string.add_yours_title);
        bar.setTitleTextColor(color(R.color.add_yours_text_primary));
        Drawable up = ContextCompat.getDrawable(this, R.drawable.ic_arrow_back);
        if (up != null) {
            up = up.mutate();
            up.setTint(color(R.color.add_yours_text_primary)); // the shared arrow is drawn dark; this screen is dark
            bar.setNavigationIcon(up);
        }
        bar.setNavigationContentDescription(R.string.add_yours_back);
        bar.setNavigationOnClickListener(v -> finish());
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        // header card
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setGravity(Gravity.CENTER_HORIZONTAL);
        head.setPadding(dp(20), dp(8), dp(20), dp(14));

        tvPrompt = new TextView(this);
        tvPrompt.setText(prompt);
        tvPrompt.setTextColor(color(R.color.add_yours_text_primary));
        tvPrompt.setTextSize(22);
        tvPrompt.setTypeface(null, Typeface.BOLD);
        tvPrompt.setGravity(Gravity.CENTER);
        head.addView(tvPrompt);

        tvOrigin = new TextView(this);
        tvOrigin.setTextColor(color(R.color.add_yours_text_origin));
        tvOrigin.setTextSize(12);
        tvOrigin.setGravity(Gravity.CENTER);
        tvOrigin.setVisibility(View.GONE);
        LinearLayout.LayoutParams oLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        oLp.topMargin = dp(4);
        head.addView(tvOrigin, oLp);
        if (hasFallback && !originName.isEmpty()) showOrigin();

        tvCount = new TextView(this);
        tvCount.setTextColor(color(R.color.add_yours_text_secondary));
        tvCount.setTextSize(14);
        tvCount.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cLp.topMargin = dp(8);
        head.addView(tvCount, cLp);

        tvNote = new TextView(this);
        tvNote.setTextColor(color(R.color.add_yours_text_note));
        tvNote.setTextSize(12);
        tvNote.setGravity(Gravity.CENTER);
        tvNote.setVisibility(View.GONE);
        LinearLayout.LayoutParams nLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nLp.topMargin = dp(2);
        head.addView(tvNote, nLp);

        btnJoin = new TextView(this);
        btnJoin.setTextColor(color(R.color.add_yours_text_primary));
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

        // grid (in a pull-to-refresh) + empty / error overlays
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
        swipe = new SwipeRefreshLayout(this);
        swipe.setColorSchemeColors(color(R.color.add_yours_btn));
        swipe.setProgressBackgroundColorSchemeColor(color(R.color.add_yours_refresh_bg));
        swipe.setOnRefreshListener(this::reloadAll);
        swipe.addView(rv, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        body.addView(swipe, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        tvEmpty = new TextView(this);
        tvEmpty.setTextColor(color(R.color.add_yours_text_empty));
        tvEmpty.setTextSize(14);
        tvEmpty.setGravity(Gravity.CENTER);
        tvEmpty.setPadding(dp(32), dp(32), dp(32), dp(32));
        tvEmpty.setVisibility(View.GONE);
        body.addView(tvEmpty, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        LinearLayout err = new LinearLayout(this);
        err.setOrientation(LinearLayout.VERTICAL);
        err.setGravity(Gravity.CENTER_HORIZONTAL);
        err.setPadding(dp(32), dp(32), dp(32), dp(32));
        err.setVisibility(View.GONE);
        tvError = new TextView(this);
        tvError.setText(R.string.add_yours_error);
        tvError.setTextColor(color(R.color.add_yours_text_empty));
        tvError.setTextSize(14);
        tvError.setGravity(Gravity.CENTER);
        err.addView(tvError);
        TextView retry = new TextView(this);
        retry.setText(R.string.add_yours_retry);
        retry.setTextColor(color(R.color.add_yours_text_primary));
        retry.setTextSize(14);
        retry.setTypeface(null, Typeface.BOLD);
        retry.setGravity(Gravity.CENTER);
        retry.setPadding(dp(24), dp(10), dp(24), dp(10));
        GradientDrawable rBg = new GradientDrawable();
        rBg.setCornerRadius(dp(20));
        rBg.setColor(color(R.color.add_yours_btn));
        retry.setBackground(rBg);
        retry.setOnClickListener(v -> reloadAll());
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rLp.topMargin = dp(14);
        err.addView(retry, rLp);
        errorBox = err;
        body.addView(errorBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private void showOrigin() {
        tvOrigin.setText(getString(R.string.add_yours_started_by, originName));
        tvOrigin.setVisibility(View.VISIBLE);
    }

    private void updateCountText(long count) {
        lastCount = Math.max(0, count);
        updateNote();
        if (tvEmpty != null) updateEmpty();
        tvCount.setText(count <= 0 ? getString(R.string.add_yours_count_zero)
                : getResources().getQuantityString(R.plurals.add_yours_count_people,
                        (int) Math.min(count, Integer.MAX_VALUE), count));
    }

    /** Counter counts everybody; the grid only lists live + public stories. Tell the viewer when they differ. */
    private void updateNote() {
        if (tvNote == null) return;
        int shownJoiners = 0;
        for (Entry e : entries) if (!e.isOrigin) shownJoiners++;
        long hidden = lastCount - shownJoiners;
        if (endReached && !loadError && hidden > 0) {
            tvNote.setText(getResources().getQuantityString(R.plurals.add_yours_hidden_note,
                    (int) Math.min(hidden, Integer.MAX_VALUE), hidden));
            tvNote.setVisibility(View.VISIBLE);
        } else {
            tvNote.setVisibility(View.GONE);
        }
    }

    private void updateJoinButton() {
        boolean enabled = (chainLoaded && !joined) || (!chainLoaded && chainMissing);
        btnJoin.setText(joined ? R.string.add_yours_btn_added
                : chainLoaded ? R.string.add_yours_btn_add
                : chainMissing ? R.string.add_yours_btn_unavailable : R.string.add_yours_btn_loading);
        btnJoin.setEnabled(enabled);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(24));
        bg.setColor(color(enabled ? R.color.add_yours_btn : R.color.add_yours_btn_disabled));
        btnJoin.setBackground(bg);
        btnJoin.setAlpha(enabled ? 1f : 0.85f);
    }

    // ── data ────────────────────────────────────────────────────────────────

    private void bindChain() {
        chainRef = FirebaseUtils.db().getReference(AddYoursHelper.CHAINS).child(chainId);
        chainListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                if (isFinishing() || isDestroyed()) return;
                if (!s.exists()) {
                    if (hasFallback) { // legacy chain: no doc yet, but the sticker told us who started it
                        if (!originName.isEmpty()) showOrigin();
                        chainLoaded = true; chainMissing = false;
                    } else { // first story is still being indexed (or the chain is gone)
                        chainLoaded = false;
                        scheduleChainMissing();
                    }
                    updateJoinButton(); return;
                }
                handler.removeCallbacks(chainMissingRunnable);
                chainMissing = false;
                String p = s.child("prompt").getValue(String.class);
                if (p != null && !p.isEmpty()) { prompt = p; tvPrompt.setText(p); }
                originUid  = str(s.child("originUid").getValue(String.class));
                originName = str(s.child("originName").getValue(String.class));
                if (!originName.isEmpty()) showOrigin();
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

    private void scheduleChainMissing() {
        handler.removeCallbacks(chainMissingRunnable);
        handler.postDelayed(chainMissingRunnable, CHAIN_WAIT_MS);
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

    /** Full reload (pull-to-refresh, Retry, or coming back from posting). Stale in-flight callbacks are dropped via gen. */
    private void reloadAll() {
        if (isFinishing() || isDestroyed()) return;
        gen++;
        handler.removeCallbacks(pageTimeoutRunnable);
        entries.clear(); seenUids.clear();
        oldestTs = Long.MAX_VALUE; endReached = false; loading = false; autoPages = 0;
        loadError = false;
        showSkeleton = !swipe.isRefreshing(); // the spinner already says "loading" for pull-to-refresh
        adapter.notifyDataSetChanged();
        updateEmpty(); updateNote();
        refreshJoined();
        loadPage();
    }

    private void loadPage() {
        if (loading || endReached || !blocksLoaded) return;
        loading = true;
        final int myGen = gen;
        final boolean firstPage = oldestTs == Long.MAX_VALUE;
        if (firstPage) { // an offline RTDB read never fails — it just waits — so give up visibly after a while
            handler.removeCallbacks(pageTimeoutRunnable);
            handler.postDelayed(pageTimeoutRunnable, FIRST_PAGE_TIMEOUT_MS);
        }
        Query q = FirebaseUtils.db().getReference(AddYoursHelper.ENTRIES).child(chainId).orderByChild("ts");
        if (oldestTs != Long.MAX_VALUE) q = q.endAt((double) (oldestTs - 1));
        q.limitToLast(PAGE).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                if (isFinishing() || isDestroyed() || myGen != gen) return;
                handler.removeCallbacks(pageTimeoutRunnable);
                loading = false;
                loadError = false;
                swipe.setRefreshing(false);
                final boolean wasSkeleton = showSkeleton;
                showSkeleton = false;
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
                    if (com.callx.app.models.StatusItem.effectiveExpiry(exp, ts) <= now) continue; // story already gone (null expiry -> ts+24h)
                    boolean listed = Boolean.TRUE.equals(r.child("listed").getValue(Boolean.class));
                    if (!listed) continue;                            // private audience: counted, never listed (no data stored)
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
                if (wasSkeleton) adapter.notifyDataSetChanged(); // placeholder tiles -> real tiles
                else if (entries.size() > before) adapter.notifyItemRangeInserted(before, entries.size() - before);
                updateEmpty();
                updateNote();
                // a page that was mostly private/expired/blocked shouldn't leave the grid looking empty
                if (!endReached && entries.size() - before < PAGE / 3 && autoPages++ < MAX_AUTO_PAGES) loadPage();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                if (isFinishing() || isDestroyed() || myGen != gen) return;
                handler.removeCallbacks(pageTimeoutRunnable);
                loading = false;
                onLoadFailed();
            }
        });
    }

    private void onFirstPageTimeout() {
        if (isFinishing() || isDestroyed() || !loading) return;
        onLoadFailed(); // `loading` stays true: if the read finally answers (same gen) it simply clears the error
    }

    private void onLoadFailed() {
        loadError = true;
        swipe.setRefreshing(false);
        boolean wasSkeleton = showSkeleton;
        showSkeleton = false;
        if (wasSkeleton) adapter.notifyDataSetChanged();
        if (!entries.isEmpty()) // pages after the first: keep what we have, scrolling down again retries
            Toast.makeText(this, R.string.add_yours_error_more, Toast.LENGTH_SHORT).show();
        updateEmpty();
        updateNote();
    }

    /** Exactly one of: skeleton tiles, grid, empty text, error box. */
    private void updateEmpty() {
        boolean noRows = entries.isEmpty() && !showSkeleton;
        boolean showErr = noRows && loadError;
        boolean showEmpty = noRows && !loadError && endReached && !loading;
        errorBox.setVisibility(showErr ? View.VISIBLE : View.GONE);
        tvEmpty.setText(lastCount > 0 ? R.string.add_yours_empty_private : R.string.add_yours_empty_none);
        tvEmpty.setVisibility(showEmpty ? View.VISIBLE : View.GONE);
    }

    // ── actions ─────────────────────────────────────────────────────────────

    private void onJoinClicked() {
        if (joined) {
            Toast.makeText(this, R.string.add_yours_toast_already, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!chainLoaded && chainMissing) { // "tap to retry": the listener is still attached, just wait again
            chainMissing = false; updateJoinButton(); scheduleChainMissing();
            return;
        }
        if (!chainLoaded || originUid.isEmpty()) return;
        try {
            String json = AddYoursHelper.buildJoinJson(prompt, chainId, originUid, originName, templateJson);
            Intent i = new Intent(this, NewStatusActivity.class);
            i.putExtra(NewStatusActivity.EXTRA_PREFILL_STICKER_JSON, json);
            i.putExtra(NewStatusActivity.EXTRA_PREFILL_TOAST, AddYoursHelper.adding(this, originName));
            awaitingComposer = true;
            startActivity(i);
        } catch (Exception e) {
            awaitingComposer = false;
            Toast.makeText(this, R.string.add_yours_toast_composer_failed, Toast.LENGTH_SHORT).show();
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

    private final class TileAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        final class VH extends RecyclerView.ViewHolder {
            final FrameLayout tile; final ImageView img; final TextView text, name, badge;
            VH(FrameLayout t, ImageView i, TextView tx, TextView n, TextView bd) {
                super(t); tile = t; img = i; text = tx; name = n; badge = bd;
            }
        }

        /** Placeholder tile with a gentle pulse; the animator only runs while the tile is attached. */
        final class SkeletonVH extends RecyclerView.ViewHolder {
            final ObjectAnimator pulse;
            SkeletonVH(FrameLayout t) {
                super(t);
                pulse = ObjectAnimator.ofFloat(t, View.ALPHA, 0.45f, 1f);
                pulse.setDuration(800);
                pulse.setInterpolator(new LinearInterpolator());
                pulse.setRepeatCount(ObjectAnimator.INFINITE);
                pulse.setRepeatMode(ObjectAnimator.REVERSE);
            }
        }

        private FrameLayout newTileFrame() {
            int w = getResources().getDisplayMetrics().widthPixels / GRID_COLS - dp(4);
            FrameLayout t = new FrameLayout(AddYoursChainActivity.this);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(w, (int) (w * 1.6f));
            lp.setMargins(dp(2), dp(2), dp(2), dp(2));
            t.setLayoutParams(lp);
            return t;
        }

        @Override public int getItemViewType(int position) { return showSkeleton ? TYPE_SKELETON : TYPE_TILE; }

        @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            FrameLayout t = newTileFrame();
            if (viewType == TYPE_SKELETON) {
                t.setBackgroundColor(color(R.color.add_yours_tile_skeleton));
                t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
                return new SkeletonVH(t);
            }
            t.setBackgroundColor(color(R.color.add_yours_tile_bg));

            ImageView img = new ImageView(AddYoursChainActivity.this);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            t.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            TextView tx = new TextView(AddYoursChainActivity.this);
            tx.setTextColor(color(R.color.add_yours_text_primary));
            tx.setTextSize(13);
            tx.setTypeface(null, Typeface.BOLD);
            tx.setGravity(Gravity.CENTER);
            tx.setPadding(dp(8), dp(8), dp(8), dp(8));
            tx.setMaxLines(6);
            tx.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); // the tile's own description covers it
            t.addView(tx, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            TextView n = new TextView(AddYoursChainActivity.this);
            n.setTextColor(color(R.color.add_yours_text_primary));
            n.setTextSize(11);
            n.setSingleLine(true);
            n.setEllipsize(android.text.TextUtils.TruncateAt.END);
            n.setPadding(dp(6), dp(14), dp(6), dp(6));
            n.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                    new int[]{color(R.color.add_yours_tile_scrim_top), color(R.color.add_yours_tile_scrim_bottom)}));
            n.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            t.addView(n, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

            TextView bd = new TextView(AddYoursChainActivity.this);
            bd.setText(R.string.add_yours_badge_original);
            bd.setTextColor(color(R.color.add_yours_text_primary));
            bd.setTextSize(9);
            bd.setTypeface(null, Typeface.BOLD);
            bd.setPadding(dp(6), dp(2), dp(6), dp(2));
            GradientDrawable bdBg = new GradientDrawable();
            bdBg.setCornerRadius(dp(8));
            bdBg.setColor(color(R.color.add_yours_badge_bg));
            bd.setBackground(bdBg);
            bd.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            FrameLayout.LayoutParams bdLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
            bdLp.setMargins(dp(6), dp(6), 0, 0);
            t.addView(bd, bdLp);
            return new VH(t, img, tx, n, bd);
        }

        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int pos) {
            if (!(holder instanceof VH)) return; // skeleton tiles have nothing to bind
            final VH h = (VH) holder;
            final Entry e = entries.get(pos);
            h.name.setText(e.name);
            h.badge.setVisibility(e.isOrigin ? View.VISIBLE : View.GONE);
            h.tile.setContentDescription(describe(e));
            if (!e.thumb.isEmpty()) {
                h.text.setVisibility(View.GONE);
                h.tile.setBackgroundColor(color(R.color.add_yours_tile_bg));
                Glide.with(h.img).load(e.thumb).centerCrop().into(h.img);
            } else { // text status (or no thumbnail): coloured tile with the first words
                Glide.with(h.img).clear(h.img);
                h.img.setImageDrawable(null);
                int c = color(R.color.add_yours_tile_bg);
                try { if (!e.bg.isEmpty()) c = Color.parseColor(e.bg); } catch (Exception ignored) {}
                h.tile.setBackgroundColor(c);
                h.text.setText(e.text);
                h.text.setVisibility(View.VISIBLE);
            }
            h.tile.setOnClickListener(v -> openStory(e));
        }

        private String describe(Entry e) {
            boolean named = !e.name.isEmpty();
            if (e.isOrigin) return named ? getString(R.string.add_yours_tile_desc_original, e.name)
                                         : getString(R.string.add_yours_tile_desc_original_unknown);
            return named ? getString(R.string.add_yours_tile_desc, e.name)
                         : getString(R.string.add_yours_tile_desc_unknown);
        }

        @Override public void onViewAttachedToWindow(@NonNull RecyclerView.ViewHolder h) {
            if (h instanceof SkeletonVH) ((SkeletonVH) h).pulse.start();
        }

        @Override public void onViewDetachedFromWindow(@NonNull RecyclerView.ViewHolder h) {
            if (h instanceof SkeletonVH) {
                ((SkeletonVH) h).pulse.cancel();
                h.itemView.setAlpha(1f);
            }
        }

        @Override public int getItemCount() { return showSkeleton ? SKELETON_COUNT : entries.size(); }
    }
}
