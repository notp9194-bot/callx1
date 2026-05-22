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

        // Expect callx://join/{groupId}
        String groupId = data.getLastPathSegment();
        if (groupId == null || groupId.isEmpty()) {
            Toast.makeText(this, "Invalid invite link", Toast.LENGTH_SHORT).show();
            finish(); return;
        }

        String uid  = FirebaseAuth.getInstance().getCurrentUser().getUid();
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

                        // Add user as member
                        Map<String, Object> memberData = new HashMap<>();
                        memberData.put("name", name != null ? name : "Member");
                        memberData.put("role", "member");
                        memberData.put("joinedAt", System.currentTimeMillis());
                        FirebaseUtils.getGroupMembersRef(groupId)
                                .child(uid).setValue(memberData);

                        // Add group to user's profile
                        FirebaseUtils.db().getReference("users")
                                .child(uid).child("groups")
                                .child(groupId).setValue(true);

                        Toast.makeText(JoinGroupActivity.this,
                                "Joined '" + groupName + "'! 🎉",
                                Toast.LENGTH_SHORT).show();

                        Intent i = new Intent(JoinGroupActivity.this,
                                GroupChatActivity.class);
                        i.putExtra("groupId",   groupId);
                        i.putExtra("groupName",
                                groupName != null ? groupName : "Group");
                        startActivity(i);
                        finish();
                    }
                    @Override public void onCancelled(DatabaseError e) {
                        Toast.makeText(JoinGroupActivity.this,
                                "Error joining group", Toast.LENGTH_SHORT).show();
                        finish();
                    }
                });
    }
}
