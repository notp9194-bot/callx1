package com.callx.app.community;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.callx.app.chat.R;
import com.callx.app.db.entity.CommunityEntity;
import com.callx.app.db.entity.CommunityPostEntity;
import com.callx.app.repository.CommunityRepository;
import com.google.firebase.auth.FirebaseAuth;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * v31: Owner analytics dashboard — member count, post count, engagement,
 * community info, and top posts.
 */
public class CommunityAnalyticsDashboardActivity extends AppCompatActivity {

    public static final String EXTRA_COMMUNITY_ID = "communityId";

    private String communityId;

    private TextView tvTotalMembers, tvTotalPosts, tvEngagementRate;
    private TextView tvCommunityCreated, tvCommunityGroups;
    private LinearLayout layoutTopPosts;

    private CommunityRepository repo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_community_analytics);

        communityId = getIntent().getStringExtra(EXTRA_COMMUNITY_ID);
        repo = CommunityRepository.getInstance(this);

        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        tvTotalMembers    = findViewById(R.id.tv_total_members);
        tvTotalPosts      = findViewById(R.id.tv_total_posts);
        tvEngagementRate  = findViewById(R.id.tv_engagement_rate);
        tvCommunityCreated = findViewById(R.id.tv_community_created);
        tvCommunityGroups = findViewById(R.id.tv_community_groups);
        layoutTopPosts    = findViewById(R.id.layout_top_posts);

        if (communityId != null) {
            repo.observeCommunity(communityId).observe(this, this::onCommunityLoaded);
            repo.observeFeed(communityId).observe(this, this::onPostsLoaded);
        }
    }

    private void onCommunityLoaded(CommunityEntity c) {
        if (c == null) return;
        tvTotalMembers.setText(String.valueOf(c.memberCount));
        tvTotalPosts.setText(String.valueOf(c.postCount));

        SimpleDateFormat sdf = new SimpleDateFormat("MMM d, yyyy", Locale.getDefault());
        if (c.createdAt > 0) {
            tvCommunityCreated.setText("Created: " + sdf.format(new Date(c.createdAt)));
        }
        tvCommunityGroups.setText("Linked Groups: " + c.groupCount);
    }

    private void onPostsLoaded(List<CommunityPostEntity> posts) {
        if (posts == null || posts.isEmpty()) {
            tvEngagementRate.setText("No posts yet");
            return;
        }

        long totalLikes    = 0;
        long totalComments = 0;
        for (CommunityPostEntity p : posts) {
            totalLikes    += p.likeCount;
            totalComments += p.commentCount;
        }
        long count = posts.size();
        long avgLikes    = totalLikes    / count;
        long avgComments = totalComments / count;
        tvEngagementRate.setText(avgLikes + " likes, " + avgComments + " comments avg.");
        tvTotalPosts.setText(String.valueOf(count));

        // Build top 5 posts by likes
        List<CommunityPostEntity> sorted = new java.util.ArrayList<>(posts);
        sorted.sort((a, b) -> Long.compare(b.likeCount, a.likeCount));

        layoutTopPosts.removeAllViews();
        int limit = Math.min(5, sorted.size());
        for (int i = 0; i < limit; i++) {
            CommunityPostEntity p = sorted.get(i);
            View row = LayoutInflater.from(this).inflate(R.layout.item_analytics_top_post, layoutTopPosts, false);
            TextView tvRank    = row.findViewById(R.id.tv_rank);
            TextView tvSnippet = row.findViewById(R.id.tv_snippet);
            TextView tvLikes   = row.findViewById(R.id.tv_likes_count);

            tvRank.setText(String.valueOf(i + 1));
            String snippet = p.text != null && !p.text.isEmpty()
                    ? p.text : (p.mediaUrl != null ? "📷 Media post" : "Post");
            if (snippet.length() > 60) snippet = snippet.substring(0, 60) + "…";
            tvSnippet.setText(snippet);
            tvLikes.setText("❤ " + p.likeCount);
            layoutTopPosts.addView(row);
        }
    }
}
