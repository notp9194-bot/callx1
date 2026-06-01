package com.callx.app.activities;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import com.bumptech.glide.Glide;
import com.callx.app.calls.databinding.ActivityCallBinding;
import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.CallLogEntity;
import com.callx.app.services.CallForegroundService;
import com.callx.app.utils.CallAudioFocusManager;
import com.callx.app.utils.CallEncryptionHelper;
import com.callx.app.utils.CallNetworkMonitor;
import com.callx.app.utils.Constants;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.PushNotify;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;
import org.json.JSONObject;
import org.webrtc.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * CallActivity — Production-grade 1:1 audio and video call.
 *
 * Production improvements over v15:
 *  - CallAudioFocusManager: proper AudioFocus lifecycle (requestFocus → abandonFocus)
 *  - CallNetworkMonitor: real-time quality indicator (Excellent/Good/Fair/Poor) via RTCStats
 *  - CallEncryptionHelper: DTLS-SRTP status verification badge in UI
 *  - Bluetooth SCO routing: detect headset, switch with AudioManager
 *  - CallSettingsActivity integration: noise suppression, echo cancel, video quality from prefs
 *  - Data saver mode: caps video to SD when enabled in settings
 *  - ICE restart state machine: exponential backoff (2s → 4s → 8s), max 3 restarts
 *  - Reconnecting overlay: shows "Reconnecting…" chip when ICE disconnected/failed
 *  - Network loss toast: "Network lost — reconnecting" on connectivity drop
 *  - Encryption badge: shows 🔒 Encrypted after DTLS handshake confirmed
 *  - Settings button: opens CallSettingsActivity mid-call (changes take effect next call)
 *  - Proper cleanup: AudioFocus abandoned, network monitor stopped, BT SCO stopped
 */
public class CallActivity extends AppCompatActivity {

    // ── UI ────────────────────────────────────────────────────────────────
    private ActivityCallBinding binding;

    // ── Call params ───────────────────────────────────────────────────────
    private String  partnerUid, partnerName, partnerPhoto, partnerThumb, callId;
    private boolean isCaller, isVideo;
    private boolean micOn = true, camOn = true, speakerOn = false;
    private boolean usingFrontCamera = true;
    private long    startedAt        = 0;
    private boolean callConnected    = false;
    private boolean finishing        = false;

    // ── Timer ─────────────────────────────────────────────────────────────
    private final Handler tick   = new Handler(Looper.getMainLooper());
    private Runnable      ticker;

    // ── ICE restart state machine ─────────────────────────────────────────
    private int      iceRestartCount   = 0;
    private long     iceRestartDelayMs = Constants.ICE_RESTART_DELAY_MS;
    private final Handler  iceHandler  = new Handler(Looper.getMainLooper());
    private Runnable       iceRunnable;

    // ── Wake lock ─────────────────────────────────────────────────────────
    private PowerManager.WakeLock wakeLock;

    // ── Audio ─────────────────────────────────────────────────────────────
    private AudioManager          audioManager;
    private CallAudioFocusManager audioFocusManager;
    private boolean               btHeadsetConnected = false;
    private BroadcastReceiver     btScoReceiver;

    // ── WebRTC ────────────────────────────────────────────────────────────
    private EglBase             eglBase;
    private PeerConnectionFactory factory;
    private PeerConnection      peerConnection;
    private VideoTrack          localVideoTrack;
    private AudioTrack          localAudioTrack;
    private CameraVideoCapturer videoCapturer;
    private VideoSource         videoSource;
    private AudioSource         audioSource;
    private SurfaceTextureHelper surfaceHelper;
    private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor();

    // ── Firebase signaling ────────────────────────────────────────────────
    private DatabaseReference     callRef;
    private ValueEventListener    statusListener;
    private ValueEventListener    iceRestartRequestListener;
    private ChildEventListener     remoteCandidateListener;
    private boolean               remoteDescSet = false;
    private final List<IceCandidate> pendingCandidates = new ArrayList<>();

