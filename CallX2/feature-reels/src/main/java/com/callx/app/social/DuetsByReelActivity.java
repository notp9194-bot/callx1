package com.callx.app.social;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.callx.app.models.ReelModel;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;

import java.util.ArrayList;
import java.util.List;

/**
 * DuetsByReelActivity — Discover all duets made of a specific reel.
 *
 * Features:
 *  ✅ 3-column grid of duet thumbnails (same as Instagram Reels grid)
 *  ✅ Firebase query: reels where duetOf == originalReelId
 *  ✅ Shows duet creator name + view count
 *  ✅ Tap opens reel player (SingleReelPlayerActivity)
 *  ✅ Empty state with label
 *  ✅ PAGINATION: loads 20 at a time, "load more" on scroll end
 *  ✅ Loading footer spinner while fetching next page
 *  ✅ No more loading ALL duets at once (crash-safe for 500+ duets)
 *
 * Pagination technique (Firebase RTDB cursor):
 *   Page 1:  orderByChild("duetOf").equalTo(reelId).limitToFirst(PAGE_SIZE)
 *   Page N:  .startAt(reelId, lastKey).endAt(reelId).limitToFirst(PAGE_SIZE+1)
 *            → returns PAGE_SIZE+1 items including overlap (lastKey)
 *            → skip item[0] (overlap), load remaining PAGE_SIZE items
 *            → if returned < PAGE_SIZE+1, no more pages
 */
public class DuetsByReelActivity extends AppCompatActivity {

    public static final String EXTRA_REEL_ID    = "duets_reel_id";
    public static final String EXTRA_OWNER_NAME = "duets_owner_name";

    private static final int PAGE_SIZE = 20;

    // View types for the adapter
    private static final int VIEW_TYPE_DUET   = 0;
    private static final int VIEW_TYPE_FOOTER = 1;

    private ImageButton  btnBack;
    private TextView     tvTitle, tvDuetCount;
    private RecyclerView rvDuets;
    private ProgressBar  progressDuets;
    private View         layoutEmpty;

    private String               originalReelId;
    private String               ownerName;
    private final List<ReelModel> duets = new ArrayList<>();
    private DuetsAdapter         adapter;

