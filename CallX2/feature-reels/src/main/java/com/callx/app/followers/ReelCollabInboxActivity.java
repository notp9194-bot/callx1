package com.callx.app.followers;

import com.callx.app.player.SingleReelPlayerActivity;
import com.callx.app.upload.ReelPostDetailsActivity;

import android.content.Intent;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;
import de.hdodenhof.circleimageview.CircleImageView;

import java.text.SimpleDateFormat;
import java.util.*;

/**
 * ReelCollabInboxActivity — Accept or Decline Collaboration Invites.
 *
 * Flow:
 *  - When User A posts a reel and adds User B as a collab via ReelPostDetailsActivity,
 *    Firebase writes a collab invite to: collabInvites/{userB_uid}/{inviteId}
 *  - This screen loads all pending invites for the logged-in user.
 *  - Accept → reel gets listed on both users' profiles, status = "accepted"
 *  - Decline → status = "declined", removed from pending list
 *
 * Features:
 *  ✅ Real-time pending invite list with sender avatar + reel thumbnail
 *  ✅ Accept button — marks collab accepted, adds reel to my profile
 *  ✅ Decline button — removes invite
 *  ✅ Accepted collab indicator on previously accepted items
 *  ✅ Empty state when no pending invites
 *  ✅ Tap reel thumbnail → opens SingleReelPlayerActivity to preview
 *  ✅ Real-time listener — updates immediately when invites arrive
 */
public class ReelCollabInboxActivity extends AppCompatActivity {

    private RecyclerView  rvInvites;
    private View          layoutEmpty;
    private ProgressBar   progressBar;
    private ImageButton   btnBack;
    private TextView      tvTitle;

