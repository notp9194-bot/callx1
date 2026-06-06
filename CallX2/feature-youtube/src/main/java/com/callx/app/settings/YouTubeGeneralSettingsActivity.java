package com.callx.app.settings;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import com.callx.app.youtube.R;

public class YouTubeGeneralSettingsActivity extends AppCompatActivity {

    private static final String PREFS = "yt_general_prefs";
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_yt_general_settings);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        Toolbar toolbar = findViewById(R.id.toolbar_yt_general);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.yt_general);
        }

        // Remind me to take a break
        SwitchCompat swBreak = findViewById(R.id.sw_yt_remind_break);
        swBreak.setChecked(prefs.getBoolean("remind_break", true));
        swBreak.setOnCheckedChangeListener((btn, checked) ->
            prefs.edit().putBoolean("remind_break", checked).apply());

        // Remind me when it's bedtime
        SwitchCompat swBedtime = findViewById(R.id.sw_yt_bedtime);
        swBedtime.setChecked(prefs.getBoolean("bedtime", false));
        swBedtime.setOnCheckedChangeListener((btn, checked) ->
            prefs.edit().putBoolean("bedtime", checked).apply());

        // Appearance
        View rowAppearance = findViewById(R.id.row_yt_appearance);
        TextView tvAppearanceVal = findViewById(R.id.tv_yt_appearance_val);
        String[] themes = {"System default", "Light", "Dark"};
        int savedTheme = prefs.getInt("theme", 0);
        tvAppearanceVal.setText(themes[savedTheme]);
        rowAppearance.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                .setTitle(R.string.yt_appearance)
                .setSingleChoiceItems(themes, prefs.getInt("theme", 0), (dlg, which) -> {
                    prefs.edit().putInt("theme", which).apply();
                    tvAppearanceVal.setText(themes[which]);
                    dlg.dismiss();
                }).show();
        });

        // App language
        View rowLang = findViewById(R.id.row_yt_app_language);
        TextView tvLangVal = findViewById(R.id.tv_yt_language_val);
        String[] langs = {"English (United States)", "Hindi", "Bengali", "Tamil", "Telugu", "Marathi", "Gujarati", "Kannada", "Malayalam", "Punjabi"};
        int savedLang = prefs.getInt("language", 0);
        tvLangVal.setText(langs[savedLang]);
        rowLang.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                .setTitle(R.string.yt_app_language)
                .setSingleChoiceItems(langs, prefs.getInt("language", 0), (dlg, which) -> {
                    prefs.edit().putInt("language", which).apply();
                    tvLangVal.setText(langs[which]);
                    dlg.dismiss();
                }).show();
        });

        // Playback in feeds
        View rowPlayback = findViewById(R.id.row_yt_playback_feeds);
        TextView tvPlaybackVal = findViewById(R.id.tv_yt_playback_feeds_val);
        String[] feedOpts = {"On", "Wi-Fi only", "Off"};
        int savedFeed = prefs.getInt("playback_feeds", 0);
        tvPlaybackVal.setText(feedOpts[savedFeed]);
        rowPlayback.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                .setTitle(R.string.yt_playback_in_feeds)
                .setSingleChoiceItems(feedOpts, prefs.getInt("playback_feeds", 0), (dlg, which) -> {
                    prefs.edit().putInt("playback_feeds", which).apply();
                    tvPlaybackVal.setText(feedOpts[which]);
                    dlg.dismiss();
                }).show();
        });

        // Location
        View rowLocation = findViewById(R.id.row_yt_location);
        TextView tvLocationVal = findViewById(R.id.tv_yt_location_val);
        String[] countries = {"India", "United States", "United Kingdom", "Canada", "Australia"};
        int savedCountry = prefs.getInt("location", 0);
        tvLocationVal.setText(countries[savedCountry]);
        rowLocation.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                .setTitle(R.string.yt_location)
                .setSingleChoiceItems(countries, prefs.getInt("location", 0), (dlg, which) -> {
                    prefs.edit().putInt("location", which).apply();
                    tvLocationVal.setText(countries[which]);
                    dlg.dismiss();
                }).show();
        });

        // Restricted Mode
        SwitchCompat swRestricted = findViewById(R.id.sw_yt_restricted_mode);
        swRestricted.setChecked(prefs.getBoolean("restricted_mode", false));
        swRestricted.setOnCheckedChangeListener((btn, checked) ->
            prefs.edit().putBoolean("restricted_mode", checked).apply());

        // Enable stats for nerds
        SwitchCompat swStats = findViewById(R.id.sw_yt_stats_nerds);
        swStats.setChecked(prefs.getBoolean("stats_nerds", false));
        swStats.setOnCheckedChangeListener((btn, checked) ->
            prefs.edit().putBoolean("stats_nerds", checked).apply());

        // Earn badges
        SwitchCompat swBadges = findViewById(R.id.sw_yt_earn_badges);
        swBadges.setChecked(prefs.getBoolean("earn_badges", true));
        swBadges.setOnCheckedChangeListener((btn, checked) ->
            prefs.edit().putBoolean("earn_badges", checked).apply());
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }
}
