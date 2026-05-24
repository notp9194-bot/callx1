package com.callx.app.activities;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.models.YouTubePlaylist;
import com.callx.app.utils.YouTubeFirebaseUtils;
import com.callx.app.youtube.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Pick playlist(s) to add a video to. Shows user's playlists with checkbox. */
public class YouTubePlaylistPickerActivity extends AppCompatActivity {

    private RecyclerView  rvPlaylists;
    private PickerAdapter adapter;
    private String        videoId, myUid;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_youtube_playlist_picker);

        videoId = getIntent().getStringExtra("video_id");
        myUid   = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";

        View btnBack = findViewById(R.id.btn_yt_picker_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        View btnCreate = findViewById(R.id.btn_yt_picker_new);
        if (btnCreate != null) btnCreate.setOnClickListener(v ->
            startActivity(new Intent(this, YouTubeCreatePlaylistActivity.class)));

        View btnDone = findViewById(R.id.btn_yt_picker_done);
        if (btnDone != null) btnDone.setOnClickListener(v -> saveAndClose());

        rvPlaylists = findViewById(R.id.rv_yt_picker_playlists);
        adapter     = new PickerAdapter();
        rvPlaylists.setLayoutManager(new LinearLayoutManager(this));
        rvPlaylists.setAdapter(adapter);

        loadPlaylists();
    }

    private void loadPlaylists() {
        YouTubeFirebaseUtils.playlistsRef(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    List<YouTubePlaylist> list = new ArrayList<>();
                    for (DataSnapshot ds : snap.getChildren()) {
                        YouTubePlaylist p = ds.getValue(YouTubePlaylist.class);
                        if (p != null) list.add(p);
                    }
                    adapter.setData(list, videoId);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void saveAndClose() {
        for (Map.Entry<String, Boolean> e : adapter.getSelections().entrySet()) {
            String plId = e.getKey();
            if (e.getValue()) {
                YouTubeFirebaseUtils.playlistVideosRef(myUid, plId)
                    .child(videoId).setValue(System.currentTimeMillis());
                YouTubeFirebaseUtils.playlistRef(myUid, plId).child("videoCount")
                    .setValue(ServerValue.increment(1));
            }
        }
        Toast.makeText(this, "Saved to playlist(s)", Toast.LENGTH_SHORT).show();
        finish();
    }

    class PickerAdapter extends RecyclerView.Adapter<PickerAdapter.PVH> {
        private List<YouTubePlaylist>    data   = new ArrayList<>();
        private Map<String, Boolean>     checks = new HashMap<>();
        private Map<String, Boolean>     already= new HashMap<>();

        void setData(List<YouTubePlaylist> d, String videoId) {
            data = d;
            for (YouTubePlaylist p : d) {
                YouTubeFirebaseUtils.playlistVideosRef(myUid, p.playlistId)
                    .child(videoId).addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override public void onDataChange(@NonNull DataSnapshot s) {
                            already.put(p.playlistId, s.exists());
                            checks.put(p.playlistId, s.exists());
                            notifyDataSetChanged();
                        }
                        @Override public void onCancelled(@NonNull DatabaseError e) {}
                    });
            }
            notifyDataSetChanged();
        }

        Map<String, Boolean> getSelections() { return checks; }

        @NonNull @Override
        public PVH onCreateViewHolder(@NonNull ViewGroup p, int t) {
            View v = LayoutInflater.from(YouTubePlaylistPickerActivity.this)
                .inflate(R.layout.item_yt_playlist_picker, p, false);
            return new PVH(v);
        }

        @Override public void onBindViewHolder(@NonNull PVH h, int pos) {
            YouTubePlaylist pl = data.get(pos);
            h.tvTitle.setText(pl.title);
            h.tvCount.setText(pl.videoCount + " videos");
            h.cbCheck.setChecked(Boolean.TRUE.equals(checks.get(pl.playlistId)));
            h.cbCheck.setOnCheckedChangeListener((btn, checked) ->
                checks.put(pl.playlistId, checked));
            h.itemView.setOnClickListener(v -> {
                boolean c = !Boolean.TRUE.equals(checks.get(pl.playlistId));
                checks.put(pl.playlistId, c);
                h.cbCheck.setChecked(c);
            });
        }

        @Override public int getItemCount() { return data.size(); }

        class PVH extends RecyclerView.ViewHolder {
            TextView tvTitle, tvCount; CheckBox cbCheck;
            PVH(View v) {
                super(v);
                tvTitle = v.findViewById(R.id.tv_yt_pl_picker_title);
                tvCount = v.findViewById(R.id.tv_yt_pl_picker_count);
                cbCheck = v.findViewById(R.id.cb_yt_pl_picker);
            }
        }
    }
}
