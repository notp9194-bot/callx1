package com.callx.app.editor;

import com.callx.app.upload.ReelUploadActivity;
import com.callx.app.music.SoundDetailActivity;
import com.callx.app.music.ReelTrendingAudioActivity;

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
    public static final String EXTRA_MUSIC_URL    = "mixer_music_url";
    /** ✅ NEW: optional sound ID passed in so Mixer can open SoundDetailActivity */
    public static final String EXTRA_SOUND_ID     = "mixer_sound_id";

    public static final String RESULT_ORIG_VOL        = "result_orig_vol";
    public static final String RESULT_MUSIC_VOL       = "result_music_vol";
    public static final String RESULT_VOICEOVER_PATH  = "result_vo_path";
    public static final String RESULT_VOICEOVER_VOL   = "result_vo_vol";
    public static final String RESULT_FADE_IN_MS      = "result_fade_in_ms";
    public static final String RESULT_FADE_OUT_MS     = "result_fade_out_ms";
    public static final String RESULT_PITCH_SEMITONES = "result_pitch_semitones";
    /** peak-normalize toggle result */
    public static final String RESULT_NORMALIZE       = "result_normalize";
    /**
     * ✅ FIX: When the user changes the background track inside the mixer
     * (via the "Change" button → ReelTrendingAudioActivity), these extras
     * carry the NEW track info back to the caller (ReelEditorActivity /
     * ReelUploadActivity) so the correct audio is mixed at upload time.
     * Without these the caller always kept the ORIGINAL sound URL.
     */
    public static final String RESULT_MUSIC_URL    = "result_music_url";
    public static final String RESULT_MUSIC_ID     = "result_music_id";
    public static final String RESULT_MUSIC_TITLE  = "result_music_title";
    public static final String RESULT_MUSIC_ARTIST = "result_music_artist";

    private static final int REQ_MIC          = 501;
    /** ✅ NEW: request codes for SoundDetail and "Change Music" pickers */
    private static final int REQ_SOUND_DETAIL = 901;
    private static final int REQ_CHANGE_MUSIC = 902;

    private PlayerView    playerView;
    private ImageButton   btnBack, btnApply;
    private TextView      tvMusicTitle, tvMusicArtist;
    private SeekBar       sbOrigVol, sbMusicVol, sbVoiceoverVol;
    private TextView      tvOrigVolPct, tvMusicVolPct, tvVoiceoverVolPct;
    private ImageButton   btnMuteOrig, btnMuteMusic, btnMuteVoiceover;
    private ImageButton   btnVoiceoverRecord;
    private TextView      tvVoiceoverStatus;
    private ProgressBar   progressBuf;
    private View          layoutVoiceoverRow;

    private ExoPlayer  exoPlayer;
    private MediaPlayer musicPlayer;
    private MediaRecorder recorder;

    private String videoUri;
    private boolean isFilePath;
    private String musicUrl;
    private String voiceoverPath;
    // ✅ NEW: stored so we can open SoundDetailActivity
    private String soundId       = "";
    private String currentTitle  = "";
    private String currentArtist = "";
    private boolean isRecordingVoiceover = false;

    private float origVol        = 1.0f;
    private float musicVol       = 0.8f;
    private float voiceoverVol   = 1.0f;
    private int   fadeInMs       = 0;
    private int   fadeOutMs      = 0;
    private float pitchSemitones = 0f;
    /** ✅ NEW: peak-normalize toggle state */
    private boolean normalizeOn  = false;

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reel_audio_mixer);

        videoUri      = getIntent().getStringExtra(EXTRA_VIDEO_URI);
        isFilePath    = getIntent().getBooleanExtra(EXTRA_IS_FILE_PATH, true);
        musicUrl      = getIntent().getStringExtra(EXTRA_MUSIC_URL);
        soundId       = nvl(getIntent().getStringExtra(EXTRA_SOUND_ID));
        currentTitle  = nvl(getIntent().getStringExtra(EXTRA_MUSIC_TITLE));
        currentArtist = nvl(getIntent().getStringExtra(EXTRA_MUSIC_ARTIST));

        bindViews();
        populateMusicInfo(currentTitle, currentArtist);
        setupPlayer();
        setupSliders();
        setupMuteToggles();
        setupVoiceover();
        setupMusicRowClicks();   // ✅ NEW

        btnBack.setOnClickListener(v -> finish());
        btnApply.setOnClickListener(v -> applyAndReturn());
        if (musicUrl == null || musicUrl.isEmpty()) {
            layoutVoiceoverRow.setVisibility(View.GONE);
        }
        injectAdvancedControls();
        if (videoUri != null && !videoUri.isEmpty() && isFilePath) detectSilentOriginalAudio();
    }

    private void injectAdvancedControls() {
        android.view.ViewGroup root = null;
        android.view.View cursor = layoutVoiceoverRow;
        for (int d = 0; d < 8 && cursor != null; d++) {
            android.view.ViewParent p = cursor.getParent();
            if (p instanceof android.widget.LinearLayout) { root = (android.view.ViewGroup) p; break; }
            cursor = (p instanceof android.view.View) ? (android.view.View) p : null;
        }
        if (root == null) return;
        float dp = getResources().getDisplayMetrics().density;

        android.widget.TextView tvAdv = new android.widget.TextView(this);
        tvAdv.setText("Advanced"); tvAdv.setTextColor(0xFFAAAAAA); tvAdv.setTextSize(11f);
        tvAdv.setPadding((int)(16*dp),(int)(14*dp),(int)(16*dp),0);
        root.addView(tvAdv);

        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setPadding((int)(12*dp),(int)(6*dp),(int)(12*dp),0);
        row.setWeightSum(2f);

        android.widget.Button btnFI = mkFadeBtn("Fade In");
        android.widget.Button btnFO = mkFadeBtn("Fade Out");
        btnFI.setOnClickListener(v -> {
            fadeInMs = fadeInMs > 0 ? 0 : 500;
            btnFI.setAlpha(fadeInMs > 0 ? 1f : 0.5f);
            btnFI.setText(fadeInMs > 0 ? "v Fade In" : "Fade In");
        });
        btnFO.setOnClickListener(v -> {
            fadeOutMs = fadeOutMs > 0 ? 0 : 500;
            btnFO.setAlpha(fadeOutMs > 0 ? 1f : 0.5f);
            btnFO.setText(fadeOutMs > 0 ? "v Fade Out" : "Fade Out");
        });
        android.widget.LinearLayout.LayoutParams half =
            new android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        half.setMargins((int)(4*dp),0,(int)(4*dp),0);
        row.addView(btnFI, half); row.addView(btnFO, half);
        root.addView(row);

        // ✅ NEW: Normalize toggle — peak-normalizes the mixed music track so
        // quiet tracks aren't drowned out and loud ones don't clip.
        android.widget.LinearLayout normRow = new android.widget.LinearLayout(this);
        normRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        normRow.setPadding((int)(12*dp),(int)(8*dp),(int)(12*dp),0);
        android.widget.Button btnNorm = mkFadeBtn("Normalize");
        btnNorm.setOnClickListener(v -> {
            normalizeOn = !normalizeOn;
            btnNorm.setAlpha(normalizeOn ? 1f : 0.5f);
            btnNorm.setText(normalizeOn ? "✓ Normalize" : "Normalize");
        });
        android.widget.LinearLayout.LayoutParams full =
            new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        full.setMargins((int)(4*dp),0,(int)(4*dp),0);
        normRow.addView(btnNorm, full);
        root.addView(normRow);

        android.widget.TextView tvPL = new android.widget.TextView(this);
        tvPL.setText("Pitch: 0.0 semitones"); tvPL.setTextColor(0xFFFFFFFF); tvPL.setTextSize(13f);
        tvPL.setPadding((int)(16*dp),(int)(10*dp),(int)(16*dp),0);
        root.addView(tvPL);

        SeekBar sbP = new SeekBar(this); sbP.setMax(120); sbP.setProgress(60);
        android.widget.LinearLayout.LayoutParams plp =
            new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        plp.setMargins((int)(16*dp),(int)(4*dp),(int)(16*dp),(int)(16*dp));
        sbP.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                pitchSemitones = (p - 60) / 10f;
                tvPL.setText(String.format(java.util.Locale.US, "Pitch: %+.1f semitones", pitchSemitones));
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        root.addView(sbP, plp);
    }

    private android.widget.Button mkFadeBtn(String lbl) {
        android.widget.Button b = new android.widget.Button(this);
        b.setText(lbl); b.setTextColor(0xFFFFFFFF);
        b.setBackgroundColor(0xFF333333); b.setAlpha(0.5f); b.setAllCaps(false);
        return b;
    }

    private void detectSilentOriginalAudio() {
        com.callx.app.music.AudioMixHelper.checkIfSilent(this, videoUri, isSilent -> {
            if (isSilent && !isFinishing()) {
                Toast.makeText(this,
                    "Original video has no audio. 'Original' slider is inactive.",
                    Toast.LENGTH_LONG).show();
                if (sbOrigVol != null) sbOrigVol.setAlpha(0.35f);
            }
        });
    }

    private void bindViews() {
        playerView        = findViewById(R.id.mixer_player_view);
        btnBack           = findViewById(R.id.btn_mixer_back);
        btnApply          = findViewById(R.id.btn_mixer_apply);
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

    // ✅ NEW: Wire click on music title/artist row → SoundDetailActivity;
    //         and hook up a "Change Music" button if the layout has one.
    private void setupMusicRowClicks() {
        if (tvMusicTitle != null) {
            tvMusicTitle.setOnClickListener(v -> openSoundDetail());
        }
        if (tvMusicArtist != null) {
            tvMusicArtist.setOnClickListener(v -> openSoundDetail());
        }
        // "Change Music" button (id: btn_mixer_change_music) — optional in layout
        View btnChangeMusic = findViewById(R.id.btn_mixer_change_music);
        if (btnChangeMusic != null) {
            btnChangeMusic.setOnClickListener(v -> openChangeMusicPicker());
        }
    }

    /** Opens SoundDetailActivity for the currently loaded sound. */
    private void openSoundDetail() {
        if (musicUrl == null || musicUrl.isEmpty()) {
            // No sound loaded — go to picker instead
            openChangeMusicPicker();
            return;
        }
        Intent i = new Intent(this, SoundDetailActivity.class);
        i.putExtra(SoundDetailActivity.EXTRA_SOUND_ID,    soundId);
        i.putExtra(SoundDetailActivity.EXTRA_SOUND_TITLE, currentTitle);
        i.putExtra(SoundDetailActivity.EXTRA_ARTIST,      currentArtist);
        i.putExtra(SoundDetailActivity.EXTRA_SOUND_URL,   musicUrl);
        startActivityForResult(i, REQ_SOUND_DETAIL);
    }

    /** Opens ReelTrendingAudioActivity so the user can pick a different track. */
    private void openChangeMusicPicker() {
        startActivityForResult(
            new Intent(this, ReelTrendingAudioActivity.class), REQ_CHANGE_MUSIC);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;

        if (requestCode == REQ_SOUND_DETAIL) {
            // "Use This Sound" tapped in SoundDetail for the already-loaded sound —
            // just dismiss; the sound is unchanged (it was already loaded).
            Toast.makeText(this, "Sound confirmed", Toast.LENGTH_SHORT).show();

        } else if (requestCode == REQ_CHANGE_MUSIC) {
            // User selected a new track from ReelTrendingAudioActivity
            String newId     = nvl(data.getStringExtra(ReelTrendingAudioActivity.RESULT_AUDIO_ID));
            String newTitle  = nvl(data.getStringExtra(ReelTrendingAudioActivity.RESULT_AUDIO_TITLE));
            String newArtist = nvl(data.getStringExtra(ReelTrendingAudioActivity.RESULT_AUDIO_ARTIST));
            String newUrl    = nvl(data.getStringExtra(ReelTrendingAudioActivity.RESULT_AUDIO_URL));
            if (!newUrl.isEmpty()) {
                soundId       = newId;
                currentTitle  = newTitle;
                currentArtist = newArtist;
                musicUrl      = newUrl;
                populateMusicInfo(currentTitle, currentArtist);
                // Restart music preview player with new URL
                if (musicPlayer != null) {
                    try { musicPlayer.stop(); musicPlayer.release(); } catch (Exception ignored) {}
                    musicPlayer = null;
                }
                startMusicPreview(newUrl);
                Toast.makeText(this, "Music changed: " + newTitle, Toast.LENGTH_SHORT).show();
            }
        }
    }

    /** Starts (or restarts) the background music preview player. */
    private void startMusicPreview(String url) {
        try {
            musicPlayer = new MediaPlayer();
            musicPlayer.setDataSource(url);
            musicPlayer.setLooping(true);
            musicPlayer.prepareAsync();
            musicPlayer.setOnPreparedListener(mp -> {
                mp.setVolume(musicVol, musicVol);
                mp.start();
            });
        } catch (Exception ignored) {}
    }

    /** Null-safe empty-string default. */
    private static String nvl(String s) { return s != null ? s : ""; }

    private void applyAndReturn() {
        Intent result = new Intent();
        result.putExtra(RESULT_ORIG_VOL,        origVol);
        result.putExtra(RESULT_MUSIC_VOL,       musicVol);
        result.putExtra(RESULT_VOICEOVER_PATH,  voiceoverPath != null ? voiceoverPath : "");
        result.putExtra(RESULT_VOICEOVER_VOL,   voiceoverVol);
        result.putExtra(RESULT_FADE_IN_MS,      fadeInMs);
        result.putExtra(RESULT_FADE_OUT_MS,     fadeOutMs);
        result.putExtra(RESULT_PITCH_SEMITONES, pitchSemitones);
        result.putExtra(RESULT_NORMALIZE,       normalizeOn);

        // ✅ FIX: Always send back the current music track info so the caller
        // (ReelEditorActivity) can update its preSelectedSoundUrl / soundId /
        // title — even when the user changed the track via the "Change" button.
        // Without this, the old original-sound URL is passed to ReelUploadActivity
        // and the new track is silently ignored after upload.
        result.putExtra(RESULT_MUSIC_URL,    musicUrl     != null ? musicUrl     : "");
        result.putExtra(RESULT_MUSIC_ID,     soundId      != null ? soundId      : "");
        result.putExtra(RESULT_MUSIC_TITLE,  currentTitle != null ? currentTitle : "");
        result.putExtra(RESULT_MUSIC_ARTIST, currentArtist!= null ? currentArtist: "");

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
