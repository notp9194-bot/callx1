package com.callx.app.music;

import android.content.Intent;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.camera.ReelCameraActivity;
import com.callx.app.player.SingleReelPlayerActivity;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class SoundDetailActivity extends AppCompatActivity {
    public static final String EXTRA_SOUND_ID = "extra_sound_id";
    public static final String EXTRA_SOUND_TITLE = "extra_sound_title";
    public static final String EXTRA_ARTIST = "extra_artist";
    public static final String EXTRA_SOUND_URL = "extra_sound_url";
    public static final String EXTRA_COVER_URL = "extra_cover_url";
    public static final String EXTRA_DURATION_MS = "extra_duration_ms";
    public static final String EXTRA_GENRE = "extra_genre";

    private String soundId, title, artist, audioUrl, coverUrl;
    private long durationMs;
    private SoundWaveformView waveformView;
    private MediaPlayer mediaPlayer;
    private boolean isPlaying = false;

    private RecyclerView rvReels;
    private SoundReelsAdapter adapter;
    private final List<ReelThumbItem> reelItems = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sound_detail);

        soundId = getIntent().getStringExtra(EXTRA_SOUND_ID);
        title = getIntent().getStringExtra(EXTRA_SOUND_TITLE);
        artist = getIntent().getStringExtra(EXTRA_ARTIST);
        audioUrl = getIntent().getStringExtra(EXTRA_SOUND_URL);
        coverUrl = getIntent().getStringExtra(EXTRA_COVER_URL);
        durationMs = getIntent().getLongExtra(EXTRA_DURATION_MS, 0);

        initViews();
        loadCreatorInfo();
        loadReels();
    }

    private void initViews() {
        waveformView = findViewById(R.id.waveform_view);
        ((TextView) findViewById(R.id.tv_detail_title)).setText(title);
        ((TextView) findViewById(R.id.tv_detail_artist)).setText(artist);
        
        long sec = durationMs / 1000;
        ((TextView) findViewById(R.id.tv_detail_stats)).setText(String.format(Locale.US, "%d:%02d", sec / 60, sec % 60));

        findViewById(R.id.btn_detail_play).setOnClickListener(v -> toggleAudio());
        findViewById(R.id.btn_save_sound).setOnClickListener(v -> saveSound());
        
        ExtendedFloatingActionButton fab = findViewById(R.id.fab_use_sound);
        fab.setOnClickListener(v -> useSound());

        rvReels = findViewById(R.id.rv_sound_reels_grid);
        rvReels.setLayoutManager(new GridLayoutManager(this, 2));
        adapter = new SoundReelsAdapter(reelItems, pos -> {
            Intent i = new Intent(this, SingleReelPlayerActivity.class);
            ArrayList<String> ids = new ArrayList<>();
            for (ReelThumbItem item : reelItems) ids.add(item.reelId);
            i.putStringArrayListExtra(SingleReelPlayerActivity.EXTRA_REEL_IDS, ids);
            i.putExtra(SingleReelPlayerActivity.EXTRA_START_POSITION, pos);
            startActivity(i);
        });
        rvReels.setAdapter(adapter);
    }

    private void toggleAudio() {
        if (audioUrl == null || audioUrl.isEmpty()) return;
        if (isPlaying) {
            if (mediaPlayer != null) mediaPlayer.pause();
            isPlaying = false;
        } else {
            if (mediaPlayer == null) {
                mediaPlayer = new MediaPlayer();
                try {
                    mediaPlayer.setDataSource(audioUrl);
                    mediaPlayer.prepareAsync();
                    mediaPlayer.setOnPreparedListener(MediaPlayer::start);
                } catch (Exception e) { return; }
            } else {
                mediaPlayer.start();
            }
            isPlaying = true;
        }
        waveformView.setPlaying(isPlaying);
    }

    private void useSound() {
        Intent i = new Intent(this, ReelCameraActivity.class);
        i.putExtra(EXTRA_SOUND_ID, soundId);
        i.putExtra(EXTRA_SOUND_TITLE, title);
        i.putExtra(EXTRA_ARTIST, artist);
        i.putExtra(EXTRA_SOUND_URL, audioUrl);
        startActivity(i);
    }

    private void saveSound() {
        String uid = FirebaseUtils.getCurrentUid();
        if (uid == null) return;
        FirebaseUtils.db().getReference("savedSounds").child(uid).child(soundId).setValue(true)
            .addOnSuccessListener(a -> Toast.makeText(this, "Sound Saved", Toast.LENGTH_SHORT).show());
    }

    private void loadCreatorInfo() {
        FirebaseUtils.db().getReference("sounds").child(soundId)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot s) {
                    String cUid = s.child("originalCreatorUid").getValue(String.class);
                    if (cUid != null) {
                        findViewById(R.id.layout_creator_card).setVisibility(View.VISIBLE);
                        ((TextView) findViewById(R.id.tv_creator_name)).setText(s.child("originalCreatorName").getValue(String.class));
                        Glide.with(SoundDetailActivity.this)
                            .load(s.child("originalCreatorAvatar").getValue(String.class))
                            .circleCrop().into((ImageView) findViewById(R.id.iv_creator_avatar));
                        findViewById(R.id.btn_view_profile).setOnClickListener(v -> {
                            // Intent to UserProfileActivity
                        });
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void loadReels() {
        FirebaseUtils.db().getReference("reelsBySound").child(soundId)
            .limitToFirst(50)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    reelItems.clear();
                    for (DataSnapshot s : snap.getChildren()) {
                        ReelThumbItem item = s.getValue(ReelThumbItem.class);
                        if (item != null) {
                            item.reelId = s.getKey();
                            reelItems.add(item);
                        }
                    }
                    adapter.notifyDataSetChanged();
                    ((TextView) findViewById(R.id.tv_reel_count_stats)).setText(reelItems.size() + " Reels");
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    @Override protected void onDestroy() {
        if (mediaPlayer != null) { mediaPlayer.release(); mediaPlayer = null; }
        super.onDestroy();
    }

    public static class ReelThumbItem {
        public String reelId, thumbnailUrl, videoUrl;
        public long viewsCount;
        public ReelThumbItem() {}
        public ReelThumbItem(String id, String t, String v) { reelId=id; thumbnailUrl=t; videoUrl=v; }
    }
}
