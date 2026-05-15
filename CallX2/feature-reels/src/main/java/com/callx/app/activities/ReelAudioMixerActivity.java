package com.callx.app.activities;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import com.callx.app.reels.R;

import java.io.File;
import java.io.IOException;

/**
 * ReelAudioMixerActivity — Production-level Audio Mixer.
 *
 * Features:
 *  ✅ ExoPlayer: plays original reel video (looped preview)
 *  ✅ Original audio volume slider (0–100%)
 *  ✅ Background music volume slider (0–100%)
 *  ✅ Voiceover recording (tap mic → records over video, stores as separate track)
 *  ✅ Voiceover volume slider
 *  ✅ Mute/unmute toggles per track
 *  ✅ Music track info display (title + artist)
 *  ✅ "Apply" returns mix config back to ReelUploadActivity/ReelEditorActivity
 *
 * Extras:
 *   INPUT:  EXTRA_VIDEO_URI, EXTRA_MUSIC_TITLE, EXTRA_MUSIC_ARTIST,
 *           EXTRA_MUSIC_URL, EXTRA_IS_FILE_PATH
 *   OUTPUT: RESULT_ORIG_VOL, RESULT_MUSIC_VOL, RESULT_VOICEOVER_PATH, RESULT_VOICEOVER_VOL
 */
public class ReelAudioMixerActivity extends AppCompatActivity {

    public static final String EXTRA_VIDEO_URI    = "mixer_video_uri";
    public static final String EXTRA_IS_FILE_PATH = "mixer_is_file";
    public static final String EXTRA_MUSIC_TITLE  = "mixer_music_title";
    public static final String EXTRA_MUSIC_ARTIST = "mixer_music_artist";
    public static final String EXTRA_MUSIC_URL      = "mixer_music_url";
    public static final String EXTRA_MUSIC_START_MS = "mixer_music_start_ms"; // FIX 9: music start offset

    public static final String RESULT_ORIG_VOL      = "result_orig_vol";
    public static final String RESULT_MUSIC_VOL     = "result_music_vol";
    public static final String RESULT_VOICEOVER_PATH= "result_vo_path";
    public static final String RESULT_VOICEOVER_VOL  = "result_vo_vol";
    public static final String RESULT_MUSIC_START_MS  = "result_music_start_ms";
    public static final String RESULT_AUDIO_MODE      = "result_audio_mode"; // 0=mic+sound, 1=sound only, 2=mic only

    private static final int REQ_MIC = 501;

    private PlayerView    playerView;
    private ImageButton   btnBack;
    private android.widget.TextView      btnApply;
    private TextView      tvMusicTitle, tvMusicArtist;
    private SeekBar       sbOrigVol, sbMusicVol, sbVoiceoverVol;
    private TextView      tvOrigVolPct, tvMusicVolPct, tvVoiceoverVolPct;
    private ImageButton   btnMuteOrig, btnMuteMusic, btnMuteVoiceover;
    private ImageButton   btnVoiceoverRecord;
    private TextView      tvVoiceoverStatus;
    private ProgressBar   progressBuf;
    private View          layoutVoiceoverRow;
    private RadioGroup    rgAudioMode;

    private ExoPlayer  exoPlayer;
    private MediaPlayer musicPlayer;
    private MediaRecorder recorder;

    private String videoUri;
    private boolean isFilePath;
    private String musicUrl;
    private long   musicStartMs   = 0L;  // FIX 9
    private int    audioMode      = 0;   // 0=Mic+Sound, 1=Sound Only, 2=Mic Only
    private String voiceoverPath;
    private boolean isRecordingVoiceover = false;

