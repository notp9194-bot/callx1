package com.callx.app.activities;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.models.YouTubePlaylist;
import com.callx.app.utils.YouTubeFirebaseUtils;
import com.callx.app.youtube.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.ArrayList;
import java.util.List;

/** Lists all playlists for a user. Tap to view, + to create new. */
public class YouTubePlaylistActivity extends AppCompatActivity {

    private RecyclerView rvPlaylists;
    private PlaylistAdapter adapter;
    private String targetUid, myUid;
    private ValueEventListener listener;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_youtube_playlists);

        myUid     = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";
        targetUid = getIntent().getStringExtra("uid");
        if (targetUid == null) targetUid = myUid;

        View btnBack = findViewById(R.id.btn_yt_playlists_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        View btnCreate = findViewById(R.id.btn_yt_create_playlist);
        if (btnCreate != null) {
            btnCreate.setVisibility(targetUid.equals(myUid) ? View.VISIBLE : View.GONE);
            btnCreate.setOnClickListener(v ->
                startActivity(new Intent(this, YouTubeCreatePlaylistActivity.class)));
        }

        rvPlaylists = findViewById(R.id.rv_yt_playlists);
        adapter     = new PlaylistAdapter();
        rvPlaylists.setLayoutManager(new LinearLayoutManager(this));
        rvPlaylists.setAdapter(adapter);

        loadPlaylists();
    }

    private void loadPlaylists() {
        listener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                List<YouTubePlaylist> list = new ArrayList<>();
                for (DataSnapshot ds : snap.getChildren()) {
                    YouTubePlaylist p = ds.getValue(YouTubePlaylist.class);
                    if (p != null) list.add(0, p);
                }
                adapter.setData(list);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        YouTubeFirebaseUtils.playlistsRef(targetUid).addValueEventListener(listener);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (listener != null)
            YouTubeFirebaseUtils.playlistsRef(targetUid).removeEventListener(listener);
    }

    class PlaylistAdapter extends RecyclerView.Adapter<PlaylistAdapter.PVH> {
        private List<YouTubePlaylist> data = new ArrayList<>();
        void setData(List<YouTubePlaylist> d) { data = d; notifyDataSetChanged(); }

        @NonNull @Override
        public PVH onCreateViewHolder(@NonNull ViewGroup p, int t) {
            View v = LayoutInflater.from(YouTubePlaylistActivity.this)
                .inflate(R.layout.item_yt_playlist, p, false);
            return new PVH(v);
        }

        @Override public void onBindViewHolder(@NonNull PVH h, int pos) {
            YouTubePlaylist pl = data.get(pos);
            h.tvTitle.setText(pl.title);
            h.tvCount.setText(pl.videoCount + " videos • " + pl.visibility);
            if (pl.thumbnailUrl != null && h.ivThumb != null)
                Glide.with(YouTubePlaylistActivity.this).load(pl.thumbnailUrl)
                    .centerCrop().into(h.ivThumb);
            h.itemView.setOnClickListener(v ->
                startActivity(new Intent(YouTubePlaylistActivity.this,
                    YouTubePlaylistViewActivity.class)
                    .putExtra("playlist_id", pl.playlistId)
                    .putExtra("uid", targetUid)));
        }

        @Override public int getItemCount() { return data.size(); }

        class PVH extends RecyclerView.ViewHolder {
            ImageView ivThumb;
            TextView  tvTitle, tvCount;
            PVH(View v) {
                super(v);
                ivThumb  = v.findViewById(R.id.iv_yt_playlist_thumb);
                tvTitle  = v.findViewById(R.id.tv_yt_playlist_title);
                tvCount  = v.findViewById(R.id.tv_yt_playlist_count);
            }
        }
    }
}
