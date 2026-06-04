package com.callx.app.youtube.settings;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import com.callx.app.youtube.settings.R;

public class YouTubePlaybackSettingsActivity extends AppCompatActivity {

    private static final String PREFS = "yt_playback_prefs";
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_yt_playback_settings);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        Toolbar toolbar = findViewById(R.id.toolbar_yt_playback);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.yt_playback);
        }

        // Autoplay next video
        SwitchCompat swAutoplay = findViewById(R.id.sw_yt_autoplay);
        swAutoplay.setChecked(prefs.getBoolean("autoplay", false));
        swAutoplay.setOnCheckedChangeListener((btn, checked) ->
            prefs.edit().putBoolean("autoplay", checked).apply());

        // Double-tap to seek
        View rowSeek = findViewById(R.id.row_yt_double_tap_seek);
        TextView tvSeekVal = findViewById(R.id.tv_yt_seek_val);
        String[] seekOpts = {"5 seconds", "10 seconds", "15 seconds", "20 seconds", "30 seconds"};
        int savedSeek = prefs.getInt("double_tap_seek", 1);
        tvSeekVal.setText(seekOpts[savedSeek]);
        rowSeek.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                .setTitle(R.string.yt_double_tap_seek)
                .setSingleChoiceItems(seekOpts, prefs.getInt("double_tap_seek", 1), (dlg, which) -> {
                    prefs.edit().putInt("double_tap_seek", which).apply();
                    tvSeekVal.setText(seekOpts[which]);
                    dlg.dismiss();
                }).show();
        });

        // Zoom to fill screen
        SwitchCompat swZoom = findViewById(R.id.sw_yt_zoom_fill);
        swZoom.setChecked(prefs.getBoolean("zoom_fill", true));
        swZoom.setOnCheckedChangeListener((btn, checked) ->
            prefs.edit().putBoolean("zoom_fill", checked).apply());

        // Picture-in-picture
        SwitchCompat swPip = findViewById(R.id.sw_yt_pip);
        swPip.setChecked(prefs.getBoolean("pip", false));
        swPip.setOnCheckedChangeListener((btn, checked) ->
            prefs.edit().putBoolean("pip", checked).apply());
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }
}
