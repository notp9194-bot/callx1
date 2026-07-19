package com.callx.app.channel;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.db.entity.ChannelEntity;
import com.callx.app.status.R;
import com.callx.app.viewmodel.ChannelViewModel;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.textfield.TextInputEditText;
import de.hdodenhof.circleimageview.CircleImageView;
import java.util.ArrayList;
import java.util.List;

/**
 * ExploreChannelsActivity — WhatsApp-level channel discovery (v2).
 *
 * Features:
 *   - "🔥 Trending" section (top 5 by weeklyGrowth) — hidden when filtering
 *   - "All Channels" section — full list, filtered by search + category
 *   - Section headers in the recycler via SectionedAdapter (TYPE_HEADER / TYPE_CHANNEL)
 *   - Category chips: All | News | Sports | Tech | Entertainment | Music | Education | Business | Health
 *   - Follow / Unfollow inline
 *   - FAB → CreateChannelActivity
 */
public class ExploreChannelsActivity extends AppCompatActivity {

    private static final String[] CATEGORIES = {
        "All", "News", "Sports", "Tech", "Entertainment",
        "Music", "Education", "Business", "Health", "Other"
    };

    private ChannelViewModel viewModel;

    private final List<ChannelEntity> allChannels     = new ArrayList<>();
    private final List<ChannelEntity> trendingChannels= new ArrayList<>();
    private String currentQuery    = "";
    private String currentCategory = "All";

