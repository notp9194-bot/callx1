package com.callx.app.social;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.CountDownTimer;
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
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.ui.PlayerView;

import com.callx.app.editor.ReelEditorActivity;
import com.callx.app.player.AdaptiveStreamingManager;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.firebase.database.ServerValue;

import org.json.JSONObject;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ReelRemixActivity — Record a remix of someone else's reel
 *
 * Layout modes:
 *  LAYOUT_SIDE_BY_SIDE  — original left, your camera right
 *  LAYOUT_REACT_CAM     — original full screen + floating cam (top-right)
 *  LAYOUT_GREEN_SCREEN  — your video over original as background
 *  LAYOUT_OVERLAY       — your video transparent overlay on original
 *
 * Flow:
 *  1. Original reel plays on loop
 *  2. User sees their cam preview
 *  3. Tap record → CameraX records to local file
 *  4. Stop → go to ReelEditorActivity with both video paths
 *  5. Editor exports composite → uploaded as new reel
 *  6. reelRemixes/{originalReelId}/{newReelId} written to Firebase
 *
 * Required extras (from ReelRemixPickerSheet):
 *   EXTRA_REEL_ID, EXTRA_OWNER_UID, EXTRA_OWNER_NAME,
 *   EXTRA_VIDEO_URL, EXTRA_THUMB_URL, EXTRA_LAYOUT
 */
@OptIn(markerClass = UnstableApi.class)
public class ReelRemixActivity extends AppCompatActivity {

    private static final String TAG = "ReelRemix";

    public static final String EXTRA_REEL_ID    = "remixOriginalReelId";
    public static final String EXTRA_OWNER_UID  = "remixOwnerUid";
    public static final String EXTRA_OWNER_NAME = "remixOwnerName";
    public static final String EXTRA_VIDEO_URL  = "remixVideoUrl";
    public static final String EXTRA_THUMB_URL  = "remixThumbUrl";
    public static final String EXTRA_LAYOUT     = "remixLayout";

    public static final String LAYOUT_SIDE_BY_SIDE = "side_by_side";
    public static final String LAYOUT_REACT_CAM    = "react_cam";
    public static final String LAYOUT_GREEN_SCREEN = "green_screen";
    public static final String LAYOUT_OVERLAY      = "overlay";

    private static final int PERM_REQ = 1201;
    private static final int MAX_DURATION_SEC = 60;

    // ── Views ─────────────────────────────────────────────────────────────────
    private PlayerView    pvOriginal;
    private PreviewView   pvCamera;
    private ImageButton   btnRecord, btnFlip, btnBack;
    private ImageView     ivLayoutIcon;
    private TextView      tvTimer, tvLayoutLabel, tvOwnerName, tvCountdown;
    private ProgressBar   pbDuration;
    private FrameLayout   floatingCamContainer;
    private View          overlayDimView;

    // ── State ─────────────────────────────────────────────────────────────────
    private ExoPlayer              originalPlayer;
    private ProcessCameraProvider  cameraProvider;
    private VideoCapture<Recorder> videoCapture;
    private Recording              activeRecording;
    private ExecutorService        cameraExecutor;
    private boolean                isRecording = false;
    private boolean                frontCamera  = true;
    private int                    elapsedSec   = 0;
    private CountDownTimer         durationTimer;

