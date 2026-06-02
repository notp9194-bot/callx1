package com.callx.app.activities;

import android.Manifest;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Rational;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import com.bumptech.glide.Glide;
import com.callx.app.calls.databinding.ActivityCallBinding;
import com.callx.app.services.CallForegroundService;
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
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.CallLogEntity;

public class CallActivity extends AppCompatActivity {

    // UI
    private ActivityCallBinding binding;

    // Call params
    private String partnerUid, partnerName, partnerPhoto, partnerThumb, callId;
    private boolean isCaller, isVideo;
    // FIX: micOn/camOn track icon + label + alpha
    private boolean micOn = true, camOn = true, speakerOn = false;
    private boolean usingFrontCamera = true;
    private long startedAt = 0;
    private boolean callConnected = false;
    private boolean finishing = false;
    // FIX: track remote camera state for overlay
    private boolean remoteCamOn = true;

    // Timer
    private final Handler tick = new Handler(Looper.getMainLooper());
    private Runnable ticker;

    // ICE reconnection
    private int iceRestartCount = 0;
    private final Handler iceRestartHandler = new Handler(Looper.getMainLooper());
    private Runnable iceRestartRunnable;
    private ValueEventListener iceRestartRequestListener;

    // FIX-WAKE: SCREEN_BRIGHT_WAKE_LOCK keeps screen on during active call
    private PowerManager.WakeLock wakeLock;

    // Audio
    private AudioManager audioManager;

    // WebRTC
    private EglBase eglBase;
    private PeerConnectionFactory factory;
    private PeerConnection peerConnection;
    private VideoTrack localVideoTrack;
    private AudioTrack localAudioTrack;
    private CameraVideoCapturer videoCapturer;
    private VideoSource videoSource;
    private AudioSource audioSource;
    private SurfaceTextureHelper surfaceHelper;
    private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor();

    // FIX-BG: track capturer state for background pause/resume
    private boolean capturerRunning = false;

    // Firebase signaling
    private DatabaseReference callRef;
    private ValueEventListener statusListener;
    private ChildEventListener remoteCandidateListener;
    private boolean remoteDescSet = false;
    private final List<IceCandidate> pendingCandidates = new ArrayList<>();

    // Network callback
    private ConnectivityManager.NetworkCallback networkCallback;

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

