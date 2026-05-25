package com.callx.app.activities;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MenuItem;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import com.callx.app.x.R;

public class XSettingsNotifActivity extends AppCompatActivity {

    private static final String PREFS = "x_notif_prefs";
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_x_settings_notif);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        Toolbar toolbar = findViewById(R.id.toolbar_x_notif);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Notifications");
        }

        setupSwitch(R.id.sw_x_notif_push,    "notif_push",    true);
        setupSwitch(R.id.sw_x_notif_likes,   "notif_likes",   true);
        setupSwitch(R.id.sw_x_notif_reposts, "notif_reposts", true);
        setupSwitch(R.id.sw_x_notif_follow,  "notif_follow",  true);
        setupSwitch(R.id.sw_x_notif_reply,   "notif_reply",   true);
        setupSwitch(R.id.sw_x_notif_dm,      "notif_dm",      true);
        setupSwitch(R.id.sw_x_notif_sound,   "notif_sound",   true);
        setupSwitch(R.id.sw_x_notif_vibrate, "notif_vibrate", true);
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