    private SectionedAdapter adapter;
    private TextInputEditText etSearch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_explore_channels);

        viewModel = new ViewModelProvider(this).get(ChannelViewModel.class);

        Toolbar toolbar = findViewById(R.id.toolbar_explore);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Explore Channels");
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        // Search
        etSearch = findViewById(R.id.et_explore_search);
        if (etSearch != null) {
            etSearch.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(Editable s) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                    currentQuery = s.toString().trim().toLowerCase();
                    rebuild();
                }
            });
        }

        // Category chips
        ChipGroup chipGroup = findViewById(R.id.chip_group_categories);
        if (chipGroup != null) {
            for (String cat : CATEGORIES) {
                Chip chip = new Chip(this);
                chip.setText(cat);
                chip.setCheckable(true);
                chip.setChecked("All".equals(cat));
                chip.setOnCheckedChangeListener((btn, checked) -> {
                    if (checked) {
                        currentCategory = cat;
                        rebuild();
                    }
                });
                chipGroup.addView(chip);
            }
        }

        // RecyclerView
        RecyclerView rv = findViewById(R.id.rv_explore_channels);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new SectionedAdapter();
        rv.setAdapter(adapter);

        // FAB → Create channel
        FloatingActionButton fab = findViewById(R.id.fab_create_channel);
        if (fab != null) {
            fab.setOnClickListener(v ->
                    startActivity(new Intent(this, CreateChannelActivity.class)));
        }

        // Observe data
        viewModel.getAllChannels(200).observe(this, channels -> {
            allChannels.clear();
            if (channels != null) allChannels.addAll(channels);
            rebuild();
        });

        viewModel.getTrendingChannels(5).observe(this, trending -> {
            trendingChannels.clear();
            if (trending != null) trendingChannels.addAll(trending);
            rebuild();
        });
    }

    private void rebuild() {
        boolean isFiltering = !currentQuery.isEmpty() || !"All".equals(currentCategory);
        adapter.buildAndSet(trendingChannels, allChannels,
                currentQuery, currentCategory, isFiltering);
    }

    // ── Sectioned adapter ─────────────────────────────────────────────────

    class SectionedAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private static final int TYPE_HEADER  = 0;
        private static final int TYPE_CHANNEL = 1;

        // Items: String = section header, ChannelEntity = channel row
        private final List<Object> items = new ArrayList<>();

        void buildAndSet(List<ChannelEntity> trending,
                          List<ChannelEntity> all,
                          String query, String category,
                          boolean isFiltering) {
            items.clear();

            // ── Trending section (only when not filtering) ────────────────
            if (!isFiltering && !trending.isEmpty()) {
                items.add("🔥 Trending");
                for (ChannelEntity ch : trending) items.add(ch);
            }

            // ── All channels section ──────────────────────────────────────
            List<ChannelEntity> filtered = new ArrayList<>();
            for (ChannelEntity ch : all) {
                boolean matchesQuery = query.isEmpty()
                        || ch.name.toLowerCase().contains(query)
                        || (ch.description != null && ch.description.toLowerCase().contains(query));
                boolean matchesCat  = "All".equals(category)
                        || category.equalsIgnoreCase(ch.category);
                if (matchesQuery && matchesCat) filtered.add(ch);
            }

            if (!filtered.isEmpty()) {
                items.add(isFiltering ? "Results" : "All Channels");
                for (ChannelEntity ch : filtered) items.add(ch);
            }

            if (items.isEmpty()) {
                items.add("No channels found");
            }

            notifyDataSetChanged();
        }

        @Override
        public int getItemViewType(int position) {
            return items.get(position) instanceof String ? TYPE_HEADER : TYPE_CHANNEL;
        }

        @NonNull @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inf = LayoutInflater.from(parent.getContext());
            if (viewType == TYPE_HEADER) {
                return new HeaderVH(inf.inflate(R.layout.item_section_header, parent, false));
            }
            return new ChannelVH(inf.inflate(R.layout.item_channel_explore, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int pos) {
            if (getItemViewType(pos) == TYPE_HEADER) {
                ((HeaderVH) holder).tvTitle.setText((String) items.get(pos));
            } else {
                bindChannel((ChannelVH) holder, (ChannelEntity) items.get(pos));
            }
        }

        private void bindChannel(ChannelVH h, ChannelEntity ch) {
            if (h.tvName != null) {
                String name = ch.verified ? ch.name + " ✓" : ch.name;
                h.tvName.setText(name);
            }
            if (h.tvFollowers != null)
                h.tvFollowers.setText(formatFollowers(ch.followers) + " followers");
            if (h.tvDescription != null) {
                boolean hasDesc = ch.description != null && !ch.description.isEmpty();
                h.tvDescription.setVisibility(hasDesc ? View.VISIBLE : View.GONE);
                if (hasDesc) h.tvDescription.setText(ch.description);
            }
            if (h.tvCategory != null) {
                boolean hasCat = ch.category != null && !ch.category.isEmpty();
                h.tvCategory.setVisibility(hasCat ? View.VISIBLE : View.GONE);
                if (hasCat) h.tvCategory.setText(ch.category);
            }
            if (h.ivIcon != null) {
                if (ch.iconUrl != null && !ch.iconUrl.isEmpty()) {
                    Glide.with(ExploreChannelsActivity.this).load(ch.iconUrl)
                            .circleCrop().into(h.ivIcon);
                } else {
                    h.ivIcon.setImageResource(R.drawable.bg_channel_avatar_default);
                }
            }

            // Follow button
            if (h.btnFollow != null) {
                h.btnFollow.setText(ch.isFollowed ? "Following" : "Follow");
                h.btnFollow.setAlpha(ch.isFollowed ? 0.6f : 1.0f);
                h.btnFollow.setOnClickListener(v -> {
                    if (ch.isFollowed) {
                        viewModel.unfollowChannel(ch);
                    } else {
                        viewModel.followChannel(ch);
                    }
                });
            }

            // Tap → open viewer
            h.itemView.setOnClickListener(v -> {
                Intent intent = new Intent(ExploreChannelsActivity.this, ChannelViewerActivity.class);
                intent.putExtra(ChannelViewerActivity.EXTRA_CHANNEL_ID,    ch.id);
                intent.putExtra(ChannelViewerActivity.EXTRA_CHANNEL_NAME,  ch.name);
                intent.putExtra(ChannelViewerActivity.EXTRA_CHANNEL_ICON,  ch.iconUrl);
                intent.putExtra(ChannelViewerActivity.EXTRA_OWNER_UID,     ch.ownerUid);
                startActivity(intent);
            });
        }

        private String formatFollowers(long n) {
            if (n >= 1_000_000) return String.format("%.1fM", n / 1_000_000.0);
            if (n >= 1_000)     return String.format("%.1fK", n / 1_000.0);
            return String.valueOf(n);
        }

        @Override public int getItemCount() { return items.size(); }

        class HeaderVH extends RecyclerView.ViewHolder {
            TextView tvTitle;
            HeaderVH(View v) {
                super(v);
                tvTitle = v.findViewById(R.id.tv_section_title);
            }
        }

        class ChannelVH extends RecyclerView.ViewHolder {
            CircleImageView ivIcon;
            TextView   tvName, tvFollowers, tvDescription, tvCategory;
            Button     btnFollow;
            ChannelVH(View v) {
                super(v);
                ivIcon        = v.findViewById(R.id.iv_channel_icon);
                tvName        = v.findViewById(R.id.tv_channel_name);
                tvFollowers   = v.findViewById(R.id.tv_channel_followers);
                tvDescription = v.findViewById(R.id.tv_channel_description);
                tvCategory    = v.findViewById(R.id.tv_channel_category);
                btnFollow     = v.findViewById(R.id.btn_follow_channel);
            }
        }
    }
}
