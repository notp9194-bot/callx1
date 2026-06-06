package com.callx.app.search;

import com.callx.app.player.YouTubePlayerActivity;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.home.YouTubeVideoAdapter;
import com.callx.app.models.YouTubeVideo;
import com.callx.app.utils.YouTubeFirebaseUtils;
import com.callx.app.youtube.R;
import com.google.firebase.database.*;
import java.util.ArrayList;
import java.util.List;

public class YouTubeTrendingActivity extends AppCompatActivity {
    private RecyclerView        rvTrending;
    private YouTubeVideoAdapter adapter;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_youtube_history);
        View btnBack = findViewById(R.id.btn_yt_history_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        rvTrending = findViewById(R.id.rv_yt_history);
        adapter = new YouTubeVideoAdapter(this, new ArrayList<>(), video ->
            startActivity(new Intent(this, YouTubePlayerActivity.class)
                .putExtra("video_id", video.videoId)));
        rvTrending.setLayoutManager(new LinearLayoutManager(this));
        rvTrending.setAdapter(adapter);

        YouTubeFirebaseUtils.globalFeedRef()
            .orderByChild("viewCount").limitToLast(30)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    List<YouTubeVideo> list = new ArrayList<>();
                    for (DataSnapshot ds : snap.getChildren()) {
                        YouTubeVideo v = ds.getValue(YouTubeVideo.class);
                        if (v != null) list.add(v);
                    }
                    list.sort((a, b) -> Long.compare(b.viewCount, a.viewCount));
                    adapter.setData(list);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }
}
