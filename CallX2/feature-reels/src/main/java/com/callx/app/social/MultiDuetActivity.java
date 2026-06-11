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
   *  Creator (Host):
   *   1. Opens MultiDuetActivity from DuetReelActivity (new button "Multi-Duet")
   *   2. Invites up to 3 followers → creates multi_duet_session/{sessionId}
   *   3. Each invite shows as a slot: "Waiting for @user..."
   *   4. Once all accept (or host starts early), each records their own duet
   *   5. DuetVideoCompositor (4-way grid mode) composites all 4 clips
   *
   * Session Firebase structure:
   *   multi_duet_sessions/{sessionId} = {
   *     hostUid, hostName,
   *     originalReelId, videoUrl,
   *     participants: {
   *       uid1: { name, status: "invited"|"accepted"|"recorded", videoPath }
   *       uid2: { ... }
   *       uid3: { ... }
   *     },
   *     maxSlots: 4,
   *     status: "waiting"|"recording"|"compositing"|"done",
   *     createdAt
   *   }
   *
   * Note: Actual real-time co-recording (WebRTC) is a future enhancement.
   * Current version does async recording — each participant records separately,
   * then compositor stitches them together in a 2×2 grid.
   */
  public class MultiDuetActivity extends AppCompatActivity {

      public static final String EXTRA_ORIGINAL_REEL_ID = "multi_duet_reel_id";
      public static final String EXTRA_VIDEO_URL        = "multi_duet_video_url";
      public static final String EXTRA_OWNER_NAME       = "multi_duet_owner_name";
      public static final String EXTRA_OWNER_UID        = "multi_duet_owner_uid";
      public static final int    MAX_SLOTS              = 4;

      private RecyclerView rvSlots;
      private Button       btnAddParticipant, btnStartSession;
      private TextView     tvTitle, tvSessionCode;
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

          btnBack            = findViewById(R.id.btn_multi_duet_back);
          rvSlots            = findViewById(R.id.rv_multi_duet_slots);
          btnAddParticipant  = findViewById(R.id.btn_add_participant);
          btnStartSession    = findViewById(R.id.btn_start_multi_duet);
          tvTitle            = findViewById(R.id.tv_multi_duet_title);
          tvSessionCode      = findViewById(R.id.tv_session_code);
          progress           = findViewById(R.id.progress_multi_duet);

          tvTitle.setText("Multi-Person Duet");
          btnBack.setOnClickListener(v -> finish());
          btnAddParticipant.setOnClickListener(v -> inviteParticipant());
          btnStartSession.setOnClickListener(v -> startSession());

          // Add host as first participant
          loadMyProfile(() -> {
              Participant host = new Participant(myUid, "You (Host)", "accepted");
              participants.add(host);
              setupAdapter();
              createSession();
          });
      }

      private void loadMyProfile(Runnable onDone) {
          if (myUid == null) { onDone.run(); return; }
          FirebaseUtils.db().getReference("users").child(myUid)
              .addListenerForSingleValueEvent(new ValueEventListener() {
                  @Override public void onDataChange(@NonNull DataSnapshot ds) {
                      String name  = ds.child("displayName").getValue(String.class);
                      String photo = ds.child("photoUrl").getValue(String.class);
                      if (!participants.isEmpty()) {
                          participants.get(0).name  = name  != null ? "You (" + name + ")" : "You (Host)";
                          participants.get(0).photo = photo != null ? photo : "";
                      }
                      onDone.run();
                  }
                  @Override public void onCancelled(@NonNull DatabaseError e) { onDone.run(); }
              });
      }

      private void createSession() {
          if (myUid == null || originalReelId == null) return;
          String key = FirebaseUtils.db().getReference("multi_duet_sessions").push().getKey();
          if (key == null) return;
          sessionId = key;
          tvSessionCode.setText("Session: " + sessionId.substring(0, 8).toUpperCase());

          Map<String, Object> session = new HashMap<>();
          session.put("hostUid",        myUid);
          session.put("originalReelId", originalReelId);
          session.put("videoUrl",       videoUrl != null ? videoUrl : "");
          session.put("maxSlots",       MAX_SLOTS);
          session.put("status",         "waiting");
          session.put("createdAt",      com.google.firebase.database.ServerValue.TIMESTAMP);

          FirebaseUtils.db().getReference("multi_duet_sessions").child(sessionId).setValue(session);
      }

      private void setupAdapter() {
          adapter = new ParticipantAdapter(participants, this::removeParticipant);
          rvSlots.setLayoutManager(new GridLayoutManager(this, 2));
          rvSlots.setAdapter(adapter);
          updateStartButton();
      }

      private void inviteParticipant() {
          if (participants.size() >= MAX_SLOTS) {
              Toast.makeText(this, "Max " + MAX_SLOTS + " participants", Toast.LENGTH_SHORT).show();
              return;
          }
          // Open follower picker (DuetInviteActivity variant for multi-duet)
          Intent i = new Intent(this, DuetInviteActivity.class);
          i.putExtra(DuetInviteActivity.EXTRA_REEL_ID,   originalReelId);
          i.putExtra(DuetInviteActivity.EXTRA_VIDEO_URL,  videoUrl);
          i.putExtra(DuetInviteActivity.EXTRA_OWNER_NAME, ownerName);
          startActivity(i);
      }

      private void removeParticipant(int pos) {
          if (pos == 0) return; // can't remove host
          Participant p = participants.get(pos);
          participants.remove(pos);
          adapter.notifyItemRemoved(pos);
          // Remove from Firebase session
          if (sessionId != null) {
              FirebaseUtils.db().getReference("multi_duet_sessions")
                  .child(sessionId).child("participants").child(p.uid).removeValue();
          }
          updateStartButton();
      }

      private void startSession() {
          if (participants.size() < 2) {
              Toast.makeText(this, "Add at least one more participant", Toast.LENGTH_SHORT).show();
              return;
          }
          if (sessionId == null) return;
          FirebaseUtils.db().getReference("multi_duet_sessions")
              .child(sessionId).child("status").setValue("recording");

          // Launch DuetReelActivity for the host (first slot)
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

      private void updateStartButton() {
          int count = participants.size();
          btnStartSession.setText("Start Multi-Duet (" + count + "/" + MAX_SLOTS + ")");
          btnStartSession.setEnabled(count >= 1);
          btnAddParticipant.setEnabled(count < MAX_SLOTS);
      }

      // ── Data model ────────────────────────────────────────────────────────────
      static class Participant {
          String uid, name, photo, status;
          Participant(String uid, String name, String status) {
              this.uid = uid; this.name = name; this.status = status; this.photo = "";
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
              h.tvStatus.setText(p.status);
              if (p.photo != null && !p.photo.isEmpty())
                  Glide.with(h.ivAvatar).load(p.photo).circleCrop().into(h.ivAvatar);
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
  