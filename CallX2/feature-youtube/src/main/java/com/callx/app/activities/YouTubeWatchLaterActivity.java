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

/** Watch Later with swipe-to-remove individual items. */
public class YouTubeWatchLaterActivity extends AppCompatActivity {

    private RecyclerView        rvWatchLater;
    private TextView            tvEmpty;
    private YouTubeVideoAdapter adapter;
    private String              myUid;
    private final List<String>  videoIds = new ArrayList<>();

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_youtube_watch_later);

        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";

        View btnBack = findViewById(R.id.btn_yt_watch_later_back);
        tvEmpty      = findViewById(R.id.tv_yt_watch_later_empty);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        adapter = new YouTubeVideoAdapter(this, new ArrayList<>(), video ->
            startActivity(new Intent(this, YouTubePlayerActivity.class)
                .putExtra("video_id", video.videoId)));
        rvWatchLater = findViewById(R.id.rv_yt_watch_later);
        rvWatchLater.setLayoutManager(new LinearLayoutManager(this));
        rvWatchLater.setAdapter(adapter);

        // Swipe to remove
        new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
                0, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
            @Override public boolean onMove(@NonNull RecyclerView rv,
                    @NonNull RecyclerView.ViewHolder vh, @NonNull RecyclerView.ViewHolder t) {
                return false;
            }
            @Override public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int dir) {
                int pos = vh.getAdapterPosition();
                if (pos >= 0 && pos < videoIds.size()) {
                    String vid = videoIds.get(pos);
                    YouTubeFirebaseUtils.watchLaterRef(myUid).child(vid).removeValue();
                    videoIds.remove(pos);
                    adapter.removeAt(pos);
                    if (videoIds.isEmpty() && tvEmpty != null)
                        tvEmpty.setVisibility(View.VISIBLE);
                }
            }
        }).attachToRecyclerView(rvWatchLater);

        loadWatchLater();
    }

    private void loadWatchLater() {
        if (myUid.isEmpty()) return;
        YouTubeFirebaseUtils.watchLaterRef(myUid)
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
                    if (count[0] == ids.size()) adapter.setData(videos);
                }
            });
        }
    }

    /** Required by adapter.removeAt() — pass-through helper */
    private final List<String> ids = videoIds;
}
