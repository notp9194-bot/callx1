package com.callx.app.activities;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.ItemTouchHelper;
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

/** Liked videos list with swipe-to-unlike. */
public class YouTubeLikedVideosActivity extends AppCompatActivity {

    private RecyclerView        rvLiked;
    private TextView            tvEmpty;
    private YouTubeVideoAdapter adapter;
    private String              myUid;
    private final List<String>  videoIds = new ArrayList<>();

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_youtube_liked);

        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";

        View btnBack = findViewById(R.id.btn_yt_liked_back);
        tvEmpty      = findViewById(R.id.tv_yt_liked_empty);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        adapter = new YouTubeVideoAdapter(this, new ArrayList<>(), video ->
            startActivity(new Intent(this, YouTubePlayerActivity.class)
                .putExtra("video_id", video.videoId)));
        rvLiked = findViewById(R.id.rv_yt_liked);
        rvLiked.setLayoutManager(new LinearLayoutManager(this));
        rvLiked.setAdapter(adapter);

        // Swipe to unlike
        new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
                0, ItemTouchHelper.LEFT) {
            @Override public boolean onMove(@NonNull RecyclerView rv,
                    @NonNull RecyclerView.ViewHolder vh, @NonNull RecyclerView.ViewHolder t) {
                return false;
            }
            @Override public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int dir) {
                int pos = vh.getAdapterPosition();
                if (pos >= 0 && pos < videoIds.size()) {
                    String vid = videoIds.get(pos);
                    YouTubeFirebaseUtils.likedVideosRef(myUid).child(vid).removeValue();
                    YouTubeFirebaseUtils.videoLikesRef(vid).child(myUid).removeValue();
                    YouTubeFirebaseUtils.videoRef(vid).child("likeCount")
                        .setValue(ServerValue.increment(-1));
                    videoIds.remove(pos);
                    adapter.removeAt(pos);
                    if (videoIds.isEmpty() && tvEmpty != null)
                        tvEmpty.setVisibility(View.VISIBLE);
                }
            }
        }).attachToRecyclerView(rvLiked);

        loadLiked();
    }

    private void loadLiked() {
        if (myUid.isEmpty()) return;
        YouTubeFirebaseUtils.likedVideosRef(myUid)
            .orderByValue().limitToLast(50)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    videoIds.clear();
                    for (DataSnapshot ds : snap.getChildren()) videoIds.add(0, ds.getKey());
                    if (tvEmpty != null)
                        tvEmpty.setVisibility(videoIds.isEmpty() ? View.VISIBLE : View.GONE);
                    fetchVideos();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void fetchVideos() {
        List<YouTubeVideo> videos = new ArrayList<>();
        if (videoIds.isEmpty()) { adapter.setData(videos); return; }
        final int[] count = {0};
        for (String id : videoIds) {
            YouTubeFirebaseUtils.videoRef(id).addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    YouTubeVideo v = snap.getValue(YouTubeVideo.class);
                    if (v != null) videos.add(v);
                    count[0]++;
                    if (count[0] == videoIds.size()) adapter.setData(videos);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    count[0]++;
                    if (count[0] == videoIds.size()) adapter.setData(videos);
                }
            });
        }
    }
}
