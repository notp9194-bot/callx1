package com.callx.app.downloads;

import com.callx.app.player.YouTubePlayerActivity;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.downloads.YouTubeDownloadManager;
import com.callx.app.utils.YouTubeFirebaseUtils;
import com.callx.app.youtube.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * YouTubeDownloadsActivity
 * Real YouTube jaisi — downloaded videos offline dekho
 * Library tab se open hota hai
 */
public class YouTubeDownloadsActivity extends AppCompatActivity {

    private RecyclerView    rvDownloads;
    private TextView        tvEmpty;
    private View            pbLoading;
    private DownloadedAdapter adapter;
    private final List<DownloadItem> items = new ArrayList<>();

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_youtube_downloads);

        findViewById(R.id.btn_yt_dl_back).setOnClickListener(v -> onBackPressed());

        rvDownloads = findViewById(R.id.rv_yt_downloads);
        tvEmpty     = findViewById(R.id.tv_yt_dl_empty);
        pbLoading   = findViewById(R.id.pb_yt_dl);

        adapter = new DownloadedAdapter(items);
        rvDownloads.setLayoutManager(new LinearLayoutManager(this));
        rvDownloads.setAdapter(adapter);

        loadDownloads();
    }

    private void loadDownloads() {
        pbLoading.setVisibility(View.VISIBLE);
        tvEmpty.setVisibility(View.GONE);

        String uid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";

        if (uid.isEmpty()) {
            showEmpty("Download dekhne ke liye login karo");
            return;
        }

        YouTubeFirebaseUtils.downloadsRef(uid)
            .orderByChild("savedAt")
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    items.clear();

                    // ── FIX: Firebase record + local file dono check karo ─────────
                    // Step 1: Firebase se completed records lo (metadata ke saath)
                    java.util.Set<String> firebaseIds = new java.util.HashSet<>();
                    for (DataSnapshot ds : snap.getChildren()) {
                        String status = ds.child("status").getValue(String.class);
                        if (!"completed".equals(status)) continue;

                        String videoId = ds.child("videoId").getValue(String.class);
                        if (videoId == null) continue;

                        // Local file exist karti hai?
                        File f = YouTubeDownloadManager.getLocalFile(
                            YouTubeDownloadsActivity.this, videoId);
                        if (!f.exists() || f.length() < 1024) continue;

                        firebaseIds.add(videoId);

                        DownloadItem item = new DownloadItem();
                        item.videoId      = videoId;
                        item.title        = ds.child("title").getValue(String.class);
                        item.channelName  = ds.child("channelName").getValue(String.class);
                        item.thumbnailUrl = ds.child("thumbnailUrl").getValue(String.class);
                        Long dur = ds.child("duration").getValue(Long.class);
                        item.duration     = dur != null ? dur : 0;
                        item.localPath    = f.getAbsolutePath();
                        item.fileSizeMb   = f.length() / (1024f * 1024f);
                        items.add(0, item); // newest first
                    }

                    // Step 2: Local disk pe jo files hain par Firebase me record nahi —
                    // unhe bhi dikhao (network fail pe bhi download complete tha)
                    java.util.List<String> localIds =
                        YouTubeDownloadManager.getLocalDownloadedIds(YouTubeDownloadsActivity.this);
                    for (String videoId : localIds) {
                        if (firebaseIds.contains(videoId)) continue; // already added
                        File f = YouTubeDownloadManager.getLocalFile(
                            YouTubeDownloadsActivity.this, videoId);
                        DownloadItem item = new DownloadItem();
                        item.videoId   = videoId;
                        item.title     = videoId; // no metadata available
                        item.localPath = f.getAbsolutePath();
                        item.fileSizeMb = f.length() / (1024f * 1024f);
                        items.add(item);
                    }

                    pbLoading.setVisibility(View.GONE);
                    if (items.isEmpty()) {
                        showEmpty("Koi download nahi hua abhi tak\nVideos download karo offline dekhne ke liye");
                    } else {
                        tvEmpty.setVisibility(View.GONE);
                        adapter.notifyDataSetChanged();
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    // Firebase fail hua — sirf local files dikhao
                    items.clear();
                    java.util.List<String> localIds =
                        YouTubeDownloadManager.getLocalDownloadedIds(YouTubeDownloadsActivity.this);
                    for (String videoId : localIds) {
                        File f = YouTubeDownloadManager.getLocalFile(
                            YouTubeDownloadsActivity.this, videoId);
                        DownloadItem item = new DownloadItem();
                        item.videoId    = videoId;
                        item.title      = videoId;
                        item.localPath  = f.getAbsolutePath();
                        item.fileSizeMb = f.length() / (1024f * 1024f);
                        items.add(item);
                    }
                    pbLoading.setVisibility(View.GONE);
                    if (items.isEmpty()) {
                        showEmpty("Load nahi hua: " + e.getMessage());
                    } else {
                        tvEmpty.setVisibility(View.GONE);
                        adapter.notifyDataSetChanged();
                    }
                }
            });
    }

    private void showEmpty(String msg) {
        pbLoading.setVisibility(View.GONE);
        tvEmpty.setVisibility(View.VISIBLE);
        tvEmpty.setText(msg);
    }

    // ── Inner adapter ─────────────────────────────────────────────────────────

    class DownloadedAdapter extends RecyclerView.Adapter<DownloadedAdapter.VH> {

        private final List<DownloadItem> data;
        DownloadedAdapter(List<DownloadItem> d) { this.data = d; }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int vt) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_yt_downloaded_video, parent, false);
            return new VH(v);
        }

        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            DownloadItem item = data.get(pos);

            h.tvTitle.setText(item.title != null ? item.title : "Unknown");
            h.tvChannel.setText(item.channelName != null ? item.channelName : "");
            h.tvSize.setText(String.format("%.1f MB · %s",
                item.fileSizeMb, formatDur(item.duration)));
            h.tvOfflineBadge.setVisibility(View.VISIBLE);

            Glide.with(YouTubeDownloadsActivity.this)
                .load(item.thumbnailUrl)
                .placeholder(R.drawable.bg_yt_thumb_placeholder)
                .centerCrop()
                .override(720, 720)
                .into(h.ivThumb);

            // Play offline
            h.itemView.setOnClickListener(v -> {
                Intent i = new Intent(YouTubeDownloadsActivity.this,
                    YouTubePlayerActivity.class);
                i.putExtra("video_id",    item.videoId);
                i.putExtra("local_path",  item.localPath); // offline ke liye
                startActivity(i);
            });

            // Delete button
            h.btnDelete.setOnClickListener(v -> {
                new AlertDialog.Builder(YouTubeDownloadsActivity.this)
                    .setTitle("Download Delete karo?")
                    .setMessage("\"" + item.title + "\"\n\nYe video offline nahi dekh paoge.")
                    .setPositiveButton("Delete", (d, w) -> {
                        YouTubeDownloadManager.deleteDownload(
                            YouTubeDownloadsActivity.this, item.videoId);
                        int idx = data.indexOf(item);
                        if (idx >= 0) { data.remove(idx); notifyItemRemoved(idx); }
                        if (data.isEmpty()) showEmpty(
                            "Koi download nahi\nVideos download karo offline dekhne ke liye");
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            });
        }

        @Override public int getItemCount() { return data.size(); }

        class VH extends RecyclerView.ViewHolder {
            ImageView ivThumb;
            TextView  tvTitle, tvChannel, tvSize, tvOfflineBadge;
            ImageButton btnDelete;
            VH(@NonNull View v) {
                super(v);
                ivThumb        = v.findViewById(R.id.iv_yt_dl_thumb);
                tvTitle        = v.findViewById(R.id.tv_yt_dl_title);
                tvChannel      = v.findViewById(R.id.tv_yt_dl_channel);
                tvSize         = v.findViewById(R.id.tv_yt_dl_size);
                tvOfflineBadge = v.findViewById(R.id.tv_yt_dl_offline_badge);
                btnDelete      = v.findViewById(R.id.btn_yt_dl_delete);
            }
        }
    }

    static class DownloadItem {
        String videoId, title, channelName, thumbnailUrl, localPath;
        long   duration;
        float  fileSizeMb;
    }

    private String formatDur(long s) {
        long m = s / 60; long ss = s % 60;
        if (m >= 60) { long h = m/60; m %= 60; return h+":"+p(m)+":"+p(ss); }
        return m + ":" + p(ss);
    }
    private String p(long n) { return n < 10 ? "0"+n : String.valueOf(n); }
}
