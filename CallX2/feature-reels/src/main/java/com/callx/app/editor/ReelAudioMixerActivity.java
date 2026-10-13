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
import com.callx.app.viewer.StatusAudioFocusHelper;

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
    /** ✅ FIX: photo-reel mode has no video file — caller passes the (cover) photo
     *  URI/path here so the preview card can show a static image instead of ExoPlayer. */
    public static final String EXTRA_PHOTO_URI    = "mixer_photo_uri";

    /** Optional clip range (ms) inside the FULL track chosen on the Adjust Music screen. The preview
     *  starts at START and loops back to START at END instead of always playing 0:00 → end. */
    public static final String EXTRA_MUSIC_START_MS = "mixer_music_start_ms";
    public static final String EXTRA_MUSIC_END_MS   = "mixer_music_end_ms";
    /** Status mode: Voiceover + Normalize (need offline processing) are hidden, and for a photo
     *  status (no EXTRA_VIDEO_URI) the Original-audio row is hidden too. Mix is stored on the
     *  music sticker and applied live at playback, not baked into the media. */
    public static final String EXTRA_STATUS_MODE    = "mixer_status_mode";
    /** Optional starting mix (re-editing a Status music sticker). Missing = the screen defaults. */
    public static final String EXTRA_INIT_ORIG_VOL     = "mixer_init_orig_vol";
    public static final String EXTRA_INIT_MUSIC_VOL    = "mixer_init_music_vol";
    public static final String EXTRA_INIT_FADE_IN_MS   = "mixer_init_fade_in_ms";
    public static final String EXTRA_INIT_FADE_OUT_MS  = "mixer_init_fade_out_ms";
    public static final String EXTRA_INIT_PITCH        = "mixer_init_pitch";

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
    /** Clip range after the mixer closes. Both 0 when the user swapped the track via "Change"
     *  (the old range doesn't apply to a different song). */
    public static final String RESULT_MUSIC_START_MS = "result_music_start_ms";
    public static final String RESULT_MUSIC_END_MS   = "result_music_end_ms";

    private static final int REQ_MIC          = 501;
    /** ✅ NEW: request codes for SoundDetail and "Change Music" pickers */
    private static final int REQ_SOUND_DETAIL = 901;
    private static final int REQ_CHANGE_MUSIC = 902;

    private PlayerView    playerView;
    private ImageView     photoPreview;
    private ImageButton   btnBack;
    private TextView      btnApply;
    private TextView      tvMusicTitle, tvMusicArtist;
    private SeekBar       sbOrigVol, sbMusicVol, sbVoiceoverVol;
    private TextView      tvOrigVolPct, tvMusicVolPct, tvVoiceoverVolPct;
    private ImageButton   btnMuteOrig, btnMuteMusic, btnMuteVoiceover;
    private ImageButton   btnVoiceoverRecord;
    private TextView      tvVoiceoverStatus;
    private ProgressBar   progressBuf;
    private View          layoutVoiceoverRow;
    // Section views addressed by id (no more "previous child" index hacks)
    private View          tvOrigLabel, layoutOrigRow;
    private RadioGroup    rgFadeIn, rgFadeOut;
    private CheckBox      cbNormalize;
    private SeekBar       sbPitch;
    private TextView      tvPitchLabel;

    private ExoPlayer  exoPlayer;
    private MediaPlayer musicPlayer;
    private MediaRecorder recorder;

    private String videoUri;
    private boolean isFilePath;
    private String photoUri;
    private String musicUrl;
    private String voiceoverPath;
    // ✅ NEW: stored so we can open SoundDetailActivity
    private String soundId       = "";
    private String currentTitle  = "";
    private String currentArtist = "";
    private boolean isRecordingVoiceover = false;
    private int     musicStartMs = 0;
    private int     musicEndMs   = 0;
    private boolean statusMode   = false;
    private boolean musicMuted   = false;
    private boolean origMuted    = false;
    private boolean voiceoverMuted = false;
    /** true only after MediaPlayer.onPrepared — start()/isPlaying()/pause() are illegal before that. */
    private boolean musicPrepared = false;
    /** true once a non-zero pitch was set, so returning to 0 still resets the player once. */
    private boolean pitchApplied = false;
    private StatusAudioFocusHelper audioFocus;
    /** 1 normally, <1 while another app asks us to duck. */
    private float duckFactor = 1f;
    /** paused because focus was lost (call / other app), not by the screen going to background. */
    private boolean focusPaused = false;

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
        photoUri      = getIntent().getStringExtra(EXTRA_PHOTO_URI);
        musicUrl      = getIntent().getStringExtra(EXTRA_MUSIC_URL);
        soundId       = nvl(getIntent().getStringExtra(EXTRA_SOUND_ID));
        currentTitle  = nvl(getIntent().getStringExtra(EXTRA_MUSIC_TITLE));
        currentArtist = nvl(getIntent().getStringExtra(EXTRA_MUSIC_ARTIST));
        musicStartMs  = Math.max(0, getIntent().getIntExtra(EXTRA_MUSIC_START_MS, 0));
        musicEndMs    = Math.max(0, getIntent().getIntExtra(EXTRA_MUSIC_END_MS, 0));
        if (musicEndMs <= musicStartMs) musicEndMs = 0;   // 0 = until the end of the track
        statusMode    = getIntent().getBooleanExtra(EXTRA_STATUS_MODE, false);
        // A saved mix stores "muted" as volume 0 → reopen as muted (icon off) with the slider at its
        // default level, so un-muting brings sound back instead of landing on 0%.
        if (getIntent().hasExtra(EXTRA_INIT_ORIG_VOL)) {
            float v = Math.max(0f, Math.min(1f, getIntent().getFloatExtra(EXTRA_INIT_ORIG_VOL, origVol)));
            if (v <= 0f) origMuted = true; else origVol = v;
        }
        if (getIntent().hasExtra(EXTRA_INIT_MUSIC_VOL)) {
            float v = Math.max(0f, Math.min(1f, getIntent().getFloatExtra(EXTRA_INIT_MUSIC_VOL, musicVol)));
            if (v <= 0f) musicMuted = true; else musicVol = v;
        }
        fadeInMs       = Math.max(0, getIntent().getIntExtra(EXTRA_INIT_FADE_IN_MS, 0));
        fadeOutMs      = Math.max(0, getIntent().getIntExtra(EXTRA_INIT_FADE_OUT_MS, 0));
        pitchSemitones = Math.max(-6f, Math.min(6f, getIntent().getFloatExtra(EXTRA_INIT_PITCH, 0f)));

        audioFocus = new StatusAudioFocusHelper(this, new StatusAudioFocusHelper.Listener() {
            @Override public void onFocusLost()     { focusPaused = true;  pausePreview(); }
            @Override public void onFocusRegained() { focusPaused = false; resumePreview(); }
            @Override public void onDuck(boolean d) {
                duckFactor = d ? 0.3f : 1f;
                applyOrigVolume(); applyMusicVolume();
            }
        });

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
        setupAdvancedControls();
        if (statusMode) applyStatusModeUi();
        if (videoUri != null && !videoUri.isEmpty()) detectSilentOriginalAudio();
    }

    /** Hides what Status can't do at playback time: Voiceover, Normalize, and Original audio for photo status. */
    private void applyStatusModeUi() {
        if (layoutVoiceoverRow != null) layoutVoiceoverRow.setVisibility(View.GONE);
        if (cbNormalize != null) cbNormalize.setVisibility(View.GONE);
        boolean hasVideo = videoUri != null && !videoUri.isEmpty();
        if (!hasVideo) {
            if (tvOrigLabel != null)   tvOrigLabel.setVisibility(View.GONE);
            if (layoutOrigRow != null) layoutOrigRow.setVisibility(View.GONE);
        }
    }

    private static final int[] FADE_CHOICES_MS = {0, 500, 1000, 2000};
    private static final int[] FADE_IN_IDS  = {R.id.rb_fade_in_off,  R.id.rb_fade_in_500,
                                               R.id.rb_fade_in_1000, R.id.rb_fade_in_2000};
    private static final int[] FADE_OUT_IDS = {R.id.rb_fade_out_off,  R.id.rb_fade_out_500,
                                               R.id.rb_fade_out_1000, R.id.rb_fade_out_2000};

    /** Wires the Advanced section (declared in XML): fade chips, Normalize, Pitch. */
    private void setupAdvancedControls() {
        bindFadeGroup(rgFadeIn,  FADE_IN_IDS,  fadeInMs,  ms -> fadeInMs  = ms);
        bindFadeGroup(rgFadeOut, FADE_OUT_IDS, fadeOutMs, ms -> fadeOutMs = ms);

        cbNormalize.setChecked(normalizeOn);
        cbNormalize.setOnCheckedChangeListener((b, on) -> normalizeOn = on);

        sbPitch.setProgress(Math.round(pitchSemitones * 10f) + 60);
        updatePitchLabel();
        sbPitch.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                pitchSemitones = (p - 60) / 10f;
                updatePitchLabel();
                applyPitchToMusicPreview();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
    }

    private void updatePitchLabel() {
        tvPitchLabel.setText(String.format(java.util.Locale.US,
            getString(R.string.mixer_pitch_fmt), pitchSemitones));
    }

    private interface IntSetter { void set(int v); }

    /** Single-select chip row: pre-selects the preset nearest to a restored value. */
    private void bindFadeGroup(RadioGroup group, int[] ids, int currentMs, IntSetter setter) {
        int sel = 0;
        for (int i = 1; i < FADE_CHOICES_MS.length; i++)
            if (Math.abs(FADE_CHOICES_MS[i] - currentMs) < Math.abs(FADE_CHOICES_MS[sel] - currentMs)) sel = i;
        if (currentMs <= 0) sel = 0;
        group.check(ids[sel]);
        group.setOnCheckedChangeListener((g, checkedId) -> {
            for (int i = 0; i < ids.length; i++)
                if (ids[i] == checkedId) { setter.set(FADE_CHOICES_MS[i]); return; }
        });
    }

    /**
     * ✅ FIX: Live-applies {@link #pitchSemitones} to the currently playing
     * background-music preview via {@link android.media.PlaybackParams}, so
     * moving the Pitch slider is actually audible in the mixer screen.
     * Speed is kept at 1.0 — only pitch changes (semitone → frequency-ratio
     * conversion: ratio = 2^(semitones/12)).
     * Silently no-ops on API < 23 or if the player isn't in a valid state.
     */
    private void applyPitchToMusicPreview() {
        if (musicPlayer == null || !musicPrepared) return;
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) return;
        try {
            // Setting params on a paused player starts it, so only touch a playing one
            // (onPrepared re-applies pitch right after start()).
            if (!musicPlayer.isPlaying()) return;
            // At 0 semitones leave the player on its default params (no needless setPlaybackParams).
            if (Math.abs(pitchSemitones) < 0.01f && !pitchApplied) return;
            float ratio = (float) Math.pow(2.0, pitchSemitones / 12.0);
            android.media.PlaybackParams params = new android.media.PlaybackParams();
            params.setPitch(Math.max(0.5f, Math.min(2.0f, ratio)));
            params.setSpeed(1.0f);
            musicPlayer.setPlaybackParams(params);
            pitchApplied = Math.abs(pitchSemitones) >= 0.01f;
        } catch (Exception ignored) {
            // Some OEM decoders throw if the player isn't started/prepared yet —
            // harmless, the value is still applied at final mix via AudioMixHelper.
        }
    }

    private void detectSilentOriginalAudio() {
        com.callx.app.music.AudioMixHelper.checkIfSilent(this, videoUri, isSilent -> {   // handles file path AND content:// uri
            if (isSilent && !isFinishing()) {
                Toast.makeText(this,
                    "Original video has no audio. 'Original' slider is inactive.",
                    Toast.LENGTH_LONG).show();
                if (sbOrigVol != null) { sbOrigVol.setAlpha(0.35f); sbOrigVol.setEnabled(false); }
                if (btnMuteOrig != null) { btnMuteOrig.setAlpha(0.35f); btnMuteOrig.setEnabled(false); }
            }
        });
    }

    private void bindViews() {
        playerView        = findViewById(R.id.mixer_player_view);
        photoPreview      = findViewById(R.id.mixer_photo_preview);
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
        tvOrigLabel       = findViewById(R.id.tv_mixer_orig_label);
        layoutOrigRow     = findViewById(R.id.layout_mixer_orig_row);
        rgFadeIn          = findViewById(R.id.rg_fade_in);
        rgFadeOut         = findViewById(R.id.rg_fade_out);
        cbNormalize       = findViewById(R.id.cb_mixer_normalize);
        sbPitch           = findViewById(R.id.sb_mixer_pitch);
        tvPitchLabel      = findViewById(R.id.tv_mixer_pitch_label);
    }

    private void populateMusicInfo(String title, String artist) {
        tvMusicTitle.setText(title  != null && !title.isEmpty()  ? title  : "No music selected");
        tvMusicArtist.setText(artist != null && !artist.isEmpty() ? artist : "—");
    }

    @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
    private void setupPlayer() {
        // ✅ FIX: Photo-reel flow has no local video file to preview (mixer opened right
        // after the trim screen for photo slideshows too). Previously this just skipped
        // preview setup entirely, leaving the preview card blank. Now we show the
        // selected/cover photo instead via ImageView + Glide.
        if ((videoUri == null || videoUri.isEmpty()) && photoUri != null && !photoUri.isEmpty()) {
            playerView.setVisibility(View.GONE);
            photoPreview.setVisibility(View.VISIBLE);
            com.bumptech.glide.Glide.with(this)
                .load(photoUri)
                .centerCrop()
                .into(photoPreview);
        }

        if (videoUri != null && !videoUri.isEmpty()) {
            photoPreview.setVisibility(View.GONE);
            exoPlayer = new ExoPlayer.Builder(this).build();
            playerView.setPlayer(exoPlayer);
            // ✅ FIX: preview card (200dp tall, full width) is much wider than a
            // portrait reel video's aspect ratio. Default PlayerView resize mode
            // (RESIZE_MODE_FIT) letterboxes the video, leaving large black strips
            // on left/right where no video is visible. RESIZE_MODE_ZOOM crops and
            // fills the whole preview box instead, so video is visible edge-to-edge.
            playerView.setResizeMode(androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
            Uri uri = isFilePath
                ? Uri.fromFile(new File(videoUri))
                : Uri.parse(videoUri);
            exoPlayer.setMediaItem(MediaItem.fromUri(uri));
            exoPlayer.setRepeatMode(androidx.media3.common.Player.REPEAT_MODE_ONE);
            applyOrigVolume();
            exoPlayer.prepare();
            exoPlayer.setPlayWhenReady(true);

            exoPlayer.addListener(new androidx.media3.common.Player.Listener() {
                @Override public void onPlaybackStateChanged(int state) {
                    progressBuf.setVisibility(
                        state == androidx.media3.common.Player.STATE_BUFFERING
                            ? View.VISIBLE : View.GONE);
                }
            });
        }

        startMusicPreview();
    }

    private void startMusicPreview() {
        if (musicUrl == null || musicUrl.isEmpty()) return;
        startMusicPreview(musicUrl);
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
                if (u && origMuted && p > 0) { origMuted = false; updateMuteIcons(); }
                applyOrigVolume();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        sbMusicVol.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                musicVol = p / 100f;
                tvMusicVolPct.setText(p + "%");
                if (u && musicMuted && p > 0) { musicMuted = false; updateMuteIcons(); }
                applyMusicVolume();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        sbVoiceoverVol.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                voiceoverVol = p / 100f;
                tvVoiceoverVolPct.setText(p + "%");
                if (u && voiceoverMuted && p > 0) { voiceoverMuted = false; updateMuteIcons(); }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
    }

    private void setupMuteToggles() {
        updateMuteIcons();   // restores the muted look when a saved mix is re-edited
        btnMuteOrig.setOnClickListener(v -> {
            origMuted = !origMuted;
            applyOrigVolume();
            updateMuteIcons();
        });
        btnMuteMusic.setOnClickListener(v -> {
            musicMuted = !musicMuted;
            applyMusicVolume();
            updateMuteIcons();
        });
        btnMuteVoiceover.setOnClickListener(v -> {
            voiceoverMuted = !voiceoverMuted;
            updateMuteIcons();
        });
    }

    private void updateMuteIcons() {
        btnMuteOrig.setImageResource(origMuted ? R.drawable.ic_volume_off : R.drawable.ic_volume_on);
        btnMuteMusic.setImageResource(musicMuted ? R.drawable.ic_volume_off : R.drawable.ic_volume_on);
        btnMuteVoiceover.setImageResource(voiceoverMuted ? R.drawable.ic_volume_off : R.drawable.ic_volume_on);
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
                // The old clip range belonged to the previous song.
                musicStartMs  = 0;
                musicEndMs    = 0;
                populateMusicInfo(currentTitle, currentArtist);
                // Restart music preview player with new URL
                startMusicPreview(newUrl);
                Toast.makeText(this, "Music changed: " + newTitle, Toast.LENGTH_SHORT).show();
            }
        }
    }

    /** Starts (or restarts) the background music preview player, honouring the chosen clip range. */
    private void startMusicPreview(String url) {
        handler.removeCallbacks(musicTick);
        musicPrepared = false;
        pitchApplied = false;
        if (musicPlayer != null) {
            try { musicPlayer.stop(); } catch (Exception ignored) {}
            try { musicPlayer.release(); } catch (Exception ignored) {}
            musicPlayer = null;
        }
        try {
            musicPlayer = new MediaPlayer();
            musicPlayer.setDataSource(url);
            musicPlayer.setLooping(true);
            musicPlayer.setOnPreparedListener(mp -> {
                if (isFinishing() || isDestroyed() || mp != musicPlayer) return;
                musicPrepared = true;
                if (musicStartMs > 0) mp.seekTo(musicStartMs);
                applyMusicVolume();
                if (focusPaused || !audioFocus.request()) return;   // refused (e.g. phone call) → stay silent
                mp.start();
                // re-apply pitch already set (slider moved before prepare / track switched)
                applyPitchToMusicPreview();
                handler.removeCallbacks(musicTick);
                handler.post(musicTick);
            });
            musicPlayer.prepareAsync();
        } catch (Exception e) {
            Toast.makeText(this, "Music preview failed", Toast.LENGTH_SHORT).show();
        }
    }

    /** Keeps the preview inside [musicStartMs, musicEndMs] and applies fade in/out per loop. */
    private final Runnable musicTick = new Runnable() {
        @Override public void run() {
            MediaPlayer mp = musicPlayer;
            if (mp == null || isFinishing()) return;
            try {
                if (mp.isPlaying()) {
                    int pos = mp.getCurrentPosition();
                    int end = effectiveEndMs(mp);
                    if ((musicEndMs > 0 && pos >= musicEndMs) || (musicStartMs > 0 && pos < musicStartMs - 300)) {
                        mp.seekTo(musicStartMs);
                        pos = musicStartMs;
                    }
                    applyMusicVolume(fadeFactor(pos, musicStartMs, end));
                }
            } catch (IllegalStateException ignored) { /* released between ticks */ }
            handler.postDelayed(this, 50);
        }
    };

    private int effectiveEndMs(MediaPlayer mp) {
        if (musicEndMs > 0) return musicEndMs;
        try { return mp.getDuration(); } catch (Exception e) { return 0; }
    }

    /** 0..1 gain for the current position inside the loop, from the fade in/out settings. */
    private float fadeFactor(int pos, int start, int end) {
        float f = 1f;
        if (fadeInMs > 0) f = Math.min(f, Math.max(0f, (pos - start) / (float) fadeInMs));
        if (fadeOutMs > 0 && end > 0) f = Math.min(f, Math.max(0f, (end - pos) / (float) fadeOutMs));
        return f;
    }

    private void applyOrigVolume() {
        if (exoPlayer != null) exoPlayer.setVolume(origMuted ? 0f : origVol * duckFactor);
    }

    private void applyMusicVolume() { applyMusicVolume(1f); }

    private void applyMusicVolume(float fade) {
        if (musicPlayer == null) return;
        float v = musicMuted ? 0f : musicVol * fade * duckFactor;
        try { musicPlayer.setVolume(v, v); } catch (IllegalStateException ignored) {}
    }

    /** Null-safe empty-string default. */
    private static String nvl(String s) { return s != null ? s : ""; }

    private void applyAndReturn() {
        Intent result = new Intent();
        // A muted track must reach the final mix as 0 (Status + Reels), not just silence the preview.
        result.putExtra(RESULT_ORIG_VOL,        origMuted ? 0f : origVol);
        result.putExtra(RESULT_MUSIC_VOL,       musicMuted ? 0f : musicVol);
        result.putExtra(RESULT_VOICEOVER_PATH,  voiceoverPath != null ? voiceoverPath : "");
        result.putExtra(RESULT_VOICEOVER_VOL,   voiceoverMuted ? 0f : voiceoverVol);
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

        result.putExtra(RESULT_MUSIC_START_MS, musicStartMs);
        result.putExtra(RESULT_MUSIC_END_MS,   musicEndMs);

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

    private void pausePreview() {
        if (exoPlayer != null) exoPlayer.pause();
        if (musicPlayer != null && musicPrepared) {
            try { if (musicPlayer.isPlaying()) musicPlayer.pause(); }
            catch (IllegalStateException ignored) {}
        }
        handler.removeCallbacks(musicTick);
    }

    private void resumePreview() {
        if (exoPlayer != null) exoPlayer.play();
        // Only after onPrepared: right after onCreate the player is still preparing → start() would throw.
        if (musicPlayer != null && musicPrepared) {
            try { if (!musicPlayer.isPlaying()) musicPlayer.start(); }
            catch (IllegalStateException ignored) {}
            applyPitchToMusicPreview();   // pitch moved while paused is applied now
            handler.removeCallbacks(musicTick);
            handler.post(musicTick);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        pausePreview();
        audioFocus.abandon();
    }

    @Override
    protected void onResume() {
        super.onResume();
        focusPaused = false;
        if (audioFocus.request()) resumePreview();
        else focusPaused = true;   // refused (call active) → wait for onFocusRegained
    }

    @Override
    protected void onDestroy() {
        if (isRecordingVoiceover) stopVoiceoverRecording();
        if (exoPlayer != null) { exoPlayer.stop(); exoPlayer.release(); }
        musicPrepared = false;
        if (audioFocus != null) audioFocus.abandon();
        if (musicPlayer != null) { try { musicPlayer.release(); } catch (Exception ignored) {} musicPlayer = null; }
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
