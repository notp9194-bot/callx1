package com.callx.app.activities;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.adapters.YouTubeVideoAdapter;
import com.callx.app.models.YouTubePlaylist;
import com.callx.app.models.YouTubeVideo;
import com.callx.app.utils.YouTubeFirebaseUtils;
import com.callx.app.youtube.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Full playlist management — view playlist, add/remove videos, rename, change privacy.
 */
public class YouTubePlaylistActivity extends AppCompatActivity {

    private RecyclerView        rvVideos;
    private YouTubeVideoAdapter adapter;
    private TextView            tvTitle, tvVideoCount, tvPrivacy;
    private String              ownerUid, playlistId, myUid;
    private boolean             isOwner;
    private final List<String>  videoIds = new ArrayList<>();

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_youtube_playlist);

        ownerUid   = getIntent().getStringExtra("owner_uid");
        playlistId = getIntent().getStringExtra("playlist_id");
        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";
        isOwner = myUid.equals(ownerUid);

        View btnBack  = findViewById(R.id.btn_yt_playlist_back);
        View btnEdit  = findViewById(R.id.btn_yt_playlist_edit);
        View btnShare = findViewById(R.id.btn_yt_playlist_share);
        tvTitle       = findViewById(R.id.tv_yt_playlist_title);
        tvVideoCount  = findViewById(R.id.tv_yt_playlist_count);
        tvPrivacy     = findViewById(R.id.tv_yt_playlist_privacy);
        rvVideos      = findViewById(R.id.rv_yt_playlist_videos);

        if (btnBack  != null) btnBack.setOnClickListener(v -> finish());
        if (btnEdit  != null) {
            btnEdit.setVisibility(isOwner ? View.VISIBLE : View.GONE);
            btnEdit.setOnClickListener(v -> showEditDialog());
        }
        if (btnShare != null) btnShare.setOnClickListener(v -> sharePlaylist());

        adapter = new YouTubeVideoAdapter(this, new ArrayList<>(), video ->
            startActivity(new Intent(this, YouTubePlayerActivity.class)
                .putExtra("video_id", video.videoId)));
        if (isOwner) {
            adapter.setOptionsCallback(new com.callx.app.sheets.YouTubeVideoOptionsSheet.OptionsCallback() {
                @Override public void onNotInterested(String vid) {}
                @Override public void onVideoDeleted(String vid) { removeFromPlaylist(vid); }
            });
        }
        rvVideos.setLayoutManager(new LinearLayoutManager(this));
        rvVideos.setAdapter(adapter);

        loadPlaylist();
    }

    private void loadPlaylist() {
        if (ownerUid == null || playlistId == null) return;
        YouTubeFirebaseUtils.playlistRef(ownerUid, playlistId)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    String title   = snap.child("title").getValue(String.class);
                    String privacy = snap.child("privacy").getValue(String.class);
                    if (tvTitle   != null) tvTitle.setText(title != null ? title : "Playlist");
                    if (tvPrivacy != null) tvPrivacy.setText(
                        "public".equals(privacy) ? "Public" : "Private");
                    loadPlaylistVideos();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void loadPlaylistVideos() {
        YouTubeFirebaseUtils.playlistVideosRef(ownerUid, playlistId)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    videoIds.clear();
                    for (DataSnapshot ds : snap.getChildren()) {
                        if (ds.getKey() != null) videoIds.add(ds.getKey());
                    }
                    if (tvVideoCount != null)
                        tvVideoCount.setText(videoIds.size() + " videos");
                    fetchVideoDetails();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void fetchVideoDetails() {
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

    private void removeFromPlaylist(String videoId) {
        YouTubeFirebaseUtils.playlistVideosRef(ownerUid, playlistId).child(videoId).removeValue();
        YouTubeFirebaseUtils.playlistRef(ownerUid, playlistId).child("videoCount")
            .setValue(ServerValue.increment(-1));
        videoIds.remove(videoId);
        if (tvVideoCount != null) tvVideoCount.setText(videoIds.size() + " videos");
        Toast.makeText(this, "Removed from playlist", Toast.LENGTH_SHORT).show();
        loadPlaylistVideos();
    }

    private void showEditDialog() {
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("Edit Playlist");
        View v = getLayoutInflater().inflate(android.R.layout.simple_list_item_2, null);
        // Simple rename dialog using EditText
        final EditText et = new EditText(this);
        et.setHint("Playlist title");
        et.setText(tvTitle != null ? tvTitle.getText().toString() : "");
        b.setView(et);
        b.setPositiveButton("Save", (d, w) -> {
            String newTitle = et.getText().toString().trim();
            if (!newTitle.isEmpty()) {
                YouTubeFirebaseUtils.playlistRef(ownerUid, playlistId)
                    .child("title").setValue(newTitle);
                if (tvTitle != null) tvTitle.setText(newTitle);
            }
        });
        b.setNegativeButton("Cancel", null);
        b.setNeutralButton("Delete Playlist", (d, w) -> confirmDeletePlaylist());
        b.show();
    }

    private void confirmDeletePlaylist() {
        new AlertDialog.Builder(this)
            .setTitle("Delete Playlist")
            .setMessage("This will permanently delete the playlist.")
            .setPositiveButton("Delete", (d, w) -> {
                YouTubeFirebaseUtils.playlistRef(ownerUid, playlistId).removeValue();
                YouTubeFirebaseUtils.playlistVideosRef(ownerUid, playlistId).removeValue();
                Toast.makeText(this, "Playlist deleted", Toast.LENGTH_SHORT).show();
                finish();
            })
            .setNegativeButton("Cancel", null).show();
    }

    private void sharePlaylist() {
        String title = tvTitle != null ? tvTitle.getText().toString() : "Playlist";
        String msg = title + "\nhttps://callx.app/playlist?uid=" + ownerUid + "&id=" + playlistId;
        Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, msg);
        startActivity(Intent.createChooser(share, "Share playlist"));
    }
}
