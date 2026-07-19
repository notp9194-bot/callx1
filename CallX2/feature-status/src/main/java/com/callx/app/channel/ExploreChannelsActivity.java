package com.callx.app.channel;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
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
import de.hdodenhof.circleimageview.CircleImageView;
import java.util.*;

/**
 * ExploreChannelsActivity v2 — WhatsApp-level architecture.
 *
 * CHANGED: Observes ChannelViewModel LiveData instead of calling Firebase directly.
 * Data flow: Firebase → ChannelRepository → Room → ChannelViewModel.allChannels → this UI.
 */
public class ExploreChannelsActivity extends AppCompatActivity {

    private ExploreAdapter adapter;
    private EditText        etSearch;
    private String          selectedCategory = null;
    private ChannelViewModel viewModel;

    private final List<ChannelEntity> allChannels = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_explore_channels);

        viewModel = new ViewModelProvider(this).get(ChannelViewModel.class);

        Toolbar toolbar = findViewById(R.id.toolbar_explore);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Channels");
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        etSearch = findViewById(R.id.et_explore_search);
        ChipGroup chipGroup = findViewById(R.id.chip_group_categories);
        RecyclerView rv = findViewById(R.id.rv_explore_channels);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ExploreAdapter();
        rv.setAdapter(adapter);

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                filterAndShow(s.toString().trim().toLowerCase());
            }
        });

        setupCategoryChips(chipGroup);

        // ── Observe ChannelViewModel LiveData (WhatsApp-level pattern) ────
        viewModel.getAllChannels(50).observe(this, channels -> {
            allChannels.clear();
            if (channels != null) allChannels.addAll(channels);
            filterAndShow(etSearch.getText().toString().trim().toLowerCase());
        });

        // Trigger Firebase → Room sync
        viewModel.refresh();
    }

    private void setupCategoryChips(ChipGroup chipGroup) {
        String[] categories = {"All", "Entertainment", "Sports", "News",
                "Education", "Technology", "Music", "Business"};
        for (String cat : categories) {
            Chip chip = new Chip(this);
            chip.setText(cat);
            chip.setCheckable(true);
            chip.setCheckedIconVisible(false);
            chip.setChipBackgroundColorResource(R.color.chip_bg_selector);
            chip.setTextColor(getResources().getColorStateList(R.color.chip_text_selector));
            if ("All".equals(cat)) chip.setChecked(true);
            chip.setOnCheckedChangeListener((btn, checked) -> {
                if (checked) {
                    selectedCategory = "All".equals(cat) ? null : cat;
                    filterAndShow(etSearch.getText().toString().trim().toLowerCase());
                }
            });
            chipGroup.addView(chip);
        }
    }

    private void filterAndShow(String query) {
        List<ChannelEntity> filtered = new ArrayList<>();
        for (ChannelEntity ch : allChannels) {
            boolean matchCat = selectedCategory == null
                || selectedCategory.equals(ch.category);
            boolean matchQ   = query.isEmpty()
                || (ch.name != null && ch.name.toLowerCase().contains(query))
                || (ch.description != null && ch.description.toLowerCase().contains(query));
            if (matchCat && matchQ) filtered.add(ch);
        }
        adapter.setChannels(filtered);
    }

    // ── Inline Adapter ────────────────────────────────────────────────────
    class ExploreAdapter extends RecyclerView.Adapter<ExploreAdapter.VH> {

        private final List<ChannelEntity> list = new ArrayList<>();

        void setChannels(List<ChannelEntity> channels) {
            list.clear();
            list.addAll(channels);
            notifyDataSetChanged();
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull android.view.ViewGroup parent, int vt) {
            return new VH(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_channel_suggested, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            ChannelEntity ch = list.get(pos);
            h.tvName.setText(ch.name != null ? ch.name : "");
            long f = ch.followers;
            h.tvFollowers.setText(f >= 1_000_000L ? String.format("%.1fM", f/1_000_000.0)
                                : f >= 1_000L     ? String.format("%.1fK", f/1_000.0)
                                : String.valueOf(f));
            h.tvVerified.setVisibility(ch.verified ? View.VISIBLE : View.GONE);
            if (ch.iconUrl != null && !ch.iconUrl.isEmpty()) {
                Glide.with(h.itemView.getContext()).load(ch.iconUrl)
                    .placeholder(R.drawable.bg_channel_avatar_default)
                    .circleCrop().override(96, 96).into(h.ivIcon);
            } else {
                h.ivIcon.setImageResource(R.drawable.bg_channel_avatar_default);
            }

            h.btnFollow.setText(ch.isFollowed ? "Following" : "Follow");
            h.btnFollow.setAlpha(ch.isFollowed ? 0.6f : 1.0f);
            h.btnDismiss.setVisibility(View.GONE);

            h.btnFollow.setOnClickListener(v -> {
                if (!ch.isFollowed) viewModel.followChannel(ch);
                else viewModel.unfollowChannel(ch);
            });

            h.itemView.setOnClickListener(v -> {
                Intent i = new Intent(ExploreChannelsActivity.this, ChannelViewerActivity.class);
                i.putExtra(ChannelViewerActivity.EXTRA_CHANNEL_ID, ch.id);
                i.putExtra(ChannelViewerActivity.EXTRA_CHANNEL_NAME, ch.name);
                i.putExtra(ChannelViewerActivity.EXTRA_CHANNEL_ICON, ch.iconUrl);
                i.putExtra(ChannelViewerActivity.EXTRA_CHANNEL_VERIFIED, ch.verified);
                i.putExtra(ChannelViewerActivity.EXTRA_CHANNEL_FOLLOWERS, ch.followers);
                startActivity(i);
            });
        }

        @Override public int getItemCount() { return list.size(); }

        class VH extends RecyclerView.ViewHolder {
            CircleImageView ivIcon;
            TextView tvName, tvVerified, tvFollowers;
            Button btnFollow;
            ImageButton btnDismiss;
            VH(View v) {
                super(v);
                ivIcon      = v.findViewById(R.id.iv_channel_icon);
                tvName      = v.findViewById(R.id.tv_channel_name);
                tvVerified  = v.findViewById(R.id.tv_channel_verified);
                tvFollowers = v.findViewById(R.id.tv_channel_followers);
                btnFollow   = v.findViewById(R.id.btn_channel_follow);
                btnDismiss  = v.findViewById(R.id.btn_channel_dismiss);
            }
        }
    }
}
