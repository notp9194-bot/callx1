package com.callx.app.activities;

import android.Manifest;
import android.app.PictureInPictureParams;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.media.projection.MediaProjectionManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Rational;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import com.bumptech.glide.Glide;
import com.callx.app.calls.databinding.ActivityCallBinding;
import com.callx.app.services.CallForegroundService;
import com.callx.app.utils.BackgroundBlurProcessor;
import com.callx.app.utils.CallStatsHelper;
import com.callx.app.utils.Constants;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.PushNotify;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;
import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.SdpObserver;
import org.webrtc.ScreenCapturerAndroid;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.CallLogEntity;

public class CallActivity extends AppCompatActivity {

    private ActivityCallBinding binding;

    // Call params
    private String partnerUid, partnerName, partnerPhoto, partnerThumb, callId;
    private boolean isCaller, isVideo;
    private boolean micOn = true, camOn = true, speakerOn = false;
    private boolean usingFrontCamera = true;
    private long startedAt = 0;
    private boolean callConnected = false;
    private boolean finishing = false;
    private boolean remoteCamOn = true;
    private boolean isRestore = false;

    // Timer
    private final Handler tick = new Handler(Looper.getMainLooper());
    private Runnable ticker;

    // Call Timeout — 60s mein koi na uthaye toh auto-cancel
    private static final long CALL_TIMEOUT_MS = 60_000L;
    private final Handler timeoutHandler = new Handler(Looper.getMainLooper());
    private Runnable timeoutRunnable;

    // ICE reconnection
    private int iceRestartCount = 0;
    private final Handler iceRestartHandler = new Handler(Looper.getMainLooper());
    private Runnable iceRestartRunnable;
    private ValueEventListener iceRestartRequestListener;

    // Wake lock
    private PowerManager.WakeLock wakeLock;

    // Audio
    private AudioManager audioManager;

    // FIX: Headphone unplug receiver
    private BroadcastReceiver noisyReceiver;
    private boolean noisyReceiverRegistered = false;

    // FIX: Bluetooth SCO receiver
    private BroadcastReceiver btScoReceiver;
    private boolean btScoReceiverRegistered = false;

    // WebRTC
    private EglBase eglBase;
    private PeerConnectionFactory factory;
    private PeerConnection peerConnection;
    private VideoTrack localVideoTrack;
    private AudioTrack localAudioTrack;
    private VideoCapturer videoCapturer;        // camera OR screen capturer
    private VideoSource videoSource;
    private AudioSource audioSource;
    private SurfaceTextureHelper surfaceHelper;
    private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor();
    private boolean capturerRunning = false;

    // Adaptive video quality
    private boolean usingLowQuality = false;

    // Firebase signaling
    private DatabaseReference callRef;
    private ValueEventListener statusListener;
    private ChildEventListener remoteCandidateListener;
    private boolean remoteDescSet = false;
    private final List<IceCandidate> pendingCandidates = new ArrayList<>();

    // Network callback
    private ConnectivityManager.NetworkCallback networkCallback;

    // ══ Feature 1: Call Recording ══════════════════════════════════════════
    private static final int REQ_RECORD_AUDIO = 403;
    private MediaRecorder mediaRecorder;
    private boolean isRecording = false;
    private File recordingFile;

    // ══ Feature 2: Screen Share ════════════════════════════════════════════
    private static final int REQ_SCREEN_CAPTURE = 404;
    private boolean screenSharing = false;
    private MediaProjectionManager mpManager;

    // ══ Feature 3: E2E Badge ═══════════════════════════════════════════════
    // Shown when ICE CONNECTED (WebRTC always uses DTLS-SRTP)

    // ══ Feature 4: Background Blur ════════════════════════════════════════
    private boolean blurEnabled = false;
    private BackgroundBlurProcessor blurProcessor;

    // ══ Feature 5: Call Stats ═════════════════════════════════════════════
    private boolean statsVisible = false;
    private final Handler statsHandler = new Handler(Looper.getMainLooper());
    private Runnable statsPollRunnable;

