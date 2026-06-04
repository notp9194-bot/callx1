package com.callx.app.youtube.settings;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import com.callx.app.youtube.settings.R;

public class YouTubeDownloadSettingsActivity extends AppCompatActivity {

    private static final String PREFS = "yt_download_prefs";
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_yt_download_settings);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        Toolbar toolbar = findViewById(R.id.toolbar_yt_downloads);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.yt_downloads);
        }

        // Download quality
        View rowQuality = findViewById(R.id.row_yt_download_quality);
        TextView tvQualityVal = findViewById(R.id.tv_yt_download_quality_val);
        String[] qualities = {"Low (144p)", "Medium (360p)", "High (720p)", "Full HD (1080p)"};
        int savedQ = prefs.getInt("download_quality", 0);
        tvQualityVal.setText(qualities[savedQ]);
        rowQuality.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                .setTitle(R.string.yt_download_quality)
                .setSingleChoiceItems(qualities, prefs.getInt("download_quality", 0), (dlg, which) -> {
                    prefs.edit().putInt("download_quality", which).apply();
                    tvQualityVal.setText(qualities[which]);
                    dlg.dismiss();
                }).show();
        });

        // Download over Wi-Fi only
        SwitchCompat swWifi = findViewById(R.id.sw_yt_download_wifi_only);
        swWifi.setChecked(prefs.getBoolean("download_wifi_only", false));
        swWifi.setOnCheckedChangeListener((btn, checked) ->
            prefs.edit().putBoolean("download_wifi_only", checked).apply());

        // Recommend downloads
        SwitchCompat swRecommend = findViewById(R.id.sw_yt_recommend_downloads);
        swRecommend.setChecked(prefs.getBoolean("recommend_downloads", false));
        swRecommend.setOnCheckedChangeListener((btn, checked) ->
            prefs.edit().putBoolean("recommend_downloads", checked).apply());

        // Downloading help
        View rowHelp = findViewById(R.id.row_yt_downloading_help);
        if (rowHelp != null) rowHelp.setOnClickListener(v ->
            new AlertDialog.Builder(this)
                .setTitle(R.string.yt_downloading_help)
                .setMessage(R.string.yt_downloading_help_desc)
                .setPositiveButton(android.R.string.ok, null).show());

        // Delete all downloads
        View rowDelete = findViewById(R.id.row_yt_delete_downloads);
        if (rowDelete != null) rowDelete.setOnClickListener(v ->
            new AlertDialog.Builder(this)
                .setTitle(R.string.yt_delete_all_downloads)
                .setMessage(R.string.yt_delete_all_downloads_confirm)
                .setPositiveButton(R.string.yt_delete, (dlg, w) -> {
                    // Clear downloads
                })
                .setNegativeButton(android.R.string.cancel, null).show());

        // Storage progress bar — 193 MB used, 18.88 GB free (example)
        ProgressBar pb = findViewById(R.id.pb_yt_storage);
        if (pb != null) {
            pb.setMax(1000);
            pb.setProgress(10); // ~1% used
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }
}
