package com.callx.app.channel;

import android.graphics.Color;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;
import com.callx.app.db.entity.ChannelEntity;
import com.callx.app.db.entity.ChannelPostEntity;
import com.callx.app.status.R;
import com.callx.app.viewmodel.ChannelViewModel;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * ChannelAnalyticsActivity — full WhatsApp-level analytics dashboard for owners/admins.
 *
 * Sections:
 *   1. Overview stats (followers, total posts, total views, total forwards, total reactions)
 *   2. Averages (avg views/post, avg reactions/post, avg forwards/post, engagement rate)
 *   3. Content mix (post type breakdown with counts and percentages)
 *   4. Peak activity (best day/hour for posting based on view data)
 *   5. Top posts by views (top 5)
 *   6. Top posts by reactions (top 5)
 *   7. Follower growth badge (weeklyGrowth from channel entity)
 *   8. Poll performance (top voted polls)
 */
public class ChannelAnalyticsActivity extends AppCompatActivity {

    public static final String EXTRA_CHANNEL_ID   = "channelId";
    public static final String EXTRA_CHANNEL_NAME = "channelName";

    private ChannelViewModel viewModel;
    private String channelId, channelName;
    private ScrollView scrollRoot;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_channel_analytics);

        channelId   = getIntent().getStringExtra(EXTRA_CHANNEL_ID);
        channelName = getIntent().getStringExtra(EXTRA_CHANNEL_NAME);
        if (channelId == null) { finish(); return; }

        viewModel = new ViewModelProvider(this).get(ChannelViewModel.class);

        Toolbar toolbar = findViewById(R.id.toolbar_analytics);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Analytics — " + channelName);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        // ── Channel-level stats ───────────────────────────────────────────
        viewModel.getChannel(channelId).observe(this, ch -> {
            if (ch == null) return;
            setText(R.id.tv_stat_followers, formatStat(ch.followers));
            setText(R.id.tv_stat_total_posts, formatStat(ch.totalPosts));

            // Follower growth
            if (ch.weeklyGrowth != 0) {
                String growthStr = (ch.weeklyGrowth > 0 ? "+" : "") + formatStat(ch.weeklyGrowth);
                TextView tvGrowth = findViewById(R.id.tv_stat_weekly_growth);
                if (tvGrowth != null) {
                    tvGrowth.setText(growthStr + " this week");
                    tvGrowth.setTextColor(ch.weeklyGrowth >= 0 ? Color.parseColor("#25D366")
                                                                : Color.parseColor("#FF3B30"));
                }
            }
        });

        // ── Post-level stats ──────────────────────────────────────────────
        viewModel.getChannelPosts(channelId).observe(this, posts -> {
            if (posts == null) posts = new ArrayList<>();
            List<ChannelPostEntity> active = new ArrayList<>();
            for (ChannelPostEntity p : posts) if (!p.isDeleted && p.scheduledAt == 0) active.add(p);

            // Aggregate counters
            long totalViews     = 0, totalForwards = 0, totalReactions = 0;
            Map<String, Integer> typeCounts = new LinkedHashMap<>();
            Map<Integer, Long>   hourViews  = new LinkedHashMap<>();  // hour-of-day → views
            List<ChannelPostEntity> pollPosts = new ArrayList<>();

            for (ChannelPostEntity p : active) {
                totalViews     += p.viewCount;
                totalForwards  += p.forwardCount;
                totalReactions += countReactions(p.reactionsJson);

                String type = p.type != null ? p.type : "text";
                typeCounts.put(type, typeCounts.getOrDefault(type, 0) + 1);

                // Peak hour (based on timestamp)
                Calendar cal = Calendar.getInstance();
                cal.setTimeInMillis(p.timestamp);
                int hour = cal.get(Calendar.HOUR_OF_DAY);
                hourViews.put(hour, hourViews.getOrDefault(hour, 0L) + p.viewCount);

                if ("poll".equals(p.type)) pollPosts.add(p);
            }

            int nonDeleted = active.size();
            double engagementRate = totalViews > 0
                ? (double)(totalReactions + totalForwards) / totalViews * 100.0 : 0;

            // Overview
            setText(R.id.tv_stat_total_views,    formatStat(totalViews));
            setText(R.id.tv_stat_total_forwards, formatStat(totalForwards));
            setText(R.id.tv_stat_total_reactions, formatStat(totalReactions));

            // Averages
            setText(R.id.tv_stat_avg_views,
                nonDeleted > 0 ? String.format("%.1f", (double) totalViews / nonDeleted) : "0");
            setText(R.id.tv_stat_avg_reactions,
                nonDeleted > 0 ? String.format("%.1f", (double) totalReactions / nonDeleted) : "0");
            setText(R.id.tv_stat_avg_forwards,
                nonDeleted > 0 ? String.format("%.1f", (double) totalForwards / nonDeleted) : "0");
            setText(R.id.tv_stat_engagement_rate,
                String.format("%.1f%%", engagementRate));

            // Content mix
            buildContentMixSection(typeCounts, nonDeleted);

            // Peak hour
            buildPeakHourSection(hourViews);

            // Top posts by views
            buildTopPostsSection(active, R.id.layout_top_posts_views,
                Comparator.comparingLong((ChannelPostEntity p) -> p.viewCount).reversed());

            // Top posts by reactions
            buildTopPostsSection(active, R.id.layout_top_posts_reactions,
                Comparator.comparingInt((ChannelPostEntity p) -> countReactions(p.reactionsJson)).reversed());

            // Poll performance
            buildPollPerformanceSection(pollPosts);
        });
    }

    // ── Content mix ───────────────────────────────────────────────────────

    private void buildContentMixSection(Map<String, Integer> typeCounts, int total) {
        LinearLayout container = findViewById(R.id.layout_content_mix);
        if (container == null) return;
        container.removeAllViews();
        if (total == 0) return;

        String[] colors = { "#25D366","#128C7E","#075E54","#34B7F1","#ECE5DD","#FF6B35","#F7DC6F" };
        int ci = 0;
        for (Map.Entry<String, Integer> entry : typeCounts.entrySet()) {
            int count = entry.getValue();
            int pct   = (int)(100.0 * count / total);
            String color = colors[ci++ % colors.length];

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, 8, 0, 8);

            View dot = new View(this);
            dot.setBackgroundColor(Color.parseColor(color));
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(24, 24);
            dotLp.setMargins(0, 0, 16, 0);
            dot.setLayoutParams(dotLp);

            TextView tvType = new TextView(this);
            tvType.setText(capitalize(entry.getKey()));
            tvType.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView tvCount = new TextView(this);
            tvCount.setText(count + " (" + pct + "%)");
            tvCount.setTextColor(0xFF757575);

            row.addView(dot);
            row.addView(tvType);
            row.addView(tvCount);
            container.addView(row);

            // Progress bar
            ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            pb.setMax(100);
            pb.setProgress(pct);
            pb.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.parseColor(color)));
            container.addView(pb);
        }
    }

    // ── Peak hour ─────────────────────────────────────────────────────────

    private void buildPeakHourSection(Map<Integer, Long> hourViews) {
        TextView tvPeakHour = findViewById(R.id.tv_stat_peak_hour);
        if (tvPeakHour == null) return;
        if (hourViews.isEmpty()) { tvPeakHour.setText("Not enough data"); return; }

        int bestHour = 0; long bestViews = -1;
        for (Map.Entry<Integer, Long> e : hourViews.entrySet()) {
            if (e.getValue() > bestViews) { bestViews = e.getValue(); bestHour = e.getKey(); }
        }
        // Format hour as 12h AM/PM
        String ampm = bestHour < 12 ? "AM" : "PM";
        int h12 = bestHour == 0 ? 12 : (bestHour > 12 ? bestHour - 12 : bestHour);
        tvPeakHour.setText(h12 + ":00 " + ampm + " – " + (h12 + 1) % 12 + ":00 " + ampm);
    }

    // ── Top posts ─────────────────────────────────────────────────────────

    private void buildTopPostsSection(List<ChannelPostEntity> posts, int containerId,
                                       Comparator<ChannelPostEntity> comparator) {
        LinearLayout container = findViewById(containerId);
        if (container == null) return;
        container.removeAllViews();

        List<ChannelPostEntity> sorted = new ArrayList<>(posts);
        sorted.sort(comparator);

        int shown = 0;
        for (ChannelPostEntity p : sorted) {
            if (shown >= 5) break;
            if (p.isDeleted) continue;

            View row = getLayoutInflater().inflate(R.layout.item_analytics_post_row, container, false);
            TextView tvText  = row.findViewById(R.id.tv_analytics_post_text);
            TextView tvViews = row.findViewById(R.id.tv_analytics_post_views);
            TextView tvReact = row.findViewById(R.id.tv_analytics_post_reactions);
            TextView tvType  = row.findViewById(R.id.tv_analytics_post_type);
            TextView tvDate  = row.findViewById(R.id.tv_analytics_post_date);

            String preview = p.text != null && !p.text.isEmpty()
                ? (p.text.length() > 60 ? p.text.substring(0, 60) + "…" : p.text)
                : "[" + (p.type != null ? p.type : "post") + "]";

            if (tvText  != null) tvText.setText(preview);
            if (tvViews != null) tvViews.setText(formatStat(p.viewCount) + " views");
            if (tvReact != null) tvReact.setText(countReactions(p.reactionsJson) + " reactions");
            if (tvType  != null) tvType.setText(p.type != null ? capitalize(p.type) : "Text");
            if (tvDate  != null && p.timestamp > 0) {
                tvDate.setText(new SimpleDateFormat("MMM d", Locale.getDefault())
                    .format(new Date(p.timestamp)));
            }
            container.addView(row);
            shown++;
        }

        if (shown == 0) {
            TextView empty = new TextView(this);
            empty.setText("No posts yet.");
            empty.setPadding(0, 8, 0, 8);
            container.addView(empty);
        }
    }

    // ── Poll performance ──────────────────────────────────────────────────

    private void buildPollPerformanceSection(List<ChannelPostEntity> pollPosts) {
        LinearLayout container = findViewById(R.id.layout_poll_performance);
        if (container == null) return;
        container.removeAllViews();

        if (pollPosts.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No polls yet.");
            empty.setPadding(0, 8, 0, 8);
            container.addView(empty);
            return;
        }

        pollPosts.sort((a, b) -> Integer.compare(b.pollTotalVotes, a.pollTotalVotes));

        for (int i = 0; i < Math.min(3, pollPosts.size()); i++) {
            ChannelPostEntity p = pollPosts.get(i);
            LinearLayout pollRow = new LinearLayout(this);
            pollRow.setOrientation(LinearLayout.VERTICAL);
            pollRow.setPadding(0, 16, 0, 8);

            TextView tvQ = new TextView(this);
            tvQ.setText(p.pollQuestion != null ? p.pollQuestion : "Poll");
            tvQ.setTextSize(14f);
            tvQ.setTextColor(0xFF212121);

            TextView tvVotes = new TextView(this);
            tvVotes.setText(p.pollTotalVotes + " votes");
            tvVotes.setTextColor(0xFF757575);
            tvVotes.setTextSize(12f);

            pollRow.addView(tvQ);
            pollRow.addView(tvVotes);

            // Divider
            View divider = new View(this);
            divider.setBackgroundColor(0x1A000000);
            divider.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1));

            container.addView(pollRow);
            container.addView(divider);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private int countReactions(String json) {
        if (json == null || json.isEmpty() || "{}".equals(json)) return 0;
        int count = 0;
        for (int i = 0; i < json.length(); i++) if (json.charAt(i) == ':') count++;
        return count;
    }

    private String formatStat(long n) {
        if (n >= 1_000_000_000L) return String.format("%.1fB", n / 1_000_000_000.0);
        if (n >= 1_000_000L)     return String.format("%.1fM", n / 1_000_000.0);
        if (n >= 1_000L)         return String.format("%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }

    private void setText(int id, String text) {
        TextView tv = findViewById(id);
        if (tv != null) tv.setText(text);
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
