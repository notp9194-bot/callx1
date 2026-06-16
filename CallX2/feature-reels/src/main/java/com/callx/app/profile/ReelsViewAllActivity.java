package com.callx.app.profile;

import com.callx.app.player.SingleReelPlayerActivity;

import android.content.Intent;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.bumptech.glide.Glide;
import com.callx.app.reels.R;
import com.callx.app.models.ReelModel;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ReelsViewAllActivity — Full Instagram-style grid + vertical scroll screen.
 *
 * Opened from the "View All Reels" footer on the Liked / Saved tabs of
 * UserReelsActivity (those tabs are capped to 6 items on the profile screen).
 * This screen loads and paginates the COMPLETE list (no cap), shown as a
 * proper 3-column scrolling grid. Tapping any thumbnail opens
 * SingleReelPlayerActivity with the full ordered reel-id list so the user
 * gets true Instagram-style vertical swipe-through playback starting at the
 * tapped reel.
 */
public class ReelsViewAllActivity extends AppCompatActivity {

    public static final String EXTRA_UID      = "uid";
    public static final String EXTRA_NAME     = "name";
    public static final String EXTRA_TAB_TYPE = "tab_type";

    public static final int TAB_TYPE_LIKED = 1;
    public static final int TAB_TYPE_SAVED = 2;

    private static final int PAGE_SIZE = 18;

    private RecyclerView       rv;
    private SwipeRefreshLayout swipeRefresh;
    private ProgressBar        progressBar;
    private TextView           tvEmpty, tvTitle, tvCount;
    private ImageButton        btnBack;
    private GridLayoutManager  gridLayoutManager;
    private ReelsAdapter       adapter;

    private final List<ReelModel> data = new ArrayList<>();

    private String  targetUid, targetName;
    private int     tabType;
    private String  lastKey      = null;
    private boolean hasMore      = true;
    private boolean isLoading    = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        targetUid = getIntent().getStringExtra(EXTRA_UID);
        targetName = getIntent().getStringExtra(EXTRA_NAME);
        tabType   = getIntent().getIntExtra(EXTRA_TAB_TYPE, TAB_TYPE_LIKED);
        if (targetUid == null || targetUid.isEmpty()) { finish(); return; }

