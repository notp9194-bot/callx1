package com.callx.app.call;

import android.Manifest;
import android.media.MediaRecorder;
import android.widget.TextView;
import android.app.PictureInPictureParams;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.app.KeyguardManager;
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
import com.callx.app.calls.R;
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
import org.webrtc.StatsObserver;
import org.webrtc.StatsReport;
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
import com.callx.app.notes.AddNoteActivity;

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

    // Feature 4: Call Timeout — 60s mein koi na uthaye toh auto-cancel
    private static final long CALL_TIMEOUT_MS = 60_000L;
    private final Handler timeoutHandler = new Handler(Looper.getMainLooper());
    private Runnable timeoutRunnable;

    // ICE reconnection
    private int iceRestartCount = 0;
    private final Handler iceRestartHandler = new Handler(Looper.getMainLooper());
    private Runnable iceRestartRunnable;
    private ValueEventListener iceRestartRequestListener;

    // ── RTCStats quality polling ─────────────────────────────────────────
    private final android.os.Handler statsHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable statsRunnable;
    private long prevBytesRx = 0, prevBytesTx = 0, prevStatsTimeMs = 0;

    // ── Call Recording ─────────────────────────────────────────────────────
    private CallRecorderHelper callRecorder;
    private boolean isRecording = false;
    private static final int REQ_RECORD = 201;

    // Wake lock
    private PowerManager.WakeLock wakeLock;
    // Proximity sensor — kaan paas aao toh screen off
    private PowerManager.WakeLock proximityWakeLock;

    // Audio
    private AudioManager audioManager;
    private android.media.AudioFocusRequest audioFocusRequest; // BUG-1 FIX: audio focus

    // FIX-NOISY: Headphone unplug receiver
    private BroadcastReceiver noisyReceiver;
    private boolean noisyReceiverRegistered = false;

    // FIX-BT: Bluetooth SCO receiver
    private BroadcastReceiver btScoReceiver;
    private boolean btScoReceiverRegistered = false;

    // ── Notification shade mic/cam toggle receiver ─────────────────────────
    private BroadcastReceiver notifToggleReceiver;
    private boolean notifToggleReceiverRegistered = false;

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
    private boolean capturerRunning = false;

    // Feature 3: Adaptive quality state
    private boolean usingLowQuality = false;

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

        // Lock screen pe call dikhao — Android 8.1+ deprecated flags use nahi karte
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
            KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            if (km != null) km.requestDismissKeyguard(this, null);
        } else {
            getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

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
        // Volume buttons → call volume control karo, music nahi
        setVolumeControlStream(AudioManager.STREAM_VOICE_CALL);
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

        String callAvatarUrl = (partnerThumb != null && !partnerThumb.isEmpty())
            ? partnerThumb : partnerPhoto;
        if (callAvatarUrl != null && !callAvatarUrl.isEmpty()) {
            Glide.with(this).load(callAvatarUrl).circleCrop().into(binding.ivCallAvatar);
            if (binding.ivRemoteCamOffAvatar != null)
                Glide.with(this).load(callAvatarUrl).circleCrop()
                    .into(binding.ivRemoteCamOffAvatar);
        }

        binding.btnEndCall.setOnClickListener(v -> endCall());
        binding.btnToggleMic.setOnClickListener(v -> toggleMic());
        binding.btnToggleCamera.setOnClickListener(v -> toggleCamera());
        binding.btnSwitchCamera.setOnClickListener(v -> switchCamera());
        binding.btnToggleSpeaker.setOnClickListener(v -> toggleSpeaker());
        if (binding.btnRecord != null)
            binding.btnRecord.setOnClickListener(v -> toggleRecording());

        updateMicUI();
        updateCameraUI();
        setupLocalVideoDrag();

        // Feature 4: Caller ke liye 60s timeout — agar na uthaye toh cancel
        if (isCaller) {
            timeoutRunnable = () -> {
                if (!callConnected && !finishing) {
                    binding.tvCallStatus.setText("No answer");
                    endCall();
                }
            };
            timeoutHandler.postDelayed(timeoutRunnable, CALL_TIMEOUT_MS);
        }

        // BUG-5 FIX: isRestore + callConnected check broken tha — nayi instance mein
        // callConnected always false hota hai. Firebase se actual status verify karo.
        if (isRestore && callId != null && !callId.isEmpty()) {
            com.google.firebase.database.FirebaseDatabase.getInstance()
                .getReference("activeCalls").child(callId).child("status")
                .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                    @Override public void onDataChange(com.google.firebase.database.DataSnapshot snap) {
                        String st = snap.getValue(String.class);
                        if ("accepted".equals(st) || "ringing".equals(st)) {
                            // Call still active — reconnect WebRTC
                            checkPermsAndInit();
                        } else {
                            // Call already ended — screen band karo
                            finish();
                        }
                    }
                    @Override public void onCancelled(com.google.firebase.database.DatabaseError e) {
                        checkPermsAndInit(); // error pe try karo
                    }
                });
            return;
        }
        checkPermsAndInit();
    }

    // ── Feature 3: Adaptive Video — resolution dynamically adjust ─────────
    private void applyVideoBitrate(boolean lowQuality) {
        if (!isVideo || videoCapturer == null || capturerRunning == false) return;
        if (lowQuality == usingLowQuality) return;
        usingLowQuality = lowQuality;
        try {
            videoCapturer.stopCapture();
            capturerRunning = false;
        } catch (Exception ignored) {}

        int w, h;
        if (lowQuality) {
            // Poor network → VGA (640×480)
            w = Constants.VIDEO_WIDTH_VGA;
            h = Constants.VIDEO_HEIGHT_VGA;
        } else {
            // Good network → HD (1280×720)
            w = Constants.VIDEO_WIDTH_HD;
            h = Constants.VIDEO_HEIGHT_HD;
        }
        try {
            videoCapturer.startCapture(w, h, Constants.VIDEO_FPS);
            capturerRunning = true;
        } catch (Exception ignored) {}
    }

    // ── Feature 5: Local video drag ───────────────────────────────────────
    private void setupLocalVideoDrag() {
        if (!isVideo) return;
        binding.localVideo.setOnTouchListener(new View.OnTouchListener() {
            float dX, dY;
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        dX = v.getX() - event.getRawX();
                        dY = v.getY() - event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float newX = event.getRawX() + dX;
                        float newY = event.getRawY() + dY;
                        ViewGroup parent = (ViewGroup) v.getParent();
                        if (parent != null) {
                            newX = Math.max(0, Math.min(newX, parent.getWidth()  - v.getWidth()));
                            newY = Math.max(0, Math.min(newY, parent.getHeight() - v.getHeight()));
                        }
                        v.setX(newX); v.setY(newY);
                        if (binding.layoutLocalCamOffBadge != null) {
                            binding.layoutLocalCamOffBadge.setX(newX);
                            binding.layoutLocalCamOffBadge.setY(newY);
                        }
                        return true;
                }
                return false;
            }
        });
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

            // Proximity sensor — kaan paas aao toh screen off (WhatsApp style)
            if (pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                proximityWakeLock = pm.newWakeLock(
                    PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "callx:proximity");
                proximityWakeLock.acquire(3 * 60 * 60 * 1000L);
            }
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
    public void onRequestPermissionsResult(int req, @NonNull String[] perms,
                                           @NonNull int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == 401) {
            boolean ok = true;
            for (int r : results) if (r != PackageManager.PERMISSION_GRANTED) { ok = false; break; }
            if (ok) fetchTurnThenInitWebRTC();
            else { Toast.makeText(this, "Mic/Camera permission required", Toast.LENGTH_LONG).show(); finish(); }
        } else if (req == REQ_RECORD) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED)
                startCallRecording();
            else
                Toast.makeText(this, "Mic permission chahiye recording ke liye", Toast.LENGTH_SHORT).show();
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
                conn.setRequestMethod("GET");
                if (conn.getResponseCode() == 200) {
                    BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder(); String line;
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
        // Free TURN fallback — strict NAT ke peeche sirf STUN se call fail hoti hai
        try {
            list.add(PeerConnection.IceServer.builder(Constants.TURN_FREE_1)
                .setUsername(Constants.TURN_FREE_USER).setPassword(Constants.TURN_FREE_CRED)
                .createIceServer());
            list.add(PeerConnection.IceServer.builder(Constants.TURN_FREE_2)
                .setUsername(Constants.TURN_FREE_USER).setPassword(Constants.TURN_FREE_CRED)
                .createIceServer());
            list.add(PeerConnection.IceServer.builder(Constants.TURN_FREE_TLS)
                .setUsername(Constants.TURN_FREE_USER).setPassword(Constants.TURN_FREE_CRED)
                .createIceServer());
        } catch (Exception ignored) {}
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
            .setVideoEncoderFactory(new DefaultVideoEncoderFactory(
                eglBase.getEglBaseContext(), true, true))
            .setVideoDecoderFactory(new DefaultVideoDecoderFactory(
                eglBase.getEglBaseContext()))
            .createPeerConnectionFactory();

        PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(iceServers);
        rtcConfig.sdpSemantics             = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        rtcConfig.iceTransportsType        = PeerConnection.IceTransportsType.ALL;
        rtcConfig.bundlePolicy             = PeerConnection.BundlePolicy.MAXBUNDLE;
        rtcConfig.rtcpMuxPolicy            = PeerConnection.RtcpMuxPolicy.REQUIRE;
        rtcConfig.continualGatheringPolicy =
            PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;

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

        List<String> ids = new ArrayList<>();
        ids.add("stream0");
        peerConnection.addTrack(localAudioTrack, ids);
        if (isVideo && localVideoTrack != null)
            peerConnection.addTrack(localVideoTrack, ids);

        enableSpeaker(isVideo);

        // BUG-1 FIX: Request audio focus — dusri apps (music/video) mute ho jayengi call mein
        requestCallAudioFocus();

        if (callId == null) {
            if (isCaller) {
                callId = FirebaseUtils.db().getReference("activeCalls").push().getKey();
            } else {
                android.util.Log.e("CallActivity", "Callee callId null — aborting");
                finish(); return;
            }
        }
        callRef = FirebaseUtils.db().getReference("activeCalls").child(callId);
        callRef.child("status").onDisconnect().setValue("ended");

        if (isCaller) {
            String myUid  = FirebaseUtils.getCurrentUid();
            String myName = FirebaseUtils.getCurrentName();
            Map<String, Object> c = new HashMap<>();
            c.put("from",   myUid); c.put("fromName", myName);
            c.put("to",     partnerUid); c.put("video", isVideo);
            c.put("at",     System.currentTimeMillis()); c.put("status", "ringing");
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
        registerNotifToggleReceiver();
    }

    // ── Notification shade mic/camera toggle ──────────────────────────────
    private void registerNotifToggleReceiver() {
        notifToggleReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                String a = intent.getAction();
                if (com.callx.app.utils.Constants.ACTION_TOGGLE_MIC.equals(a)
                        || "com.callx.app.INTERNAL_TOGGLE_MIC".equals(a)) {
                    // Sync mic state from service static field, then apply
                    boolean newMicOn = com.callx.app.services.CallForegroundService.micOn;
                    if (micOn != newMicOn) {
                        micOn = newMicOn;
                        if (localAudioTrack != null) localAudioTrack.setEnabled(micOn);
                        String uid = FirebaseUtils.getCurrentUid();
                        if (callRef != null && uid != null)
                            callRef.child("micState").child(uid).setValue(micOn);
                        binding.btnToggleMic.setAlpha(micOn ? 1f : 0.4f);
                        binding.btnToggleMic.setImageResource(micOn
                            ? com.callx.app.calls.R.drawable.ic_mic
                            : com.callx.app.calls.R.drawable.ic_mic_off);
                        if (binding.tvMicLabel != null)
                            binding.tvMicLabel.setText(micOn ? "Mute" : "Unmute");
                    }
                } else if (com.callx.app.utils.Constants.ACTION_TOGGLE_CAMERA.equals(a)
                        || "com.callx.app.INTERNAL_TOGGLE_CAMERA".equals(a)) {
                    boolean newCamOn = com.callx.app.services.CallForegroundService.camOn;
                    if (isVideo && camOn != newCamOn) {
                        camOn = newCamOn;
                        if (localVideoTrack != null) localVideoTrack.setEnabled(camOn);
                        if (videoCapturer != null) {
                            if (!camOn && capturerRunning) {
                                try { videoCapturer.stopCapture(); capturerRunning = false; }
                                catch (Exception ignored) {}
                            } else if (camOn && !capturerRunning) {
                                videoCapturer.startCapture(
                                    com.callx.app.utils.Constants.VIDEO_WIDTH_VGA,
                                    com.callx.app.utils.Constants.VIDEO_HEIGHT_VGA,
                                    com.callx.app.utils.Constants.VIDEO_FPS);
                                capturerRunning = true;
                            }
                        }
                        String uid = FirebaseUtils.getCurrentUid();
                        if (callRef != null && uid != null)
                            callRef.child("camState").child(uid).setValue(camOn);
                        binding.btnToggleCamera.setAlpha(camOn ? 1f : 0.4f);
                        binding.btnToggleCamera.setImageResource(camOn
                            ? com.callx.app.calls.R.drawable.ic_video
                            : com.callx.app.calls.R.drawable.ic_video_off);
                        if (binding.tvCameraLabel != null)
                            binding.tvCameraLabel.setText(camOn ? "Camera" : "Cam Off");
                    }
                }
            }
        };
        try {
            android.content.IntentFilter f = new android.content.IntentFilter();
            f.addAction("com.callx.app.INTERNAL_TOGGLE_MIC");
            f.addAction("com.callx.app.INTERNAL_TOGGLE_CAMERA");
            registerReceiver(notifToggleReceiver, f);
            notifToggleReceiverRegistered = true;
        } catch (Exception ignored) {}
    }

    // ── FIX-NOISY: Headphone unplug ───────────────────────────────────────
    private void registerNoisyReceiver() {
        noisyReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction()))
                    if (speakerOn) enableSpeaker(false);
            }
        };
        try {
            registerReceiver(noisyReceiver,
                new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
            noisyReceiverRegistered = true;
        } catch (Exception ignored) {}
    }

    // ── FIX-BT: Bluetooth SCO ─────────────────────────────────────────────
    private void registerBtScoReceiver() {
        btScoReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                int state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1);
                if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                    if (audioManager != null) {
                        audioManager.setBluetoothScoOn(true);
                        speakerOn = false;
                        if (binding.tvSpeakerLabel != null)
                            binding.tvSpeakerLabel.setText("Bluetooth");
                    }
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

    // ── Network quality indicator ─────────────────────────────────────────
    private void showQualityIndicator(PeerConnection.IceConnectionState state) {
        if (binding.layoutQuality == null) return;
        if (state == PeerConnection.IceConnectionState.CONNECTED ||
            state == PeerConnection.IceConnectionState.COMPLETED) {
            binding.layoutQuality.setVisibility(View.VISIBLE);
            if (binding.tvQualityLabel != null) binding.tvQualityLabel.setText("Good");
        } else if (state == PeerConnection.IceConnectionState.DISCONNECTED) {
            binding.layoutQuality.setVisibility(View.VISIBLE);
            if (binding.tvQualityLabel != null) binding.tvQualityLabel.setText("Weak");
        } else if (state == PeerConnection.IceConnectionState.FAILED ||
                   state == PeerConnection.IceConnectionState.CHECKING) {
            // FIX: Poor state — pehle missing tha
            binding.layoutQuality.setVisibility(View.VISIBLE);
            if (binding.tvQualityLabel != null) binding.tvQualityLabel.setText("Poor");
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

    // ── Lifecycle ──────────────────────────────────────────────────────────
    @Override protected void onStop() {
        super.onStop();
        if (isVideo && videoCapturer != null && capturerRunning
                && !isInPictureInPictureMode()) {
            try { videoCapturer.stopCapture(); capturerRunning = false; }
            catch (Exception ignored) {}
        }
    }

    @Override protected void onStart() {
        super.onStart();
        if (isVideo && videoCapturer != null && !capturerRunning && camOn)
            startCapture();
    }

    // ── PiP ───────────────────────────────────────────────────────────────
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
    public void onPictureInPictureModeChanged(boolean isInPipMode) {
        super.onPictureInPictureModeChanged(isInPipMode);
        int v = isInPipMode ? View.GONE : View.VISIBLE;
        binding.btnEndCall.setVisibility(v);
        binding.btnToggleMic.setVisibility(v);
        binding.btnToggleCamera.setVisibility(v);
        binding.btnToggleSpeaker.setVisibility(v);
        binding.btnSwitchCamera.setVisibility(v);
        binding.tvCallerName.setVisibility(v);
        binding.tvCallStatus.setVisibility(v);
        if (!isInPipMode && isVideo && videoCapturer != null && !capturerRunning && camOn)
            startCapture();
        // BUG-8 FIX: PiP se wapas aane par tvCallStatus timer sync karo
        // PiP ke dauran ticker chal raha tha lekin text visible nahi tha —
        // ab wapas aate hi current elapsed time se status refresh karo
        if (!isInPipMode && callConnected && startedAt > 0) {
            long e = (System.currentTimeMillis() - startedAt) / 1000;
            binding.tvCallStatus.setText(
                String.format("Connected \u2022 %d:%02d", e / 60, e % 60));
        }
    }

    // ── Controls ───────────────────────────────────────────────────────────
    private void switchCamera() {
        if (!isVideo || videoCapturer == null) return;
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
        if (isVideo && videoCapturer != null) {
            if (!camOn && capturerRunning) {
                try { videoCapturer.stopCapture(); capturerRunning = false; }
                catch (Exception ignored) {}
            } else if (camOn && !capturerRunning) {
                startCapture();
            }
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

    // ── ICE state ─────────────────────────────────────────────────────────
    private void handleIceStateChange(PeerConnection.IceConnectionState s) {
        showQualityIndicator(s);

        if (s == PeerConnection.IceConnectionState.CONNECTED ||
            s == PeerConnection.IceConnectionState.COMPLETED) {
            stopCallRecording(true);  // Save recording on call end
        cancelPendingIceRestart();
            onCallConnected();
            binding.tvCallStatus.setAlpha(1f);
            // Feature 3: Network kuch behtar hua → restore HD quality
            applyVideoBitrate(false);

        } else if (s == PeerConnection.IceConnectionState.DISCONNECTED) {
            if (callConnected) {
                binding.tvCallStatus.setText("Reconnecting...");
                // Feature 3: Network weak → VGA save karo bandwidth
                applyVideoBitrate(true);
                scheduleIceRestart();
            }
        } else if (s == PeerConnection.IceConnectionState.FAILED) {
            // Feature 3: Very poor → stay on low quality
            applyVideoBitrate(true);
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
                } else { handleOffer(s); }
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
        }, new SessionDescription(
            SessionDescription.Type.fromCanonicalForm(type), sdp));
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
                            runOnUiThread(() ->
                                binding.tvCallStatus.setText(
                                    (partnerName != null ? partnerName : "Contact") + " is on another call"));
                        } else if ("rejected".equals(st) || "cancelled".equals(st)) {
                            String myUid = FirebaseUtils.getCurrentUid();
                            String myName = FirebaseUtils.getCurrentName();
                            if (myUid != null) {
                                // FIX: toUid = partnerUid (callee ko missed call notification)
                                // Pehle toUid = myUid tha — caller apne aap ko notify kar raha tha
                                PushNotify.notifyMissedCall(
                                    partnerUid   != null ? partnerUid   : "",
                                    myUid,
                                    myName       != null ? myName       : "",
                                    callId       != null ? callId       : "",
                                    isVideo,
                                    partnerPhoto != null ? partnerPhoto : "");
                            }
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
                try { if (sdpMLine != null) idx = Integer.parseInt(sdpMLine); }
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
        callRef.child("candidates").child(myUid)
            .addChildEventListener(remoteCandidateListener);
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
        startRtcStatsPolling(); // RTCStats quality polling shuru karo
        iceRestartCount = 0;
        startedAt = System.currentTimeMillis();

        // Feature 4: Cancel timeout — call connected ho gaya
        if (timeoutRunnable != null) {
            timeoutHandler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }

        binding.tvCallStatus.setText("Connected \u2022 0:00");
        if (isVideo) binding.ivCallAvatar.setVisibility(View.GONE);

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

        ticker = new Runnable() {
            @Override public void run() {
                long e = (System.currentTimeMillis() - startedAt) / 1000;
                binding.tvCallStatus.setText(
                    String.format("Connected \u2022 %d:%02d", e / 60, e % 60));
                tick.postDelayed(this, 1_000);
            }
        };
        tick.post(ticker);
    }

    // ── BUG-1 FIX: Audio Focus ────────────────────────────────────────────
    private void requestCallAudioFocus() {
        if (audioManager == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = new android.media.AudioFocusRequest.Builder(
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                .setOnAudioFocusChangeListener(focusChange -> {
                    // Call ke dauran focus change — kuch nahi karna,
                    // WebRTC khud manage karta hai
                })
                .build();
            audioManager.requestAudioFocus(audioFocusRequest);
        } else {
            //noinspection deprecation
            audioManager.requestAudioFocus(null,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
        }
    }

    private void abandonCallAudioFocus() {
        if (audioManager == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
            audioFocusRequest = null;
        } else {
            //noinspection deprecation
            audioManager.abandonAudioFocus(null);
        }
    }

    // ── End call ───────────────────────────────────────────────────────────
    private void endCall() {
        if (finishing) return;
        finishing = true;

        cancelPendingIceRestart();
        if (ticker != null) tick.removeCallbacks(ticker);
        if (timeoutRunnable != null) {
            timeoutHandler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }

        try { stopService(new Intent(this, CallForegroundService.class)); }
        catch (Exception ignored) {}

        final String myUid = FirebaseUtils.getCurrentUid();

        if (callRef != null) {
            long dur = startedAt == 0 ? 0 : System.currentTimeMillis() - startedAt;
            // Caller ne unanswered call kat di → "cancelled" likhao taaki callee ka
            // IncomingRingService turant ring band kare aur missed call dikhaye.
            // Call connected ho chuki thi → "ended" likhao (normal end).
            String endStatus = (isCaller && !callConnected) ? "cancelled" : "ended";
            callRef.child("status").setValue(endStatus);
            callRef.child("status").onDisconnect().cancel();

            // FIX-CLEANUP: camState hata do
            if (isVideo && myUid != null)
                callRef.child("camState").child(myUid).removeValue();

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

                // ── Push call entry bubble to shared chat thread ─────────────
                // Only caller pushes (avoids duplicate). Both sides see it via Firebase listener.
                if (isCaller && partnerUid != null && !partnerUid.isEmpty()) {
                    final String chatId  = myUid.compareTo(partnerUid) < 0
                        ? myUid + "_" + partnerUid
                        : partnerUid + "_" + myUid;
                    final String direction = callConnected ? "connected" : "missed";
                    final DatabaseReference msgRef = FirebaseUtils.db()
                        .getReference("messages").child(chatId);
                    final String msgKey = msgRef.push().getKey();
                    if (msgKey != null) {
                        Map<String, Object> callMsg = new HashMap<>();
                        callMsg.put("id",        msgKey);
                        callMsg.put("messageId", msgKey);
                        callMsg.put("senderId",  myUid);
                        callMsg.put("type",      "call_entry");
                        callMsg.put("text",      direction);
                        callMsg.put("fileName",  fType);
                        callMsg.put("duration",  fDur);
                        callMsg.put("timestamp", fTs);
                        callMsg.put("status",    "sent");
                        msgRef.child(msgKey).setValue(callMsg);
                    }
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
            // BUG-1 FIX: Audio focus release karo
            abandonCallAudioFocus();
        }
        releaseWebRTC();

        // ── Launch AddNoteActivity for caller when call went unanswered ───────
        if (isCaller && !callConnected && myUid != null && partnerUid != null && !partnerUid.isEmpty()) {
            try {
                final String chatId2 = myUid.compareTo(partnerUid) < 0
                    ? myUid + "_" + partnerUid
                    : partnerUid + "_" + myUid;
                android.content.Intent noteIntent = new android.content.Intent(this, AddNoteActivity.class);
                noteIntent.putExtra(AddNoteActivity.EXTRA_PARTNER_UID,   partnerUid);
                noteIntent.putExtra(AddNoteActivity.EXTRA_PARTNER_NAME,  partnerName != null ? partnerName : "");
                noteIntent.putExtra(AddNoteActivity.EXTRA_PARTNER_PHOTO, partnerPhoto != null ? partnerPhoto : "");
                noteIntent.putExtra(AddNoteActivity.EXTRA_CHAT_ID,       chatId2);
                noteIntent.putExtra(AddNoteActivity.EXTRA_IS_VIDEO,      isVideo);
                startActivity(noteIntent);
            } catch (Exception ex) {
                android.util.Log.w("CallActivity", "AddNoteActivity launch failed", ex);
            }
        }
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
        if (notifToggleReceiverRegistered && notifToggleReceiver != null) {
            try { unregisterReceiver(notifToggleReceiver); } catch (Exception ignored) {}
            notifToggleReceiverRegistered = false;
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
            if (videoCapturer != null) { videoCapturer.stopCapture(); videoCapturer.dispose(); }
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
        stopRtcStatsPolling(); // RTCStats polling band karo
        try { bgExecutor.shutdownNow(); } catch (Exception ignored) {}
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {}
    }

    // ── RTCStats quality polling — real bitrate/packet-loss based ─────────
    private void startRtcStatsPolling() {
        prevBytesRx = 0; prevBytesTx = 0; prevStatsTimeMs = 0;
        statsRunnable = new Runnable() {
            @Override public void run() {
                if (!callConnected || finishing) return;
                pollRtcStats();
                statsHandler.postDelayed(this, 4000);
            }
        };
        statsHandler.postDelayed(statsRunnable, 4000);
    }

    private void stopRtcStatsPolling() {
        if (statsRunnable != null) {
            statsHandler.removeCallbacks(statsRunnable);
            statsRunnable = null;
        }
    }

    private void pollRtcStats() {
        if (peerConnection == null || finishing) return;
        try {
            peerConnection.getStats(new StatsObserver() {
                @Override
                public void onComplete(StatsReport[] reports) {
                    if (reports == null || finishing) return;
                    long totalBytesRx = 0, totalBytesTx = 0;
                    double maxPacketLoss = 0;
                    for (StatsReport r : reports) {
                        if (r.values == null) continue;
                        for (StatsReport.Value v : r.values) {
                            try {
                                if ("bytesReceived".equals(v.name)) totalBytesRx += Long.parseLong(v.value);
                                else if ("bytesSent".equals(v.name)) totalBytesTx += Long.parseLong(v.value);
                                else if ("packetsLost".equals(v.name)) {
                                    double lost = Double.parseDouble(v.value);
                                    if (lost > 0) maxPacketLoss = Math.max(maxPacketLoss, lost / 100.0);
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                    long now = System.currentTimeMillis();
                    int rxKbps = 0, txKbps = 0;
                    if (prevStatsTimeMs > 0) {
                        double sec = (now - prevStatsTimeMs) / 1000.0;
                        if (sec > 0.1) {
                            rxKbps = (int)(((totalBytesRx - prevBytesRx) * 8L) / sec / 1000);
                            txKbps = (int)(((totalBytesTx - prevBytesTx) * 8L) / sec / 1000);
                        }
                    }
                    prevBytesRx = totalBytesRx; prevBytesTx = totalBytesTx; prevStatsTimeMs = now;
                    final int fRx = Math.max(0, rxKbps), fTx = Math.max(0, txKbps);
                    final double fLoss = maxPacketLoss;
                    runOnUiThread(() -> {
                        if (finishing || binding.layoutQuality == null) return;
                        int total = fRx + fTx;
                        String lbl;
                        if (total >= 200 && fLoss < 0.05) { lbl = "Good"; if (isVideo) applyVideoBitrate(false); }
                        else if (total >= 60 || fLoss < 0.15) { lbl = "Weak"; if (isVideo) applyVideoBitrate(true); }
                        else { lbl = "Poor"; if (isVideo) applyVideoBitrate(true); }
                        binding.layoutQuality.setVisibility(android.view.View.VISIBLE);
                        if (binding.tvQualityLabel != null)
                            binding.tvQualityLabel.setText(fRx > 0 ? lbl + " • " + fRx + "↓ " + fTx + "↑ kbps" : lbl);
                    });
                }
            }, null);
        } catch (Exception ignored) {}
    }
    // ── Call Recording ─────────────────────────────────────────────────────
    /**
     * Record button tap handler.
     * Pehle RECORD_AUDIO permission check hota hai, phir recording start/stop.
     */
    private void toggleRecording() {
        if (!callConnected) {
            Toast.makeText(this, "Recording sirf connected call mein hoti hai", Toast.LENGTH_SHORT).show();
            return;
        }
        if (isRecording) {
            stopCallRecording(false);
        } else {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                startCallRecording();
            } else {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RECORD);
            }
        }
    }

    private void startCallRecording() {
        callRecorder = new CallRecorderHelper(this, partnerName, callId);
        boolean ok = callRecorder.start();
        if (ok) {
            isRecording = true;
            updateRecordUi(true);
            Toast.makeText(this, "Recording shuru ho gayi", Toast.LENGTH_SHORT).show();
        } else {
            callRecorder = null;
            Toast.makeText(this, "Recording shuru nahi hui — mic busy hai", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Stop recording.
     * @param silent true on call-end (no Toast), false on manual stop.
     */
    private void stopCallRecording(boolean silent) {
        if (!isRecording || callRecorder == null) return;
        String savedPath = callRecorder.stop();
        callRecorder = null;
        isRecording = false;
        updateRecordUi(false);
        if (!silent) {
            if (savedPath != null) {
                Toast.makeText(this, "Recording saved: " + new java.io.File(savedPath).getName(), Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "Recording save nahi hui", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void updateRecordUi(boolean active) {
        if (binding.btnRecord == null) return;
        binding.btnRecord.setImageResource(
            active ? android.R.drawable.presence_busy : R.drawable.ic_record);
        binding.btnRecord.setAlpha(active ? 1.0f : 0.75f);
        if (binding.tvRecordLabel != null)
            binding.tvRecordLabel.setText(active ? "Stop" : "Record");
        if (active) {
            // Pulsing red tint to indicate recording
            binding.btnRecord.setColorFilter(
                android.graphics.Color.RED, android.graphics.PorterDuff.Mode.SRC_IN);
        } else {
            binding.btnRecord.clearColorFilter();
        }
    }

    // REQ_RECORD case handled in existing onRequestPermissionsResult above
    private SdpObserver noopSdp() {
        return new SdpObserver() {
            @Override public void onCreateSuccess(SessionDescription s) {}
            @Override public void onSetSuccess() {}
            @Override public void onCreateFailure(String e) {}
            @Override public void onSetFailure(String e) {}
        };
    }

    @Override
    public void onBackPressed() {
        // Back = call end karo silently nahi
        endCall();
    }

    @Override
    protected void onDestroy() {
        if (!finishing) releaseWebRTC();
        if (ticker != null) tick.removeCallbacks(ticker);
        cancelPendingIceRestart();
        if (timeoutRunnable != null) timeoutHandler.removeCallbacks(timeoutRunnable);
        // Proximity wake lock release
        try {
            if (proximityWakeLock != null && proximityWakeLock.isHeld())
                proximityWakeLock.release();
        } catch (Exception ignored) {}
        super.onDestroy();
    }
}
