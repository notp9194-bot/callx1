package com.callx.app.admin;

import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.appcompat.app.AppCompatActivity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Shared operational views for organisations, payment trust and audit. */
public class AdminOperationsActivity extends AppCompatActivity {
    private String mode;
    private LinearLayout list;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        mode = getIntent().getStringExtra("mode");
        if (mode == null) mode = "audit";
        LinearLayout root = AdminUi.screen(this);
        String title = "orgs".equals(mode) ? "Groups, channels & communities"
            : "payments".equals(mode) ? "Payments & trust" : "Audit & crash center";
        root.addView(AdminUi.toolbar(this, title),
            new LinearLayout.LayoutParams(-1, AdminUi.dp(this, 56)));
        android.widget.ScrollView scroll = AdminUi.scroll(this);
        list = AdminUi.column(this);
        scroll.addView(list, new ViewGroup.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        list.addView(AdminUi.title(this, title));
        list.addView(AdminUi.button(this, "Refresh", v -> load()));
        setContentView(root);
        load();
    }

    private void load() {
        String action = "orgs".equals(mode) ? "listOrganizations"
            : "payments".equals(mode) ? "listPayments" : "listAudit";
        AdminApi.call(action, new AdminApi.Callback() {
            @Override public void onSuccess(Object value) { render(AdminApi.map(value)); }
            @Override public void onError(String error) { list.addView(AdminUi.label(AdminOperationsActivity.this, error)); }
        });
    }

    private void render(Map<String, Object> result) {
        while (list.getChildCount() > 2) list.removeViewAt(2);
        Object raw = result.get("items");
        if (!(raw instanceof List) || ((List<?>) raw).isEmpty()) {
            list.addView(AdminUi.label(this, "No records found. Backend indexes are empty or not deployed yet."));
            return;
        }
        for (Object item : (List<?>) raw) {
            Map<String, Object> row = AdminApi.map(item);
            com.google.android.material.card.MaterialCardView card = AdminUi.card(this);
            LinearLayout inside = AdminUi.column(this);
            inside.setPadding(AdminUi.dp(this, 10), AdminUi.dp(this, 6),
                AdminUi.dp(this, 10), AdminUi.dp(this, 6));
            if ("orgs".equals(mode)) renderOrg(inside, row);
            else if ("payments".equals(mode)) renderPayment(inside, row);
            else renderAudit(inside, row);
            card.addView(inside);
            list.addView(card);
        }
    }

    private void renderOrg(LinearLayout inside, Map<String, Object> row) {
        String type = AdminApi.text(row.get("type"), "organisation");
        String id = AdminApi.text(row.get("id"), AdminApi.text(row.get("orgId"), "—"));
        inside.addView(AdminUi.title(this, type + " • " + id));
        inside.addView(AdminUi.body(this, "Name: " + AdminApi.text(row.get("name"), "—")
            + "\nOwner: " + AdminApi.text(row.get("ownerUid"), "—")
            + "\nStatus: " + AdminApi.text(row.get("status"), "active")
            + "\nMembers: " + AdminApi.number(row.get("memberCount"))));
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.addView(AdminUi.button(this, "Suspend / restore",
            v -> organisationAction(row, "toggleSuspended")));
        actions.addView(AdminUi.dangerButton(this, "Delete organisation",
            v -> AdminUi.confirm(this, "Delete organisation",
                "This removes the organisation node and writes an audit record.", "Delete",
                () -> organisationAction(row, "delete"))));
        inside.addView(actions);
    }

    private void renderPayment(LinearLayout inside, Map<String, Object> row) {
        String id = AdminApi.text(row.get("id"), AdminApi.text(row.get("transactionId"), "—"));
        String recordType = AdminApi.text(row.get("recordType"), "transaction");
        inside.addView(AdminUi.title(this, recordType + " • " + id));
        inside.addView(AdminUi.body(this, "Owner: " + AdminApi.text(row.get("ownerUid"), "—")
            + "\nCounterparty: " + AdminApi.text(row.get("counterpartyUid"), "—")
            + "\nAmount: " + AdminUi.money(row.get("amountPaise"))
            + "\nStatus: " + AdminApi.text(row.get("status"), "—")
            + "\nFailure: " + AdminApi.text(row.get("failureReason"), "—")
            + "\nKYC: " + AdminApi.text(row.get("kycStatus"), "not reviewed")
            + "\nFraud: " + AdminApi.text(row.get("fraudStatus"), "clear")));
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.addView(AdminUi.button(this, "Open dispute", v -> paymentAction(row, "dispute")));
        actions.addView(AdminUi.button(this, "Mark fraud / clear flag", v -> paymentAction(row, "fraud")));
        actions.addView(AdminUi.button(this, "Review KYC", v -> paymentAction(row, "kyc_approved")));
        actions.addView(AdminUi.dangerButton(this, "Refund ledger transaction",
            v -> AdminUi.confirm(this, "Refund transaction",
                "This records a refund request. A real gateway refund requires a gateway adapter.", "Refund",
                () -> paymentAction(row, "refund"))));
        inside.addView(actions);
    }

    private void renderAudit(LinearLayout inside, Map<String, Object> row) {
        inside.addView(AdminUi.title(this, AdminApi.text(row.get("action"), "Event")));
        inside.addView(AdminUi.body(this, "Admin: " + AdminApi.text(row.get("adminUid"), "—")
            + "\nTarget: " + AdminApi.text(row.get("targetId"), "—")
            + "\nType: " + AdminApi.text(row.get("targetType"), "—")
            + "\nTime: " + AdminApi.text(row.get("createdAt"), "—")
            + "\nDetails: " + AdminApi.text(row.get("details"), "—")));
    }

    private void organisationAction(Map<String, Object> row, String operation) {
        Map<String, Object> p = new HashMap<>();
        p.put("type", AdminApi.text(row.get("type"), ""));
        p.put("id", AdminApi.text(row.get("id"), AdminApi.text(row.get("orgId"), "")));
        p.put("operation", operation);
        p.put("currentStatus", AdminApi.text(row.get("status"), "active"));
        AdminApi.call("organizationAction", p, new AdminApi.Callback() {
            @Override public void onSuccess(Object value) { AdminUi.toast(AdminOperationsActivity.this, "Organization updated"); load(); }
            @Override public void onError(String error) { AdminUi.toast(AdminOperationsActivity.this, error); }
        });
    }

    private void paymentAction(Map<String, Object> row, String operation) {
        Map<String, Object> p = new HashMap<>();
        p.put("transactionId", AdminApi.text(row.get("id"), AdminApi.text(row.get("transactionId"), "")));
        p.put("operation", operation);
        AdminApi.call("paymentReview", p, new AdminApi.Callback() {
            @Override public void onSuccess(Object value) { AdminUi.toast(AdminOperationsActivity.this, "Payment case recorded"); load(); }
            @Override public void onError(String error) { AdminUi.toast(AdminOperationsActivity.this, error); }
        });
    }
}