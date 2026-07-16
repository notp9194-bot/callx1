package com.callx.app.settings;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import com.callx.app.x.R;

public class XSettingsSecurityActivity extends AppCompatActivity {

    private static final String PREFS = "x_security_prefs";
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_x_settings_security);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        Toolbar toolbar = findViewById(R.id.toolbar_x_security);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Security and account access");
        }

        setupSwitch(R.id.sw_x_2fa,            "two_factor",       false);
        setupSwitch(R.id.sw_x_pwd_protection,  "pwd_protection",   false);

        View rowSessions = findViewById(R.id.row_x_apps_sessions);
        if (rowSessions != null)
            rowSessions.setOnClickListener(v ->
                Toast.makeText(this, "Apps and sessions coming soon", Toast.LENGTH_SHORT).show());

        View rowDevices = findViewById(R.id.row_x_devices);
        if (rowDevices != null)
            rowDevices.setOnClickListener(v ->
                Toast.makeText(this, "Logged-in devices coming soon", Toast.LENGTH_SHORT).show());
    }

    private void setupSwitch(int id, String key, boolean def) {
        SwitchCompat sw = findViewById(id);
        if (sw == null) return;
        sw.setChecked(prefs.getBoolean(key, def));
        sw.setOnCheckedChangeListener((b, c) -> prefs.edit().putBoolean(key, c).apply());
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }
}
