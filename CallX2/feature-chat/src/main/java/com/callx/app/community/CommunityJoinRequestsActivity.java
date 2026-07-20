package com.callx.app.community;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.chat.R;
import com.callx.app.community.canvas.CommunityAvatarPreloader;
import com.callx.app.community.canvas.CommunityScrollOptimizer;
import com.callx.app.db.entity.CommunityJoinRequestEntity;
import com.callx.app.repository.CommunityRepository;
import com.google.firebase.auth.FirebaseAuth;

/**
 * v31: Join Requests screen — admin/owner sees pending requests and can approve or reject.
 * Firebase: communities/{communityId}/join_requests/{requestId}
 */
public class CommunityJoinRequestsActivity extends AppCompatActivity
        implements CommunityJoinRequestAdapter.Listener {

    public static final String EXTRA_COMMUNITY_ID = "communityId";

    private String communityId;
    private String currentUid;

    private RecyclerView rvRequests;
    private View layoutEmpty;
    private CommunityJoinRequestAdapter adapter;
    private CommunityRepository repo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_community_join_requests);

        communityId = getIntent().getStringExtra(EXTRA_COMMUNITY_ID);
        currentUid = FirebaseAuth.getInstance().getCurrentUser() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;
        repo = CommunityRepository.getInstance(this);

        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle("Join Requests");
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        rvRequests  = findViewById(R.id.rv_join_requests);
        layoutEmpty = findViewById(R.id.layout_empty_requests);

        LinearLayoutManager llm = new LinearLayoutManager(this);
        rvRequests.setLayoutManager(llm);
        rvRequests.setHasFixedSize(false);
        rvRequests.setItemAnimator(null);
        CommunityScrollOptimizer.apply(rvRequests, llm);
        CommunityScrollOptimizer.applySharedPool(rvRequests);

        adapter = new CommunityJoinRequestAdapter(this);
        rvRequests.setAdapter(adapter);
        CommunityAvatarPreloader.attachAvatar(this, rvRequests,
                new CommunityAvatarPreloader.UrlProvider() {
                    @Override public String urlAt(int pos) {
                        java.util.List<com.callx.app.db.entity.CommunityJoinRequestEntity> list =
                                adapter.getCurrentList();
                        return (pos >= 0 && pos < list.size()) ? list.get(pos).requesterPhoto : null;
                    }
                    @Override public int count() { return adapter.getItemCount(); }
                }, 44);

        if (communityId != null) {
            repo.observePendingJoinRequests(communityId).observe(this, requests -> {
                adapter.submitList(requests);
                boolean empty = requests == null || requests.isEmpty();
                rvRequests.setVisibility(empty ? View.GONE : View.VISIBLE);
                layoutEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
            });
        }
    }

    @Override
    public void onApprove(CommunityJoinRequestEntity request) {
        if (currentUid == null) return;
        boolean isGroupRequest = request.groupId != null;
        String message = isGroupRequest
                ? "Allow " + request.requesterName + " to join this group?"
                : "Allow " + request.requesterName + " to join the community?";
        new AlertDialog.Builder(this)
                .setTitle("Approve Request")
                .setMessage(message)
                .setPositiveButton("Approve", (d, w) ->
                        repo.approveJoinRequest(communityId, request.id, request.groupId,
                                request.requesterUid, request.requesterName, request.requesterPhoto,
                                currentUid,
                                (success, error) -> runOnUiThread(() -> {
                                    if (!success)
                                        Toast.makeText(this, "Failed: " + error, Toast.LENGTH_SHORT).show();
                                })))
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public void onReject(CommunityJoinRequestEntity request) {
        if (currentUid == null) return;
        new AlertDialog.Builder(this)
                .setTitle("Reject Request")
                .setMessage("Reject " + request.requesterName + "'s request to join?")
                .setPositiveButton("Reject", (d, w) ->
                        repo.rejectJoinRequest(communityId, request.id, currentUid,
                                (success, error) -> runOnUiThread(() -> {
                                    if (!success)
                                        Toast.makeText(this, "Failed: " + error, Toast.LENGTH_SHORT).show();
                                })))
                .setNegativeButton("Cancel", null)
                .show();
    }
}
