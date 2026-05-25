package com.callx.app.activities;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MenuItem;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import com.callx.app.youtube.R;
public class YouTubeNotifSettingsActivity extends AppCompatActivity {
    private static final String PREFS = "yt_notif_prefs";
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_yt_notif_settings);
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        Toolbar toolbar = findViewById(R.id.toolbar_yt_notif);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.yt_notifications);
        }
        setupSwitch(R.id.sw_yt_notif_subscriptions, prefs, "notif_subscriptions", true);
        setupSwitch(R.id.sw_yt_notif_recommended,   prefs, "notif_recommended",   true);
        setupSwitch(R.id.sw_yt_notif_activity,      prefs, "notif_activity",      true);
        setupSwitch(R.id.sw_yt_notif_mentions,      prefs, "notif_mentions",      true);
        setupSwitch(R.id.sw_yt_notif_shared,        prefs, "notif_shared",        false);
        setupSwitch(R.id.sw_yt_notif_sound,         prefs, "notif_sound",         true);
        setupSwitch(R.id.sw_yt_notif_vibrate,       prefs, "notif_vibrate",       true);
    }
    private void setupSwitch(int id, SharedPreferences prefs, String key, boolean def) {
        SwitchCompat sw = findViewById(id);
        if (sw == null) return;
        sw.setChecked(prefs.getBoolean(key, def));
        sw.setOnCheckedChangeListener((b, c) -> prefs.edit().putBoolean(key, c).apply());
    }
    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }
}
