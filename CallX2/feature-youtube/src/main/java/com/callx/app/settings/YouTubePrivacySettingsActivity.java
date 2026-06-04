package com.callx.app.settings;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MenuItem;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import com.callx.app.youtube.R;
public class YouTubePrivacySettingsActivity extends AppCompatActivity {
    private static final String PREFS = "yt_privacy_prefs";
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_yt_privacy_settings);
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        Toolbar toolbar = findViewById(R.id.toolbar_yt_privacy);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.yt_privacy);
        }
        setupSwitch(R.id.sw_yt_private_subscriptions, prefs, "private_subscriptions", false);
        setupSwitch(R.id.sw_yt_private_liked,         prefs, "private_liked",         false);
        setupSwitch(R.id.sw_yt_private_saved,         prefs, "private_saved",         false);
        setupSwitch(R.id.sw_yt_private_playlists,     prefs, "private_playlists",     false);
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
