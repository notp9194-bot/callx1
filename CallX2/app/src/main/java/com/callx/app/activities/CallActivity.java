package com.callx.app.activities;
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
public class CallActivity extends AppCompatActivity {
    // UI
    private ActivityCallBinding binding;
    // Call params
    private String partnerUid, partnerName, partnerPhoto, callId;
    private boolean isCaller, isVideo, micOn = true, camOn = true;
    private long startedAt = 0;
    private boolean callConnected = false;
    private boolean finishing = false;
    // Timer
    private final Handler tick = new Handler(Looper.getMainLooper());
    private Runnable ticker;
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
    // Firebase signaling
    private DatabaseReference callRef;
    private ValueEventListener statusListener;
    private ChildEventListener remoteCandidateListener;
    private boolean remoteDescSet = false;
    private final List<IceCandidate> pendingCandidates = new ArrayList<>();
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityCallBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        partnerUid   = getIntent().getStringExtra("partnerUid");
        partnerName  = getIntent().getStringExtra("partnerName");
        partnerPhoto = getIntent().getStringExtra("partnerPhoto");
        isCaller     = getIntent().getBooleanExtra("isCaller", false);
        isVideo      = getIntent().getBooleanExtra("video", false);
        callId       = getIntent().getStringExtra("callId");
        if (partnerUid == null) { finish(); return; }
        binding.tvCallerName.setText(partnerName != null ? partnerName : "Unknown");
        binding.tvCallStatus.setText(isCaller
            ? (isVideo ? "Video calling..." : "Calling...") : "Connecting...");
        if (!isVideo) {
            binding.localVideo.setVisibility(View.GONE);
            binding.remoteVideo.setVisibility(View.GONE);
            binding.btnToggleCamera.setVisibility(View.GONE);
        }
        if (partnerPhoto != null && !partnerPhoto.isEmpty()) {
            Glide.with(this).load(partnerPhoto).circleCrop().into(binding.ivCallAvatar);
        }
        binding.btnEndCall.setOnClickListener(v -> endCall());
        binding.btnToggleMic.setOnClickListener(v -> toggleMic());
        binding.btnToggleCamera.setOnClickListener(v -> toggleCamera());
        checkPermsAndInit();
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
        else      initWebRTC();
    }
    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms,
                                           @NonNull int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == 401) {
            boolean ok = true;
            for (int r : results) if (r != PackageManager.PERMISSION_GRANTED) { ok = false; break; }
            if (ok) initWebRTC();
            else { Toast.makeText(this, "Mic/Camera permission required", Toast.LENGTH_LONG).show(); finish(); }
        }
    }
    // ── WebRTC init ────────────────────────────────────────────────────────
    private void initWebRTC() {
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
        // ICE servers (Google public STUN)
        List<PeerConnection.IceServer> ice = new ArrayList<>();
        ice.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());
        ice.add(PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer());
        PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(ice);
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        peerConnection = factory.createPeerConnection(rtcConfig, new PeerConnection.Observer() {
            @Override public void onIceCandidate(IceCandidate c) { sendCandidate(c); }
            @Override public void onIceCandidatesRemoved(IceCandidate[] c) {}
            @Override public void onSignalingChange(PeerConnection.SignalingState s) {}
            @Override public void onIceConnectionChange(PeerConnection.IceConnectionState s) {
                runOnUiThread(() -> {
                    if (s == PeerConnection.IceConnectionState.CONNECTED ||
                        s == PeerConnection.IceConnectionState.COMPLETED) {
                        onCallConnected();
                    } else if ((s == PeerConnection.IceConnectionState.DISCONNECTED ||
                                s == PeerConnection.IceConnectionState.FAILED) &&
                                callConnected) {
                        endCall();
                    }
                });
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
        audioSource = factory.createAudioSource(new MediaConstraints());
        localAudioTrack = factory.createAudioTrack("audio0", audioSource);
        localAudioTrack.setEnabled(true);
        // Video
        if (isVideo) {
            videoSource   = factory.createVideoSource(false);
            surfaceHelper = SurfaceTextureHelper.create("CaptureThread",
                                eglBase.getEglBaseContext());
            videoCapturer = createFrontCapturer();
            if (videoCapturer != null) {
                videoCapturer.initialize(surfaceHelper, this, videoSource.getCapturerObserver());
                videoCapturer.startCapture(640, 480, 30);
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
            // Callee: signal accepted, then watch for offer
            callRef.child("status").setValue("accepted");
            watchForOffer();
        }
        watchCallStatus();
        watchRemoteCandidates();
    }
    private CameraVideoCapturer createFrontCapturer() {
        Camera2Enumerator e = new Camera2Enumerator(this);
        for (String d : e.getDeviceNames()) if (e.isFrontFacing(d)) return e.createCapturer(d, null);
        for (String d : e.getDeviceNames()) return e.createCapturer(d, null);
        return null;
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
                if (!s.exists() || peerConnection == null || peerConnection.getRemoteDescription() != null) return;
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
                if (!s.exists() || peerConnection == null || peerConnection.getRemoteDescription() != null) return;
                String type = s.child("type").getValue(String.class);
                String sdp  = s.child("sdp").getValue(String.class);
                if (type == null || sdp == null) return;
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
        m.put("candidate", c.sdp); m.put("sdpMid", c.sdpMid); m.put("sdpMLineIndex", c.sdpMLineIndex);
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
        startedAt = System.currentTimeMillis();
        binding.tvCallStatus.setText("Connected \u2022 0:00");
        if (isVideo) binding.ivCallAvatar.setVisibility(View.GONE);
        Intent fg = new Intent(this, CallForegroundService.class);
        fg.putExtra("name",   partnerName != null ? partnerName : "");
        fg.putExtra("callId", callId != null ? callId : "");
        fg.putExtra("isVideo", isVideo);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
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
        releaseWebRTC();
        finish();
    }
    private void releaseWebRTC() {
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
            if (isVideo) { binding.localVideo.release(); binding.remoteVideo.release(); }
            if (eglBase         != null) eglBase.release();
            if (factory         != null) factory.dispose();
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
        super.onDestroy();
    }
}
