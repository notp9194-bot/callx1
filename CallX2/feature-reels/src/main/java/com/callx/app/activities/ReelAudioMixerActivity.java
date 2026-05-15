package com.callx.app.activities;

import android.content.Intent;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import com.callx.app.reels.R;

import java.io.File;

/**
 * ReelAudioMixerActivity — Audio Mixer (Original + Music only).
 *
 * Features:
 *  ✅ ExoPlayer: plays original reel video (looped preview)
 *  ✅ Original audio volume slider (0–100%)
 *  ✅ Background music volume slider (0–100%)
 *  ✅ Mute/unmute toggles per track
 *  ✅ Music track info display (title + artist)
 *  ✅ "Apply" returns mix config back to ReelEditorActivity
 *
 * Extras:
 *   INPUT:  EXTRA_VIDEO_URI, EXTRA_MUSIC_TITLE, EXTRA_MUSIC_ARTIST,
 *           EXTRA_MUSIC_URL, EXTRA_IS_FILE_PATH
 *   OUTPUT: RESULT_ORIG_VOL, RESULT_MUSIC_VOL
 */
public class ReelAudioMixerActivity extends AppCompatActivity {

    public static final String EXTRA_VIDEO_URI    = "mixer_video_uri";
    public static final String EXTRA_IS_FILE_PATH = "mixer_is_file";
    public static final String EXTRA_MUSIC_TITLE  = "mixer_music_title";
    public static final String EXTRA_MUSIC_ARTIST = "mixer_music_artist";
    public static final String EXTRA_MUSIC_URL    = "mixer_music_url";

    public static final String RESULT_ORIG_VOL      = "result_orig_vol";
    public static final String RESULT_MUSIC_VOL     = "result_music_vol";
    public static final String RESULT_VOICEOVER_PATH= "result_vo_path";
    public static final String RESULT_VOICEOVER_VOL = "result_vo_vol";

    private PlayerView  playerView;
    private ImageButton btnBack, btnApply;
    private TextView    tvMusicTitle, tvMusicArtist;
    private SeekBar     sbOrigVol, sbMusicVol;
    private TextView    tvOrigVolPct, tvMusicVolPct;
    private ImageButton btnMuteOrig, btnMuteMusic;
    private ProgressBar progressBuf;

    private ExoPlayer   exoPlayer;
    private MediaPlayer musicPlayer;

    private String  videoUri;
    private boolean isFilePath;
    private String  musicUrl;

