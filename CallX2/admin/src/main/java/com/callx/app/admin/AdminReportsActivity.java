package com.callx.app.admin;

import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Unified moderation queue for every report-producing feature in CallX. */
public class AdminReportsActivity extends AppCompatActivity {
    private LinearLayout list;
    private TextView summary;
    private android.widget.EditText contentType;
    private android.widget.EditText contentId;
    private android.widget.EditText containerId;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = AdminUi.screen(this);
        root.addView(AdminUi.toolbar(this, "Reports & moderation"),
            new LinearLayout.LayoutParams(-1, AdminUi.dp(this, 56)));
        android.widget.ScrollView scroll = AdminUi.scroll(this);
        list = AdminUi.column(this);
        scroll.addView(list, new ViewGroup.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        list.addView(AdminUi.title(this, "Central review queue"));
        list.addView(AdminUi.label(this,
            "Includes user, reel, comment/reply, X, sound, group, channel and community reports."));
        summary = AdminUi.label(this, "Loading reports…");
        list.addView(summary);
        list.addView(AdminUi.button(this, "Refresh queue", v -> load()));
        list.addView(AdminUi.title(this, "Direct message/media takedown"));
        list.addView(AdminUi.label(this,
            "Use only for a verified policy decision. Types: message, media, reel, x, group, channel, community."));
        contentType = AdminUi.field(this, "Content type");
        contentType.setSingleLine(true);
        contentType.setText("message");
        contentId = AdminUi.field(this, "Content/message ID");
        contentId.setSingleLine(true);
        containerId = AdminUi.field(this, "Container ID (chat/group/reel ID where needed)");
        containerId.setSingleLine(true);
        list.addView(contentType);
        list.addView(contentId);
        list.addView(containerId);
        list.addView(AdminUi.dangerButton(this, "Remove content and audit",
            v -> directTakedown()));
        setContentView(root);
        load();
    }

    private void load() {
        AdminApi.call("listReports", new AdminApi.Callback() {
            @Override public void onSuccess(Object value) {
                Map<String, Object> result = AdminApi.map(value);
                summary.setText("Open reports: " + AdminApi.number(result.get("count"))
                    + " • Last refreshed just now");
                render(result.get("items"));
            }
            @Override public void onError(String message) {
                summary.setText("Queue unavailable: " + message);
            }
        });
    }

    private void render(Object raw) {
        // Keep the header, queue controls and direct-takedown form intact.
        while (list.getChildCount() > 10) list.removeViewAt(10);
        if (!(raw instanceof List) || ((List<?>) raw).isEmpty()) {
            list.addView(AdminUi.label(this, "No open reports."));
            return;
        }
        for (Object item : (List<?>) raw) {
            Map<String, Object> report = AdminApi.map(item);
            com.google.android.material.card.MaterialCardView card = AdminUi.card(this);
            LinearLayout inside = AdminUi.column(this);
            inside.setPadding(AdminUi.dp(this, 10), AdminUi.dp(this, 6),
                AdminUi.dp(this, 10), AdminUi.dp(this, 6));
            String type = AdminApi.text(report.get("type"), "unknown").toUpperCase();
            String target = AdminApi.text(report.get("targetId"),
                AdminApi.text(report.get("reportedUid"), "—"));
            inside.addView(AdminUi.title(this, type + " • " + target));
            inside.addView(AdminUi.body(this,
                "Reason: " + AdminApi.text(report.get("reason"), "—")
                + "\nReporter: " + AdminApi.text(report.get("reporterUid"), "—")
                + "\nStatus: " + AdminApi.text(report.get("status"), "open")
                + "\nReport ID: " + AdminApi.text(report.get("reportId"), "—")));
            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.addView(AdminUi.button(this, "Resolve", v -> updateReport(report, "resolved")));
            actions.addView(AdminUi.button(this, "Dismiss", v -> updateReport(report, "dismissed")));
            if (report.get("targetPath") != null) {
                actions.addView(AdminUi.dangerButton(this, "Takedown target",
                    v -> takedown(report)));
            }
            inside.addView(actions);
            card.addView(inside);
            list.addView(card);
        }
    }

    private void updateReport(Map<String, Object> report, String state) {
        Map<String, Object> p = reportPayload(report);
        p.put("status", state);
        AdminApi.call("moderateReport", p, new AdminApi.Callback() {
            @Override public void onSuccess(Object value) { AdminUi.toast(AdminReportsActivity.this, "Report " + state); load(); }
            @Override public void onError(String error) { AdminUi.toast(AdminReportsActivity.this, error); }
        });
    }

    private void takedown(Map<String, Object> report) {
        AdminUi.confirm(this, "Content takedown",
            "This removes the reported content from its target path and records an audit event.",
            "Remove", () -> {
                Map<String, Object> p = reportPayload(report);
                p.put("removeTarget", true);
                AdminApi.call("moderateReport", p, new AdminApi.Callback() {
                    @Override public void onSuccess(Object value) { AdminUi.toast(AdminReportsActivity.this, "Content removed"); load(); }
                    @Override public void onError(String error) { AdminUi.toast(AdminReportsActivity.this, error); }
                });
            });
    }

    private Map<String, Object> reportPayload(Map<String, Object> report) {
        Map<String, Object> p = new HashMap<>();
        p.put("type", AdminApi.text(report.get("type"), ""));
        p.put("reportId", AdminApi.text(report.get("reportId"), ""));
        p.put("targetId", AdminApi.text(report.get("targetId"), ""));
        p.put("targetPath", AdminApi.text(report.get("targetPath"), ""));
        return p;
    }

    private void directTakedown() {
        String type = contentType.getText().toString().trim().toLowerCase();
        String id = contentId.getText().toString().trim();
        String container = containerId.getText().toString().trim();
        if (type.isEmpty() || id.isEmpty()) {
            AdminUi.toast(this, "Content type and ID are required");
            return;
        }
        AdminUi.confirm(this, "Direct content takedown",
            "Remove " + type + " " + id + "? This cannot be undone from the admin app.",
            "Remove", () -> {
                Map<String, Object> p = new HashMap<>();
                p.put("type", type);
                p.put("contentId", id);
                p.put("containerId", container);
                AdminApi.call("directTakedown", p, new AdminApi.Callback() {
                    @Override public void onSuccess(Object value) {
                        AdminUi.toast(AdminReportsActivity.this, "Content removed and audited");
                        contentId.setText("");
                    }
                    @Override public void onError(String error) { AdminUi.toast(AdminReportsActivity.this, error); }
                });
            });
    }
}