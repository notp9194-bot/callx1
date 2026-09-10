package com.callx.app.admin;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.HashMap;
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
                } else {
                    for (Object raw : payouts) renderPayout(AdminApi.map(raw));
                }
                loadMilestones();
            }
            @Override public void onError(String message) {
                summary.setText("Creator monetization unavailable: " + message
                    + "\nDeploy the updated functions before using this queue.");
                loadMilestones();
            }
        });
    }

    @SuppressWarnings("unchecked")
    private void loadMilestones() {
        AdminApi.call("listMilestoneEarnings", new AdminApi.Callback() {
            @Override public void onSuccess(Object value) {
                Map<String, Object> result = AdminApi.map(value);
                content.addView(AdminUi.title(AdminMonetizationActivity.this,
                    "Milestone earnings"));
                Map<String, Object> config = AdminApi.map(result.get("config"));
                List<?> levels = config.get("levels") instanceof List
                    ? (List<?>) config.get("levels") : new ArrayList<>();
                List<?> users = result.get("users") instanceof List
                    ? (List<?>) result.get("users") : new ArrayList<>();
                List<?> payouts = result.get("payouts") instanceof List
                    ? (List<?>) result.get("payouts") : new ArrayList<>();
                content.addView(AdminUi.body(AdminMonetizationActivity.this,
                    "Feature: " + (Boolean.FALSE.equals(config.get("enabled"))
                        ? "PAUSED" : "ACTIVE")
                        + "\nAccounts with milestone activity: " + users.size()
                        + "\nWithdrawal requests: " + payouts.size()
                        + "\nRules and rewards are server-controlled."));
                content.addView(AdminUi.button(AdminMonetizationActivity.this,
                    "Edit milestone rules", v -> openMilestoneEditor(config)));
                if (payouts.isEmpty()) {
                    content.addView(AdminUi.cardText(AdminMonetizationActivity.this,
                        "No milestone withdrawal requests yet."));
                } else {
                    for (Object raw : payouts) renderMilestonePayout(AdminApi.map(raw));
                }
                if (!levels.isEmpty()) {
                    StringBuilder rules = new StringBuilder("Current levels:\n");
                    for (Object raw : levels) {
                        Map<String, Object> level = AdminApi.map(raw);
                        rules.append("L").append(AdminApi.number(level.get("level")))
                            .append("  ")
                            .append(AdminApi.number(level.get("likes"))).append(" likes • ")
                            .append(AdminApi.number(level.get("following"))).append(" following • ")
                            .append(AdminApi.number(level.get("shares"))).append(" WhatsApp shares • ₹")
                            .append(AdminApi.number(level.get("rewardPaise")) / 100.0).append("\n");
                    }
                    content.addView(AdminUi.cardText(AdminMonetizationActivity.this, rules.toString()));
                }
            }
            @Override public void onError(String message) {
                content.addView(AdminUi.cardText(AdminMonetizationActivity.this,
                    "Milestone earnings unavailable: " + message));
            }
        });
    }

    private void renderMilestonePayout(Map<String, Object> row) {
        com.google.android.material.card.MaterialCardView card = AdminUi.card(this);
        LinearLayout inside = AdminUi.column(this);
        inside.setPadding(AdminUi.dp(this, 10), AdminUi.dp(this, 8),
            AdminUi.dp(this, 10), AdminUi.dp(this, 8));
        String status = AdminApi.text(row.get("status"), "pending");
        inside.addView(AdminUi.title(this, AdminUi.money(row.get("amountPaise"))
            + " • " + status.toUpperCase(Locale.US)));
        inside.addView(AdminUi.body(this, "Creator UID: " + AdminApi.text(row.get("uid"), "—")
            + "\nPayout ID: " + AdminApi.text(row.get("id"), "—")));
        if (!"paid".equals(status) && !"rejected".equals(status)) {
            inside.addView(AdminUi.button(this, "Approve milestone withdrawal",
                v -> reviewMilestonePayout(row, "approve")));
            inside.addView(AdminUi.button(this, "Mark milestone withdrawal paid",
                v -> reviewMilestonePayout(row, "paid")));
            inside.addView(AdminUi.dangerButton(this, "Reject and restore balance",
                v -> reviewMilestonePayout(row, "reject")));
        }
        card.addView(inside);
        content.addView(card);
    }

    private void reviewMilestonePayout(Map<String, Object> row, String operation) {
        AdminUi.confirm(this, "Update milestone withdrawal",
            "This writes an audited status of " + operation + " for the creator withdrawal.",
            "Continue", () -> {
                Map<String, Object> payload = new HashMap<>();
                payload.put("uid", AdminApi.text(row.get("uid"), ""));
                payload.put("payoutId", AdminApi.text(row.get("id"), ""));
                payload.put("operation", operation);
                AdminApi.call("reviewMilestonePayout", payload, new AdminApi.Callback() {
                    @Override public void onSuccess(Object value) {
                        AdminUi.toast(AdminMonetizationActivity.this, "Milestone withdrawal updated");
                        recreate();
                    }
                    @Override public void onError(String error) {
                        AdminUi.toast(AdminMonetizationActivity.this, error);
                    }
                });
            });
    }

    private void openMilestoneEditor(Map<String, Object> config) {
        EditText editor = AdminUi.field(this,
            "level: likes: following: shares: rupees");
        editor.setSingleLine(false);
        StringBuilder initial = new StringBuilder();
        Object rawLevels = config.get("levels");
        if (rawLevels instanceof List) {
            for (Object raw : (List<?>) rawLevels) {
                Map<String, Object> level = AdminApi.map(raw);
                initial.append(AdminApi.number(level.get("level"))).append(":")
                    .append(AdminApi.number(level.get("likes"))).append(":")
                    .append(AdminApi.number(level.get("following"))).append(":")
                    .append(AdminApi.number(level.get("shares"))).append(":")
                    .append(AdminApi.number(level.get("rewardPaise")) / 100).append("\n");
            }
        }
        editor.setText(initial.toString().trim());
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Milestone rules")
            .setMessage("One line per level: level:likes:following:shares:rupees")
            .setView(editor)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", (dialog, which) -> {
                List<Map<String, Object>> levels = new ArrayList<>();
                String[] lines = editor.getText().toString().split("\\n");
                try {
                    for (String line : lines) {
                        String[] parts = line.trim().split(":");
                        if (parts.length != 5) throw new IllegalArgumentException();
                        Map<String, Object> level = new HashMap<>();
                        level.put("level", Long.parseLong(parts[0].trim()));
                        level.put("likes", Long.parseLong(parts[1].trim()));
                        level.put("following", Long.parseLong(parts[2].trim()));
                        level.put("shares", Long.parseLong(parts[3].trim()));
                        level.put("rewardPaise", Long.parseLong(parts[4].trim()) * 100L);
                        levels.add(level);
                    }
                } catch (Exception e) {
                    AdminUi.toast(this, "Invalid rules format");
                    return;
                }
                Map<String, Object> nextConfig = new HashMap<>();
                nextConfig.put("enabled", !Boolean.FALSE.equals(config.get("enabled")));
                nextConfig.put("levels", levels);
                Map<String, Object> payload = new HashMap<>();
                payload.put("config", nextConfig);
                AdminApi.call("updateMilestoneConfig", payload, new AdminApi.Callback() {
                    @Override public void onSuccess(Object value) {
                        AdminUi.toast(AdminMonetizationActivity.this, "Milestone rules saved");
                        recreate();
                    }
                    @Override public void onError(String error) {
                        AdminUi.toast(AdminMonetizationActivity.this, error);
                    }
                });
            }).show();
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