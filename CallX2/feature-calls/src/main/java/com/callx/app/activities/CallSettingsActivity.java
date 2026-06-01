package com.callx.app.activities;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.CompoundButton;
import android.widget.ImageButton;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.callx.app.calls.R;

/**
 * CallSettingsActivity — Settings for 1:1 audio and video calls.
 *
 * Categories:
 *  1. AUDIO QUALITY
 *     - Noise suppression (on/off, default: on)
 *     - Echo cancellation (on/off, default: on)
 *     - Auto gain control (on/off, default: on)
 *
 *  2. VIDEO QUALITY
 *     - Resolution: HD (1280x720) / SD (640x480)
 *     - Frame rate: 30fps / 24fps / 15fps
 *
 *  3. AUDIO ROUTING
 *     - Default: Earpiece (voice) / Speaker (video)
 *     - Auto-switch to speaker on video call (on/off)
 *
 *  4. NOTIFICATIONS
 *     - Show caller name on lock screen (on/off)
 *     - Silent ringtone (on/off)
 *     - Missed call notifications (on/off)
 *
 *  5. PRIVACY
 *     - Show "In a call" presence status (on/off)
 *     - Block unknown callers (on/off)
 *
 *  6. ADVANCED
 *     - Data saver (limits video bitrate to SD regardless of quality setting)
 *     - Keep screen on (on/off)
 *
 * Persisted in SharedPreferences: PREFS_NAME
 */
public class CallSettingsActivity extends AppCompatActivity {

    public static final String PREFS_NAME = "call_settings_1to1";

    // SharedPreferences keys
    public static final String KEY_NOISE_SUPPRESSION  = "noise_suppression";
    public static final String KEY_ECHO_CANCELLATION  = "echo_cancellation";
    public static final String KEY_AUTO_GAIN          = "auto_gain";
    public static final String KEY_VIDEO_QUALITY      = "video_quality";   // "hd" | "sd"
    public static final String KEY_FPS                = "fps";             // 30 | 24 | 15
    public static final String KEY_ROUTING            = "routing";         // "speaker" | "earpiece"
    public static final String KEY_AUTO_SPEAKER_VIDEO = "auto_speaker_video";
    public static final String KEY_LOCKSCREEN_NAME    = "lockscreen_name";
    public static final String KEY_SILENT_RINGTONE    = "silent_ringtone";
    public static final String KEY_MISSED_NOTIF       = "missed_notif";
    public static final String KEY_SHOW_IN_CALL_STATUS = "show_in_call_status";
    public static final String KEY_BLOCK_UNKNOWN      = "block_unknown";
    public static final String KEY_DATA_SAVER         = "data_saver";
    public static final String KEY_KEEP_SCREEN_ON     = "keep_screen_on";

    private SharedPreferences prefs;