    // ══ (Custom Ringtone is in IncomingRingService — no state needed here)

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);

        binding = ActivityCallBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        partnerUid   = getIntent().getStringExtra("partnerUid");
        partnerName  = getIntent().getStringExtra("partnerName");
        partnerPhoto = getIntent().getStringExtra("partnerPhoto");
        partnerThumb = getIntent().getStringExtra("partnerThumb");
        isCaller     = getIntent().getBooleanExtra("isCaller", false);
        isVideo      = getIntent().getBooleanExtra("video", false);
        callId       = getIntent().getStringExtra("callId");
        isRestore    = getIntent().getBooleanExtra("isRestore", false);

        if (partnerUid == null) { finish(); return; }

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        mpManager    = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        acquireWakeLock();

        binding.tvCallerName.setText(partnerName != null ? partnerName : "Unknown");
        binding.tvCallStatus.setText(isCaller
            ? (isVideo ? "Video calling..." : "Calling...") : "Connecting...");

        if (!isVideo) {
            binding.localVideo.setVisibility(View.GONE);
            binding.remoteVideo.setVisibility(View.GONE);
            binding.btnToggleCamera.setVisibility(View.GONE);
            binding.btnSwitchCamera.setVisibility(View.GONE);
            if (binding.tvCameraLabel != null) binding.tvCameraLabel.setVisibility(View.GONE);
            // Blur is video-only
            if (binding.btnBlur != null)      binding.btnBlur.setVisibility(View.GONE);
            if (binding.tvBlurLabel != null)  binding.tvBlurLabel.setVisibility(View.GONE);
            // Screen share: still available for audio calls (shares screen as video)
        }

        String avatarUrl = (partnerThumb != null && !partnerThumb.isEmpty())
            ? partnerThumb : partnerPhoto;
        if (avatarUrl != null && !avatarUrl.isEmpty())
            Glide.with(this).load(avatarUrl).circleCrop().into(binding.ivCallAvatar);

        binding.btnEndCall.setOnClickListener(v -> endCall());
        binding.btnToggleMic.setOnClickListener(v -> toggleMic());
        binding.btnToggleCamera.setOnClickListener(v -> toggleCamera());
        binding.btnSwitchCamera.setOnClickListener(v -> switchCamera());
        binding.btnToggleSpeaker.setOnClickListener(v -> toggleSpeaker());

        // ── New feature buttons ──
        if (binding.btnRecord != null)
            binding.btnRecord.setOnClickListener(v -> toggleRecording());
        if (binding.btnScreenShare != null)
            binding.btnScreenShare.setOnClickListener(v -> toggleScreenShare());
        if (binding.btnStats != null)
            binding.btnStats.setOnClickListener(v -> toggleStats());
        if (binding.btnBlur != null)
            binding.btnBlur.setOnClickListener(v -> toggleBlur());

        updateMicUI();
        updateCameraUI();
        setupLocalVideoDrag();

        // Call Timeout for caller
        if (isCaller) {
            timeoutRunnable = () -> {
                if (!callConnected && !finishing) {
                    binding.tvCallStatus.setText("No answer");
                    endCall();
                }
            };
            timeoutHandler.postDelayed(timeoutRunnable, CALL_TIMEOUT_MS);
        }

        if (isRestore && callConnected) return;
        checkPermsAndInit();
    }

    // ══ Feature 1: Call Recording ══════════════════════════════════════════
    private void toggleRecording() {
        if (!callConnected) {
            Toast.makeText(this, "Call not connected yet", Toast.LENGTH_SHORT).show();
            return;
        }
        if (isRecording) stopRecording();
        else             startRecording();
    }

    private void startRecording() {
        // Need RECORD_AUDIO (already granted) + WRITE_EXTERNAL_STORAGE (pre-Q)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                   != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_RECORD_AUDIO);
            return;
        }
        try {
            File dir = new File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "CallX");
            if (!dir.exists()) dir.mkdirs();
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            recordingFile = new File(dir, "call_" + ts + ".m4a");

            mediaRecorder = new MediaRecorder();
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setAudioSamplingRate(44100);
            mediaRecorder.setAudioEncodingBitRate(128000);
            mediaRecorder.setOutputFile(recordingFile.getAbsolutePath());
            mediaRecorder.prepare();
            mediaRecorder.start();

            isRecording = true;
            if (binding.layoutRecordingBadge != null)
                binding.layoutRecordingBadge.setVisibility(View.VISIBLE);
            if (binding.tvRecordLabel != null) binding.tvRecordLabel.setText("Stop Rec");
            binding.btnRecord.setAlpha(1f);
            binding.btnRecord.setBackgroundResource(
                com.callx.app.calls.R.drawable.circle_reject_light);
            Toast.makeText(this, "Recording started", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Recording failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
            releaseRecorder();
        }
    }

    private void stopRecording() {
        try {
            if (mediaRecorder != null) {
                mediaRecorder.stop();
                String path = recordingFile != null ? recordingFile.getAbsolutePath() : "";
                Toast.makeText(this, "Saved: " + path, Toast.LENGTH_LONG).show();
            }
        } catch (Exception ignored) {}
        isRecording = false;
        releaseRecorder();
        if (binding.layoutRecordingBadge != null)
            binding.layoutRecordingBadge.setVisibility(View.GONE);
        if (binding.tvRecordLabel != null) binding.tvRecordLabel.setText("Record");
        if (binding.btnRecord != null) {
            binding.btnRecord.setAlpha(0.8f);
            binding.btnRecord.setBackgroundResource(
                com.callx.app.calls.R.drawable.circle_avatar_bg);
        }
    }

    private void releaseRecorder() {
        try { if (mediaRecorder != null) { mediaRecorder.release(); mediaRecorder = null; } }
        catch (Exception ignored) {}
    }

    // ══ Feature 2: Screen Share ════════════════════════════════════════════
    private void toggleScreenShare() {
        if (screenSharing) stopScreenShare();
        else               requestScreenShare();
    }

    private void requestScreenShare() {
        if (mpManager == null) return;
        startActivityForResult(mpManager.createScreenCaptureIntent(), REQ_SCREEN_CAPTURE);
    }

    private void startScreenShare(Intent data) {
        if (videoCapturer != null && capturerRunning) {
            try { videoCapturer.stopCapture(); } catch (Exception ignored) {}
            videoCapturer.dispose();
        }
        try {
            videoCapturer = new ScreenCapturerAndroid(data, new android.media.projection.MediaProjection.Callback() {
                @Override public void onStop() {
                    runOnUiThread(() -> stopScreenShare());
                }
            });
            videoCapturer.initialize(surfaceHelper, this, videoSource.getCapturerObserver());
            videoCapturer.startCapture(1280, 720, 30);
            capturerRunning = true;
            screenSharing = true;
            if (binding.tvScreenShareLabel != null)
                binding.tvScreenShareLabel.setText("Stop Share");
            if (binding.btnScreenShare != null)
                binding.btnScreenShare.setBackgroundResource(
                    com.callx.app.calls.R.drawable.circle_reject_light);
            Toast.makeText(this, "Screen sharing started", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Screen share failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void stopScreenShare() {
        screenSharing = false;
        if (videoCapturer != null && capturerRunning) {
            try { videoCapturer.stopCapture(); } catch (Exception ignored) {}
            videoCapturer.dispose();
            capturerRunning = false;
        }
        // Restore camera capturer
        if (isVideo) {
            videoCapturer = createCapturer(usingFrontCamera);
            if (videoCapturer != null && videoSource != null) {
                try {
                    videoCapturer.initialize(surfaceHelper, this,
                        videoSource.getCapturerObserver());
                    startCapture();
                } catch (Exception ignored) {}
            }
        }
        if (binding.tvScreenShareLabel != null)
            binding.tvScreenShareLabel.setText("Share");
        if (binding.btnScreenShare != null)
            binding.btnScreenShare.setBackgroundResource(
                com.callx.app.calls.R.drawable.circle_avatar_bg);
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req == REQ_SCREEN_CAPTURE) {
            if (result == RESULT_OK && data != null) startScreenShare(data);
            else Toast.makeText(this, "Screen share cancelled", Toast.LENGTH_SHORT).show();
        }
    }

    // ══ Feature 4: Background Blur ════════════════════════════════════════
    private void toggleBlur() {
        if (!isVideo) return;
        blurEnabled = !blurEnabled;

        if (blurEnabled) {
            // Init processor if needed
            if (blurProcessor == null && binding.textureBlurPreview != null) {
                blurProcessor = new BackgroundBlurProcessor(this, binding.textureBlurPreview);
                if (localVideoTrack != null) localVideoTrack.addSink(blurProcessor);
            }
            if (blurProcessor != null) blurProcessor.setEnabled(true);
            if (binding.textureBlurPreview != null)
                binding.textureBlurPreview.setVisibility(View.VISIBLE);
            binding.localVideo.setVisibility(View.INVISIBLE);
            if (binding.tvBlurLabel != null) binding.tvBlurLabel.setText("Blur On");
            if (binding.btnBlur != null)
                binding.btnBlur.setBackgroundResource(
                    com.callx.app.calls.R.drawable.circle_reject_light);
            Toast.makeText(this, "Background blur ON", Toast.LENGTH_SHORT).show();
        } else {
            if (blurProcessor != null) blurProcessor.setEnabled(false);
            if (binding.textureBlurPreview != null)
                binding.textureBlurPreview.setVisibility(View.GONE);
            binding.localVideo.setVisibility(View.VISIBLE);
            if (binding.tvBlurLabel != null) binding.tvBlurLabel.setText("Blur");
            if (binding.btnBlur != null)
                binding.btnBlur.setBackgroundResource(
                    com.callx.app.calls.R.drawable.circle_avatar_bg);
        }
    }

    // ══ Feature 5: Call Stats ═════════════════════════════════════════════
    private void toggleStats() {
        statsVisible = !statsVisible;
        if (binding.layoutStatsOverlay != null)
            binding.layoutStatsOverlay.setVisibility(statsVisible ? View.VISIBLE : View.GONE);
        if (statsVisible) startStatsPoll();
        else              stopStatsPoll();
    }

    private void startStatsPoll() {
        CallStatsHelper.reset();
        statsPollRunnable = new Runnable() {
            @Override public void run() {
                if (!statsVisible || peerConnection == null) return;
                CallStatsHelper.collect(peerConnection, (txKbps, rxKbps, loss, rttMs) ->
                    runOnUiThread(() -> {
                        if (binding.tvStatsBitrate != null)
                            binding.tvStatsBitrate.setText(
                                String.format(Locale.US, "↑%d ↓%d kbps", txKbps, rxKbps));
                        if (binding.tvStatsLoss != null)
                            binding.tvStatsLoss.setText(
                                String.format(Locale.US, "Loss: %.1f%%", loss));
                        if (binding.tvStatsRtt != null)
                            binding.tvStatsRtt.setText(
                                rttMs >= 0
                                    ? String.format(Locale.US, "RTT: %d ms", rttMs)
                                    : "RTT: — ms");
                    }));
                statsHandler.postDelayed(this, 3000);
            }
        };
        statsHandler.post(statsPollRunnable);
    }

    private void stopStatsPoll() {
        statsHandler.removeCallbacksAndMessages(null);
        statsPollRunnable = null;
    }

    // ══ Feature 3: E2E Badge ═══════════════════════════════════════════════
    private void showE2eBadge() {
        // WebRTC always uses DTLS-SRTP — show badge when connected
        if (binding.layoutE2eBadge != null)
            binding.layoutE2eBadge.setVisibility(View.VISIBLE);
    }

    // ── Adaptive Video ─────────────────────────────────────────────────────
    private void applyVideoBitrate(boolean lowQuality) {
        if (!isVideo || videoCapturer == null || !capturerRunning || screenSharing) return;
        if (lowQuality == usingLowQuality) return;
        usingLowQuality = lowQuality;
        try { videoCapturer.stopCapture(); capturerRunning = false; } catch (Exception ignored) {}
        int w = lowQuality ? Constants.VIDEO_WIDTH_VGA : Constants.VIDEO_WIDTH_HD;
        int h = lowQuality ? Constants.VIDEO_HEIGHT_VGA : Constants.VIDEO_HEIGHT_HD;
        try {
            videoCapturer.startCapture(w, h, Constants.VIDEO_FPS);
            capturerRunning = true;
        } catch (Exception ignored) {}
    }

    // ── Local video drag ───────────────────────────────────────────────────
    private void setupLocalVideoDrag() {
        if (!isVideo) return;
        View.OnTouchListener drag = new View.OnTouchListener() {
            float dX, dY;
            @Override public boolean onTouch(View v, android.view.MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        dX = v.getX() - event.getRawX();
                        dY = v.getY() - event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float nx = event.getRawX() + dX;
                        float ny = event.getRawY() + dY;
                        ViewGroup parent = (ViewGroup) v.getParent();
                        if (parent != null) {
                            nx = Math.max(0, Math.min(nx, parent.getWidth()  - v.getWidth()));
                            ny = Math.max(0, Math.min(ny, parent.getHeight() - v.getHeight()));
                        }
                        v.setX(nx); v.setY(ny);
                        return true;
                }
                return false;
            }
        };
        binding.localVideo.setOnTouchListener(drag);
        if (binding.textureBlurPreview != null)
            binding.textureBlurPreview.setOnTouchListener(drag);
    }

    // ── Wake lock ──────────────────────────────────────────────────────────
    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "callx:call_active");
            wakeLock.acquire(3 * 60 * 60 * 1000L);
        } catch (Exception ignored) {}
    }

    private void checkPermsAndInit() {
        List<String> needed = new ArrayList<>();
        needed.add(Manifest.permission.RECORD_AUDIO);
        if (isVideo) needed.add(Manifest.permission.CAMERA);
        boolean ok = true;
        for (String p : needed)
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) { ok = false; break; }
        if (!ok) ActivityCompat.requestPermissions(this,
            needed.toArray(new String[0]), 401);
        else      fetchTurnThenInitWebRTC();
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms,
                                           @NonNull int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == 401) {
            boolean ok = true;
            for (int r : results) if (r != PackageManager.PERMISSION_GRANTED) { ok = false; break; }
            if (ok) fetchTurnThenInitWebRTC();
            else { Toast.makeText(this, "Mic/Camera permission required", Toast.LENGTH_LONG).show(); finish(); }
        } else if (req == REQ_RECORD_AUDIO && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED) {
            startRecording();
        }
    }

    // ── TURN fetch ─────────────────────────────────────────────────────────
    private void fetchTurnThenInitWebRTC() {
        bgExecutor.execute(() -> {
            List<PeerConnection.IceServer> iceServers = buildFallbackIce();
            try {
                String url = Constants.SERVER_URL + Constants.TURN_CREDENTIALS_PATH;
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(5000); conn.setReadTimeout(5000);
                if (conn.getResponseCode() == 200) {
                    BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder(); String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    JSONObject j = new JSONObject(sb.toString());
                    String turnUrl = j.optString("url",""), user = j.optString("username",""),
                           cred = j.optString("credential","");
                    if (!turnUrl.isEmpty() && !user.isEmpty() && !cred.isEmpty()) {
                        iceServers.clear();
                        iceServers.add(PeerConnection.IceServer.builder(Constants.STUN_GOOGLE_1).createIceServer());
                        iceServers.add(PeerConnection.IceServer.builder(Constants.STUN_GOOGLE_2).createIceServer());
                        iceServers.add(PeerConnection.IceServer.builder(turnUrl)
                            .setUsername(user).setPassword(cred).createIceServer());
                    }
                }
            } catch (Exception ignored) {}
            final List<PeerConnection.IceServer> ice = iceServers;
            runOnUiThread(() -> initWebRTC(ice));
        });
    }

    private List<PeerConnection.IceServer> buildFallbackIce() {
        List<PeerConnection.IceServer> l = new ArrayList<>();
        l.add(PeerConnection.IceServer.builder(Constants.STUN_GOOGLE_1).createIceServer());
        l.add(PeerConnection.IceServer.builder(Constants.STUN_GOOGLE_2).createIceServer());
        return l;
    }

    // ── WebRTC init ────────────────────────────────────────────────────────
    private void initWebRTC(List<PeerConnection.IceServer> iceServers) {
        eglBase = EglBase.create();
        if (isVideo) {
            binding.remoteVideo.init(eglBase.getEglBaseContext(), null);
            binding.remoteVideo.setMirror(false);
            binding.localVideo.init(eglBase.getEglBaseContext(), null);
            binding.localVideo.setMirror(true);
        }

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(this).createInitializationOptions());

        factory = PeerConnectionFactory.builder()
            .setOptions(new PeerConnectionFactory.Options())
            .setVideoEncoderFactory(new DefaultVideoEncoderFactory(
                eglBase.getEglBaseContext(), true, true))
            .setVideoDecoderFactory(new DefaultVideoDecoderFactory(
                eglBase.getEglBaseContext()))
            .createPeerConnectionFactory();

        PeerConnection.RTCConfiguration cfg = new PeerConnection.RTCConfiguration(iceServers);
        cfg.sdpSemantics             = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        cfg.iceTransportsType        = PeerConnection.IceTransportsType.ALL;
        cfg.bundlePolicy             = PeerConnection.BundlePolicy.MAXBUNDLE;
        cfg.rtcpMuxPolicy            = PeerConnection.RtcpMuxPolicy.REQUIRE;
        cfg.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;

        peerConnection = factory.createPeerConnection(cfg, new PeerConnection.Observer() {
            @Override public void onIceCandidate(IceCandidate c) { sendCandidate(c); }
            @Override public void onIceCandidatesRemoved(IceCandidate[] c) {}
            @Override public void onSignalingChange(PeerConnection.SignalingState s) {}
            @Override public void onIceConnectionChange(PeerConnection.IceConnectionState s) {
                runOnUiThread(() -> handleIceStateChange(s));
            }
            @Override public void onIceConnectionReceivingChange(boolean b) {}
            @Override public void onIceGatheringChange(PeerConnection.IceGatheringState s) {}
            @Override public void onAddStream(MediaStream stream) {
                if (isVideo && !stream.videoTracks.isEmpty()) {
                    VideoTrack rv = stream.videoTracks.get(0);
                    rv.setEnabled(true);
                    runOnUiThread(() -> rv.addSink(binding.remoteVideo));
                }
            }
            @Override public void onRemoveStream(MediaStream stream) {}
            @Override public void onDataChannel(DataChannel dc) {}
            @Override public void onRenegotiationNeeded() {}
            @Override public void onAddTrack(RtpReceiver r, MediaStream[] streams) {
                if (isVideo && r.track() instanceof VideoTrack) {
                    VideoTrack t = (VideoTrack) r.track();
                    runOnUiThread(() -> { t.setEnabled(true); t.addSink(binding.remoteVideo); });
                }
            }
        });

        audioSource     = factory.createAudioSource(buildAudioConstraints());
        localAudioTrack = factory.createAudioTrack("audio0", audioSource);
        localAudioTrack.setEnabled(true);

        if (isVideo) {
            videoSource   = factory.createVideoSource(false);
            surfaceHelper = SurfaceTextureHelper.create("CaptureThread",
                eglBase.getEglBaseContext());
            videoCapturer = createCapturer(true);
            if (videoCapturer != null) {
                videoCapturer.initialize(surfaceHelper, this,
                    videoSource.getCapturerObserver());
                startCapture();
            }
            localVideoTrack = factory.createVideoTrack("video0", videoSource);
            localVideoTrack.setEnabled(true);
            localVideoTrack.addSink(binding.localVideo);
        }

        List<String> ids = new ArrayList<>(); ids.add("stream0");
        peerConnection.addTrack(localAudioTrack, ids);
        if (isVideo && localVideoTrack != null)
            peerConnection.addTrack(localVideoTrack, ids);

        enableSpeaker(isVideo);

        if (callId == null) {
            if (isCaller)
                callId = FirebaseUtils.db().getReference("activeCalls").push().getKey();
            else { finish(); return; }
        }
        callRef = FirebaseUtils.db().getReference("activeCalls").child(callId);
        callRef.child("status").onDisconnect().setValue("ended");

        if (isCaller) {
            String myUid = FirebaseUtils.getCurrentUid(), myName = FirebaseUtils.getCurrentName();
            Map<String, Object> c = new HashMap<>();
            c.put("from", myUid); c.put("fromName", myName);
            c.put("to", partnerUid); c.put("video", isVideo);
            c.put("at", System.currentTimeMillis()); c.put("status", "ringing");
            callRef.setValue(c).addOnCompleteListener(t -> createOffer());
            PushNotify.notifyUser(partnerUid, myUid, myName,
                isVideo ? "video_call" : "call", callId);
            watchCalleeIceRestartRequest();
        } else {
            callRef.child("status").setValue("accepted");
            watchForOffer();
        }

        watchCallStatus();
        watchRemoteCandidates();
        watchRemoteCameraState();
        registerNetworkCallback();
        registerNoisyReceiver();
        registerBtScoReceiver();
    }

    // ── Receivers ─────────────────────────────────────────────────────────
    private void registerNoisyReceiver() {
        noisyReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent intent) {
                if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())
                        && speakerOn) enableSpeaker(false);
            }
        };
        try {
            registerReceiver(noisyReceiver,
                new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
            noisyReceiverRegistered = true;
        } catch (Exception ignored) {}
    }

    private void registerBtScoReceiver() {
        btScoReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent intent) {
                int state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1);
                if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                    if (audioManager != null) audioManager.setBluetoothScoOn(true);
                    speakerOn = false;
                } else if (state == AudioManager.SCO_AUDIO_STATE_DISCONNECTED) {
                    if (audioManager != null) {
                        audioManager.setBluetoothScoOn(false);
                        audioManager.stopBluetoothSco();
                        enableSpeaker(false);
                    }
                }
            }
        };
        try {
            registerReceiver(btScoReceiver,
                new IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED));
            btScoReceiverRegistered = true;
        } catch (Exception ignored) {}
    }

    // ── Remote camera state ────────────────────────────────────────────────
    private void watchRemoteCameraState() {
        if (callRef == null || !isVideo) return;
        callRef.child("camState").child(partnerUid).addValueEventListener(
            new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot s) {
                    Boolean val = s.getValue(Boolean.class);
                    remoteCamOn = (val == null || val);
                    runOnUiThread(() -> {
                        if (binding.layoutRemoteCamOff != null)
                            binding.layoutRemoteCamOff.setVisibility(
                                remoteCamOn ? View.GONE : View.VISIBLE);
                    });
                }
                @Override public void onCancelled(DatabaseError e) {}
            });
    }

    // ── ICE state ─────────────────────────────────────────────────────────
    private void handleIceStateChange(PeerConnection.IceConnectionState s) {
        showQualityIndicator(s);

        if (s == PeerConnection.IceConnectionState.CONNECTED ||
            s == PeerConnection.IceConnectionState.COMPLETED) {
            cancelPendingIceRestart();
            onCallConnected();
            // Feature 3: E2E badge — always on when connected (DTLS-SRTP)
            showE2eBadge();
            // Adaptive: restore HD
            applyVideoBitrate(false);

        } else if (s == PeerConnection.IceConnectionState.DISCONNECTED) {
            if (callConnected) {
                binding.tvCallStatus.setText("Reconnecting...");
                applyVideoBitrate(true);   // Adaptive: reduce to VGA
                scheduleIceRestart();
            }
        } else if (s == PeerConnection.IceConnectionState.FAILED) {
            applyVideoBitrate(true);
            if (callConnected && iceRestartCount < Constants.ICE_MAX_RESTARTS)
                performIceRestart();
            else if (callConnected)
                runOnUiThread(this::endCall);
        } else if (s == PeerConnection.IceConnectionState.CLOSED) {
            if (!finishing) runOnUiThread(this::endCall);
        }
    }

    private void showQualityIndicator(PeerConnection.IceConnectionState s) {
        if (binding.layoutQuality == null) return;
        if (s == PeerConnection.IceConnectionState.CONNECTED ||
            s == PeerConnection.IceConnectionState.COMPLETED) {
            binding.layoutQuality.setVisibility(View.VISIBLE);
            if (binding.tvQualityLabel != null) binding.tvQualityLabel.setText("Good");
        } else if (s == PeerConnection.IceConnectionState.DISCONNECTED) {
            binding.layoutQuality.setVisibility(View.VISIBLE);
            if (binding.tvQualityLabel != null) binding.tvQualityLabel.setText("Weak");
        } else if (s == PeerConnection.IceConnectionState.FAILED ||
                   s == PeerConnection.IceConnectionState.CHECKING) {
            binding.layoutQuality.setVisibility(View.VISIBLE);
            if (binding.tvQualityLabel != null) binding.tvQualityLabel.setText("Poor");
        } else {
            binding.layoutQuality.setVisibility(View.GONE);
        }
    }

    private void scheduleIceRestart() {
        cancelPendingIceRestart();
        iceRestartRunnable = () -> { if (!finishing && peerConnection != null) performIceRestart(); };
        iceRestartHandler.postDelayed(iceRestartRunnable, 4_000);
    }

    private void performIceRestart() {
        if (peerConnection == null || finishing) return;
        iceRestartCount++;
        if (isCaller) {
            MediaConstraints mc = new MediaConstraints();
            mc.mandatory.add(new MediaConstraints.KeyValuePair("IceRestart", "true"));
            peerConnection.createOffer(new SdpObserver() {
                @Override public void onCreateSuccess(SessionDescription sdp) {
                    peerConnection.setLocalDescription(noopSdp(), sdp);
                    if (callRef != null) {
                        Map<String, Object> o = new HashMap<>();
                        o.put("type", sdp.type.canonicalForm());
                        o.put("sdp",  sdp.description);
                        callRef.child("offer").setValue(o);
                    }
                }
                @Override public void onSetSuccess() {}
                @Override public void onCreateFailure(String e) {}
                @Override public void onSetFailure(String e) {}
            }, mc);
        } else {
            if (callRef != null)
                callRef.child("iceRestartRequest").setValue(System.currentTimeMillis());
        }
    }

    private void cancelPendingIceRestart() {
        if (iceRestartRunnable != null) {
            iceRestartHandler.removeCallbacks(iceRestartRunnable);
            iceRestartRunnable = null;
        }
    }

    private void watchCalleeIceRestartRequest() {
        iceRestartRequestListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot s) {
                Long ts = s.getValue(Long.class);
                if (ts != null && ts > 0)
                    runOnUiThread(() -> { iceRestartCount = 0; scheduleIceRestart(); });
            }
            @Override public void onCancelled(DatabaseError e) {}
        };
        callRef.child("iceRestartRequest").addValueEventListener(iceRestartRequestListener);
    }

    // ── Firebase signaling ─────────────────────────────────────────────────
    private void createOffer() {
        if (peerConnection == null) return;
        peerConnection.createOffer(new SdpObserver() {
            @Override public void onCreateSuccess(SessionDescription sdp) {
                peerConnection.setLocalDescription(noopSdp(), sdp);
                if (callRef == null) return;
                Map<String, Object> o = new HashMap<>();
                o.put("type", sdp.type.canonicalForm());
                o.put("sdp",  sdp.description);
                callRef.child("offer").setValue(o);
            }
            @Override public void onSetSuccess() {}
            @Override public void onCreateFailure(String e) {}
            @Override public void onSetFailure(String e) {}
        }, new MediaConstraints());
    }

    private void watchForOffer() {
        if (callRef == null) return;
        callRef.child("offer").addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot s) {
                if (!s.exists()) {
                    callRef.child("offer").addValueEventListener(new ValueEventListener() {
                        @Override public void onDataChange(DataSnapshot s2) {
                            if (s2.exists()) {
                                callRef.child("offer").removeEventListener(this);
                                handleOffer(s2);
                            }
                        }
                        @Override public void onCancelled(DatabaseError e) {}
                    });
                } else handleOffer(s);
            }
            @Override public void onCancelled(DatabaseError e) {}
        });
    }

    private void handleOffer(DataSnapshot s) {
        String type = s.child("type").getValue(String.class);
        String sdp  = s.child("sdp").getValue(String.class);
        if (type == null || sdp == null) return;
        peerConnection.setRemoteDescription(new SdpObserver() {
            @Override public void onSetSuccess() {
                remoteDescSet = true;
                drainPendingCandidates();
                runOnUiThread(CallActivity.this::createAnswer);
            }
            @Override public void onCreateSuccess(SessionDescription s2) {}
            @Override public void onCreateFailure(String e) {}
            @Override public void onSetFailure(String e) {}
        }, new SessionDescription(SessionDescription.Type.fromCanonicalForm(type), sdp));
    }

    private void createAnswer() {
        if (peerConnection == null) return;
        peerConnection.createAnswer(new SdpObserver() {
            @Override public void onCreateSuccess(SessionDescription sdp) {
                peerConnection.setLocalDescription(noopSdp(), sdp);
                if (callRef == null) return;
                Map<String, Object> a = new HashMap<>();
                a.put("type", sdp.type.canonicalForm());
                a.put("sdp",  sdp.description);
                callRef.child("answer").setValue(a);
            }
            @Override public void onSetSuccess() {}
            @Override public void onCreateFailure(String e) {}
            @Override public void onSetFailure(String e) {}
        }, new MediaConstraints());
    }

    private void watchCallStatus() {
        statusListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot s) {
                String st = s.getValue(String.class);
                if ("ended".equals(st) || "rejected".equals(st)
                        || "cancelled".equals(st) || "busy".equals(st)) {
                    if (!callConnected && isCaller) {
                        if ("busy".equals(st)) {
                            runOnUiThread(() -> binding.tvCallStatus.setText(
                                (partnerName != null ? partnerName : "Contact") +
                                " is on another call"));
                        } else if ("rejected".equals(st) || "cancelled".equals(st)) {
                            String myUid = FirebaseUtils.getCurrentUid();
                            if (myUid != null)
                                PushNotify.notifyMissedCall(myUid,
                                    partnerUid  != null ? partnerUid  : "",
                                    partnerName != null ? partnerName : "",
                                    callId      != null ? callId      : "",
                                    isVideo,
                                    partnerPhoto != null ? partnerPhoto : "");
                        }
                    }
                    runOnUiThread(() -> endCall());
                }
            }
            @Override public void onCancelled(DatabaseError e) {}
        };
        callRef.child("status").addValueEventListener(statusListener);
    }

    private void watchRemoteCandidates() {
        if (callRef == null) return;
        remoteCandidateListener = new ChildEventListener() {
            @Override public void onChildAdded(DataSnapshot s, String prev) {
                String sdpMid = s.child("sdpMid").getValue(String.class);
                String cand   = s.child("candidate").getValue(String.class);
                if (sdpMid == null || cand == null) return;
                int idx = 0;
                try { String ml = s.child("sdpMLineIndex").getValue(String.class);
                      if (ml != null) idx = Integer.parseInt(ml); }
                catch (Exception ignored) {}
                IceCandidate ic = new IceCandidate(sdpMid, idx, cand);
                if (remoteDescSet) peerConnection.addIceCandidate(ic);
                else               pendingCandidates.add(ic);
            }
            @Override public void onChildChanged(DataSnapshot s, String p) {}
            @Override public void onChildRemoved(DataSnapshot s) {}
            @Override public void onChildMoved(DataSnapshot s, String p) {}
            @Override public void onCancelled(DatabaseError e) {}
        };
        String myUid = FirebaseUtils.getCurrentUid();
        if (myUid == null) return;
        callRef.child("candidates").child(myUid).addChildEventListener(remoteCandidateListener);
    }

    private void drainPendingCandidates() {
        if (peerConnection == null) return;
        for (IceCandidate ic : pendingCandidates) peerConnection.addIceCandidate(ic);
        pendingCandidates.clear();
    }

    private void sendCandidate(IceCandidate c) {
        if (callRef == null) return;
        String myUid = FirebaseUtils.getCurrentUid();
        if (myUid == null) return;
        Map<String, Object> m = new HashMap<>();
        m.put("sdpMid",        c.sdpMid);
        m.put("sdpMLineIndex", String.valueOf(c.sdpMLineIndex));
        m.put("candidate",     c.sdp);
        callRef.child("candidates").child(partnerUid).push().setValue(m);
    }

    private void registerNetworkCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
        ConnectivityManager cm =
            (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network net) {
                runOnUiThread(() -> { if (callConnected) scheduleIceRestart(); });
            }
        };
        cm.registerNetworkCallback(
            new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
            networkCallback);
    }

    private void unregisterNetworkCallback() {
        if (networkCallback == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
        try {
            ConnectivityManager cm =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) cm.unregisterNetworkCallback(networkCallback);
        } catch (Exception ignored) {}
    }

    // ── Call connected ─────────────────────────────────────────────────────
    private void onCallConnected() {
        if (callConnected) return;
        callConnected = true;
        iceRestartCount = 0;
        startedAt = System.currentTimeMillis();

        // Cancel call timeout
        if (timeoutRunnable != null) {
            timeoutHandler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }

        binding.tvCallStatus.setText("Connected \u2022 0:00");
        if (isVideo) binding.ivCallAvatar.setVisibility(View.GONE);

        // Start foreground service
        Intent fg = new Intent(this, CallForegroundService.class);
        fg.putExtra("name",         partnerName  != null ? partnerName  : "");
        fg.putExtra("callId",       callId       != null ? callId       : "");
        fg.putExtra("isVideo",      isVideo);
        fg.putExtra("partnerThumb", partnerThumb != null ? partnerThumb : "");
        fg.putExtra(CallForegroundService.EXTRA_PARTNER_PHOTO,
            partnerPhoto != null ? partnerPhoto : "");
        fg.putExtra(CallForegroundService.EXTRA_IS_CALLER, isCaller);
        fg.putExtra(CallForegroundService.EXTRA_PARTNER_UID,
            partnerUid != null ? partnerUid : "");
        fg.putExtra(CallForegroundService.EXTRA_DIRECTION,
            isCaller ? "outgoing" : "incoming");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(fg);
        else startService(fg);

        // Call duration ticker
        ticker = new Runnable() {
            @Override public void run() {
                long e = (System.currentTimeMillis() - startedAt) / 1000;
                binding.tvCallStatus.setText(
                    String.format(Locale.US, "Connected \u2022 %d:%02d", e / 60, e % 60));
                tick.postDelayed(this, 1_000);
            }
        };
        tick.post(ticker);
    }

    // ── Controls ───────────────────────────────────────────────────────────
    private void switchCamera() {
        if (!isVideo || videoCapturer == null || screenSharing) return;
        usingFrontCamera = !usingFrontCamera;
        if (videoCapturer instanceof CameraVideoCapturer)
            ((CameraVideoCapturer) videoCapturer).switchCamera(null);
        binding.localVideo.setMirror(usingFrontCamera);
    }

    private void toggleSpeaker() {
        speakerOn = !speakerOn;
        enableSpeaker(speakerOn);
    }

    private void enableSpeaker(boolean on) {
        if (audioManager == null) return;
        speakerOn = on;
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        audioManager.setSpeakerphoneOn(on);
        binding.btnToggleSpeaker.setAlpha(on ? 1f : 0.5f);
        if (binding.tvSpeakerLabel != null)
            binding.tvSpeakerLabel.setText(on ? "Speaker On" : "Speaker");
    }

    private void toggleMic() {
        micOn = !micOn;
        if (localAudioTrack != null) localAudioTrack.setEnabled(micOn);
        updateMicUI();
        if (callRef != null) {
            String myUid = FirebaseUtils.getCurrentUid();
            if (myUid != null) callRef.child("micState").child(myUid).setValue(micOn);
        }
    }

    private void updateMicUI() {
        binding.btnToggleMic.setAlpha(micOn ? 1f : 0.4f);
        binding.btnToggleMic.setImageResource(micOn
            ? com.callx.app.calls.R.drawable.ic_mic
            : com.callx.app.calls.R.drawable.ic_mic_off);
        if (binding.tvMicLabel != null)
            binding.tvMicLabel.setText(micOn ? "Mute" : "Unmute");
        binding.btnToggleMic.setBackgroundResource(micOn
            ? com.callx.app.calls.R.drawable.circle_avatar_bg
            : com.callx.app.calls.R.drawable.circle_reject_light);
    }

    private void toggleCamera() {
        camOn = !camOn;
        if (localVideoTrack != null) localVideoTrack.setEnabled(camOn);
        if (isVideo && videoCapturer != null && !screenSharing) {
            if (!camOn && capturerRunning) {
                try { videoCapturer.stopCapture(); capturerRunning = false; }
                catch (Exception ignored) {}
            } else if (camOn && !capturerRunning) startCapture();
        }
        if (binding.layoutLocalCamOffBadge != null)
            binding.layoutLocalCamOffBadge.setVisibility(camOn ? View.GONE : View.VISIBLE);
        updateCameraUI();
        if (callRef != null) {
            String myUid = FirebaseUtils.getCurrentUid();
            if (myUid != null) callRef.child("camState").child(myUid).setValue(camOn);
        }
    }

    private void updateCameraUI() {
        if (!isVideo) return;
        binding.btnToggleCamera.setAlpha(camOn ? 1f : 0.4f);
        binding.btnToggleCamera.setImageResource(camOn
            ? com.callx.app.calls.R.drawable.ic_video
            : com.callx.app.calls.R.drawable.ic_video_off);
        if (binding.tvCameraLabel != null)
            binding.tvCameraLabel.setText(camOn ? "Camera" : "Cam Off");
        binding.btnToggleCamera.setBackgroundResource(camOn
            ? com.callx.app.calls.R.drawable.circle_avatar_bg
            : com.callx.app.calls.R.drawable.circle_reject_light);
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────
    @Override protected void onStop() {
        super.onStop();
        if (isVideo && videoCapturer != null && capturerRunning && !screenSharing
                && !isInPictureInPictureMode()) {
            try { videoCapturer.stopCapture(); capturerRunning = false; }
            catch (Exception ignored) {}
        }
    }

    @Override protected void onStart() {
        super.onStart();
        if (isVideo && videoCapturer != null && !capturerRunning && camOn && !screenSharing)
            startCapture();
    }

    @Override protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (isVideo && callConnected && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            enterPipMode();
    }

    private void enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                enterPictureInPictureMode(new PictureInPictureParams.Builder()
                    .setAspectRatio(new Rational(9, 16)).build());
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean pip) {
        super.onPictureInPictureModeChanged(pip);
        int v = pip ? View.GONE : View.VISIBLE;
        binding.btnEndCall.setVisibility(v);
        binding.btnToggleMic.setVisibility(v);
        binding.btnToggleCamera.setVisibility(v);
        binding.btnToggleSpeaker.setVisibility(v);
        binding.btnSwitchCamera.setVisibility(v);
        binding.tvCallerName.setVisibility(v);
        binding.tvCallStatus.setVisibility(v);
        if (binding.layoutExtraControls != null) binding.layoutExtraControls.setVisibility(v);
        if (!pip && isVideo && videoCapturer != null && !capturerRunning && camOn && !screenSharing)
            startCapture();
    }

    // ── Camera helpers ─────────────────────────────────────────────────────
    private void startCapture() {
        if (videoCapturer == null || videoSource == null) return;
        try {
            videoCapturer.startCapture(Constants.VIDEO_WIDTH_HD, Constants.VIDEO_HEIGHT_HD,
                Constants.VIDEO_FPS);
            capturerRunning = true;
        } catch (Exception e) {
            try {
                videoCapturer.startCapture(Constants.VIDEO_WIDTH_VGA, Constants.VIDEO_HEIGHT_VGA,
                    Constants.VIDEO_FPS);
                capturerRunning = true;
            } catch (Exception ignored) {}
        }
    }

    private CameraVideoCapturer createCapturer(boolean preferFront) {
        Camera2Enumerator e = new Camera2Enumerator(this);
        for (String d : e.getDeviceNames())
            if (preferFront ? e.isFrontFacing(d) : e.isBackFacing(d))
                return e.createCapturer(d, null);
        for (String d : e.getDeviceNames()) return e.createCapturer(d, null);
        return null;
    }

    private MediaConstraints buildAudioConstraints() {
        MediaConstraints mc = new MediaConstraints();
        mc.mandatory.add(new MediaConstraints.KeyValuePair("googEchoCancellation", "true"));
        mc.mandatory.add(new MediaConstraints.KeyValuePair("googNoiseSuppression", "true"));
        mc.mandatory.add(new MediaConstraints.KeyValuePair("googAutoGainControl",  "true"));
        mc.mandatory.add(new MediaConstraints.KeyValuePair("googHighpassFilter",   "true"));
        mc.mandatory.add(new MediaConstraints.KeyValuePair("googTypingNoiseDetection", "true"));
        return mc;
    }

    // ── End call ───────────────────────────────────────────────────────────
    private void endCall() {
        if (finishing) return;
        finishing = true;

        cancelPendingIceRestart();
        stopStatsPoll();
        if (ticker != null) tick.removeCallbacks(ticker);
        if (timeoutRunnable != null) { timeoutHandler.removeCallbacks(timeoutRunnable); }
        if (isRecording) stopRecording();
        if (blurProcessor != null) { blurProcessor.release(); blurProcessor = null; }

        try { stopService(new Intent(this, CallForegroundService.class)); }
        catch (Exception ignored) {}

        if (callRef != null) {
            long dur = startedAt == 0 ? 0 : System.currentTimeMillis() - startedAt;
            callRef.child("status").setValue("ended");
            callRef.child("status").onDisconnect().cancel();
            if (isVideo) {
                String myUid = FirebaseUtils.getCurrentUid();
                if (myUid != null) callRef.child("camState").child(myUid).removeValue();
            }
            String myUid = FirebaseUtils.getCurrentUid();
            if (myUid != null) {
                final long fDur = dur, fTs = System.currentTimeMillis();
                final String fType = isVideo ? "video" : "audio";

                Map<String, Object> myLog = new HashMap<>();
                myLog.put("partnerUid",  partnerUid);
                myLog.put("partnerName", partnerName);
                myLog.put("direction",   isCaller ? "outgoing" : "incoming");
                myLog.put("mediaType",   fType);
                myLog.put("timestamp",   fTs);
                myLog.put("duration",    fDur);
                FirebaseUtils.getCallsRef(myUid).push().setValue(myLog);

                if (partnerUid != null && !partnerUid.isEmpty()) {
                    String myName = FirebaseUtils.getCurrentName();
                    Map<String, Object> pl = new HashMap<>();
                    pl.put("partnerUid",  myUid);
                    pl.put("partnerName", myName != null ? myName : "");
                    pl.put("direction",   isCaller ? "incoming" : "outgoing");
                    pl.put("mediaType",   fType);
                    pl.put("timestamp",   fTs);
                    pl.put("duration",    fDur);
                    FirebaseUtils.getCallsRef(partnerUid).push().setValue(pl);
                }

                bgExecutor.execute(() -> {
                    try {
                        CallLogEntity entity = new CallLogEntity();
                        entity.id          = java.util.UUID.randomUUID().toString();
                        entity.partnerUid  = partnerUid;
                        entity.partnerName = partnerName;
                        entity.direction   = isCaller ? "outgoing" : "incoming";
                        entity.mediaType   = fType;
                        entity.timestamp   = fTs;
                        entity.duration    = fDur;
                        AppDatabase.getInstance(getApplicationContext())
                            .callLogDao().insertCallLog(entity);
                    } catch (Exception ex) {
                        android.util.Log.w("CallActivity", "Room log failed", ex);
                    }
                });
            }
        }

        if (audioManager != null) {
            audioManager.setSpeakerphoneOn(false);
            audioManager.setMode(AudioManager.MODE_NORMAL);
        }
        releaseWebRTC();
        finish();
    }

    private void releaseWebRTC() {
        try { unregisterNetworkCallback(); } catch (Exception ignored) {}
        if (noisyReceiverRegistered && noisyReceiver != null) {
            try { unregisterReceiver(noisyReceiver); } catch (Exception ignored) {}
            noisyReceiverRegistered = false;
        }
        if (btScoReceiverRegistered && btScoReceiver != null) {
            try { unregisterReceiver(btScoReceiver); } catch (Exception ignored) {}
            btScoReceiverRegistered = false;
        }
        try {
            if (remoteCandidateListener != null && callRef != null) {
                String myUid = FirebaseUtils.getCurrentUid();
                if (myUid != null)
                    callRef.child("candidates").child(myUid)
                        .removeEventListener(remoteCandidateListener);
            }
            if (statusListener != null && callRef != null)
                callRef.child("status").removeEventListener(statusListener);
            if (iceRestartRequestListener != null && callRef != null)
                callRef.child("iceRestartRequest")
                    .removeEventListener(iceRestartRequestListener);
            if (videoCapturer != null) {
                try { videoCapturer.stopCapture(); } catch (Exception ignored) {}
                videoCapturer.dispose();
            }
            if (localVideoTrack  != null) localVideoTrack.dispose();
            if (localAudioTrack  != null) localAudioTrack.dispose();
            if (videoSource      != null) videoSource.dispose();
            if (audioSource      != null) audioSource.dispose();
            if (peerConnection   != null) { peerConnection.close(); peerConnection.dispose(); }
            if (surfaceHelper    != null) surfaceHelper.dispose();
            if (isVideo) {
                try { binding.localVideo.release();  } catch (Exception ignored) {}
                try { binding.remoteVideo.release(); } catch (Exception ignored) {}
            }
            if (eglBase  != null) eglBase.release();
            if (factory  != null) factory.dispose();
        } catch (Exception ignored) {}
        try { bgExecutor.shutdownNow(); } catch (Exception ignored) {}
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); }
        catch (Exception ignored) {}
    }

    private SdpObserver noopSdp() {
        return new SdpObserver() {
            @Override public void onCreateSuccess(SessionDescription s) {}
            @Override public void onSetSuccess() {}
            @Override public void onCreateFailure(String e) {}
            @Override public void onSetFailure(String e) {}
        };
    }

    @Override
    protected void onDestroy() {
        if (!finishing) releaseWebRTC();
        if (ticker != null) tick.removeCallbacks(ticker);
        cancelPendingIceRestart();
        if (timeoutRunnable != null) timeoutHandler.removeCallbacks(timeoutRunnable);
        stopStatsPoll();
        super.onDestroy();
    }
}
