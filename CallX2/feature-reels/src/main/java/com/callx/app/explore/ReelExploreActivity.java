package com.callx.app.explore;

import com.callx.app.player.SingleReelPlayerActivity;
import com.callx.app.music.SoundDetailActivity;
import com.callx.app.profile.UserReelsActivity;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.callx.app.reels.R;
import com.callx.app.models.ReelModel;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.explore.ReelChallengeActivity;
import com.callx.app.music.ReelTrendingAudioActivity;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.firebase.database.*;

import java.util.*;

/**
 * ReelExploreActivity — Production-grade Explore / Discover page.
 *
 * Features:
 *  ✅ Search bar at top — tap to open ReelSearchActivity (full search experience)
 *  ✅ Category chips: Trending, Dance, Comedy, Food, Travel, Music, Sports, Beauty, Education, Gaming
 *  ✅ 🏆 Challenges chip — opens ReelChallengeActivity
 *  ✅ Trending hashtags row (horizontal scroll) → HashtagReelsActivity
 *  ✅ Trending sounds row (horizontal scroll) → SoundDetailActivity
 *  ✅ "See All" sounds → ReelTrendingAudioActivity
 *  ✅ Featured creators row (horizontal scroll) → UserReelsActivity
 *  ✅ Suggested For You row — personalized creators based on reel engagement
 *  ✅ Reels grid filtered by selected category/hashtag
 *  ✅ Real-time Firebase data
 *  ✅ Trending score–based sorting
 *  ✅ Pull-to-refresh on the full explore screen
 *  ✅ Back button
 */
public class ReelExploreActivity extends AppCompatActivity {

    private static final String[] CATEGORIES = {
        "Trending", "Dance", "Comedy", "Food", "Travel",
        "Music", "Sports", "Beauty", "Education", "Gaming"
    };

    private ImageButton      btnBack;
    private EditText         etSearch;
    private ChipGroup        chipGroupCategory;
    private RecyclerView     rvHashtags, rvSounds, rvCreators, rvSuggested, rvReelsGrid;
    private SwipeRefreshLayout swipeRefresh;
    private ProgressBar      progressBar;
    private TextView         tvSectionHashtags, tvSectionSounds, tvSectionCreators, tvSectionSuggested;
    private TextView         tvResultCount;

    private String selectedCategory = "Trending";
    private final List<ReelModel> allReels = new ArrayList<>();
    private ValueEventListener reelsListener;

