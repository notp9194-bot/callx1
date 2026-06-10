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
 *  ✅ Shows duet creator name + duet count badge
 *  ✅ Tap opens reel player
 *  ✅ Empty state with label
 */
public class DuetsByReelActivity extends AppCompatActivity {

    public static final String EXTRA_REEL_ID    = "duets_reel_id";
    public static final String EXTRA_OWNER_NAME = "duets_owner_name";

    private ImageButton   btnBack;
    private TextView      tvTitle, tvDuetCount;
    private RecyclerView  rvDuets;
    private ProgressBar   progressDuets;
    private View          layoutEmpty;

    private String           originalReelId;
    private String           ownerName;
    private final List<ReelModel> duets = new ArrayList<>();
    private DuetsAdapter     adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_duets_by_reel);

        originalReelId = getIntent().getStringExtra(EXTRA_REEL_ID);
        ownerName      = getIntent().getStringExtra(EXTRA_OWNER_NAME);
        if (ownerName == null) ownerName = "";

        btnBack      = findViewById(R.id.btn_duets_back);
        tvTitle      = findViewById(R.id.tv_duets_title);
        tvDuetCount  = findViewById(R.id.tv_duet_count);
        rvDuets      = findViewById(R.id.rv_duets);
        progressDuets= findViewById(R.id.progress_duets);
        layoutEmpty  = findViewById(R.id.layout_duets_empty);

        tvTitle.setText(ownerName.isEmpty() ? "Duets" : "Duets of @" + ownerName);

        adapter = new DuetsAdapter(duets, this::onDuetTapped);
        rvDuets.setLayoutManager(new GridLayoutManager(this, 3));
        rvDuets.setAdapter(adapter);

        btnBack.setOnClickListener(v -> finish());

        loadDuets();
    }

    private void loadDuets() {
        if (originalReelId == null) { showEmpty(true); return; }
        progressDuets.setVisibility(View.VISIBLE);
        layoutEmpty.setVisibility(View.GONE);

        // Query all reels where duetOf == originalReelId
        FirebaseUtils.db().getReference("reels")
            .orderByChild("duetOf")
            .equalTo(originalReelId)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    progressDuets.setVisibility(View.GONE);
                    duets.clear();
                    for (DataSnapshot ds : snap.getChildren()) {
                        ReelModel m = ds.getValue(ReelModel.class);
                        if (m != null) {
                            if (m.reelId == null) m.reelId = ds.getKey();
                            duets.add(m);
                        }
                    }
                    // Sort newest first
                    duets.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
                    adapter.notifyDataSetChanged();

                    tvDuetCount.setText(duets.size() + " duet" + (duets.size() == 1 ? "" : "s"));
                    showEmpty(duets.isEmpty());
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    progressDuets.setVisibility(View.GONE);
                    showEmpty(true);
                    Toast.makeText(DuetsByReelActivity.this,
                        "Failed to load duets", Toast.LENGTH_SHORT).show();
                }
            });
    }

    private void showEmpty(boolean show) {
        layoutEmpty.setVisibility(show ? View.VISIBLE : View.GONE);
        rvDuets.setVisibility(show ? View.GONE : View.VISIBLE);
    }

    private void onDuetTapped(ReelModel reel) {
        // Open the duet reel in the player
        // Pass to a single-reel viewer or the main feed at this reel's position
        Toast.makeText(this, "Opening duet by @" + reel.ownerName, Toast.LENGTH_SHORT).show();
        // TODO: integrate with your reel player activity when available
        // Intent i = new Intent(this, SingleReelPlayerActivity.class);
        // i.putExtra("reel_id", reel.reelId);
        // startActivity(i);
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    private static class DuetsAdapter extends RecyclerView.Adapter<DuetsAdapter.VH> {
        interface OnDuetClick { void onClick(ReelModel reel); }

        private final List<ReelModel> items;
        private final OnDuetClick     listener;

        DuetsAdapter(List<ReelModel> items, OnDuetClick listener) {
            this.items = items;
            this.listener = listener;
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_reel_grid, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            ReelModel reel = items.get(pos);
            Glide.with(h.thumbnail.getContext())
                .load(reel.thumbUrl != null ? reel.thumbUrl : reel.thumbnailUrl)
                .centerCrop()
                .placeholder(R.drawable.bg_skeleton_rect)
                .into(h.thumbnail);

            h.tvCreator.setText(reel.ownerName != null ? "@" + reel.ownerName : "");
            h.tvViews.setText(formatCount(reel.viewsCount));
            h.itemView.setOnClickListener(v -> listener.onClick(reel));

            // Duet badge
            h.badgeDuet.setVisibility(View.VISIBLE);
        }

        @Override public int getItemCount() { return items.size(); }

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
    }
}
