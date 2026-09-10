package com.callx.app.admin;

import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin review queue for Star Talent applications. The callable backend
 * applies the approved tier to users/{uid}; the admin client only sends a
 * reviewed decision.
 */
public class AdminStarTalentActivity extends AppCompatActivity {
    private LinearLayout content;
    private TextView summary;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        renderShell();
        load();
    }

    private void renderShell() {
        LinearLayout root = AdminUi.screen(this);
        root.addView(AdminUi.toolbar(this, "Star Talent applications"),
            new LinearLayout.LayoutParams(-1, AdminUi.dp(this, 56)));
        android.widget.ScrollView scroll = AdminUi.scroll(this);
        content = AdminUi.column(this);
        scroll.addView(content, new ViewGroup.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        content.addView(AdminUi.title(this, "Creator program review"));
        summary = AdminUi.body(this, "Loading applications…");
        content.addView(summary);
        setContentView(root);
    }

    @SuppressWarnings("unchecked")
    private void load() {
        AdminApi.call("listStarTalentApplications", new AdminApi.Callback() {
            @Override public void onSuccess(Object value) {
                Map<String, Object> result = AdminApi.map(value);
                List<?> items = result.get("items") instanceof List
                    ? (List<?>) result.get("items") : new ArrayList<>();
                summary.setText("Pending applications: " + items.size()
                    + "\nApprove a tier only after reviewing the creator profile and application.");
                if (items.isEmpty()) {
                    content.addView(AdminUi.cardText(AdminStarTalentActivity.this,
                        "No pending Star Talent applications."));
                } else {
                    for (Object raw : items) renderApplication(AdminApi.map(raw));
                }
            }
            @Override public void onError(String message) {
                summary.setText("Could not load applications: " + message);
            }
        });
    }

    private void renderApplication(Map<String, Object> item) {
        com.google.android.material.card.MaterialCardView card = AdminUi.card(this);
        LinearLayout inside = AdminUi.column(this);
        inside.setPadding(AdminUi.dp(this, 10), AdminUi.dp(this, 8),
            AdminUi.dp(this, 10), AdminUi.dp(this, 8));
        String tier = AdminApi.text(item.get("tierName"),
            AdminApi.text(item.get("tierKey"), "Star Talent"));
        inside.addView(AdminUi.title(this, tier));
        inside.addView(AdminUi.body(this,
            "Creator: " + AdminApi.text(item.get("name"), "—")
                + "\nUID: " + AdminApi.text(item.get("uid"), "—")
                + "\nCategory: " + AdminApi.text(item.get("category"), "—")
                + "\nSubmitted: " + AdminApi.text(item.get("submittedAt"), "—")
                + "\n\n" + AdminApi.text(item.get("reason"), "No reason supplied")));
        inside.addView(AdminUi.button(this, "Approve application",
            v -> review(item, "approve")));
        inside.addView(AdminUi.dangerButton(this, "Reject application",
            v -> review(item, "reject")));
        card.addView(inside);
        content.addView(card);
    }

    private void review(Map<String, Object> item, String decision) {
        String title = "approve".equals(decision) ? "Approve Star Talent" : "Reject Star Talent";
        String message = "This will " + ("approve".equals(decision)
            ? "activate the selected talent tier on the creator profile."
            : "close the application without activating a talent tier.");
        AdminUi.confirm(this, title, message, "Continue", () -> {
            Map<String, Object> payload = new HashMap<>();
            payload.put("uid", AdminApi.text(item.get("uid"), ""));
            payload.put("decision", decision);
            AdminApi.call("reviewStarTalentApplication", payload, new AdminApi.Callback() {
                @Override public void onSuccess(Object data) {
                    AdminUi.toast(AdminStarTalentActivity.this,
                        "Application " + ("approve".equals(decision) ? "approved" : "rejected"));
                    recreate();
                }
                @Override public void onError(String error) {
                    AdminUi.toast(AdminStarTalentActivity.this, error);
                }
            });
        });
    }
}