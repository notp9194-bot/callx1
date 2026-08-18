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
import com.google.android.material.slider.RangeSlider;
import com.google.android.material.textfield.TextInputEditText;
import de.hdodenhof.circleimageview.CircleImageView;
import java.util.*;

/**
 * ExploreChannelsActivity — WhatsApp-level channel discovery screen (v5).
 *
 * v5 additions:
 *   ✓ NEW: Verified filter chip — show only verified channels (has verified badge)
 *   ✓ NEW: Follower-range filter — RangeSlider to filter by min–max follower count
 *   ✓ NEW: Near Me / Local filter chip — shows channels tagged with user's region
 *   ✓ Existing: Category chips, search by name, trending, new channels
 *   ✓ Follow / Unfollow in-list button
 *   ✓ Tapping a row opens ChannelViewerActivity
 *   ✓ Follower count formatted with K/M shorthand
 *   ✓ Verified badge icon shown on verified channels
 */
public class ExploreChannelsActivity extends AppCompatActivity {

    private ChannelViewModel viewModel;
    private ExploreAdapter   adapter;
    private final List<ChannelEntity> allChannels      = new ArrayList<>();
    private final List<ChannelEntity> filteredChannels = new ArrayList<>();

    // Active filters
    private String  activeCategory  = "All";
    private String  searchQuery     = "";
    private boolean filterVerified  = false;
    private long    minFollowers    = 0;
    private long    maxFollowers    = 10_000_000L;
    private boolean filterLocal     = false;