    // ── Production helpers ────────────────────────────────────────────────
    private CallNetworkMonitor    networkMonitor;
    private CallEncryptionHelper  encryptionHelper;
    private SharedPreferences     callPrefs;

    // ── Network callback ──────────────────────────────────────────────────
    private android.net.ConnectivityManager.NetworkCallback networkCallback;

    // ── Settings ──────────────────────────────────────────────────────────
    private boolean settingNoiseSuppression = true;
    private boolean settingEchoCancellation = true;
    private boolean settingAutoGain         = true;
    private String  settingVideoQuality     = "hd";
    private int     settingFps              = 30;
    private boolean settingDataSaver        = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
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

        // ── Load settings ─────────────────────────────────────────────────
        callPrefs            = getSharedPreferences(CallSettingsActivity.PREFS_NAME, MODE_PRIVATE);
        settingNoiseSuppression = callPrefs.getBoolean(CallSettingsActivity.KEY_NOISE_SUPPRESSION, true);
        settingEchoCancellation = callPrefs.getBoolean(CallSettingsActivity.KEY_ECHO_CANCELLATION, true);
        settingAutoGain         = callPrefs.getBoolean(CallSettingsActivity.KEY_AUTO_GAIN, true);
        settingVideoQuality     = callPrefs.getString(CallSettingsActivity.KEY_VIDEO_QUALITY, "hd");
        settingFps              = callPrefs.getInt(CallSettingsActivity.KEY_FPS, 30);
        settingDataSaver        = callPrefs.getBoolean(CallSettingsActivity.KEY_DATA_SAVER, false);

        audioManager       = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        audioFocusManager  = new CallAudioFocusManager(this);
        encryptionHelper   = new CallEncryptionHelper();

        acquireWakeLock();
        audioFocusManager.requestFocus();
        detectBluetoothHeadset();
        registerBtScoReceiver();

        // ── UI setup ──────────────────────────────────────────────────────
        binding.tvCallerName.setText(partnerName != null ? partnerName : "Unknown");
        binding.tvCallStatus.setText(isCaller
            ? (isVideo ? "Video calling…" : "Calling…") : "Connecting…");

        // Show encryption badge as PENDING initially
        updateEncryptionBadge(CallEncryptionHelper.EncryptionStatus.PENDING);
        // Hide quality chip until connected
        if (binding.tvNetworkQuality != null) binding.tvNetworkQuality.setVisibility(View.GONE);

        if (!isVideo) {
            if (binding.localVideo  != null) binding.localVideo.setVisibility(View.GONE);
            if (binding.remoteVideo != null) binding.remoteVideo.setVisibility(View.GONE);
            if (binding.btnToggleCamera != null) binding.btnToggleCamera.setVisibility(View.GONE);
            if (binding.btnSwitchCamera != null) binding.btnSwitchCamera.setVisibility(View.GONE);
        }

        String avatarUrl = (partnerThumb != null && !partnerThumb.isEmpty()) ? partnerThumb : partnerPhoto;
        if (avatarUrl != null && !avatarUrl.isEmpty())
            Glide.with(this).load(avatarUrl).circleCrop().into(binding.ivCallAvatar);

        // ── Button listeners ──────────────────────────────────────────────
        binding.btnEndCall.setOnClickListener(v -> endCall());
        binding.btnToggleMic.setOnClickListener(v -> toggleMic());
        if (binding.btnToggleCamera != null) binding.btnToggleCamera.setOnClickListener(v -> toggleCamera());
        if (binding.btnSwitchCamera != null) binding.btnSwitchCamera.setOnClickListener(v -> switchCamera());
        binding.btnToggleSpeaker.setOnClickListener(v -> toggleSpeaker());
        if (binding.btnSettings != null)
            binding.btnSettings.setOnClickListener(v -> openSettings());