    private float origVol  = 1.0f;
    private float musicVol = 0.8f;

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reel_audio_mixer);

        videoUri   = getIntent().getStringExtra(EXTRA_VIDEO_URI);
        isFilePath = getIntent().getBooleanExtra(EXTRA_IS_FILE_PATH, true);
        musicUrl   = getIntent().getStringExtra(EXTRA_MUSIC_URL);
        String musicTitle  = getIntent().getStringExtra(EXTRA_MUSIC_TITLE);
        String musicArtist = getIntent().getStringExtra(EXTRA_MUSIC_ARTIST);

        bindViews();
        populateMusicInfo(musicTitle, musicArtist);
        setupPlayer();
        setupSliders();
        setupMuteToggles();

        btnBack.setOnClickListener(v -> finish());
        btnApply.setOnClickListener(v -> applyAndReturn());
    }

    private void bindViews() {
        playerView    = findViewById(R.id.mixer_player_view);
        btnBack       = findViewById(R.id.btn_mixer_back);
        btnApply      = findViewById(R.id.btn_mixer_apply);
        tvMusicTitle  = findViewById(R.id.tv_mixer_music_title);
        tvMusicArtist = findViewById(R.id.tv_mixer_music_artist);
        sbOrigVol     = findViewById(R.id.sb_orig_vol);
        sbMusicVol    = findViewById(R.id.sb_music_vol);
        tvOrigVolPct  = findViewById(R.id.tv_orig_vol_pct);
        tvMusicVolPct = findViewById(R.id.tv_music_vol_pct);
        btnMuteOrig   = findViewById(R.id.btn_mute_orig);
        btnMuteMusic  = findViewById(R.id.btn_mute_music);
        progressBuf   = findViewById(R.id.mixer_progress_buf);
    }

    private void populateMusicInfo(String title, String artist) {
        tvMusicTitle.setText(title  != null && !title.isEmpty()  ? title  : "No music selected");
        tvMusicArtist.setText(artist != null && !artist.isEmpty() ? artist : "—");
    }

    @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
    private void setupPlayer() {
        if (videoUri == null || videoUri.isEmpty()) return;
        exoPlayer = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(exoPlayer);
        Uri uri = isFilePath
            ? Uri.fromFile(new File(videoUri))
            : Uri.parse(videoUri);
        exoPlayer.setMediaItem(MediaItem.fromUri(uri));
        exoPlayer.setRepeatMode(androidx.media3.common.Player.REPEAT_MODE_ONE);
        exoPlayer.setVolume(origVol);
        exoPlayer.prepare();
        exoPlayer.setPlayWhenReady(true);

        exoPlayer.addListener(new androidx.media3.common.Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                progressBuf.setVisibility(
                    state == androidx.media3.common.Player.STATE_BUFFERING
                        ? View.VISIBLE : View.GONE);
            }
        });

        startMusicPreview();
    }

    private void startMusicPreview() {
        if (musicUrl == null || musicUrl.isEmpty()) return;
        try {
            musicPlayer = new MediaPlayer();
            musicPlayer.setDataSource(musicUrl);
            musicPlayer.setLooping(true);
            musicPlayer.setVolume(musicVol, musicVol);
            musicPlayer.prepareAsync();
            musicPlayer.setOnPreparedListener(MediaPlayer::start);
        } catch (Exception e) {
            Toast.makeText(this, "Music preview failed", Toast.LENGTH_SHORT).show();
        }
    }

    private void setupSliders() {
        sbOrigVol.setMax(100);
        sbOrigVol.setProgress((int)(origVol * 100));
        tvOrigVolPct.setText((int)(origVol * 100) + "%");

        sbMusicVol.setMax(100);
        sbMusicVol.setProgress((int)(musicVol * 100));
        tvMusicVolPct.setText((int)(musicVol * 100) + "%");

        sbOrigVol.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                origVol = p / 100f;
                tvOrigVolPct.setText(p + "%");
                if (exoPlayer != null) exoPlayer.setVolume(origVol);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        sbMusicVol.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                musicVol = p / 100f;
                tvMusicVolPct.setText(p + "%");
                if (musicPlayer != null) musicPlayer.setVolume(musicVol, musicVol);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
    }

    private void setupMuteToggles() {
        final boolean[] mutedOrig  = {false};
        final boolean[] mutedMusic = {false};

        btnMuteOrig.setOnClickListener(v -> {
            mutedOrig[0] = !mutedOrig[0];
            if (exoPlayer != null) exoPlayer.setVolume(mutedOrig[0] ? 0f : origVol);
            btnMuteOrig.setImageResource(mutedOrig[0]
                ? R.drawable.ic_volume_off : R.drawable.ic_volume_on);
        });

        btnMuteMusic.setOnClickListener(v -> {
            mutedMusic[0] = !mutedMusic[0];
            if (musicPlayer != null) {
                float v2 = mutedMusic[0] ? 0f : musicVol;
                musicPlayer.setVolume(v2, v2);
            }
            btnMuteMusic.setImageResource(mutedMusic[0]
                ? R.drawable.ic_volume_off : R.drawable.ic_volume_on);
        });
    }

    private void applyAndReturn() {
        Intent result = new Intent();
        result.putExtra(RESULT_ORIG_VOL,       origVol);
        result.putExtra(RESULT_MUSIC_VOL,      musicVol);
        result.putExtra(RESULT_VOICEOVER_PATH, "");
        result.putExtra(RESULT_VOICEOVER_VOL,  1.0f);
        setResult(RESULT_OK, result);
        finish();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (exoPlayer != null) exoPlayer.pause();
        if (musicPlayer != null && musicPlayer.isPlaying()) musicPlayer.pause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (exoPlayer != null) exoPlayer.play();
        if (musicPlayer != null && !musicPlayer.isPlaying()) musicPlayer.start();
    }

    @Override
    protected void onDestroy() {
        if (exoPlayer != null) { exoPlayer.stop(); exoPlayer.release(); }
        if (musicPlayer != null) { musicPlayer.release(); musicPlayer = null; }
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
