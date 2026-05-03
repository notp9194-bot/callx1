package com.callx.app.activities;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import androidx.appcompat.app.AppCompatActivity;
import com.callx.app.R;
import com.callx.app.utils.Constants;

/**
 * GroupCallSettingsActivity — Full production-grade group call settings.
 *
 * Settings categories:
 *
 * 1. AUDIO QUALITY
 *    - Noise suppression  (on/off)
 *    - Echo cancellation  (on/off)
 *    - Auto gain control  (on/off)
 *
 * 2. VIDEO QUALITY
 *    - Resolution: HD (1280x720) / SD (640x480) / Auto
 *    - Frame rate: 30fps / 24fps / 15fps
 *    - Camera: Front / Back / Auto
 *
 * 3. AUDIO ROUTING
 *    - Default speaker mode: Speaker / Earpiece / Bluetooth (if available)
 *    - Auto-switch to speaker when video is on
 *
 * 4. NOTIFICATION PREFERENCES
 *    - Missed group call notifications: on/off
 *    - Show caller name on lock screen: on/off
 *    - Ringtone: System default / Silent
 *
 * 5. PRIVACY & SECURITY
 *    - Allow group call from: Everyone / Contacts only / Nobody
 *    - Show "In a group call" status: on/off
 *    - Block unknown callers: on/off
 *
 * 6. ADVANCED
 *    - Max participants shown (2–8)
 *    - Data saver mode (limits video bitrate)
 *    - Keep screen on during call: on/off
 *
 * All settings are persisted in SharedPreferences with Constants key names.
 * If called with EXTRA_CALL_ACTIVE=true, changes take effect on next call.
 */
public class GroupCallSettingsActivity extends AppCompatActivity {

    public static final String EXTRA_CALL_ACTIVE = "gcs_call_active";

    private SharedPreferences prefs;
    private boolean callActive;

    // Audio
    private Switch switchNoiseSuppression, switchEchoCancellation,
                   switchAutoGainControl;

    // Video
    private RadioGroup rgVideoQuality, rgFrameRate;
    private Switch switchFrontCameraDefault;

    // Routing
    private RadioGroup rgDefaultSpeaker;
    private Switch switchAutoSpeakerVideo;

    // Notifications
    private Switch switchMissedCallNotif, switchLockScreenName, switchSilentRingtone;

    // Privacy
    private RadioGroup rgAllowCallFrom;
    private Switch switchShowCallStatus, switchBlockUnknown;

