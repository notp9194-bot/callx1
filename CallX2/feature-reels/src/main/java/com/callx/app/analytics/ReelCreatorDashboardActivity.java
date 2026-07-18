package com.callx.app.analytics;

import com.callx.app.upload.ReelUploadActivity;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.callx.app.reels.R;
import com.callx.app.models.ReelModel;
import com.callx.app.creator.ReelCreatorToolsActivity;
import com.callx.app.creator.ReelCreatorFundActivity;
import com.callx.app.analytics.ReelAudienceInsightsActivity;
import com.callx.app.profile.ReelQRCodeActivity;
import com.callx.app.library.SavedReelsActivity;
import com.callx.app.library.LikedReelsActivity;
import com.callx.app.library.ReelDraftsActivity;
import com.callx.app.settings.ReelNotificationSettingsActivity;
import com.callx.app.feed.ReelFeedSettingsActivity;
import com.callx.app.settings.ReelWatermarkSettingsActivity;
import com.callx.app.settings.ReelRemixSettingsActivity;
import com.callx.app.creator.ReelModerationActivity;
import com.callx.app.creator.ReelGiftingActivity;
import com.callx.app.live.ReelLiveActivity;
import com.callx.app.library.ReelBookmarkCollectionsActivity;
import com.callx.app.followers.ReelCollabInboxActivity;
import com.callx.app.upload.ReelMentionsActivity;
import com.callx.app.live.ReelLiveReplayActivity;
import com.callx.app.upload.ReelPostDetailsActivity;
import com.callx.app.notifications.ReelNotificationsActivity;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;

import java.text.SimpleDateFormat;
import java.util.*;

/**
 * ReelCreatorDashboardActivity — Creator Overview Dashboard (all-reels stats).
 *
 * Features:
 *  ✅ Total views / likes / comments / shares across ALL reels
 *  ✅ Total followers count
 *  ✅ Total reels posted count
 *  ✅ Average engagement rate across all reels
 *  ✅ Best performing reels list (top 5 by trendingScore)
 *  ✅ Best posting time hint (based on timestamps of top-performing reels)
 *  ✅ 30-day view trend (text bar chart)
 *  ✅ Tap reel row → opens ReelDeepAnalyticsActivity for that reel
 *  ✅ Tap "New Reel" → opens ReelUploadActivity
 */
public class ReelCreatorDashboardActivity extends AppCompatActivity {

    private ImageButton btnBack;
    private ImageButton btnNotifications;
    private TextView    tvTotalViews, tvTotalLikes, tvTotalComments, tvTotalShares;
    private TextView    tvTotalReels, tvFollowers, tvAvgEngagement, tvBestTime;
    private TextView    tv30DayTrend;
    private ProgressBar progressBar;
    private View        layoutContent;
    private RecyclerView rvTopReels;
    private View        btnNewReel;
    private View        btnCreatorTools, btnCreatorFund, btnAudienceInsights, btnQrCode;
    private View        btnSavedReels, btnLikedReels, btnDraftsBtn;
    private View        btnNotifSettings, btnFeedSettings, btnWatermark, btnRemixSettings;
    private View        btnModeration, btnGifting, btnLive, btnBookmarks;
    private View        btnCollabInbox, btnMentions, btnLiveReplays, btnPostDetails;

