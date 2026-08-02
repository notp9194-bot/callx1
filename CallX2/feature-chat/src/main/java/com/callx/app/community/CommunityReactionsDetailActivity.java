package com.callx.app.community;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.callx.app.chat.R;
import com.callx.app.repository.CommunityRepository;
import com.callx.app.utils.FirebaseUtils;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.hdodenhof.circleimageview.CircleImageView;

/**
 * Community post reaction detail sheet — WhatsApp/Facebook-style "who
 * reacted" screen. Was previously a no-op (tapping the reaction count on a
 * community post did nothing); this reads the post's per-user reaction map
 * directly from Firebase (communities/{id}/posts/{id}/reactions/{uid}),
 * resolves each uid's name/photo, and shows an "All" + per-emoji filter
 * chip row above the list — mirrors ChannelReactionsDetailActivity's
 * pattern used for X/channel posts.
 */
public class CommunityReactionsDetailActivity extends AppCompatActivity {

    public static final String EXTRA_COMMUNITY_ID = "communityId";
    public static final String EXTRA_POST_ID       = "postId";

    private final List<ReactorEntry> allReactors   = new ArrayList<>();
    private final List<ReactorEntry> shownReactors = new ArrayList<>();
    private ReactionDetailAdapter    adapter;
    private String                   filterType    = null; // null = show all

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_community_reactions_detail);

        String communityId = getIntent().getStringExtra(EXTRA_COMMUNITY_ID);
        String postId       = getIntent().getStringExtra(EXTRA_POST_ID);

        Toolbar toolbar = findViewById(R.id.toolbar_reactions_detail);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Reactions");
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        RecyclerView rv = findViewById(R.id.rv_reactions);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ReactionDetailAdapter(shownReactors);
        rv.setAdapter(adapter);

        ChipGroup chipGroup = findViewById(R.id.chip_group_emoji_filter);

        if (communityId == null || postId == null) { finish(); return; }

        CommunityRepository.getInstance(this)
                .getPostReactionsRef(communityId, postId)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snap) {
                        if (!snap.exists()) { finish(); return; }

                        Map<String, String> uidToType = new LinkedHashMap<>();
                        for (DataSnapshot child : snap.getChildren()) {
                            String type = child.getValue(String.class);
                            if (type != null) uidToType.put(child.getKey(), type);
                        }
                        if (uidToType.isEmpty()) { finish(); return; }

                        // Build type -> count for the filter chips
                        Map<String, Integer> typeCounts = new LinkedHashMap<>();
                        for (String type : uidToType.values()) {
                            typeCounts.put(type, typeCounts.getOrDefault(type, 0) + 1);
                        }

                        if (chipGroup != null) {
                            Chip allChip = new Chip(CommunityReactionsDetailActivity.this);
                            allChip.setText("All " + uidToType.size());
                            allChip.setCheckable(true);
                            allChip.setChecked(true);
                            allChip.setOnCheckedChangeListener((btn, checked) -> {
                                if (checked) { filterType = null; applyFilter(); }
                            });
                            chipGroup.addView(allChip);

                            for (Map.Entry<String, Integer> e : typeCounts.entrySet()) {
                                Chip chip = new Chip(CommunityReactionsDetailActivity.this);
                                chip.setText(CommunityReaction.getEmoji(e.getKey()) + " " + e.getValue());
                                chip.setCheckable(true);
                                final String type = e.getKey();
                                chip.setOnCheckedChangeListener((btn, checked) -> {
                                    if (checked) { filterType = type; applyFilter(); }
                                });
                                chipGroup.addView(chip);
                            }
                        }

                        for (Map.Entry<String, String> e : uidToType.entrySet()) {
                            allReactors.add(new ReactorEntry(e.getKey(), e.getValue()));
                        }
                        applyFilter();
                        resolveNames();
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        finish();
                    }
                });
    }

    private void applyFilter() {
        shownReactors.clear();
        for (ReactorEntry r : allReactors) {
            if (filterType == null || filterType.equals(r.type)) shownReactors.add(r);
        }
        adapter.notifyDataSetChanged();
    }

    private void resolveNames() {
        if (allReactors.isEmpty()) return;
        final int[] done = {0};
        for (ReactorEntry r : allReactors) {
            FirebaseUtils.getUserRef(r.uid)
                    .addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override
                        public void onDataChange(@NonNull DataSnapshot snap) {
                            r.name    = snap.child("name").getValue(String.class);
                            r.photoUrl = snap.child("photoUrl").getValue(String.class);
                            if (r.name == null) r.name = r.uid.length() > 8 ? r.uid.substring(0, 8) + "…" : r.uid;
                            done[0]++;
                            if (done[0] >= allReactors.size()) adapter.notifyDataSetChanged();
                        }

                        @Override
                        public void onCancelled(@NonNull DatabaseError error) {
                            r.name = r.uid.length() > 8 ? r.uid.substring(0, 8) + "…" : r.uid;
                            done[0]++;
                            if (done[0] >= allReactors.size()) adapter.notifyDataSetChanged();
                        }
                    });
        }
    }

    // ── Adapter ──────────────────────────────────────────────────────────

    static class ReactionDetailAdapter extends RecyclerView.Adapter<ReactionDetailAdapter.VH> {
        private final List<ReactorEntry> list;

        ReactionDetailAdapter(List<ReactorEntry> list) { this.list = list; }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_community_reaction_detail, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            ReactorEntry r = list.get(pos);
            h.tvName.setText(r.name != null ? r.name : r.uid);
            h.tvEmoji.setText(CommunityReaction.getEmoji(r.type));
            if (r.photoUrl != null && !r.photoUrl.isEmpty()) {
                Glide.with(h.itemView.getContext()).load(r.photoUrl).circleCrop().into(h.ivAvatar);
            }
        }

        @Override
        public int getItemCount() { return list.size(); }

        static class VH extends RecyclerView.ViewHolder {
            CircleImageView ivAvatar;
            TextView tvName, tvEmoji;

            VH(View v) {
                super(v);
                ivAvatar = v.findViewById(R.id.iv_reactor_avatar);
                tvName   = v.findViewById(R.id.tv_reactor_name);
                tvEmoji  = v.findViewById(R.id.tv_reactor_emoji);
            }
        }
    }

    // ── Data class ───────────────────────────────────────────────────────

    static class ReactorEntry {
        final String uid;
        final String type;
        String name;
        String photoUrl;

        ReactorEntry(String uid, String type) {
            this.uid = uid;
            this.type = type;
        }
    }
}
