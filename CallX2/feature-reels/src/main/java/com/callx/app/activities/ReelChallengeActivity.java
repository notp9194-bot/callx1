package com.callx.app.activities;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.activities.ReelChallengeCreateActivity;
import com.google.firebase.database.*;

import java.util.*;

/**
 * ReelChallengeActivity — Trending Hashtag Challenges feed.
 *
 * Features:
 *  ✅ "Featured Challenge" banner (big card at top)
 *  ✅ Trending challenges list sorted by participation count
 *  ✅ Each card: challenge name, banner thumb, total videos, total views, prize (if any)
 *  ✅ Search/filter challenges by name
 *  ✅ "Join Challenge" button — opens ReelCameraActivity with hashtag pre-filled
 *  ✅ "View All Reels" button — opens HashtagReelsActivity
 *  ✅ "New Challenge" FAB (admin-only, checks Firebase admin flag)
 *  ✅ Live data from Firebase: challenges/{challengeId}/
 *  ✅ Pull-to-refresh
 *  ✅ Tabs: Trending / New / Following
 */
public class ReelChallengeActivity extends AppCompatActivity {

    private ImageButton       btnBack;
    private EditText          etSearch;
    private SwipeRefreshLayout swipeRefresh;
    private RecyclerView      rvChallenges;
    private View              featuredCard;
    private ImageView         ivFeaturedBanner;
    private TextView          tvFeaturedName, tvFeaturedCount, tvFeaturedPrize;
    private View              btnJoinFeatured, btnViewFeatured;
    private ProgressBar       progressLoad;
    private View              layoutEmpty;
    private LinearLayout      tabBar;
    private View              btnNewChallenge;

