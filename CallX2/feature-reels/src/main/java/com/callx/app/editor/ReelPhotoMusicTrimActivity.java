package com.callx.app.editor;

import android.content.Intent;
import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.*;

import java.io.File;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.media.AudioAttributesCompat;
import androidx.media.AudioFocusRequestCompat;
import androidx.media.AudioManagerCompat;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.callx.app.music.ReelTrendingAudioActivity;
import com.callx.app.music.SoundDetailSheetFragment;
import com.callx.app.reels.R;
import com.callx.app.views.AudioTrimWaveformView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ReelPhotoMusicTrimActivity — shown right after the user picks a track
 * (from ReelTrendingAudioActivity) while creating a photo-slideshow reel.
 *
 * Combines:
 *  • A live reel-style photo preview (cycles through the selected photos)
 *  • A modern draggable dual-handle waveform trimmer (AudioTrimWaveformView)
 *    plus the classic Start/End seekbars, kept in sync with each other
 *
 * The user trims the track to exactly the portion they want; only that
 * trimmed range (RESULT_START_MS…RESULT_END_MS) is stored on the reel and
 * played back at view time — the rest of the track is never used.
 *
 * Playback-side support for a trimmed range already exists
 * (ReelPlayerController / ReelPlayerFragment read reel.musicStartMs /
 * reel.musicEndMs) — this screen is what lets the uploader actually set
 * those values for photo reels instead of always defaulting to 0.
 *
 * UI note: colors used throughout this screen's layout come from the
 * @color/trim_* tokens (values/colors.xml + values-night/colors.xml), so
 * the screen automatically follows the device's light/dark mode — no
 * theme-detection code needed here beyond resolving those same tokens for
 * the custom waveform view's Canvas-drawn palette (setupWaveformPalette()).
 */
public class ReelPhotoMusicTrimActivity extends AppCompatActivity {

    public static final String EXTRA_PHOTO_URIS   = "trim_photo_uris";
    /** Per-photo slide duration in ms, used to pace the preview. Default 3000. */
    public static final String EXTRA_SLIDE_MS     = "trim_slide_ms";

    /** Video-reel flow only: the selected video's uri/path, so the preview can show
     *  the actual clip instead of falling back to the track's cover art. */
    public static final String EXTRA_VIDEO_URI         = "trim_video_uri";
    public static final String EXTRA_VIDEO_IS_FILE_PATH = "trim_video_is_file_path";

    public static final String EXTRA_SOUND_ID     = "trim_sound_id";
    public static final String EXTRA_SOUND_TITLE  = "trim_sound_title";
    public static final String EXTRA_SOUND_ARTIST = "trim_sound_artist";
    public static final String EXTRA_SOUND_URL    = "trim_sound_url";
    public static final String EXTRA_SOUND_COVER  = "trim_sound_cover";
    public static final String EXTRA_DURATION_MS  = "trim_duration_ms";
    /** Optional — resume trimming an already-chosen range (e.g. re-opened via "Change"). */
    public static final String EXTRA_INITIAL_START_MS = "trim_initial_start_ms";
    public static final String EXTRA_INITIAL_END_MS   = "trim_initial_end_ms";

    public static final String RESULT_START_MS    = "result_start_ms";
    public static final String RESULT_END_MS      = "result_end_ms";
    public static final String RESULT_SOUND_ID    = "result_sound_id";
    public static final String RESULT_SOUND_TITLE = "result_sound_title";
    public static final String RESULT_SOUND_ARTIST= "result_sound_artist";
    public static final String RESULT_SOUND_URL   = "result_sound_url";
    public static final String RESULT_SOUND_COVER = "result_sound_cover";

    /** Convenience launcher — photo-reel flow (no video preview). */
    public static void start(AppCompatActivity from, ArrayList<String> photoUris, int slideMs,
                              String soundId, String soundTitle, String soundArtist,
                              String soundUrl, String soundCover, int durationMs,
                              int initialStartMs, int initialEndMs, int requestCode) {
        start(from, photoUris, slideMs, soundId, soundTitle, soundArtist, soundUrl, soundCover,
            durationMs, initialStartMs, initialEndMs, null, false, requestCode);
    }