    // Audio
    private Switch switchNoiseSuppression, switchEchoCancellation, switchAutoGain;
    // Video
    private RadioGroup rgVideoQuality, rgFrameRate;
    // Routing
    private RadioGroup rgRouting;
    private Switch switchAutoSpeakerVideo;
    // Notifications
    private Switch switchLockscreenName, switchSilentRingtone, switchMissedNotif;
    // Privacy
    private Switch switchShowInCallStatus, switchBlockUnknown;
    // Advanced
    private Switch switchDataSaver, switchKeepScreenOn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_call_settings);
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        setupToolbar();
        bindViews();
        loadSettings();
        attachListeners();
    }

    private void setupToolbar() {
        ImageButton btnBack = findViewById(R.id.btnCallSettingsBack);
        TextView    tvTitle = findViewById(R.id.tvCallSettingsTitle);
        if (tvTitle != null) tvTitle.setText("Call Settings");
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());
    }

    private void bindViews() {
        switchNoiseSuppression  = find(R.id.switchNoiseSuppression);
        switchEchoCancellation  = find(R.id.switchEchoCancellation);
        switchAutoGain          = find(R.id.switchAutoGain);
        rgVideoQuality          = findViewById(R.id.rgVideoQuality);
        rgFrameRate             = findViewById(R.id.rgFrameRate);
        rgRouting               = findViewById(R.id.rgRouting);
        switchAutoSpeakerVideo  = find(R.id.switchAutoSpeakerVideo);
        switchLockscreenName    = find(R.id.switchLockscreenName);
        switchSilentRingtone    = find(R.id.switchSilentRingtone);
        switchMissedNotif       = find(R.id.switchMissedNotif);
        switchShowInCallStatus  = find(R.id.switchShowInCallStatus);
        switchBlockUnknown      = find(R.id.switchBlockUnknown);
        switchDataSaver         = find(R.id.switchDataSaver);
        switchKeepScreenOn      = find(R.id.switchKeepScreenOn);
    }

    private void loadSettings() {
        setChecked(switchNoiseSuppression, prefs.getBoolean(KEY_NOISE_SUPPRESSION, true));
        setChecked(switchEchoCancellation, prefs.getBoolean(KEY_ECHO_CANCELLATION, true));
        setChecked(switchAutoGain,         prefs.getBoolean(KEY_AUTO_GAIN, true));

        String quality = prefs.getString(KEY_VIDEO_QUALITY, "hd");
        if (rgVideoQuality != null) {
            rgVideoQuality.check("hd".equals(quality)
                ? R.id.rbVideoQualityHD : R.id.rbVideoQualitySD);
        }

        int fps = prefs.getInt(KEY_FPS, 30);
        if (rgFrameRate != null) {
            if (fps >= 30)      rgFrameRate.check(R.id.rbFps30);
            else if (fps >= 24) rgFrameRate.check(R.id.rbFps24);
            else                rgFrameRate.check(R.id.rbFps15);
        }

        String routing = prefs.getString(KEY_ROUTING, "earpiece");
        if (rgRouting != null) {
            rgRouting.check("speaker".equals(routing)
                ? R.id.rbRoutingSpeaker : R.id.rbRoutingEarpiece);
        }

        setChecked(switchAutoSpeakerVideo, prefs.getBoolean(KEY_AUTO_SPEAKER_VIDEO, true));
        setChecked(switchLockscreenName,   prefs.getBoolean(KEY_LOCKSCREEN_NAME, true));
        setChecked(switchSilentRingtone,   prefs.getBoolean(KEY_SILENT_RINGTONE, false));
        setChecked(switchMissedNotif,      prefs.getBoolean(KEY_MISSED_NOTIF, true));
        setChecked(switchShowInCallStatus, prefs.getBoolean(KEY_SHOW_IN_CALL_STATUS, true));
        setChecked(switchBlockUnknown,     prefs.getBoolean(KEY_BLOCK_UNKNOWN, false));
        setChecked(switchDataSaver,        prefs.getBoolean(KEY_DATA_SAVER, false));
        setChecked(switchKeepScreenOn,     prefs.getBoolean(KEY_KEEP_SCREEN_ON, true));
    }

    private void attachListeners() {
        onChange(switchNoiseSuppression, KEY_NOISE_SUPPRESSION);
        onChange(switchEchoCancellation, KEY_ECHO_CANCELLATION);
        onChange(switchAutoGain,         KEY_AUTO_GAIN);
        onChange(switchAutoSpeakerVideo, KEY_AUTO_SPEAKER_VIDEO);
        onChange(switchLockscreenName,   KEY_LOCKSCREEN_NAME);
        onChange(switchSilentRingtone,   KEY_SILENT_RINGTONE);
        onChange(switchMissedNotif,      KEY_MISSED_NOTIF);
        onChange(switchShowInCallStatus, KEY_SHOW_IN_CALL_STATUS);
        onChange(switchBlockUnknown,     KEY_BLOCK_UNKNOWN);
        onChange(switchDataSaver,        KEY_DATA_SAVER);
        onChange(switchKeepScreenOn,     KEY_KEEP_SCREEN_ON);

        if (rgVideoQuality != null) rgVideoQuality.setOnCheckedChangeListener((g, id) ->
            prefs.edit().putString(KEY_VIDEO_QUALITY,
                id == R.id.rbVideoQualityHD ? "hd" : "sd").apply());

        if (rgFrameRate != null) rgFrameRate.setOnCheckedChangeListener((g, id) -> {
            int fps = id == R.id.rbFps30 ? 30 : id == R.id.rbFps24 ? 24 : 15;
            prefs.edit().putInt(KEY_FPS, fps).apply();
        });

        if (rgRouting != null) rgRouting.setOnCheckedChangeListener((g, id) ->
            prefs.edit().putString(KEY_ROUTING,
                id == R.id.rbRoutingSpeaker ? "speaker" : "earpiece").apply());
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private void onChange(Switch sw, String key) {
        if (sw == null) return;
        sw.setOnCheckedChangeListener((v, checked) -> prefs.edit().putBoolean(key, checked).apply());
    }

    private void setChecked(Switch sw, boolean val) {
        if (sw != null) sw.setChecked(val);
    }

    private Switch find(int id) { return findViewById(id); }

    @Override public void onBackPressed() { finish(); }

    // ── Static helpers for reading settings in CallActivity ───────────────

    public static boolean isNoiseSuppression(SharedPreferences p)  { return p.getBoolean(KEY_NOISE_SUPPRESSION, true); }
    public static boolean isEchoCancellation(SharedPreferences p)  { return p.getBoolean(KEY_ECHO_CANCELLATION, true); }
    public static boolean isAutoGain(SharedPreferences p)          { return p.getBoolean(KEY_AUTO_GAIN, true); }
    public static String  getVideoQuality(SharedPreferences p)     { return p.getString(KEY_VIDEO_QUALITY, "hd"); }
    public static int     getFps(SharedPreferences p)              { return p.getInt(KEY_FPS, 30); }
    public static String  getRouting(SharedPreferences p)          { return p.getString(KEY_ROUTING, "earpiece"); }
    public static boolean isAutoSpeakerVideo(SharedPreferences p)  { return p.getBoolean(KEY_AUTO_SPEAKER_VIDEO, true); }
    public static boolean isDataSaver(SharedPreferences p)         { return p.getBoolean(KEY_DATA_SAVER, false); }
    public static boolean isKeepScreenOn(SharedPreferences p)      { return p.getBoolean(KEY_KEEP_SCREEN_ON, true); }
    public static boolean isBlockUnknown(SharedPreferences p)      { return p.getBoolean(KEY_BLOCK_UNKNOWN, false); }
}
