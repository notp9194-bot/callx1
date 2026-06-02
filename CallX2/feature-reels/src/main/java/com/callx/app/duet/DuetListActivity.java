package com.callx.app.duet;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.callx.app.activities.SingleReelPlayerActivity;
import com.callx.app.models.ReelModel;
import com.callx.app.reels.R;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.database.FirebaseDatabase;

import java.util.ArrayList;
import java.util.List;

/**
 * DuetListActivity — Shows all duets created from a specific reel.
 *
 * Launched from:
 *  - ReelMoreBottomSheet → ACTION_VIEW_DUETS
 *  - Duet count badge tap in SingleReelPlayerActivity
 *
 * Extras:
 *  EXTRA_REEL_ID     → original reel ID
 *  EXTRA_REEL_OWNER  → original reel owner's display name
 */
public class DuetListActivity extends AppCompatActivity {

    public static final String EXTRA_REEL_ID    = "duet_list_reel_id";
    public static final String EXTRA_REEL_OWNER = "duet_list_owner_name";

    private RecyclerView recyclerView;
    private ProgressBar  progress;
    private TextView     tvEmpty, tvTitle;
    private ImageButton  btnBack;

    private String reelId;
    private String ownerName;
    private final List<ReelModel> duets = new ArrayList<>();
    private DuetAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_duet_list);

        reelId    = getIntent().getStringExtra(EXTRA_REEL_ID);
        ownerName = getIntent().getStringExtra(EXTRA_REEL_OWNER);
        if (ownerName == null) ownerName = "";

        bindViews();
        setupRecycler();
        loadDuets();
    }

    private void bindViews() {
        recyclerView = findViewById(R.id.rv_duets);
        progress     = findViewById(R.id.progress_duets);
        tvEmpty      = findViewById(R.id.tv_duets_empty);
        tvTitle      = findViewById(R.id.tv_duets_title);
        btnBack      = findViewById(R.id.btn_duets_back);

        String title = ownerName.isEmpty() ? "Duets" : "Duets of @" + ownerName;
        tvTitle.setText(title);
        btnBack.setOnClickListener(v -> finish());
    }

    private void setupRecycler() {
        adapter = new DuetAdapter(duets, reel -> {
            if (reel == null || reel.reelId == null) return;
            Intent i = new Intent(this, SingleReelPlayerActivity.class);
            i.putExtra("reel_id", reel.reelId);
            startActivity(i);
        });
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);
    }

    private void loadDuets() {
        if (reelId == null) { showEmpty(); return; }
        progress.setVisibility(View.VISIBLE);

        DuetFirebaseHelper.loadDuetsOfReel(reelId, 50, duetIds -> {
            if (duetIds.isEmpty()) { showEmpty(); return; }
            loadReelModels(duetIds);
        });
    }

    private void loadReelModels(List<String> ids) {
        final int[] remaining = {ids.size()};
        for (String id : ids) {
            FirebaseDatabase.getInstance().getReference("reels")
                .child("videos").child(id)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        ReelModel model = snap.getValue(ReelModel.class);
                        if (model != null) { duets.add(model); adapter.notifyItemInserted(duets.size() - 1); }
                        if (--remaining[0] <= 0) showLoaded();
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {
                        if (--remaining[0] <= 0) showLoaded();
                    }
                });
        }
    }

    private void showEmpty() {
        runOnUiThread(() -> {
            progress.setVisibility(View.GONE);
            tvEmpty.setVisibility(View.VISIBLE);
        });
    }

    private void showLoaded() {
        runOnUiThread(() -> {
            progress.setVisibility(View.GONE);
            if (duets.isEmpty()) tvEmpty.setVisibility(View.VISIBLE);
        });
    }

    // ── Adapter ──────────────────────────────────────────────────────────────

    public interface OnDuetClickListener { void onClick(ReelModel reel); }

    static class DuetAdapter extends RecyclerView.Adapter<DuetAdapter.VH> {
        private final List<ReelModel>    items;
        private final OnDuetClickListener listener;

        DuetAdapter(List<ReelModel> items, OnDuetClickListener listener) {
            this.items    = items;
            this.listener = listener;
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_duet_card, parent, false);
            return new VH(v);
        }

        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            ReelModel m = items.get(pos);
            h.tvName.setText(m.ownerName != null ? "@" + m.ownerName : "Unknown");
            h.tvCaption.setText(m.caption != null ? m.caption : "");

            // Format duet badge
            String badge = "Duet • " + formatCount(m.likesCount) + " likes";
            h.tvBadge.setText(badge);

            // Load thumbnail
            if (m.thumbUrl != null && !m.thumbUrl.isEmpty()) {
                Glide.with(h.ivThumb.getContext())
                    .load(m.thumbUrl)
                    .centerCrop()
                    .placeholder(android.R.color.darker_gray)
                    .into(h.ivThumb);
            }
            h.itemView.setOnClickListener(v -> listener.onClick(m));
        }

        @Override public int getItemCount() { return items.size(); }

        private String formatCount(int n) {
            if (n >= 1_000_000) return String.format("%.1fM", n / 1_000_000f);
            if (n >= 1_000)     return String.format("%.1fK", n / 1_000f);
            return String.valueOf(n);
        }

        static class VH extends RecyclerView.ViewHolder {
            ImageView ivThumb;
            TextView  tvName, tvCaption, tvBadge;
            VH(View v) {
                super(v);
                ivThumb   = v.findViewById(R.id.iv_duet_thumb);
                tvName    = v.findViewById(R.id.tv_duet_name);
                tvCaption = v.findViewById(R.id.tv_duet_caption);
                tvBadge   = v.findViewById(R.id.tv_duet_badge);
            }
        }
    }
}
