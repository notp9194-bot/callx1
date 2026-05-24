package com.callx.app.activities;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.adapters.YouTubeVideoAdapter;
import com.callx.app.models.YouTubeVideo;
import com.callx.app.utils.YouTubeFirebaseUtils;
import com.callx.app.youtube.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Creator Studio — analytics dashboard showing:
 * - Total views, watch time, subscribers, likes
 * - Per-video breakdown (title, views, likes, comments, watch time)
 * - Live streams management
 * - Quick links: Upload Video, Schedule Upload, Community Post, Go Live
 */
public class YouTubeCreatorStudioActivity extends AppCompatActivity {

    private TextView        tvTotalViews, tvTotalSubs, tvTotalWatchTime,
                            tvTotalVideos, tvTotalLikes;
    private RecyclerView    rvVideos;
    private YouTubeVideoAdapter adapter;
    private String          myUid;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_youtube_creator_studio);

        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";

        View btnBack = findViewById(R.id.btn_yt_studio_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        tvTotalViews     = findViewById(R.id.tv_yt_studio_views);
        tvTotalSubs      = findViewById(R.id.tv_yt_studio_subs);
        tvTotalWatchTime = findViewById(R.id.tv_yt_studio_watchtime);
        tvTotalVideos    = findViewById(R.id.tv_yt_studio_videos);
        tvTotalLikes     = findViewById(R.id.tv_yt_studio_likes);

        rvVideos = findViewById(R.id.rv_yt_studio_videos);
        adapter  = new YouTubeVideoAdapter(this, new ArrayList<>(), video ->
            startActivity(new Intent(this, YouTubePlayerActivity.class)
                .putExtra("video_id", video.videoId)));
        rvVideos.setLayoutManager(new LinearLayoutManager(this));
        rvVideos.setAdapter(adapter);

        // Quick action buttons
        View btnUpload    = findViewById(R.id.btn_yt_studio_upload);
        View btnSchedule  = findViewById(R.id.btn_yt_studio_schedule);
        View btnCommunity = findViewById(R.id.btn_yt_studio_community);
        View btnGoLive    = findViewById(R.id.btn_yt_studio_go_live);

        if (btnUpload    != null) btnUpload.setOnClickListener(v ->
            startActivity(new Intent(this, YouTubeUploadActivity.class)));
        if (btnSchedule  != null) btnSchedule.setOnClickListener(v ->
            startActivity(new Intent(this, YouTubeScheduleUploadActivity.class)));
        if (btnCommunity != null) btnCommunity.setOnClickListener(v ->
            startActivity(new Intent(this, YouTubeCommunityActivity.class)
                .putExtra("uid", myUid)));
        if (btnGoLive    != null) btnGoLive.setOnClickListener(v ->
            startActivity(new Intent(this, YouTubeLiveActivity.class)));

        loadChannelStats();
        loadMyVideos();
    }

    private void loadChannelStats() {
        if (myUid.isEmpty()) return;
        YouTubeFirebaseUtils.channelRef(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    Long views  = snap.child("totalViews").getValue(Long.class);
                    Long subs   = snap.child("subscriberCount").getValue(Long.class);
                    Long videos = snap.child("videoCount").getValue(Long.class);

                    if (tvTotalViews  != null) tvTotalViews.setText(formatCount(views != null ? views : 0));
                    if (tvTotalSubs   != null) tvTotalSubs.setText(formatCount(subs != null ? subs : 0));
                    if (tvTotalVideos != null) tvTotalVideos.setText(String.valueOf(videos != null ? videos : 0));
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });

        // Aggregate likes + watch time from all videos
        YouTubeFirebaseUtils.userVideosRef(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    List<String> ids = new ArrayList<>();
                    for (DataSnapshot ds : snap.getChildren())
                        if (ds.getKey() != null) ids.add(ds.getKey());
                    aggregateVideoStats(ids);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void aggregateVideoStats(List<String> ids) {
        final long[] totalLikes = {0}, totalWT = {0};
        final int[]  count      = {0};
        if (ids.isEmpty()) return;
        for (String id : ids) {
            YouTubeFirebaseUtils.videoRef(id)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        Long likes = snap.child("likeCount").getValue(Long.class);
                        Long wt    = snap.child("watchTimeSeconds").getValue(Long.class);
                        totalLikes[0] += likes != null ? likes : 0;
                        totalWT[0]    += wt    != null ? wt    : 0;
                        if (++count[0] == ids.size()) {
                            if (tvTotalLikes     != null) tvTotalLikes.setText(formatCount(totalLikes[0]));
                            if (tvTotalWatchTime != null) tvTotalWatchTime.setText(formatWatchTime(totalWT[0]));
                        }
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) { count[0]++; }
                });
        }
    }

    private void loadMyVideos() {
        if (myUid.isEmpty()) return;
        YouTubeFirebaseUtils.userVideosRef(myUid)
            .orderByValue().limitToLast(50)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    List<String> ids = new ArrayList<>();
                    for (DataSnapshot ds : snap.getChildren())
                        if (ds.getKey() != null) ids.add(0, ds.getKey());
                    fetchVideos(ids);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void fetchVideos(List<String> ids) {
        List<YouTubeVideo> list = new ArrayList<>();
        if (ids.isEmpty()) { adapter.setData(list); return; }
        final int[] count = {0};
        for (String id : ids) {
            YouTubeFirebaseUtils.videoRef(id)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        YouTubeVideo v = snap.getValue(YouTubeVideo.class);
                        if (v != null) list.add(v);
                        if (++count[0] == ids.size()) {
                            list.sort((a, b) -> Long.compare(b.uploadedAt, a.uploadedAt));
                            adapter.setData(list);
                        }
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {
                        if (++count[0] == ids.size()) adapter.setData(list);
                    }
                });
        }
    }

    private String formatCount(long n) {
        if (n >= 1_000_000_000) return String.format("%.1fB", n / 1_000_000_000.0);
        if (n >= 1_000_000)     return String.format("%.1fM", n / 1_000_000.0);
        if (n >= 1_000)         return String.format("%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }

    private String formatWatchTime(long secs) {
        if (secs >= 3600) return String.format("%.1f hrs", secs / 3600.0);
        if (secs >= 60)   return (secs / 60) + " mins";
        return secs + " secs";
    }
}
