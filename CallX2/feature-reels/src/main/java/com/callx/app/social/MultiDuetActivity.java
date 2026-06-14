package com.callx.app.social;

import android.content.Intent;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.*;

/**
 * MultiDuetActivity — 3-4 person duet session.
 *
 * Flow:
 *  1. Host opens this from DuetReelActivity
 *  2. Taps "+ Add Participant" → opens DuetInviteActivity in MULTI_DUET_MODE
 *  3. DuetInviteActivity lets host search ANY user by name/username
 *  4. On user pick → returned via onActivityResult → added to slots list
 *  5. Host can add up to MAX_SLOTS-1 others (total 4 incl. host)
 *  6. "Start" → writes session to Firebase, launches DuetReelActivity for host
 *
 * Firebase structure:
 *   multi_duet_sessions/{sessionId} = {
 *     hostUid, originalReelId, videoUrl, maxSlots, status, createdAt,
 *     participants: {
 *       uid1: { name, photo, status:"accepted" },
 *       uid2: { name, photo, status:"invited" }, ...
 *     }
 *   }
 */
public class MultiDuetActivity extends AppCompatActivity {

    public static final String EXTRA_ORIGINAL_REEL_ID = "multi_duet_reel_id";
    public static final String EXTRA_VIDEO_URL        = "multi_duet_video_url";
    public static final String EXTRA_OWNER_NAME       = "multi_duet_owner_name";
    public static final String EXTRA_OWNER_UID        = "multi_duet_owner_uid";
    public static final int    MAX_SLOTS              = 4;
    private static final int   RC_PICK_USER           = 1001;

    private RecyclerView rvSlots;
    private Button       btnAddParticipant, btnStartSession;
    private TextView     tvTitle, tvSessionCode, tvParticipantCount;
    private ProgressBar  progress;
    private ImageButton  btnBack;

