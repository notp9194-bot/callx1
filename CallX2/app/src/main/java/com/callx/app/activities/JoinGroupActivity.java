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

                        // BUG FIX: previously this always called setValue(),
                        // silently overwriting an existing member's role
                        // (e.g. admin/owner) back to "member" and resetting
                        // their joinedAt just for tapping their own invite
                        // link again. Now: already a member → skip the
                        // write entirely and just open the chat.
                        FirebaseUtils.getGroupMembersRef(groupId).child(uid)
                                .addListenerForSingleValueEvent(new ValueEventListener() {
                                    @Override public void onDataChange(DataSnapshot memberSnap) {
                                        if (!memberSnap.exists()) {
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
                                        }
                                        openGroup(groupId, groupName);
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

    private void openGroup(String groupId, String groupName) {
        Intent i = new Intent(JoinGroupActivity.this, GroupChatActivity.class);
        i.putExtra("groupId",   groupId);
        i.putExtra("groupName", groupName != null ? groupName : "Group");
        startActivity(i);
        finish();
    }
}