        if (partnerUid == null) { finish(); return; }

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
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
        }

        String callAvatarUrl = (partnerThumb != null && !partnerThumb.isEmpty()) ? partnerThumb : partnerPhoto;
        if (callAvatarUrl != null && !callAvatarUrl.isEmpty()) {
            Glide.with(this).load(callAvatarUrl).circleCrop().into(binding.ivCallAvatar);
            // FIX: also load into remote cam off overlay avatar
            if (binding.ivRemoteCamOffAvatar != null) {
                Glide.with(this).load(callAvatarUrl).circleCrop()
                    .into(binding.ivRemoteCamOffAvatar);
            }
        }

        binding.btnEndCall.setOnClickListener(v -> endCall());
        binding.btnToggleMic.setOnClickListener(v -> toggleMic());
        binding.btnToggleCamera.setOnClickListener(v -> toggleCamera());
        binding.btnSwitchCamera.setOnClickListener(v -> switchCamera());
        binding.btnToggleSpeaker.setOnClickListener(v -> toggleSpeaker());

        // FIX: initial button visual state
        updateMicUI();
        updateCameraUI();

        checkPermsAndInit();
    }

    // ── FIX-WAKE: Use SCREEN_BRIGHT_WAKE_LOCK so screen stays ON during video call ──
    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            // FIX: SCREEN_BRIGHT_WAKE_LOCK keeps display on (was PARTIAL_WAKE_LOCK — only CPU)
            wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "callx:call_active");
            wakeLock.acquire(3 * 60 * 60 * 1000L);
        } catch (Exception ignored) {}
    }

    private void checkPermsAndInit() {
        String[] perms = isVideo
            ? new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA}
            : new String[]{Manifest.permission.RECORD_AUDIO};
        boolean ok = true;
        for (String p : perms)
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) { ok = false; break; }
        if (!ok) ActivityCompat.requestPermissions(this, perms, 401);
        else      fetchTurnThenInitWebRTC();
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms, @NonNull int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == 401) {
            boolean ok = true;
            for (int r : results) if (r != PackageManager.PERMISSION_GRANTED) { ok = false; break; }
            if (ok) fetchTurnThenInitWebRTC();
            else { Toast.makeText(this, "Mic/Camera permission required", Toast.LENGTH_LONG).show(); finish(); }
        }
    }

    // ── TURN credential fetch ──────────────────────────────────────────────

    private void fetchTurnThenInitWebRTC() {
        bgExecutor.execute(() -> {
            List<PeerConnection.IceServer> iceServers = buildFallbackIce();
            try {
                String url = Constants.SERVER_URL + Constants.TURN_CREDENTIALS_PATH;
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setRequestMethod("GET");
                if (conn.getResponseCode() == 200) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    JSONObject j = new JSONObject(sb.toString());
                    String turnUrl = j.optString("url", "");
                    String user    = j.optString("username", "");
                    String cred    = j.optString("credential", "");
                    if (!turnUrl.isEmpty() && !user.isEmpty() && !cred.isEmpty()) {
                        iceServers.clear();
                        iceServers.add(PeerConnection.IceServer.builder(Constants.STUN_GOOGLE_1).createIceServer());
                        iceServers.add(PeerConnection.IceServer.builder(Constants.STUN_GOOGLE_2).createIceServer());
                        iceServers.add(PeerConnection.IceServer.builder(turnUrl)
                            .setUsername(user).setPassword(cred).createIceServer());
                    }
                }
            } catch (Exception ignored) {}
            final List<PeerConnection.IceServer> finalIce = iceServers;
            runOnUiThread(() -> initWebRTC(finalIce));
        });
    }

    private List<PeerConnection.IceServer> buildFallbackIce() {
        List<PeerConnection.IceServer> list = new ArrayList<>();
        list.add(PeerConnection.IceServer.builder(Constants.STUN_GOOGLE_1).createIceServer());
        list.add(PeerConnection.IceServer.builder(Constants.STUN_GOOGLE_2).createIceServer());
        return list;
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
            .setVideoEncoderFactory(new DefaultVideoEncoderFactory(eglBase.getEglBaseContext(), true, true))
            .setVideoDecoderFactory(new DefaultVideoDecoderFactory(eglBase.getEglBaseContext()))
            .createPeerConnectionFactory();

        PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(iceServers);
        rtcConfig.sdpSemantics             = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        rtcConfig.iceTransportsType        = PeerConnection.IceTransportsType.ALL;
        rtcConfig.bundlePolicy             = PeerConnection.BundlePolicy.MAXBUNDLE;
        rtcConfig.rtcpMuxPolicy            = PeerConnection.RtcpMuxPolicy.REQUIRE;
        rtcConfig.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;

        peerConnection = factory.createPeerConnection(rtcConfig, new PeerConnection.Observer() {
            @Override public void onIceCandidate(IceCandidate c) { sendCandidate(c); }
            @Override public void onIceCandidatesRemoved(IceCandidate[] c) {}
            @Override public void onSignalingChange(PeerConnection.SignalingState s) {}
            @Override public void onIceConnectionChange(PeerConnection.IceConnectionState s) {
                runOnUiThread(() -> handleIceStateChange(s));
            }
            @Override public void onIceConnectionReceivingChange(boolean b) {}
            @Override public void onIceGatheringChange(PeerConnection.IceGatheringState s) {}
            @Override public void onAddStream(MediaStream stream) {
                runOnUiThread(() -> {
                    if (isVideo && !stream.videoTracks.isEmpty()) {
                        VideoTrack rv = stream.videoTracks.get(0);
                        rv.setEnabled(true);
                        rv.addSink(binding.remoteVideo);
                    }
                });
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

        audioSource = factory.createAudioSource(buildAudioConstraints());
        localAudioTrack = factory.createAudioTrack("audio0", audioSource);
        localAudioTrack.setEnabled(true);

        if (isVideo) {
            videoSource   = factory.createVideoSource(false);
            surfaceHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.getEglBaseContext());
            videoCapturer = createCapturer(true);
            if (videoCapturer != null) {
                videoCapturer.initialize(surfaceHelper, this, videoSource.getCapturerObserver());
                startCapture();
            }
            localVideoTrack = factory.createVideoTrack("video0", videoSource);
            localVideoTrack.setEnabled(true);
            localVideoTrack.addSink(binding.localVideo);
        }

        List<String> ids = new ArrayList<>();
        ids.add("stream0");
        peerConnection.addTrack(localAudioTrack, ids);
        if (isVideo && localVideoTrack != null) peerConnection.addTrack(localVideoTrack, ids);

        if (isVideo) enableSpeaker(true);
        else         enableSpeaker(false);

        if (callId == null) {
            if (isCaller) {
                callId = FirebaseUtils.db().getReference("activeCalls").push().getKey();
            } else {
                android.util.Log.e("CallActivity", "Callee callId is null — FCM issue. Aborting.");
                finish(); return;
            }
        }
        callRef = FirebaseUtils.db().getReference("activeCalls").child(callId);

        // FIX-KILLED: onDisconnect auto-cleans Firebase if device loses connection/app killed
        callRef.child("status").onDisconnect().setValue("ended");

        if (isCaller) {
            String myUid  = FirebaseUtils.getCurrentUid();
            String myName = FirebaseUtils.getCurrentName();
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
        // FIX: watch remote camera state signal from Firebase
        watchRemoteCameraState();
        registerNetworkCallback();
    }

    // ── FIX: Watch remote party's camera state via Firebase signal ──────────
    private void watchRemoteCameraState() {
        if (callRef == null || !isVideo) return;
        callRef.child("camState").child(partnerUid).addValueEventListener(
            new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot s) {
                    Boolean camOn = s.getValue(Boolean.class);
                    remoteCamOn = (camOn == null || camOn);
                    runOnUiThread(() -> updateRemoteCamOverlay());
                }
                @Override public void onCancelled(DatabaseError e) {}
            });
    }

    private void updateRemoteCamOverlay() {
        if (binding.layoutRemoteCamOff == null) return;
        binding.layoutRemoteCamOff.setVisibility(remoteCamOn ? View.GONE : View.VISIBLE);
    }

    // ── FIX: Network quality indicator ─────────────────────────────────────
    private void showQualityIndicator(PeerConnection.IceConnectionState state) {
        if (binding.layoutQuality == null) return;
        if (state == PeerConnection.IceConnectionState.CONNECTED ||
            state == PeerConnection.IceConnectionState.COMPLETED) {
            binding.layoutQuality.setVisibility(View.VISIBLE);
            if (binding.tvQualityLabel != null) binding.tvQualityLabel.setText("Good");
        } else if (state == PeerConnection.IceConnectionState.DISCONNECTED) {
            binding.layoutQuality.setVisibility(View.VISIBLE);
            if (binding.tvQualityLabel != null) binding.tvQualityLabel.setText("Weak");
        } else {
            binding.layoutQuality.setVisibility(View.GONE);
        }
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

    private void startCapture() {
        if (videoCapturer == null) return;
        try {
            videoCapturer.startCapture(Constants.VIDEO_WIDTH_HD, Constants.VIDEO_HEIGHT_HD, Constants.VIDEO_FPS);
            capturerRunning = true;
        } catch (Exception e) {
            try {
                videoCapturer.startCapture(Constants.VIDEO_WIDTH_VGA, Constants.VIDEO_HEIGHT_VGA, Constants.VIDEO_FPS);
                capturerRunning = true;
            } catch (Exception ignored) {}
        }
    }

    private CameraVideoCapturer createCapturer(boolean preferFront) {
        Camera2Enumerator e = new Camera2Enumerator(this);
        String[] devices = e.getDeviceNames();
        for (String d : devices)
            if (preferFront ? e.isFrontFacing(d) : e.isBackFacing(d))
                return e.createCapturer(d, null);
        for (String d : devices) return e.createCapturer(d, null);
        return null;
    }

    // ── FIX-BG: Pause camera when app goes to background ──────────────────

    @Override
    protected void onStop() {
        super.onStop();
        // FIX: Stop camera capture when app is backgrounded (saves battery + hides green dot)
        if (isVideo && videoCapturer != null && capturerRunning && !isInPictureInPictureMode()) {
            try { videoCapturer.stopCapture(); capturerRunning = false; } catch (Exception ignored) {}
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        // FIX: Resume camera capture when app returns to foreground
        if (isVideo && videoCapturer != null && !capturerRunning && camOn) {
            startCapture();
        }
    }

    // ── FIX-PIP: Picture-in-Picture mode when Home button pressed ──────────

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        // FIX: Enter PiP when user presses Home during video call
        if (isVideo && callConnected && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            enterPipMode();
        }
    }

    private void enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder();
                builder.setAspectRatio(new Rational(9, 16));
                enterPictureInPictureMode(builder.build());
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPipMode) {
        super.onPictureInPictureModeChanged(isInPipMode);
        // FIX: Hide controls in PiP mode, show only video feeds
        View controls = binding.getRoot().findViewById(
            com.callx.app.calls.R.id.btn_end_call);
        if (controls != null) {
            // Hide all bottom controls in PiP
            binding.btnEndCall.setVisibility(isInPipMode ? View.GONE : View.VISIBLE);
            binding.btnToggleMic.setVisibility(isInPipMode ? View.GONE : View.VISIBLE);
            binding.btnToggleCamera.setVisibility(isInPipMode ? View.GONE : View.VISIBLE);
            binding.btnToggleSpeaker.setVisibility(isInPipMode ? View.GONE : View.VISIBLE);
            binding.btnSwitchCamera.setVisibility(isInPipMode ? View.GONE : View.VISIBLE);
            binding.tvCallerName.setVisibility(isInPipMode ? View.GONE : View.VISIBLE);
            binding.tvCallStatus.setVisibility(isInPipMode ? View.GONE : View.VISIBLE);
        }
        // Resume camera if returning from PiP
        if (!isInPipMode && isVideo && videoCapturer != null && !capturerRunning && camOn) {
            startCapture();
        }
    }

    // ── Camera switch ──────────────────────────────────────────────────────

    private void switchCamera() {
        if (!isVideo || videoCapturer == null) return;
        usingFrontCamera = !usingFrontCamera;
        if (videoCapturer instanceof CameraVideoCapturer)
            ((CameraVideoCapturer) videoCapturer).switchCamera(null);
        binding.localVideo.setMirror(usingFrontCamera);
    }

    // ── FIX: Speaker toggle with proper initial state ──────────────────────

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

    // ── FIX: Mic toggle — icon + label + alpha + Firebase signal ──────────

    private void toggleMic() {
        micOn = !micOn;
        if (localAudioTrack != null) localAudioTrack.setEnabled(micOn);
        updateMicUI();
        // Signal to remote party (optional — for mute indicator on their screen)
        if (callRef != null) {
            String myUid = FirebaseUtils.getCurrentUid();
            if (myUid != null)
                callRef.child("micState").child(myUid).setValue(micOn);
        }
    }

    private void updateMicUI() {
        binding.btnToggleMic.setAlpha(micOn ? 1f : 0.4f);
        // FIX: Change icon when muted
        binding.btnToggleMic.setImageResource(micOn
            ? com.callx.app.calls.R.drawable.ic_mic
            : com.callx.app.calls.R.drawable.ic_mic_off);
        if (binding.tvMicLabel != null)
            binding.tvMicLabel.setText(micOn ? "Mute" : "Unmute");
        // FIX: Red tint background when muted
        binding.btnToggleMic.setBackgroundResource(micOn
            ? com.callx.app.calls.R.drawable.circle_avatar_bg
            : com.callx.app.calls.R.drawable.circle_reject_light);
    }

    // ── FIX: Camera toggle — icon + label + alpha + Firebase signal ───────

    private void toggleCamera() {
        camOn = !camOn;
        if (localVideoTrack != null) localVideoTrack.setEnabled(camOn);
        // FIX: Also stop/start physical capture when cam is toggled
        if (isVideo && videoCapturer != null) {
            if (!camOn && capturerRunning) {
                try { videoCapturer.stopCapture(); capturerRunning = false; } catch (Exception ignored) {}
            } else if (camOn && !capturerRunning) {
                startCapture();
            }
        }
        // FIX: Show/hide local cam off badge
        if (binding.layoutLocalCamOffBadge != null)
            binding.layoutLocalCamOffBadge.setVisibility(camOn ? View.GONE : View.VISIBLE);
        updateCameraUI();
        // Signal to remote party
        if (callRef != null) {
            String myUid = FirebaseUtils.getCurrentUid();
            if (myUid != null)
                callRef.child("camState").child(myUid).setValue(camOn);
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

    // ── ICE state / reconnection ───────────────────────────────────────────

    private void handleIceStateChange(PeerConnection.IceConnectionState s) {
        showQualityIndicator(s);
        if (s == PeerConnection.IceConnectionState.CONNECTED ||
            s == PeerConnection.IceConnectionState.COMPLETED) {
            cancelPendingIceRestart();
            onCallConnected();
            binding.tvCallStatus.setAlpha(1f);
        } else if (s == PeerConnection.IceConnectionState.DISCONNECTED) {
            if (callConnected) {
                binding.tvCallStatus.setText("Reconnecting...");
                scheduleIceRestart();
            }
        } else if (s == PeerConnection.IceConnectionState.FAILED) {
            if (callConnected && iceRestartCount < Constants.ICE_MAX_RESTARTS) {
                performIceRestart();
            } else if (callConnected) {
                runOnUiThread(this::endCall);
            }
        } else if (s == PeerConnection.IceConnectionState.CLOSED) {
            if (!finishing) runOnUiThread(this::endCall);
        }
    }

    private void scheduleIceRestart() {
        cancelPendingIceRestart();
        iceRestartRunnable = () -> {
            if (!finishing && peerConnection != null) performIceRestart();
        };
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
            // Callee signals caller to restart
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
                if (ts != null && ts > 0) {
                    runOnUiThread(() -> { iceRestartCount = 0; scheduleIceRestart(); });
                }
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
                            if (s2.exists()) { callRef.child("offer").removeEventListener(this); handleOffer(s2); }
                        }
                        @Override public void onCancelled(DatabaseError e) {}
                    });
                } else {
                    handleOffer(s);
                }
            }
            @Override public void onCancelled(DatabaseError e) {}
        });
    }

    private void handleOffer(DataSnapshot s) {
        String type = s.child("type").getValue(String.class);
        String sdp  = s.child("sdp").getValue(String.class);
        if (type == null || sdp == null) return;
        SessionDescription offer = new SessionDescription(
            SessionDescription.Type.fromCanonicalForm(type), sdp);
        peerConnection.setRemoteDescription(new SdpObserver() {
            @Override public void onSetSuccess() {
                remoteDescSet = true;
                drainPendingCandidates();
                runOnUiThread(CallActivity.this::createAnswer);
            }
            @Override public void onCreateSuccess(SessionDescription s2) {}
            @Override public void onCreateFailure(String e) {}
            @Override public void onSetFailure(String e) {}
        }, offer);
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
                if ("ended".equals(st) || "rejected".equals(st) || "cancelled".equals(st)) {
                    if (!callConnected && isCaller
                            && ("rejected".equals(st) || "cancelled".equals(st))) {
                        String myUid = FirebaseUtils.getCurrentUid();
                        if (myUid != null) {
                            PushNotify.notifyMissedCall(myUid,
                                partnerUid != null ? partnerUid : "",
                                partnerName != null ? partnerName : "",
                                callId != null ? callId : "",
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
                String sdpMid   = s.child("sdpMid").getValue(String.class);
                String sdpMLine = s.child("sdpMLineIndex").getValue(String.class);
                String cand     = s.child("candidate").getValue(String.class);
                if (sdpMid == null || cand == null) return;
                int idx = 0;
                try { if (sdpMLine != null) idx = Integer.parseInt(sdpMLine); } catch (Exception ignored) {}
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
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
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
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) cm.unregisterNetworkCallback(networkCallback);
        } catch (Exception ignored) {}
    }

    // ── Call connected ─────────────────────────────────────────────────────

    private void onCallConnected() {
        if (callConnected) return;
        callConnected = true;
        iceRestartCount = 0;
        startedAt = System.currentTimeMillis();
        binding.tvCallStatus.setText("Connected \u2022 0:00");
        if (isVideo) binding.ivCallAvatar.setVisibility(View.GONE);

        Intent fg = new Intent(this, CallForegroundService.class);
        fg.putExtra("name",         partnerName != null ? partnerName : "");
        fg.putExtra("callId",       callId != null ? callId : "");
        fg.putExtra("isVideo",      isVideo);
        fg.putExtra("partnerThumb", partnerThumb != null ? partnerThumb : "");
        fg.putExtra(CallForegroundService.EXTRA_PARTNER_UID, partnerUid != null ? partnerUid : "");
        fg.putExtra(CallForegroundService.EXTRA_DIRECTION,   isCaller ? "outgoing" : "incoming");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(fg);
        else startService(fg);

        ticker = new Runnable() {
            @Override public void run() {
                long e = (System.currentTimeMillis() - startedAt) / 1000;
                binding.tvCallStatus.setText(String.format("Connected \u2022 %d:%02d", e/60, e%60));
                tick.postDelayed(this, 1_000);
            }
        };
        tick.post(ticker);
    }

    // ── End call ───────────────────────────────────────────────────────────

    private void endCall() {
        if (finishing) return;
        finishing = true;
        cancelPendingIceRestart();
        if (ticker != null) tick.removeCallbacks(ticker);
        try { stopService(new Intent(this, CallForegroundService.class)); } catch (Exception ignored) {}

        if (callRef != null) {
            long dur = startedAt == 0 ? 0 : System.currentTimeMillis() - startedAt;
            callRef.child("status").setValue("ended");
            // FIX: Cancel onDisconnect so it doesn't fire again after explicit end
            callRef.child("status").onDisconnect().cancel();

            String myUid = FirebaseUtils.getCurrentUid();
            if (myUid != null) {
                final long fDur = dur;
                final long fTs  = System.currentTimeMillis();
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
                    Map<String, Object> partnerLog = new HashMap<>();
                    partnerLog.put("partnerUid",  myUid);
                    partnerLog.put("partnerName", myName != null ? myName : "");
                    partnerLog.put("direction",   isCaller ? "incoming" : "outgoing");
                    partnerLog.put("mediaType",   fType);
                    partnerLog.put("timestamp",   fTs);
                    partnerLog.put("duration",    fDur);
                    FirebaseUtils.getCallsRef(partnerUid).push().setValue(partnerLog);
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
                        AppDatabase.getInstance(getApplicationContext()).callLogDao().insertCallLog(entity);
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
        try {
            if (remoteCandidateListener != null && callRef != null) {
                String myUid = FirebaseUtils.getCurrentUid();
                if (myUid != null)
                    callRef.child("candidates").child(myUid).removeEventListener(remoteCandidateListener);
            }
            if (statusListener != null && callRef != null)
                callRef.child("status").removeEventListener(statusListener);
            if (iceRestartRequestListener != null && callRef != null)
                callRef.child("iceRestartRequest").removeEventListener(iceRestartRequestListener);
            if (videoCapturer != null) { videoCapturer.stopCapture(); videoCapturer.dispose(); }
            if (localVideoTrack  != null) localVideoTrack.dispose();
            if (localAudioTrack  != null) localAudioTrack.dispose();
            if (videoSource      != null) videoSource.dispose();
            if (audioSource      != null) audioSource.dispose();
            if (peerConnection   != null) { peerConnection.close(); peerConnection.dispose(); }
            if (surfaceHelper    != null) surfaceHelper.dispose();
            if (isVideo) {
                try { binding.localVideo.release(); } catch (Exception ignored) {}
                try { binding.remoteVideo.release(); } catch (Exception ignored) {}
            }
            if (eglBase  != null) eglBase.release();
            if (factory  != null) factory.dispose();
        } catch (Exception ignored) {}
        try { bgExecutor.shutdownNow(); } catch (Exception ignored) {}
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
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
        super.onDestroy();
    }
}
