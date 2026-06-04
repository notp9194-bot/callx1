package com.callx.app.youtube.settings;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MenuItem;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import com.callx.app.youtube.settings.R;

public class YouTubeDataSavingSettingsActivity extends AppCompatActivity {

    private static final String PREFS = "yt_data_prefs";
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_yt_data_saving_settings);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        Toolbar toolbar = findViewById(R.id.toolbar_yt_data_saving);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.yt_data_saving);
        }

        setupSwitch(R.id.sw_yt_data_saving_mode,       "data_saving_mode",       false);
        setupSwitch(R.id.sw_yt_reduce_video_quality,   "reduce_video_quality",   true);
        setupSwitch(R.id.sw_yt_reduce_download_quality,"reduce_download_quality",true);
        setupSwitch(R.id.sw_yt_reduce_smart_downloads, "reduce_smart_downloads", false);
        setupSwitch(R.id.sw_yt_wifi_only_downloads,    "wifi_only_downloads",    false);
        setupSwitch(R.id.sw_yt_wifi_only_upload,       "wifi_only_upload",       false);
        setupSwitch(R.id.sw_yt_muted_playback_wifi,    "muted_playback_wifi",    false);
        setupSwitch(R.id.sw_yt_select_quality_every,   "select_quality_every",   false);
        setupSwitch(R.id.sw_yt_data_usage_reminder,    "data_usage_reminder",    false);
    }

    private void setupSwitch(int viewId, String key, boolean defaultVal) {
        SwitchCompat sw = findViewById(viewId);
        if (sw == null) return;
        sw.setChecked(prefs.getBoolean(key, defaultVal));
        sw.setOnCheckedChangeListener((btn, checked) ->
            prefs.edit().putBoolean(key, checked).apply());
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }
}
