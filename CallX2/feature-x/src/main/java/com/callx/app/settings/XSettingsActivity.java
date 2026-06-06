package com.callx.app.settings;

import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import com.callx.app.x.R;

/**
 * XSettingsActivity — Main X Settings screen.
 * Mirrors YouTubeSettingsActivity structure exactly.
 * Sections: Your account, Content preferences, Help and about.
 */
public class XSettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_x_settings);

        Toolbar toolbar = findViewById(R.id.toolbar_x_settings);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Settings");
        }

        // ── Your account ─────────────────────────────────────────────────────
        setupRow(R.id.row_x_account,       "Your account",               R.drawable.ic_person,            XSettingsAccountActivity.class);
        setupRow(R.id.row_x_security,      "Security and account access", R.drawable.ic_x_lock,           XSettingsSecurityActivity.class);
        setupRow(R.id.row_x_privacy,       "Privacy and safety",         R.drawable.ic_x_shield,          XSettingsPrivacyActivity.class);
        setupRow(R.id.row_x_notifications, "Notifications",              R.drawable.ic_notifications,      XSettingsNotifActivity.class);

        // ── Content preferences ───────────────────────────────────────────────
        setupRow(R.id.row_x_display,       "Display and sound",          R.drawable.ic_x_display,         XSettingsDisplayActivity.class);
        setupRow(R.id.row_x_accessibility, "Accessibility",              R.drawable.ic_x_accessibility,   XSettingsAccessibilityActivity.class);
        setupRow(R.id.row_x_data_usage,    "Data usage",                 R.drawable.ic_x_data,            XSettingsDataActivity.class);

        // ── Help and about ────────────────────────────────────────────────────
        setupRow(R.id.row_x_about,         "About",                      R.drawable.ic_x_info,            XSettingsAboutActivity.class);
    }

    private void setupRow(int rowId, String title, int iconRes, Class<?> activityClass) {
        View row = findViewById(rowId);
        if (row == null) return;
        TextView tv = row.findViewById(R.id.tv_x_row_title);
        if (tv != null) tv.setText(title);
        ImageView iv = row.findViewById(R.id.iv_x_row_icon);
        if (iv != null) iv.setImageResource(iconRes);
        row.setOnClickListener(v -> startActivity(new Intent(this, activityClass)));
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }
}
