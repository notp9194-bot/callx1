package com.callx.app.channel;

import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.status.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.firebase.database.*;
import de.hdodenhof.circleimageview.CircleImageView;
import java.util.*;

/**
 * ChannelReactionsDetailActivity — WhatsApp-level reaction detail sheet.
 *
 * Shows who reacted with which emoji on a channel post.
 * Features:
 *   - "All" tab showing every reactor + emoji
 *   - Per-emoji filter chips (👍 3, ❤️ 2, etc.)
 *   - User names + avatars loaded from Firebase
 */
public class ChannelReactionsDetailActivity extends AppCompatActivity {

    public static final String EXTRA_REACTIONS_JSON = "reactionsJson";
    public static final String EXTRA_POST_ID        = "postId";

    private List<ReactorEntry>        allReactors  = new ArrayList<>();
    private List<ReactorEntry>        shownReactors = new ArrayList<>();
    private ReactionDetailAdapter     adapter;
    private String                    filterEmoji  = null; // null = show all

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_channel_reactions_detail);

        String reactionsJson = getIntent().getStringExtra(EXTRA_REACTIONS_JSON);

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

        // Parse reactions JSON
        Map<String, String> reactionsMap = parseReactionsJson(reactionsJson);
        if (reactionsMap.isEmpty()) { finish(); return; }

        // Build emoji → count map for filter chips
        Map<String, Integer> emojiCounts = new LinkedHashMap<>();
        for (String emoji : reactionsMap.values()) {
            emojiCounts.put(emoji, emojiCounts.getOrDefault(emoji, 0) + 1);
        }

        // "All" chip
        if (chipGroup != null) {
            Chip allChip = new Chip(this);
            allChip.setText("All " + reactionsMap.size());
            allChip.setCheckable(true);
            allChip.setChecked(true);
            allChip.setOnCheckedChangeListener((btn, checked) -> {
                if (checked) { filterEmoji = null; applyFilter(); }
            });
            chipGroup.addView(allChip);

            // Per-emoji chips
            for (Map.Entry<String, Integer> e : emojiCounts.entrySet()) {
                Chip chip = new Chip(this);
                chip.setText(e.getKey() + " " + e.getValue());
                chip.setCheckable(true);
                final String emoji = e.getKey();
                chip.setOnCheckedChangeListener((btn, checked) -> {
                    if (checked) { filterEmoji = emoji; applyFilter(); }
                });
                chipGroup.addView(chip);
            }
        }

        // Build reactor list
        for (Map.Entry<String, String> e : reactionsMap.entrySet()) {
            allReactors.add(new ReactorEntry(e.getKey(), e.getValue()));
        }
        applyFilter();
        resolveNames();
    }

    private void applyFilter() {
        shownReactors.clear();
        for (ReactorEntry r : allReactors) {
            if (filterEmoji == null || filterEmoji.equals(r.emoji)) shownReactors.add(r);
        }
        adapter.notifyDataSetChanged();
    }

    private void resolveNames() {
        if (allReactors.isEmpty()) return;
        final int[] done = {0};
        for (ReactorEntry r : allReactors) {
            FirebaseUtils.getUserRef(r.uid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        r.name    = snap.child("name").getValue(String.class);
                        r.iconUrl = snap.child("photoUrl").getValue(String.class);
                        if (r.name == null) r.name = r.uid.substring(0, 8) + "…";
                        done[0]++;
                        if (done[0] >= allReactors.size()) adapter.notifyDataSetChanged();
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {
                        r.name = r.uid.substring(0, 8) + "…";
                        done[0]++;
                        if (done[0] >= allReactors.size()) adapter.notifyDataSetChanged();
                    }
                });
        }
    }

    // ── Adapter ───────────────────────────────────────────────────────────

    static class ReactionDetailAdapter extends RecyclerView.Adapter<ReactionDetailAdapter.VH> {
        private final List<ReactorEntry> list;
        ReactionDetailAdapter(List<ReactorEntry> list) { this.list = list; }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_reaction_detail, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            ReactorEntry r = list.get(pos);
            h.tvName.setText(r.name != null ? r.name : r.uid);
            h.tvEmoji.setText(r.emoji);
            if (h.ivAvatar != null && r.iconUrl != null && !r.iconUrl.isEmpty())
                Glide.with(h.itemView.getContext()).load(r.iconUrl).circleCrop().into(h.ivAvatar);
        }

        @Override public int getItemCount() { return list.size(); }

        static class VH extends RecyclerView.ViewHolder {
            CircleImageView ivAvatar;
            TextView        tvName, tvEmoji;
            VH(View v) {
                super(v);
                ivAvatar = v.findViewById(R.id.iv_reactor_avatar);
                tvName   = v.findViewById(R.id.tv_reactor_name);
                tvEmoji  = v.findViewById(R.id.tv_reactor_emoji);
            }
        }
    }

    // ── Data class ────────────────────────────────────────────────────────

    static class ReactorEntry {
        String uid, emoji, name, iconUrl;
        ReactorEntry(String uid, String emoji) { this.uid = uid; this.emoji = emoji; }
    }

    // ── JSON parsing ──────────────────────────────────────────────────────

    private Map<String, String> parseReactionsJson(String json) {
        Map<String, String> map = new LinkedHashMap<>();
        try {
            if (json == null || json.trim().isEmpty() || "{}".equals(json.trim())) return map;
            String s = json.trim();
            if (s.startsWith("{")) s = s.substring(1);
            if (s.endsWith("}"))   s = s.substring(0, s.length() - 1);
            for (String entry : s.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)")) {
                int colon = entry.lastIndexOf(":");
                if (colon > 0) {
                    String k = entry.substring(0, colon).replaceAll("\"", "").trim();
                    String v = entry.substring(colon + 1).replaceAll("\"", "").trim();
                    if (!k.isEmpty()) map.put(k, v);
                }
            }
        } catch (Exception ignored) {}
        return map;
    }
}