    // UI
    private TextInputEditText etSearch;
    private RangeSlider       rangeFollowers;
    private TextView          tvFollowerRange;
    private Chip              chipVerified, chipLocal;
    private View              layoutAdvancedFilters;
    private ImageButton       btnToggleFilters;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_explore_channels);

        viewModel = new ViewModelProvider(this).get(ChannelViewModel.class);

        Toolbar toolbar = findViewById(R.id.toolbar_explore);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Explore channels");
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        etSearch              = findViewById(R.id.et_explore_search);
        rangeFollowers        = findViewById(R.id.range_follower_count);
        tvFollowerRange       = findViewById(R.id.tv_follower_range_label);
        chipVerified          = findViewById(R.id.chip_verified_only);
        chipLocal             = findViewById(R.id.chip_local);
        layoutAdvancedFilters = findViewById(R.id.layout_advanced_filters);
        btnToggleFilters      = findViewById(R.id.btn_toggle_filters);

        // Category chips
        ChipGroup cgCategories = findViewById(R.id.chip_group_categories);
        String[] categories = {"All","News","Entertainment","Sports","Tech","Education",
                               "Music","Art","Health","Food","Travel","Business","Gaming","Local"};
        if (cgCategories != null) {
            for (String cat : categories) {
                Chip chip = new Chip(this);
                chip.setText(cat);
                chip.setCheckable(true);
                chip.setChecked("All".equals(cat));
                chip.setOnCheckedChangeListener((btn, checked) -> {
                    if (checked) { activeCategory = cat; filterLocal = "Local".equals(cat); applyFilters(); }
                });
                cgCategories.addView(chip);
            }
        }

        // NEW: Verified filter chip
        if (chipVerified != null) {
            chipVerified.setOnCheckedChangeListener((btn, checked) -> {
                filterVerified = checked; applyFilters();
            });
        }

        // NEW: Local / Near Me chip
        if (chipLocal != null) {
            chipLocal.setOnCheckedChangeListener((btn, checked) -> {
                filterLocal = checked; applyFilters();
            });
        }

        // NEW: Follower range slider
        if (rangeFollowers != null) {
            rangeFollowers.setValueFrom(0f);
            rangeFollowers.setValueTo(1_000_000f);
            rangeFollowers.setValues(0f, 1_000_000f);
            rangeFollowers.addOnChangeListener((slider, value, fromUser) -> {
                List<Float> vals = slider.getValues();
                minFollowers = vals.get(0).longValue();
                maxFollowers = vals.get(1).longValue();
                updateFollowerRangeLabel();
                applyFilters();
            });
        }

        // Toggle advanced filter panel
        if (btnToggleFilters != null) {
            btnToggleFilters.setOnClickListener(v -> {
                if (layoutAdvancedFilters != null) {
                    boolean visible = layoutAdvancedFilters.getVisibility() == View.VISIBLE;
                    layoutAdvancedFilters.setVisibility(visible ? View.GONE : View.VISIBLE);
                }
            });
        }

        // Search
        if (etSearch != null) {
            etSearch.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(Editable s) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                    searchQuery = s.toString().trim().toLowerCase();
                    if (searchQuery.isEmpty()) {
                        applyFilters();
                    } else {
                        viewModel.searchChannels(searchQuery);
                    }
                }
            });
        }

        RecyclerView rv = findViewById(R.id.rv_explore_channels);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ExploreAdapter();
        rv.setAdapter(adapter);

        // Observe suggested channels
        viewModel.suggestedChannels.observe(this, channels -> {
            allChannels.clear();
            if (channels != null) allChannels.addAll(channels);
            applyFilters();
        });

        // Observe search results
        viewModel.channelSearchResults.observe(this, channels -> {
            if (!searchQuery.isEmpty()) {
                filteredChannels.clear();
                if (channels != null) filteredChannels.addAll(channels);
                adapter.setData(filteredChannels);
            }
        });

        viewModel.toastMessage.observe(this, msg -> {
            if (msg != null && !msg.isEmpty()) Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        });
    }

    private void applyFilters() {
        filteredChannels.clear();
        for (ChannelEntity ch : allChannels) {
            // Category filter
            if (!"All".equals(activeCategory) && !activeCategory.equals(ch.category)) continue;
            // Search filter (redundant when search is active but kept for category+search combo)
            if (!searchQuery.isEmpty() && ch.name != null && !ch.name.toLowerCase().contains(searchQuery)) continue;
            // Verified filter (NEW)
            if (filterVerified && !ch.isVerified) continue;
            // Follower range filter (NEW)
            if (ch.followers < minFollowers || ch.followers > maxFollowers) continue;
            // Local/Near Me filter (NEW) — channels tagged with category "Local"
            if (filterLocal && !"Local".equalsIgnoreCase(ch.category)) continue;
            filteredChannels.add(ch);
        }
        adapter.setData(filteredChannels);
    }

    private void updateFollowerRangeLabel() {
        if (tvFollowerRange == null) return;
        tvFollowerRange.setText("Followers: " + formatCompact(minFollowers)
            + " – " + formatCompact(maxFollowers));
    }

    private String formatCompact(long n) {
        if (n >= 1_000_000L) return String.format("%.0fM", n / 1_000_000.0);
        if (n >= 1_000L)     return String.format("%.0fK", n / 1_000.0);
        return String.valueOf(n);
    }

    // ── ExploreAdapter ────────────────────────────────────────────────────

    class ExploreAdapter extends RecyclerView.Adapter<ExploreAdapter.VH> {
        private final List<ChannelEntity> data = new ArrayList<>();

        void setData(List<ChannelEntity> d) { data.clear(); data.addAll(d); notifyDataSetChanged(); }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_explore_channel, parent, false));
        }

        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            ChannelEntity ch = data.get(pos);
            if (h.tvName     != null) h.tvName.setText(ch.name != null ? ch.name : "");
            if (h.tvDesc     != null) h.tvDesc.setText(ch.description != null ? ch.description : "");
            if (h.tvFollowers!= null) h.tvFollowers.setText(formatCompact(ch.followers) + " followers");
            if (h.tvCategory != null) h.tvCategory.setText(ch.category != null ? ch.category : "");
            // Verified badge (NEW)
            if (h.ivVerified != null) h.ivVerified.setVisibility(ch.isVerified ? View.VISIBLE : View.GONE);
            if (h.ivIcon != null && ch.iconUrl != null && !ch.iconUrl.isEmpty())
                Glide.with(h.ivIcon.getContext()).load(ch.iconUrl).circleCrop().into(h.ivIcon);

            if (h.btnFollow != null) {
                h.btnFollow.setText(ch.isFollowing ? "Following" : "Follow");
                h.btnFollow.setOnClickListener(v -> {
                    if (ch.isFollowing) viewModel.unfollowChannel(ch);
                    else                viewModel.followChannel(ch);
                    ch.isFollowing = !ch.isFollowing;
                    notifyItemChanged(pos);
                });
            }

            h.itemView.setOnClickListener(v -> {
                Intent i = new Intent(ExploreChannelsActivity.this, ChannelViewerActivity.class);
                i.putExtra(ChannelViewerActivity.EXTRA_CHANNEL_ID,   ch.id);
                i.putExtra(ChannelViewerActivity.EXTRA_CHANNEL_NAME, ch.name);
                startActivity(i);
            });
        }

        @Override public int getItemCount() { return data.size(); }

        class VH extends RecyclerView.ViewHolder {
            CircleImageView ivIcon;
            ImageView       ivVerified;
            TextView        tvName, tvDesc, tvFollowers, tvCategory;
            com.google.android.material.button.MaterialButton btnFollow;
            VH(View v) {
                super(v);
                ivIcon      = v.findViewById(R.id.iv_explore_channel_icon);
                ivVerified  = v.findViewById(R.id.iv_explore_verified);
                tvName      = v.findViewById(R.id.tv_explore_channel_name);
                tvDesc      = v.findViewById(R.id.tv_explore_channel_desc);
                tvFollowers = v.findViewById(R.id.tv_explore_channel_followers);
                tvCategory  = v.findViewById(R.id.tv_explore_channel_category);
                btnFollow   = v.findViewById(R.id.btn_explore_follow);
            }
        }
    }
}
