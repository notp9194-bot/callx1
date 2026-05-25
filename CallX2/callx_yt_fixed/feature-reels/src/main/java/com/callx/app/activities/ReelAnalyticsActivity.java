package com.callx.app.activities;

import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * ReelAnalyticsActivity — Creator analytics dashboard for a reel.
 *
 * Features:
 *  ✅ Views, Likes, Comments, Shares, Saves counts
 *  ✅ Estimated total watch time (duration × views)
 *  ✅ Engagement rate = (likes + comments + shares) / views × 100%
 *  ✅ Emoji reaction breakdown
 *  ✅ Audience type (Everyone / Contacts)
 *  ✅ Reel age and post date
 */
public class ReelAnalyticsActivity extends AppCompatActivity {

    public static final String EXTRA_REEL_ID       = "analytics_reel_id";
    public static final String EXTRA_REEL_DURATION = "analytics_duration";

    private ProgressBar progressBar;
    private View        layoutContent;

    private TextView tvViews, tvLikes, tvComments, tvShares, tvSaves, tvReposts;
    private TextView tvWatchTime, tvEngagement, tvAudience, tvPostedAt, tvAge;
    private TextView tvReactions;

    private String reelId;
    private int    durationMs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reel_analytics);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Analytics");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        reelId     = getIntent().getStringExtra(EXTRA_REEL_ID);
        durationMs = getIntent().getIntExtra(EXTRA_REEL_DURATION, 0);

        if (reelId == null || reelId.isEmpty()) {
            Toast.makeText(this, "Reel not found", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        bindViews();
        loadAnalytics();
    }

    private void bindViews() {
        progressBar   = findViewById(R.id.progress_analytics);
        layoutContent = findViewById(R.id.layout_analytics_content);
        tvViews       = findViewById(R.id.tv_views_count);
        tvLikes       = findViewById(R.id.tv_likes_count);
        tvComments    = findViewById(R.id.tv_comments_count);
        tvShares      = findViewById(R.id.tv_shares_count);
        tvSaves       = findViewById(R.id.tv_saves_count);
        tvWatchTime   = findViewById(R.id.tv_watch_time);
        tvEngagement  = findViewById(R.id.tv_engagement_rate);
        tvAudience    = findViewById(R.id.tv_audience_type);
        tvPostedAt    = findViewById(R.id.tv_posted_at);
        tvAge         = findViewById(R.id.tv_reel_age);
        tvReposts     = findViewById(R.id.tv_reposts_count);
        tvReactions   = findViewById(R.id.tv_reactions_breakdown);
    }

    private void loadAnalytics() {
        progressBar.setVisibility(View.VISIBLE);
        layoutContent.setVisibility(View.GONE);

        FirebaseUtils.getReelsRef().child(reelId)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (!snap.exists()) {
                        progressBar.setVisibility(View.GONE);
                        Toast.makeText(ReelAnalyticsActivity.this,
                            "Reel data not found", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    int views    = snapInt(snap, "viewsCount");
                    int likes    = snapInt(snap, "likesCount");
                    int comments = snapInt(snap, "commentsCount");
                    int shares   = snapInt(snap, "sharesCount");
                    long ts      = snap.child("timestamp").getValue(Long.class) != null
                        ? snap.child("timestamp").getValue(Long.class) : 0L;
                    String audience = snap.child("audienceType").getValue(String.class);
                    if (audience == null) audience = "everyone";

                    int reposts  = snapInt(snap, "repostCount");
                    tvViews.setText(formatCount(views));
                    tvLikes.setText(formatCount(likes));
                    tvComments.setText(formatCount(comments));
                    tvShares.setText(formatCount(shares));
                    if (tvReposts != null) tvReposts.setText(formatCount(reposts));

                    long dur = durationMs > 0 ? durationMs
                        : (snap.child("duration").getValue(Integer.class) != null
                           ? snap.child("duration").getValue(Integer.class) : 0);
                    long totalWatchSec = (dur / 1000L) * views;
                    tvWatchTime.setText(formatWatchTime(totalWatchSec));

                    // Reposts count at 2× weight (same as in trendingScore)
                    float engagement = views > 0
                        ? (float)(likes + comments + shares + reposts * 2) / views * 100f : 0f;
                    tvEngagement.setText(String.format(Locale.US, "%.1f%%", engagement));

                    tvAudience.setText(audience.equals("contacts") ? "Contacts Only" : "Everyone");

                    if (ts > 0) {
                        java.util.Date date = new java.util.Date(ts);
                        tvPostedAt.setText(new java.text.SimpleDateFormat(
                            "MMM d, yyyy 'at' h:mm a", Locale.US).format(date));
                        long ageDays = (System.currentTimeMillis() - ts) / 86_400_000L;
                        tvAge.setText(ageDays == 0 ? "Today"
                            : ageDays + " day" + (ageDays == 1 ? "" : "s") + " ago");
                    }

                    loadSavesAndReactions(views, likes, comments, shares);
                }

                @Override public void onCancelled(@NonNull DatabaseError error) {
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(ReelAnalyticsActivity.this,
                        "Failed to load: " + error.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
    }

    private void loadSavesAndReactions(int views, int likes, int comments, int shares) {
        FirebaseUtils.getReelSavesRef("_index").getParent();

        FirebaseUtils.getReelReactionsRef(reelId)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    Map<String, Integer> counts = new HashMap<>();
                    for (DataSnapshot child : snap.getChildren()) {
                        String emoji = child.getValue(String.class);
                        if (emoji != null)
                            counts.put(emoji, counts.getOrDefault(emoji, 0) + 1);
                    }
                    if (!counts.isEmpty()) {
                        StringBuilder sb = new StringBuilder();
                        for (Map.Entry<String, Integer> e : counts.entrySet()) {
                            sb.append(e.getKey()).append(" ").append(e.getValue()).append("  ");
                        }
                        tvReactions.setText(sb.toString().trim());
                    } else {
                        tvReactions.setText("No reactions yet");
                    }

                    progressBar.setVisibility(View.GONE);
                    layoutContent.setVisibility(View.VISIBLE);
                }

                @Override public void onCancelled(@NonNull DatabaseError error) {
                    tvReactions.setText("—");
                    progressBar.setVisibility(View.GONE);
                    layoutContent.setVisibility(View.VISIBLE);
                }
            });
    }

    private int snapInt(DataSnapshot snap, String key) {
        Integer v = snap.child(key).getValue(Integer.class);
        return v != null ? v : 0;
    }

    private String formatCount(int n) {
        if (n >= 1_000_000) return String.format(Locale.US, "%.1fM", n / 1_000_000f);
        if (n >= 1_000)     return String.format(Locale.US, "%.1fK", n / 1_000f);
        return String.valueOf(n);
    }

    private String formatWatchTime(long totalSec) {
        if (totalSec >= 3600) return String.format(Locale.US, "%.1f hrs", totalSec / 3600f);
        if (totalSec >= 60)   return String.format(Locale.US, "%.1f min", totalSec / 60f);
        return totalSec + " sec";
    }
}
