package com.callx.app.activities;

import android.app.Activity;
import android.content.Intent;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.callx.app.chat.R;
import com.callx.app.chat.databinding.ActivityNotificationSettingsBinding;
import com.callx.app.managers.ChatNotificationManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * NotificationSettingsActivity — Per-chat notification settings screen.
 *
 * Sections:
 *   1. Mute — Off / 1hr / 8hr / 1wk / Always
 *   2. Ringtone — system ringtone picker
 *   3. Vibration — Off / Default / Gentle / Strong (with preview tap)
 *   4. LED Color — Default / Purple / Red / Green / Blue / None
 *   5. Show Preview — toggle (show message text vs "New message")
 *   6. Reset to defaults
 *
 * Launch from ChatActivity info/settings menu:
 *   Intent i = new Intent(this, NotificationSettingsActivity.class);
 *   i.putExtra(NotificationSettingsActivity.EXTRA_CHAT_ID,      chatId);
 *   i.putExtra(NotificationSettingsActivity.EXTRA_PARTNER_NAME, partnerName);
 *   startActivity(i);
 */
public class NotificationSettingsActivity extends AppCompatActivity {

    public static final String EXTRA_CHAT_ID      = "chatId";
    public static final String EXTRA_PARTNER_NAME = "partnerName";

    private static final int REQ_RINGTONE = 601;

    private ActivityNotificationSettingsBinding binding;
    private ChatNotificationManager             mgr;
    private String                              chatId;
    private String                              partnerName;

    private ActivityResultLauncher<Intent> ringtonePicker;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityNotificationSettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        chatId      = getIntent().getStringExtra(EXTRA_CHAT_ID);
        partnerName = getIntent().getStringExtra(EXTRA_PARTNER_NAME);
        mgr         = new ChatNotificationManager(this, chatId);

