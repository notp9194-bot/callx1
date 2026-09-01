package com.callx.app.admin;

import android.content.Intent;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.Map;

/**
 * Operations home. Every privileged screen is reachable here so moderators
 * do not need to know Firebase node names or use the Firebase Console.
 */
public class AdminDashboardActivity extends AppCompatActivity {
    private LinearLayout content;
    private TextView stats;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        renderShell();
        loadDashboard();
    }

    private void renderShell() {
        LinearLayout root = AdminUi.screen(this);
        root.addView(AdminUi.toolbar(this, "CallX Admin Control Center"),
            new LinearLayout.LayoutParams(-1, AdminUi.dp(this, 56)));

        android.widget.ScrollView scroll = AdminUi.scroll(this);
        content = AdminUi.column(this);
        scroll.addView(content, new ViewGroup.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        TextView heading = AdminUi.title(this, "Production operations");
        content.addView(heading);
        stats = AdminUi.body(this, "Loading live metrics…");
        content.addView(stats);
        AdminUi.gap(this, content, 8);

        addModule("Users", "Search profiles, suspend/ban/delete, revoke sessions and inspect global blocks.",
            () -> open(AdminUsersActivity.class));
        addModule("Content & reports", "Review users, reels, comments, X, group/channel/community reports and takedowns.",
            () -> open(AdminReportsActivity.class));
        addModule("Groups, channels & communities", "Global oversight, suspend/delete and admin override actions.",
            () -> openMode("orgs"));
        addModule("Payments & trust", "Transactions, disputes, refunds, fraud flags, KYC and failure logs.",
            () -> openMode("payments"));
        addModule("System announcements", "Preview and send an FCM announcement to all users.",
            () -> open(AdminCommunicationsActivity.class));
        addModule("Crashes & audit", "Crash reports, admin actions and moderation history.",
            () -> openMode("audit"));
        addModule("App config & feature flags", "Maintenance mode, kill switches and operational limits.",
            () -> open(AdminConfigActivity.class));
        addModule("Admin-of-admins", "Manage roles and permissions without editing the database by hand.",
            () -> open(AdminAdminsActivity.class));
        addModule("Verification approvals", "Existing verification badge queue.",
            () -> open(AdminVerificationListActivity.class));

        content.addView(AdminUi.button(this, "Sign out", v -> {
            com.google.firebase.auth.FirebaseAuth.getInstance().signOut();
            startActivity(new Intent(this, AdminLoginActivity.class));
            finish();
        }));
        setContentView(root);
    }

    private void addModule(String title, String description, Runnable action) {
        com.google.android.material.card.MaterialCardView card = AdminUi.card(this);
        LinearLayout inside = AdminUi.column(this);
        inside.setPadding(AdminUi.dp(this, 10), AdminUi.dp(this, 6),
            AdminUi.dp(this, 10), AdminUi.dp(this, 6));
        inside.addView(AdminUi.title(this, title));
        inside.addView(AdminUi.label(this, description));
        inside.addView(AdminUi.button(this, "Open", v -> action.run()));
        card.addView(inside);
        content.addView(card);
    }

    private void loadDashboard() {
        AdminApi.call("dashboard", new AdminApi.Callback() {
            @Override public void onSuccess(Object value) {
                Map<String, Object> result = AdminApi.map(value);
                Map<String, Object> metrics = AdminApi.map(result.get("metrics"));
                String role = AdminApi.text(result.get("role"), "admin");
                stats.setText("Role: " + role + "\nUsers: " + AdminApi.number(metrics.get("users"))
                    + "   DAU: " + AdminApi.number(metrics.get("dau"))
                    + "   MAU: " + AdminApi.number(metrics.get("mau"))
                    + "\nOnline: " + AdminApi.number(metrics.get("online"))
                    + "   Active calls: " + AdminApi.number(metrics.get("activeCalls"))
                    + "   Storage bytes: " + AdminApi.number(metrics.get("storageBytes"))
                    + "\nPending reports: " + AdminApi.number(metrics.get("pendingReports"))
                    + "   Pending verification: " + AdminApi.number(metrics.get("pendingVerification"))
                    + "\nLast refreshed: just now");
            }
            @Override public void onError(String message) {
                stats.setText("Metrics unavailable: " + message
                    + "\nControls remain available; check backend deployment.");
            }
        });
    }

    private void open(Class<?> cls) { startActivity(new Intent(this, cls)); }
    private void openMode(String mode) {
        Intent i = new Intent(this, AdminOperationsActivity.class);
        i.putExtra("mode", mode);
        startActivity(i);
    }
}