    /** Convenience launcher — video-reel flow: pass the selected video so the preview
     *  can actually show it (muted, looping) instead of just the track's cover art. */
    public static void start(AppCompatActivity from, ArrayList<String> photoUris, int slideMs,
                              String soundId, String soundTitle, String soundArtist,
                              String soundUrl, String soundCover, int durationMs,
                              int initialStartMs, int initialEndMs,
                              @Nullable String videoUri, boolean videoIsFilePath, int requestCode) {
        Intent i = new Intent(from, ReelPhotoMusicTrimActivity.class);
        i.putStringArrayListExtra(EXTRA_PHOTO_URIS, photoUris);
        i.putExtra(EXTRA_SLIDE_MS,     slideMs);
        i.putExtra(EXTRA_SOUND_ID,     soundId);
        i.putExtra(EXTRA_SOUND_TITLE,  soundTitle);
        i.putExtra(EXTRA_SOUND_ARTIST, soundArtist);
        i.putExtra(EXTRA_SOUND_URL,    soundUrl);
        i.putExtra(EXTRA_SOUND_COVER,  soundCover);
        i.putExtra(EXTRA_DURATION_MS,  durationMs);
        i.putExtra(EXTRA_INITIAL_START_MS, initialStartMs);
        i.putExtra(EXTRA_INITIAL_END_MS,   initialEndMs);
        if (videoUri != null && !videoUri.isEmpty()) {
            i.putExtra(EXTRA_VIDEO_URI, videoUri);
            i.putExtra(EXTRA_VIDEO_IS_FILE_PATH, videoIsFilePath);
        }
        from.startActivityForResult(i, requestCode);
    }

    private ImageButton  btnBack, btnPreview;
    private TextView      btnUse, btnChange;
    private TextView     tvTitle, tvStartTime, tvEndTime, tvDuration, tvSelectedRange;
    private TextView     tvStartValue, tvEndValue;
    private TextView     tvTrackTitle, tvTrackArtist;
    private ImageView    ivCover, ivPhotoPreview, ivPlayHint;
    private VideoView    videoPreview;
    private SeekBar      sbStart, sbEnd;
    private AudioTrimWaveformView waveformView;
    private RadioGroup   rgPresets;
    private ProgressBar  progressLoad;
    private View         layoutControls;
    private View         layoutTrackInfo;

    /** Request code for reopening the trending audio picker via the "Change" button. */
    private static final int REQ_CHANGE_MUSIC = 501;

    private String soundId, soundUrl, soundTitle, soundArtist, soundCover;
    private int    totalDurationMs;
    private int    startMs = 0;
    private int    endMs   = 30_000;

    /** Guards against feedback loops while seekbars/waveform sync each other. */
    private boolean syncingRange = false;

    private List<String> photoUris = new ArrayList<>();
    private int slideMs = 3000;
    private int previewPhotoIndex = 0;

    /** Video-reel flow: set when opened from video upload instead of photo slideshow. */
    @Nullable private Uri videoUri;
    private boolean videoIsFilePath;

    private MediaPlayer mediaPlayer;
    private boolean     isPreviewing = false;
    /** True only once the track is prepared (duration + waveform known). Gates Preview/Done. */
    private boolean     audioReady = false;
    private AudioFocusRequestCompat focusRequest;
    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {
        // Phone call / another app took audio — stop our preview instead of fighting it.
        if (change == AudioManager.AUDIOFOCUS_LOSS
                || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            runOnUiThread(this::stopPreview);
        }
    };

    private final Handler handler       = new Handler(Looper.getMainLooper());
    private final Handler slideHandler  = new Handler(Looper.getMainLooper());

    /** Ticks while previewing: stops playback at the trim end and drives the waveform playhead. */
    private final Runnable previewStopCheck = new Runnable() {
        @Override public void run() {
            if (mediaPlayer != null && isPreviewing) {
                int pos = mediaPlayer.getCurrentPosition();
                if (waveformView != null) waveformView.setPlayheadMs(pos);
                if (pos >= endMs) {
                    loopPreview();   // keep auditioning the selection until the user stops it
                } else {
                    handler.postDelayed(this, 60);
                }
            }
        }
    };

