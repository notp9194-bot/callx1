package com.callx.app.activities;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.*;
import android.media.audiofx.BassBoost;
import android.media.audiofx.EnvironmentalReverb;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;
import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.UUID;

/**
 * ReelSoundRecorderActivity — Record original audio / voiceover for reels.
 *
 * Features (production-level):
 *  ✅ Tap-to-record / tap-to-stop with REAL amplitude waveform (MediaRecorder.getMaxAmplitude)
 *  ✅ Pause / Resume recording (accumulates segments into one output file)
 *  ✅ Real-time duration counter (max 60 s, auto-stops)
 *  ✅ Playback after recording with seekbar scrubbing
 *  ✅ Retake (discard + re-record)
 *  ✅ 9 voice-effect presets applied at preview via AudioEffect + PlaybackParams:
 *       Normal | Echo | Reverb | Pitch Up | Pitch Down | Telephone | Helium | Underwater | Slow-Mo
 *  ✅ Save → returns local file path to caller (ReelEditorActivity / ReelAudioMixerActivity)
 *  ✅ "Publish as Sound" → uploads to Firebase Storage + writes musicLibrary entry
 *       so other users can discover and use the original sound in their reels
 *  ✅ Waveform bars grow/shrink based on mic amplitude in real time
 *  ✅ Named sound title entry for publish flow
 */
public class ReelSoundRecorderActivity extends AppCompatActivity {

    public static final String RESULT_AUDIO_PATH  = "audio_path";
    public static final String RESULT_AUDIO_TITLE = "audio_title";

    private static final int    MAX_DURATION_MS = 60_000;
    private static final int    RC_AUDIO        = 101;
    private static final int    WAVEFORM_BARS   = 28;

    // ── Views ─────────────────────────────────────────────────────────────
    private ImageButton  btnBack, btnRecord, btnPlay, btnRetake;
    private TextView     tvTimer, tvStatus, btnUse, btnPublish;
    private SeekBar      sbPlayback;
    private LinearLayout layoutWaveform, layoutEffects;
    private RadioGroup   rgEffects;
    private ProgressBar  progressBar;
    private EditText     etSoundTitle;
    private View         layoutPostRecord;

    // ── State ─────────────────────────────────────────────────────────────
    private MediaRecorder recorder;
    private MediaPlayer   player;
    private String        outputPath;
    private boolean       isRecording  = false;
    private boolean       isPaused     = false;   // pause/resume
    private boolean       isPlaying    = false;
    private boolean       hasRecording = false;
    private long          recordStart  = 0;
    private long          accumulatedMs= 0;        // accumulated from previous segments

    private EnvironmentalReverb reverbEffect;
    private BassBoost           bassEffect;

    // Effect ids (matching rgEffects radio buttons)
    private static final int E_NORMAL     = 0;
    private static final int E_ECHO       = 1;
    private static final int E_REVERB     = 2;
    private static final int E_PITCH_UP   = 3;
    private static final int E_PITCH_DOWN = 4;
    private static final int E_TELEPHONE  = 5;
    private static final int E_HELIUM     = 6;
    private static final int E_UNDERWATER = 7;
    private static final int E_SLOW_MO    = 8;
    private int selectedEffect = E_NORMAL;

    private final Handler handler     = new Handler(Looper.getMainLooper());
    private final Handler waveHandler = new Handler(Looper.getMainLooper());

    private final Runnable timerRunnable = new Runnable() {
        @Override public void run() {
            if (!isRecording || isPaused) return;
            long elapsed = accumulatedMs + (System.currentTimeMillis() - recordStart);
            tvTimer.setText(msToTime(elapsed));
            updateWaveformAmplitude();
            if (elapsed >= MAX_DURATION_MS) { stopRecording(); return; }
            handler.postDelayed(this, 80);
        }
    };

