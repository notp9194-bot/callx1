package com.callx.app.admin;

import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.callx.app.admin.databinding.ActivityAdminVerificationListBinding;
import com.callx.app.admin.model.VerificationRequest;
import com.callx.app.corelite.FirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.Query;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lists every verification_requests/{uid} node with status == "pending" and
 * lets the signed-in admin approve (sets users/{uid}/isVerified = true) or
 * reject (marks the request "rejected") each one. Reached only after
 * {@link AdminLoginActivity} has confirmed the signed-in user is on the
 * admins/{uid} allowlist.
 */
public class AdminVerificationListActivity extends AppCompatActivity {

    private ActivityAdminVerificationListBinding binding;
    private VerificationRequestAdapter adapter;
    private ValueEventListener pendingListener;
    private Query pendingQuery;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityAdminVerificationListBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setSupportActionBar(binding.toolbarAdmin);

        adapter = new VerificationRequestAdapter(new VerificationRequestAdapter.Listener() {
            @Override public void onApprove(VerificationRequest request) { decide(request, true); }
            @Override public void onReject(VerificationRequest request)  { decide(request, false); }
        });
        binding.rvVerificationRequests.setLayoutManager(new LinearLayoutManager(this));
        binding.rvVerificationRequests.setAdapter(adapter);

        listenForPendingRequests();
    }

    private void listenForPendingRequests() {
        pendingQuery = FirebaseUtils.getVerificationRequestsRef()
            .orderByChild("status")
            .equalTo(FirebaseUtils.STATUS_PENDING);

        pendingListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) {
                List<VerificationRequest> list = new ArrayList<>();
                for (DataSnapshot child : snapshot.getChildren()) {
                    VerificationRequest req = child.getValue(VerificationRequest.class);
                    if (req != null) list.add(req);
                }
                adapter.submitList(list);
                binding.tvEmptyState.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
            }
            @Override public void onCancelled(DatabaseError error) {
                Toast.makeText(AdminVerificationListActivity.this,
                    "Failed to load requests: " + error.getMessage(), Toast.LENGTH_LONG).show();
            }
        };
        pendingQuery.addValueEventListener(pendingListener);
    }

    /** Approve sets users/{uid}/isVerified = true; reject just flips status.
     *  Both also stamp status/reviewedAt/reviewedBy on the request itself so
     *  there's a record of who decided and when. */
    private void decide(VerificationRequest request, boolean approve) {
        String adminUid = FirebaseUtils.getCurrentUid();
        Map<String, Object> update = new HashMap<>();
        update.put("status", approve ? FirebaseUtils.STATUS_APPROVED : FirebaseUtils.STATUS_REJECTED);
        update.put("reviewedAt", com.google.firebase.database.ServerValue.TIMESTAMP);
        update.put("reviewedBy", adminUid);

        FirebaseUtils.getVerificationRequestRef(request.uid).updateChildren(update)
            .addOnFailureListener(e -> Toast.makeText(this, "Failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());

        if (approve) {
            FirebaseUtils.getIsVerifiedRef(request.uid).setValue(true)
                .addOnSuccessListener(unused -> Toast.makeText(this, "Approved", Toast.LENGTH_SHORT).show())
                .addOnFailureListener(e -> Toast.makeText(this, "Failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
        } else {
            Toast.makeText(this, "Rejected", Toast.LENGTH_SHORT).show();
        }
        // No manual list refresh needed — the "status" query above stops
        // matching this node the instant it flips away from "pending", so
        // the live ValueEventListener drops it from the list on its own.
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (pendingQuery != null && pendingListener != null) pendingQuery.removeEventListener(pendingListener);
    }
}