    private String myUid;
    private final List<ReelModel> myReels = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reel_creator_dashboard);

        try { myUid = FirebaseUtils.getCurrentUid(); }
        catch (Exception e) { finish(); return; }

        bindViews();
        loadDashboard();
    }

    private void bindViews() {
        btnBack         = findViewById(R.id.btn_dashboard_back);
        btnNotifications= findViewById(R.id.btn_dashboard_notifications);
        tvTotalViews    = findViewById(R.id.tv_dash_total_views);
        tvTotalLikes    = findViewById(R.id.tv_dash_total_likes);
        tvTotalComments = findViewById(R.id.tv_dash_total_comments);
        tvTotalShares   = findViewById(R.id.tv_dash_total_shares);
        tvTotalReels    = findViewById(R.id.tv_dash_total_reels);
        tvFollowers     = findViewById(R.id.tv_dash_followers);
        tvAvgEngagement = findViewById(R.id.tv_dash_avg_engagement);
        tvBestTime      = findViewById(R.id.tv_dash_best_time);
        tv30DayTrend    = findViewById(R.id.tv_dash_trend);
        progressBar     = findViewById(R.id.progress_dashboard);
        layoutContent   = findViewById(R.id.layout_dashboard_content);
        rvTopReels      = findViewById(R.id.rv_dashboard_top_reels);
        btnNewReel      = findViewById(R.id.btn_dashboard_new_reel);

        btnBack.setOnClickListener(v -> finish());
        btnNotifications.setOnClickListener(v ->
            startActivity(new Intent(this, ReelNotificationsActivity.class)));
        btnNewReel.setOnClickListener(v ->
            startActivity(new Intent(this, ReelUploadActivity.class)));

        // Creator quick-action buttons — wire if present in layout
        btnCreatorTools    = findViewById(R.id.btn_creator_tools);
        btnCreatorFund     = findViewById(R.id.btn_creator_fund);
        btnAudienceInsights= findViewById(R.id.btn_audience_insights);
        btnQrCode          = findViewById(R.id.btn_qr_code);
        btnSavedReels      = findViewById(R.id.btn_saved_reels);
        btnLikedReels      = findViewById(R.id.btn_liked_reels);
        btnDraftsBtn       = findViewById(R.id.btn_drafts);
        btnNotifSettings   = findViewById(R.id.btn_notif_settings);
        btnFeedSettings    = findViewById(R.id.btn_feed_settings);
        btnWatermark       = findViewById(R.id.btn_watermark);
        btnRemixSettings   = findViewById(R.id.btn_remix_settings);
        btnModeration      = findViewById(R.id.btn_moderation);
        btnGifting         = findViewById(R.id.btn_gifting);
        btnLive            = findViewById(R.id.btn_live);
        btnBookmarks       = findViewById(R.id.btn_bookmarks);

        if (btnCreatorTools    != null) btnCreatorTools.setOnClickListener(v    -> startActivity(new Intent(this, ReelCreatorToolsActivity.class)));
        if (btnCreatorFund     != null) btnCreatorFund.setOnClickListener(v     -> startActivity(new Intent(this, ReelCreatorFundActivity.class)));
        if (btnAudienceInsights!= null) btnAudienceInsights.setOnClickListener(v-> startActivity(new Intent(this, ReelAudienceInsightsActivity.class)));
        if (btnQrCode          != null) btnQrCode.setOnClickListener(v          -> startActivity(new Intent(this, ReelQRCodeActivity.class)));
        if (btnSavedReels      != null) btnSavedReels.setOnClickListener(v      -> startActivity(new Intent(this, SavedReelsActivity.class)));
        if (btnLikedReels      != null) btnLikedReels.setOnClickListener(v      -> startActivity(new Intent(this, LikedReelsActivity.class)));
        if (btnDraftsBtn       != null) btnDraftsBtn.setOnClickListener(v       -> startActivity(new Intent(this, ReelDraftsActivity.class)));
        if (btnNotifSettings   != null) btnNotifSettings.setOnClickListener(v   -> startActivity(new Intent(this, ReelNotificationSettingsActivity.class)));
        if (btnFeedSettings    != null) btnFeedSettings.setOnClickListener(v    -> startActivity(new Intent(this, ReelFeedSettingsActivity.class)));
        if (btnWatermark       != null) btnWatermark.setOnClickListener(v       -> startActivity(new Intent(this, ReelWatermarkSettingsActivity.class)));
        if (btnRemixSettings   != null) btnRemixSettings.setOnClickListener(v   -> startActivity(new Intent(this, ReelRemixSettingsActivity.class)));
        if (btnModeration      != null) btnModeration.setOnClickListener(v      -> startActivity(new Intent(this, ReelModerationActivity.class)));
        if (btnGifting         != null) btnGifting.setOnClickListener(v         -> startActivity(new Intent(this, ReelGiftingActivity.class)));
        if (btnLive            != null) btnLive.setOnClickListener(v            -> startActivity(new Intent(this, ReelLiveActivity.class)));
        if (btnBookmarks       != null) btnBookmarks.setOnClickListener(v       -> startActivity(new Intent(this, ReelBookmarkCollectionsActivity.class)));

        // Previously unconnected screens — now wired up
        btnCollabInbox  = findViewById(R.id.btn_collab_inbox);
        btnMentions     = findViewById(R.id.btn_mentions);
        btnLiveReplays  = findViewById(R.id.btn_live_replays);
        btnPostDetails  = findViewById(R.id.btn_post_details);
        if (btnCollabInbox  != null) btnCollabInbox.setOnClickListener(v  -> startActivity(new Intent(this, ReelCollabInboxActivity.class)));
        if (btnMentions     != null) btnMentions.setOnClickListener(v     -> startActivity(new Intent(this, ReelMentionsActivity.class)));
        if (btnLiveReplays  != null) btnLiveReplays.setOnClickListener(v  -> startActivity(new Intent(this, ReelLiveReplayActivity.class)));
        if (btnPostDetails  != null) btnPostDetails.setOnClickListener(v  -> startActivity(new Intent(this, ReelPostDetailsActivity.class)));

        rvTopReels.setLayoutManager(new LinearLayoutManager(this));
        layoutContent.setVisibility(View.GONE);
    }

    private void loadDashboard() {
        progressBar.setVisibility(View.VISIBLE);

        FirebaseUtils.getReelsByUserRef(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot reelIndexSnap) {
                    List<String> reelIds = new ArrayList<>();
                    for (DataSnapshot s : reelIndexSnap.getChildren()) reelIds.add(s.getKey());

                    if (reelIds.isEmpty()) {
                        progressBar.setVisibility(View.GONE);
                        layoutContent.setVisibility(View.VISIBLE);
                        fillEmptyState();
                        return;
                    }
                    loadReelDetails(reelIds);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(ReelCreatorDashboardActivity.this,
                        "Failed to load dashboard", Toast.LENGTH_SHORT).show();
                }
            });

        loadFollowerCount();
    }

    private void loadReelDetails(List<String> reelIds) {
        final int[] loaded = {0};
        myReels.clear();
        for (String id : reelIds) {
            FirebaseUtils.getReelsRef().child(id)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        ReelModel r = snap.getValue(ReelModel.class);
                        if (r != null) {
                            if (r.reelId == null) r.reelId = snap.getKey();
                            myReels.add(r);
                        }
                        loaded[0]++;
                        if (loaded[0] >= reelIds.size()) buildDashboard();
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {
                        loaded[0]++;
                        if (loaded[0] >= reelIds.size()) buildDashboard();
                    }
                });
        }
    }

    private void loadFollowerCount() {
        FirebaseUtils.db().getReference("reelFollowers").child(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (tvFollowers != null) tvFollowers.setText(fmt(snap.getChildrenCount()));
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void buildDashboard() {
        if (isFinishing() || isDestroyed()) return;

        long totalViews = 0, totalLikes = 0, totalComments = 0, totalShares = 0;
        for (ReelModel r : myReels) {
            totalViews    += r.viewsCount;
            totalLikes    += r.likesCount;
            totalComments += r.commentsCount;
            totalShares   += r.sharesCount;
        }

        final long finalTotalViews    = totalViews;
        final long finalTotalLikes    = totalLikes;
        final long finalTotalComments = totalComments;
        final long finalTotalShares   = totalShares;

        float avgEngagement = finalTotalViews > 0
            ? (float)(finalTotalLikes + finalTotalComments + finalTotalShares) / finalTotalViews * 100f : 0f;

        // Sort by trending score for top reels
        List<ReelModel> sorted = new ArrayList<>(myReels);
        sorted.sort((a, b) -> Float.compare(b.trendingScore(), a.trendingScore()));

        runOnUiThread(() -> {
            tvTotalViews.setText(fmt(finalTotalViews));
            tvTotalLikes.setText(fmt(finalTotalLikes));
            tvTotalComments.setText(fmt(finalTotalComments));
            tvTotalShares.setText(fmt(finalTotalShares));
            tvTotalReels.setText(String.valueOf(myReels.size()));
            tvAvgEngagement.setText(String.format(Locale.US, "%.1f%%", avgEngagement));
            tvBestTime.setText(computeBestPostingTime());
            tv30DayTrend.setText(build30DayTrend());

            List<ReelModel> topFive = sorted.subList(0, Math.min(5, sorted.size()));
            rvTopReels.setAdapter(new TopReelAdapter(topFive, reel -> {
                Intent i = new Intent(this, ReelDeepAnalyticsActivity.class);
                i.putExtra(ReelDeepAnalyticsActivity.EXTRA_REEL_ID, reel.reelId);
                i.putExtra(ReelDeepAnalyticsActivity.EXTRA_REEL_DURATION, reel.duration);
                startActivity(i);
            }));

            progressBar.setVisibility(View.GONE);
            layoutContent.setVisibility(View.VISIBLE);
        });
    }

    private void fillEmptyState() {
        tvTotalViews.setText("0");
        tvTotalLikes.setText("0");
        tvTotalComments.setText("0");
        tvTotalShares.setText("0");
        tvTotalReels.setText("0");
        tvAvgEngagement.setText("0%");
        tvBestTime.setText("Post your first reel!");
        tv30DayTrend.setText("No data yet");
    }

    private String computeBestPostingTime() {
        if (myReels.isEmpty()) return "—";
        ReelModel best = null;
        for (ReelModel r : myReels) {
            if (best == null || r.trendingScore() > best.trendingScore()) best = r;
        }
        if (best == null) return "—";
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(best.timestamp);
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        String period = hour < 12 ? "AM" : "PM";
        int h12 = hour % 12 == 0 ? 12 : hour % 12;
        String dayName = new SimpleDateFormat("EEEE", Locale.US).format(cal.getTime());
        return dayName + " around " + h12 + " " + period;
    }

    private String build30DayTrend() {
        if (myReels.isEmpty()) return "No reels posted yet";
        long now = System.currentTimeMillis();
        long[] buckets = new long[30];
        for (ReelModel r : myReels) {
            long daysAgo = (now - r.timestamp) / 86_400_000L;
            if (daysAgo >= 0 && daysAgo < 30) buckets[(int) daysAgo] += r.viewsCount;
        }
        long maxVal = 0;
        for (long v : buckets) if (v > maxVal) maxVal = v;
        if (maxVal == 0) return "No views in last 30 days";
        StringBuilder sb = new StringBuilder();
        for (int i = 29; i >= 0; i -= 5) {
            long v = buckets[i];
            int bars = maxVal > 0 ? (int)(v * 8 / maxVal) : 0;
            sb.append("Day -").append(i).append(": ")
              .append("█".repeat(bars)).append("░".repeat(8 - bars))
              .append("  ").append(fmt(v)).append("\n");
        }
        return sb.toString().trim();
    }

    private String fmt(long n) {
        if (n >= 1_000_000) return String.format(Locale.US, "%.1fM", n / 1_000_000.0);
        if (n >= 1_000)     return String.format(Locale.US, "%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }

    // ── Top reels adapter ─────────────────────────────────────────────────

    interface OnReelClick { void onClick(ReelModel reel); }

    static class TopReelAdapter extends RecyclerView.Adapter<TopReelAdapter.VH> {
        private final List<ReelModel> reels;
        private final OnReelClick click;
        TopReelAdapter(List<ReelModel> r, OnReelClick c) { reels = r; click = c; }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_dashboard_top_reel, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            ReelModel r = reels.get(pos);
            h.tvRank.setText("#" + (pos + 1));
            h.tvCaption.setText(r.caption != null && !r.caption.isEmpty() ? r.caption : "No caption");
            h.tvViews.setText(fmt(r.viewsCount) + " views");
            h.tvLikes.setText(fmt(r.likesCount) + " likes");
            if (r.thumbUrl != null && !r.thumbUrl.isEmpty()) {
                Glide.with(h.ivThumb).load(r.thumbUrl)
                    .override(480, 853)
                    .placeholder(android.R.color.darker_gray).into(h.ivThumb);
            }
            h.itemView.setOnClickListener(v -> click.onClick(r));
        }

        @Override public int getItemCount() { return reels.size(); }

        static String fmt(long n) {
            if (n >= 1_000_000) return String.format(Locale.US, "%.1fM", n / 1_000_000.0);
            if (n >= 1_000)     return String.format(Locale.US, "%.1fK", n / 1_000.0);
            return String.valueOf(n);
        }

        static class VH extends RecyclerView.ViewHolder {
            TextView  tvRank, tvCaption, tvViews, tvLikes;
            ImageView ivThumb;
            VH(View v) {
                super(v);
                tvRank    = v.findViewById(R.id.tv_dash_reel_rank);
                tvCaption = v.findViewById(R.id.tv_dash_reel_caption);
                tvViews   = v.findViewById(R.id.tv_dash_reel_views);
                tvLikes   = v.findViewById(R.id.tv_dash_reel_likes);
                ivThumb   = v.findViewById(R.id.iv_dash_reel_thumb);
            }
        }
    }
}
