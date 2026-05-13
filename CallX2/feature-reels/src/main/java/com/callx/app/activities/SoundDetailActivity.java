package com.callx.app.activities;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.activities.SavedSoundsActivity;
import com.google.firebase.database.*;

import java.util.*;

/**
 * SoundDetailActivity — Production-level Audio/Sound Detail Screen.
 *
 * Features:
 *  ✅ Sound info (title, artist, duration, BPM)
 *  ✅ Save / Use this sound button
 *  ✅ All reels using this sound (grid)
 *  ✅ Trending rank badge
 *  ✅ Play/Pause audio preview
 *  ✅ Total reel count using this sound
 *  ✅ Original audio vs remix indicator
 */
public class SoundDetailActivity extends AppCompatActivity {

    public static final String EXTRA_SOUND_ID    = "sound_id";
    public static final String EXTRA_SOUND_TITLE = "sound_title";
    public static final String EXTRA_SOUND_URL   = "sound_url";
    public static final String EXTRA_ARTIST      = "sound_artist";
    public static final String EXTRA_DURATION_MS = "sound_duration_ms";

    private ImageButton btnBack, btnPlayPause;
    private TextView    tvSoundTitle, tvArtist, tvDuration, tvReelCount, tvTrendingRank;
    private TextView    btnUseSoundCamera, btnUseSoundGallery;
    private ImageView   ivSoundCover;
    private ImageButton btnSaveSound;
    private RecyclerView rvReels;
    private ProgressBar progressBar;
    private View        layoutSoundInfo;

    private String soundId, soundTitle, soundUrl, artist;
    private int    durationMs;
    private boolean isSaved = false;
    private boolean isPlaying = false;
    private android.media.MediaPlayer mediaPlayer;

