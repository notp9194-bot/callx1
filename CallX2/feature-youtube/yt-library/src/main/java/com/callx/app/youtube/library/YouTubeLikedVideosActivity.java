package com.callx.app.youtube.library;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.youtube.core.adapters.YouTubeVideoAdapter;
import com.callx.app.youtube.core.models.YouTubeVideo;
import com.callx.app.youtube.core.utils.YouTubeFirebaseUtils;
import com.callx.app.youtube.library.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.ArrayList;
import java.util.List;

public class YouTubeLikedVideosActivity extends AppCompatActivity {

    private RecyclerView        rvLiked;
    private YouTubeVideoAdapter adapter;
    private String              myUid;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_youtube_liked);

        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";

        View btnBack = findViewById(R.id.btn_yt_liked_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        adapter = new YouTubeVideoAdapter(this, new ArrayList<>(), video ->
            startActivity(new Intent(this, YouTubePlayerActivity.class)
                .putExtra("video_id", video.videoId)));
        rvLiked = findViewById(R.id.rv_yt_liked);
        rvLiked.setLayoutManager(new LinearLayoutManager(this));
        rvLiked.setAdapter(adapter);

        loadLiked();
    }

    private void loadLiked() {
        if (myUid.isEmpty()) return;
        YouTubeFirebaseUtils.likedVideosRef(myUid)
            .orderByValue().limitToLast(50)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    List<String> ids = new ArrayList<>();
                    for (DataSnapshot ds : snap.getChildren()) ids.add(0, ds.getKey());
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
