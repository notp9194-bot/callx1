package com.callx.app.activities;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.models.YouTubeVideo;
import com.callx.app.utils.YouTubeFirebaseUtils;
import com.callx.app.youtube.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Downloads / Offline Videos activity.
 * Shows videos the user has saved for offline viewing.
 * Actual offline download logic requires WorkManager + local storage.
 * This activity manages the download list in Firebase.
 */
public class YouTubeDownloadsActivity extends AppCompatActivity {

    private RecyclerView rvDownloads;
    private DownloadAdapter adapter;
    private String myUid;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_youtube_downloads);

        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";

        View btnBack = findViewById(R.id.btn_yt_downloads_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        rvDownloads = findViewById(R.id.rv_yt_downloads);
        adapter     = new DownloadAdapter();
        rvDownloads.setLayoutManager(new LinearLayoutManager(this));
        rvDownloads.setAdapter(adapter);

        loadDownloads();
    }

    private void loadDownloads() {
        if (myUid.isEmpty()) return;
        YouTubeFirebaseUtils.downloadsRef(myUid)
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
        if (ids.isEmpty()) {
            adapter.setData(list);
            View tvEmpty = findViewById(R.id.tv_yt_downloads_empty);
            if (tvEmpty != null) tvEmpty.setVisibility(View.VISIBLE);
            return;
        }
        final int[] count = {0};
        for (String id : ids) {
            YouTubeFirebaseUtils.videoRef(id)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        YouTubeVideo v = snap.getValue(YouTubeVideo.class);
                        if (v != null) list.add(v);
                        if (++count[0] == ids.size()) adapter.setData(list);
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {
                        if (++count[0] == ids.size()) adapter.setData(list);
                    }
                });
        }
    }

    class DownloadAdapter extends RecyclerView.Adapter<DownloadAdapter.DVH> {
        private List<YouTubeVideo> data = new ArrayList<>();
        void setData(List<YouTubeVideo> d) { data = d; notifyDataSetChanged(); }

        @NonNull @Override
        public DVH onCreateViewHolder(@NonNull ViewGroup p, int t) {
            View v = LayoutInflater.from(YouTubeDownloadsActivity.this)
                .inflate(R.layout.item_yt_video, p, false);
            return new DVH(v);
        }

        @Override public void onBindViewHolder(@NonNull DVH h, int pos) {
            YouTubeVideo v = data.get(pos);
            h.tvTitle.setText(v.title);
            h.tvChannel.setText(v.uploaderName);
            h.itemView.setOnClickListener(x ->
                startActivity(new Intent(YouTubeDownloadsActivity.this,
                    YouTubePlayerActivity.class).putExtra("video_id", v.videoId)));
            if (h.btnDelete != null)
                h.btnDelete.setOnClickListener(x -> {
                    YouTubeFirebaseUtils.downloadsRef(myUid).child(v.videoId).removeValue();
                    data.remove(v);
                    notifyDataSetChanged();
                    Toast.makeText(YouTubeDownloadsActivity.this,
                        "Removed from downloads", Toast.LENGTH_SHORT).show();
                });
        }

        @Override public int getItemCount() { return data.size(); }

        class DVH extends RecyclerView.ViewHolder {
            TextView tvTitle, tvChannel;
            View     btnDelete;
            DVH(View v) {
                super(v);
                tvTitle   = v.findViewById(R.id.tv_yt_video_title);
                tvChannel = v.findViewById(R.id.tv_yt_channel_name);
                btnDelete = v.findViewById(R.id.btn_yt_download_delete);
            }
        }
    }
}