    private final Runnable playbackRunnable = new Runnable() {
        @Override public void run() {
            if (isPlaying && player != null) {
                try { sbPlayback.setProgress(player.getCurrentPosition()); } catch (Exception ignored) {}
                handler.postDelayed(this, 120);
            }
        }
    };

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_reel_sound_recorder);
        bindViews();
        buildStaticWaveform();
        checkPermission();
    }

    private void bindViews() {
        btnBack         = findViewById(R.id.btn_recorder_back);
        btnRecord       = findViewById(R.id.btn_recorder_record);
        btnPlay         = findViewById(R.id.btn_recorder_play);
        btnRetake       = findViewById(R.id.btn_recorder_retake);
        tvTimer         = findViewById(R.id.tv_recorder_timer);
        tvStatus        = findViewById(R.id.tv_recorder_status);
        btnUse          = findViewById(R.id.btn_recorder_use);
        btnPublish      = findViewById(R.id.btn_recorder_publish);
        sbPlayback      = findViewById(R.id.sb_recorder_playback);
        layoutWaveform  = findViewById(R.id.layout_recorder_waveform);
        layoutEffects   = findViewById(R.id.layout_recorder_effects);
        rgEffects       = findViewById(R.id.rg_recorder_effects);
        progressBar     = findViewById(R.id.progress_recorder);
        etSoundTitle    = findViewById(R.id.et_sound_title);
        layoutPostRecord= findViewById(R.id.layout_post_record);

        btnBack.setOnClickListener(v -> finish());
        btnRecord.setOnClickListener(v -> handleRecordButton());
        if (btnPlay   != null) btnPlay.setOnClickListener(v -> { if (isPlaying) stopPlayback(); else startPlayback(); });
        if (btnRetake != null) btnRetake.setOnClickListener(v -> retake());
        if (btnUse    != null) btnUse.setOnClickListener(v -> useRecording());
        if (btnPublish!= null) btnPublish.setOnClickListener(v -> publishAsSound());

        if (rgEffects != null) {
            rgEffects.setOnCheckedChangeListener((rg, id) -> {
                if      (id == R.id.rb_fx_normal)     selectedEffect = E_NORMAL;
                else if (id == R.id.rb_fx_echo)       selectedEffect = E_ECHO;
                else if (id == R.id.rb_fx_reverb)     selectedEffect = E_REVERB;
                else if (id == R.id.rb_fx_pitch_up)   selectedEffect = E_PITCH_UP;
                else if (id == R.id.rb_fx_pitch_down) selectedEffect = E_PITCH_DOWN;
                else if (id == R.id.rb_fx_telephone)  selectedEffect = E_TELEPHONE;
                else if (id == R.id.rb_fx_helium)     selectedEffect = E_HELIUM;
                else if (id == R.id.rb_fx_underwater) selectedEffect = E_UNDERWATER;
                else if (id == R.id.rb_fx_slow_mo)    selectedEffect = E_SLOW_MO;
                if (isPlaying) { stopPlayback(); startPlayback(); }
            });
        }
        setPostRecordVisible(false);
    }

    // ── Waveform ─────────────────────────────────────────────────────────

    private void buildStaticWaveform() {
        if (layoutWaveform == null) return;
        layoutWaveform.removeAllViews();
        float dp = getResources().getDisplayMetrics().density;
        for (int i = 0; i < WAVEFORM_BARS; i++) {
            View bar = new View(this);
            int h = (int)((8 + (int)(Math.random() * 20)) * dp);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int)(5 * dp), h);
            lp.setMargins((int)(2 * dp), 0, (int)(2 * dp), 0);
            lp.gravity = Gravity.BOTTOM;
            bar.setLayoutParams(lp);
            bar.setBackgroundColor(0x44FFFFFF);
            bar.setTag("wBar");
            layoutWaveform.addView(bar);
        }
    }

    /** Update waveform bars using actual mic amplitude. */
    private void updateWaveformAmplitude() {
        if (layoutWaveform == null || recorder == null) return;
        int amp = 0;
        try { amp = recorder.getMaxAmplitude(); } catch (Exception ignored) {}
        float dp = getResources().getDisplayMetrics().density;
        int count = layoutWaveform.getChildCount();
        for (int i = 0; i < count; i++) {
            View bar = layoutWaveform.getChildAt(i);
            // shift: each bar takes the value of the next bar (scroll effect)
            float ratio = (i == count - 1)
                    ? Math.min(1f, amp / 28000f)
                    : (layoutWaveform.getChildAt(i + 1).getLayoutParams().height
                       / (float)(dp * 52));
            int newH = Math.max((int)(6 * dp), (int)((6 + ratio * 46) * dp));
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) bar.getLayoutParams();
            lp.height = newH;
            bar.setLayoutParams(lp);
            bar.setBackgroundColor(isRecording ? 0xFFFF3B5C : 0x44FFFFFF);
        }
    }

    // ── Recording ─────────────────────────────────────────────────────────

    private void handleRecordButton() {
        if (!isRecording) {
            startRecording();
        } else if (!isPaused) {
            pauseRecording();
        } else {
            resumeRecording();
        }
    }

    private void startRecording() {
        outputPath = new File(getExternalFilesDir(null),
                "reel_sound_" + System.currentTimeMillis() + ".m4a").getAbsolutePath();
        recorder = new MediaRecorder();
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        recorder.setAudioSamplingRate(44100);
        recorder.setAudioEncodingBitRate(128_000);
        recorder.setOutputFile(outputPath);
        try {
            recorder.prepare();
            recorder.start();
            isRecording = true; isPaused = false; hasRecording = false;
            recordStart = System.currentTimeMillis();
            btnRecord.setImageResource(R.drawable.ic_pause);
            tvStatus.setText("Recording…");
            setPostRecordVisible(false);
            handler.post(timerRunnable);
        } catch (IOException e) {
            Toast.makeText(this, "Failed to start recording", Toast.LENGTH_SHORT).show();
        }
    }

    private void pauseRecording() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                recorder.pause();
                isPaused = true;
                accumulatedMs += System.currentTimeMillis() - recordStart;
                btnRecord.setImageResource(R.drawable.ic_play);
                tvStatus.setText("Paused — tap to resume");
                handler.removeCallbacks(timerRunnable);
            } catch (Exception e) {
                // Pause not supported — just stop
                stopRecording();
            }
        } else {
            stopRecording();
        }
    }

    private void resumeRecording() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                recorder.resume();
                isPaused = false;
                recordStart = System.currentTimeMillis();
                btnRecord.setImageResource(R.drawable.ic_pause);
                tvStatus.setText("Recording…");
                handler.post(timerRunnable);
            } catch (Exception e) {
                Toast.makeText(this, "Resume failed", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void stopRecording() {
        handler.removeCallbacks(timerRunnable);
        if (recorder != null) {
            if (!isPaused) accumulatedMs += System.currentTimeMillis() - recordStart;
            try { recorder.stop(); } catch (Exception ignored) {}
            recorder.release(); recorder = null;
        }
        isRecording = false; isPaused = false; hasRecording = true;
        btnRecord.setImageResource(R.drawable.ic_reel_camera);
        tvStatus.setText("Done — " + tvTimer.getText());
        sbPlayback.setMax((int) accumulatedMs);
        sbPlayback.setProgress(0);
        buildStaticWaveform();
        setPostRecordVisible(true);
    }

    // ── Playback ──────────────────────────────────────────────────────────

    private void startPlayback() {
        if (!hasRecording || outputPath == null) return;
        releaseEffects();
        try {
            player = new MediaPlayer();
            player.setDataSource(outputPath);
            player.prepare();
            applyEffectsToPlayer();
            player.start();
            isPlaying = true;
            if (btnPlay != null) btnPlay.setImageResource(R.drawable.ic_pause);
            sbPlayback.setMax(player.getDuration());
            handler.post(playbackRunnable);
            player.setOnCompletionListener(mp -> stopPlayback());
        } catch (Exception e) {
            Toast.makeText(this, "Playback failed", Toast.LENGTH_SHORT).show();
        }
    }

    private void applyEffectsToPlayer() {
        if (player == null) return;
        int session = player.getAudioSessionId();

        // Reverb / echo effects via EnvironmentalReverb
        boolean wantReverb = selectedEffect == E_REVERB || selectedEffect == E_ECHO
                || selectedEffect == E_UNDERWATER || selectedEffect == E_SLOW_MO;
        if (wantReverb) {
            try {
                reverbEffect = new EnvironmentalReverb(0, session);
                switch (selectedEffect) {
                    case E_ECHO:
                        reverbEffect.setReflectionsDelay(150);
                        reverbEffect.setReflectionsLevel((short) 1000);
                        reverbEffect.setReverbDelay(80);
                        reverbEffect.setReverbLevel((short) 800);
                        reverbEffect.setDecayTime(600);
                        break;
                    case E_REVERB:
                        reverbEffect.setReverbLevel((short) 2000);
                        reverbEffect.setDecayTime(3000);
                        reverbEffect.setDensity((short) 1000);
                        reverbEffect.setDiffusion((short) 1000);
                        break;
                    case E_UNDERWATER:
                        reverbEffect.setReverbLevel((short) 2500);
                        reverbEffect.setDecayTime(5000);
                        reverbEffect.setDecayHFRatio((short) 100);
                        reverbEffect.setDensity((short) 800);
                        break;
                    case E_SLOW_MO:
                        reverbEffect.setReverbLevel((short) 500);
                        reverbEffect.setDecayTime(1500);
                        break;
                }
                reverbEffect.setEnabled(true);
                player.attachAuxEffect(reverbEffect.getId());
                player.setAuxEffectSendLevel(1.0f);
            } catch (Exception ignored) {}
        }

        // Bass boost for telephone effect
        if (selectedEffect == E_TELEPHONE) {
            try {
                bassEffect = new BassBoost(0, session);
                bassEffect.setStrength((short) 1000);
                bassEffect.setEnabled(true);
            } catch (Exception ignored) {}
        }

        // Pitch / speed shift
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                PlaybackParams pp = new PlaybackParams();
                pp.allowDefaults();
                switch (selectedEffect) {
                    case E_PITCH_UP:   pp.setPitch(1.40f); break;
                    case E_PITCH_DOWN: pp.setPitch(0.70f); break;
                    case E_HELIUM:     pp.setPitch(1.80f); pp.setSpeed(1.2f); break;
                    case E_SLOW_MO:    pp.setSpeed(0.60f); break;
                    case E_TELEPHONE:  pp.setPitch(1.05f); break;
                    default: pp.setPitch(1.0f); break;
                }
                player.setPlaybackParams(pp);
            } catch (Exception ignored) {}
        }
    }

    private void stopPlayback() {
        if (player != null) {
            try { player.stop(); player.release(); } catch (Exception ignored) {}
            player = null;
        }
        releaseEffects();
        isPlaying = false;
        if (btnPlay != null) btnPlay.setImageResource(R.drawable.ic_play);
        handler.removeCallbacks(playbackRunnable);
    }

    // ── Actions ───────────────────────────────────────────────────────────

    private void retake() {
        stopPlayback(); stopRecording();
        hasRecording = false; accumulatedMs = 0;
        if (outputPath != null) { new File(outputPath).delete(); outputPath = null; }
        tvTimer.setText("00:00");
        tvStatus.setText("Tap the mic to start recording");
        setPostRecordVisible(false);
        buildStaticWaveform();
    }

    private void useRecording() {
        if (!hasRecording || outputPath == null) {
            Toast.makeText(this, "Nothing recorded yet", Toast.LENGTH_SHORT).show(); return;
        }
        stopPlayback();
        String title = etSoundTitle != null && etSoundTitle.getText() != null
                ? etSoundTitle.getText().toString().trim() : "";
        if (title.isEmpty()) title = "Original Sound";
        Intent result = new Intent();
        result.putExtra(RESULT_AUDIO_PATH,  outputPath);
        result.putExtra(RESULT_AUDIO_TITLE, title);
        setResult(RESULT_OK, result);
        finish();
    }

    /**
     * Upload recorded sound to Firebase Storage + write to musicLibrary so
     * other users can discover it in MusicPickerActivity.
     */
    private void publishAsSound() {
        if (!hasRecording || outputPath == null) {
            Toast.makeText(this, "Nothing recorded yet", Toast.LENGTH_SHORT).show(); return;
        }
        stopPlayback();
        String title = etSoundTitle != null && etSoundTitle.getText() != null
                ? etSoundTitle.getText().toString().trim() : "";
        if (title.isEmpty()) { Toast.makeText(this, "Enter a name for your sound", Toast.LENGTH_SHORT).show(); return; }

        if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
        if (btnPublish  != null) btnPublish.setEnabled(false);

        String uid = null;
        String displayName = "Unknown";
        try { uid = FirebaseUtils.getCurrentUid(); } catch (Exception ignored) {}
        final String finalUid  = uid != null ? uid : "anon";
        final String finalTitle = title;

        String trackId = UUID.randomUUID().toString();
        File   file    = new File(outputPath);

        StorageReference storageRef = FirebaseStorage.getInstance()
                .getReference("sounds/" + trackId + ".m4a");

        storageRef.putFile(Uri.fromFile(file))
                .addOnProgressListener(snap -> {
                    double pct = (100.0 * snap.getBytesTransferred()) / snap.getTotalByteCount();
                    if (tvStatus != null) tvStatus.setText("Uploading… " + (int) pct + "%");
                })
                .addOnSuccessListener(snap -> snap.getStorage().getDownloadUrl()
                        .addOnSuccessListener(uri -> {
                            // Write track metadata to musicLibrary
                            DatabaseReference libRef = FirebaseUtils.getMusicLibraryRef().child(trackId);
                            com.google.firebase.database.DataSnapshot ignored2 = null;
                            java.util.Map<String, Object> meta = new java.util.HashMap<>();
                            meta.put("trackId",       trackId);
                            meta.put("title",         finalTitle);
                            meta.put("name",          finalTitle);
                            meta.put("artist",        displayName);
                            meta.put("audioUrl",      uri.toString());
                            meta.put("coverUrl",      "");
                            meta.put("durationMs",    accumulatedMs);
                            meta.put("usageCount",    0L);
                            meta.put("trendingRank",  0L);
                            meta.put("totalSaves",    0L);
                            meta.put("isOriginalSound", true);
                            meta.put("isVerified",    false);
                            meta.put("uploadedByUid", finalUid);
                            meta.put("addedAt",       System.currentTimeMillis());
                            meta.put("genre",         "Original");
                            libRef.setValue(meta)
                                    .addOnSuccessListener(u -> {
                                        if (progressBar != null) progressBar.setVisibility(View.GONE);
                                        Toast.makeText(this, "\"" + finalTitle + "\" published!", Toast.LENGTH_LONG).show();
                                        // Also use it in the reel
                                        Intent result = new Intent();
                                        result.putExtra(RESULT_AUDIO_PATH,  outputPath);
                                        result.putExtra(RESULT_AUDIO_TITLE, finalTitle);
                                        result.putExtra("published_sound_id",  trackId);
                                        result.putExtra("published_sound_url", uri.toString());
                                        setResult(RESULT_OK, result);
                                        finish();
                                    })
                                    .addOnFailureListener(e -> {
                                        if (progressBar != null) progressBar.setVisibility(View.GONE);
                                        if (btnPublish != null) btnPublish.setEnabled(true);
                                        Toast.makeText(this, "Metadata save failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                                    });
                        }))
                .addOnFailureListener(e -> {
                    if (progressBar != null) progressBar.setVisibility(View.GONE);
                    if (btnPublish != null) btnPublish.setEnabled(true);
                    Toast.makeText(this, "Upload failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
    }

    // ── Misc ──────────────────────────────────────────────────────────────

    private void releaseEffects() {
        if (reverbEffect != null) { try { reverbEffect.release(); } catch (Exception ignored) {} reverbEffect = null; }
        if (bassEffect   != null) { try { bassEffect.release();   } catch (Exception ignored) {} bassEffect   = null; }
    }

    private void setPostRecordVisible(boolean v) {
        int vis = v ? View.VISIBLE : View.GONE;
        if (layoutPostRecord != null) layoutPostRecord.setVisibility(vis);
        else {
            if (btnPlay    != null) btnPlay.setVisibility(vis);
            if (btnRetake  != null) btnRetake.setVisibility(vis);
            if (btnUse     != null) btnUse.setVisibility(vis);
            if (btnPublish != null) btnPublish.setVisibility(vis);
            if (sbPlayback != null) sbPlayback.setVisibility(vis);
            if (layoutEffects != null) layoutEffects.setVisibility(vis);
        }
    }

    private void checkPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, RC_AUDIO);
        }
    }

    @Override public void onRequestPermissionsResult(int rc, @NonNull String[] perms, @NonNull int[] results) {
        super.onRequestPermissionsResult(rc, perms, results);
        if (rc == RC_AUDIO && (results.length == 0 || results[0] != PackageManager.PERMISSION_GRANTED)) {
            Toast.makeText(this, "Microphone permission required", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        waveHandler.removeCallbacksAndMessages(null);
        releaseEffects();
        stopPlayback();
        if (isRecording) { try { recorder.stop(); } catch (Exception ignored) {} if (recorder != null) { recorder.release(); recorder = null; } }
        super.onDestroy();
    }

    private static String msToTime(long ms) {
        long s = ms / 1000, m = s / 60; s = s % 60;
        return String.format(Locale.US, "%02d:%02d", m, s);
    }
}
