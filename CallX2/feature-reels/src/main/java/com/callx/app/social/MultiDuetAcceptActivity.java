package com.callx.app.social;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import com.bumptech.glide.Glide;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;

/**
 * MultiDuetAcceptActivity
 *
 * Opened when an invited participant taps a "Multi-Duet Invite" FCM notification.
 *
 * Extras (from notification deep-link):
 *   "session_id"  — multi_duet_sessions/{id}
 *   "from_uid"    — host UID
 *   "from_name"   — host display name
 *   "reel_id"     — original reel ID
 *
 * Flow:
 *   1. Load session from Firebase → show host info, participant count, reel thumbnail
 *   2. "Accept & Record" → mark participant status="accepted", launch DuetReelActivity
 *      with multi_duet_session_id + multi_duet_slot (= participant index in session)
 *   3. "Decline" → mark status="declined", finish()
 */
public class MultiDuetAcceptActivity extends AppCompatActivity {

    private String sessionId, fromUid, fromName, reelId;
    private String myUid;
    private int    mySlot = -1;

    private ImageView  ivHostAvatar, ivReelThumb;
    private TextView   tvHostName, tvParticipants, tvStatus;
    private Button     btnAccept, btnDecline;
    private ProgressBar progress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_multi_duet_accept);

        sessionId = getIntent().getStringExtra("session_id");
        fromUid   = getIntent().getStringExtra("from_uid");
        fromName  = getIntent().getStringExtra("from_name");
        reelId    = getIntent().getStringExtra("reel_id");
        myUid     = FirebaseAuth.getInstance().getUid();

        ivHostAvatar   = findViewById(R.id.iv_invite_host_avatar);
        ivReelThumb    = findViewById(R.id.iv_invite_reel_thumb);
        tvHostName     = findViewById(R.id.tv_invite_host_name);
        tvParticipants = findViewById(R.id.tv_invite_participants);
        tvStatus       = findViewById(R.id.tv_invite_status);
        progress       = findViewById(R.id.progress_invite);
        btnAccept      = findViewById(R.id.btn_invite_accept);
        btnDecline     = findViewById(R.id.btn_invite_decline);

        btnAccept.setOnClickListener(v -> acceptInvite());
        btnDecline.setOnClickListener(v -> declineInvite());

        if (sessionId == null || sessionId.isEmpty()) {
            tvStatus.setText("Invalid invite link.");
            btnAccept.setEnabled(false);
            return;
        }

        loadSession();
    }

    private void loadSession() {
        progress.setVisibility(View.VISIBLE);
        FirebaseUtils.db().getReference("multi_duet_sessions").child(sessionId)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    progress.setVisibility(View.GONE);
                    if (!snap.exists()) {
                        tvStatus.setText("Session no longer available.");
                        btnAccept.setEnabled(false);
                        return;
                    }

                    String hostUid    = snap.child("hostUid").getValue(String.class);
                    String videoUrl   = snap.child("videoUrl").getValue(String.class);
                    String sessionStatus = snap.child("status").getValue(String.class);

                    // Count participants + find my slot
                    DataSnapshot pSnap = snap.child("participants");
                    int participantCount = (int) pSnap.getChildrenCount();
                    int slotIndex = 0;
                    for (DataSnapshot ds : pSnap.getChildren()) {
                        if (ds.getKey() != null && ds.getKey().equals(myUid)) {
                            mySlot = slotIndex;
                        }
                        slotIndex++;
                    }

                    if ("recording".equals(sessionStatus) || "completed".equals(sessionStatus)) {
                        tvStatus.setText("This session is already " + sessionStatus + ".");
                        btnAccept.setEnabled(false);
                    } else {
                        tvStatus.setText("You've been invited to record your part!");
                        btnAccept.setEnabled(true);
                    }

                    tvHostName.setText(fromName != null ? fromName + " invited you" : "You've been invited");
                    tvParticipants.setText(participantCount + " participants in this session");

                    // Load host avatar
                    if (fromUid != null) {
                        FirebaseUtils.db().getReference("users").child(fromUid)
                            .addListenerForSingleValueEvent(new ValueEventListener() {
                                @Override public void onDataChange(@NonNull DataSnapshot u) {
                                    String photo = u.child("photoUrl").getValue(String.class);
                                    if (photo != null && !photo.isEmpty()) {
                                        Glide.with(MultiDuetAcceptActivity.this)
                                            .load(photo).circleCrop().into(ivHostAvatar);
                                    }
                                }
                                @Override public void onCancelled(@NonNull DatabaseError e) {}
                            });
                    }

                    // Load reel thumbnail
                    if (reelId != null) {
                        FirebaseUtils.getReelsRef().child(reelId)
                            .addListenerForSingleValueEvent(new ValueEventListener() {
                                @Override public void onDataChange(@NonNull DataSnapshot r) {
                                    String thumb = r.child("thumbUrl").getValue(String.class);
                                    if (thumb != null && !thumb.isEmpty()) {
                                        Glide.with(MultiDuetAcceptActivity.this)
                                            .load(thumb).centerCrop().into(ivReelThumb);
                                    }

                                    // Store videoUrl for launch
                                    String vidUrl = r.child("videoUrl").getValue(String.class);
                                    if (vidUrl != null) {
                                        btnAccept.setTag(vidUrl);
                                    }
                                }
                                @Override public void onCancelled(@NonNull DatabaseError e) {}
                            });
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    progress.setVisibility(View.GONE);
                    tvStatus.setText("Failed to load invite.");
                    btnAccept.setEnabled(false);
                }
            });
    }

    private void acceptInvite() {
        if (myUid == null || sessionId == null) return;

        // Mark accepted in Firebase
        FirebaseUtils.db().getReference("multi_duet_sessions")
            .child(sessionId).child("participants").child(myUid)
            .child("status").setValue("accepted");

        // Read session to get videoUrl + total for launch
        FirebaseUtils.db().getReference("multi_duet_sessions").child(sessionId)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    String videoUrl   = snap.child("videoUrl").getValue(String.class);
                    String hostName   = "";
                    String hostUid    = snap.child("hostUid").getValue(String.class);
                    int    total      = (int) snap.child("participants").getChildrenCount();

                    // Determine my slot index
                    int slot = 1; // default to 1 (first non-host)
                    int idx  = 0;
                    for (DataSnapshot ds : snap.child("participants").getChildren()) {
                        if (ds.getKey() != null && ds.getKey().equals(myUid)) {
                            slot = idx;
                            break;
                        }
                        idx++;
                    }

                    Intent i = new Intent(MultiDuetAcceptActivity.this, DuetReelActivity.class);
                    i.putExtra(DuetReelActivity.EXTRA_REEL_ID,    reelId != null ? reelId : "");
                    i.putExtra(DuetReelActivity.EXTRA_VIDEO_URL,  videoUrl != null ? videoUrl : "");
                    i.putExtra(DuetReelActivity.EXTRA_OWNER_UID,  hostUid != null ? hostUid : "");
                    i.putExtra(DuetReelActivity.EXTRA_OWNER_NAME, fromName != null ? fromName : "");
                    i.putExtra("multi_duet_session_id", sessionId);
                    i.putExtra("multi_duet_slot",       slot);
                    i.putExtra("multi_duet_total",      total);
                    startActivity(i);
                    finish();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    Toast.makeText(MultiDuetAcceptActivity.this,
                            "Failed to load session", Toast.LENGTH_SHORT).show();
                }
            });
    }

    private void declineInvite() {
        if (myUid == null || sessionId == null) { finish(); return; }
        FirebaseUtils.db().getReference("multi_duet_sessions")
            .child(sessionId).child("participants").child(myUid)
            .child("status").setValue("declined");
        Toast.makeText(this, "Invite declined", Toast.LENGTH_SHORT).show();
        finish();
    }
}