        buildLayout();
        loadPage(true);
    }

    // ── UI ────────────────────────────────────────────────────────────────

    private void buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0D0D0D);

        // Toolbar
        LinearLayout tb = new LinearLayout(this);
        tb.setOrientation(LinearLayout.HORIZONTAL);
        tb.setGravity(Gravity.CENTER_VERTICAL);
        tb.setBackgroundColor(0xFF141414);
        tb.setPadding(dp(4), 0, dp(16), 0);

        btnBack = new ImageButton(this);
        btnBack.setImageResource(R.drawable.ic_arrow_back);
        btnBack.setBackground(null);
        if (btnBack.getDrawable() != null) btnBack.getDrawable().setTint(0xFFFFFFFF);
        btnBack.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        btnBack.setOnClickListener(v -> finish());
        tb.addView(btnBack);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        titleCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        titleCol.setPadding(dp(4), 0, 0, 0);

        tvTitle = new TextView(this);
        String who = (targetName != null && !targetName.isEmpty()) ? targetName : "User";
        tvTitle.setText(tabType == TAB_TYPE_SAVED ? who + "'s Saved Reels" : who + "'s Liked Reels");
        tvTitle.setTextColor(0xFFFFFFFF);
        tvTitle.setTextSize(17);
        tvTitle.setMaxLines(1);
        tvTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tvTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        titleCol.addView(tvTitle);

        tvCount = new TextView(this);
        tvCount.setTextColor(0xFF888888);
        tvCount.setTextSize(12);
        titleCol.addView(tvCount);
        tb.addView(titleCol);
        root.addView(tb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        View div = new View(this);
        div.setBackgroundColor(0xFF222222);
        root.addView(div, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));

        // SwipeRefresh + Grid (RecyclerView owns all scrolling — Instagram style)
        swipeRefresh = new SwipeRefreshLayout(this);
        swipeRefresh.setColorSchemeColors(0xFF5B5BF6);

        FrameLayout frame = new FrameLayout(this);

        rv = new RecyclerView(this);
        gridLayoutManager = new GridLayoutManager(this, 3);
        rv.setLayoutManager(gridLayoutManager);
        rv.setHasFixedSize(false);
        rv.setClipToPadding(false);
        rv.setPadding(dp(1), dp(1), dp(1), dp(1));
        adapter = new ReelsAdapter();
        rv.setAdapter(adapter);
        frame.addView(rv, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        progressBar = new ProgressBar(this);
        FrameLayout.LayoutParams pLp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pLp.gravity = Gravity.CENTER;
        pLp.topMargin = dp(40);
        progressBar.setLayoutParams(pLp);
        progressBar.getIndeterminateDrawable().setColorFilter(0xFFA855F7, android.graphics.PorterDuff.Mode.SRC_IN);
        frame.addView(progressBar);

        tvEmpty = new TextView(this);
        tvEmpty.setText(tabType == TAB_TYPE_SAVED ? "No saved reels yet" : "No liked reels yet");
        tvEmpty.setTextColor(0xFF666666);
        tvEmpty.setTextSize(15);
        tvEmpty.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams eLp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        eLp.gravity = Gravity.CENTER;
        tvEmpty.setLayoutParams(eLp);
        tvEmpty.setVisibility(View.GONE);
        frame.addView(tvEmpty);

        swipeRefresh.addView(frame);
        root.addView(swipeRefresh, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        swipeRefresh.setOnRefreshListener(() -> loadPage(true));

        // Pagination: load next page when near the bottom — same pattern as
        // UserReelsActivity's scroll-driven pagination.
        rv.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                swipeRefresh.setEnabled(!recyclerView.canScrollVertically(-1));
                if (isLoading || !hasMore) return;
                int total       = gridLayoutManager.getItemCount();
                int lastVisible = gridLayoutManager.findLastVisibleItemPosition();
                if (lastVisible >= total - 6) loadPage(false);
            }
        });
    }

    // ── Data loading (paginated — full list, no cap) ────────────────────────

    private DatabaseReference sourceRef() {
        return tabType == TAB_TYPE_SAVED
            ? FirebaseUtils.getReelSavesRef(targetUid)
            : FirebaseUtils.getReelLikedByUserRef(targetUid);
    }

    private void loadPage(boolean refresh) {
        if (isLoading && !refresh) return;
        isLoading = true;
        if (refresh) {
            lastKey = null;
            hasMore = true;
            data.clear();
            if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
            if (tvEmpty != null) tvEmpty.setVisibility(View.GONE);
        }

        Query q = lastKey == null
            ? sourceRef().orderByKey().limitToLast(PAGE_SIZE)
            : sourceRef().orderByKey().endBefore(lastKey).limitToLast(PAGE_SIZE);

        q.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (isFinishing() || isDestroyed()) return;
                if (snap.getChildrenCount() < PAGE_SIZE) hasMore = false;
                if (snap.getChildrenCount() == 0) { finishLoad(refresh); return; }

                List<String> ids = new ArrayList<>();
                for (DataSnapshot s : snap.getChildren()) ids.add(s.getKey());
                Collections.reverse(ids);
                if (!ids.isEmpty()) lastKey = ids.get(ids.size() - 1);

                fetchAndAppend(ids, refresh);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { finishLoad(refresh); }
        });
    }

    private void fetchAndAppend(List<String> ids, boolean refresh) {
        AtomicInteger remaining = new AtomicInteger(ids.size());
        List<ReelModel> fetched = new ArrayList<>();
        for (String id : ids) {
            FirebaseUtils.getReelsRef().child(id).addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (!isFinishing() && !isDestroyed()) {
                        ReelModel r = snap.getValue(ReelModel.class);
                        if (r != null) {
                            if (r.reelId == null || r.reelId.isEmpty()) r.reelId = snap.getKey();
                            fetched.add(r);
                        }
                    }
                    if (remaining.decrementAndGet() == 0) {
                        fetched.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
                        data.addAll(fetched);
                        finishLoad(refresh);
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    if (remaining.decrementAndGet() == 0) finishLoad(refresh);
                }
            });
        }
    }

    private void finishLoad(boolean refresh) {
        if (isFinishing() || isDestroyed()) return;
        isLoading = false;
        if (progressBar != null) progressBar.setVisibility(View.GONE);
        if (swipeRefresh != null) swipeRefresh.setRefreshing(false);

        boolean empty = data.isEmpty();
        if (tvEmpty != null) tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (rv != null) rv.setVisibility(empty ? View.GONE : View.VISIBLE);

        int n = data.size();
        if (tvCount != null) {
            tvCount.setText(n + (n == 1 ? " reel" : " reels") + (hasMore ? "+" : ""));
        }
        adapter.notifyDataSetChanged();
    }

    // ── Open player — Instagram-style full screen vertical scroll ──────────

    private void openPlayerAt(int position) {
        if (position < 0 || position >= data.size()) return;
        ArrayList<String> ids = new ArrayList<>();
        for (ReelModel r : data) if (r != null && r.reelId != null) ids.add(r.reelId);

        Intent intent = new Intent(this, SingleReelPlayerActivity.class);
        intent.putStringArrayListExtra(SingleReelPlayerActivity.EXTRA_REEL_IDS, ids);
        intent.putExtra(SingleReelPlayerActivity.EXTRA_START_POSITION, position);
        intent.putExtra(SingleReelPlayerActivity.EXTRA_TITLE, tvTitle != null ? tvTitle.getText().toString() : "Reels");
        startActivity(intent);
    }

    // ── Adapter ──────────────────────────────────────────────────────────

    class ReelsAdapter extends RecyclerView.Adapter<ReelsAdapter.VH> {

        class VH extends RecyclerView.ViewHolder {
            ImageView ivThumb;
            TextView  tvDuration;
            VH(View v) {
                super(v);
                ivThumb    = v.findViewWithTag("img");
                tvDuration = v.findViewWithTag("dur");
            }
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            int size = (getResources().getDisplayMetrics().widthPixels - dp(2)) / 3;
            FrameLayout cell = new FrameLayout(ReelsViewAllActivity.this);
            RecyclerView.LayoutParams cellLp = new RecyclerView.LayoutParams(size, size);
            cellLp.setMargins(1, 1, 1, 1);
            cell.setLayoutParams(cellLp);
            cell.setBackgroundColor(0xFF1A1A1A);
            cell.setForeground(androidx.core.content.ContextCompat.getDrawable(
                ReelsViewAllActivity.this, android.R.drawable.list_selector_background));

            ImageView iv = new ImageView(ReelsViewAllActivity.this);
            iv.setTag("img");
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cell.addView(iv, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);

            View scrim = new View(ReelsViewAllActivity.this);
            scrim.setBackgroundColor(0x55000000);
            FrameLayout.LayoutParams scrimLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(26));
            scrimLp.gravity = Gravity.BOTTOM;
            cell.addView(scrim, scrimLp);

            TextView tvDur = new TextView(ReelsViewAllActivity.this);
            tvDur.setTag("dur");
            tvDur.setTextColor(0xFFFFFFFF);
            tvDur.setTextSize(11);
            FrameLayout.LayoutParams dLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dLp.gravity = Gravity.BOTTOM | Gravity.END;
            dLp.setMargins(0, 0, dp(4), dp(4));
            cell.addView(tvDur, dLp);

            return new VH(cell);
        }

        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            ReelModel reel = data.get(pos);
            String thumb = reel.thumbUrl != null && !reel.thumbUrl.isEmpty() ? reel.thumbUrl : reel.thumbnailUrl;
            if (thumb != null && !thumb.isEmpty())
                Glide.with(ReelsViewAllActivity.this).load(thumb).centerCrop()
                    .placeholder(R.drawable.ic_reels).into(h.ivThumb);
            else h.ivThumb.setImageResource(R.drawable.ic_reels);

            if (reel.duration > 0) {
                int s = (reel.duration / 1000) % 60, m = reel.duration / 60000;
                h.tvDuration.setText(String.format(Locale.getDefault(), "%d:%02d", m, s));
                h.tvDuration.setVisibility(View.VISIBLE);
            } else {
                h.tvDuration.setVisibility(View.GONE);
            }

            h.itemView.setOnClickListener(v -> openPlayerAt(h.getAdapterPosition()));
        }

        @Override public int getItemCount() { return data.size(); }
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
}
