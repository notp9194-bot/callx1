package com.callx.app.admin;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Finance/moderation view for the Reels creator monetization queue.
 * All reads and payout state changes go through adminAction.
 */
public class AdminMonetizationActivity extends AppCompatActivity {
    private LinearLayout content;
    private TextView summary;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        renderShell();
        load();
    }

    private void renderShell() {
        LinearLayout root = AdminUi.screen(this);
        root.addView(AdminUi.toolbar(this, "Creator monetization"),
            new LinearLayout.LayoutParams(-1, AdminUi.dp(this, 56)));
        android.widget.ScrollView scroll = AdminUi.scroll(this);
        content = AdminUi.column(this);
        scroll.addView(content, new ViewGroup.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        content.addView(AdminUi.title(this, "Reels earnings & payouts"));
        summary = AdminUi.body(this, "Loading creator balances…");
        content.addView(summary);
        setContentView(root);
    }

    @SuppressWarnings("unchecked")
    private void load() {
        AdminApi.call("listCreatorPayouts", new AdminApi.Callback() {
            @Override public void onSuccess(Object value) {
                Map<String, Object> result = AdminApi.map(value);
                List<?> creators = result.get("creators") instanceof List
                    ? (List<?>) result.get("creators") : new ArrayList<>();
                List<?> payouts = result.get("payouts") instanceof List
                    ? (List<?>) result.get("payouts") : new ArrayList<>();
                long pendingCents = 0;
                for (Object raw : payouts) {
                    Map<String, Object> row = AdminApi.map(raw);
                    if ("pending".equals(AdminApi.text(row.get("status"), "pending"))) {
                        pendingCents += AdminApi.number(row.get("amountCents"));
                    }
                }
                summary.setText("Creators: " + creators.size()
                    + "\nPayout requests: " + payouts.size()
                    + "\nPending review: " + money(pendingCents)
                    + "\nOnly finance and super-admin roles can change payout status.");
                if (payouts.isEmpty()) {
                    content.addView(AdminUi.cardText(AdminMonetizationActivity.this,
                        "No payout requests yet. Approved creator earnings will appear here."));
                    return;
                }
                for (Object raw : payouts) renderPayout(AdminApi.map(raw));
            }
            @Override public void onError(String message) {
                summary.setText("Creator monetization unavailable: " + message
                    + "\nDeploy the updated functions before using this queue.");
            }
        });
    }

    private void renderPayout(Map<String, Object> row) {
        com.google.android.material.card.MaterialCardView card = AdminUi.card(this);
        LinearLayout inside = AdminUi.column(this);
        inside.setPadding(AdminUi.dp(this, 10), AdminUi.dp(this, 8),
            AdminUi.dp(this, 10), AdminUi.dp(this, 8));
        String status = AdminApi.text(row.get("status"), "pending");
        String uid = AdminApi.text(row.get("uid"), "—");
        String payoutId = AdminApi.text(row.get("id"), "—");
        inside.addView(AdminUi.title(this, money(AdminApi.number(row.get("amountCents")))
            + " • " + status.toUpperCase(Locale.US)));
        inside.addView(AdminUi.body(this, "Creator UID: " + uid
            + "\nPayout ID: " + payoutId
            + "\nRequested: " + AdminApi.text(row.get("requestedAt"), "—")
            + "\nTalent plan: " + AdminApi.text(row.get("talentPlan"), "not supplied")));
        if (!"paid".equals(status) && !"rejected".equals(status)) {
            inside.addView(AdminUi.button(this, "Approve payout",
                v -> review(row, "approve")));
            inside.addView(AdminUi.button(this, "Mark as paid",
                v -> review(row, "paid")));
            inside.addView(AdminUi.dangerButton(this, "Reject and restore balance",
                v -> review(row, "reject")));
        }
        card.addView(inside);
        content.addView(card);
    }

    private void review(Map<String, Object> row, String operation) {
        String title = "approve".equals(operation) ? "Approve payout"
            : "paid".equals(operation) ? "Mark payout as paid" : "Reject payout";
        String message = "This will " + ("reject".equals(operation)
            ? "reject the request and restore the creator balance."
            : "write an audited payout status of " + operation + ".");
        AdminUi.confirm(this, title, message, "Continue",
            () -> {
                Map<String, Object> payload = new java.util.HashMap<>();
                payload.put("uid", AdminApi.text(row.get("uid"), ""));
                payload.put("payoutId", AdminApi.text(row.get("id"), ""));
                payload.put("operation", operation);
                AdminApi.call("reviewCreatorPayout", payload, new AdminApi.Callback() {
                    @Override public void onSuccess(Object value) {
                        AdminUi.toast(AdminMonetizationActivity.this, "Payout updated");
                        recreate();
                    }
                    @Override public void onError(String error) {
                        AdminUi.toast(AdminMonetizationActivity.this, error);
                    }
                });
            });
    }

    private static String money(long cents) {
        return String.format(Locale.US, "$%.2f", cents / 100.0);
    }
}