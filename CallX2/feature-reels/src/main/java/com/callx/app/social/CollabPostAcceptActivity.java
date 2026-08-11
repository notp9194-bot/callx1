package com.callx.app.social;

import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import com.bumptech.glide.Glide;
import com.callx.app.notifications.CollabRepostNotificationHelper;
import com.callx.app.reels.R;
import com.callx.app.utils.Constants;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;
import de.hdodenhof.circleimageview.CircleImageView;
import java.util.HashMap;
import java.util.Map;

/**
 * CollabPostAcceptActivity — Shown when a user receives a Joint Collab Post invite.
 *
 * On Accept: reel goes live on both initiator's and collaborator's feeds simultaneously.
 *            Both are shown as co-authors in the feed.
 * On Decline: invite is declined, initiator is notified.
 *
 * Launch from notification or CollabInboxActivity:
 *   Intent i = new Intent(ctx, CollabPostAcceptActivity.class);
 *   i.putExtra(EXTRA_INVITE_ID,      inviteId);
 *   i.putExtra(EXTRA_REEL_ID,        reelId);
 *   i.putExtra(EXTRA_INITIATOR_UID,  initiatorUid);
 *   i.putExtra(EXTRA_INITIATOR_NAME, initiatorName);
 *   i.putExtra(EXTRA_INITIATOR_PHOTO,initiatorPhoto);
 *   i.putExtra(EXTRA_CAPTION,        initiatorCaption);
 *   i.putExtra(EXTRA_THUMB_URL,      thumbUrl);
 */
public class CollabPostAcceptActivity extends AppCompatActivity {

    public static final String EXTRA_INVITE_ID       = "cpa_invite_id";
    public static final String EXTRA_REEL_ID         = "cpa_reel_id";
    public static final String EXTRA_INITIATOR_UID   = "cpa_init_uid";
    public static final String EXTRA_INITIATOR_NAME  = "cpa_init_name";
    public static final String EXTRA_INITIATOR_PHOTO = "cpa_init_photo";
    public static final String EXTRA_CAPTION         = "cpa_caption";
    public static final String EXTRA_THUMB_URL       = "cpa_thumb_url";

    // ── UI ────────────────────────────────────────────────────────────────
    private ImageView       ivThumb;
    private CircleImageView ivInitiatorAvatar;
    private TextView        tvInitiatorName, tvCaption, tvInfo;
    private Button          btnAccept, btnDecline;
    private ProgressBar     progressBar;

