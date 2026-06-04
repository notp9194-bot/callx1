package com.callx.app.youtube.library;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.youtube.home.YouTubeVideoAdapter;
import com.callx.app.youtube.core.models.YouTubeVideo;
import com.callx.app.youtube.core.utils.YouTubeFirebaseUtils;
import com.callx.app.youtube.library.R;
import com.google.firebase.database.*;
import java.util.ArrayList;
import java.util.List;

public class YouTubePlaylistActivity extends AppCompatActivity {

    private RecyclerView        rvVideos;
    private YouTubeVideoAdapter adapter;
    private TextView            tvTitle;
    private String              ownerUid, playlistId;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_youtube_playlist);

        ownerUid   = getIntent().getStringExtra("owner_uid");
        playlistId = getIntent().getStringExtra("playlist_id");

        View btnBack = findViewById(R.id.btn_yt_playlist_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        tvTitle  = findViewById(R.id.tv_yt_playlist_title);
        rvVideos = findViewById(R.id.rv_yt_playlist_videos);

        adapter = new YouTubeVideoAdapter(this, new ArrayList<>(), video ->
            startActivity(new Intent(this, YouTubePlayerActivity.class)
                .putExtra("video_id", video.videoId)));
        rvVideos.setLayoutManager(new LinearLayoutManager(this));
        rvVideos.setAdapter(adapter);

        loadPlaylist();
    }

    private void loadPlaylist() {
        if (ownerUid == null || playlistId == null) return;
        YouTubeFirebaseUtils.playlistRef(ownerUid, playlistId)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    String title = snap.child("title").getValue(String.class);
                    tvTitle.setText(title != null ? title : "Playlist");
                    List<String> ids = new ArrayList<>();
                    DataSnapshot vids = snap.child("videoIds");
                    for (DataSnapshot ds : vids.getChildren()) {
                        String id = ds.getValue(String.class);
                        if (id != null) ids.add(id);
                    }
                    fetchVideos(ids);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void fetchVideos(List<String> ids) {
        List<YouTubeVideo> videos = new ArrayList<>();
        if (ids.isEmpty()) { adapter.setData(videos); return; }
        final int[] count = {0};
        for (String id : ids) {
            YouTubeFirebaseUtils.videoRef(id).addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    YouTubeVideo v = snap.getValue(YouTubeVideo.class);
                    if (v != null) videos.add(v);
                    count[0]++;
                    if (count[0] == ids.size()) adapter.setData(videos);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    count[0]++;
                    if (count[0] == ids.size()) adapter.setData(videos);
                }
            });
        }
    }
}