    /** Advances the photo preview to the next slide while previewing. */
    private final Runnable slideRunnable = new Runnable() {
        @Override public void run() {
            if (!isPreviewing || photoUris.isEmpty()) return;
            previewPhotoIndex = (previewPhotoIndex + 1) % photoUris.size();
            showPhoto(previewPhotoIndex);
            slideHandler.postDelayed(this, slideMs);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reel_photo_music_trim);

        soundId       = getIntent().getStringExtra(EXTRA_SOUND_ID);
        soundUrl      = getIntent().getStringExtra(EXTRA_SOUND_URL);
        soundTitle    = getIntent().getStringExtra(EXTRA_SOUND_TITLE);
        soundArtist   = getIntent().getStringExtra(EXTRA_SOUND_ARTIST);
        soundCover    = getIntent().getStringExtra(EXTRA_SOUND_COVER);
        totalDurationMs = getIntent().getIntExtra(EXTRA_DURATION_MS, 0);
        slideMs       = getIntent().getIntExtra(EXTRA_SLIDE_MS, 3000);
        if (slideMs <= 0) slideMs = 3000;

        ArrayList<String> uris = getIntent().getStringArrayListExtra(EXTRA_PHOTO_URIS);
        if (uris != null) photoUris = uris;

        String videoUriStr = getIntent().getStringExtra(EXTRA_VIDEO_URI);
        videoIsFilePath     = getIntent().getBooleanExtra(EXTRA_VIDEO_IS_FILE_PATH, false);
        if (videoUriStr != null && !videoUriStr.isEmpty()) {
            videoUri = videoIsFilePath ? Uri.fromFile(new File(videoUriStr)) : Uri.parse(videoUriStr);
        }

        int initStart = getIntent().getIntExtra(EXTRA_INITIAL_START_MS, 0);
        int initEnd   = getIntent().getIntExtra(EXTRA_INITIAL_END_MS, 0);
        if (initEnd > initStart) { startMs = initStart; endMs = initEnd; }

        bindViews();
        setupWaveformPalette();
        populateInfo();
        showPhoto(0);
        loadAudio();
        setupSeekbars();
        setupWaveformDrag();
        setupPresets();
        setupButtons();
    }

    private void bindViews() {
        btnBack         = findViewById(R.id.btn_trim_back);
        btnPreview      = findViewById(R.id.btn_trim_preview);
        btnUse          = findViewById(R.id.btn_trim_use);
        tvTitle         = findViewById(R.id.tv_trim_title);
        tvDuration      = findViewById(R.id.tv_trim_total_duration);
        tvTrackTitle    = findViewById(R.id.tv_trim_title_track);
        tvTrackArtist   = findViewById(R.id.tv_trim_artist);
        ivCover         = findViewById(R.id.iv_trim_cover);
        ivPhotoPreview  = findViewById(R.id.iv_photo_preview);
        videoPreview    = findViewById(R.id.video_preview);
        ivPlayHint      = findViewById(R.id.iv_preview_play_hint);
        sbStart         = findViewById(R.id.sb_trim_start);
        sbEnd           = findViewById(R.id.sb_trim_end);
        waveformView    = findViewById(R.id.waveform_trim_view);
        rgPresets       = findViewById(R.id.rg_trim_presets);
        progressLoad    = findViewById(R.id.progress_trim_load);
        layoutControls  = findViewById(R.id.layout_trim_controls);
        layoutTrackInfo = findViewById(R.id.layout_trim_track_info);
        btnChange       = findViewById(R.id.btn_trim_change);

        View.OnClickListener togglePreviewClick = v -> togglePreview();
        if (ivPhotoPreview != null) ivPhotoPreview.setOnClickListener(togglePreviewClick);
        if (ivPlayHint      != null) ivPlayHint.setOnClickListener(togglePreviewClick);
        // videoPreview sits on top of ivPhotoPreview once visible, so it needs its
        // own tap target to pause/resume, otherwise taps stop reaching ivPhotoPreview.
        if (videoPreview    != null) videoPreview.setOnClickListener(togglePreviewClick);

        setupVideoPreview();
    }

