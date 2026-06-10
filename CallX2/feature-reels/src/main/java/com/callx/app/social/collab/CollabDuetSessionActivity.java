package com.callx.app.social.collab;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.View;
import android.widget.*;
import android.net.Uri;
import androidx.annotation.NonNull;
import com.google.android.material.button.MaterialButton;
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
import androidx.work.*;

import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.workers.CollabDuetCompositorWorker;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.firebase.database.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * CollabDuetSessionActivity — Real-time collab duet recording screen.
 *
 * Both HOST and PARTNER use this same Activity (controlled by EXTRA_IS_HOST).
 *
 * Architecture:
 *  ┌─────────────────┬─────────────────┐
 *  │  Original Reel  │  Partner Camera │  ← top half (split 50/50)
 *  │  (ExoPlayer)    │  (JPEG frames)  │
 *  ├─────────────────┴─────────────────┤
 *  │         Self Camera (CameraX)     │  ← bottom half
 *  ├────────────────────────────────────┤
 *  │   [Ready ✅]          [Stop ⏹]   │  ← controls
 *  └────────────────────────────────────┘
 *
 * Sync protocol (Firebase RTDB):
 *  1. Both tap "I'm Ready" → hostReady / partnerReady set to true
 *  2. When bothReady == true, host writes startAtMillis = now + 4000
 *  3. Both clients countdown to startAtMillis simultaneously
 *  4. Both start CameraX recording + ExoPlayer at the same moment
 *  5. Every 500ms, each user uploads a compressed camera frame as base64
 *     to collabDuetFrames/{sessionId}/{uid} for partner preview
 *  6. On Stop: local video uploaded → both URLs written → compositor queued
 */
@UnstableApi
public class CollabDuetSessionActivity extends AppCompatActivity {

    public static final String EXTRA_SESSION_ID = "collab_session_id";
    public static final String EXTRA_IS_HOST    = "collab_is_host";

    private static final int REQ_PERMISSIONS = 444;
    private static final long FRAME_INTERVAL_MS = 600;  // partner preview frame rate
    private static final int  FRAME_JPEG_QUALITY = 35;  // compressed for Firebase (≈15KB/frame)

    // Views
    private PlayerView  playerOriginal;
    private FrameLayout framePartner;
    private ImageView   ivPartnerFrame;
    private LinearLayout layoutPartnerWaiting;
    private TextView    tvPartnerStatus, tvPartnerNameLabel;
    private PreviewView previewSelf;
    private MaterialButton btnReady, btnStop;
    private TextView    tvCountdown, tvRecordingTime, tvSessionInfo;
    private View        layoutRecordingIndicator, dotRecording;
    private ImageButton btnBack;

    // State
    private String sessionId;
    private boolean isHost;
    private CollabDuetSession session;
    private boolean  myReady = false;
    private boolean  isRecording = false;

    // Media
    private ExoPlayer    exoPlayer;
    private VideoCapture<Recorder> videoCapture;
    private Recording    activeRecording;
    private ExecutorService cameraExecutor;
    private File         outputFile;

    // Timers
    private Handler    mainHandler   = new Handler(Looper.getMainLooper());
    private CountDownTimer countDownTimer;
    private Runnable   frameUploadRunnable;
    private Runnable   recordingTimerRunnable;
    private long       recordingStartMs = 0;

