package com.callx.app.admin;

import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.HashMap;
import java.util.Map;

/** System-wide announcement composer backed by an FCM fan-out function. */
public class AdminCommunicationsActivity extends AppCompatActivity {
    private android.widget.EditText title;
    private android.widget.EditText body;
    private android.widget.EditText audience;
    private TextView preview;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = AdminUi.screen(this);
        root.addView(AdminUi.toolbar(this, "System announcements"),
            new LinearLayout.LayoutParams(-1, AdminUi.dp(this, 56)));
        android.widget.ScrollView scroll = AdminUi.scroll(this);
        LinearLayout content = AdminUi.column(this);
        scroll.addView(content, new ViewGroup.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        content.addView(AdminUi.title(this, "FCM broadcast"));
        content.addView(AdminUi.label(this, "A server fan-out sends this to registered CallX devices. No device tokens are exposed to the admin app."));
        title = AdminUi.field(this, "Notification title");
        title.setSingleLine(true);
        body = AdminUi.field(this, "Message body");
        body.setMinLines(4);
        audience = AdminUi.field(this, "Audience (all, or a role/tag supported by backend)");
        audience.setSingleLine(true);
        audience.setText("all");
        content.addView(title);
        content.addView(body);
        content.addView(audience);
        preview = AdminUi.body(this, "Preview will appear here.");
        content.addView(preview);
        content.addView(AdminUi.button(this, "Preview", v -> showPreview()));
        content.addView(AdminUi.button(this, "Send announcement", v -> send()));
        setContentView(root);
    }

    private void showPreview() {
        preview.setText("Preview\n" + title.getText().toString().trim()
            + "\n" + body.getText().toString().trim()
            + "\nAudience: " + audience.getText().toString().trim());
    }

    private void send() {
        String t = title.getText().toString().trim();
        String b = body.getText().toString().trim();
        String a = audience.getText().toString().trim();
        if (t.isEmpty() || b.isEmpty()) { AdminUi.toast(this, "Title and body are required"); return; }
        AdminUi.confirm(this, "Send announcement",
            "This will send the following notification to " + a + ":\n\n" + t + "\n" + b,
            "Send", () -> {
                Map<String, Object> p = new HashMap<>();
                p.put("title", t);
                p.put("body", b);
                p.put("audience", a.isEmpty() ? "all" : a);
                AdminApi.call("sendAnnouncement", p, new AdminApi.Callback() {
                    @Override public void onSuccess(Object value) {
                        Map<String, Object> r = AdminApi.map(value);
                        AdminUi.toast(AdminCommunicationsActivity.this,
                            "Sent to " + AdminApi.number(r.get("sent")) + " devices");
                    }
                    @Override public void onError(String error) { AdminUi.toast(AdminCommunicationsActivity.this, error); }
                });
            });
    }
}