    /**
     * Video-reel flow: wires the actual selected clip into the preview VideoView
     * (muted, looped) so this screen shows the real video instead of leaving the
     * preview on the track's cover art. No-op for photo reels (videoUri is null).
     */
    private void setupVideoPreview() {
        if (videoPreview == null || videoUri == null) return;
        try {
            videoPreview.setVideoURI(videoUri);
            videoPreview.setOnPreparedListener(mp -> {
                mp.setLooping(true);
                mp.setVolume(0f, 0f); // silent — the trimmed music track is the audio
            });
            videoPreview.setOnErrorListener((mp, what, extra) -> true); // swallow, poster frame still shows
        } catch (Exception ignored) {}
    }

    /**
     * Resolves the waveform's Canvas-drawn colors from the day/night-aware
     * @color/trim_* resources so it matches the rest of the (XML-themed)
     * screen in both light and dark mode.
     */
    private void setupWaveformPalette() {
        if (waveformView == null) return;
        int dim       = ContextCompat.getColor(this, R.color.trim_waveform_dim);
        int start     = ContextCompat.getColor(this, R.color.trim_gradient_start);
        int end       = ContextCompat.getColor(this, R.color.trim_gradient_end);
        int playhead  = ContextCompat.getColor(this, R.color.trim_text_primary);
        int tooltipBg = ContextCompat.getColor(this, R.color.trim_tooltip_bg);
        int tooltipTx = ContextCompat.getColor(this, R.color.trim_tooltip_text);
        waveformView.setPalette(dim, start, end, playhead, tooltipBg, tooltipTx);
    }

    private void populateInfo() {
        if (tvTitle != null) tvTitle.setText("Adjust Music");
        if (tvTrackTitle != null)
            tvTrackTitle.setText(soundTitle != null && !soundTitle.isEmpty() ? soundTitle : "Original audio");
        if (tvTrackArtist != null)
            tvTrackArtist.setText(soundArtist != null && !soundArtist.isEmpty() ? soundArtist : "—");
        if (ivCover != null && soundCover != null && !soundCover.isEmpty()) {
            Glide.with(this).load(soundCover)
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(ivCover);
        }
        updateTimeLabels();
        if (totalDurationMs > 0 && tvDuration != null)
            tvDuration.setText(msToTime(totalDurationMs));
    }

    private void showPhoto(int index) {
        if (ivPhotoPreview == null) return;
        if (photoUris.isEmpty()) {
            // Video-reel flow: no slideshow photos to cycle through. Show a poster
            // frame grabbed from the actual selected video (Glide decodes a frame
            // for local file/content uris) so the card reflects the real clip;
            // the VideoView plays the clip itself once preview starts. Only fall
            // back to the track's cover art if no video was passed in.
            if (videoUri != null) {
                Glide.with(this).load(videoUri)
                    .transition(DrawableTransitionOptions.withCrossFade())
                    .into(ivPhotoPreview);
            } else if (soundCover != null && !soundCover.isEmpty()) {
                Glide.with(this).load(soundCover)
                    .transition(DrawableTransitionOptions.withCrossFade())
                    .into(ivPhotoPreview);
            }
            return;
        }
        if (index < 0 || index >= photoUris.size()) index = 0;
        String uriStr = photoUris.get(index);
        Glide.with(this).load(Uri.parse(uriStr))
            .transition(DrawableTransitionOptions.withCrossFade())
            .into(ivPhotoPreview);
    }