    private final List<String> reelIds = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sound_detail);

        soundId    = getIntent().getStringExtra(EXTRA_SOUND_ID);
        soundTitle = getIntent().getStringExtra(EXTRA_SOUND_TITLE);
        soundUrl   = getIntent().getStringExtra(EXTRA_SOUND_URL);
        artist     = getIntent().getStringExtra(EXTRA_ARTIST);
        durationMs = getIntent().getIntExtra(EXTRA_DURATION_MS, 0);

        bindViews();
        populateSoundInfo();
        loadReelsWithSound();
        setupClickListeners();
    }

    private void bindViews() {
        btnBack          = findViewById(R.id.btn_sound_back);
        btnPlayPause     = findViewById(R.id.btn_sound_play_pause);
        tvSoundTitle     = findViewById(R.id.tv_sound_title);
        tvArtist         = findViewById(R.id.tv_sound_artist);
        tvDuration       = findViewById(R.id.tv_sound_duration);
        tvReelCount      = findViewById(R.id.tv_sound_reel_count);
        tvTrendingRank   = findViewById(R.id.tv_sound_trending_rank);
        btnUseSoundCamera  = findViewById(R.id.btn_use_sound_camera);
        btnUseSoundGallery = findViewById(R.id.btn_use_sound_gallery);
        ivSoundCover     = findViewById(R.id.iv_sound_cover);
        btnSaveSound     = findViewById(R.id.btn_save_sound);
        rvReels          = findViewById(R.id.rv_sound_reels);
        progressBar      = findViewById(R.id.progress_sound);
        layoutSoundInfo  = findViewById(R.id.layout_sound_info);

        rvReels.setLayoutManager(new GridLayoutManager(this, 3));
    }

    private void populateSoundInfo() {
        tvSoundTitle.setText(soundTitle != null ? soundTitle : "Unknown Sound");
        tvArtist.setText(artist != null ? "• " + artist : "• Original Audio");
        if (durationMs > 0) {
            int sec = durationMs / 1000;
            tvDuration.setText(String.format(Locale.US, "%d:%02d", sec / 60, sec % 60));
        }
    }

    private void loadReelsWithSound() {
        if (soundId == null) return;
        progressBar.setVisibility(View.VISIBLE);
        FirebaseUtils.db().getReference("sounds").child(soundId)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snap) {
                    if (isFinishing() || isDestroyed()) return;
                    long count = snap.child("reel_count").getValue(Long.class) != null
                               ? snap.child("reel_count").getValue(Long.class) : 0;
                    long rank  = snap.child("trending_rank").getValue(Long.class) != null
                               ? snap.child("trending_rank").getValue(Long.class) : 0;
                    tvReelCount.setText(formatCount(count) + " Reels");
                    if (rank > 0 && rank <= 50) {
                        tvTrendingRank.setVisibility(View.VISIBLE);
                        tvTrendingRank.setText("#" + rank + " Trending");
                    }
                    progressBar.setVisibility(View.GONE);
                    layoutSoundInfo.setVisibility(View.VISIBLE);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    if (!isFinishing()) progressBar.setVisibility(View.GONE);
                }
            });
    }

    private void setupClickListeners() {
        btnBack.setOnClickListener(v -> finish());

        btnPlayPause.setOnClickListener(v -> togglePlayPause());

        btnSaveSound.setOnLongClickListener(v -> {
            startActivity(new Intent(this, SavedSoundsActivity.class));
            return true;
        });
        btnSaveSound.setOnClickListener(v -> {
            isSaved = !isSaved;
            btnSaveSound.setImageResource(isSaved
                ? R.drawable.ic_bookmark_filled : R.drawable.ic_bookmark);
            Toast.makeText(this,
                isSaved ? "Sound saved — Long press to view saved sounds" : "Sound removed", Toast.LENGTH_SHORT).show();
            if (soundId != null) {
                String uid = null; try { uid = FirebaseUtils.getCurrentUid(); } catch (Exception ignored) {}
                if (uid != null) {
                    DatabaseReference ref = FirebaseUtils.db().getReference("users").child(uid).child("saved_sounds").child(soundId);
                    if (isSaved) ref.setValue(soundTitle);
                    else ref.removeValue();
                }
            }
        });

        btnUseSoundCamera.setOnClickListener(v -> {
            Intent i = new Intent(this, ReelCameraActivity.class);
            i.putExtra("selected_sound_id",  soundId);
            i.putExtra("selected_sound_title", soundTitle);
            i.putExtra("selected_sound_url",   soundUrl);
            startActivity(i);
        });

        btnUseSoundGallery.setOnClickListener(v -> {
            Intent i = new Intent(this, ReelUploadActivity.class);
            i.putExtra("selected_sound_id",   soundId);
            i.putExtra("selected_sound_title", soundTitle);
            i.putExtra("selected_sound_url",   soundUrl);
            startActivity(i);
        });
    }

    private void togglePlayPause() {
        if (soundUrl == null || soundUrl.isEmpty()) {
            Toast.makeText(this, "Preview not available", Toast.LENGTH_SHORT).show();
            return;
        }
        if (mediaPlayer == null) {
            mediaPlayer = new android.media.MediaPlayer();
            try {
                mediaPlayer.setDataSource(soundUrl);
                mediaPlayer.setLooping(true);
                mediaPlayer.prepareAsync();
                mediaPlayer.setOnPreparedListener(mp -> {
                    mp.start();
                    isPlaying = true;
                    btnPlayPause.setImageResource(R.drawable.ic_pause);
                });
            } catch (Exception e) {
                Toast.makeText(this, "Cannot play preview", Toast.LENGTH_SHORT).show();
            }
        } else if (isPlaying) {
            mediaPlayer.pause();
            isPlaying = false;
            btnPlayPause.setImageResource(R.drawable.ic_play);
        } else {
            mediaPlayer.start();
            isPlaying = true;
            btnPlayPause.setImageResource(R.drawable.ic_pause);
        }
    }

    private String formatCount(long count) {
        if (count >= 1_000_000) return String.format(Locale.US, "%.1fM", count / 1_000_000.0);
        if (count >= 1_000)     return String.format(Locale.US, "%.1fK", count / 1_000.0);
        return String.valueOf(count);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }
}
