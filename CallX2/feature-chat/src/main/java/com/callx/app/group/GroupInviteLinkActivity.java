package com.callx.app.group;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.callx.app.chat.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * GroupInviteLinkActivity — Generate, share, and reset group invite links.
 *
 * Firebase path: /groups/{groupId}/inviteCode = "abc123xyz"
 * Deep link format: "https://callx.app/invite/{inviteCode}"
 *
 * Flow:
 *   1. Admin opens this screen from GroupSettingsActivity
 *   2. App fetches/generates inviteCode from Firebase
 *   3. User can Copy, Share, or Reset the link
 *   4. Anyone who opens the deep link is added to the group
 *
 * To handle deep link in MainActivity:
 *   String code = intent.getData().getLastPathSegment();
 *   GroupInviteHandler.joinWithCode(code, ctx);
 */
public class GroupInviteLinkActivity extends AppCompatActivity {

    private static final String BASE_URL = "https://callx.app/invite/";

    private String groupId;
    private String groupName;
    private TextView tvLink;
    private Button btnCopy, btnShare, btnReset;
    private View progressBar;

    private DatabaseReference groupRef;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_group_invite_link);

        groupId   = getIntent().getStringExtra("groupId");
        groupName = getIntent().getStringExtra("groupName");

        if (groupId == null) { finish(); return; }

        groupRef = FirebaseDatabase.getInstance()
                .getReference("groups").child(groupId);

        setupViews();
        loadOrGenerateLink();
    }

    private void setupViews() {
        tvLink      = findViewById(R.id.tv_invite_link);
        btnCopy     = findViewById(R.id.btn_copy_link);
        btnShare    = findViewById(R.id.btn_share_link);
        btnReset    = findViewById(R.id.btn_reset_link);
        progressBar = findViewById(R.id.progress_bar);

        TextView tvTitle = findViewById(R.id.tv_title);
        tvTitle.setText("Invite to " + groupName);

        ImageView ivBack = findViewById(R.id.iv_back);
        ivBack.setOnClickListener(v -> finish());

        btnCopy.setOnClickListener(v -> copyLink());
        btnShare.setOnClickListener(v -> shareLink());
        btnReset.setOnClickListener(v -> confirmReset());
    }

    private void loadOrGenerateLink() {
        progressBar.setVisibility(View.VISIBLE);
        groupRef.child("inviteCode").addListenerForSingleValueEvent(
                new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                progressBar.setVisibility(View.GONE);
                String code = snap.getValue(String.class);
                if (code == null) {
                    generateNewCode();
                } else {
                    displayLink(code);
                }
            }
            @Override public void onCancelled(DatabaseError e) {
                progressBar.setVisibility(View.GONE);
                Toast.makeText(GroupInviteLinkActivity.this,
                        "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void generateNewCode() {
        String code = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Map<String, Object> updates = new HashMap<>();
        updates.put("inviteCode", code);
        updates.put("inviteCreatedBy", FirebaseAuth.getInstance().getUid());
        updates.put("inviteCreatedAt", System.currentTimeMillis());
        groupRef.updateChildren(updates)
                .addOnSuccessListener(v -> displayLink(code))
                .addOnFailureListener(e -> Toast.makeText(this,
                        "Failed to generate link", Toast.LENGTH_SHORT).show());
    }

    private void displayLink(String code) {
        String link = BASE_URL + code;
        tvLink.setText(link);
        btnCopy.setEnabled(true);
        btnShare.setEnabled(true);
        btnReset.setEnabled(true);
    }

    private void copyLink() {
        String link = tvLink.getText().toString();
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("Invite Link", link));
        Toast.makeText(this, "Link copied!", Toast.LENGTH_SHORT).show();
    }

    private void shareLink() {
        String link = tvLink.getText().toString();
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT,
                "Join " + groupName + " on CallX2: " + link);
        startActivity(Intent.createChooser(intent, "Share invite link"));
    }

    private void confirmReset() {
        new AlertDialog.Builder(this)
            .setTitle("Reset Link")
            .setMessage("Current link will stop working. Generate a new one?")
            .setPositiveButton("Reset", (d, w) -> resetLink())
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void resetLink() {
        groupRef.child("inviteCode").removeValue()
                .addOnSuccessListener(v -> {
                    tvLink.setText("Generating...");
                    generateNewCode();
                    Toast.makeText(this, "Link reset", Toast.LENGTH_SHORT).show();
                });
    }
}