    private void loadAudio() {
        audioReady = false;
        if (soundUrl == null || soundUrl.isEmpty()) {
            // No track to validate — nothing to wait for.
            if (layoutControls != null) layoutControls.setVisibility(View.VISIBLE);
            if (progressLoad   != null) progressLoad.setVisibility(View.GONE);
            updateActionStates();
            return;
        }
        if (progressLoad != null) progressLoad.setVisibility(View.VISIBLE);
        updateActionStates();
        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setOnPreparedListener(mp -> runOnUiThread(() -> {
                if (isFinishing() || mediaPlayer != mp) return;
                int d = mp.getDuration();
                if (totalDurationMs <= 0 || d > 0 && d < totalDurationMs) totalDurationMs = d;
                if (totalDurationMs <= 0) { onAudioLoadFailed("Cannot load audio"); return; }
                if (tvDuration != null) tvDuration.setText(msToTime(totalDurationMs));
                // Keep the range inside the real track (min 1s window) so Done can never
                // return a range beyond the song's end.
                endMs   = Math.min(endMs, totalDurationMs);
                startMs = Math.max(0, Math.min(startMs, Math.max(0, endMs - 1000)));
                if (endMs - startMs < 1000) endMs = Math.min(totalDurationMs, startMs + 1000);
                if (waveformView != null) {
                    waveformView.setDurationMs(totalDurationMs);
                    waveformView.generateAmplitudes(soundId != null ? soundId : soundUrl);
                }
                setupSeekbarsRange();
                updateTimeLabels();
                syncPresetChip();
                audioReady = true;
                if (progressLoad   != null) progressLoad.setVisibility(View.GONE);
                if (layoutControls != null) layoutControls.setVisibility(View.VISIBLE);
                updateActionStates();
            }));
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                runOnUiThread(() -> {
                    if (mediaPlayer != mp) return;
                    boolean wasReady = audioReady;
                    onAudioLoadFailed(wasReady ? "Playback error" : "Cannot load audio");
                });
                return true;
            });
            mediaPlayer.setDataSource(soundUrl);
            mediaPlayer.prepareAsync();
        } catch (Exception e) {
            onAudioLoadFailed("Failed to load audio");
        }
    }

    /** Load/playback failure: stop everything and keep Done/Preview disabled (no bogus range returned). */
    private void onAudioLoadFailed(String message) {
        audioReady = false;
        stopPreview();
        if (progressLoad != null) progressLoad.setVisibility(View.GONE);
        updateActionStates();
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    /** Done + Preview are only usable once the track is ready (or when there is no track url at all). */
    private void updateActionStates() {
        boolean noTrack = soundUrl == null || soundUrl.isEmpty();
        boolean enabled = audioReady || noTrack;
        if (btnUse != null) {
            btnUse.setEnabled(enabled);
            btnUse.setClickable(enabled);
            btnUse.setAlpha(enabled ? 1f : 0.45f);
        }
        if (btnPreview != null) {
            btnPreview.setEnabled(audioReady);
            btnPreview.setAlpha(audioReady ? 1f : 0.45f);
        }
    }

    private void setupSeekbars() {
        if (sbStart == null || sbEnd == null) return;
        if (totalDurationMs > 0) setupSeekbarsRange();

        sbStart.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (syncingRange) return;
                startMs = p;
                if (startMs >= endMs - 1000) {
                    startMs = Math.max(0, endMs - 1000);
                    sb.setProgress(startMs);
                }
                updateTimeLabels();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { stopPreview(); }
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        sbEnd.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (syncingRange) return;
                endMs = p;
                if (endMs <= startMs + 1000) {
                    endMs = Math.min(totalDurationMs, startMs + 1000);
                    sb.setProgress(endMs);
                }
                updateTimeLabels();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { stopPreview(); }
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
    }

    private void setupSeekbarsRange() {
        if (sbStart == null || sbEnd == null || totalDurationMs <= 0) return;
        syncingRange = true;
        sbStart.setMax(totalDurationMs);
        sbStart.setProgress(startMs);
        sbEnd.setMax(totalDurationMs);
        sbEnd.setProgress(Math.min(endMs, totalDurationMs));
        syncingRange = false;
    }

    /** Wires the draggable waveform's handles to the same startMs/endMs state as the seekbars. */
    private void setupWaveformDrag() {
        if (waveformView == null) return;
        waveformView.setOnRangeChangeListener(new AudioTrimWaveformView.OnRangeChangeListener() {
            @Override public void onRangeChanging(int newStart, int newEnd, boolean isStartHandle) {
                if (isPreviewing) stopPreview();
                startMs = newStart;
                endMs   = newEnd;
                updateTimeLabels();
                syncPresetChip(); // manual range no longer matches a preset -> deselect chip
            }
            @Override public void onRangeChangeFinished(int newStart, int newEnd) {
                startMs = newStart;
                endMs   = newEnd;
                updateTimeLabels();
                syncPresetChip();
                // Auto-audition: as soon as the finger lifts, play from the new start.
                if (audioReady) startPreview();
            }
        });
    }

    private static final int PRESET_TOLERANCE_MS = 500;

    private void setupPresets() {
        if (rgPresets == null) return;
        // Click listeners (not OnCheckedChange): clicking the already-selected chip after a manual
        // drag must still re-apply the preset, and selecting a chip from code must never snap the
        // range (that used to overwrite a resumed custom range on re-open).
        rgPresets.setOnCheckedChangeListener(null);
        bindPresetChip(R.id.rb_preset_15, 15_000);
        bindPresetChip(R.id.rb_preset_30, 30_000);
        bindPresetChip(R.id.rb_preset_60, 60_000);
        syncPresetChip();
    }

    private void bindPresetChip(int id, int clipMs) {
        View chip = rgPresets.findViewById(id);
        if (chip == null) return;
        chip.setOnClickListener(v -> {
            stopPreview();
            rgPresets.check(id);
            snapTo(clipMs);
        });
    }

    /** Highlights the chip that matches the current range, or none for a custom range. */
    private void syncPresetChip() {
        if (rgPresets == null) return;
        int span = endMs - startMs;
        int[] ids  = { R.id.rb_preset_15, R.id.rb_preset_30, R.id.rb_preset_60 };
        int[] lens = { 15_000, 30_000, 60_000 };
        int match = -1;
        for (int i = 0; i < ids.length; i++) {
            int len = totalDurationMs > 0 ? Math.min(lens[i], totalDurationMs) : lens[i];
            if (Math.abs(span - len) <= PRESET_TOLERANCE_MS) { match = ids[i]; break; }
        }
        if (match == -1) rgPresets.clearCheck();
        else if (rgPresets.getCheckedRadioButtonId() != match) rgPresets.check(match);
    }

    private void snapTo(int clipMs) {
        if (totalDurationMs <= 0) return;
        int maxStart = Math.max(0, totalDurationMs - clipMs);
        startMs = Math.min(startMs, maxStart);
        endMs   = Math.min(startMs + clipMs, totalDurationMs);
        setupSeekbarsRange();
        updateTimeLabels();
    }

    private void setupButtons() {
        if (btnBack      != null) btnBack.setOnClickListener(v -> finish());
        if (btnPreview    != null) btnPreview.setOnClickListener(v -> togglePreview());
        if (btnUse        != null) btnUse.setOnClickListener(v -> useSelection());
        if (btnChange     != null) btnChange.setOnClickListener(v -> openChangeMusicPicker());
        if (layoutTrackInfo != null) layoutTrackInfo.setOnClickListener(v -> openSoundDetail());
    }

    /** "Change" tap — reopens the same trending audio screen to pick a different track. */
    private void openChangeMusicPicker() {
        stopPreview();
        startActivityForResult(
            new Intent(this, ReelTrendingAudioActivity.class), REQ_CHANGE_MUSIC);
    }

    /** Track-info row tap — opens the SoundDetail sheet for the currently loaded audio. */
    private void openSoundDetail() {
        if (soundUrl == null || soundUrl.isEmpty()) return;
        stopPreview();
        SoundDetailSheetFragment sheet = SoundDetailSheetFragment.newInstance(
            soundId, soundTitle, soundArtist, soundCover, soundUrl, totalDurationMs);
        sheet.show(getSupportFragmentManager(), "sound_detail_sheet");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CHANGE_MUSIC || resultCode != RESULT_OK || data == null) return;

        String newId     = data.getStringExtra(ReelTrendingAudioActivity.RESULT_AUDIO_ID);
        String newTitle  = data.getStringExtra(ReelTrendingAudioActivity.RESULT_AUDIO_TITLE);
        String newArtist = data.getStringExtra(ReelTrendingAudioActivity.RESULT_AUDIO_ARTIST);
        String newUrl    = data.getStringExtra(ReelTrendingAudioActivity.RESULT_AUDIO_URL);
        String newCover  = data.getStringExtra(ReelTrendingAudioActivity.RESULT_COVER_URL);
        if (newUrl == null || newUrl.isEmpty()) return;

        loadNewSound(newId, newTitle, newArtist, newUrl, newCover);
    }

    /** Swaps in a freshly-picked track (from "Change") and reloads playback + waveform state. */
    private void loadNewSound(String newId, String newTitle, String newArtist,
                               String newUrl, String newCover) {
        if (mediaPlayer != null) {
            try { mediaPlayer.stop(); } catch (Exception ignored) {}
            mediaPlayer.release();
            mediaPlayer = null;
        }
        soundId     = newId;
        soundTitle  = newTitle;
        soundArtist = newArtist;
        soundUrl    = newUrl;
        soundCover  = newCover;
        totalDurationMs = 0;
        startMs = 0;
        endMs   = 30_000;
        audioReady = false;
        isPreviewing = false;
        if (waveformView != null) waveformView.setPlayheadMs(-1);

        populateInfo();
        // Refresh the preview cover art too — matters when there are no
        // slideshow photos (video-reel flow), since showPhoto() falls back
        // to soundCover, which just changed.
        showPhoto(previewPhotoIndex);
        loadAudio();
        setupPresets();
        updateTimeLabels();
    }

    private void togglePreview() {
        if (isPreviewing) stopPreview();
        else startPreview();
    }

    private void startPreview() {
        if (mediaPlayer == null || soundUrl == null || !audioReady) {
            Toast.makeText(this, "Audio not ready", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!requestAudioFocus()) return;
        try {
            mediaPlayer.seekTo(startMs);
            mediaPlayer.start();
            isPreviewing = true;
            if (btnPreview != null) btnPreview.setImageResource(R.drawable.ic_pause);
            if (ivPlayHint != null) ivPlayHint.setVisibility(View.GONE);
            handler.postDelayed(previewStopCheck, 60);
            previewPhotoIndex = 0;
            showPhoto(previewPhotoIndex);
            if (photoUris.size() > 1) slideHandler.postDelayed(slideRunnable, slideMs);
            if (videoPreview != null && videoUri != null) {
                videoPreview.setVisibility(View.VISIBLE);
                videoPreview.seekTo(0);
                videoPreview.start();
            }
        } catch (Exception e) {
            abandonAudioFocus();
            Toast.makeText(this, "Playback failed", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean requestAudioFocus() {
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return true;
            if (focusRequest == null) {
                focusRequest = new AudioFocusRequestCompat.Builder(AudioManagerCompat.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributesCompat.Builder()
                        .setUsage(AudioAttributesCompat.USAGE_MEDIA)
                        .setContentType(AudioAttributesCompat.CONTENT_TYPE_MUSIC)
                        .build())
                    .setOnAudioFocusChangeListener(focusListener)
                    .build();
            }
            return AudioManagerCompat.requestAudioFocus(am, focusRequest)
                == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        } catch (Exception e) {
            return true; // never block preview because of a focus API hiccup
        }
    }

    private void abandonAudioFocus() {
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am != null && focusRequest != null) AudioManagerCompat.abandonAudioFocusRequest(am, focusRequest);
        } catch (Exception ignored) {}
    }

    /** Restarts the selected range from its start (audio + photo/video preview) without stopping. */
    private void loopPreview() {
        try {
            mediaPlayer.seekTo(startMs);
            if (!mediaPlayer.isPlaying()) mediaPlayer.start();
        } catch (Exception e) { stopPreview(); return; }
        if (waveformView != null) waveformView.setPlayheadMs(startMs);
        if (photoUris.size() > 1) {
            slideHandler.removeCallbacks(slideRunnable);
            previewPhotoIndex = 0;
            showPhoto(0);
            slideHandler.postDelayed(slideRunnable, slideMs);
        }
        if (videoPreview != null && videoUri != null) {
            try { videoPreview.seekTo(0); videoPreview.start(); } catch (Exception ignored) {}
        }
        // Give the (possibly streamed) seek a moment before checking the end again.
        handler.postDelayed(previewStopCheck, 250);
    }

    private void stopPreview() {
        if (mediaPlayer != null && isPreviewing) {
            try { mediaPlayer.pause(); } catch (Exception ignored) {}
        }
        isPreviewing = false;
        abandonAudioFocus();
        if (btnPreview != null) btnPreview.setImageResource(R.drawable.ic_play);
        if (ivPlayHint != null) ivPlayHint.setVisibility(View.VISIBLE);
        if (waveformView != null) waveformView.setPlayheadMs(-1);
        handler.removeCallbacks(previewStopCheck);
        slideHandler.removeCallbacks(slideRunnable);
        if (videoPreview != null && videoUri != null) {
            try { videoPreview.pause(); } catch (Exception ignored) {}
            videoPreview.setVisibility(View.GONE); // reveals the poster-frame ImageView beneath
        }
    }

    private void useSelection() {
        if (!audioReady && soundUrl != null && !soundUrl.isEmpty()) {
            Toast.makeText(this, "Audio still loading", Toast.LENGTH_SHORT).show();
            return;
        }
        stopPreview();
        if (totalDurationMs > 0) {
            endMs   = Math.min(endMs, totalDurationMs);
            startMs = Math.max(0, Math.min(startMs, endMs - 1000));
        }
        Intent result = new Intent();
        result.putExtra(RESULT_START_MS,     startMs);
        result.putExtra(RESULT_END_MS,       endMs);
        result.putExtra(RESULT_SOUND_ID,     soundId     != null ? soundId     : "");
        result.putExtra(RESULT_SOUND_TITLE,  soundTitle  != null ? soundTitle  : "");
        result.putExtra(RESULT_SOUND_ARTIST, soundArtist != null ? soundArtist : "");
        result.putExtra(RESULT_SOUND_URL,    soundUrl    != null ? soundUrl    : "");
        result.putExtra(RESULT_SOUND_COVER,  soundCover  != null ? soundCover  : "");
        setResult(RESULT_OK, result);
        finish();
    }

    private void updateTimeLabels() {
        if (tvStartTime     != null) tvStartTime.setText(msToTime(startMs));
        if (tvEndTime       != null) tvEndTime.setText(msToTime(endMs));
        if (tvSelectedRange != null)
            tvSelectedRange.setText("Selected: " + msToTime(startMs) + " – " + msToTime(endMs));
        if (tvStartValue    != null) tvStartValue.setText(msToTimeTenths(startMs));
        if (tvEndValue       != null) tvEndValue.setText(msToTimeTenths(endMs));

        // Keep the seekbars and the draggable waveform in lockstep with whichever
        // control the user just moved, without re-triggering each other's listeners.
        syncingRange = true;
        if (sbStart != null && sbStart.getProgress() != startMs) sbStart.setProgress(startMs);
        if (sbEnd   != null && sbEnd.getProgress()   != endMs)   sbEnd.setProgress(endMs);
        syncingRange = false;
        if (waveformView != null) waveformView.setRangeMs(startMs, endMs);
    }

    private static String msToTime(int ms) {
        int totalSec = ms / 1000;
        return String.format(Locale.US, "%d:%02d", totalSec / 60, totalSec % 60);
    }

    /** Same as msToTime but with a tenths-of-a-second digit, e.g. "0:01.2". */
    private static String msToTimeTenths(int ms) {
        int totalTenths = ms / 100;
        int sec = totalTenths / 10;
        int tenth = totalTenths % 10;
        return String.format(Locale.US, "%d:%02d.%d", sec / 60, sec % 60, tenth);
    }

    @Override
    protected void onPause() {
        // Don't keep playing the preview when the app goes to background / a call comes in.
        stopPreview();
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        // Back without an explicit "Use" tap = cancel (caller keeps prior state).
        setResult(RESULT_CANCELED);
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        slideHandler.removeCallbacksAndMessages(null);
        if (mediaPlayer != null) {
            try { mediaPlayer.stop(); } catch (Exception ignored) {}
            mediaPlayer.release();
            mediaPlayer = null;
        }
        if (videoPreview != null) {
            try { videoPreview.stopPlayback(); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }
}
