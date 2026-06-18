package com.callx.app.collab;

import com.callx.app.reels.R;

import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.*;
import java.util.HashMap;
import java.util.Map;

/**
 * LiveCollabActivity — Two creators stream together on one shared live.
 * Uses Firebase signaling (same pattern as WebRTC calls) to co-host.
 *
 * Flow:
 *   Host starts live → invites a guest → guest joins split-screen → both go live.
 *
 * Requires: Agora or WebRTC integration for actual media (same setup as CallActivity).
 */
public class LiveCollabActivity extends AppCompatActivity {

    public static final String EXTRA_LIVE_ID    = "liveId";
    public static final String EXTRA_IS_HOST    = "isHost";
    public static final String EXTRA_GUEST_UID  = "guestUid";
    public static final String EXTRA_GUEST_NAME = "guestName";

    private TextView tvStatus, tvHostName, tvGuestName, tvViewerCount;
    private View viewHostPreview, viewGuestPreview;
    private ImageButton btnMicToggle, btnCameraToggle, btnEndLive;
    private ProgressBar progressWaiting;
    private boolean isHost;
    private String liveId, guestUid, guestName;
    private DatabaseReference liveRef;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_live_collab);

        isHost    = getIntent().getBooleanExtra(EXTRA_IS_HOST, true);
        liveId    = getIntent().getStringExtra(EXTRA_LIVE_ID);
        guestUid  = getIntent().getStringExtra(EXTRA_GUEST_UID);
        guestName = getIntent().getStringExtra(EXTRA_GUEST_NAME);

        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u == null) { finish(); return; }

        bindViews();
        setupLive(u.getUid(), u.getDisplayName());
    }

    private void bindViews() {
        tvStatus        = findViewById(R.id.tv_live_status);
        tvHostName      = findViewById(R.id.tv_host_name);
        tvGuestName     = findViewById(R.id.tv_guest_name);
        tvViewerCount   = findViewById(R.id.tv_viewer_count);
        viewHostPreview = findViewById(R.id.view_host_preview);
        viewGuestPreview= findViewById(R.id.view_guest_preview);
        btnMicToggle    = findViewById(R.id.btn_mic_toggle);
        btnCameraToggle = findViewById(R.id.btn_camera_toggle);
        btnEndLive      = findViewById(R.id.btn_end_live);
        progressWaiting = findViewById(R.id.progress_waiting_guest);
    }

    private void setupLive(String myUid, String myName) {
        liveRef = FirebaseDatabase.getInstance().getReference("liveCollabs").child(liveId);

        if (isHost) {
            // Create live session
            Map<String, Object> session = new HashMap<>();
            session.put("hostUid",   myUid);
            session.put("hostName",  myName != null ? myName : "Host");
            session.put("guestUid",  guestUid != null ? guestUid : "");
            session.put("guestName", guestName != null ? guestName : "");
            session.put("status",    "waiting");
            session.put("viewerCount", 0);
            session.put("startedAt", System.currentTimeMillis());
            liveRef.updateChildren(session);

            tvStatus.setText("🔴 LIVE — Waiting for guest...");
            progressWaiting.setVisibility(View.VISIBLE);
            tvGuestName.setText(guestName != null ? guestName : "Guest");

            // Send collab live invite notification
            if (guestUid != null) {
                Map<String, Object> notif = new HashMap<>();
                notif.put("type",      "live_collab_invite");
                notif.put("senderUid", myUid);
                notif.put("senderName",myName != null ? myName : "Host");
                notif.put("liveId",    liveId);
                notif.put("timestamp", System.currentTimeMillis());
                notif.put("read",      false);
                FirebaseDatabase.getInstance().getReference("reel_notifications")
                    .child(guestUid).push().updateChildren(notif);
            }

            // Watch for guest join
            liveRef.child("guestJoined").addValueEventListener(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (Boolean.TRUE.equals(snap.getValue(Boolean.class))) {
                        progressWaiting.setVisibility(View.GONE);
                        tvStatus.setText("🔴 LIVE — Collab Active!");
                        liveRef.child("status").setValue("active");
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });

        } else {
            // Guest joins
            liveRef.child("guestJoined").setValue(true);
            tvStatus.setText("🔴 LIVE — You're co-hosting!");
        }

        // Viewer count listener
        liveRef.child("viewerCount").addValueEventListener(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                Long c = snap.getValue(Long.class);
                tvViewerCount.setText((c != null ? c : 0) + " viewers");
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        });

        btnMicToggle.setOnClickListener(v -> toggleMic());
        btnCameraToggle.setOnClickListener(v -> toggleCamera());
        btnEndLive.setOnClickListener(v -> endLive());
    }

    private boolean micOn = true, cameraOn = true;
    private void toggleMic()    { micOn    = !micOn;    /* toggle Agora/WebRTC mic */ }
    private void toggleCamera() { cameraOn = !cameraOn; /* toggle Agora/WebRTC cam */ }

    private void endLive() {
        liveRef.child("status").setValue("ended");
        liveRef.child("endedAt").setValue(System.currentTimeMillis());
        finish();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (isHost) liveRef.child("status").setValue("ended");
    }
}
