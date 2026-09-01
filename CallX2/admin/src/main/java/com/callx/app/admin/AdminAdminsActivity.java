package com.callx.app.admin;

import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.appcompat.app.AppCompatActivity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Role-based admin management. The backend still protects this action. */
public class AdminAdminsActivity extends AppCompatActivity {
    private LinearLayout list;
    private android.widget.EditText uid;
    private android.widget.EditText role;
    private android.widget.EditText permissions;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = AdminUi.screen(this);
        root.addView(AdminUi.toolbar(this, "Admin-of-admins"),
            new LinearLayout.LayoutParams(-1, AdminUi.dp(this, 56)));
        android.widget.ScrollView scroll = AdminUi.scroll(this);
        list = AdminUi.column(this);
        scroll.addView(list, new ViewGroup.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        list.addView(AdminUi.title(this, "Roles & permissions"));
        list.addView(AdminUi.label(this, "Supported roles: super_admin, moderator, support, finance. The legacy admins/{uid}: true entry remains supported as super_admin."));
        uid = AdminUi.field(this, "Firebase UID");
        uid.setSingleLine(true);
        role = AdminUi.field(this, "Role");
        role.setSingleLine(true);
        role.setText("moderator");
        permissions = AdminUi.field(this, "Extra permissions (comma separated, optional)");
        permissions.setSingleLine(true);
        list.addView(uid);
        list.addView(role);
        list.addView(permissions);
        list.addView(AdminUi.button(this, "Add / update admin", v -> save()));
        list.addView(AdminUi.button(this, "Refresh admin list", v -> load()));
        load();
        setContentView(root);
    }

    private void load() {
        AdminApi.call("listAdmins", new AdminApi.Callback() {
            @Override public void onSuccess(Object data) {
                while (list.getChildCount() > 7) list.removeViewAt(7);
                Object raw = AdminApi.map(data).get("items");
                if (!(raw instanceof List)) { list.addView(AdminUi.label(AdminAdminsActivity.this, "No admins found.")); return; }
                for (Object item : (List<?>) raw) {
                    Map<String, Object> row = AdminApi.map(item);
                    LinearLayout card = new LinearLayout(AdminAdminsActivity.this);
                    card.setOrientation(LinearLayout.HORIZONTAL);
                    card.addView(AdminUi.body(AdminAdminsActivity.this,
                        AdminApi.text(row.get("uid"), "—") + " • " + AdminApi.text(row.get("role"), "super_admin")),
                        new LinearLayout.LayoutParams(0, -2, 1));
                    String target = AdminApi.text(row.get("uid"), "");
                    card.addView(AdminUi.dangerButton(AdminAdminsActivity.this, "Remove",
                        v -> remove(target)));
                    list.addView(card);
                }
            }
            @Override public void onError(String error) { AdminUi.toast(AdminAdminsActivity.this, error); }
        });
    }

    private void save() {
        String target = uid.getText().toString().trim();
        if (target.isEmpty()) { AdminUi.toast(this, "UID is required"); return; }
        Map<String, Object> p = new HashMap<>();
        p.put("uid", target);
        p.put("role", role.getText().toString().trim());
        Map<String, Object> grants = new HashMap<>();
        for (String permission : permissions.getText().toString().split(",")) {
            String clean = permission.trim();
            if (!clean.isEmpty()) grants.put(clean, true);
        }
        p.put("permissions", grants);
        AdminApi.call("setAdmin", p, new AdminApi.Callback() {
            @Override public void onSuccess(Object data) { AdminUi.toast(AdminAdminsActivity.this, "Admin saved"); load(); }
            @Override public void onError(String error) { AdminUi.toast(AdminAdminsActivity.this, error); }
        });
    }

    private void remove(String target) {
        AdminUi.confirm(this, "Remove admin", "Revoke admin access for " + target + "?", "Remove", () -> {
            Map<String, Object> p = new HashMap<>();
            p.put("uid", target);
            AdminApi.call("removeAdmin", p, new AdminApi.Callback() {
                @Override public void onSuccess(Object data) { AdminUi.toast(AdminAdminsActivity.this, "Admin removed"); load(); }
                @Override public void onError(String error) { AdminUi.toast(AdminAdminsActivity.this, error); }
            });
        });
    }
}