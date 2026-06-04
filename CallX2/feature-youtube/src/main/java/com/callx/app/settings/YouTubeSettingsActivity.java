package com.callx.app.settings;

import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import com.callx.app.youtube.R;

/**
 * Main YouTube Settings screen — mirrors the real YouTube settings hierarchy.
 * Sections: Account, Video and audio preferences, Help and policy, Developer preferences.
 */
public class YouTubeSettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_youtube_settings);

        Toolbar toolbar = findViewById(R.id.toolbar_yt_settings);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.yt_settings);
        }

        // ── Account section ──────────────────────────────────────────────────
        setupRow(R.id.row_yt_general,        "General",                       YouTubeGeneralSettingsActivity.class);
        setupRow(R.id.row_yt_switch_account, "Switch account",                YouTubeSwitchAccountActivity.class);
        setupRow(R.id.row_yt_family_center,  "Family Center",                 YouTubeFamilyCenterActivity.class);
        setupRow(R.id.row_yt_notifications,  "Notifications",                 YouTubeNotifSettingsActivity.class);
        setupRow(R.id.row_yt_purchases,      "Purchases and memberships",     YouTubePurchasesActivity.class);
        setupRow(R.id.row_yt_billing,        "Billing & payments",            YouTubeBillingActivity.class);
        setupRow(R.id.row_yt_manage_history, "Manage all history",            YouTubeManageHistoryActivity.class);
        setupRow(R.id.row_yt_your_data,      "Your data in YouTube",          YouTubeYourDataActivity.class);
        setupRow(R.id.row_yt_privacy,        "Privacy",                       YouTubePrivacySettingsActivity.class);
        setupRow(R.id.row_yt_connected_apps, "Connected apps",                YouTubeConnectedAppsActivity.class);
        setupRow(R.id.row_yt_experimental,   "Try experimental new features", YouTubeExperimentalActivity.class);

        // ── Video and audio preferences section ──────────────────────────────
        setupRow(R.id.row_yt_video_quality,  "Video quality preferences",     YouTubeVideoQualitySettingsActivity.class);
        setupRow(R.id.row_yt_playback,       "Playback",                      YouTubePlaybackSettingsActivity.class);
        setupRow(R.id.row_yt_captions,       "Captions",                      YouTubeCaptionsSettingsActivity.class);
        setupRow(R.id.row_yt_data_saving,    "Data saving",                   YouTubeDataSavingSettingsActivity.class);
        setupRow(R.id.row_yt_downloads,      "Downloads",                     YouTubeDownloadSettingsActivity.class);
        setupRow(R.id.row_yt_live_chat,      "Live chat",                     YouTubeLiveChatSettingsActivity.class);
        setupRow(R.id.row_yt_accessibility,  "Accessibility",                 YouTubeAccessibilitySettingsActivity.class);

        // ── Help and policy section ───────────────────────────────────────────
        setupRow(R.id.row_yt_help,           "Help",                          YouTubeHelpActivity.class);
        setupRow(R.id.row_yt_tos,            "YouTube Terms of Service",      YouTubeTosActivity.class);
        setupRow(R.id.row_yt_feedback,       "Send feedback",                 YouTubeFeedbackActivity.class);
        setupRow(R.id.row_yt_about,          "About",                         YouTubeAboutActivity.class);

        // ── Developer preferences ─────────────────────────────────────────────
        setupRow(R.id.row_yt_developer,      "Developer preferences",         YouTubeDeveloperSettingsActivity.class);
    }

    private void setupRow(int rowId, String title, Class<?> activityClass) {
        View row = findViewById(rowId);
        if (row == null) return;
        TextView tv = row.findViewById(R.id.tv_yt_row_title);
        if (tv != null) tv.setText(title);
        row.setOnClickListener(v -> startActivity(new Intent(this, activityClass)));
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }
}