    private String myUid, sessionId;
    private String originalReelId, videoUrl, ownerName, ownerUid;
    private final List<Participant> participants = new ArrayList<>();
    private ParticipantAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_multi_duet);

        originalReelId = getIntent().getStringExtra(EXTRA_ORIGINAL_REEL_ID);
        videoUrl       = getIntent().getStringExtra(EXTRA_VIDEO_URL);
        ownerName      = getIntent().getStringExtra(EXTRA_OWNER_NAME);
        ownerUid       = getIntent().getStringExtra(EXTRA_OWNER_UID);
        myUid          = FirebaseAuth.getInstance().getUid();

        btnBack              = findViewById(R.id.btn_multi_duet_back);
        rvSlots              = findViewById(R.id.rv_multi_duet_slots);
        btnAddParticipant    = findViewById(R.id.btn_add_participant);
        btnStartSession      = findViewById(R.id.btn_start_multi_duet);
        tvTitle              = findViewById(R.id.tv_multi_duet_title);
        tvSessionCode        = findViewById(R.id.tv_session_code);
        tvParticipantCount   = findViewById(R.id.tv_participant_count);
        progress             = findViewById(R.id.progress_multi_duet);

        tvTitle.setText("Multi-Person Duet");
        btnBack.setOnClickListener(v -> finish());
        btnAddParticipant.setOnClickListener(v -> openUserPicker());
        btnStartSession.setOnClickListener(v -> startSession());

        // Add host placeholder first, then load real name/photo
        Participant host = new Participant(myUid != null ? myUid : "", "You (Host)", "", "accepted");
        participants.add(host);
        setupAdapter();
        createSession();

        // Load real profile in background — updates slot 0
        loadMyProfile();
    }

    // ── Activity Result ───────────────────────────────────────────────────────

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == RC_PICK_USER && resultCode == RESULT_OK && data != null) {
            String uid      = data.getStringExtra(DuetInviteActivity.RESULT_USER_UID);
            String name     = data.getStringExtra(DuetInviteActivity.RESULT_USER_NAME);
            String photo    = data.getStringExtra(DuetInviteActivity.RESULT_USER_PHOTO);
            String username = data.getStringExtra(DuetInviteActivity.RESULT_USER_USERNAME);

            if (uid == null || isAlreadyAdded(uid)) {
                Toast.makeText(this, "User already in session", Toast.LENGTH_SHORT).show();
                return;
            }

            String displayName = (name != null ? name : "")
                + (username != null && !username.isEmpty() ? " (@" + username + ")" : "");

            Participant p = new Participant(uid, displayName,
                photo != null ? photo : "", "invited");
            participants.add(p);
            adapter.notifyItemInserted(participants.size() - 1);
            updateUI();

            // Write to Firebase session
            if (sessionId != null) {
                Map<String, Object> pData = new HashMap<>();
                pData.put("name",   name != null ? name : "");
                pData.put("photo",  photo != null ? photo : "");
                pData.put("status", "invited");
                FirebaseUtils.db().getReference("multi_duet_sessions")
                    .child(sessionId).child("participants").child(uid).setValue(pData);

                // Send invite notification to target user
                sendSessionInviteToUser(uid, name);
            }
        }
    }

    // ── Firebase helpers ──────────────────────────────────────────────────────

    private void loadMyProfile() {
        if (myUid == null) return;
        FirebaseUtils.db().getReference("users").child(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot ds) {
                    String name  = ds.child("displayName").getValue(String.class);
                    String photo = ds.child("photoUrl").getValue(String.class);
                    if (!participants.isEmpty()) {
                        participants.get(0).name  = name != null ? "You (" + name + ")" : "You (Host)";
                        participants.get(0).photo = photo != null ? photo : "";
                        if (adapter != null) adapter.notifyItemChanged(0);
                        // Update host data in Firebase session
                        if (sessionId != null) {
                            Map<String, Object> hostData = new HashMap<>();
                            hostData.put("name",   participants.get(0).name);
                            hostData.put("photo",  participants.get(0).photo);
                            hostData.put("status", "accepted");
                            FirebaseUtils.db().getReference("multi_duet_sessions")
                                .child(sessionId).child("participants").child(myUid)
                                .setValue(hostData);
                        }
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void createSession() {
        if (myUid == null || originalReelId == null) return;
        String key = FirebaseUtils.db().getReference("multi_duet_sessions").push().getKey();
        if (key == null) return;
        sessionId = key;
        tvSessionCode.setText("Session: " + sessionId.substring(0, 8).toUpperCase());

        DatabaseReference sessionRef2 = FirebaseUtils.db()
            .getReference("multi_duet_sessions").child(sessionId);

        Map<String, Object> session = new HashMap<>();
        session.put("hostUid",        myUid);
        session.put("originalReelId", originalReelId);
        session.put("videoUrl",       videoUrl != null ? videoUrl : "");
        session.put("maxSlots",       MAX_SLOTS);
        session.put("status",         "waiting");
        session.put("createdAt",      com.google.firebase.database.ServerValue.TIMESTAMP);
        sessionRef2.setValue(session);

        // Write host participant separately (avoid slash-in-key crash)
        String hostName  = participants.isEmpty() ? "Host" : participants.get(0).name;
        String hostPhoto = participants.isEmpty() ? ""     : participants.get(0).photo;
        Map<String, Object> hostData = new HashMap<>();
        hostData.put("name",   hostName);
        hostData.put("photo",  hostPhoto);
        hostData.put("status", "accepted");
        sessionRef2.child("participants").child(myUid).setValue(hostData);

        listenToSession();
    }

    private void sendSessionInviteToUser(String targetUid, String targetName) {
        if (sessionId == null || myUid == null) return;
        FirebaseUtils.db().getReference("users").child(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot ds) {
                    String fromName  = ds.child("displayName").getValue(String.class);
                    String fromPhoto = ds.child("photoUrl").getValue(String.class);
                    if (fromName == null) fromName = "Someone";

                    Map<String, Object> invite = new HashMap<>();
                    invite.put("fromUid",    myUid);
                    invite.put("fromName",   fromName);
                    invite.put("fromPhoto",  fromPhoto != null ? fromPhoto : "");
                    invite.put("reelId",     originalReelId);
                    invite.put("videoUrl",   videoUrl != null ? videoUrl : "");
                    invite.put("sessionId",  sessionId);
                    invite.put("type",       "multi_duet");
                    invite.put("sentAt",     com.google.firebase.database.ServerValue.TIMESTAMP);
                    invite.put("status",     "pending");

                    String inviteKey = FirebaseUtils.db()
                        .getReference("duet_invites").child(targetUid).push().getKey();
                    if (inviteKey == null) return;
                    FirebaseUtils.db().getReference("duet_invites")
                        .child(targetUid).child(inviteKey).setValue(invite);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private void setupAdapter() {
        adapter = new ParticipantAdapter(participants, this::removeParticipant);
        rvSlots.setLayoutManager(new GridLayoutManager(this, 2));
        rvSlots.setAdapter(adapter);
        updateUI();
    }

    private void openUserPicker() {
        if (participants.size() >= MAX_SLOTS) {
            Toast.makeText(this, "Max " + MAX_SLOTS + " participants reached", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(this, DuetInviteActivity.class);
        i.putExtra(DuetInviteActivity.EXTRA_REEL_ID,         originalReelId);
        i.putExtra(DuetInviteActivity.EXTRA_VIDEO_URL,        videoUrl);
        i.putExtra(DuetInviteActivity.EXTRA_OWNER_NAME,       ownerName);
        i.putExtra(DuetInviteActivity.EXTRA_OWNER_UID,        ownerUid);
        i.putExtra(DuetInviteActivity.EXTRA_MULTI_DUET_MODE,  true);
        startActivityForResult(i, RC_PICK_USER);
    }

    private void removeParticipant(int pos) {
        if (pos == 0) return; // can't remove host
        Participant p = participants.get(pos);
        participants.remove(pos);
        adapter.notifyItemRemoved(pos);
        if (sessionId != null) {
            FirebaseUtils.db().getReference("multi_duet_sessions")
                .child(sessionId).child("participants").child(p.uid).removeValue();
        }
        updateUI();
    }

    // ── Session listener ──────────────────────────────────────────────────────

    private ValueEventListener sessionListener;
    private DatabaseReference  sessionRef;

    private void listenToSession() {
        if (sessionId == null) return;
        sessionRef = FirebaseUtils.db()
            .getReference("multi_duet_sessions").child(sessionId);
        sessionListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (isFinishing() || isDestroyed()) return;
                int recorded = 0;
                int total    = participants.size();
                DataSnapshot pSnap = snap.child("participants");
                for (DataSnapshot ds : pSnap.getChildren()) {
                    String status = ds.child("status").getValue(String.class);
                    if ("recorded".equals(status)) recorded++;
                    // Update UI slot status
                    String uid = ds.getKey();
                    for (Participant p : participants) {
                        if (p.uid != null && p.uid.equals(uid)) {
                            String s = ds.child("status").getValue(String.class);
                            if (s != null) p.status = s;
                        }
                    }
                }
                adapter.notifyDataSetChanged();
                int finalRecorded = recorded;
                if (tvSessionCode != null) {
                    tvSessionCode.setText("Session: " + sessionId.substring(0, 8).toUpperCase()
                        + " — " + finalRecorded + "/" + total + " recorded");
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        sessionRef.addValueEventListener(sessionListener);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (sessionRef != null && sessionListener != null) {
            sessionRef.removeEventListener(sessionListener);
        }
    }

    private void startSession() {
        if (participants.size() < 2) {
            Toast.makeText(this, "Add at least one more participant", Toast.LENGTH_SHORT).show();
            return;
        }
        if (sessionId == null) return;
        FirebaseUtils.db().getReference("multi_duet_sessions")
            .child(sessionId).child("status").setValue("recording");

        Intent i = new Intent(this, DuetReelActivity.class);
        i.putExtra(DuetReelActivity.EXTRA_REEL_ID,    originalReelId);
        i.putExtra(DuetReelActivity.EXTRA_VIDEO_URL,  videoUrl);
        i.putExtra(DuetReelActivity.EXTRA_OWNER_NAME, ownerName);
        i.putExtra(DuetReelActivity.EXTRA_OWNER_UID,  ownerUid);
        i.putExtra("multi_duet_session_id", sessionId);
        i.putExtra("multi_duet_slot",       0);
        i.putExtra("multi_duet_total",      participants.size());
        startActivity(i);
    }

    private void updateUI() {
        int count = participants.size();
        int slots = MAX_SLOTS - count;
        btnStartSession.setText("Start Multi-Duet (" + count + "/" + MAX_SLOTS + ")");
        btnStartSession.setEnabled(count >= 1);
        btnAddParticipant.setEnabled(count < MAX_SLOTS);
        btnAddParticipant.setText(count < MAX_SLOTS
            ? "+ Add Participant (" + slots + " slot" + (slots == 1 ? "" : "s") + " left)"
            : "Max participants reached");

        if (tvParticipantCount != null) {
            tvParticipantCount.setText(count + "/" + MAX_SLOTS + " participants");
        }
    }

    private boolean isAlreadyAdded(String uid) {
        for (Participant p : participants) {
            if (uid.equals(p.uid)) return true;
        }
        return false;
    }

    // ── Data model ────────────────────────────────────────────────────────────
    static class Participant {
        String uid, name, photo, status;
        Participant(String uid, String name, String photo, String status) {
            this.uid = uid; this.name = name; this.photo = photo; this.status = status;
        }
    }

    // ── Adapter ───────────────────────────────────────────────────────────────
    static class ParticipantAdapter extends RecyclerView.Adapter<ParticipantAdapter.VH> {
        interface OnRemove { void onRemove(int pos); }
        private final List<Participant> items;
        private final OnRemove onRemove;
        ParticipantAdapter(List<Participant> items, OnRemove r) {
            this.items = items; this.onRemove = r;
        }
        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup p, int t) {
            View v = LayoutInflater.from(p.getContext())
                .inflate(R.layout.item_multi_duet_slot, p, false);
            return new VH(v);
        }
        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            Participant p = items.get(pos);
            h.tvName.setText(p.name);
            h.tvStatus.setText(pos == 0 ? "Host ✓" : p.status);
            h.tvStatus.setTextColor(pos == 0 ? 0xFF4CAF50 :
                "accepted".equals(p.status) ? 0xFF4CAF50 : 0xFFFFD700);
            if (p.photo != null && !p.photo.isEmpty()) {
                Glide.with(h.ivAvatar).load(p.photo).circleCrop().into(h.ivAvatar);
            } else {
                h.ivAvatar.setImageResource(android.R.drawable.ic_menu_myplaces);
            }
            h.btnRemove.setVisibility(pos == 0 ? View.GONE : View.VISIBLE);
            h.btnRemove.setOnClickListener(v -> onRemove.onRemove(h.getAdapterPosition()));
        }
        @Override public int getItemCount() { return items.size(); }
        static class VH extends RecyclerView.ViewHolder {
            ImageView ivAvatar; TextView tvName, tvStatus; ImageButton btnRemove;
            VH(View v) {
                super(v);
                ivAvatar  = v.findViewById(R.id.iv_slot_avatar);
                tvName    = v.findViewById(R.id.tv_slot_name);
                tvStatus  = v.findViewById(R.id.tv_slot_status);
                btnRemove = v.findViewById(R.id.btn_slot_remove);
            }
        }
    }
}
