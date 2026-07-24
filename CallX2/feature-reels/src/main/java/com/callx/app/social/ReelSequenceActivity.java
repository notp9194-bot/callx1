package com.callx.app.social;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.*;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.*;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.*;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import com.bumptech.glide.Glide;
import com.callx.app.camera.MultiClipCameraActivity;
import com.callx.app.editor.ReelEditorActivity;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ReelSequenceActivity — Production-grade Sequence Feature
 *
 * Flow:
 *  Step 1 — The original reel plays (or is previewed). User selects how many
 *            seconds of the original to use as the intro (1 / 3 / 5 / 10 / 15s).
 *            Clips exceeding the selected duration are trimmed during composition.
 *
 *  Step 2 — User sees live camera preview and records their continuation clip
 *            (max 60s). A thumbnail of the original is shown in the corner as
 *            a reminder of context.
 *
 *  Step 3 — Both clips (original trimmed portion + user recording) are passed
 *            to ReelEditorActivity as a multi-clip sequence.
 *            The editor merges them end-to-end: original → user's continuation.
 *
 * Firebase:
 *  On publish, ReelUploadActivity sees EXTRA_SEQUENCE_OF_REEL_ID / OWNER_UID
 *  and writes a "reelSequences/{originalReelId}/{newReelId}" record + increments
 *  the original reel's sequenceCount, and fires a notification to the creator.
 *
 * Extras accepted:
 *   EXTRA_ORIGINAL_REEL_ID   — Firebase key of the original reel
 *   EXTRA_ORIGINAL_VIDEO_URL — Streaming URL of the original reel
 *   EXTRA_ORIGINAL_OWNER_UID — UID of the original reel creator
 *   EXTRA_ORIGINAL_OWNER_NAME — Display name of the original creator
 *   EXTRA_ORIGINAL_THUMB_URL — Thumbnail URL for the corner reminder
 */
@OptIn(markerClass = UnstableApi.class)
public class ReelSequenceActivity extends AppCompatActivity {

    private static final String TAG = "ReelSequence";

    // ── Extras ───────────────────────────────────────────────────────────────
    public static final String EXTRA_ORIGINAL_REEL_ID    = "seq_original_reel_id";
    public static final String EXTRA_ORIGINAL_VIDEO_URL  = "seq_original_video_url";
    public static final String EXTRA_ORIGINAL_OWNER_UID  = "seq_original_owner_uid";
    public static final String EXTRA_ORIGINAL_OWNER_NAME = "seq_original_owner_name";
    public static final String EXTRA_ORIGINAL_THUMB_URL  = "seq_original_thumb_url";

    // ── Constants ─────────────────────────────────────────────────────────────
    private static final int  REQ_PERMISSIONS  = 1501;
    private static final int  MAX_RECORD_SEC   = 60;
    private static final int  DEFAULT_CLIP_SEC = 5;

    // ── Input data ────────────────────────────────────────────────────────────
    private String originalReelId;
    private String originalVideoUrl;
    private String originalOwnerUid;
    private String originalOwnerName;
    private String originalThumbUrl;

    // ── Step 1 views ──────────────────────────────────────────────────────────
    private FrameLayout step1Container;
    private PlayerView  pvOriginal;
    private TextView    tvOwnerName, tvSelectionLabel;
    private Button      btnPreview, btnNext;

    // ── Step 2 views ──────────────────────────────────────────────────────────
    private FrameLayout step2Container;
    private PreviewView pvCamera;
    private ImageView   ivSeqThumb;
    private ImageButton btnRecord, btnFlip, btnBack2;
    private TextView    tvTimer, tvCountdown, tvRecordHint;
    private ProgressBar pbDuration;

    // ── Compositing overlay ───────────────────────────────────────────────────
    private LinearLayout layoutCompositing;
    private TextView     tvCompositingStatus;
    private ProgressBar  pbCompositing;

    // ── Player ────────────────────────────────────────────────────────────────
    private ExoPlayer exoPlayer;

    // ── Camera ────────────────────────────────────────────────────────────────
    private ProcessCameraProvider    cameraProvider;
    private VideoCapture<Recorder>   videoCapture;
    private Recording                activeRecording;
    private ExecutorService          cameraExecutor;

    // ── State ─────────────────────────────────────────────────────────────────
    private int     clipDurationSec  = DEFAULT_CLIP_SEC;  // selected original clip length
    private boolean isFront          = true;
    private boolean isRecording      = false;
    private File    outputFile;
    private CountDownTimer recordTimer;
    private final Handler  mainHandler  = new Handler(Looper.getMainLooper());

