package com.callx.app.activities;

import android.Manifest;
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
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import com.bumptech.glide.Glide;
import com.callx.app.databinding.ActivityCallBinding;
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

public class CallActivity extends AppCompatActivity {

    // UI
    private ActivityCallBinding binding;

    // Call params
    private String partnerUid, partnerName, partnerPhoto, callId;
    private boolean isCaller, isVideo;
    private boolean micOn = true, camOn = true, speakerOn = false;
    private boolean usingFrontCamera = true;
    private long startedAt = 0;
    private boolean callConnected = false;
    private boolean finishing = false;

    // Timer
    private final Handler tick = new Handler(Looper.getMainLooper());
    private Runnable ticker;

    // ICE reconnection
    private int iceRestartCount = 0;
    private final Handler iceRestartHandler = new Handler(Looper.getMainLooper());
    private Runnable iceRestartRunnable;

    // Wake lock (keep screen on)
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

        // Keep screen on for the entire call
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);

        binding = ActivityCallBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        partnerUid   = getIntent().getStringExtra("partnerUid");
        partnerName  = getIntent().getStringExtra("partnerName");
        partnerPhoto = getIntent().getStringExtra("partnerPhoto");
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
        }

        if (partnerPhoto != null && !partnerPhoto.isEmpty()) {
            Glide.with(this).load(partnerPhoto).circleCrop().into(binding.ivCallAvatar);
        }

        binding.btnEndCall.setOnClickListener(v -> endCall());
        binding.btnToggleMic.setOnClickListener(v -> toggleMic());
        binding.btnToggleCamera.setOnClickListener(v -> toggleCamera());
        binding.btnSwitchCamera.setOnClickListener(v -> switchCamera());
        binding.btnToggleSpeaker.setOnClickListener(v -> toggleSpeaker());

        checkPermsAndInit();
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "callx:call_active");
            wakeLock.acquire(3 * 60 * 60 * 1000L); // max 3 hours
        } catch (Exception ignored) {}
    }

    private void checkPermsAndInit() {
        String[] perms = isVideo
            ? new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA}
            : new String[]{Manifest.permission.RECORD_AUDIO};
        boolean ok = true;
        for (String p : perms) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                ok = false; break;
            }
        }
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
                    BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    JSONObject j = new JSONObject(sb.toString());
                    String turnUrl  = j.optString("url",  "");
                    String user     = j.optString("username", "");
                    String cred     = j.optString("credential", "");
                    if (!turnUrl.isEmpty() && !user.isEmpty() && !cred.isEmpty()) {
                        iceServers.clear();
                        iceServers.add(PeerConnection.IceServer.builder(Constants.STUN_GOOGLE_1)
                            .createIceServer());
                        iceServers.add(PeerConnection.IceServer.builder(Constants.STUN_GOOGLE_2)
                            .createIceServer());
                        iceServers.add(PeerConnection.IceServer.builder(turnUrl)
                            .setUsername(user)
                            .setPassword(cred)
                            .createIceServer());
                    }
                }
            } catch (Exception e) {
                // Fallback to STUN-only — call may still work on good networks
            }
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
            PeerConnectionFactory.InitializationOptions.builder(this)
                .createInitializationOptions());

        factory = PeerConnectionFactory.builder()
            .setOptions(new PeerConnectionFactory.Options())
            .setVideoEncoderFactory(
                new DefaultVideoEncoderFactory(eglBase.getEglBaseContext(), true, true))
            .setVideoDecoderFactory(
                new DefaultVideoDecoderFactory(eglBase.getEglBaseContext()))
            .createPeerConnectionFactory();

        PeerConnection.RTCConfiguration rtcConfig =
            new PeerConnection.RTCConfiguration(iceServers);
        rtcConfig.sdpSemantics          = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        rtcConfig.iceTransportsType     = PeerConnection.IceTransportsType.ALL;
        rtcConfig.bundlePolicy          = PeerConnection.BundlePolicy.MAXBUNDLE;
        rtcConfig.rtcpMuxPolicy         = PeerConnection.RtcpMuxPolicy.REQUIRE;
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

        // Audio
        audioSource = factory.createAudioSource(buildAudioConstraints());
        localAudioTrack = factory.createAudioTrack("audio0", audioSource);
        localAudioTrack.setEnabled(true);

        // Video
        if (isVideo) {
            videoSource   = factory.createVideoSource(false);
            surfaceHelper = SurfaceTextureHelper.create("CaptureThread",
                                eglBase.getEglBaseContext());
            videoCapturer = createCapturer(true);
            if (videoCapturer != null) {
                videoCapturer.initialize(surfaceHelper, this, videoSource.getCapturerObserver());
                startCapture();
            }
            localVideoTrack = factory.createVideoTrack("video0", videoSource);
            localVideoTrack.setEnabled(true);
            localVideoTrack.addSink(binding.localVideo);
        }

        // Add tracks
        List<String> ids = new ArrayList<>();
        ids.add("stream0");
        peerConnection.addTrack(localAudioTrack, ids);
        if (isVideo && localVideoTrack != null) peerConnection.addTrack(localVideoTrack, ids);

        // Set up audio routing (earpiece by default for audio calls, speaker for video)
        if (isVideo) enableSpeaker(true);
        else         enableSpeaker(false);

        // Firebase setup
        if (callId == null) callId = FirebaseUtils.db().getReference("activeCalls").push().getKey();
        callRef = FirebaseUtils.db().getReference("activeCalls").child(callId);

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
        } else {
            callRef.child("status").setValue("accepted");
            watchForOffer();
        }

        watchCallStatus();
        watchRemoteCandidates();
        registerNetworkCallback();
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
            videoCapturer.startCapture(
                Constants.VIDEO_WIDTH_HD, Constants.VIDEO_HEIGHT_HD, Constants.VIDEO_FPS);
        } catch (Exception e) {
            try {
                videoCapturer.startCapture(
                    Constants.VIDEO_WIDTH_VGA, Constants.VIDEO_HEIGHT_VGA, Constants.VIDEO_FPS);
            } catch (Exception ignored) {}
        }
    }

    private CameraVideoCapturer createCapturer(boolean preferFront) {
        Camera2Enumerator e = new Camera2Enumerator(this);
        String[] devices = e.getDeviceNames();
        // First pass: preferred direction
        for (String d : devices)
            if (preferFront ? e.isFrontFacing(d) : e.isBackFacing(d))
                return e.createCapturer(d, null);
        // Second pass: any camera
        for (String d : devices)
            return e.createCapturer(d, null);
        return null;
    }

    // ── Camera switch ──────────────────────────────────────────────────────

    private void switchCamera() {
        if (!isVideo || videoCapturer == null) return;
        usingFrontCamera = !usingFrontCamera;
        if (videoCapturer instanceof CameraVideoCapturer) {
            ((CameraVideoCapturer) videoCapturer).switchCamera(null);
        }
        // Mirror local video for front, don't mirror for back
        binding.localVideo.setMirror(usingFrontCamera);
    }

    // ── Speaker toggle ─────────────────────────────────────────────────────

    private void toggleSpeaker() {
        speakerOn = !speakerOn;
        enableSpeaker(speakerOn);
        binding.btnToggleSpeaker.setAlpha(speakerOn ? 1f : 0.4f);
    }

    private void enableSpeaker(boolean on) {
        if (audioManager == null) return;
        speakerOn = on;
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        audioManager.setSpeakerphoneOn(on);
        binding.btnToggleSpeaker.setAlpha(on ? 1f : 0.4f);
    }

    // ── ICE state / reconnection ───────────────────────────────────────────

    private void handleIceStateChange(PeerConnection.IceConnectionState s) {
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
            if (callConnected) {
                scheduleIceRestart();
            } else {
                Toast.makeText(this, "Call connection failed", Toast.LENGTH_SHORT).show();
                endCall();
            }
        }
    }

    private void scheduleIceRestart() {
        cancelPendingIceRestart();
        if (iceRestartCount >= Constants.ICE_MAX_RESTARTS) {
            Toast.makeText(this, "Connection lost", Toast.LENGTH_SHORT).show();
            endCall();
            return;
        }
        iceRestartRunnable = () -> {
            iceRestartCount++;
            if (peerConnection != null && isCaller) {
                // Caller restarts ICE by creating a new offer
                MediaConstraints mc = new MediaConstraints();
                mc.mandatory.add(new MediaConstraints.KeyValuePair("IceRestart", "true"));
                peerConnection.createOffer(new SdpObserver() {
                    @Override public void onCreateSuccess(SessionDescription sdp) {
                        peerConnection.setLocalDescription(noopSdp(), sdp);
                        Map<String, Object> m = new HashMap<>();
                        m.put("type", sdp.type.canonicalForm());
                        m.put("sdp", sdp.description);
                        callRef.child("offer").setValue(m);
                    }
                    @Override public void onSetSuccess() {}
                    @Override public void onCreateFailure(String e) { endCall(); }
                    @Override public void onSetFailure(String e) {}
                }, mc);
            }
        };
        iceRestartHandler.postDelayed(iceRestartRunnable, Constants.ICE_RECONNECT_TIMEOUT_MS);
    }

    private void cancelPendingIceRestart() {
        if (iceRestartRunnable != null) {
            iceRestartHandler.removeCallbacks(iceRestartRunnable);
            iceRestartRunnable = null;
        }
    }

    // ── Network change monitoring ──────────────────────────────────────────

    private void registerNetworkCallback() {
        try {
            ConnectivityManager cm =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            NetworkRequest req = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) {
                    if (callConnected && peerConnection != null) {
                        runOnUiThread(() -> {
                            iceRestartCount = 0;
                            scheduleIceRestart();
                        });
                    }
                }
            };
            cm.registerNetworkCallback(req, networkCallback);
        } catch (Exception ignored) {}
    }

    private void unregisterNetworkCallback() {
        try {
            ConnectivityManager cm =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null && networkCallback != null)
                cm.unregisterNetworkCallback(networkCallback);
        } catch (Exception ignored) {}
    }

    // ── SDP offer/answer ───────────────────────────────────────────────────

    private void createOffer() {
        MediaConstraints mc = new MediaConstraints();
        mc.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        if (isVideo) mc.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));
        peerConnection.createOffer(new SdpObserver() {
            @Override public void onCreateSuccess(SessionDescription sdp) {
                peerConnection.setLocalDescription(noopSdp(), sdp);
                Map<String, Object> m = new HashMap<>();
                m.put("type", sdp.type.canonicalForm()); m.put("sdp", sdp.description);
                callRef.child("offer").setValue(m);
                watchForAnswer();
            }
            @Override public void onSetSuccess() {}
            @Override public void onCreateFailure(String e) {}
            @Override public void onSetFailure(String e) {}
        }, mc);
    }

    private void watchForAnswer() {
        callRef.child("answer").addValueEventListener(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot s) {
                if (!s.exists() || peerConnection == null ||
                    peerConnection.getRemoteDescription() != null) return;
                String type = s.child("type").getValue(String.class);
                String sdp  = s.child("sdp").getValue(String.class);
                if (type == null || sdp == null) return;
                SessionDescription answer = new SessionDescription(
                    SessionDescription.Type.fromCanonicalForm(type), sdp);
                peerConnection.setRemoteDescription(new SdpObserver() {
                    @Override public void onSetSuccess() { remoteDescSet = true; drainCandidates(); }
                    @Override public void onCreateSuccess(SessionDescription s) {}
                    @Override public void onSetFailure(String e) {}
                    @Override public void onCreateFailure(String e) {}
                }, answer);
            }
            @Override public void onCancelled(DatabaseError e) {}
        });
    }

    private void watchForOffer() {
        callRef.child("offer").addValueEventListener(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot s) {
                if (!s.exists() || peerConnection == null) return;
                String type = s.child("type").getValue(String.class);
                String sdp  = s.child("sdp").getValue(String.class);
                if (type == null || sdp == null) return;
                // ICE restart: allow re-processing offer even if remote desc already set
                SessionDescription offer = new SessionDescription(
                    SessionDescription.Type.fromCanonicalForm(type), sdp);
                peerConnection.setRemoteDescription(new SdpObserver() {
                    @Override public void onSetSuccess() {
                        remoteDescSet = true; drainCandidates(); createAnswer();
                    }
                    @Override public void onCreateSuccess(SessionDescription s) {}
                    @Override public void onSetFailure(String e) {}
                    @Override public void onCreateFailure(String e) {}
                }, offer);
            }
            @Override public void onCancelled(DatabaseError e) {}
        });
    }

    private void createAnswer() {
        MediaConstraints mc = new MediaConstraints();
        mc.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        if (isVideo) mc.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));
        peerConnection.createAnswer(new SdpObserver() {
            @Override public void onCreateSuccess(SessionDescription sdp) {
                peerConnection.setLocalDescription(noopSdp(), sdp);
                Map<String, Object> m = new HashMap<>();
                m.put("type", sdp.type.canonicalForm()); m.put("sdp", sdp.description);
                callRef.child("answer").setValue(m);
            }
            @Override public void onSetSuccess() {}
            @Override public void onCreateFailure(String e) {}
            @Override public void onSetFailure(String e) {}
        }, mc);
    }

    // ── ICE candidates ─────────────────────────────────────────────────────

    private void sendCandidate(IceCandidate c) {
        if (callRef == null) return;
        String myUid = FirebaseUtils.getCurrentUid();
        if (myUid == null) return;
        Map<String, Object> m = new HashMap<>();
        m.put("candidate", c.sdp);
        m.put("sdpMid", c.sdpMid);
        m.put("sdpMLineIndex", c.sdpMLineIndex);
        callRef.child("candidates").child(myUid).push().setValue(m);
    }

    private void watchRemoteCandidates() {
        remoteCandidateListener = new ChildEventListener() {
            @Override public void onChildAdded(DataSnapshot s, String p) {
                String cand = s.child("candidate").getValue(String.class);
                String mid  = s.child("sdpMid").getValue(String.class);
                Integer idx = s.child("sdpMLineIndex").getValue(Integer.class);
                if (cand == null || mid == null || idx == null) return;
                IceCandidate ic = new IceCandidate(mid, idx, cand);
                if (remoteDescSet) peerConnection.addIceCandidate(ic);
                else pendingCandidates.add(ic);
            }
            @Override public void onChildChanged(DataSnapshot s, String p) {}
            @Override public void onChildRemoved(DataSnapshot s) {}
            @Override public void onChildMoved(DataSnapshot s, String p) {}
            @Override public void onCancelled(DatabaseError e) {}
        };
        callRef.child("candidates").child(partnerUid)
            .addChildEventListener(remoteCandidateListener);
    }

    private void drainCandidates() {
        for (IceCandidate ic : pendingCandidates) peerConnection.addIceCandidate(ic);
        pendingCandidates.clear();
    }

    // ── Call lifecycle ─────────────────────────────────────────────────────

    private void watchCallStatus() {
        statusListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot s) {
                String st = s.getValue(String.class);
                if ("ended".equals(st) || "rejected".equals(st) || "cancelled".equals(st)) {
                    runOnUiThread(() -> endCall());
                }
            }
            @Override public void onCancelled(DatabaseError e) {}
        };
        callRef.child("status").addValueEventListener(statusListener);
    }

    private void onCallConnected() {
        if (callConnected) return;
        callConnected = true;
        iceRestartCount = 0;
        startedAt = System.currentTimeMillis();
        binding.tvCallStatus.setText("Connected \u2022 0:00");
        if (isVideo) binding.ivCallAvatar.setVisibility(View.GONE);

        Intent fg = new Intent(this, CallForegroundService.class);
        fg.putExtra("name",    partnerName != null ? partnerName : "");
        fg.putExtra("callId",  callId != null ? callId : "");
        fg.putExtra("isVideo", isVideo);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            startForegroundService(fg);
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

    private void toggleMic() {
        micOn = !micOn;
        if (localAudioTrack != null) localAudioTrack.setEnabled(micOn);
        binding.btnToggleMic.setAlpha(micOn ? 1f : 0.4f);
    }

    private void toggleCamera() {
        camOn = !camOn;
        if (localVideoTrack != null) localVideoTrack.setEnabled(camOn);
        binding.btnToggleCamera.setAlpha(camOn ? 1f : 0.4f);
    }

    private void endCall() {
        if (finishing) return;
        finishing = true;
        cancelPendingIceRestart();
        if (ticker != null) tick.removeCallbacks(ticker);
        try { stopService(new Intent(this, CallForegroundService.class)); } catch (Exception ignored) {}
        if (callRef != null) {
            long dur = startedAt == 0 ? 0 : System.currentTimeMillis() - startedAt;
            callRef.child("status").setValue("ended");
            String myUid = FirebaseUtils.getCurrentUid();
            if (myUid != null) {
                Map<String, Object> log = new HashMap<>();
                log.put("partnerUid",  partnerUid);
                log.put("partnerName", partnerName);
                log.put("direction",   isCaller ? "outgoing" : "incoming");
                log.put("mediaType",   isVideo ? "video" : "audio");
                log.put("timestamp",   System.currentTimeMillis());
                log.put("duration",    dur);
                FirebaseUtils.getCallsRef(myUid).push().setValue(log);
            }
        }
        // Restore audio mode
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
            if (remoteCandidateListener != null && callRef != null)
                callRef.child("candidates").child(partnerUid)
                    .removeEventListener(remoteCandidateListener);
            if (statusListener != null && callRef != null)
                callRef.child("status").removeEventListener(statusListener);
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
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {}
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