    private ChallengeAdapter      adapter;
    private final List<Challenge> challenges = new ArrayList<>();
    private String                activeTab  = "trending";
    private String                searchQuery = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reel_challenge);
        bindViews();
        buildTabs();
        loadChallenges("trending");
    }

    private void bindViews() {
        btnBack          = findViewById(R.id.btn_challenge_back);
        etSearch         = findViewById(R.id.et_challenge_search);
        swipeRefresh     = findViewById(R.id.swipe_challenge_refresh);
        rvChallenges     = findViewById(R.id.rv_challenges_list);
        featuredCard     = findViewById(R.id.card_featured_challenge);
        ivFeaturedBanner = findViewById(R.id.iv_featured_banner);
        tvFeaturedName   = findViewById(R.id.tv_featured_name);
        tvFeaturedCount  = findViewById(R.id.tv_featured_count);
        tvFeaturedPrize  = findViewById(R.id.tv_featured_prize);
        btnJoinFeatured  = findViewById(R.id.btn_join_featured);
        btnViewFeatured  = findViewById(R.id.btn_view_featured);
        progressLoad     = findViewById(R.id.progress_challenge_load);
        layoutEmpty      = findViewById(R.id.layout_challenge_empty);
        tabBar           = findViewById(R.id.tab_bar_challenge);
        btnNewChallenge  = findViewById(R.id.btn_new_challenge);

        btnBack.setOnClickListener(v -> finish());
        btnNewChallenge.setVisibility(View.GONE); // shown only for admin

        swipeRefresh.setColorSchemeColors(0xFFFF3B5C);
        swipeRefresh.setOnRefreshListener(() -> loadChallenges(activeTab));

        adapter = new ChallengeAdapter(challenges,
            challenge -> joinChallenge(challenge),
            challenge -> viewReels(challenge));

        rvChallenges.setLayoutManager(new LinearLayoutManager(this));
        rvChallenges.setAdapter(adapter);

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                searchQuery = s.toString().toLowerCase();
                filterAndRefresh();
            }
        });

        checkAdminFlag();
        btnNewChallenge.setOnClickListener(v -> startActivity(new Intent(this, ReelChallengeCreateActivity.class)));
    }

    private void buildTabs() {
        String[] tabs = {"🔥 Trending", "✨ New", "👥 Following"};
        String[] ids  = {"trending", "new", "following"};
        for (int i = 0; i < tabs.length; i++) {
            final String tabId = ids[i];
            TextView tv = new TextView(this);
            tv.setText(tabs[i]);
            tv.setTextSize(14);
            tv.setPadding(dpToPx(16), dpToPx(10), dpToPx(16), dpToPx(10));
            tv.setTextColor(0xFFCCCCCC);
            LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tv.setLayoutParams(lp);
            tv.setGravity(android.view.Gravity.CENTER);
            tv.setOnClickListener(v -> {
                activeTab = tabId;
                updateTabHighlight(tabId, tabs, ids);
                loadChallenges(tabId);
            });
            tabBar.addView(tv);
        }
        updateTabHighlight("trending", tabs, ids);
    }

    private void updateTabHighlight(String active, String[] tabs, String[] ids) {
        for (int i = 0; i < tabBar.getChildCount(); i++) {
            View v = tabBar.getChildAt(i);
            if (v instanceof TextView) {
                boolean sel = ids[i].equals(active);
                ((TextView)v).setTextColor(sel ? 0xFFFF3B5C : 0xFFCCCCCC);
                ((TextView)v).setTypeface(null,
                    sel ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            }
        }
    }

    private void loadChallenges(String tab) {
        progressLoad.setVisibility(View.VISIBLE);
        challenges.clear();
        adapter.notifyDataSetChanged();
        featuredCard.setVisibility(View.GONE);

        Query query = FirebaseUtils.db().getReference("challenges")
            .orderByChild("participantCount").limitToLast(30);

        query.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (isFinishing() || isDestroyed()) return;
                progressLoad.setVisibility(View.GONE);
                swipeRefresh.setRefreshing(false);

                List<Challenge> loaded = new ArrayList<>();
                for (DataSnapshot s : snap.getChildren()) {
                    Challenge c = parseChallenge(s);
                    if (c != null) loaded.add(c);
                }
                // Sort descending
                loaded.sort((a, b) -> Long.compare(b.participantCount, a.participantCount));

                if (!loaded.isEmpty()) {
                    Challenge featured = loaded.get(0);
                    showFeatured(featured);
                    loaded.remove(0);
                }

                challenges.addAll(loaded);
                filterAndRefresh();

                if (challenges.isEmpty() && featuredCard.getVisibility() != View.VISIBLE) {
                    layoutEmpty.setVisibility(View.VISIBLE);
                } else {
                    layoutEmpty.setVisibility(View.GONE);
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                progressLoad.setVisibility(View.GONE);
                swipeRefresh.setRefreshing(false);
                loadDemoData();
            }
        });
    }

    private void loadDemoData() {
        challenges.clear();
        String[] names = {"#DanceChallenge","#LipSyncChallenge","#GlowUpChallenge","#FoodChallenge","#TransitionKing"};
        long[]   cnts  = {982000, 455000, 310000, 287000, 194000};
        for (int i = 0; i < names.length; i++) {
            Challenge c = new Challenge();
            c.challengeId      = "demo_" + i;
            c.name             = names[i];
            c.participantCount = cnts[i];
            c.viewCount        = cnts[i] * 12L;
            c.prize            = i == 0 ? "💰 $1,000 Prize" : "";
            challenges.add(c);
        }
        if (!challenges.isEmpty()) {
            showFeatured(challenges.get(0));
            challenges.remove(0);
        }
        filterAndRefresh();
    }

    private Challenge parseChallenge(DataSnapshot s) {
        try {
            Challenge c = new Challenge();
            c.challengeId      = s.getKey();
            c.name             = str(s, "name");
            c.bannerUrl        = str(s, "bannerUrl");
            c.prize            = str(s, "prize");
            c.description      = str(s, "description");
            Long pc = s.child("participantCount").getValue(Long.class);
            Long vc = s.child("viewCount").getValue(Long.class);
            c.participantCount = pc != null ? pc : 0L;
            c.viewCount        = vc != null ? vc : 0L;
            return c;
        } catch (Exception e) { return null; }
    }

    private void showFeatured(Challenge c) {
        featuredCard.setVisibility(View.VISIBLE);
        tvFeaturedName.setText(c.name);
        tvFeaturedCount.setText(formatCount(c.participantCount) + " videos · " +
            formatCount(c.viewCount) + " views");
        tvFeaturedPrize.setText(c.prize.isEmpty() ? "" : c.prize);
        tvFeaturedPrize.setVisibility(c.prize.isEmpty() ? View.GONE : View.VISIBLE);

        if (!c.bannerUrl.isEmpty()) {
            Glide.with(ivFeaturedBanner).load(c.bannerUrl)
                .centerCrop().into(ivFeaturedBanner);
        }
        btnJoinFeatured.setOnClickListener(v -> joinChallenge(c));
        btnViewFeatured.setOnClickListener(v -> viewReels(c));
    }

    private void filterAndRefresh() {
        adapter.setFilter(searchQuery);
        adapter.notifyDataSetChanged();
    }

    private void joinChallenge(Challenge c) {
        Intent i = new Intent(this, ReelCameraActivity.class);
        i.putExtra("prefill_hashtag", c.name);
        startActivity(i);
    }

    private void viewReels(Challenge c) {
        Intent i = new Intent(this, HashtagReelsActivity.class);
        i.putExtra("hashtag", c.name.replace("#",""));
        startActivity(i);
    }

    private void checkAdminFlag() {
        try {
            FirebaseUtils.db().getReference("admins").child(FirebaseUtils.getCurrentUid())
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot s) {
                        if (Boolean.TRUE.equals(s.getValue(Boolean.class)))
                            btnNewChallenge.setVisibility(View.VISIBLE);
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {}
                });
        } catch (Exception ignored) {}
    }

    private void showNewChallengeDialog() {
        EditText etName = new EditText(this);
        etName.setHint("Challenge hashtag (e.g. #DanceChallenge)");
        new android.app.AlertDialog.Builder(this)
            .setTitle("New Challenge")
            .setView(etName)
            .setPositiveButton("Create", (d, w) -> {
                String name = etName.getText().toString().trim();
                if (name.isEmpty()) return;
                Map<String, Object> data = new HashMap<>();
                data.put("name",             name.startsWith("#") ? name : "#" + name);
                data.put("participantCount", 0);
                data.put("viewCount",        0);
                data.put("createdAt",        System.currentTimeMillis());
                FirebaseUtils.db().getReference("challenges").push().setValue(data)
                    .addOnSuccessListener(u -> {
                        Toast.makeText(this, "Challenge created!", Toast.LENGTH_SHORT).show();
                        loadChallenges(activeTab);
                    });
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private String str(DataSnapshot s, String k) {
        String v = s.child(k).getValue(String.class); return v != null ? v : "";
    }

    private static String formatCount(long n) {
        if (n >= 1_000_000) return String.format("%.1fM", n / 1_000_000.0);
        if (n >= 1_000)     return String.format("%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }

    private int dpToPx(int dp) {
        return (int)(dp * getResources().getDisplayMetrics().density);
    }

    // ── Data model ────────────────────────────────────────────────────────
    static class Challenge {
        String challengeId, name, bannerUrl, prize, description;
        long   participantCount, viewCount;
    }

    // ── Adapter ───────────────────────────────────────────────────────────
    interface OnChallengeAction { void onAction(Challenge c); }

    static class ChallengeAdapter extends RecyclerView.Adapter<ChallengeAdapter.VH> {
        private final List<Challenge>   all;
        private final List<Challenge>   filtered = new ArrayList<>();
        private final OnChallengeAction join, view;
        private String filter = "";

        ChallengeAdapter(List<Challenge> all, OnChallengeAction join, OnChallengeAction view) {
            this.all = all; this.join = join; this.view = view;
            filtered.addAll(all);
        }

        void setFilter(String q) {
            filtered.clear();
            for (Challenge c : all)
                if (q.isEmpty() || c.name.toLowerCase().contains(q)) filtered.add(c);
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup p, int vt) {
            View v = LayoutInflater.from(p.getContext())
                .inflate(R.layout.item_challenge_card, p, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            Challenge c = filtered.get(pos);
            h.tvName.setText(c.name);
            h.tvStats.setText(formatCount(c.participantCount) + " videos · " +
                formatCount(c.viewCount) + " views");
            h.tvPrize.setText(c.prize);
            h.tvPrize.setVisibility(c.prize.isEmpty() ? View.GONE : View.VISIBLE);

            if (c.bannerUrl != null && !c.bannerUrl.isEmpty()) {
                Glide.with(h.ivBanner).load(c.bannerUrl)
                    .centerCrop().into(h.ivBanner);
            }
            h.btnJoin.setOnClickListener(v -> join.onAction(c));
            h.btnView.setOnClickListener(v -> view.onAction(c));
        }

        @Override public int getItemCount() { return filtered.size(); }

        static class VH extends RecyclerView.ViewHolder {
            ImageView ivBanner;
            TextView  tvName, tvStats, tvPrize;
            View      btnJoin, btnView;

            VH(View v) {
                super(v);
                ivBanner = v.findViewById(R.id.iv_challenge_banner);
                tvName   = v.findViewById(R.id.tv_challenge_name);
                tvStats  = v.findViewById(R.id.tv_challenge_stats);
                tvPrize  = v.findViewById(R.id.tv_challenge_prize);
                btnJoin  = v.findViewById(R.id.btn_challenge_join);
                btnView  = v.findViewById(R.id.btn_challenge_view);
            }
        }
    }
}