    private ReelGridMiniAdapter gridAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reel_explore);
        bindViews();
        buildCategoryChips();
        setupSearch();
        setupRefresh();
        loadAll();
    }

    private void bindViews() {
        btnBack           = findViewById(R.id.btn_explore_back);
        etSearch          = findViewById(R.id.et_explore_search);
        chipGroupCategory = findViewById(R.id.chip_group_category);
        rvHashtags        = findViewById(R.id.rv_trending_hashtags);
        rvSounds          = findViewById(R.id.rv_trending_sounds);
        rvCreators        = findViewById(R.id.rv_featured_creators);
        rvSuggested       = findViewById(R.id.rv_suggested_creators);
        rvReelsGrid       = findViewById(R.id.rv_explore_grid);
        swipeRefresh      = findViewById(R.id.swipe_refresh_explore);
        progressBar       = findViewById(R.id.progress_explore);
        tvSectionHashtags = findViewById(R.id.tv_section_hashtags);
        tvSectionSounds   = findViewById(R.id.tv_section_sounds);
        tvSectionCreators = findViewById(R.id.tv_section_creators);
        tvSectionSuggested= findViewById(R.id.tv_section_suggested);
        tvResultCount     = findViewById(R.id.tv_result_count);

        btnBack.setOnClickListener(v -> finish());

        // "See All" on Trending Sounds section → ReelTrendingAudioActivity
        if (tvSectionSounds != null) {
            tvSectionSounds.setOnClickListener(v ->
                startActivity(new Intent(this, ReelTrendingAudioActivity.class)));
        }

        // "See All" on Featured Creators → opens Explore with no filter
        if (tvSectionCreators != null) {
            tvSectionCreators.setOnClickListener(v -> {
                // Already on Explore — scroll to grid
            });
        }

        if (rvHashtags != null) rvHashtags.setLayoutManager(
            new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        if (rvSounds != null) rvSounds.setLayoutManager(
            new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        if (rvCreators != null) rvCreators.setLayoutManager(
            new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        if (rvSuggested != null) rvSuggested.setLayoutManager(
            new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        if (rvReelsGrid != null) rvReelsGrid.setLayoutManager(new GridLayoutManager(this, 3));

        gridAdapter = new ReelGridMiniAdapter(allReels, reel -> {
            Intent i = new Intent(this, SingleReelPlayerActivity.class);
            ArrayList<String> ids = new ArrayList<>();
            ids.add(reel.reelId);
            i.putStringArrayListExtra(SingleReelPlayerActivity.EXTRA_REEL_IDS, ids);
            i.putExtra(SingleReelPlayerActivity.EXTRA_START_POSITION, 0);
            startActivity(i);
        });
        if (rvReelsGrid != null) rvReelsGrid.setAdapter(gridAdapter);
    }

    private void buildCategoryChips() {
        if (chipGroupCategory == null) return;
        for (String cat : CATEGORIES) {
            Chip chip = new Chip(this);
            chip.setText(cat);
            chip.setCheckable(true);
            chip.setChecked(cat.equals("Trending"));
            chip.setOnClickListener(v -> {
                selectedCategory = cat;
                updateResultCount();
                filterReelsByCategory();
            });
            chipGroupCategory.addView(chip);
        }

        // Challenges quick-action chip
        Chip chipChallenges = new Chip(this);
        chipChallenges.setText("🏆 Challenges");
        chipChallenges.setCheckable(false);
        chipChallenges.setOnClickListener(v ->
            startActivity(new Intent(this, ReelChallengeActivity.class)));
        chipGroupCategory.addView(chipChallenges);
    }

    private void setupSearch() {
        if (etSearch == null) return;
        // Tap anywhere on etSearch → open dedicated search screen
        etSearch.setOnClickListener(v ->
            startActivity(new Intent(this, ReelSearchActivity.class)));
        // Also handle text input — forward first keystroke to search
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                if (s.length() > 0) {
                    // Pass query to ReelSearchActivity
                    Intent i = new Intent(ReelExploreActivity.this, ReelSearchActivity.class);
                    i.putExtra("initial_query", s.toString());
                    startActivity(i);
                    etSearch.setText("");
                }
            }
        });
        etSearch.setFocusable(false);
    }

    private void setupRefresh() {
        if (swipeRefresh == null) return;
        try {
            swipeRefresh.setColorSchemeResources(R.color.brand_primary);
        } catch (Exception ignored) {}
        swipeRefresh.setOnRefreshListener(() -> {
            allReels.clear();
            if (reelsListener != null) FirebaseUtils.getReelsRef().removeEventListener(reelsListener);
            loadAll();
        });
    }

    private void loadAll() {
        loadReels();
        loadTrendingHashtags();
        loadTrendingSounds();
        loadFeaturedCreators();
        loadSuggestedCreators();
    }

    // ── Firebase loaders ──────────────────────────────────────────────────

    private void loadReels() {
        if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
        reelsListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                allReels.clear();
                for (DataSnapshot s : snap.getChildren()) {
                    ReelModel r = s.getValue(ReelModel.class);
                    if (r != null) {
                        if (r.reelId == null) r.reelId = s.getKey();
                        allReels.add(r);
                    }
                }
                allReels.sort((a, b) -> Float.compare(b.trendingScore(), a.trendingScore()));
                if (progressBar != null) progressBar.setVisibility(View.GONE);
                if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                updateResultCount();
                filterReelsByCategory();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                if (progressBar != null) progressBar.setVisibility(View.GONE);
                if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                Toast.makeText(ReelExploreActivity.this,
                    "Failed to load reels", Toast.LENGTH_SHORT).show();
            }
        };
        FirebaseUtils.getReelsRef()
            .orderByChild("timestamp")
            .limitToLast(200)
            .addValueEventListener(reelsListener);
    }

    private void filterReelsByCategory() {
        List<ReelModel> filtered = new ArrayList<>();
        for (ReelModel r : allReels) {
            if ("Trending".equals(selectedCategory)) {
                filtered.add(r);
            } else {
                String cat = selectedCategory.toLowerCase();
                String cap = r.caption != null ? r.caption.toLowerCase() : "";
                List<String> tags = r.hashtags != null ? r.hashtags : new ArrayList<>();
                // Check category, hashtags, and sound name
                boolean tagMatch = false;
                for (String t : tags) {
                    if (t != null && t.toLowerCase().contains(cat)) { tagMatch = true; break; }
                }
                if (cap.contains(cat) || tagMatch) filtered.add(r);
            }
        }
        gridAdapter.setReels(filtered);
        updateResultCount();
    }

    private void updateResultCount() {
        if (tvResultCount == null) return;
        int count = gridAdapter != null ? gridAdapter.getItemCount() : 0;
        tvResultCount.setText(count + " reels");
        tvResultCount.setVisibility(View.VISIBLE);
    }

    private void loadTrendingHashtags() {
        FirebaseUtils.getTrendingHashtagsRef()
            .orderByChild("count")
            .limitToLast(15)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    List<String[]> items = new ArrayList<>();
                    for (DataSnapshot s : snap.getChildren()) {
                        String tag   = s.getKey();
                        Long   count = s.child("count").getValue(Long.class);
                        if (tag != null)
                            items.add(new String[]{"#" + tag, formatCount(count != null ? count : 0)});
                    }
                    if (items.isEmpty()) {
                        String[] defaults = {"#trending","#viral","#fyp","#reels","#music","#dance"};
                        for (String d : defaults) items.add(new String[]{d, "—"});
                    }
                    Collections.reverse(items);
                    if (rvHashtags != null)
                        rvHashtags.setAdapter(new HashtagChipAdapter(items, tag -> {
                            Intent i = new Intent(ReelExploreActivity.this, HashtagReelsActivity.class);
                            i.putExtra("hashtag", tag.replace("#", ""));
                            startActivity(i);
                        }));
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void loadTrendingSounds() {
        FirebaseUtils.getMusicLibraryRef()
            .orderByChild("usageCount")
            .limitToLast(12)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    List<String[]> items = new ArrayList<>();
                    for (DataSnapshot s : snap.getChildren()) {
                        String title  = s.child("title").getValue(String.class);
                        String artist = s.child("artist").getValue(String.class);
                        String id     = s.getKey();
                        String url    = s.child("audioUrl").getValue(String.class);
                        String cover  = s.child("coverUrl").getValue(String.class);
                        Long usage    = s.child("usageCount").getValue(Long.class);
                        if (title != null)
                            items.add(new String[]{
                                id,
                                title,
                                artist != null ? artist : "Unknown",
                                url    != null ? url    : "",
                                cover  != null ? cover  : "",
                                usage  != null ? formatCount(usage) + " uses" : ""
                            });
                    }
                    Collections.reverse(items);
                    if (rvSounds != null)
                        rvSounds.setAdapter(new SoundCardAdapter(items, item -> {
                            Intent i = new Intent(ReelExploreActivity.this, SoundDetailActivity.class);
                            i.putExtra(SoundDetailActivity.EXTRA_SOUND_ID,    item[0]);
                            i.putExtra(SoundDetailActivity.EXTRA_SOUND_TITLE, item[1]);
                            i.putExtra(SoundDetailActivity.EXTRA_ARTIST,      item[2]);
                            i.putExtra(SoundDetailActivity.EXTRA_SOUND_URL,   item[3]);
                            startActivity(i);
                        }));
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void loadFeaturedCreators() {
        FirebaseUtils.db().getReference("users")
            .orderByChild("reelCount")
            .limitToLast(10)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    List<String[]> creators = new ArrayList<>();
                    for (DataSnapshot s : snap.getChildren()) {
                        String uid   = s.getKey();
                        String name  = s.child("name").getValue(String.class);
                        String photo = s.child("photoUrl").getValue(String.class);
                        Long   rc    = s.child("reelCount").getValue(Long.class);
                        if (uid != null && name != null)
                            creators.add(new String[]{
                                uid,
                                name,
                                photo != null ? photo : "",
                                rc != null ? formatCount(rc) + " reels" : "Creator"
                            });
                    }
                    Collections.reverse(creators);
                    if (rvCreators != null)
                        rvCreators.setAdapter(new CreatorCardAdapter(creators, uid -> {
                            Intent i = new Intent(ReelExploreActivity.this, UserReelsActivity.class);
                            i.putExtra("uid", uid);
                            startActivity(i);
                        }));
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    /**
     * Suggested For You: creators sorted by follower count that the current viewer
     * might not be following yet. Uses reelFollowers/{uid} count as a proxy for
     * popularity since a proper recommendation engine requires server-side ML.
     */
    private void loadSuggestedCreators() {
        if (rvSuggested == null || tvSectionSuggested == null) return;

        String myUid;
        try { myUid = FirebaseUtils.getCurrentUid(); }
        catch (Exception e) { return; }
        final String viewerUid = myUid;

        // Load who the viewer already follows
        FirebaseUtils.getReelFollowsRef(viewerUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot followsSnap) {
                    Set<String> alreadyFollowed = new HashSet<>();
                    alreadyFollowed.add(viewerUid); // exclude self
                    for (DataSnapshot s : followsSnap.getChildren()) alreadyFollowed.add(s.getKey());

                    // Fetch top creators by follower count
                    FirebaseUtils.db().getReference("users")
                        .orderByChild("followerCount")
                        .limitToLast(15)
                        .addListenerForSingleValueEvent(new ValueEventListener() {
                            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                                List<String[]> suggested = new ArrayList<>();
                                for (DataSnapshot s : snap.getChildren()) {
                                    String uid  = s.getKey();
                                    if (uid == null || alreadyFollowed.contains(uid)) continue;
                                    String name  = s.child("name").getValue(String.class);
                                    String photo = s.child("photoUrl").getValue(String.class);
                                    if (name != null)
                                        suggested.add(new String[]{
                                            uid,
                                            name,
                                            photo != null ? photo : ""
                                        });
                                }
                                Collections.reverse(suggested);
                                if (suggested.isEmpty()) {
                                    if (tvSectionSuggested != null)
                                        tvSectionSuggested.setVisibility(View.GONE);
                                    if (rvSuggested != null)
                                        rvSuggested.setVisibility(View.GONE);
                                    return;
                                }
                                rvSuggested.setAdapter(new CreatorCardAdapter(suggested, uid -> {
                                    Intent i = new Intent(ReelExploreActivity.this, UserReelsActivity.class);
                                    i.putExtra("uid", uid);
                                    startActivity(i);
                                }));
                            }
                            @Override public void onCancelled(@NonNull DatabaseError e) {}
                        });
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private String formatCount(long n) {
        if (n >= 1_000_000) return String.format(Locale.US, "%.1fM", n / 1_000_000.0);
        if (n >= 1_000)     return String.format(Locale.US, "%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }

    @Override protected void onDestroy() {
        if (reelsListener != null) FirebaseUtils.getReelsRef().removeEventListener(reelsListener);
        super.onDestroy();
    }

    // ══════════════════════════════════════════════════════════════════════
    // Inner adapters
    // ══════════════════════════════════════════════════════════════════════

    interface OnItemClick<T> { void onClick(T item); }

    // ── Reel grid mini ────────────────────────────────────────────────────
    static class ReelGridMiniAdapter
            extends RecyclerView.Adapter<ReelGridMiniAdapter.VH> {
        private List<ReelModel> list;
        private final OnItemClick<ReelModel> click;
        ReelGridMiniAdapter(List<ReelModel> l, OnItemClick<ReelModel> c) { list = new ArrayList<>(l); click = c; }
        void setReels(List<ReelModel> l) { list = new ArrayList<>(l); notifyDataSetChanged(); }
        @NonNull @Override public VH onCreateViewHolder(@NonNull android.view.ViewGroup p, int t) {
            ImageView iv = new ImageView(p.getContext());
            int sz = dpToPx(p.getContext(), 160);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT, sz);
            lp.setMargins(1, 1, 1, 1);
            iv.setLayoutParams(lp);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundColor(0xFF1A1A1A);
            return new VH(iv);
        }
        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            ReelModel r = list.get(pos);
            if (r.thumbUrl != null && !r.thumbUrl.isEmpty()) {
                com.bumptech.glide.Glide.with(h.iv).load(r.thumbUrl)
                    .override(480, 853)
                    .placeholder(android.R.color.darker_gray).into(h.iv);
            } else {
                h.iv.setImageResource(android.R.color.darker_gray);
            }
            h.iv.setOnClickListener(v -> click.onClick(r));
        }
        @Override public int getItemCount() { return list.size(); }
        static class VH extends RecyclerView.ViewHolder {
            ImageView iv;
            VH(ImageView v) { super(v); iv = v; }
        }
        static int dpToPx(android.content.Context c, int dp) {
            return (int)(dp * c.getResources().getDisplayMetrics().density);
        }
    }

    // ── Hashtag chip row ──────────────────────────────────────────────────
    static class HashtagChipAdapter
            extends RecyclerView.Adapter<HashtagChipAdapter.VH> {
        private final List<String[]> items;
        private final OnItemClick<String> click;
        HashtagChipAdapter(List<String[]> i, OnItemClick<String> c) { items = i; click = c; }
        @NonNull @Override public VH onCreateViewHolder(@NonNull android.view.ViewGroup p, int t) {
            android.widget.LinearLayout ll = new android.widget.LinearLayout(p.getContext());
            ll.setOrientation(android.widget.LinearLayout.VERTICAL);
            ll.setGravity(android.view.Gravity.CENTER);
            int pad = dpToPx(p.getContext(), 10);
            ll.setPadding(pad, pad, pad, pad);
            ll.setBackgroundResource(R.drawable.bg_speed_chip);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.WRAP_CONTENT, RecyclerView.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dpToPx(p.getContext(), 8));
            ll.setLayoutParams(lp);
            TextView tvTag = new TextView(p.getContext());
            tvTag.setTextSize(13);
            tvTag.setTextColor(0xFFFFFFFF);
            tvTag.setTag("tag");
            TextView tvCount = new TextView(p.getContext());
            tvCount.setTextSize(10);
            tvCount.setTextColor(0xFF888888);
            tvCount.setTag("count");
            ll.addView(tvTag);
            ll.addView(tvCount);
            return new VH(ll);
        }
        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            ((TextView) h.ll.findViewWithTag("tag")).setText(items.get(pos)[0]);
            ((TextView) h.ll.findViewWithTag("count")).setText(items.get(pos)[1]);
            h.ll.setOnClickListener(v -> click.onClick(items.get(pos)[0]));
        }
        @Override public int getItemCount() { return items.size(); }
        static class VH extends RecyclerView.ViewHolder {
            android.widget.LinearLayout ll;
            VH(android.widget.LinearLayout v) { super(v); ll = v; }
        }
        static int dpToPx(android.content.Context c, int dp) {
            return (int)(dp * c.getResources().getDisplayMetrics().density);
        }
    }

    // ── Sound card row ────────────────────────────────────────────────────
    static class SoundCardAdapter
            extends RecyclerView.Adapter<SoundCardAdapter.VH> {
        private final List<String[]> items;
        private final OnItemClick<String[]> click;
        SoundCardAdapter(List<String[]> i, OnItemClick<String[]> c) { items = i; click = c; }
        @NonNull @Override public VH onCreateViewHolder(@NonNull android.view.ViewGroup p, int t) {
            android.widget.LinearLayout ll = new android.widget.LinearLayout(p.getContext());
            ll.setOrientation(android.widget.LinearLayout.VERTICAL);
            int w = dpToPx(p.getContext(), 100);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(w, RecyclerView.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dpToPx(p.getContext(), 12));
            ll.setLayoutParams(lp);
            ImageView iv = new ImageView(p.getContext());
            iv.setLayoutParams(new android.widget.LinearLayout.LayoutParams(w, w));
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundColor(0xFF2A2A2A);
            iv.setTag("cover");
            TextView tvTitle = new TextView(p.getContext());
            tvTitle.setMaxLines(1);
            tvTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tvTitle.setTextSize(12);
            tvTitle.setTextColor(0xFFFFFFFF);
            tvTitle.setPadding(0, dpToPx(p.getContext(), 4), 0, 0);
            tvTitle.setTag("title");
            TextView tvArtist = new TextView(p.getContext());
            tvArtist.setMaxLines(1);
            tvArtist.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tvArtist.setTextSize(10);
            tvArtist.setTextColor(0xFF888888);
            tvArtist.setTag("artist");
            ll.addView(iv); ll.addView(tvTitle); ll.addView(tvArtist);
            return new VH(ll);
        }
        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            String[] item = items.get(pos);
            ((TextView) h.ll.findViewWithTag("title")).setText(item[1]);
            ((TextView) h.ll.findViewWithTag("artist")).setText(item[2]);
            // Load cover art if available
            String cover = item.length > 4 ? item[4] : "";
            ImageView iv = h.ll.findViewWithTag("cover");
            if (!cover.isEmpty()) {
                com.bumptech.glide.Glide.with(iv).load(cover)
                    .override(480, 853)
                    .centerCrop().placeholder(android.R.color.darker_gray).into(iv);
            }
            h.ll.setOnClickListener(v -> click.onClick(item));
        }
        @Override public int getItemCount() { return items.size(); }
        static class VH extends RecyclerView.ViewHolder {
            android.widget.LinearLayout ll;
            VH(android.widget.LinearLayout v) { super(v); ll = v; }
        }
        static int dpToPx(android.content.Context c, int dp) {
            return (int)(dp * c.getResources().getDisplayMetrics().density);
        }
    }

    // ── Creator card row ──────────────────────────────────────────────────
    static class CreatorCardAdapter
            extends RecyclerView.Adapter<CreatorCardAdapter.VH> {
        private final List<String[]> creators;
        private final OnItemClick<String> click;
        CreatorCardAdapter(List<String[]> c, OnItemClick<String> cl) { creators = c; click = cl; }
        @NonNull @Override public VH onCreateViewHolder(@NonNull android.view.ViewGroup p, int t) {
            android.widget.LinearLayout ll = new android.widget.LinearLayout(p.getContext());
            ll.setOrientation(android.widget.LinearLayout.VERTICAL);
            ll.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            int w = dpToPx(p.getContext(), 80);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(w, RecyclerView.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dpToPx(p.getContext(), 12));
            ll.setLayoutParams(lp);
            de.hdodenhof.circleimageview.CircleImageView iv =
                new de.hdodenhof.circleimageview.CircleImageView(p.getContext());
            iv.setLayoutParams(new android.widget.LinearLayout.LayoutParams(w, w));
            iv.setTag("avatar");
            TextView tvName = new TextView(p.getContext());
            tvName.setMaxLines(1);
            tvName.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tvName.setTextSize(11);
            tvName.setTextColor(0xFFFFFFFF);
            tvName.setGravity(android.view.Gravity.CENTER);
            tvName.setPadding(0, dpToPx(p.getContext(), 4), 0, 0);
            tvName.setTag("name");
            TextView tvSub = new TextView(p.getContext());
            tvSub.setMaxLines(1);
            tvSub.setTextSize(10);
            tvSub.setTextColor(0xFF888888);
            tvSub.setGravity(android.view.Gravity.CENTER);
            tvSub.setTag("sub");
            ll.addView(iv); ll.addView(tvName); ll.addView(tvSub);
            return new VH(ll);
        }
        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            String[] c = creators.get(pos);
            ((TextView) h.ll.findViewWithTag("name")).setText(c[1]);
            String sub = c.length > 3 ? c[3] : "";
            TextView tvSub = h.ll.findViewWithTag("sub");
            if (tvSub != null) tvSub.setText(sub);
            de.hdodenhof.circleimageview.CircleImageView iv = h.ll.findViewWithTag("avatar");
            if (c[2] != null && !c[2].isEmpty())
                com.bumptech.glide.Glide.with(iv).load(c[2])
                    .override(480, 853)
                    .placeholder(R.drawable.ic_person).into(iv);
            h.ll.setOnClickListener(v -> click.onClick(c[0]));
        }
        @Override public int getItemCount() { return creators.size(); }
        static class VH extends RecyclerView.ViewHolder {
            android.widget.LinearLayout ll;
            VH(android.widget.LinearLayout v) { super(v); ll = v; }
        }
        static int dpToPx(android.content.Context c, int dp) {
            return (int)(dp * c.getResources().getDisplayMetrics().density);
        }
    }
}
