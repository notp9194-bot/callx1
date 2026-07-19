package com.callx.app.channel;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import com.bumptech.glide.Glide;
import com.callx.app.status.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.android.material.button.MaterialButton;
import com.google.firebase.database.*;
import de.hdodenhof.circleimageview.CircleImageView;
import java.util.HashMap;
import java.util.Map;

/**
 * ChannelJoinActivity — handles joining a private channel via invite code / deep link.
 *
 * Entry points:
 *   1. Deep link: callx://channel/join/{code}  (Intent filter in AndroidManifest)
 *   2. Intent with EXTRA_INVITE_CODE
 *
 * Flow:
 *   1. Resolve invite code at Firebase: channelInviteCodes/{code} → channelId
 *   2. Fetch channel metadata: channels/{channelId}
 *   3. Show channel preview card
 *   4. User taps "Join" → write follow record + increment followers
 *   5. Open ChannelViewerActivity and finish
 */
public class ChannelJoinActivity extends AppCompatActivity {

    public static final String EXTRA_INVITE_CODE = "inviteCode";
    public static final String EXTRA_INVITE_LINK = "inviteLink";

    private View     layoutLoading, layoutPreview, layoutError;
    private CircleImageView ivChannelIcon;
    private TextView tvChannelName, tvFollowers, tvDescription, tvErrorMsg;
    private MaterialButton btnJoin, btnNoThanks;