    private float origVol     = 1.0f;
    private float musicVol    = 0.8f;
    private float voiceoverVol= 1.0f;

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reel_audio_mixer);

        videoUri   = getIntent().getStringExtra(EXTRA_VIDEO_URI);
        isFilePath = getIntent().getBooleanExtra(EXTRA_IS_FILE_PATH, true);
        musicUrl      = getIntent().getStringExtra(EXTRA_MUSIC_URL);
        musicStartMs  = getIntent().getLongExtra(EXTRA_MUSIC_START_MS, 0L);
        String musicTitle  = getIntent().getStringExtra(EXTRA_MUSIC_TITLE);
        String musicArtist = getIntent().getStringExtra(EXTRA_MUSIC_ARTIST);

        bindViews();
        populateMusicInfo(musicTitle, musicArtist);
        setupPlayer();
        setupSliders();
        setupMuteToggles();
        setupAudioMode();
        setupVoiceover();

        btnBack.setOnClickListener(v -> finish());
        btnApply.setOnClickListener(v -> applyAndReturn());

        if (musicUrl == null || musicUrl.isEmpty()) {
            layoutVoiceoverRow.setVisibility(View.GONE);
        }
    }

    private void bindViews() {
        playerView        = findViewById(R.id.mixer_player_view);
        btnBack           = findViewById(R.id.btn_mixer_back);
        btnApply          = (android.widget.TextView) findViewById(R.id.btn_mixer_apply);
        tvMusicTitle      = findViewById(R.id.tv_mixer_music_title);
        tvMusicArtist     = findViewById(R.id.tv_mixer_music_artist);
        sbOrigVol         = findViewById(R.id.sb_orig_vol);
        sbMusicVol        = findViewById(R.id.sb_music_vol);
        sbVoiceoverVol    = findViewById(R.id.sb_voiceover_vol);
        tvOrigVolPct      = findViewById(R.id.tv_orig_vol_pct);
        tvMusicVolPct     = findViewById(R.id.tv_music_vol_pct);
        tvVoiceoverVolPct = findViewById(R.id.tv_voiceover_vol_pct);
        btnMuteOrig       = findViewById(R.id.btn_mute_orig);
        btnMuteMusic      = findViewById(R.id.btn_mute_music);
        btnMuteVoiceover  = findViewById(R.id.btn_mute_voiceover);
        btnVoiceoverRecord= findViewById(R.id.btn_voiceover_record);
        tvVoiceoverStatus = findViewById(R.id.tv_voiceover_status);
        progressBuf       = findViewById(R.id.mixer_progress_buf);
        layoutVoiceoverRow= findViewById(R.id.layout_voiceover_row);
        rgAudioMode       = findViewById(R.id.rg_audio_mode);
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

        sbVoiceoverVol.setMax(100);
        sbVoiceoverVol.setProgress((int)(voiceoverVol * 100));
        tvVoiceoverVolPct.setText((int)(voiceoverVol * 100) + "%");

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

        sbVoiceoverVol.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                voiceoverVol = p / 100f;
                tvVoiceoverVolPct.setText(p + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
    }

    private void setupMuteToggles() {
        final boolean[] mutedOrig      = {false};
        final boolean[] mutedMusic     = {false};
        final boolean[] mutedVoiceover = {false};

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
        btnMuteVoiceover.setOnClickListener(v -> {
            mutedVoiceover[0] = !mutedVoiceover[0];
            btnMuteVoiceover.setImageResource(mutedVoiceover[0]
                ? R.drawable.ic_volume_off : R.drawable.ic_volume_on);
        });
    }

    private void setupVoiceover() {
        btnVoiceoverRecord.setOnClickListener(v -> {
            if (isRecordingVoiceover) stopVoiceoverRecording();
            else checkMicAndStartVoiceover();
        });
    }

    private void checkMicAndStartVoiceover() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        } else {
            startVoiceoverRecording();
        }
    }

    private void startVoiceoverRecording() {
        voiceoverPath = new File(getCacheDir(),
            "voiceover_" + System.currentTimeMillis() + ".aac").getAbsolutePath();
        recorder = new MediaRecorder();
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS);
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        recorder.setAudioSamplingRate(44100);
        recorder.setAudioEncodingBitRate(128000);
        recorder.setOutputFile(voiceoverPath);
        try {
            recorder.prepare();
            recorder.start();
            isRecordingVoiceover = true;
            btnVoiceoverRecord.setImageResource(R.drawable.ic_pause);
            tvVoiceoverStatus.setText("Recording voiceover…");
            if (exoPlayer != null && !exoPlayer.isPlaying()) exoPlayer.play();
        } catch (IOException e) {
            Toast.makeText(this, "Voiceover failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            voiceoverPath = null;
        }
    }

    private void stopVoiceoverRecording() {
        if (recorder != null) {
            try { recorder.stop(); } catch (Exception ignored) {}
            recorder.release();
            recorder = null;
        }
        isRecordingVoiceover = false;
        btnVoiceoverRecord.setImageResource(R.drawable.ic_mic);
        tvVoiceoverStatus.setText("Voiceover recorded");
    }

    private void setupAudioMode() {
        if (rgAudioMode == null) return;
        rgAudioMode.check(R.id.rb_mic_and_sound); // default

        rgAudioMode.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.rb_sound_only) {
                // Sound Only — mic 100% off
                audioMode = 1;
                origVol   = 0f;
                sbOrigVol.setProgress(0);
                tvOrigVolPct.setText("0%");
                sbOrigVol.setEnabled(false);
                if (exoPlayer != null) exoPlayer.setVolume(0f);
            } else if (checkedId == R.id.rb_mic_only) {
                // Mic Only
                audioMode = 2;
                origVol   = 1f;
                sbOrigVol.setProgress(100);
                tvOrigVolPct.setText("100%");
                sbOrigVol.setEnabled(true);
                if (exoPlayer != null) exoPlayer.setVolume(1f);
                // Also mute music preview
                musicVol  = 0f;
                sbMusicVol.setProgress(0);
                tvMusicVolPct.setText("0%");
                sbMusicVol.setEnabled(false);
                if (musicPlayer != null) musicPlayer.setVolume(0f, 0f);
            } else {
                // Mic + Sound
                audioMode = 0;
                origVol   = 1f;
                sbOrigVol.setProgress(100);
                tvOrigVolPct.setText("100%");
                sbOrigVol.setEnabled(true);
                if (exoPlayer != null) exoPlayer.setVolume(1f);
                musicVol  = 0.8f;
                sbMusicVol.setProgress(80);
                tvMusicVolPct.setText("80%");
                sbMusicVol.setEnabled(true);
                if (musicPlayer != null) musicPlayer.setVolume(0.8f, 0.8f);
            }
        });
    }

    private void applyAndReturn() {
        Intent result = new Intent();
        result.putExtra(RESULT_ORIG_VOL,       origVol);
        result.putExtra(RESULT_MUSIC_VOL,      musicVol);
        result.putExtra(RESULT_VOICEOVER_PATH, voiceoverPath != null ? voiceoverPath : "");
        result.putExtra(RESULT_VOICEOVER_VOL,   voiceoverVol);
        result.putExtra(RESULT_MUSIC_START_MS,  musicStartMs);
        result.putExtra(RESULT_AUDIO_MODE,      audioMode);
        setResult(RESULT_OK, result);
        finish();
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms,
                                           @NonNull int[] grants) {
        super.onRequestPermissionsResult(req, perms, grants);
        if (req == REQ_MIC && grants.length > 0
                && grants[0] == PackageManager.PERMISSION_GRANTED) {
            startVoiceoverRecording();
        } else {
            Toast.makeText(this, "Microphone permission required", Toast.LENGTH_SHORT).show();
        }
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
        if (isRecordingVoiceover) stopVoiceoverRecording();
        if (exoPlayer != null) { exoPlayer.stop(); exoPlayer.release(); }
        if (musicPlayer != null) { musicPlayer.release(); musicPlayer = null; }
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