    // Advanced
    private RadioGroup rgMaxParticipants;
    private Switch switchDataSaver, switchKeepScreenOn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_group_call_settings);

        callActive = getIntent().getBooleanExtra(EXTRA_CALL_ACTIVE, false);
        prefs = getSharedPreferences(Constants.GROUP_CALL_SETTINGS_PREFS, MODE_PRIVATE);

        setupToolbar();
        bindViews();
        loadCurrentSettings();
        setupSaveOnChange();
    }

    private void setupToolbar() {
        ImageButton btnBack = findViewById(R.id.btnGroupCallSettingsBack);
        TextView tvTitle    = findViewById(R.id.tvGroupCallSettingsTitle);
        tvTitle.setText("Group Call Settings");
        if (btnBack != null) btnBack.setOnClickListener(v -> {
            setResult(RESULT_OK);
            finish();
        });
        if (callActive) {
            TextView tvNote = findViewById(R.id.tvGroupCallSettingsNote);
            if (tvNote != null) tvNote.setText(
                "Some changes will take effect after the current call ends.");
        }
    }

    private void bindViews() {
        // Audio
        switchNoiseSuppression  = findViewById(R.id.switchGCNoiseSuppression);
        switchEchoCancellation  = findViewById(R.id.switchGCEchoCancellation);
        switchAutoGainControl   = findViewById(R.id.switchGCAutoGainControl);

        // Video
        rgVideoQuality          = findViewById(R.id.rgGCVideoQuality);
        rgFrameRate             = findViewById(R.id.rgGCFrameRate);
        switchFrontCameraDefault= findViewById(R.id.switchGCFrontCamera);

        // Routing
        rgDefaultSpeaker        = findViewById(R.id.rgGCDefaultSpeaker);
        switchAutoSpeakerVideo  = findViewById(R.id.switchGCAutoSpeakerVideo);

        // Notifications
        switchMissedCallNotif   = findViewById(R.id.switchGCMissedCallNotif);
        switchLockScreenName    = findViewById(R.id.switchGCLockScreenName);
        switchSilentRingtone    = findViewById(R.id.switchGCSilentRingtone);

        // Privacy
        rgAllowCallFrom         = findViewById(R.id.rgGCAllowCallFrom);
        switchShowCallStatus    = findViewById(R.id.switchGCShowCallStatus);
        switchBlockUnknown      = findViewById(R.id.switchGCBlockUnknown);

        // Advanced
        rgMaxParticipants       = findViewById(R.id.rgGCMaxParticipants);
        switchDataSaver         = findViewById(R.id.switchGCDataSaver);
        switchKeepScreenOn      = findViewById(R.id.switchGCKeepScreenOn);
    }

    private void loadCurrentSettings() {
        // Audio
        setSwitchChecked(switchNoiseSuppression,
            prefs.getBoolean(Constants.GCALL_PREF_NOISE_SUPPRESSION, true));
        setSwitchChecked(switchEchoCancellation,
            prefs.getBoolean(Constants.GCALL_PREF_ECHO_CANCELLATION, true));
        setSwitchChecked(switchAutoGainControl,
            prefs.getBoolean(Constants.GCALL_PREF_AUTO_GAIN, true));

        // Video quality
        String quality = prefs.getString(Constants.GCALL_PREF_VIDEO_QUALITY, "hd");
        if (rgVideoQuality != null) {
            if ("hd".equals(quality))   rgVideoQuality.check(R.id.rbGCQualityHD);
            else if ("sd".equals(quality)) rgVideoQuality.check(R.id.rbGCQualitySD);
            else                         rgVideoQuality.check(R.id.rbGCQualityAuto);
        }

        // Frame rate
        int fps = prefs.getInt(Constants.GCALL_PREF_FPS, 30);
        if (rgFrameRate != null) {
            if (fps >= 30)      rgFrameRate.check(R.id.rbGCFps30);
            else if (fps >= 24) rgFrameRate.check(R.id.rbGCFps24);
            else                rgFrameRate.check(R.id.rbGCFps15);
        }

        setSwitchChecked(switchFrontCameraDefault,
            prefs.getBoolean(Constants.GCALL_PREF_FRONT_CAMERA, true));

        // Routing
        String routing = prefs.getString(Constants.GCALL_PREF_ROUTING, "speaker");
        if (rgDefaultSpeaker != null) {
            if ("speaker".equals(routing))   rgDefaultSpeaker.check(R.id.rbGCRoutingSpeaker);
            else if ("earpiece".equals(routing)) rgDefaultSpeaker.check(R.id.rbGCRoutingEarpiece);
            else                             rgDefaultSpeaker.check(R.id.rbGCRoutingBluetooth);
        }
        setSwitchChecked(switchAutoSpeakerVideo,
            prefs.getBoolean(Constants.GCALL_PREF_AUTO_SPEAKER, true));

        // Notifications
        setSwitchChecked(switchMissedCallNotif,
            prefs.getBoolean(Constants.GCALL_PREF_MISSED_NOTIF, true));
        setSwitchChecked(switchLockScreenName,
            prefs.getBoolean(Constants.GCALL_PREF_LOCKSCREEN_NAME, true));
        setSwitchChecked(switchSilentRingtone,
            prefs.getBoolean(Constants.GCALL_PREF_SILENT_RINGTONE, false));

        // Privacy
        String allowFrom = prefs.getString(Constants.GCALL_PREF_ALLOW_FROM, "everyone");
        if (rgAllowCallFrom != null) {
            if ("everyone".equals(allowFrom))  rgAllowCallFrom.check(R.id.rbGCAllowEveryone);
            else if ("contacts".equals(allowFrom)) rgAllowCallFrom.check(R.id.rbGCAllowContacts);
            else                               rgAllowCallFrom.check(R.id.rbGCAllowNobody);
        }
        setSwitchChecked(switchShowCallStatus,
            prefs.getBoolean(Constants.GCALL_PREF_SHOW_STATUS, true));
        setSwitchChecked(switchBlockUnknown,
            prefs.getBoolean(Constants.GCALL_PREF_BLOCK_UNKNOWN, false));

        // Advanced
        int maxP = prefs.getInt(Constants.GCALL_PREF_MAX_PARTICIPANTS, 8);
        if (rgMaxParticipants != null) {
            if (maxP <= 2)      rgMaxParticipants.check(R.id.rbGCMax2);
            else if (maxP <= 4) rgMaxParticipants.check(R.id.rbGCMax4);
            else if (maxP <= 6) rgMaxParticipants.check(R.id.rbGCMax6);
            else                rgMaxParticipants.check(R.id.rbGCMax8);
        }
        setSwitchChecked(switchDataSaver,
            prefs.getBoolean(Constants.GCALL_PREF_DATA_SAVER, false));
        setSwitchChecked(switchKeepScreenOn,
            prefs.getBoolean(Constants.GCALL_PREF_KEEP_SCREEN_ON, true));
    }

    private void setupSaveOnChange() {
        // Audio switches — save immediately
        setOnCheckedChange(switchNoiseSuppression, Constants.GCALL_PREF_NOISE_SUPPRESSION);
        setOnCheckedChange(switchEchoCancellation, Constants.GCALL_PREF_ECHO_CANCELLATION);
        setOnCheckedChange(switchAutoGainControl,  Constants.GCALL_PREF_AUTO_GAIN);
        setOnCheckedChange(switchFrontCameraDefault, Constants.GCALL_PREF_FRONT_CAMERA);
        setOnCheckedChange(switchAutoSpeakerVideo, Constants.GCALL_PREF_AUTO_SPEAKER);
        setOnCheckedChange(switchMissedCallNotif,  Constants.GCALL_PREF_MISSED_NOTIF);
        setOnCheckedChange(switchLockScreenName,   Constants.GCALL_PREF_LOCKSCREEN_NAME);
        setOnCheckedChange(switchSilentRingtone,   Constants.GCALL_PREF_SILENT_RINGTONE);
        setOnCheckedChange(switchShowCallStatus,   Constants.GCALL_PREF_SHOW_STATUS);
        setOnCheckedChange(switchBlockUnknown,     Constants.GCALL_PREF_BLOCK_UNKNOWN);
        setOnCheckedChange(switchDataSaver,        Constants.GCALL_PREF_DATA_SAVER);
        setOnCheckedChange(switchKeepScreenOn,     Constants.GCALL_PREF_KEEP_SCREEN_ON);

        // Video quality radio
        if (rgVideoQuality != null) rgVideoQuality.setOnCheckedChangeListener((g, id) -> {
            String v = id == R.id.rbGCQualityHD ? "hd"
                     : id == R.id.rbGCQualitySD ? "sd" : "auto";
            prefs.edit().putString(Constants.GCALL_PREF_VIDEO_QUALITY, v).apply();
        });

        // Frame rate radio
        if (rgFrameRate != null) rgFrameRate.setOnCheckedChangeListener((g, id) -> {
            int fps = id == R.id.rbGCFps30 ? 30 : id == R.id.rbGCFps24 ? 24 : 15;
            prefs.edit().putInt(Constants.GCALL_PREF_FPS, fps).apply();
        });

        // Routing radio
        if (rgDefaultSpeaker != null) rgDefaultSpeaker.setOnCheckedChangeListener((g, id) -> {
            String r = id == R.id.rbGCRoutingSpeaker  ? "speaker"
                     : id == R.id.rbGCRoutingEarpiece ? "earpiece" : "bluetooth";
            prefs.edit().putString(Constants.GCALL_PREF_ROUTING, r).apply();
        });

        // Allow from radio
        if (rgAllowCallFrom != null) rgAllowCallFrom.setOnCheckedChangeListener((g, id) -> {
            String v = id == R.id.rbGCAllowEveryone ? "everyone"
                     : id == R.id.rbGCAllowContacts ? "contacts" : "nobody";
            prefs.edit().putString(Constants.GCALL_PREF_ALLOW_FROM, v).apply();
        });

        // Max participants radio
        if (rgMaxParticipants != null) rgMaxParticipants.setOnCheckedChangeListener((g, id) -> {
            int max = id == R.id.rbGCMax2 ? 2 : id == R.id.rbGCMax4 ? 4
                    : id == R.id.rbGCMax6 ? 6 : 8;
            prefs.edit().putInt(Constants.GCALL_PREF_MAX_PARTICIPANTS, max).apply();
        });
    }

    private void setOnCheckedChange(Switch sw, String key) {
        if (sw == null) return;
        sw.setOnCheckedChangeListener((v, checked) ->
            prefs.edit().putBoolean(key, checked).apply());
    }

    private void setSwitchChecked(Switch sw, boolean checked) {
        if (sw == null) return;
        sw.setChecked(checked);
    }

    @Override
    public void onBackPressed() {
        setResult(RESULT_OK);
        super.onBackPressed();
    }
}