    private String resolvedChannelId, resolvedChannelName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_channel_join);

        Toolbar toolbar = findViewById(R.id.toolbar_join);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Join Channel");
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        layoutLoading = findViewById(R.id.layout_join_loading);
        layoutPreview = findViewById(R.id.layout_join_preview);
        layoutError   = findViewById(R.id.layout_join_error);
        ivChannelIcon = findViewById(R.id.iv_join_channel_icon);
        tvChannelName = findViewById(R.id.tv_join_channel_name);
        tvFollowers   = findViewById(R.id.tv_join_followers);
        tvDescription = findViewById(R.id.tv_join_description);
        tvErrorMsg    = findViewById(R.id.tv_join_error_msg);
        btnJoin       = findViewById(R.id.btn_join_channel);
        btnNoThanks   = findViewById(R.id.btn_join_no_thanks);

        showLoading();

        // Determine invite code: from Intent extra or deep link URI
        String inviteCode = getIntent().getStringExtra(EXTRA_INVITE_CODE);
        if (inviteCode == null || inviteCode.isEmpty()) {
            android.net.Uri data = getIntent().getData();
            if (data != null) {
                // callx://channel/join/{code}  or  https://callx.app/channel/join/{code}
                inviteCode = data.getLastPathSegment();
            }
        }

        if (inviteCode == null || inviteCode.isEmpty()) {
            showError("Invalid invite link.");
            return;
        }

        resolveInviteCode(inviteCode);
    }

    // ── Invite resolution ─────────────────────────────────────────────────

    private void resolveInviteCode(String code) {
        FirebaseUtils.db().getReference()
                .child("channelInviteCodes").child(code)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snap) {
                        String channelId = snap.getValue(String.class);
                        if (channelId == null || channelId.isEmpty()) {
                            showError("This invite link is invalid or has expired.");
                        } else {
                            fetchChannelPreview(channelId);
                        }
                    }
                    @Override
                    public void onCancelled(@NonNull DatabaseError e) {
                        showError("Failed to validate invite link. Please try again.");
                    }
                });
    }

    private void fetchChannelPreview(String channelId) {
        FirebaseUtils.db().getReference()
                .child("channels").child(channelId)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snap) {
                        if (!snap.exists()) { showError("Channel not found."); return; }

                        resolvedChannelId   = channelId;
                        resolvedChannelName = snap.child("name").getValue(String.class);
                        String desc         = snap.child("description").getValue(String.class);
                        String iconUrl      = snap.child("iconUrl").getValue(String.class);
                        Long   followers    = snap.child("followers").getValue(Long.class);
                        Boolean isVerified  = snap.child("isVerified").getValue(Boolean.class);

                        showPreview(resolvedChannelName, desc, iconUrl,
                                followers != null ? followers : 0L,
                                Boolean.TRUE.equals(isVerified));
                    }
                    @Override
                    public void onCancelled(@NonNull DatabaseError e) {
                        showError("Failed to load channel info.");
                    }
                });
    }

    // ── Join logic ────────────────────────────────────────────────────────

    private void joinChannel() {
        if (resolvedChannelId == null) return;
        String uid = FirebaseUtils.getCurrentUid();
        if (uid == null) { Toast.makeText(this, "Please sign in to join channels.", Toast.LENGTH_SHORT).show(); return; }

        btnJoin.setEnabled(false);
        btnJoin.setText("Joining…");

        // Multi-path update
        Map<String, Object> updates = new HashMap<>();
        updates.put("channelFollows/" + uid + "/" + resolvedChannelId, true);
        updates.put("channelFollowers/" + resolvedChannelId + "/" + uid + "/uid", uid);
        updates.put("channelFollowers/" + resolvedChannelId + "/" + uid + "/joinedAt",
                ServerValue.TIMESTAMP);

        FirebaseUtils.db().getReference().updateChildren(updates, (err, ref) -> {
            if (err == null) {
                // Increment follower count via transaction
                FirebaseUtils.db().getReference()
                        .child("channels").child(resolvedChannelId).child("followers")
                        .runTransaction(new Transaction.Handler() {
                            @NonNull @Override
                            public Transaction.Result doTransaction(@NonNull MutableData d) {
                                Long v = d.getValue(Long.class);
                                d.setValue(v == null ? 1 : v + 1);
                                return Transaction.success(d);
                            }
                            @Override
                            public void onComplete(DatabaseError e, boolean committed, DataSnapshot s) {
                                openChannelViewer();
                            }
                        });
            } else {
                btnJoin.setEnabled(true);
                btnJoin.setText("Join Channel");
                Toast.makeText(this, "Failed to join. Please try again.", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void openChannelViewer() {
        Intent intent = new Intent(this, ChannelViewerActivity.class);
        intent.putExtra(ChannelViewerActivity.EXTRA_CHANNEL_ID,   resolvedChannelId);
        intent.putExtra(ChannelViewerActivity.EXTRA_CHANNEL_NAME, resolvedChannelName != null ? resolvedChannelName : "");
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }

    // ── UI state helpers ──────────────────────────────────────────────────

    private void showLoading() {
        if (layoutLoading != null) layoutLoading.setVisibility(View.VISIBLE);
        if (layoutPreview != null) layoutPreview.setVisibility(View.GONE);
        if (layoutError   != null) layoutError.setVisibility(View.GONE);
    }

    private void showPreview(String name, String desc, String iconUrl,
                              long followers, boolean verified) {
        if (layoutLoading != null) layoutLoading.setVisibility(View.GONE);
        if (layoutPreview != null) layoutPreview.setVisibility(View.VISIBLE);
        if (layoutError   != null) layoutError.setVisibility(View.GONE);

        if (tvChannelName != null) {
            String label = verified ? name + " ✓" : name;
            tvChannelName.setText(label != null ? label : "");
        }
        if (tvFollowers != null)
            tvFollowers.setText(followers + " followers");
        if (tvDescription != null)
            tvDescription.setText(desc != null ? desc : "");

        if (ivChannelIcon != null && iconUrl != null && !iconUrl.isEmpty()) {
            Glide.with(this).load(iconUrl).circleCrop().into(ivChannelIcon);
        }

        if (btnJoin != null) btnJoin.setOnClickListener(v -> joinChannel());
        if (btnNoThanks != null) btnNoThanks.setOnClickListener(v -> finish());
    }

    private void showError(String message) {
        if (layoutLoading != null) layoutLoading.setVisibility(View.GONE);
        if (layoutPreview != null) layoutPreview.setVisibility(View.GONE);
        if (layoutError   != null) layoutError.setVisibility(View.VISIBLE);
        if (tvErrorMsg    != null) tvErrorMsg.setText(message);
    }
}