    // ── State ────────────────────────────────────────────────────────────
    private String myUid, myName, myPhoto;
    private String inviteId, reelId, initiatorUid, initiatorName, initiatorPhoto, caption, thumbUrl;
    private boolean actionTaken = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_collab_post_accept);

        inviteId      = getIntent().getStringExtra(EXTRA_INVITE_ID);
        reelId        = getIntent().getStringExtra(EXTRA_REEL_ID);
        initiatorUid  = getIntent().getStringExtra(EXTRA_INITIATOR_UID);
        initiatorName = getIntent().getStringExtra(EXTRA_INITIATOR_NAME);
        initiatorPhoto= getIntent().getStringExtra(EXTRA_INITIATOR_PHOTO);
        caption       = getIntent().getStringExtra(EXTRA_CAPTION);
        thumbUrl      = getIntent().getStringExtra(EXTRA_THUMB_URL);

        if (inviteId == null || reelId == null) { finish(); return; }

        myUid  = FirebaseUtils.getCurrentUid();
        myName = FirebaseUtils.getCurrentName();
        if (myUid == null) { finish(); return; }

        loadMyPhoto();
        bindViews();
    }

    private void loadMyPhoto() {
        FirebaseDatabase.getInstance(Constants.DB_URL)
            .getReference("reels/users").child(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot s) {
                    Object p = s.child("photoUrl").getValue();
                    myPhoto = p != null ? p.toString() : "";
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void bindViews() {
        ivThumb          = findViewById(R.id.iv_accept_thumb);
        ivInitiatorAvatar= findViewById(R.id.iv_accept_initiator_avatar);
        tvInitiatorName  = findViewById(R.id.tv_accept_initiator_name);
        tvCaption        = findViewById(R.id.tv_accept_caption);
        tvInfo           = findViewById(R.id.tv_accept_info);
        btnAccept        = findViewById(R.id.btn_accept_collab);
        btnDecline       = findViewById(R.id.btn_decline_collab);
        progressBar      = findViewById(R.id.progress_accept_collab);

        View btnBack = findViewById(R.id.btn_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        if (thumbUrl != null && !thumbUrl.isEmpty() && ivThumb != null)
            Glide.with(this).load(thumbUrl).centerCrop().into(ivThumb);
        if (initiatorPhoto != null && !initiatorPhoto.isEmpty() && ivInitiatorAvatar != null)
            Glide.with(this).load(initiatorPhoto).circleCrop().into(ivInitiatorAvatar);
        if (tvInitiatorName != null)
            tvInitiatorName.setText("@" + (initiatorName != null ? initiatorName : ""));
        if (tvCaption != null)
            tvCaption.setText(caption != null ? caption : "");
        if (tvInfo != null)
            tvInfo.setText((initiatorName != null ? initiatorName : "Someone") +
                " invited you to co-author this Reel. Both of your names will appear as creators.");

        btnAccept.setOnClickListener(v -> acceptInvite());
        btnDecline.setOnClickListener(v -> declineInvite());
    }

    private void acceptInvite() {
        if (actionTaken) return;
        actionTaken = true;
        progressBar.setVisibility(View.VISIBLE);
        btnAccept.setEnabled(false);
        btnDecline.setEnabled(false);

        long now = System.currentTimeMillis();
        DatabaseReference root = FirebaseDatabase.getInstance(Constants.DB_URL).getReference();
        Map<String, Object> updates = new HashMap<>();

        // Accept the invite
        updates.put("collabPostInvites/" + myUid + "/" + inviteId + "/status",       "accepted");
        updates.put("collabPostInvites/" + myUid + "/" + inviteId + "/acceptedAt",   now);
        updates.put("collabPostInvitesSent/" + initiatorUid + "/" + inviteId + "/status", "accepted");

        // ✅ MULTI-COLLABORATOR: only THIS collaborator's own entry in the
        // reel's collabMap flips to "accepted" — other invited people's
        // entries (still pending/declined) are untouched by this write.
        updates.put("reels/" + reelId + "/collabMap/" + myUid + "/status",      "accepted");
        updates.put("reels/" + reelId + "/collabMap/" + myUid + "/respondedAt", now);
        updates.put("reels/" + reelId + "/collabMap/" + myUid + "/displayName", myName != null ? myName : "");
        updates.put("reels/" + reelId + "/collabMap/" + myUid + "/avatarUrl",   myPhoto != null ? myPhoto : "");

        // At least one accepted collaborator makes this a live joint post.
        updates.put("reels/" + reelId + "/isCollabPost",        true);
        updates.put("reels/" + reelId + "/collabAcceptedAt",    now);
        // Legacy single-collaborator mirror fields — only overwritten if this
        // is (still) the first/only collaborator; harmless if a later accept
        // from another invitee overwrites it again, since old builds only
        // ever render one collaborator anyway.
        updates.put("reels/" + reelId + "/collabUid",           myUid);
        updates.put("reels/" + reelId + "/collabDisplayName",   myName != null ? myName : "");
        updates.put("reels/" + reelId + "/collabAvatarUrl",     myPhoto != null ? myPhoto : "");
        updates.put("reels/" + reelId + "/collabInviteId",      inviteId);

        // Add to collaborator's own feed index
        updates.put("reelsByUser/" + myUid + "/" + reelId,      true);
        // Also add to collaborator's profile grid
        updates.put("userReels/" + myUid + "/" + reelId,        true);

        root.updateChildren(updates, (error, ref) -> {
            if (error != null) {
                progressBar.setVisibility(View.GONE);
                actionTaken = false;
                btnAccept.setEnabled(true);
                btnDecline.setEnabled(true);
                Toast.makeText(this, "Failed. Please try again.", Toast.LENGTH_SHORT).show();
                return;
            }
            // Notify initiator of acceptance
            try {
                CollabRepostNotificationHelper.notifyCollabAccepted(
                    this, initiatorUid, myUid, myName != null ? myName : "Your collaborator",
                    reelId, thumbUrl != null ? thumbUrl : ""
                );
            } catch (Exception ignored) {}
            // Recompute reel-level isCollabPending: true only while at least
            // one OTHER invited collaborator hasn't responded yet.
            refreshPendingFlagThenFinish();
        });
    }

    /**
     * Reads the reel's full collabMap after an accept/decline write and
     * updates isCollabPending to reflect whether any invited collaborator is
     * still awaiting a response — a multi-person invite shouldn't stay
     * flagged "pending" forever just because one of several people answered.
     */
    private void refreshPendingFlagThenFinish() {
        FirebaseDatabase.getInstance(Constants.DB_URL)
            .getReference("reels/" + reelId + "/collabMap")
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    boolean anyPending = false;
                    for (DataSnapshot child : snap.getChildren()) {
                        String status = child.child("status").getValue(String.class);
                        if ("pending".equals(status)) { anyPending = true; break; }
                    }
                    FirebaseDatabase.getInstance(Constants.DB_URL)
                        .getReference("reels/" + reelId + "/isCollabPending")
                        .setValue(anyPending);
                    finishWithSuccess();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) { finishWithSuccess(); }
            });
    }

    private void finishWithSuccess() {
        progressBar.setVisibility(View.GONE);
        Toast.makeText(this, "🎉 You're now co-author of this Reel!", Toast.LENGTH_LONG).show();
        setResult(RESULT_OK);
        finish();
    }

    private void declineInvite() {
        if (actionTaken) return;
        actionTaken = true;
        progressBar.setVisibility(View.VISIBLE);
        btnAccept.setEnabled(false);
        btnDecline.setEnabled(false);

        long now = System.currentTimeMillis();
        DatabaseReference root = FirebaseDatabase.getInstance(Constants.DB_URL).getReference();
        Map<String, Object> updates = new HashMap<>();
        updates.put("collabPostInvites/" + myUid + "/" + inviteId + "/status",      "declined");
        updates.put("collabPostInvites/" + myUid + "/" + inviteId + "/declinedAt",  now);
        updates.put("collabPostInvitesSent/" + initiatorUid + "/" + inviteId + "/status", "declined");
        // ✅ MULTI-COLLABORATOR: only THIS collaborator's own collabMap entry
        // is marked declined — other invited people's entries are untouched,
        // so the post can still go live with whoever else accepts.
        updates.put("reels/" + reelId + "/collabMap/" + myUid + "/status",      "declined");
        updates.put("reels/" + reelId + "/collabMap/" + myUid + "/respondedAt", now);

        root.updateChildren(updates, (error, ref) -> {
            try {
                CollabRepostNotificationHelper.notifyCollabDeclined(
                    this, initiatorUid, myUid, myName != null ? myName : "Someone", reelId
                );
            } catch (Exception ignored) {}
            // Recompute isCollabPending the same way accept does — other
            // invitees may still be pending even though this one declined.
            refreshPendingFlagAfterDecline();
        });
    }

    private void refreshPendingFlagAfterDecline() {
        FirebaseDatabase.getInstance(Constants.DB_URL)
            .getReference("reels/" + reelId + "/collabMap")
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    boolean anyPending = false;
                    for (DataSnapshot child : snap.getChildren()) {
                        String status = child.child("status").getValue(String.class);
                        if ("pending".equals(status)) { anyPending = true; break; }
                    }
                    FirebaseDatabase.getInstance(Constants.DB_URL)
                        .getReference("reels/" + reelId + "/isCollabPending")
                        .setValue(anyPending);
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(CollabPostAcceptActivity.this, "Invite declined.", Toast.LENGTH_SHORT).show();
                    setResult(RESULT_CANCELED);
                    finish();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(CollabPostAcceptActivity.this, "Invite declined.", Toast.LENGTH_SHORT).show();
                    setResult(RESULT_CANCELED);
                    finish();
                }
            });
    }
}