    private String  lastLoadedKey = null;  // cursor for next page
    private boolean hasMorePages  = true;
    private boolean isLoadingPage = false;
    private int     totalLoaded   = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_duets_by_reel);

        originalReelId = getIntent().getStringExtra(EXTRA_REEL_ID);
        ownerName      = getIntent().getStringExtra(EXTRA_OWNER_NAME);
        if (ownerName == null) ownerName = "";

        btnBack       = findViewById(R.id.btn_duets_back);
        tvTitle       = findViewById(R.id.tv_duets_title);
        tvDuetCount   = findViewById(R.id.tv_duet_count);
        rvDuets       = findViewById(R.id.rv_duets);
        progressDuets = findViewById(R.id.progress_duets);
        layoutEmpty   = findViewById(R.id.layout_duets_empty);

        tvTitle.setText(ownerName.isEmpty() ? "Duets" : "Duets of @" + ownerName);

        GridLayoutManager glm = new GridLayoutManager(this, 3);
        // Footer row spans all 3 columns
        glm.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override public int getSpanSize(int position) {
                return adapter.getItemViewType(position) == VIEW_TYPE_FOOTER ? 3 : 1;
            }
        });

        adapter = new DuetsAdapter(duets, this::onDuetTapped);
        rvDuets.setLayoutManager(glm);
        rvDuets.setAdapter(adapter);
        btnBack.setOnClickListener(v -> finish());

        // ── Scroll listener: load next page when near bottom ─────────────────
        rvDuets.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (dy <= 0 || isLoadingPage || !hasMorePages) return;
                GridLayoutManager lm = (GridLayoutManager) rv.getLayoutManager();
                if (lm == null) return;
                int lastVisible = lm.findLastVisibleItemPosition();
                int total       = lm.getItemCount();
                // Trigger when within 6 items of the end (2 rows)
                if (lastVisible >= total - 6) loadNextPage();
            }
        });

        loadFirstPage();
    }

    // ── Page loading ──────────────────────────────────────────────────────────

    private void loadFirstPage() {
        if (originalReelId == null) { showEmpty(true); return; }
        progressDuets.setVisibility(View.VISIBLE);
        layoutEmpty.setVisibility(View.GONE);
        isLoadingPage = true;

        buildFirstPageQuery().addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                progressDuets.setVisibility(View.GONE);
                isLoadingPage = false;

                List<ReelModel> page = parseSnapshot(snap, false);
                hasMorePages = (snap.getChildrenCount() >= PAGE_SIZE);

                if (!page.isEmpty()) {
                    lastLoadedKey = page.get(page.size() - 1).reelId;
                }
                duets.addAll(page);
                totalLoaded += page.size();
                adapter.setShowFooter(hasMorePages);
                adapter.notifyDataSetChanged();

                updateCountLabel();
                showEmpty(duets.isEmpty());
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                progressDuets.setVisibility(View.GONE);
                isLoadingPage = false;
                showEmpty(duets.isEmpty());
                Toast.makeText(DuetsByReelActivity.this,
                    "Failed to load duets", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void loadNextPage() {
        if (!hasMorePages || isLoadingPage || lastLoadedKey == null) return;
        isLoadingPage = true;
        adapter.setShowFooter(true);

        buildNextPageQuery(lastLoadedKey).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                isLoadingPage = false;

                // parseSnapshot skips first item (overlap with previous page cursor)
                List<ReelModel> page = parseSnapshot(snap, true);

                // If we got fewer than PAGE_SIZE new items, no more pages remain
                hasMorePages = (snap.getChildrenCount() > PAGE_SIZE);

                if (!page.isEmpty()) {
                    lastLoadedKey = page.get(page.size() - 1).reelId;
                    int insertAt = duets.size();
                    duets.addAll(page);
                    totalLoaded += page.size();
                    adapter.setShowFooter(hasMorePages);
                    adapter.notifyItemRangeInserted(insertAt, page.size());
                } else {
                    hasMorePages = false;
                    adapter.setShowFooter(false);
                    adapter.notifyDataSetChanged();
                }
                updateCountLabel();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                isLoadingPage = false;
                adapter.setShowFooter(false);
                adapter.notifyDataSetChanged();
            }
        });
    }

    // ── Firebase queries ──────────────────────────────────────────────────────

    private Query buildFirstPageQuery() {
        return FirebaseUtils.db()
            .getReference("reels")
            .orderByChild("duetOf")
            .equalTo(originalReelId)
            .limitToFirst(PAGE_SIZE);
    }

    /**
     * Cursor query for subsequent pages.
     *
     * Firebase RTDB trick: when multiple children share the same child value,
     * startAt(value, key) acts as a compound cursor (value + key tiebreaker).
     * We load PAGE_SIZE+1 so the first item is always the overlap (lastKey),
     * which parseSnapshot(snap, skipFirst=true) discards.
     */
    private Query buildNextPageQuery(String cursorKey) {
        return FirebaseUtils.db()
            .getReference("reels")
            .orderByChild("duetOf")
            .startAt(originalReelId, cursorKey)   // inclusive — first item = cursorKey
            .endAt(originalReelId)
            .limitToFirst(PAGE_SIZE + 1);          // +1 to absorb the cursor overlap
    }

    // ── Parse helpers ─────────────────────────────────────────────────────────

    private List<ReelModel> parseSnapshot(DataSnapshot snap, boolean skipFirst) {
        List<ReelModel> page = new ArrayList<>();
        boolean first = true;
        for (DataSnapshot ds : snap.getChildren()) {
            if (skipFirst && first) { first = false; continue; }
            first = false;
            ReelModel m = ds.getValue(ReelModel.class);
            if (m != null) {
                if (m.reelId == null) m.reelId = ds.getKey();
                page.add(m);
            }
        }
        return page;
    }

    private void updateCountLabel() {
        String suffix = hasMorePages ? "+" : "";
        tvDuetCount.setText(totalLoaded + suffix + " duet" + (totalLoaded == 1 ? "" : "s"));
    }

    private void showEmpty(boolean show) {
        layoutEmpty.setVisibility(show ? View.VISIBLE : View.GONE);
        rvDuets.setVisibility(show ? View.GONE : View.VISIBLE);
    }

    private void onDuetTapped(ReelModel reel) {
        if (reel == null || reel.reelId == null) return;
        Intent i = new Intent(this, com.callx.app.player.SingleReelPlayerActivity.class);
        i.putExtra(com.callx.app.player.SingleReelPlayerActivity.EXTRA_REEL_ID, reel.reelId);
        i.putExtra(com.callx.app.player.SingleReelPlayerActivity.EXTRA_TITLE,
                   reel.ownerName != null ? "Duet by @" + reel.ownerName : "Duet");
        startActivity(i);
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    private static class DuetsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        interface OnDuetClick { void onClick(ReelModel reel); }

        private final List<ReelModel> items;
        private final OnDuetClick     listener;
        private boolean               showFooter = false;

        DuetsAdapter(List<ReelModel> items, OnDuetClick listener) {
            this.items    = items;
            this.listener = listener;
        }

        void setShowFooter(boolean show) { this.showFooter = show; }

        @Override public int getItemViewType(int position) {
            return (showFooter && position == items.size()) ? VIEW_TYPE_FOOTER : VIEW_TYPE_DUET;
        }

        @Override public int getItemCount() {
            return items.size() + (showFooter ? 1 : 0);
        }

        @NonNull @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inf = LayoutInflater.from(parent.getContext());
            if (viewType == VIEW_TYPE_FOOTER) {
                // Inline footer: centered ProgressBar
                FrameLayout fl = new FrameLayout(parent.getContext());
                ProgressBar pb = new ProgressBar(parent.getContext());
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
                lp.gravity = android.view.Gravity.CENTER;
                pb.setLayoutParams(lp);
                fl.addView(pb);
                fl.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 120));
                return new FooterVH(fl);
            }
            View v = inf.inflate(R.layout.item_reel_grid, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int pos) {
            if (holder instanceof FooterVH) return;
            VH h = (VH) holder;
            ReelModel reel = items.get(pos);

            Glide.with(h.thumbnail.getContext())
                .load(reel.thumbUrl != null ? reel.thumbUrl : reel.thumbnailUrl)
                .centerCrop()
                .placeholder(R.drawable.bg_skeleton_rect)
                .into(h.thumbnail);

            h.tvCreator.setText(reel.ownerName != null ? "@" + reel.ownerName : "");
            h.tvViews.setText(formatCount(reel.viewsCount));
            h.badgeDuet.setVisibility(View.VISIBLE);
            h.itemView.setOnClickListener(v -> listener.onClick(reel));
        }

        private static String formatCount(int n) {
            if (n >= 1_000_000) return String.format("%.1fM", n / 1_000_000f);
            if (n >= 1_000)     return String.format("%.1fK", n / 1_000f);
            return String.valueOf(n);
        }

        static class VH extends RecyclerView.ViewHolder {
            ImageView thumbnail;
            TextView  tvCreator, tvViews;
            View      badgeDuet;
            VH(View v) {
                super(v);
                thumbnail = v.findViewById(R.id.iv_reel_thumb);
                tvCreator = v.findViewById(R.id.tv_reel_creator);
                tvViews   = v.findViewById(R.id.tv_reel_views);
                badgeDuet = v.findViewById(R.id.badge_duet);
            }
        }

        static class FooterVH extends RecyclerView.ViewHolder {
            FooterVH(View v) { super(v); }
        }
    }
}
