package com.callx.app.community;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.callx.app.community.CommunityRole;
import com.callx.app.repository.CommunityRepository;
import com.google.firebase.auth.FirebaseAuth;

/**
 * v31: Deep-link landing for community invite links.
 * Handles: callx://community/{communityId}?invite={token}
 *
 * Flow:
 *  1. Parse token from the URI.
 *  2. Resolve token → communityId from Firebase.
 *  3. If user is already a member → open CommunityActivity.
 *  4. If not → send join request (private) or add as member (public).
 */
public class CommunityInviteLandingActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        Uri data = intent != null ? intent.getData() : null;

        if (data == null) { finish(); return; }

        // callx://community/{communityId}?invite={token}
        String communityId = data.getPath() != null ? data.getPath().replace("/", "") : null;
        String token       = data.getQueryParameter("invite");

        if (communityId == null || communityId.isEmpty() || token == null) {
            Toast.makeText(this, "Invalid invite link", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        String currentUid  = FirebaseAuth.getInstance().getCurrentUser() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;
        String currentName = FirebaseAuth.getInstance().getCurrentUser() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getDisplayName() : null;
        String currentPhoto = FirebaseAuth.getInstance().getCurrentUser() != null
                && FirebaseAuth.getInstance().getCurrentUser().getPhotoUrl() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getPhotoUrl().toString() : null;

        if (currentUid == null) {
            // Not logged in — redirect to auth, preserve deep link
            finish();
            return;
        }

        CommunityRepository repo = CommunityRepository.getInstance(this);

        // Validate token resolves to expected communityId
        repo.resolveInviteToken(token, resolvedId -> {
            if (resolvedId == null || !resolvedId.equals(communityId)) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "Invite link is invalid or expired",
                            Toast.LENGTH_SHORT).show();
                    finish();
                });
                return;
            }

            // Add as member (if community is public) or send join request (if private).
            // v33: use a removable Observer (not a bare lambda) so we can detach it after
            // the first real emission — observeForever() otherwise leaks past finish().
            final androidx.lifecycle.LiveData<com.callx.app.db.entity.CommunityEntity> liveCommunity =
                    repo.observeCommunity(communityId);
            final androidx.lifecycle.Observer<com.callx.app.db.entity.CommunityEntity>[] observerHolder = new androidx.lifecycle.Observer[1];
            observerHolder[0] = community -> {
                if (community == null) return; // wait for the real value instead of finishing on the initial null
                liveCommunity.removeObserver(observerHolder[0]);

                final String uid = currentUid;
                final String name = currentName != null ? currentName : "Member";
                final String photo = currentPhoto;

                if (community.isPrivate) {
                    repo.sendJoinRequest(communityId, uid, name, photo, null, (success, error) ->
                            runOnUiThread(() -> {
                                Toast.makeText(this,
                                        success ? "Join request sent! Waiting for approval."
                                                : "Failed: " + error,
                                        Toast.LENGTH_LONG).show();
                                finish();
                            }));
                } else {
                    repo.addMember(communityId, uid, name, photo, CommunityRole.MEMBER, (success, error) ->
                            runOnUiThread(() -> {
                                if (success) {
                                    Intent i = new Intent(this, CommunityActivity.class);
                                    i.putExtra(CommunityActivity.EXTRA_COMMUNITY_ID, communityId);
                                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                                    startActivity(i);
                                }
                                finish();
                            }));
                }
            };
            liveCommunity.observeForever(observerHolder[0]);
        });
    }
}
