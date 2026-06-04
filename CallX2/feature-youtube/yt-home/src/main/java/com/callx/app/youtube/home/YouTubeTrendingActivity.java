package com.callx.app.youtube.home;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.youtube.core.models.YouTubeVideo;
import com.callx.app.youtube.core.navigator.YTNavigatorProvider;
import com.callx.app.youtube.core.utils.YouTubeFirebaseUtils;
import com.callx.app.youtube.core.adapters.YouTubeVideoAdapter;
import com.google.firebase.database.*;
import java.util.ArrayList;
import java.util.List;

/**
 * YouTubeTrendingActivity — Shows trending videos from Firebase.
 * Part of yt-home module.
 */
public class YouTubeTrendingActivity extends AppCompatActivity {

    private RecyclerView rvTrending;
    private YouTubeVideoAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_youtube_trending);
        rvTrending = findViewById(R.id.rv_yt_trending);
        rvTrending.setLayoutManager(new LinearLayoutManager(this));
        adapter = new YouTubeVideoAdapter(this, new ArrayList<>(),
            video -> YTNavigatorProvider.get().openPlayer(this, video.videoId));
        rvTrending.setAdapter(adapter);
        loadTrending();
    }

    private void loadTrending() {
        YouTubeFirebaseUtils.trendingRef()
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    List<YouTubeVideo> list = new ArrayList<>();
                    for (DataSnapshot ds : snap.getChildren()) {
                        YouTubeVideo v = ds.getValue(YouTubeVideo.class);
                        if (v != null) list.add(v);
                    }
                    adapter.setData(list);
                }
                @Override public void onCancelled(DatabaseError e) {}
            });
    }
}