    // ── Data ──────────────────────────────────────────────────────────────────
    private String originalReelId, ownerUid, ownerName, originalVideoUrl, originalThumbUrl;
    private String layoutMode;
    private File   outputFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reel_remix);

        readExtras();
        bindViews();
        applyLayout();
        setupButtons();

        cameraExecutor = Executors.newSingleThreadExecutor();

        if (hasPermissions()) {
            startOriginalPlayback();
            startCamera();
        } else {
            requestPermissions();
        }
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    private void readExtras() {
        Intent i       = getIntent();
        originalReelId = i.getStringExtra(EXTRA_REEL_ID);
        ownerUid       = i.getStringExtra(EXTRA_OWNER_UID);
        ownerName      = i.getStringExtra(EXTRA_OWNER_NAME);
        originalVideoUrl = i.getStringExtra(EXTRA_VIDEO_URL);
        originalThumbUrl = i.getStringExtra(EXTRA_THUMB_URL);
        layoutMode     = i.getStringExtra(EXTRA_LAYOUT);
        if (layoutMode == null) layoutMode = LAYOUT_SIDE_BY_SIDE;
    }

    private void bindViews() {
        pvOriginal          = findViewById(R.id.pv_remix_original);
        pvCamera            = findViewById(R.id.pv_remix_camera);
        btnRecord           = findViewById(R.id.btn_remix_record);
        btnFlip             = findViewById(R.id.btn_remix_flip);
        btnBack             = findViewById(R.id.btn_remix_back);
        ivLayoutIcon        = findViewById(R.id.iv_remix_layout_icon);
        tvTimer             = findViewById(R.id.tv_remix_timer);
        tvLayoutLabel       = findViewById(R.id.tv_remix_layout_label);
        tvOwnerName         = findViewById(R.id.tv_remix_owner_name);
        tvCountdown         = findViewById(R.id.tv_remix_countdown);
        pbDuration          = findViewById(R.id.pb_remix_duration);
        floatingCamContainer = findViewById(R.id.container_floating_cam);
        overlayDimView      = findViewById(R.id.view_overlay_dim);

        tvOwnerName.setText("Remixing @" + ownerName);
        pbDuration.setMax(MAX_DURATION_SEC);
        pbDuration.setProgress(0);
    }

    private void applyLayout() {
        switch (layoutMode) {
            case LAYOUT_REACT_CAM:
                tvLayoutLabel.setText("React Cam");
                floatingCamContainer.setVisibility(View.VISIBLE);
                overlayDimView.setVisibility(View.GONE);
                break;
            case LAYOUT_GREEN_SCREEN:
                tvLayoutLabel.setText("Green Screen");
                floatingCamContainer.setVisibility(View.GONE);
                overlayDimView.setVisibility(View.GONE);
                break;
            case LAYOUT_OVERLAY:
                tvLayoutLabel.setText("Overlay");
                floatingCamContainer.setVisibility(View.GONE);
                overlayDimView.setVisibility(View.VISIBLE);
                overlayDimView.setAlpha(0.45f);
                break;
            default: // side_by_side
                tvLayoutLabel.setText("Side by Side");
                floatingCamContainer.setVisibility(View.GONE);
                overlayDimView.setVisibility(View.GONE);
                break;
        }
    }

    private void setupButtons() {
        btnBack.setOnClickListener(v -> onBackPressed());
        btnFlip.setOnClickListener(v -> {
            frontCamera = !frontCamera;
            startCamera();
        });
        btnRecord.setOnClickListener(v -> {
            if (isRecording) stopRecording();
            else startCountdownThenRecord();
        });
    }

    // ── Original reel playback ────────────────────────────────────────────────

    private void startOriginalPlayback() {
        originalPlayer = AdaptiveStreamingManager.get(this)
            .buildPlayer(originalVideoUrl,
                AdaptiveStreamingManager.QualityCap.Q720P, null);
        pvOriginal.setPlayer(originalPlayer);
        originalPlayer.setRepeatMode(Player.REPEAT_MODE_ONE);
        originalPlayer.setPlayWhenReady(true);
        originalPlayer.prepare();
    }

    // ── Camera ────────────────────────────────────────────────────────────────

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future =
            ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                bindCameraUseCases();
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Camera init failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases() {
        cameraProvider.unbindAll();

        CameraSelector selector = frontCamera
            ? CameraSelector.DEFAULT_FRONT_CAMERA
            : CameraSelector.DEFAULT_BACK_CAMERA;

        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(pvCamera.getSurfaceProvider());

        Recorder recorder = new Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HD))
            .build();
        videoCapture = VideoCapture.withOutput(recorder);

        cameraProvider.bindToLifecycle(this, selector, preview, videoCapture);
    }

    // ── Recording ─────────────────────────────────────────────────────────────

    private void startCountdownThenRecord() {
        btnRecord.setEnabled(false);
        tvCountdown.setVisibility(View.VISIBLE);

        new CountDownTimer(3_000, 1_000) {
            int n = 3;
            @Override public void onTick(long ms) {
                tvCountdown.setText(String.valueOf(n--));
            }
            @Override public void onFinish() {
                tvCountdown.setVisibility(View.GONE);
                btnRecord.setEnabled(true);
                startRecording();
            }
        }.start();
    }

    @SuppressWarnings("MissingPermission")
    private void startRecording() {
        outputFile = new File(getCacheDir(),
            "remix_" + System.currentTimeMillis() + ".mp4");

        FileOutputOptions opts = new FileOutputOptions.Builder(outputFile).build();
        activeRecording = videoCapture.getOutput()
            .prepareRecording(this, opts)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(this), event -> {
                if (event instanceof VideoRecordEvent.Finalize) {
                    VideoRecordEvent.Finalize fin = (VideoRecordEvent.Finalize) event;
                    if (!fin.hasError()) {
                        goToEditor(fin.getOutputResults().getOutputUri());
                    } else {
                        Log.e(TAG, "Recording error: " + fin.getError());
                        Toast.makeText(this, "Recording failed", Toast.LENGTH_SHORT).show();
                    }
                }
            });

        isRecording = true;
        btnRecord.setImageResource(R.drawable.ic_pause);
        startDurationTimer();
    }

    private void stopRecording() {
        if (activeRecording != null) {
            activeRecording.stop();
            activeRecording = null;
        }
        isRecording = false;
        if (durationTimer != null) durationTimer.cancel();
        btnRecord.setImageResource(R.drawable.ic_play);
    }

    private void startDurationTimer() {
        elapsedSec = 0;
        durationTimer = new CountDownTimer((long) MAX_DURATION_SEC * 1_000, 1_000) {
            @Override public void onTick(long ms) {
                elapsedSec++;
                tvTimer.setText(formatTime(elapsedSec));
                pbDuration.setProgress(elapsedSec);
            }
            @Override public void onFinish() { stopRecording(); }
        }.start();
    }

    private String formatTime(int sec) {
        return String.format("%02d:%02d", sec / 60, sec % 60);
    }

    // ── Navigate to editor ────────────────────────────────────────────────────

    private void goToEditor(Uri remixUri) {
        Intent intent = new Intent(this, ReelEditorActivity.class);
        intent.putExtra("videoPath", remixUri.toString());
        intent.putExtra("isRemix", true);
        intent.putExtra("remixOriginalReelId",  originalReelId);
        intent.putExtra("remixOriginalVideoUrl", originalVideoUrl);
        intent.putExtra("remixOriginalThumbUrl", originalThumbUrl);
        intent.putExtra("remixOwnerUid",         ownerUid);
        intent.putExtra("remixOwnerName",        ownerName);
        intent.putExtra("remixLayoutMode",       layoutMode);
        startActivity(intent);
        finish();
    }

    // ── Firebase — write remix record ─────────────────────────────────────────

    /**
     * Called by the upload pipeline after the composite remix video is uploaded.
     * Writes the remix record to Firebase and increments remixCount atomically.
     */
    public static void publishRemixToFirebase(
            String originalReelId, String newReelId,
            String originalOwnerUid, String originalOwnerName,
            String originalThumbUrl, String originalVideoUrl,
            String remixerUid, String remixerName, String remixerPhoto,
            String remixVideoUrl, String remixThumbUrl,
            String remixCaption, String layoutMode) {

        ReelRemixModel model = new ReelRemixModel(
            newReelId, originalReelId,
            originalOwnerUid, originalOwnerName, originalThumbUrl, originalVideoUrl,
            remixerUid, remixerName, remixerPhoto,
            remixVideoUrl, remixThumbUrl, remixCaption, layoutMode);

        // Write remix record
        FirebaseUtils.db()
            .getReference("reelRemixes")
            .child(originalReelId)
            .child(newReelId)
            .setValue(model);

        // Atomic increment of remixCount on the original reel
        FirebaseUtils.db()
            .getReference("reels")
            .child(originalReelId)
            .child("remixCount")
            .setValue(ServerValue.increment(1));

        // Index: remixer's remixes list
        FirebaseUtils.db()
            .getReference("userRemixes")
            .child(remixerUid)
            .child(newReelId)
            .setValue(System.currentTimeMillis());

        // Notify original creator
        Map<String, Object> notif = new HashMap<>();
        notif.put("type",         "remix");
        notif.put("fromUid",      remixerUid);
        notif.put("fromName",     remixerName);
        notif.put("reelId",       originalReelId);
        notif.put("remixReelId",  newReelId);
        notif.put("timestamp",    System.currentTimeMillis());
        FirebaseUtils.db()
            .getReference("reelNotifications")
            .child(originalOwnerUid)
            .push()
            .setValue(notif);
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    private boolean hasPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED
            && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissions() {
        ActivityCompat.requestPermissions(this,
            new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO},
            PERM_REQ);
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms,
                                           @NonNull int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == PERM_REQ && results.length >= 2
            && results[0] == PackageManager.PERMISSION_GRANTED
            && results[1] == PackageManager.PERMISSION_GRANTED) {
            startOriginalPlayback();
            startCamera();
        } else {
            Toast.makeText(this, "Camera & Mic permission required", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override protected void onPause() {
        super.onPause();
        if (originalPlayer != null) originalPlayer.pause();
    }

    @Override protected void onResume() {
        super.onResume();
        if (originalPlayer != null) originalPlayer.play();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (originalPlayer != null) { originalPlayer.release(); originalPlayer = null; }
        if (durationTimer  != null) durationTimer.cancel();
        if (activeRecording != null) { activeRecording.stop(); activeRecording = null; }
        if (cameraProvider  != null) cameraProvider.unbindAll();
        cameraExecutor.shutdown();
    }
}