    // Firebase
    private DatabaseReference sessionRef;
    private DatabaseReference framesRef;
    private ValueEventListener sessionListener;
    private ValueEventListener partnerFrameListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_collab_duet_session);

        sessionId = getIntent().getStringExtra(EXTRA_SESSION_ID);
        isHost    = getIntent().getBooleanExtra(EXTRA_IS_HOST, true);
        cameraExecutor = Executors.newSingleThreadExecutor();

        bindViews();
        checkPermissions();
        setupFirebaseListeners();
    }

    private void bindViews() {
        playerOriginal         = findViewById(R.id.player_collab_original);
        framePartner           = findViewById(R.id.preview_collab_partner);
        ivPartnerFrame         = findViewById(R.id.iv_partner_frame);
        layoutPartnerWaiting   = findViewById(R.id.layout_partner_waiting);
        tvPartnerStatus        = findViewById(R.id.tv_partner_status);
        tvPartnerNameLabel     = findViewById(R.id.tv_partner_name_label);
        previewSelf            = findViewById(R.id.preview_collab_self);
        btnReady               = findViewById(R.id.btn_collab_ready);
        btnStop                = findViewById(R.id.btn_collab_stop);
        tvCountdown            = findViewById(R.id.tv_collab_countdown);
        tvRecordingTime        = findViewById(R.id.tv_recording_time);
        tvSessionInfo          = findViewById(R.id.tv_collab_session_info);
        layoutRecordingIndicator = findViewById(R.id.layout_recording_indicator);
        dotRecording           = findViewById(R.id.dot_recording);
        btnBack                = findViewById(R.id.btn_collab_back);

        btnBack.setOnClickListener(v -> onBackPressed());
        btnReady.setOnClickListener(v -> onReadyTapped());
        btnStop.setOnClickListener(v -> stopRecording());
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    private void checkPermissions() {
        String[] perms = {Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO};
        boolean allGranted = true;
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false; break;
            }
        }
        if (allGranted) startCamera();
        else ActivityCompat.requestPermissions(this, perms, REQ_PERMISSIONS);
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms, @NonNull int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        boolean ok = true;
        for (int r : results) if (r != PackageManager.PERMISSION_GRANTED) { ok = false; break; }
        if (ok) startCamera();
        else { Toast.makeText(this, "Camera & mic permission required", Toast.LENGTH_LONG).show(); finish(); }
    }

    // ── Firebase ──────────────────────────────────────────────────────────────

    private void setupFirebaseListeners() {
        if (sessionId == null) { finish(); return; }
        sessionRef = FirebaseUtils.getCollabDuetSessionsRef().child(sessionId);
        framesRef  = FirebaseUtils.getCollabDuetFramesRef(sessionId);

        sessionListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                session = snap.getValue(CollabDuetSession.class);
                if (session == null) return;
                onSessionUpdated(session);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        sessionRef.addValueEventListener(sessionListener);
    }

    private void onSessionUpdated(CollabDuetSession s) {
        // Update session info label
        String myUid = FirebaseUtils.getCurrentUid();
        String partnerName = isHost ? s.partnerName : s.hostName;
        if (partnerName != null && !partnerName.isEmpty()) {
            tvSessionInfo.setText("Collab with @" + partnerName);
            tvPartnerNameLabel.setText("@" + partnerName);
            tvPartnerNameLabel.setVisibility(View.VISIBLE);
        }

        // Setup ExoPlayer here if it wasn't ready when startCamera() first ran
        if (exoPlayer == null) setupOriginalPlayer();

        switch (s.status) {
            case CollabDuetSession.STATUS_WAITING:
                tvPartnerStatus.setText(s.partnerUid != null
                    ? "Waiting for @" + s.partnerName + " to join…"
                    : "Waiting for partner…");
                layoutPartnerWaiting.setVisibility(View.VISIBLE);
                break;

            case CollabDuetSession.STATUS_BOTH_READY:
                // Host writes startAtMillis; both clients schedule synchronized start
                if (isHost && s.startAtMillis == 0) {
                    long startAt = System.currentTimeMillis() + 4200; // 4.2s for network delay
                    sessionRef.child("startAtMillis").setValue(startAt);
                }
                if (s.startAtMillis > 0 && !isRecording && countDownTimer == null) {
                    scheduleCountdown(s.startAtMillis);
                }
                break;

            case CollabDuetSession.STATUS_RECORDING:
                // Ensure recording started (may have been triggered by countdown)
                break;

            case CollabDuetSession.STATUS_DONE:
                if (s.bothUploaded()) {
                    queueCompositor(s);
                }
                break;

            case CollabDuetSession.STATUS_DECLINED:
                Toast.makeText(this, "Partner declined the collab invite", Toast.LENGTH_LONG).show();
                finish();
                break;
        }

        // Start listening to partner's camera frames
        if (!CollabDuetSession.STATUS_WAITING.equals(s.status)) {
            startPartnerFrameListener(s, myUid);
        }
    }

    private void startPartnerFrameListener(CollabDuetSession s, String myUid) {
        if (partnerFrameListener != null) return;
        String partnerUid = isHost ? s.partnerUid : s.hostUid;
        if (partnerUid == null) return;

        partnerFrameListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                String b64 = snap.getValue(String.class);
                if (b64 == null || b64.isEmpty()) return;
                try {
                    byte[] bytes = Base64.decode(b64, Base64.NO_WRAP);
                    Bitmap bm = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    if (bm != null) {
                        ivPartnerFrame.setImageBitmap(bm);
                        layoutPartnerWaiting.setVisibility(View.GONE);
                    }
                } catch (Exception ignored) {}
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        framesRef.child(partnerUid).addValueEventListener(partnerFrameListener);
    }

    // ── Ready → Countdown → Record ───────────────────────────────────────────

    private void onReadyTapped() {
        if (myReady) return;
        myReady = true;
        btnReady.setEnabled(false);
        btnReady.setText("Waiting for partner…");

        String myUid = FirebaseUtils.getCurrentUid();
        if (myUid == null) return;
        boolean iAmHost = session != null && myUid.equals(session.hostUid);
        sessionRef.child(iAmHost ? "hostReady" : "partnerReady").setValue(true);

        // Check if partner is already ready
        if (session != null && session.bothReady()) {
            sessionRef.child("status").setValue(CollabDuetSession.STATUS_BOTH_READY);
        }
    }

    private void scheduleCountdown(long startAtMillis) {
        long delay = startAtMillis - System.currentTimeMillis();
        if (delay < 0) delay = 0;

        tvCountdown.setVisibility(View.VISIBLE);

        countDownTimer = new CountDownTimer(delay + 3000, 1000) {
            @Override public void onTick(long ms) {
                long sec = ms / 1000;
                if (sec >= 1 && sec <= 3) {
                    tvCountdown.setText(String.valueOf(sec));
                    tvCountdown.setAlpha(1f);
                } else if (sec == 0) {
                    tvCountdown.setText("GO!");
                }
            }
            @Override public void onFinish() {
                tvCountdown.setVisibility(View.GONE);
                startRecording();
            }
        }.start();

        // Schedule ExoPlayer start at exact startAtMillis
        mainHandler.postDelayed(() -> {
            if (exoPlayer != null) {
                exoPlayer.setPlayWhenReady(true);
                exoPlayer.seekTo(0);
                exoPlayer.play();
            }
        }, delay);
    }

    // ── Camera ────────────────────────────────────────────────────────────────

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future =
            ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewSelf.getSurfaceProvider());

                Recorder recorder = new Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.HD))
                    .build();
                videoCapture = VideoCapture.withOutput(recorder);

                CameraSelector selector = CameraSelector.DEFAULT_FRONT_CAMERA;
                provider.unbindAll();
                provider.bindToLifecycle(this, selector, preview, videoCapture);

                setupOriginalPlayer();

            } catch (Exception e) {
                Toast.makeText(this, "Camera init failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void setupOriginalPlayer() {
        if (session == null || session.reelVideoUrl == null || session.reelVideoUrl.isEmpty()) return;
        exoPlayer = new ExoPlayer.Builder(this).build();
        playerOriginal.setPlayer(exoPlayer);
        exoPlayer.setMediaItem(MediaItem.fromUri(session.reelVideoUrl));
        exoPlayer.setRepeatMode(Player.REPEAT_MODE_ONE);
        exoPlayer.setPlayWhenReady(false); // start only on countdown finish
        exoPlayer.prepare();
    }

    // ── Recording ─────────────────────────────────────────────────────────────

    @SuppressWarnings("MissingPermission")
    private void startRecording() {
        if (isRecording || videoCapture == null) return;
        isRecording = true;
        sessionRef.child("status").setValue(CollabDuetSession.STATUS_RECORDING);

        btnReady.setVisibility(View.GONE);
        btnStop.setVisibility(View.VISIBLE);
        layoutRecordingIndicator.setVisibility(View.VISIBLE);

        // Blink recording dot
        Runnable blinkRunnable = new Runnable() {
            boolean visible = true;
            @Override public void run() {
                if (!isRecording) return;
                dotRecording.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
                visible = !visible;
                mainHandler.postDelayed(this, 700);
            }
        };
        mainHandler.post(blinkRunnable);

        // Recording timer
        recordingStartMs = System.currentTimeMillis();
        recordingTimerRunnable = new Runnable() {
            @Override public void run() {
                if (!isRecording) return;
                long elapsed = System.currentTimeMillis() - recordingStartMs;
                long sec = elapsed / 1000;
                tvRecordingTime.setText(String.format(Locale.ROOT, "%d:%02d", sec / 60, sec % 60));
                if (session != null && session.durationMs > 0 && elapsed >= session.durationMs) {
                    CollabDuetSessionActivity.this.stopRecording();
                    return;
                }
                mainHandler.postDelayed(this, 1000);
            }
        };
        mainHandler.post(recordingTimerRunnable);

        // Start frame uploads for partner preview
        String myUid = FirebaseUtils.getCurrentUid();
        startFrameUploads(myUid);

        // Start CameraX recording
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date());
        outputFile = new File(getCacheDir(), "collab_" + sessionId + "_" + ts + ".mp4");

        FileOutputOptions opts = new FileOutputOptions.Builder(outputFile).build();
        activeRecording = videoCapture.getOutput()
            .prepareRecording(this, opts)
            .withAudioEnabled()
            .start(cameraExecutor, event -> {
                if (event instanceof VideoRecordEvent.Finalize) {
                    VideoRecordEvent.Finalize fin = (VideoRecordEvent.Finalize) event;
                    if (!fin.hasError()) {
                        mainHandler.post(() -> uploadMyVideo(outputFile));
                    } else {
                        mainHandler.post(() -> Toast.makeText(this,
                            "Recording error: " + fin.getError(), Toast.LENGTH_LONG).show());
                    }
                }
            });
    }

    private void stopRecording() {
        if (!isRecording) return;
        isRecording = false;
        if (activeRecording != null) {
            activeRecording.stop();
            activeRecording = null;
        }
        if (exoPlayer != null) exoPlayer.pause();
        stopFrameUploads();
        btnStop.setEnabled(false);
        btnStop.setText("Uploading…");
        layoutRecordingIndicator.setVisibility(View.GONE);
    }

    // ── Frame uploads (partner preview) ──────────────────────────────────────

    private void startFrameUploads(String myUid) {
        if (myUid == null) return;
        DatabaseReference myFrameRef = framesRef.child(myUid);

        frameUploadRunnable = new Runnable() {
            @Override public void run() {
                if (!isRecording) return;
                captureAndUploadFrame(myFrameRef);
                mainHandler.postDelayed(this, FRAME_INTERVAL_MS);
            }
        };
        mainHandler.postDelayed(frameUploadRunnable, FRAME_INTERVAL_MS);
    }

    private void captureAndUploadFrame(DatabaseReference ref) {
        try {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return;
            Bitmap bm = previewSelf.getBitmap();
            if (bm == null) return;
            // Scale down + compress
            Bitmap scaled = Bitmap.createScaledBitmap(bm, 160, 284, false);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, FRAME_JPEG_QUALITY, baos);
            String b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
            ref.setValue(b64);
            baos.close();
            scaled.recycle();
        } catch (Exception ignored) {}
    }

    private void stopFrameUploads() {
        if (frameUploadRunnable != null) {
            mainHandler.removeCallbacks(frameUploadRunnable);
            frameUploadRunnable = null;
        }
    }

    // ── Upload & composite ────────────────────────────────────────────────────

    private void uploadMyVideo(File file) {
        if (file == null || !file.exists()) return;
        String myUid = FirebaseUtils.getCurrentUid();
        if (myUid == null) return;

        // Upload to Firebase Storage via simple OkHttp multipart (or use Firebase Storage SDK)
        String storagePath = "collab_duets/" + sessionId + "/" + myUid + ".mp4";

        com.google.firebase.storage.FirebaseStorage.getInstance()
            .getReference(storagePath)
            .putFile(Uri.fromFile(file))
            .addOnSuccessListener(task ->
                task.getStorage().getDownloadUrl().addOnSuccessListener(uri -> {
                    String url = uri.toString();
                    // Write my upload URL to session
                    String field = isHost ? "hostVideoUrl" : "partnerVideoUrl";
                    sessionRef.child(field).setValue(url);
                    // Mark done if both uploaded
                    sessionRef.child("status").setValue(CollabDuetSession.STATUS_DONE);
                    runOnUiThread(() -> {
                        btnStop.setText("Uploaded!");
                        Toast.makeText(CollabDuetSessionActivity.this, "Your recording uploaded. Compositing...",
                            Toast.LENGTH_LONG).show();
                    });
                })
            )
            .addOnFailureListener(e -> runOnUiThread(() ->
                Toast.makeText(CollabDuetSessionActivity.this, "Upload failed: " + e.getMessage(),
                    Toast.LENGTH_LONG).show()));
    }

    private void queueCompositor(CollabDuetSession s) {
        // Only host queues the compositor (avoid double-queuing)
        if (!isHost) return;

        Data data = new Data.Builder()
            .putString(CollabDuetCompositorWorker.KEY_SESSION_ID,      s.sessionId)
            .putString(CollabDuetCompositorWorker.KEY_HOST_VIDEO_URL,  s.hostVideoUrl)
            .putString(CollabDuetCompositorWorker.KEY_PART_VIDEO_URL,  s.partnerVideoUrl)
            .putString(CollabDuetCompositorWorker.KEY_REEL_ID,         s.reelId)
            .putString(CollabDuetCompositorWorker.KEY_HOST_UID,        s.hostUid)
            .putString(CollabDuetCompositorWorker.KEY_HOST_NAME,       s.hostName)
            .putString(CollabDuetCompositorWorker.KEY_PARTNER_UID,     s.partnerUid)
            .putString(CollabDuetCompositorWorker.KEY_PARTNER_NAME,    s.partnerName)
            .build();

        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(CollabDuetCompositorWorker.class)
            .setInputData(data)
            .setConstraints(new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build())
            .build();

        WorkManager.getInstance(this).enqueue(req);
        Toast.makeText(this, "🎬 Compositing your collab duet…", Toast.LENGTH_LONG).show();
        finish();
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override protected void onDestroy() {
        super.onDestroy();
        mainHandler.removeCallbacksAndMessages(null);
        if (countDownTimer != null) countDownTimer.cancel();
        if (sessionRef != null && sessionListener != null)
            sessionRef.removeEventListener(sessionListener);
        if (framesRef != null && partnerFrameListener != null && session != null) {
            String partnerUid = isHost ? session.partnerUid : session.hostUid;
            if (partnerUid != null) framesRef.child(partnerUid).removeEventListener(partnerFrameListener);
        }
        if (activeRecording != null) activeRecording.stop();
        if (exoPlayer != null) { exoPlayer.release(); exoPlayer = null; }
        if (cameraExecutor != null) cameraExecutor.shutdown();
    }

    @Override public void onBackPressed() {
        if (isRecording) {
            new android.app.AlertDialog.Builder(this)
                .setTitle("Leave session?")
                .setMessage("Recording will be cancelled.")
                .setPositiveButton("Leave", (d, w) -> { stopRecording(); leaveSession(); })
                .setNegativeButton("Stay", null).show();
        } else {
            leaveSession();
        }
    }

    private void leaveSession() {
        super.onBackPressed();
    }
}