        setupRingtonePicker();
        setupToolbar();
        bindCurrentValues();
        setupClickListeners();
    }

    // ─────────────────────────────────────────────────────────────────────
    // SETUP
    // ─────────────────────────────────────────────────────────────────────

    private void setupRingtonePicker() {
        ringtonePicker = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
                    String uriStr = (uri != null) ? uri.toString() : ChatNotificationManager.RINGTONE_SILENT;
                    mgr.setRingtone(uriStr);
                    binding.tvRingtoneValue.setText(mgr.getRingtoneName());
                }
            }
        );
    }

    private void setupToolbar() {
        binding.tvTitle.setText(partnerName != null ? partnerName : "Notifications");
        binding.btnBack.setOnClickListener(v -> finish());
    }

    private void bindCurrentValues() {
        // Mute
        binding.tvMuteValue.setText(mgr.getMuteLabel());
        binding.switchMute.setChecked(mgr.isMuted());

        // Ringtone
        binding.tvRingtoneValue.setText(mgr.getRingtoneName());

        // Vibration
        binding.tvVibrateValue.setText(mgr.getVibrationLabel());

        // LED
        binding.tvLedValue.setText(mgr.getLedLabel());

        // Preview
        binding.switchPreview.setChecked(mgr.isShowPreview());
        binding.tvPreviewSub.setText(mgr.isShowPreview()
                ? "Message text shown in notification"
                : "\"New message\" shown (private mode)");
    }

    // ─────────────────────────────────────────────────────────────────────
    // CLICK LISTENERS
    // ─────────────────────────────────────────────────────────────────────

    private void setupClickListeners() {

        // ── Mute toggle ──────────────────────────────────────────────────
        binding.switchMute.setOnCheckedChangeListener((btn, checked) -> {
            if (checked) showMuteDurationPicker();
            else {
                mgr.clearMute();
                binding.tvMuteValue.setText(mgr.getMuteLabel());
            }
        });
        binding.rowMute.setOnClickListener(v -> {
            if (mgr.isMuted()) {
                mgr.clearMute();
                binding.switchMute.setChecked(false);
                binding.tvMuteValue.setText(mgr.getMuteLabel());
            } else {
                showMuteDurationPicker();
            }
        });

        // ── Ringtone ─────────────────────────────────────────────────────
        binding.rowRingtone.setOnClickListener(v -> openRingtonePicker());

        // ── Vibration ────────────────────────────────────────────────────
        binding.rowVibrate.setOnClickListener(v -> showVibrationPicker());

        // ── LED Color ────────────────────────────────────────────────────
        binding.rowLed.setOnClickListener(v -> showLedColorPicker());

        // ── Preview toggle ───────────────────────────────────────────────
        binding.switchPreview.setOnCheckedChangeListener((btn, checked) -> {
            mgr.setShowPreview(checked);
            binding.tvPreviewSub.setText(checked
                    ? "Message text shown in notification"
                    : "\"New message\" shown (private mode)");
        });
        binding.rowPreview.setOnClickListener(v ->
                binding.switchPreview.setChecked(!binding.switchPreview.isChecked()));

        // ── Reset ────────────────────────────────────────────────────────
        binding.btnReset.setOnClickListener(v -> {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Reset Notifications")
                    .setMessage("Reset all notification settings for this chat to defaults?")
                    .setPositiveButton("Reset", (d, w) -> {
                        mgr.resetToDefaults();
                        bindCurrentValues();
                        Toast.makeText(this, "Reset to defaults", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // DIALOGS
    // ─────────────────────────────────────────────────────────────────────

    private void showMuteDurationPicker() {
        String[] labels = {"1 hour", "8 hours", "1 week", "Always"};
        long[]   values = {
                ChatNotificationManager.MUTE_1HR,
                ChatNotificationManager.MUTE_8HR,
                ChatNotificationManager.MUTE_1WK,
                ChatNotificationManager.MUTE_ALWAYS
        };

        new MaterialAlertDialogBuilder(this)
                .setTitle("Mute notifications for...")
                .setItems(labels, (d, which) -> {
                    mgr.setMute(values[which]);
                    binding.switchMute.setChecked(true);
                    binding.tvMuteValue.setText(mgr.getMuteLabel());
                    Toast.makeText(this, "Muted for " + labels[which], Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", (d, w) ->
                        binding.switchMute.setChecked(mgr.isMuted()))
                .show();
    }

    private void openRingtonePicker() {
        Intent intent = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE,    RingtoneManager.TYPE_NOTIFICATION);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE,   "Select ringtone for " + partnerName);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true);

        // Pre-select current ringtone
        String current = mgr.getRingtoneUriString();
        if (!ChatNotificationManager.RINGTONE_SILENT.equals(current)
                && !ChatNotificationManager.RINGTONE_DEFAULT.equals(current)) {
            intent.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(current));
        }
        ringtonePicker.launch(intent);
    }

    private void showVibrationPicker() {
        String[] labels = {"Off", "Default", "Gentle (short bursts)", "Strong (long pulses)"};
        int[]    values = {
                ChatNotificationManager.VIBRATE_OFF,
                ChatNotificationManager.VIBRATE_DEFAULT,
                ChatNotificationManager.VIBRATE_GENTLE,
                ChatNotificationManager.VIBRATE_STRONG
        };
        int current = mgr.getVibrationPattern();

        new MaterialAlertDialogBuilder(this)
                .setTitle("Vibration")
                .setSingleChoiceItems(labels, current, (d, which) -> {
                    mgr.setVibrationPattern(values[which]);
                    binding.tvVibrateValue.setText(mgr.getVibrationLabel());
                    // Preview the vibration
                    mgr.previewVibration();
                    d.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showLedColorPicker() {
        String[] labels = {"Default", "Purple (Brand)", "Red", "Green", "Blue", "None"};
        int[]    values = {
                ChatNotificationManager.LED_DEFAULT,
                ChatNotificationManager.LED_BRAND,
                ChatNotificationManager.LED_RED,
                ChatNotificationManager.LED_GREEN,
                ChatNotificationManager.LED_BLUE,
                ChatNotificationManager.LED_NONE
        };
        int current = mgr.getLedOption();

        new MaterialAlertDialogBuilder(this)
                .setTitle("Notification LED")
                .setSingleChoiceItems(labels, current, (d, which) -> {
                    mgr.setLedColor(values[which]);
                    binding.tvLedValue.setText(mgr.getLedLabel());
                    d.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