        checkPermsAndInit();
    }

    // ── Settings ──────────────────────────────────────────────────────────

    private void openSettings() {
        Intent i = new Intent(this, CallSettingsActivity.class);
        startActivity(i);
    }

    // ── Bluetooth headset detection ───────────────────────────────────────

    private void detectBluetoothHeadset() {
        try {
            BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
            if (bt != null && bt.isEnabled()) {
                btHeadsetConnected = bt.getProfileConnectionState(BluetoothProfile.HEADSET)
                    == BluetoothProfile.STATE_CONNECTED;
            }
        } catch (Exception ignored) {}
    }

    private void registerBtScoReceiver() {
        btScoReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent intent) {
                int state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1);
                if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                    btHeadsetConnected = true;
                    updateSpeakerIcon();
                } else if (state == AudioManager.SCO_AUDIO_STATE_DISCONNECTED) {
                    btHeadsetConnected = false;
                    updateSpeakerIcon();
                }
            }
        };
        try {
            registerReceiver(btScoReceiver,
                new IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED));
        } catch (Exception ignored) {}
    }

    private void updateSpeakerIcon() {
        // Update speaker button to reflect BT state
        if (binding.btnToggleSpeaker != null)
            binding.btnToggleSpeaker.setAlpha(speakerOn || btHeadsetConnected ? 1f : 0.5f);
    }

    // ── Wake lock ─────────────────────────────────────────────────────────

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "callx:call_active");
            wakeLock.acquire(3 * 60 * 60 * 1000L);
        } catch (Exception ignored) {}
    }

    // ── Permissions ───────────────────────────────────────────────────────

    private void checkPermsAndInit() {
        String[] perms = isVideo
            ? new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA}
            : new String[]{Manifest.permission.RECORD_AUDIO};
        boolean ok = true;
        for (String p : perms)
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) { ok = false; break; }
        if (!ok) ActivityCompat.requestPermissions(this, perms, 401);
        else     fetchTurnThenInitWebRTC();
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

    // ── TURN fetch ────────────────────────────────────────────────────────

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
        return list;
    }

    // ── WebRTC init ───────────────────────────────────────────────────────

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
            @Override public void onIceCandidate(IceCandidate c)           { sendCandidate(c); }
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

        // Audio track
        audioSource     = factory.createAudioSource(buildAudioConstraints());
        localAudioTrack = factory.createAudioTrack("audio0", audioSource);
        localAudioTrack.setEnabled(true);

        // Video track
        if (isVideo) {
            boolean hd = !settingDataSaver && "hd".equals(settingVideoQuality);
            int w = hd ? Constants.VIDEO_WIDTH_HD  : Constants.VIDEO_WIDTH_VGA;
            int h = hd ? Constants.VIDEO_HEIGHT_HD : Constants.VIDEO_HEIGHT_VGA;
            int fps = settingDataSaver ? 15 : settingFps;

            videoSource   = factory.createVideoSource(false);
            surfaceHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.getEglBaseContext());
            videoCapturer = createCapturer(true);
            if (videoCapturer != null) {
                videoCapturer.initialize(surfaceHelper, this, videoSource.getCapturerObserver());
                videoCapturer.startCapture(w, h, fps);
            }
            localVideoTrack = factory.createVideoTrack("video0", videoSource);
            localVideoTrack.setEnabled(true);
            localVideoTrack.addSink(binding.localVideo);
        }

        // Add tracks
        List<String> ids = new ArrayList<>(); ids.add("stream0");
        peerConnection.addTrack(localAudioTrack, ids);
        if (isVideo && localVideoTrack != null) peerConnection.addTrack(localVideoTrack, ids);

        // Audio routing
        boolean defaultSpeaker = isVideo || "speaker".equals(
            callPrefs.getString(CallSettingsActivity.KEY_ROUTING, "earpiece"));
        if (isVideo && callPrefs.getBoolean(CallSettingsActivity.KEY_AUTO_SPEAKER_VIDEO, true))
            defaultSpeaker = true;
        speakerOn = defaultSpeaker;
        audioFocusManager.configureForCall(speakerOn);

        // Firebase setup
        if (callId == null) {
            if (isCaller) callId = FirebaseUtils.db().getReference("activeCalls").push().getKey();
            else {
                android.util.Log.e("CallActivity", "Callee callId null — FCM issue. Aborting.");
                finish(); return;
            }
        }
        callRef = FirebaseUtils.db().getReference("activeCalls").child(callId);

        if (isCaller) {
            String myUid  = FirebaseUtils.getCurrentUid();
            String myName = FirebaseUtils.getCurrentName();
            Map<String, Object> c = new HashMap<>();
            c.put("from", myUid); c.put("fromName", myName);
            c.put("to", partnerUid);
            c.put("status", "calling");
            c.put("isVideo", isVideo);
            c.put("timestamp", System.currentTimeMillis());
            callRef.setValue(c);
            watchForAnswer();
        } else {
            callRef.child("status").setValue("accepted");
        }

        watchCallStatus();
        watchRemoteCandidates();
        watchIceRestartRequest();

        if (!isCaller) createAnswer();
    }

    private MediaConstraints buildAudioConstraints() {
        MediaConstraints mc = new MediaConstraints();
        mc.mandatory.add(new MediaConstraints.KeyValuePair("googEchoCancellation", String.valueOf(settingEchoCancellation)));
        mc.mandatory.add(new MediaConstraints.KeyValuePair("googNoiseSuppression", String.valueOf(settingNoiseSuppression)));
        mc.mandatory.add(new MediaConstraints.KeyValuePair("googAutoGainControl",  String.valueOf(settingAutoGain)));
        mc.mandatory.add(new MediaConstraints.KeyValuePair("googHighpassFilter",   "true"));
        mc.mandatory.add(new MediaConstraints.KeyValuePair("googTypingNoiseDetection", "true"));
        return mc;
    }

    private CameraVideoCapturer createCapturer(boolean front) {
        Camera2Enumerator e = new Camera2Enumerator(this);
        for (String n : e.getDeviceNames()) {
            if (front ? e.isFrontFacing(n) : e.isBackFacing(n)) {
                CameraVideoCapturer c = e.createCapturer(n, null);
                if (c != null) return c;
            }
        }
        for (String n : e.getDeviceNames()) {
            CameraVideoCapturer c = e.createCapturer(n, null);
            if (c != null) return c;
        }
        return null;
    }

    // ── ICE state handling ────────────────────────────────────────────────

    private void handleIceStateChange(PeerConnection.IceConnectionState state) {
        switch (state) {
            case CONNECTED:
            case COMPLETED:
                cancelPendingIceRestart();
                iceRestartCount   = 0;
                iceRestartDelayMs = Constants.ICE_RESTART_DELAY_MS;
                onCallConnected();
                showReconnectingOverlay(false);
                // Start network quality monitor
                startNetworkMonitor();
                // Check encryption status after connection
                checkEncryptionStatus();
                break;
            case DISCONNECTED:
                showReconnectingOverlay(true);
                scheduleIceRestart();
                break;
            case FAILED:
                showReconnectingOverlay(true);
                scheduleIceRestart();
                break;
            case CLOSED:
                endCall();
                break;
            default:
                break;
        }
    }

    private void scheduleIceRestart() {
        if (iceRestartCount >= Constants.ICE_MAX_RESTARTS) {
            runOnUiThread(() -> {
                Toast.makeText(this, "Connection lost", Toast.LENGTH_LONG).show();
                endCall();
            });
            return;
        }
        cancelPendingIceRestart();
        long delay = (long)(iceRestartDelayMs * Math.pow(Constants.ICE_RESTART_BACKOFF, iceRestartCount));
        iceRunnable = () -> {
            iceRestartCount++;
            iceRestartDelayMs = Constants.ICE_RESTART_DELAY_MS;
            if (peerConnection != null && isCaller) {
                MediaConstraints mc = new MediaConstraints();
                mc.mandatory.add(new MediaConstraints.KeyValuePair("IceRestart", "true"));
                peerConnection.createOffer(new SdpObserver() {
                    @Override public void onCreateSuccess(SessionDescription sdp) {
                        peerConnection.setLocalDescription(noopSdp(), sdp);
                        if (callRef != null) callRef.child("offer").setValue(sdp.description);
                    }
                    @Override public void onSetSuccess() {}
                    @Override public void onCreateFailure(String e) {}
                    @Override public void onSetFailure(String e) {}
                }, mc);
            } else if (callRef != null) {
                // Callee requests restart via Firebase signal
                callRef.child("iceRestartRequest").setValue(System.currentTimeMillis());
            }
        };
        iceHandler.postDelayed(iceRunnable, delay);
    }

    private void cancelPendingIceRestart() {
        if (iceRunnable != null) { iceHandler.removeCallbacks(iceRunnable); iceRunnable = null; }
    }

    // ── Network quality monitor ───────────────────────────────────────────

    private void startNetworkMonitor() {
        if (peerConnection == null) return;
        networkMonitor = new CallNetworkMonitor(this, peerConnection,
            new CallNetworkMonitor.Callback() {
                @Override public void onQualityChanged(CallNetworkMonitor.Quality q, String label) {
                    updateQualityChip(q, label);
                }
                @Override public void onNetworkLost() {
                    showToast("Network lost — reconnecting");
                }
                @Override public void onNetworkRestored() {
                    showToast("Network restored");
                }
            });
        networkMonitor.start();
    }

    private void updateQualityChip(CallNetworkMonitor.Quality quality, String label) {
        if (binding.tvNetworkQuality == null) return;
        binding.tvNetworkQuality.setVisibility(View.VISIBLE);
        binding.tvNetworkQuality.setText(label);
        int color;
        switch (quality) {
            case EXCELLENT: color = 0xFF22C55E; break;
            case GOOD:      color = 0xFF84CC16; break;
            case FAIR:      color = 0xFFF59E0B; break;
            case POOR:      color = 0xFFEF4444; break;
            default:        color = 0xFF94A3B8; break;
        }
        binding.tvNetworkQuality.setTextColor(color);
    }

    // ── Encryption badge ──────────────────────────────────────────────────

    private void checkEncryptionStatus() {
        if (peerConnection == null) return;
        peerConnection.getStats(report -> {
            CallEncryptionHelper.EncryptionStatus status =
                CallEncryptionHelper.extractStatus(report);
            runOnUiThread(() -> updateEncryptionBadge(status));
        });
    }

    private void updateEncryptionBadge(CallEncryptionHelper.EncryptionStatus status) {
        if (binding.tvEncryptionBadge == null) return;
        binding.tvEncryptionBadge.setText(status.label);
        int color;
        switch (status) {
            case ENCRYPTED:   color = 0xFF22C55E; break;
            case PENDING:     color = 0xFFF59E0B; break;
            case UNENCRYPTED: color = 0xFFEF4444; break;
            default:          color = 0xFF94A3B8; break;
        }
        binding.tvEncryptionBadge.setTextColor(color);
        binding.tvEncryptionBadge.setVisibility(View.VISIBLE);
    }

    // ── Reconnecting overlay ──────────────────────────────────────────────

    private void showReconnectingOverlay(boolean show) {
        if (binding.tvReconnecting != null)
            binding.tvReconnecting.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    // ── Signaling ─────────────────────────────────────────────────────────

    private void watchForAnswer() {
        // Caller: create offer
        MediaConstraints mc = new MediaConstraints();
        mc.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        if (isVideo) mc.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));
        peerConnection.createOffer(new SdpObserver() {
            @Override public void onCreateSuccess(SessionDescription sdp) {
                peerConnection.setLocalDescription(noopSdp(), sdp);
                if (callRef != null) callRef.child("offer").setValue(sdp.description);
            }
            @Override public void onSetSuccess() {}
            @Override public void onCreateFailure(String e) {}
            @Override public void onSetFailure(String e) {}
        }, mc);

        // Watch for answer from callee
        callRef.child("answer").addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                String sdp = snap.getValue(String.class);
                if (sdp != null && !sdp.isEmpty() && peerConnection != null) {
                    SessionDescription remote = new SessionDescription(
                        SessionDescription.Type.ANSWER, sdp);
                    peerConnection.setRemoteDescription(noopSdp(), remote);
                    remoteDescSet = true;
                    drainPendingCandidates();
                }
            }
            @Override public void onCancelled(DatabaseError e) {}
        });
    }

    private void createAnswer() {
        // Callee: watch for offer
        callRef.child("offer").addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                String sdp = snap.getValue(String.class);
                if (sdp == null || sdp.isEmpty() || peerConnection == null) return;
                SessionDescription offer = new SessionDescription(
                    SessionDescription.Type.OFFER, sdp);
                peerConnection.setRemoteDescription(new SdpObserver() {
                    @Override public void onSetSuccess() {
                        remoteDescSet = true;
                        drainPendingCandidates();
                        MediaConstraints mc = new MediaConstraints();
                        peerConnection.createAnswer(new SdpObserver() {
                            @Override public void onCreateSuccess(SessionDescription ans) {
                                peerConnection.setLocalDescription(noopSdp(), ans);
                                if (callRef != null) callRef.child("answer").setValue(ans.description);
                            }
                            @Override public void onSetSuccess() {}
                            @Override public void onCreateFailure(String e) {}
                            @Override public void onSetFailure(String e) {}
                        }, mc);
                    }
                    @Override public void onCreateSuccess(SessionDescription s) {}
                    @Override public void onCreateFailure(String e) {}
                    @Override public void onSetFailure(String e) {}
                }, offer);
            }
            @Override public void onCancelled(DatabaseError e) {}
        });
    }

    private void watchRemoteCandidates() {
        String myUid = FirebaseUtils.getCurrentUid();
        if (myUid == null || callRef == null) return;
        remoteCandidateListener = new ChildEventListener() {
            @Override public void onChildAdded(DataSnapshot snap, String prev) {
                String sdp      = snap.child("sdp").getValue(String.class);
                String sdpMid   = snap.child("sdpMid").getValue(String.class);
                Integer sdpLine = snap.child("sdpMLineIndex").getValue(Integer.class);
                if (sdp == null) return;
                IceCandidate c = new IceCandidate(
                    sdpMid != null ? sdpMid : "",
                    sdpLine != null ? sdpLine : 0, sdp);
                if (remoteDescSet) peerConnection.addIceCandidate(c);
                else               pendingCandidates.add(c);
            }
            @Override public void onChildChanged(DataSnapshot s, String p) {}
            @Override public void onChildRemoved(DataSnapshot s) {}
            @Override public void onChildMoved(DataSnapshot s, String p) {}
            @Override public void onCancelled(DatabaseError e) {}
        };
        callRef.child("candidates").child(partnerUid).addChildEventListener(remoteCandidateListener);
    }

    private void drainPendingCandidates() {
        if (peerConnection == null) return;
        for (IceCandidate c : pendingCandidates) peerConnection.addIceCandidate(c);
        pendingCandidates.clear();
    }

    private void sendCandidate(IceCandidate c) {
        String myUid = FirebaseUtils.getCurrentUid();
        if (myUid == null || callRef == null) return;
        Map<String, Object> m = new HashMap<>();
        m.put("sdp",          c.sdp);
        m.put("sdpMid",       c.sdpMid);
        m.put("sdpMLineIndex", c.sdpMLineIndex);
        callRef.child("candidates").child(myUid).push().setValue(m);
    }

    private void watchCallStatus() {
        statusListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot s) {
                String st = s.getValue(String.class);
                if ("ended".equals(st) || "rejected".equals(st) || "cancelled".equals(st)) {
                    if (!callConnected && isCaller
                            && ("rejected".equals(st) || "cancelled".equals(st))) {
                        String myUid  = FirebaseUtils.getCurrentUid();
                        String myName = FirebaseUtils.getCurrentName();
                        if (myUid != null) {
                            PushNotify.notifyMissedCall(myUid, partnerUid != null ? partnerUid : "",
                                partnerName != null ? partnerName : "",
                                callId != null ? callId : "", isVideo,
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

    private void watchIceRestartRequest() {
        iceRestartRequestListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot s) {
                Long ts = s.getValue(Long.class);
                if (ts != null && ts > 0 && isCaller) {
                    iceRestartCount = 0;
                    scheduleIceRestart();
                }
            }
            @Override public void onCancelled(DatabaseError e) {}
        };
        callRef.child("iceRestartRequest").addValueEventListener(iceRestartRequestListener);
    }

    // ── Call connected ────────────────────────────────────────────────────

    private void onCallConnected() {
        if (callConnected) return;
        callConnected = true;
        iceRestartCount   = 0;
        iceRestartDelayMs = Constants.ICE_RESTART_DELAY_MS;
        startedAt = System.currentTimeMillis();
        binding.tvCallStatus.setText("Connected \u2022 0:00");
        if (isVideo && binding.ivCallAvatar != null) binding.ivCallAvatar.setVisibility(View.GONE);

        Intent fg = new Intent(this, CallForegroundService.class);
        fg.putExtra("name",         partnerName != null ? partnerName : "");
        fg.putExtra("callId",       callId != null ? callId : "");
        fg.putExtra("isVideo",      isVideo);
        fg.putExtra("partnerThumb", partnerThumb != null ? partnerThumb : "");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(fg);
        else startService(fg);

        ticker = new Runnable() {
            @Override public void run() {
                long e = (System.currentTimeMillis() - startedAt) / 1000;
                binding.tvCallStatus.setText(String.format("Connected \u2022 %d:%02d", e / 60, e % 60));
                tick.postDelayed(this, 1_000);
            }
        };
        tick.post(ticker);
    }

    // ── Controls ──────────────────────────────────────────────────────────

    private void toggleMic() {
        micOn = !micOn;
        if (localAudioTrack != null) localAudioTrack.setEnabled(micOn);
        binding.btnToggleMic.setAlpha(micOn ? 1f : 0.4f);
        // Notify foreground service so notification can show mute icon
        Intent fg = new Intent(this, CallForegroundService.class);
        fg.putExtra("micOn", micOn);
        fg.putExtra("updateMic", true);
        try { startService(fg); } catch (Exception ignored) {}
    }

    private void toggleCamera() {
        camOn = !camOn;
        if (localVideoTrack != null) localVideoTrack.setEnabled(camOn);
        if (binding.btnToggleCamera != null) binding.btnToggleCamera.setAlpha(camOn ? 1f : 0.4f);
    }

    private void switchCamera() {
        if (videoCapturer instanceof CameraVideoCapturer) {
            ((CameraVideoCapturer) videoCapturer).switchCamera(null);
            usingFrontCamera = !usingFrontCamera;
        }
    }

    private void toggleSpeaker() {
        speakerOn = !speakerOn;
        audioFocusManager.setSpeakerOn(speakerOn);
        binding.btnToggleSpeaker.setAlpha(speakerOn ? 1f : 0.5f);
    }

    private void enableSpeaker(boolean on) {
        speakerOn = on;
        audioFocusManager.configureForCall(on);
    }

    // ── End call ──────────────────────────────────────────────────────────

    private void endCall() {
        if (finishing) return;
        finishing = true;
        cancelPendingIceRestart();
        if (ticker != null) tick.removeCallbacks(ticker);
        if (networkMonitor != null) networkMonitor.stop();

        try { stopService(new Intent(this, CallForegroundService.class)); } catch (Exception ignored) {}
        if (callRef != null) {
            long dur = startedAt == 0 ? 0 : System.currentTimeMillis() - startedAt;
            callRef.child("status").setValue("ended");
            String myUid = FirebaseUtils.getCurrentUid();
            if (myUid != null) {
                final long     fDur       = dur;
                final long     fTimestamp = System.currentTimeMillis();
                final String   fMedia     = isVideo ? "video" : "audio";

                // My log
                Map<String, Object> myLog = new HashMap<>();
                myLog.put("partnerUid",  partnerUid);
                myLog.put("partnerName", partnerName);
                myLog.put("direction",   isCaller ? "outgoing" : "incoming");
                myLog.put("mediaType",   fMedia);
                myLog.put("timestamp",   fTimestamp);
                myLog.put("duration",    fDur);
                FirebaseUtils.getCallsRef(myUid).push().setValue(myLog)
                    .addOnFailureListener(e ->
                        android.util.Log.w("CallActivity", "Firebase my-log failed", e));

                // Partner log
                if (partnerUid != null && !partnerUid.isEmpty()) {
                    String myName = FirebaseUtils.getCurrentName();
                    Map<String, Object> partnerLog = new HashMap<>();
                    partnerLog.put("partnerUid",  myUid);
                    partnerLog.put("partnerName", myName != null ? myName : "");
                    partnerLog.put("direction",   isCaller ? "incoming" : "outgoing");
                    partnerLog.put("mediaType",   fMedia);
                    partnerLog.put("timestamp",   fTimestamp);
                    partnerLog.put("duration",    fDur);
                    FirebaseUtils.getCallsRef(partnerUid).push().setValue(partnerLog)
                        .addOnFailureListener(e ->
                            android.util.Log.w("CallActivity", "Firebase partner-log failed", e));
                }

                // Room cache
                bgExecutor.execute(() -> {
                    try {
                        CallLogEntity entity = new CallLogEntity();
                        entity.id          = UUID.randomUUID().toString();
                        entity.partnerUid  = partnerUid;
                        entity.partnerName = partnerName;
                        entity.direction   = isCaller ? "outgoing" : "incoming";
                        entity.mediaType   = fMedia;
                        entity.timestamp   = fTimestamp;
                        entity.duration    = fDur;
                        AppDatabase.getInstance(getApplicationContext()).callLogDao().insertCallLog(entity);
                    } catch (Exception ex) {
                        android.util.Log.w("CallActivity", "Room log failed", ex);
                    }
                });
            }
        }

        // Restore audio
        audioFocusManager.abandonFocus();
        audioFocusManager.restoreAudio();

        // BT SCO cleanup
        try {
            if (audioManager != null) {
                audioManager.stopBluetoothSco();
                audioManager.setBluetoothScoOn(false);
            }
        } catch (Exception ignored) {}

        releaseWebRTC();
        finish();
    }

    private void releaseWebRTC() {
        try { unregisterNetworkCallback(); } catch (Exception ignored) {}
        try { if (btScoReceiver != null) unregisterReceiver(btScoReceiver); } catch (Exception ignored) {}
        try {
            if (remoteCandidateListener != null && callRef != null)
                callRef.child("candidates").child(partnerUid).removeEventListener(remoteCandidateListener);
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

    private void unregisterNetworkCallback() {}

    // ── Helpers ───────────────────────────────────────────────────────────

    private void showToast(String msg) {
        if (finishing) return;
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
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
        if (!finishing) {
            audioFocusManager.abandonFocus();
            audioFocusManager.restoreAudio();
            releaseWebRTC();
        }
        if (networkMonitor != null) networkMonitor.stop();
        if (ticker != null) tick.removeCallbacks(ticker);
        cancelPendingIceRestart();
        super.onDestroy();
    }
}
