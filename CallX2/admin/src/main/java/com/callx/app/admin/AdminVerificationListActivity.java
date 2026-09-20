package com.callx.app.admin;

import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
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
import java.util.List;

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
        binding.btnManagePrices.setOnClickListener(v -> showPriceEditor());
        binding.btnBackfillBadges.setOnClickListener(v -> backfillLegacyBadges());

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

    /** All privileged verification writes go through adminAction. */
    private void decide(VerificationRequest request, boolean approve) {
        java.util.Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("uid", request.uid);
        payload.put("decision", approve ? "approve" : "reject");
        AdminApi.call("reviewVerificationRequest", payload, new AdminApi.Callback() {
            @Override public void onSuccess(Object data) {
                Toast.makeText(AdminVerificationListActivity.this,
                    approve ? "Approved" : "Rejected", Toast.LENGTH_SHORT).show();
            }
            @Override public void onError(String message) {
                Toast.makeText(AdminVerificationListActivity.this,
                    "Failed: " + message, Toast.LENGTH_LONG).show();
            }
        });
    }

    @SuppressWarnings("unchecked")
    private void showPriceEditor() {
        AdminApi.call("getVerificationCatalog", new AdminApi.Callback() {
            @Override public void onSuccess(Object raw) {
                java.util.Map<String, Object> data = AdminApi.map(raw);
                Object value = data.get("plans");
                if (!(value instanceof java.util.List)) {
                    Toast.makeText(AdminVerificationListActivity.this,
                        "Catalog not available", Toast.LENGTH_LONG).show();
                    return;
                }
                LinearLayout form = new LinearLayout(AdminVerificationListActivity.this);
                form.setOrientation(LinearLayout.VERTICAL);
                int pad = (int) (20 * getResources().getDisplayMetrics().density);
                form.setPadding(pad, 0, pad, 0);
                java.util.Map<String, EditText> inputs = new java.util.LinkedHashMap<>();
                for (Object item : (java.util.List<?>) value) {
                    java.util.Map<String, Object> plan = AdminApi.map(item);
                    String key = AdminApi.text(plan.get("key"), "");
                    String label = AdminApi.text(plan.get("tierName"), "")
                        + " · " + AdminApi.text(plan.get("period"), "");
                    EditText input = new EditText(AdminVerificationListActivity.this);
                    input.setHint(label + " (₹)");
                    input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
                    input.setText(AdminApi.text(plan.get("priceRupees"), ""));
                    form.addView(input, new LinearLayout.LayoutParams(-1, dp(54)));
                    inputs.put(key, input);
                }
                new AlertDialog.Builder(AdminVerificationListActivity.this)
                    .setTitle("Verification prices (INR)")
                    .setView(form)
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Save", (dialog, which) -> {
                        java.util.Map<String, Object> prices = new java.util.HashMap<>();
                        try {
                            for (java.util.Map.Entry<String, EditText> entry : inputs.entrySet()) {
                                int price = Integer.parseInt(entry.getValue().getText().toString().trim());
                                if (price < 1 || price > 100000) throw new NumberFormatException();
                                prices.put(entry.getKey(), price);
                            }
                        } catch (NumberFormatException e) {
                            Toast.makeText(AdminVerificationListActivity.this,
                                "Use whole rupee prices from ₹1 to ₹100000", Toast.LENGTH_LONG).show();
                            return;
                        }
                        java.util.Map<String, Object> payload = new java.util.HashMap<>();
                        payload.put("prices", prices);
                        AdminApi.call("updateVerificationCatalog", payload, new AdminApi.Callback() {
                            @Override public void onSuccess(Object data) {
                                Toast.makeText(AdminVerificationListActivity.this,
                                    "Prices updated", Toast.LENGTH_SHORT).show();
                            }
                            @Override public void onError(String message) {
                                Toast.makeText(AdminVerificationListActivity.this,
                                    "Failed: " + message, Toast.LENGTH_LONG).show();
                            }
                        });
                    }).show();
            }
            @Override public void onError(String message) {
                Toast.makeText(AdminVerificationListActivity.this,
                    "Failed: " + message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void backfillLegacyBadges() {
        new AlertDialog.Builder(this)
            .setTitle("Backfill legacy talent badges?")
            .setMessage("This copies existing approved Star/Gold/Platinum talent fields into the new badge fields. Existing users are not downgraded.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Backfill", (dialog, which) ->
                AdminApi.call("backfillVerificationBadges", new AdminApi.Callback() {
                    @Override public void onSuccess(Object data) {
                        Toast.makeText(AdminVerificationListActivity.this,
                            "Legacy badge fields backfilled", Toast.LENGTH_LONG).show();
                    }
                    @Override public void onError(String message) {
                        Toast.makeText(AdminVerificationListActivity.this,
                            "Failed: " + message, Toast.LENGTH_LONG).show();
                    }
                })).show();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (pendingQuery != null && pendingListener != null) pendingQuery.removeEventListener(pendingListener);
    }
}
