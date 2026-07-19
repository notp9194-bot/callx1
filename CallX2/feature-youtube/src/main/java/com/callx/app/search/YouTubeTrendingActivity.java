package com.callx.app.search;

import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.home.YouTubeVideoAdapter;
import com.callx.app.models.YouTubeVideo;
import com.callx.app.player.YouTubePlayerActivity;
import com.callx.app.utils.YouTubeFirebaseUtils;
import com.callx.app.youtube.R;
import com.google.firebase.database.*;
import java.util.ArrayList;
import java.util.List;

/**
 * YouTubeTrendingActivity — Proper trending page with toolbar, filter chips, and video list.
 * Fixed to use its own dedicated layout instead of the history layout.
 */
public class YouTubeTrendingActivity extends AppCompatActivity {

    private RecyclerView        rvTrending;
    private YouTubeVideoAdapter adapter;
    private TextView            tvEmpty;
    private static final String[] TRENDING_TABS = {"All", "Music", "Gaming", "News", "Sports"};

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_yt_trending);

        Toolbar toolbar = findViewById(R.id.toolbar_yt_trending);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Trending");
        }

        tvEmpty    = findViewById(R.id.tv_yt_trending_empty);
        rvTrending = findViewById(R.id.rv_yt_trending_list);

        adapter = new YouTubeVideoAdapter(this, new ArrayList<>(), video ->
            startActivity(new Intent(this, YouTubePlayerActivity.class)
                .putExtra("video_id", video.videoId)));
        rvTrending.setLayoutManager(new LinearLayoutManager(this));
        rvTrending.setAdapter(adapter);

        loadTrending("All");
    }

    private void loadTrending(String category) {
        if (tvEmpty != null) tvEmpty.setVisibility(View.GONE);

        DatabaseReference ref = YouTubeFirebaseUtils.globalFeedRef();
        if ("All".equals(category)) {
            ref.orderByChild("viewCount").limitToLast(50)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        List<YouTubeVideo> list = new ArrayList<>();
                        for (DataSnapshot ds : snap.getChildren()) {
                            YouTubeVideo v = ds.getValue(YouTubeVideo.class);
                            if (v != null && "public".equals(v.visibility)) list.add(v);
                        }
                        list.sort((a, b) -> Long.compare(b.viewCount, a.viewCount));
                        adapter.setData(list);
                        if (tvEmpty != null)
                            tvEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {
                        if (tvEmpty != null) tvEmpty.setVisibility(View.VISIBLE);
                    }
                });
        } else {
            ref.orderByChild("category").equalTo(category)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        List<YouTubeVideo> list = new ArrayList<>();
                        for (DataSnapshot ds : snap.getChildren()) {
                            YouTubeVideo v = ds.getValue(YouTubeVideo.class);
                            if (v != null && "public".equals(v.visibility)) list.add(v);
                        }
                        list.sort((a, b) -> Long.compare(b.viewCount, a.viewCount));
                        adapter.setData(list);
                        if (tvEmpty != null)
                            tvEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {
                        if (tvEmpty != null) tvEmpty.setVisibility(View.VISIBLE);
                    }
                });
        }
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }
}
