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
import com.google.firebase.database.*;
import java.util.ArrayList;
import java.util.List;

/** Shows all videos in a playlist and plays them sequentially. */
public class YouTubePlaylistViewActivity extends AppCompatActivity {

    private RecyclerView        rvVideos;
    private YouTubeVideoAdapter adapter;
    private String              playlistId, uid;
    private List<YouTubeVideo>  videos = new ArrayList<>();

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_youtube_playlist_view);

        playlistId = getIntent().getStringExtra("playlist_id");
        uid        = getIntent().getStringExtra("uid");

        View btnBack = findViewById(R.id.btn_yt_plview_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        rvVideos = findViewById(R.id.rv_yt_plview_videos);
        adapter  = new YouTubeVideoAdapter(this, new ArrayList<>(), video -> {
            int pos = videos.indexOf(video);
            startActivity(new Intent(this, YouTubePlayerActivity.class)
                .putExtra("video_id", video.videoId));
        });
        rvVideos.setLayoutManager(new LinearLayoutManager(this));
        rvVideos.setAdapter(adapter);

        loadPlaylist();
    }

    private void loadPlaylist() {
        YouTubeFirebaseUtils.playlistRef(uid, playlistId)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    String title = snap.child("title").getValue(String.class);
                    TextView tv = findViewById(R.id.tv_yt_plview_title);
                    if (tv != null && title != null) tv.setText(title);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });

        YouTubeFirebaseUtils.playlistVideosRef(uid, playlistId)
            .orderByValue()
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
        videos.clear();
        if (ids.isEmpty()) { adapter.setData(new ArrayList<>()); return; }
        final int[] count = {0};
        for (String id : ids) {
            YouTubeFirebaseUtils.videoRef(id)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        YouTubeVideo v = snap.getValue(YouTubeVideo.class);
                        if (v != null) videos.add(v);
                        if (++count[0] == ids.size()) adapter.setData(new ArrayList<>(videos));
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {
                        if (++count[0] == ids.size()) adapter.setData(new ArrayList<>(videos));
                    }
                });
        }
    }
}
