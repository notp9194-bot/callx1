package com.callx.app.social;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.callx.app.player.SingleReelPlayerActivity;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;

import java.util.ArrayList;
import java.util.List;

/**
 * RemixesByReelActivity — Shows all remixes of a specific reel
 *
 * Usage:
 *   Intent i = new Intent(ctx, RemixesByReelActivity.class);
 *   i.putExtra(EXTRA_REEL_ID, reelId);
 *   i.putExtra(EXTRA_OWNER_NAME, ownerName);
 *   startActivity(i);
 *
 * Firebase path: reelRemixes/{reelId}/{remixReelId}
 */
public class RemixesByReelActivity extends AppCompatActivity {

    public static final String EXTRA_REEL_ID    = "reelId";
    public static final String EXTRA_OWNER_NAME = "ownerName";

    private RecyclerView       rvRemixes;
    private ProgressBar        progressBar;
    private LinearLayout       layoutEmpty;
    private RemixListAdapter   adapter;
    private final List<ReelRemixModel> remixes = new ArrayList<>();
    private ValueEventListener listener;
    private String             reelId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_remixes_by_reel);

        reelId = getIntent().getStringExtra(EXTRA_REEL_ID);
        String ownerName = getIntent().getStringExtra(EXTRA_OWNER_NAME);

        Toolbar tb = findViewById(R.id.toolbar);
        setSupportActionBar(tb);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Remixes of @" + ownerName);
        }
        tb.setNavigationOnClickListener(v -> finish());

        rvRemixes   = findViewById(R.id.rv_remixes);
        progressBar = findViewById(R.id.progress_remixes);
        layoutEmpty = findViewById(R.id.layout_empty_remixes);

        adapter = new RemixListAdapter(remixes);
        rvRemixes.setLayoutManager(new LinearLayoutManager(this));
        rvRemixes.setAdapter(adapter);

        loadRemixes();
    }

    private void loadRemixes() {
        progressBar.setVisibility(View.VISIBLE);
        listener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snap) {
                remixes.clear();
                for (DataSnapshot s : snap.getChildren()) {
                    ReelRemixModel m = s.getValue(ReelRemixModel.class);
                    if (m != null) remixes.add(m);
                }
                remixes.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
                progressBar.setVisibility(View.GONE);
                layoutEmpty.setVisibility(remixes.isEmpty() ? View.VISIBLE : View.GONE);
                adapter.notifyDataSetChanged();
            }

            @Override public void onCancelled(@NonNull DatabaseError e) {
                progressBar.setVisibility(View.GONE);
            }
        };
        FirebaseUtils.db()
            .getReference("reelRemixes")
            .child(reelId)
            .addValueEventListener(listener);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (listener != null)
            FirebaseUtils.db().getReference("reelRemixes").child(reelId)
                .removeEventListener(listener);
    }

    // ── Inner adapter ─────────────────────────────────────────────────────────

    private class RemixListAdapter
        extends RecyclerView.Adapter<RemixListAdapter.VH> {

        private final List<ReelRemixModel> data;
        RemixListAdapter(List<ReelRemixModel> d) { this.data = d; }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_remix_card, parent, false);
            return new VH(v);
        }

        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            ReelRemixModel m = data.get(pos);

            h.tvRemixerName.setText("@" + m.remixerName);
            h.tvLayoutMode.setText(layoutLabel(m.layoutMode));
            h.tvCaption.setText(m.remixCaption != null ? m.remixCaption : "");
            h.tvStats.setText(m.viewsCount + " views · " + m.likesCount + " likes");

            if (m.remixThumbUrl != null && !m.remixThumbUrl.isEmpty()) {
                Glide.with(h.ivThumb).load(m.remixThumbUrl)
                    .placeholder(R.drawable.bg_reel_comment_btn)
                    .override(480, 853)
                    .into(h.ivThumb);
            }

            Glide.with(h.ivAvatar).load(m.remixerPhoto)
                .placeholder(R.drawable.ic_person)
                .circleCrop()
                .override(96, 96)
                .into(h.ivAvatar);

            h.itemView.setOnClickListener(v -> {
                Intent intent = new Intent(RemixesByReelActivity.this,
                    SingleReelPlayerActivity.class);
                intent.putExtra(SingleReelPlayerActivity.EXTRA_REEL_ID, m.remixReelId);
                startActivity(intent);
            });
        }

        @Override public int getItemCount() { return data.size(); }

        private String layoutLabel(String mode) {
            if (mode == null) return "Remix";
            switch (mode) {
                case ReelRemixActivity.LAYOUT_REACT_CAM:    return "React Cam";
                case ReelRemixActivity.LAYOUT_GREEN_SCREEN: return "Green Screen";
                case ReelRemixActivity.LAYOUT_OVERLAY:      return "Overlay";
                default:                                    return "Side by Side";
            }
        }

        class VH extends RecyclerView.ViewHolder {
            ImageView ivThumb, ivAvatar;
            TextView  tvRemixerName, tvLayoutMode, tvCaption, tvStats;

            VH(@NonNull View v) {
                super(v);
                ivThumb       = v.findViewById(R.id.iv_remix_thumb);
                ivAvatar      = v.findViewById(R.id.iv_remix_avatar);
                tvRemixerName = v.findViewById(R.id.tv_remix_remixer_name);
                tvLayoutMode  = v.findViewById(R.id.tv_remix_layout_mode);
                tvCaption     = v.findViewById(R.id.tv_remix_caption);
                tvStats       = v.findViewById(R.id.tv_remix_stats);
            }
        }
    }
}