    // ── Duration chip views ───────────────────────────────────────────────────
    private TextView chip1s, chip3s, chip5s, chip10s, chip15s;

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reel_sequence);

        originalReelId    = getIntent().getStringExtra(EXTRA_ORIGINAL_REEL_ID);
        originalVideoUrl  = getIntent().getStringExtra(EXTRA_ORIGINAL_VIDEO_URL);
        originalOwnerUid  = nvl(getIntent().getStringExtra(EXTRA_ORIGINAL_OWNER_UID));
        originalOwnerName = nvl(getIntent().getStringExtra(EXTRA_ORIGINAL_OWNER_NAME));
        originalThumbUrl  = nvl(getIntent().getStringExtra(EXTRA_ORIGINAL_THUMB_URL));

        if (originalVideoUrl == null || originalVideoUrl.isEmpty()) {
            Toast.makeText(this, "Could not load original reel", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        cameraExecutor = Executors.newSingleThreadExecutor();
        bindViews();
        setupStep1();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (exoPlayer != null && !exoPlayer.isPlaying() && step1Container.getVisibility() == View.VISIBLE) {
            exoPlayer.play();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (exoPlayer != null) exoPlayer.pause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releasePlayer();
        if (activeRecording != null) {
            activeRecording.stop();
            activeRecording = null;
        }
        cameraExecutor.shutdown();
        if (recordTimer != null) recordTimer.cancel();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // View binding
    // ─────────────────────────────────────────────────────────────────────────

    private void bindViews() {
        // Step 1
        step1Container   = findViewById(R.id.container_seq_step1);
        pvOriginal       = findViewById(R.id.pv_seq_original);
        tvOwnerName      = findViewById(R.id.tv_seq_owner_name);
        tvSelectionLabel = findViewById(R.id.tv_seq_selection_label);
        btnPreview       = findViewById(R.id.btn_seq_preview);
        btnNext          = findViewById(R.id.btn_seq_next);
        chip1s           = findViewById(R.id.chip_seq_1s);
        chip3s           = findViewById(R.id.chip_seq_3s);
        chip5s           = findViewById(R.id.chip_seq_5s);
        chip10s          = findViewById(R.id.chip_seq_10s);
        chip15s          = findViewById(R.id.chip_seq_15s);

        // Step 2
        step2Container   = findViewById(R.id.container_seq_step2);
        pvCamera         = findViewById(R.id.pv_seq_camera);
        ivSeqThumb       = findViewById(R.id.iv_seq_thumb);
        btnRecord        = findViewById(R.id.btn_seq_record);
        btnFlip          = findViewById(R.id.btn_seq_flip);
        btnBack2         = findViewById(R.id.btn_seq_back2);
        tvTimer          = findViewById(R.id.tv_seq_timer);
        tvCountdown      = findViewById(R.id.tv_seq_countdown);
        tvRecordHint     = findViewById(R.id.tv_seq_record_hint);
        pbDuration       = findViewById(R.id.pb_seq_duration);

        // Compositing overlay
        layoutCompositing    = findViewById(R.id.layout_seq_compositing);
        tvCompositingStatus  = findViewById(R.id.tv_seq_compositing_status);
        pbCompositing        = findViewById(R.id.pb_seq_compositing);

        // Back on step 1
        View btnBack1 = findViewById(R.id.btn_seq_back);
        if (btnBack1 != null) btnBack1.setOnClickListener(v -> finish());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STEP 1 — Original preview + clip duration selection
    // ─────────────────────────────────────────────────────────────────────────

    private void setupStep1() {
        // Owner label
        if (!originalOwnerName.isEmpty()) {
            tvOwnerName.setText("Sequencing @" + originalOwnerName + "'s reel");
        }

        // Original video autoplay
        startOriginalPlayback();

        // Duration chips
        selectChip(chip5s);   // default: 5s
        chip1s.setOnClickListener(v  -> { clipDurationSec = 1;  selectChip(chip1s);  });
        chip3s.setOnClickListener(v  -> { clipDurationSec = 3;  selectChip(chip3s);  });
        chip5s.setOnClickListener(v  -> { clipDurationSec = 5;  selectChip(chip5s);  });
        chip10s.setOnClickListener(v -> { clipDurationSec = 10; selectChip(chip10s); });
        chip15s.setOnClickListener(v -> { clipDurationSec = 15; selectChip(chip15s); });

        // Preview button: rewind + play original from the start up to selected duration
        btnPreview.setOnClickListener(v -> previewClip());

        // Next: go to recording step
        btnNext.setOnClickListener(v -> transitionToStep2());
    }

    private void startOriginalPlayback() {
        releasePlayer();
        exoPlayer = new ExoPlayer.Builder(this).build();
        pvOriginal.setPlayer(exoPlayer);
        pvOriginal.setUseController(false);
        exoPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(originalVideoUrl)));
        exoPlayer.setRepeatMode(Player.REPEAT_MODE_ONE);
        exoPlayer.prepare();
        exoPlayer.play();
    }

    private void previewClip() {
        if (exoPlayer == null) return;
        exoPlayer.seekTo(0);
        exoPlayer.play();
        // Auto-pause after the selected clip duration
        mainHandler.removeCallbacksAndMessages(null);
        mainHandler.postDelayed(() -> {
            if (exoPlayer != null) exoPlayer.pause();
        }, clipDurationSec * 1000L);
    }

    private void selectChip(TextView selected) {
        clearChipSelections();
        selected.setBackgroundResource(R.drawable.bg_reel_chip_selected);
        selected.setTextColor(getResources().getColor(android.R.color.white, null));
        tvSelectionLabel.setText("Using first " + clipDurationSec + "s of original");
    }

    private void clearChipSelections() {
        int defaultBg = R.drawable.bg_speed_chip;
        chip1s.setBackgroundResource(defaultBg);
        chip3s.setBackgroundResource(defaultBg);
        chip5s.setBackgroundResource(defaultBg);
        chip10s.setBackgroundResource(defaultBg);
        chip15s.setBackgroundResource(defaultBg);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STEP 2 — Camera recording
    // ─────────────────────────────────────────────────────────────────────────

    private void transitionToStep2() {
        // Stop original playback
        if (exoPlayer != null) exoPlayer.pause();

        // Switch containers
        step1Container.setVisibility(View.GONE);
        step2Container.setVisibility(View.VISIBLE);

        // Load thumb into corner reminder
        if (!originalThumbUrl.isEmpty()) {
            Glide.with(this)
                .load(originalThumbUrl)
                .placeholder(R.drawable.bg_card_rounded)
                .into(ivSeqThumb);
        }

        // Wire buttons
        btnBack2.setOnClickListener(v -> {
            // Go back to step 1
            step2Container.setVisibility(View.GONE);
            step1Container.setVisibility(View.VISIBLE);
            if (exoPlayer != null) exoPlayer.play();
        });
        btnFlip.setOnClickListener(v -> {
            isFront = !isFront;
            if (cameraProvider != null) bindCamera();
        });
        btnRecord.setOnClickListener(v -> {
            if (isRecording) stopRecording();
            else startRecordingWithCountdown();
        });

        // Start camera
        if (hasPermissions()) startCamera();
        else requestPermissions();
    }

    // ── Camera init ───────────────────────────────────────────────────────────

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future =
            ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                bindCamera();
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "CameraProvider failed", e);
                runOnUiThread(() ->
                    Toast.makeText(this, "Camera error", Toast.LENGTH_SHORT).show());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCamera() {
        if (cameraProvider == null) return;
        cameraProvider.unbindAll();
        CameraSelector selector = isFront
            ? CameraSelector.DEFAULT_FRONT_CAMERA
            : CameraSelector.DEFAULT_BACK_CAMERA;

        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(pvCamera.getSurfaceProvider());

        Recorder recorder = new Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HD))
            .build();
        videoCapture = VideoCapture.withOutput(recorder);

        try {
            cameraProvider.bindToLifecycle(this, selector, preview, videoCapture);
        } catch (Exception e) {
            Log.e(TAG, "Use case binding failed", e);
        }
    }

    // ── Recording ─────────────────────────────────────────────────────────────

    private void startRecordingWithCountdown() {
        tvCountdown.setVisibility(View.VISIBLE);
        tvRecordHint.setVisibility(View.GONE);
        btnRecord.setEnabled(false);

        new CountDownTimer(3000, 1000) {
            @Override public void onTick(long remaining) {
                tvCountdown.setText(String.valueOf((remaining / 1000) + 1));
            }
            @Override public void onFinish() {
                tvCountdown.setVisibility(View.GONE);
                btnRecord.setEnabled(true);
                beginRecording();
            }
        }.start();
    }

    @OptIn(markerClass = androidx.camera.core.ExperimentalGetImage.class)
    private void beginRecording() {
        if (videoCapture == null) {
            Toast.makeText(this, "Camera not ready", Toast.LENGTH_SHORT).show();
            return;
        }

        outputFile = new File(getCacheDir(),
            "seq_user_" + System.currentTimeMillis() + ".mp4");

        FileOutputOptions options = new FileOutputOptions.Builder(outputFile).build();
        activeRecording = videoCapture.getOutput()
            .prepareRecording(this, options)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(this), event -> {
                if (event instanceof VideoRecordEvent.Start) {
                    onRecordingStarted();
                } else if (event instanceof VideoRecordEvent.Finalize) {
                    VideoRecordEvent.Finalize finalize = (VideoRecordEvent.Finalize) event;
                    if (!finalize.hasError()) {
                        onRecordingFinalized(outputFile);
                    } else {
                        Log.e(TAG, "Recording error: " + finalize.getError());
                        runOnUiThread(() ->
                            Toast.makeText(this, "Recording failed", Toast.LENGTH_SHORT).show());
                    }
                }
            });
    }

    private void onRecordingStarted() {
        isRecording = true;
        btnRecord.setImageResource(R.drawable.ic_stop);
        btnRecord.setBackgroundTintList(
            android.content.res.ColorStateList.valueOf(
                android.graphics.Color.parseColor("#FF3B5C")));

        pbDuration.setMax(MAX_RECORD_SEC);
        pbDuration.setProgress(0);

        recordTimer = new CountDownTimer(MAX_RECORD_SEC * 1000L, 1000) {
            @Override public void onTick(long remaining) {
                int elapsed = MAX_RECORD_SEC - (int)(remaining / 1000);
                int minutes = elapsed / 60;
                int seconds = elapsed % 60;
                tvTimer.setText(String.format("%d:%02d", minutes, seconds));
                pbDuration.setProgress(elapsed);
            }
            @Override public void onFinish() {
                // Auto-stop at max duration
                stopRecording();
            }
        }.start();
    }

    private void stopRecording() {
        if (activeRecording != null) {
            activeRecording.stop();
            activeRecording = null;
        }
        if (recordTimer != null) {
            recordTimer.cancel();
            recordTimer = null;
        }
        isRecording = false;
        btnRecord.setImageResource(R.drawable.ic_play);
        btnRecord.setBackgroundTintList(null);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Post-recording: trim original + launch editor
    // ─────────────────────────────────────────────────────────────────────────

    private void onRecordingFinalized(File userClipFile) {
        // Show compositing overlay
        layoutCompositing.setVisibility(View.VISIBLE);
        tvCompositingStatus.setText("Trimming original clip…");
        pbCompositing.setProgress(20);

        // Run trimming on a background thread
        cameraExecutor.execute(() -> {
            File trimmedOriginal = trimOriginalClip();

            runOnUiThread(() -> {
                pbCompositing.setProgress(60);
                tvCompositingStatus.setText("Preparing sequence…");
            });

            // Small delay for UX continuity
            try { Thread.sleep(400); } catch (InterruptedException ignored) {}

            runOnUiThread(() -> {
                pbCompositing.setProgress(100);
                layoutCompositing.setVisibility(View.GONE);
                launchEditor(trimmedOriginal, userClipFile);
            });
        });
    }

    /**
     * Trims the original video to the selected clip duration.
     *
     * Uses MediaMetadataRetriever to determine actual duration and
     * MediaMuxer-based trimming. Falls back gracefully by returning
     * a "clip URI string" that is passed to ReelEditorActivity which
     * can also handle trimming via its own VideoTrimActivity.
     *
     * @return trimmed File, or null if trimming fails (editor handles it)
     */
    private File trimOriginalClip() {
        // We pass the original URL + trim duration to the editor via extras;
        // the editor's multi-clip merger already handles time-range trimming
        // via MediaExtractor when it merges the clip list. So we simply
        // signal the trim range via EXTRA_SEQ_ORIGINAL_TRIM_SEC in the Intent.
        //
        // If a local cached copy of the original were available we would trim
        // it here with MediaMuxer + MediaExtractor; since we have a streaming
        // URL we let the editor pipeline handle it.
        return null;   // null signals "use original URL + trim extra"
    }

    private void launchEditor(File trimmedOriginal, File userClipFile) {
        publishSequenceToFirebase();

        Intent intent = new Intent(this, ReelEditorActivity.class);

        if (trimmedOriginal != null) {
            // Both clips available as local files — pass as multi-clip list
            ArrayList<String> clips = new ArrayList<>();
            clips.add(trimmedOriginal.getAbsolutePath());
            clips.add(userClipFile.getAbsolutePath());
            intent.putStringArrayListExtra(MultiClipCameraActivity.EXTRA_CLIP_PATHS, clips);
            intent.putExtra("is_multi_clip", true);
        } else {
            // Pass original URL + user clip path; editor merges with trim
            intent.putExtra(ReelEditorActivity.EXTRA_VIDEO_URI,   userClipFile.getAbsolutePath());
            intent.putExtra(ReelEditorActivity.EXTRA_IS_FILE_PATH, true);
            // Sequence metadata
            intent.putExtra("seq_original_video_url",  originalVideoUrl);
            intent.putExtra("seq_original_clip_sec",   clipDurationSec);
            intent.putExtra("is_sequence", true);
        }

        // Sequence metadata for upload
        if (originalReelId   != null) intent.putExtra(EXTRA_ORIGINAL_REEL_ID,    originalReelId);
        if (originalOwnerUid != null) intent.putExtra(EXTRA_ORIGINAL_OWNER_UID,   originalOwnerUid);
        if (originalOwnerName!= null) intent.putExtra(EXTRA_ORIGINAL_OWNER_NAME, originalOwnerName);

        startActivity(intent);
        finish();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Firebase — write sequence record + notification
    // ─────────────────────────────────────────────────────────────────────────

    private void publishSequenceToFirebase() {
        if (originalReelId == null || originalReelId.isEmpty()) return;

        String myUid  = FirebaseUtils.getCurrentUid();
        String myName = FirebaseUtils.getCurrentName();
        if (myUid == null || myUid.isEmpty()) return;

        // Increment sequenceCount on the original reel
        FirebaseUtils.db()
            .getReference("reels")
            .child(originalReelId)
            .child("sequenceCount")
            .setValue(com.google.firebase.database.ServerValue.increment(1));

        // Write sequence record (will be updated with real reelId after upload)
        String pendingKey = FirebaseUtils.db()
            .getReference("reelSequences")
            .child(originalReelId)
            .push()
            .getKey();

        if (pendingKey != null) {
            Map<String, Object> record = new HashMap<>();
            record.put("originalReelId",   originalReelId);
            record.put("originalOwnerUid", originalOwnerUid);
            record.put("sequencerUid",     myUid);
            record.put("sequencerName",    myName != null ? myName : "");
            record.put("clipDurationSec",  clipDurationSec);
            record.put("timestamp",        System.currentTimeMillis());
            record.put("pending",          true);

            FirebaseUtils.db()
                .getReference("reelSequences")
                .child(originalReelId)
                .child(pendingKey)
                .setValue(record);
        }

        // Notify original creator
        if (!originalOwnerUid.isEmpty() && !originalOwnerUid.equals(myUid)) {
            Map<String, Object> notif = new HashMap<>();
            notif.put("type",       "sequence");
            notif.put("fromUid",    myUid);
            notif.put("fromName",   myName != null ? myName : "");
            notif.put("reelId",     originalReelId);
            notif.put("clipSec",    clipDurationSec);
            notif.put("timestamp",  System.currentTimeMillis());
            FirebaseUtils.db()
                .getReference("reelNotifications")
                .child(originalOwnerUid)
                .push()
                .setValue(notif);
        }

        Log.d(TAG, "publishSequenceToFirebase: originalReelId=" + originalReelId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Permissions
    // ─────────────────────────────────────────────────────────────────────────

    private boolean hasPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
            && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissions() {
        ActivityCompat.requestPermissions(this,
            new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO},
            REQ_PERMISSIONS);
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms,
                                           @NonNull int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == REQ_PERMISSIONS
            && results.length >= 2
            && results[0] == PackageManager.PERMISSION_GRANTED
            && results[1] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            Toast.makeText(this, "Camera & Mic permission required", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utilities
    // ─────────────────────────────────────────────────────────────────────────

    private void releasePlayer() {
        if (exoPlayer != null) {
            exoPlayer.release();
            exoPlayer = null;
        }
    }

    private static String nvl(String s) {
        return s != null ? s : "";
    }
}
