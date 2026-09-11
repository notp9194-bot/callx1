package com.callx.app.activities;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;

import java.util.HashMap;
import java.util.Map;
import com.callx.app.group.GroupChatActivity;

/**
 * Feature 10: Group Invite Link handler.
 * Launched when the user taps a callx://join/{groupId} link.
 * Adds the current user to the group, then opens GroupChatActivity.
 */
public class JoinGroupActivity extends AppCompatActivity {

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            startActivity(new Intent(this, AuthActivity.class));
            finish(); return;
        }

        Uri data = getIntent().getData();
        if (data == null) { finish(); return; }

        // Expect callx://join/{groupId}?t={inviteToken}
        String groupId = data.getLastPathSegment();
        if (groupId == null || groupId.isEmpty()) {
            Toast.makeText(this, "Invalid invite link", Toast.LENGTH_SHORT).show();
            finish(); return;
        }
        // BUG FIX: the token that GroupInfoActivity's "Reset Invite Link"
        // writes to groups/{groupId}/inviteToken was never being checked
        // here — a reset/revoked link kept working forever. If the link
        // carries no token at all (old-style links shared before any reset
        // happened), we still allow the join for backward compatibility;
        // we only reject when the group HAS a token on record and the
        // link's token doesn't match it.
        final String linkToken = data.getQueryParameter("t");

        String uid  = FirebaseUtils.getCurrentUid();
        String name = FirebaseUtils.getCurrentName();

        // Check group exists
        FirebaseUtils.getGroupsRef().child(groupId)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot snap) {
                        if (!snap.exists()) {
                            Toast.makeText(JoinGroupActivity.this,
                                    "Group not found or invite has expired",
                                    Toast.LENGTH_LONG).show();
                            finish(); return;
                        }

                        String groupName = snap.child("name")
                                .getValue(String.class);
                        String currentToken = snap.child("inviteToken")
                                .getValue(String.class);
                        if (currentToken != null && !currentToken.isEmpty()
                                && !currentToken.equals(linkToken)) {
                            Toast.makeText(JoinGroupActivity.this,
                                    "This invite link has been reset by the group admin",
                                    Toast.LENGTH_LONG).show();
                            finish(); return;
                        }

                        // WHATSAPP-LEVEL FIX: groupSettings/approvalRequired was
                        // saveable from GroupSettingsActivity but never actually
                        // checked here — every invite link added the tapper
                        // straight to "members" regardless of the toggle. Read
                        // it once alongside the group snapshot we already have.
                        String approvalStr = snap.child("groupSettings")
                                .child("approvalRequired").getValue(String.class);
                        final boolean approvalRequired = "1".equals(approvalStr);

                        // BUG FIX: previously this always called setValue(),
                        // silently overwriting an existing member's role
                        // (e.g. admin/owner) back to "member" and resetting
                        // their joinedAt just for tapping their own invite
                        // link again. Now: already a member → skip the
                        // write entirely (and skip the approval flow, since
                        // they're already in) and just open the chat.
                        FirebaseUtils.getGroupMembersRef(groupId).child(uid)
                                .addListenerForSingleValueEvent(new ValueEventListener() {
                                    @Override public void onDataChange(DataSnapshot memberSnap) {
                                        if (memberSnap.exists()) {
                                            openGroup(groupId, groupName);
                                            return;
                                        }
                                        if (approvalRequired) {
                                            requestToJoin(groupId, groupName, uid, name);
                                        } else {
                                            Map<String, Object> memberData = new HashMap<>();
                                            memberData.put("name", name != null ? name : "Member");
                                            memberData.put("role", "member");
                                            memberData.put("joinedAt", System.currentTimeMillis());
                                            FirebaseUtils.getGroupMembersRef(groupId)
                                                    .child(uid).setValue(memberData);
                                            FirebaseUtils.db().getReference("users")
                                                    .child(uid).child("groups")
                                                    .child(groupId).setValue(true);
                                            Toast.makeText(JoinGroupActivity.this,
                                                    "Joined '" + groupName + "'! 🎉",
                                                    Toast.LENGTH_SHORT).show();
                                            openGroup(groupId, groupName);
                                        }
                                    }
                                    @Override public void onCancelled(DatabaseError e) {
                                        openGroup(groupId, groupName);
                                    }
                                });
                    }
                    @Override public void onCancelled(DatabaseError e) {
                        Toast.makeText(JoinGroupActivity.this,
                                "Error joining group", Toast.LENGTH_SHORT).show();
                        finish();
                    }
                });
    }

    /**
     * groupSettings/approvalRequired is on — don't add the tapper as a
     * member directly. Instead drop a request under
     * groups/{groupId}/joinRequests/{uid} for an admin to approve/reject
     * from GroupInfoActivity's "Join Requests" row, and let them know we
     * did. They aren't a member yet, so we don't open the chat.
     */
    private void requestToJoin(String groupId, String groupName, String uid, String name) {
        FirebaseUtils.getGroupJoinRequestRef(groupId, uid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot reqSnap) {
                        if (reqSnap.exists()) {
                            Toast.makeText(JoinGroupActivity.this,
                                    "Your request to join '" + groupName + "' is still pending approval",
                                    Toast.LENGTH_LONG).show();
                            finish();
                            return;
                        }
                        Map<String, Object> request = new HashMap<>();
                        request.put("name", name != null ? name : "Member");
                        request.put("requestedAt", System.currentTimeMillis());
                        FirebaseUtils.getGroupJoinRequestRef(groupId, uid).setValue(request)
                                .addOnCompleteListener(t -> {
                                    Toast.makeText(JoinGroupActivity.this,
                                            "Request sent! Waiting for admin approval to join '"
                                                    + groupName + "'",
                                            Toast.LENGTH_LONG).show();
                                    finish();
                                });
                    }
                    @Override public void onCancelled(DatabaseError e) {
                        Toast.makeText(JoinGroupActivity.this,
                                "Could not send join request", Toast.LENGTH_SHORT).show();
                        finish();
                    }
                });
    }

    private void openGroup(String groupId, String groupName) {
        Intent i = new Intent(JoinGroupActivity.this, GroupChatActivity.class);
        i.putExtra("groupId",   groupId);
        i.putExtra("groupName", groupName != null ? groupName : "Group");
        startActivity(i);
        finish();
    }
}