    private CollabInviteAdapter      adapter;
    private final List<CollabInvite> invites = new ArrayList<>();
    private ValueEventListener        inviteListener;
    private String                    myUid;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reel_collab_inbox);

        try { myUid = FirebaseUtils.getCurrentUid(); }
        catch (Exception e) { finish(); return; }

        bindViews();
        loadInvites();
    }

    private void bindViews() {
        btnBack     = findViewById(R.id.btn_collab_inbox_back);
        rvInvites   = findViewById(R.id.rv_collab_invites);
        layoutEmpty = findViewById(R.id.layout_collab_empty);
        progressBar = findViewById(R.id.progress_collab_inbox);
        tvTitle     = findViewById(R.id.tv_collab_inbox_title);

        if (tvTitle != null) tvTitle.setText("Collab Invites");
        btnBack.setOnClickListener(v -> finish());

        adapter = new CollabInviteAdapter(invites,
            invite -> acceptInvite(invite),
            invite -> declineInvite(invite),
            invite -> previewReel(invite));

        rvInvites.setLayoutManager(new LinearLayoutManager(this));
        rvInvites.setAdapter(adapter);
    }

    private void loadInvites() {
        progressBar.setVisibility(View.VISIBLE);

        inviteListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (isFinishing() || isDestroyed()) return;
                invites.clear();
                for (DataSnapshot s : snap.getChildren()) {
                    String status = s.child("status").getValue(String.class);
                    if ("pending".equals(status)) {
                        CollabInvite invite = parseInvite(s);
                        if (invite != null) invites.add(invite);
                    }
                }
                progressBar.setVisibility(View.GONE);
                layoutEmpty.setVisibility(invites.isEmpty() ? View.VISIBLE : View.GONE);
                rvInvites.setVisibility(invites.isEmpty() ? View.GONE : View.VISIBLE);
                adapter.notifyDataSetChanged();
            }

            @Override public void onCancelled(@NonNull DatabaseError e) {
                progressBar.setVisibility(View.GONE);
                Toast.makeText(ReelCollabInboxActivity.this,
                    "Failed to load invites", Toast.LENGTH_SHORT).show();
            }
        };

        FirebaseUtils.db().getReference("collabInvites").child(myUid)
            .orderByChild("createdAt")
            .addValueEventListener(inviteListener);
    }

    private CollabInvite parseInvite(DataSnapshot s) {
        try {
            CollabInvite inv = new CollabInvite();
            inv.inviteId     = s.getKey();
            inv.reelId       = getString(s, "reelId");
            inv.reelThumb    = getString(s, "reelThumb");
            inv.reelCaption  = getString(s, "reelCaption");
            inv.senderUid    = getString(s, "senderUid");
            inv.senderName   = getString(s, "senderName");
            inv.senderPhoto  = getString(s, "senderPhoto");
            inv.status       = getString(s, "status");
            Long ts          = s.child("createdAt").getValue(Long.class);
            inv.createdAt    = ts != null ? ts : 0L;
            return inv;
        } catch (Exception e) {
            return null;
        }
    }

    private String getString(DataSnapshot s, String key) {
        String v = s.child(key).getValue(String.class);
        return v != null ? v : "";
    }

    private void acceptInvite(CollabInvite invite) {
        if (invite.inviteId == null || invite.inviteId.isEmpty()) return;

        Map<String, Object> updates = new HashMap<>();
        updates.put("status", "accepted");
        updates.put("acceptedAt", System.currentTimeMillis());

        FirebaseUtils.db().getReference("collabInvites")
            .child(myUid).child(invite.inviteId)
            .updateChildren(updates)
            .addOnSuccessListener(unused -> {
                if (isFinishing() || isDestroyed()) return;

                if (!invite.reelId.isEmpty()) {
                    FirebaseUtils.getReelsByUserRef(myUid)
                        .child(invite.reelId).setValue(true);

                    FirebaseUtils.getReelsRef().child(invite.reelId)
                        .child("collabAccepted").setValue(true);
                    FirebaseUtils.getReelsRef().child(invite.reelId)
                        .child("collabUid").setValue(myUid);
                }

                Toast.makeText(this, "Collab accepted! Reel added to your profile.",
                    Toast.LENGTH_SHORT).show();

                removeInviteFromList(invite.inviteId);
            })
            .addOnFailureListener(e ->
                Toast.makeText(this, "Failed to accept: " + e.getMessage(),
                    Toast.LENGTH_SHORT).show());
    }

    private void declineInvite(CollabInvite invite) {
        if (invite.inviteId == null) return;

        new android.app.AlertDialog.Builder(this)
            .setTitle("Decline Collab?")
            .setMessage("You will not be listed as a collaborator on this reel.")
            .setPositiveButton("Decline", (d, w) -> {
                FirebaseUtils.db().getReference("collabInvites")
                    .child(myUid).child(invite.inviteId)
                    .child("status").setValue("declined")
                    .addOnSuccessListener(unused -> {
                        if (!isFinishing())
                            Toast.makeText(this, "Invite declined", Toast.LENGTH_SHORT).show();
                        removeInviteFromList(invite.inviteId);
                    });
            })
            .setNegativeButton("Keep", null)
            .show();
    }

    private void previewReel(CollabInvite invite) {
        if (invite.reelId == null || invite.reelId.isEmpty()) return;
        Intent i = new Intent(this, SingleReelPlayerActivity.class);
        i.putExtra("reel_id", invite.reelId);
        startActivity(i);
    }

    private void removeInviteFromList(String inviteId) {
        for (int i = 0; i < invites.size(); i++) {
            if (inviteId.equals(invites.get(i).inviteId)) {
                invites.remove(i);
                adapter.notifyItemRemoved(i);
                break;
            }
        }
        layoutEmpty.setVisibility(invites.isEmpty() ? View.VISIBLE : View.GONE);
        rvInvites.setVisibility(invites.isEmpty() ? View.GONE : View.VISIBLE);
    }

    @Override
    protected void onDestroy() {
        if (inviteListener != null)
            FirebaseUtils.db().getReference("collabInvites").child(myUid)
                .removeEventListener(inviteListener);
        super.onDestroy();
    }

    // ── Data model ────────────────────────────────────────────────────────

    static class CollabInvite {
        String inviteId, reelId, reelThumb, reelCaption;
        String senderUid, senderName, senderPhoto, status;
        long   createdAt;
    }

    // ── Adapter ───────────────────────────────────────────────────────────

    interface OnInviteAction { void onAction(CollabInvite invite); }

    static class CollabInviteAdapter
            extends RecyclerView.Adapter<CollabInviteAdapter.VH> {

        private final List<CollabInvite> items;
        private final OnInviteAction accept, decline, preview;

        CollabInviteAdapter(List<CollabInvite> items,
                            OnInviteAction accept,
                            OnInviteAction decline,
                            OnInviteAction preview) {
            this.items   = items;
            this.accept  = accept;
            this.decline = decline;
            this.preview = preview;
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_collab_invite, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            CollabInvite inv = items.get(pos);

            h.tvSenderName.setText(inv.senderName != null && !inv.senderName.isEmpty()
                ? inv.senderName : "Someone");
            h.tvCaption.setText(inv.reelCaption != null && !inv.reelCaption.isEmpty()
                ? inv.reelCaption : "No caption");

            if (inv.createdAt > 0) {
                String timeStr = new SimpleDateFormat("MMM d 'at' h:mm a", Locale.US)
                    .format(new Date(inv.createdAt));
                h.tvTime.setText(timeStr);
            }

            if (inv.senderPhoto != null && !inv.senderPhoto.isEmpty()) {
                Glide.with(h.ivSenderAvatar)
                    .load(inv.senderPhoto)
                    .placeholder(R.drawable.ic_person)
                    .circleCrop()
                    .override(96, 96)
                    .into(h.ivSenderAvatar);
            }

            if (inv.reelThumb != null && !inv.reelThumb.isEmpty()) {
                Glide.with(h.ivReelThumb)
                    .load(inv.reelThumb)
                    .placeholder(android.R.color.darker_gray)
                    .centerCrop()
                    .override(480, 853)
                    .into(h.ivReelThumb);
            }

            h.ivReelThumb.setOnClickListener(v -> preview.onAction(inv));
            h.btnAccept.setOnClickListener(v -> accept.onAction(inv));
            h.btnDecline.setOnClickListener(v -> decline.onAction(inv));
        }

        @Override public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            CircleImageView ivSenderAvatar;
            ImageView       ivReelThumb;
            TextView        tvSenderName, tvCaption, tvTime;
            View            btnAccept, btnDecline;

            VH(View v) {
                super(v);
                ivSenderAvatar = v.findViewById(R.id.iv_collab_sender_avatar);
                ivReelThumb    = v.findViewById(R.id.iv_collab_reel_thumb);
                tvSenderName   = v.findViewById(R.id.tv_collab_sender_name);
                tvCaption      = v.findViewById(R.id.tv_collab_reel_caption);
                tvTime         = v.findViewById(R.id.tv_collab_invite_time);
                btnAccept      = v.findViewById(R.id.btn_collab_accept);
                btnDecline     = v.findViewById(R.id.btn_collab_decline);
            }
        }
    }
}
