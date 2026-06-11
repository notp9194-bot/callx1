package com.callx.app.social;

  import android.content.Intent;
  import android.os.Bundle;
  import android.view.*;
  import android.widget.*;
  import androidx.annotation.NonNull;
  import androidx.appcompat.app.AppCompatActivity;
  import androidx.recyclerview.widget.LinearLayoutManager;
  import androidx.recyclerview.widget.RecyclerView;
  import com.bumptech.glide.Glide;
  import com.callx.app.models.ReelModel;
  import com.callx.app.reels.R;
  import com.callx.app.utils.FirebaseUtils;
  import com.google.firebase.auth.FirebaseAuth;
  import com.google.firebase.database.*;
  import java.util.*;

  /**
   * DuetApprovalQueueActivity — Creator reviews pending duets before they go live.
   *
   * Firebase structure:
   *   duet_pending/{ownerUid}/{duetReelId} = {
   *     duetReelId, duetorUid, duetorName, duetorPhoto,
   *     videoUrl, thumbUrl, originalReelId, submittedAt
   *   }
   *
   * Actions:
   *   APPROVE → moves record to reels/{duetReelId} (makes it public)
   *             increments duetCount on original reel
   *   REJECT  → deletes duet_pending/{ownerUid}/{duetReelId}
   *             deletes the draft reel storage entry
   *             notifies duetor via duet_notif
   *
   * Enabled when ReelRemixSettingsActivity sets:
   *   reels/{reelId}/duetApprovalRequired = true
   */
  public class DuetApprovalQueueActivity extends AppCompatActivity {

      public static final String EXTRA_REEL_ID    = "approval_reel_id";
      public static final String EXTRA_REEL_TITLE = "approval_reel_title";

      private RecyclerView rvQueue;
      private ProgressBar  progress;
      private View         layoutEmpty;
      private TextView     tvTitle, tvCount;
      private ImageButton  btnBack;

      private String myUid, reelId;
      private final List<PendingDuet> pending = new ArrayList<>();
      private ApprovalAdapter adapter;

      @Override
      protected void onCreate(Bundle savedInstanceState) {
          super.onCreate(savedInstanceState);
          setContentView(R.layout.activity_duet_approval_queue);

          myUid  = FirebaseAuth.getInstance().getUid();
          reelId = getIntent().getStringExtra(EXTRA_REEL_ID);
          String reelTitle = getIntent().getStringExtra(EXTRA_REEL_TITLE);

          btnBack     = findViewById(R.id.btn_approval_back);
          tvTitle     = findViewById(R.id.tv_approval_title);
          tvCount     = findViewById(R.id.tv_approval_count);
          rvQueue     = findViewById(R.id.rv_approval_queue);
          progress    = findViewById(R.id.progress_approval);
          layoutEmpty = findViewById(R.id.layout_approval_empty);

          tvTitle.setText("Duet Requests");
          btnBack.setOnClickListener(v -> finish());

          adapter = new ApprovalAdapter(pending, this::approve, this::reject);
          rvQueue.setLayoutManager(new LinearLayoutManager(this));
          rvQueue.setAdapter(adapter);

          loadQueue();
      }

      private void loadQueue() {
          if (myUid == null) return;
          progress.setVisibility(View.VISIBLE);

          DatabaseReference ref = FirebaseUtils.db().getReference("duet_pending").child(myUid);
          if (reelId != null && !reelId.isEmpty()) {
              // Filter by specific reel
              ref.orderByChild("originalReelId").equalTo(reelId)
                 .addListenerForSingleValueEvent(new ValueEventListener() {
                     @Override public void onDataChange(@NonNull DataSnapshot snap) {
                         processSnapshot(snap);
                     }
                     @Override public void onCancelled(@NonNull DatabaseError e) {
                         progress.setVisibility(View.GONE);
                     }
                 });
          } else {
              // All pending duets for this user
              ref.orderByChild("submittedAt").addListenerForSingleValueEvent(new ValueEventListener() {
                  @Override public void onDataChange(@NonNull DataSnapshot snap) {
                      processSnapshot(snap);
                  }
                  @Override public void onCancelled(@NonNull DatabaseError e) {
                      progress.setVisibility(View.GONE);
                  }
              });
          }
      }

      private void processSnapshot(DataSnapshot snap) {
          pending.clear();
          for (DataSnapshot ds : snap.getChildren()) {
              PendingDuet d = new PendingDuet();
              d.duetReelId     = ds.child("duetReelId").getValue(String.class);
              d.duetorUid      = ds.child("duetorUid").getValue(String.class);
              d.duetorName     = ds.child("duetorName").getValue(String.class);
              d.duetorPhoto    = ds.child("duetorPhoto").getValue(String.class);
              d.videoUrl       = ds.child("videoUrl").getValue(String.class);
              d.thumbUrl       = ds.child("thumbUrl").getValue(String.class);
              d.originalReelId = ds.child("originalReelId").getValue(String.class);
              d.layoutMode     = ds.child("layoutMode").getValue(Integer.class);
              if (d.duetorName != null) pending.add(d);
          }
          progress.setVisibility(View.GONE);
          tvCount.setText(pending.size() + " pending");
          adapter.notifyDataSetChanged();
          showEmpty(pending.isEmpty());
      }

      private void approve(PendingDuet d, int pos) {
          if (d.duetReelId == null || myUid == null) return;

          // 1. Move from pending → published reels node
          Map<String, Object> updates = new HashMap<>();
          updates.put("approvedAt",  com.google.firebase.database.ServerValue.TIMESTAMP);
          updates.put("approved",    true);
          updates.put("isPublic",    true);

          FirebaseUtils.db().getReference("reels").child(d.duetReelId).updateChildren(updates);

          // 2. Increment duetCount on original reel
          if (d.originalReelId != null) {
              FirebaseUtils.db().getReference("reels").child(d.originalReelId)
                  .child("duetCount").setValue(com.google.firebase.database.ServerValue.increment(1));
          }

          // 3. Notify the duetor
          Map<String, Object> notif = new HashMap<>();
          notif.put("type",      "duet_approved");
          notif.put("reelId",    d.originalReelId);
          notif.put("approvedAt", com.google.firebase.database.ServerValue.TIMESTAMP);
          FirebaseUtils.db().getReference("reel_notifications")
              .child(d.duetorUid).push().setValue(notif);

          // 4. Remove from pending queue
          FirebaseUtils.db().getReference("duet_pending").child(myUid).child(d.duetReelId).removeValue();

          pending.remove(pos);
          adapter.notifyItemRemoved(pos);
          tvCount.setText(pending.size() + " pending");
          showEmpty(pending.isEmpty());
          Toast.makeText(this, "Duet approved ✅", Toast.LENGTH_SHORT).show();
      }

      private void reject(PendingDuet d, int pos) {
          if (d.duetReelId == null || myUid == null) return;

          // Notify duetor of rejection
          Map<String, Object> notif = new HashMap<>();
          notif.put("type",       "duet_rejected");
          notif.put("reelId",     d.originalReelId);
          notif.put("rejectedAt", com.google.firebase.database.ServerValue.TIMESTAMP);
          FirebaseUtils.db().getReference("reel_notifications")
              .child(d.duetorUid).push().setValue(notif);

          // Remove from pending
          FirebaseUtils.db().getReference("duet_pending").child(myUid).child(d.duetReelId).removeValue();
          // Delete draft reel
          FirebaseUtils.db().getReference("reels").child(d.duetReelId).removeValue();

          pending.remove(pos);
          adapter.notifyItemRemoved(pos);
          tvCount.setText(pending.size() + " pending");
          showEmpty(pending.isEmpty());
          Toast.makeText(this, "Duet rejected", Toast.LENGTH_SHORT).show();
      }

      private void showEmpty(boolean show) {
          layoutEmpty.setVisibility(show ? View.VISIBLE : View.GONE);
          rvQueue.setVisibility(show ? View.GONE : View.VISIBLE);
      }

      // ── Data model ────────────────────────────────────────────────────────────
      static class PendingDuet {
          String duetReelId, duetorUid, duetorName, duetorPhoto;
          String videoUrl, thumbUrl, originalReelId;
          Integer layoutMode;
      }

      // ── Adapter ───────────────────────────────────────────────────────────────
      static class ApprovalAdapter extends RecyclerView.Adapter<ApprovalAdapter.VH> {
          interface OnApprove { void onApprove(PendingDuet d, int pos); }
          interface OnReject  { void onReject(PendingDuet d, int pos); }

          private final List<PendingDuet> items;
          private final OnApprove onApprove;
          private final OnReject  onReject;

          ApprovalAdapter(List<PendingDuet> items, OnApprove a, OnReject r) {
              this.items = items; this.onApprove = a; this.onReject = r;
          }
          @NonNull @Override
          public VH onCreateViewHolder(@NonNull ViewGroup p, int t) {
              View v = LayoutInflater.from(p.getContext())
                  .inflate(R.layout.item_duet_approval, p, false);
              return new VH(v);
          }
          @Override public void onBindViewHolder(@NonNull VH h, int pos) {
              PendingDuet d = items.get(pos);
              h.tvName.setText(d.duetorName != null ? d.duetorName : "Unknown");
              if (d.thumbUrl != null && !d.thumbUrl.isEmpty()) {
                  Glide.with(h.ivThumb).load(d.thumbUrl).centerCrop().into(h.ivThumb);
              }
              if (d.duetorPhoto != null && !d.duetorPhoto.isEmpty()) {
                  Glide.with(h.ivAvatar).load(d.duetorPhoto).circleCrop().into(h.ivAvatar);
              }
              h.btnApprove.setOnClickListener(v -> onApprove.onApprove(d, h.getAdapterPosition()));
              h.btnReject.setOnClickListener(v  -> onReject.onReject(d,  h.getAdapterPosition()));
          }
          @Override public int getItemCount() { return items.size(); }

          static class VH extends RecyclerView.ViewHolder {
              ImageView ivAvatar, ivThumb;
              TextView  tvName;
              Button    btnApprove, btnReject;
              VH(View v) {
                  super(v);
                  ivAvatar   = v.findViewById(R.id.iv_approval_avatar);
                  ivThumb    = v.findViewById(R.id.iv_approval_thumb);
                  tvName     = v.findViewById(R.id.tv_approval_name);
                  btnApprove = v.findViewById(R.id.btn_approval_approve);
                  btnReject  = v.findViewById(R.id.btn_approval_reject);
              }
          }
      }
  }
  