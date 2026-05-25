package com.callx.app.activities;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import com.callx.app.youtube.R;

public class YouTubeVideoQualitySettingsActivity extends AppCompatActivity {

    private static final String PREFS = "yt_quality_prefs";
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_yt_video_quality_settings);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        Toolbar toolbar = findViewById(R.id.toolbar_yt_video_quality);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.yt_video_quality_preferences);
        }

        // Mobile network quality
        RadioGroup rgMobile = findViewById(R.id.rg_yt_mobile_quality);
        int mobileQ = prefs.getInt("mobile_quality", R.id.rb_yt_mobile_data_saver);
        rgMobile.check(mobileQ);
        rgMobile.setOnCheckedChangeListener((grp, id) ->
            prefs.edit().putInt("mobile_quality", id).apply());

        // Wi-Fi quality
        RadioGroup rgWifi = findViewById(R.id.rg_yt_wifi_quality);
        int wifiQ = prefs.getInt("wifi_quality", R.id.rb_yt_wifi_data_saver);
        rgWifi.check(wifiQ);
        rgWifi.setOnCheckedChangeListener((grp, id) ->
            prefs.edit().putInt("wifi_quality", id).apply());
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }
}
