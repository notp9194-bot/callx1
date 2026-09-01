package com.callx.app.admin;

import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** User lookup and lifecycle controls. */
public class AdminUsersActivity extends AppCompatActivity {
    private LinearLayout list;
    private android.widget.EditText uidField;
    private TextView status;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = AdminUi.screen(this);
        root.addView(AdminUi.toolbar(this, "User management"),
            new LinearLayout.LayoutParams(-1, AdminUi.dp(this, 56)));
        android.widget.ScrollView scroll = AdminUi.scroll(this);
        list = AdminUi.column(this);
        scroll.addView(list, new ViewGroup.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        list.addView(AdminUi.title(this, "Search by Firebase UID"));
        uidField = AdminUi.field(this, "Paste a user UID");
        uidField.setSingleLine(true);
        list.addView(uidField);
        list.addView(AdminUi.button(this, "Lookup profile", v -> lookup()));
        list.addView(AdminUi.button(this, "View all blocked users", v -> blocked()));
        status = AdminUi.label(this, "No user selected.");
        list.addView(status);
        setContentView(root);
    }

    private void lookup() {
        String uid = uidField.getText().toString().trim();
        if (uid.isEmpty()) { AdminUi.toast(this, "Enter a UID first"); return; }
        Map<String, Object> p = new HashMap<>();
        p.put("uid", uid);
        status.setText("Loading…");
        AdminApi.call("lookupUser", p, new AdminApi.Callback() {
            @Override public void onSuccess(Object value) { renderUser(uid, AdminApi.map(value)); }
            @Override public void onError(String message) { status.setText("Lookup failed: " + message); }
        });
    }

    private void renderUser(String uid, Map<String, Object> result) {
        list.removeViews(5, Math.max(0, list.getChildCount() - 5));
        if (result.isEmpty() || result.get("user") == null) {
            status.setText("No profile found for " + uid);
            return;
        }
        Map<String, Object> user = AdminApi.map(result.get("user"));
        status.setText("Profile loaded");
        com.google.android.material.card.MaterialCardView card = AdminUi.card(this);
        LinearLayout inside = AdminUi.column(this);
        inside.setPadding(AdminUi.dp(this, 10), AdminUi.dp(this, 6),
            AdminUi.dp(this, 10), AdminUi.dp(this, 6));
        String name = AdminApi.text(user.get("name"), AdminApi.text(user.get("displayName"), "Unnamed"));
        inside.addView(AdminUi.title(this, name));
        inside.addView(AdminUi.body(this, "UID: " + uid
            + "\nEmail: " + AdminApi.text(user.get("email"), "—")
            + "\nCallX ID: " + AdminApi.text(user.get("callxId"), "—")
            + "\nStatus: " + AdminApi.text(user.get("accountStatus"), "active")
            + "\nCreated: " + AdminApi.text(user.get("createdAt"), "—")));
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        addAction(actions, "Activate user", "active", uid);
        addAction(actions, "Suspend user", "suspended", uid);
        addAction(actions, "Ban user", "banned", uid);
        actions.addView(AdminUi.button(this, "Force logout / kill sessions",
            v -> confirmAction("Revoke every refresh token for this account?", "forceLogout", uid)));
        actions.addView(AdminUi.dangerButton(this, "Delete user account",
            v -> confirmAction("This deletes the Firebase Auth account and primary user profile. This cannot be undone.",
                "deleteUser", uid)));
        inside.addView(actions);
        card.addView(inside);
        list.addView(card);
    }

    private void addAction(LinearLayout parent, String label, String state, String uid) {
        parent.addView(AdminUi.button(this, label,
            v -> confirmAction("Set account state to " + state + "?", "setUserStatus", uid, state)));
    }

    private void confirmAction(String message, String action, String uid) {
        confirmAction(message, action, uid, null);
    }

    private void confirmAction(String message, String action, String uid, String state) {
        AdminUi.confirm(this, "Confirm admin action", message, "Confirm", () -> {
            Map<String, Object> p = new HashMap<>();
            p.put("uid", uid);
            if (state != null) p.put("status", state);
            AdminApi.call(action, p, new AdminApi.Callback() {
                @Override public void onSuccess(Object value) {
                    AdminUi.toast(AdminUsersActivity.this, "Action completed");
                    lookup();
                }
                @Override public void onError(String error) { AdminUi.toast(AdminUsersActivity.this, error); }
            });
        });
    }

    private void blocked() {
        list.removeViews(5, Math.max(0, list.getChildCount() - 5));
        AdminApi.call("listBlockedUsers", new AdminApi.Callback() {
            @Override public void onSuccess(Object value) {
                Map<String, Object> result = AdminApi.map(value);
                Object raw = result.get("items");
                list.addView(AdminUi.title(AdminUsersActivity.this, "Global blocked-user view"));
                if (!(raw instanceof List) || ((List<?>) raw).isEmpty()) {
                    list.addView(AdminUi.label(AdminUsersActivity.this, "No blocked-user records found."));
                    return;
                }
                for (Object item : (List<?>) raw) {
                    Map<String, Object> row = AdminApi.map(item);
                    list.addView(AdminUi.body(AdminUsersActivity.this,
                        AdminApi.text(row.get("ownerUid"), "?") + " blocked "
                            + AdminApi.text(row.get("blockedUid"), "?")
                            + " (" + AdminApi.text(row.get("source"), "blocks") + ")"));
                }
            }
            @Override public void onError(String error) { AdminUi.toast(AdminUsersActivity.this, error); }
        });
    }
}