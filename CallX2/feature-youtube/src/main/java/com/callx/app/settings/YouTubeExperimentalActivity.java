package com.callx.app.settings;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import com.callx.app.youtube.R;

/**
 * YouTubeExperimentalActivity — Try experimental new features.
 * Real toggles saved to SharedPrefs — PlayerActivity checks these at startup.
 */
public class YouTubeExperimentalActivity extends AppCompatActivity {

    private static final String PREFS = "yt_experimental_prefs";
    private SharedPreferences prefs;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_yt_experimental);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        Toolbar toolbar = findViewById(R.id.toolbar_yt_experimental);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Try Experimental Features");
        }

        setupExp(R.id.sw_exp_new_comments_ui,         "new_comments_ui",         false,
            "New Comments UI — threaded replies naye design mein");
        setupExp(R.id.sw_exp_shorts_quality,           "shorts_quality",          false,
            "Higher Shorts Quality — shorts 1080p mein stream honge");
        setupExp(R.id.sw_exp_ambient_mode,             "ambient_mode",            true,
            "Ambient Mode — player background video ka color reflect karega");
        setupExp(R.id.sw_exp_precise_seeking,          "precise_seeking",         false,
            "Precise Seeking — timeline pe slow-mo preview while scrubbing");
        setupExp(R.id.sw_exp_chapters,                 "chapters",                true,
            "Chapter Markers — video description se chapters auto detect");
        setupExp(R.id.sw_exp_live_chat_replay,         "live_chat_replay",        false,
            "Live Chat Replay — old live streams ka chat replay");
        setupExp(R.id.sw_exp_new_player_controls,      "new_player_controls",     false,
            "New Player Controls — redesigned video player");
        setupExp(R.id.sw_exp_shorts_camera,            "shorts_camera",           false,
            "Shorts Camera — in-app Shorts recording");
        setupExp(R.id.sw_exp_offline_subtitles,        "offline_subtitles",       false,
            "Offline Subtitles — captions downloaded ke saath");
        setupExp(R.id.sw_exp_auto_chapters,            "auto_chapters",           false,
            "Auto Chapters — AI se automatic chapter detection");
    }

    private void setupExp(int switchId, String key, boolean defaultVal, String label) {
        SwitchCompat sw = findViewById(switchId);
        if (sw == null) return;
        sw.setChecked(prefs.getBoolean(key, defaultVal));
        sw.setOnCheckedChangeListener((btn, checked) -> {
            prefs.edit().putBoolean(key, checked).apply();
            Toast.makeText(this,
                (checked ? "✅ Enabled: " : "Disabled: ") + label.split("—")[0].trim(),
                Toast.LENGTH_SHORT).show();
        });
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }
}
