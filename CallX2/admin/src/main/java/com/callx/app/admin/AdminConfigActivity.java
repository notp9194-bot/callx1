package com.callx.app.admin;

import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Runtime configuration and kill-switch surface. */
public class AdminConfigActivity extends AppCompatActivity {
    private LinearLayout list;
    private android.widget.EditText key;
    private android.widget.EditText value;
    private TextView current;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = AdminUi.screen(this);
        root.addView(AdminUi.toolbar(this, "App config & feature flags"),
            new LinearLayout.LayoutParams(-1, AdminUi.dp(this, 56)));
        android.widget.ScrollView scroll = AdminUi.scroll(this);
        list = AdminUi.column(this);
        scroll.addView(list, new ViewGroup.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        list.addView(AdminUi.title(this, "Operational controls"));
        list.addView(AdminUi.label(this, "Examples: maintenanceMode, disableUploads, disablePayments, disableCalls, reelsViral/minUses. Values are stored under appConfig."));
        key = AdminUi.field(this, "Config key, e.g. maintenanceMode");
        key.setSingleLine(true);
        value = AdminUi.field(this, "Value (true / false / number / text)");
        value.setSingleLine(true);
        list.addView(key);
        list.addView(value);
        list.addView(AdminUi.button(this, "Save config", v -> save()));
        current = AdminUi.label(this, "Loading current flags…");
        list.addView(current);
        setContentView(root);
        load();
    }

    private void load() {
        AdminApi.call("listConfig", new AdminApi.Callback() {
            @Override public void onSuccess(Object data) {
                Map<String, Object> result = AdminApi.map(data);
                current.setText("Current values: " + AdminApi.text(result.get("config"), "none"));
            }
            @Override public void onError(String error) { current.setText("Config read failed: " + error); }
        });
    }

    private void save() {
        String k = key.getText().toString().trim();
        String v = value.getText().toString().trim();
        if (k.isEmpty() || v.isEmpty()) { AdminUi.toast(this, "Key and value are required"); return; }
        Map<String, Object> p = new HashMap<>();
        p.put("key", k);
        p.put("value", parse(v));
        AdminApi.call("setConfig", p, new AdminApi.Callback() {
            @Override public void onSuccess(Object data) { AdminUi.toast(AdminConfigActivity.this, "Config saved"); load(); }
            @Override public void onError(String error) { AdminUi.toast(AdminConfigActivity.this, error); }
        });
    }

    private Object parse(String raw) {
        if ("true".equalsIgnoreCase(raw)) return true;
        if ("false".equalsIgnoreCase(raw)) return false;
        try { return Long.parseLong(raw); } catch (NumberFormatException ignored) { return raw; }
    }
}