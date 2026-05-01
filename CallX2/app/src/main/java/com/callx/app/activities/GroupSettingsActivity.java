package com.callx.app.activities;

import android.content.SharedPreferences;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import com.callx.app.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.HashMap;
import java.util.Map;

/**
 * GroupSettingsActivity — Ultra-advanced comprehensive group settings.
 *
 * Features:
 *  1.  Mute notifications: off / 1 hour / 8 hours / 1 week / always
 *  2.  Custom notification tone (system ringtone picker)
 *  3.  Notification preview toggle
 *  4.  @Mention alert priority bypass (always notify even if muted)
 *  5.  DND schedule for this group (set quiet hours)
 *  6.  Auto-download images & videos (Wi-Fi only / always / off)
 *  7.  Save to gallery toggle
 *  8.  Disappearing messages timer (admin only)
 *  9.  Screenshot lock for this chat
 * 10.  Encryption info dialog
 * 11.  Admin permissions: who can send, who can edit info
 * 12.  Admin approval required to join
 * 13.  Only admins can add members toggle
 *
 * Settings are stored in:
 *  - Firebase:  groups/{groupId}/settings/{uid}/...  (per-user settings)
 *  - Firebase:  groups/{groupId}/groupSettings/...   (group-level, admin-controlled)
 *  - SharedPrefs: "group_settings_{groupId}"         (offline cache)
 */
public class GroupSettingsActivity extends AppCompatActivity {

    public static final String EXTRA_GROUP_ID   = "groupId";
    public static final String EXTRA_GROUP_NAME = "groupName";

    private static final String PREF_PREFIX = "group_settings_";

    private String groupId, groupName, currentUid;
    private boolean isAdmin = false;

    // Prefs
    private SharedPreferences prefs;

    // Views — Notifications
    private TextView    tvMuteStatus, tvNotifTone, tvDNDStatus;
    private SwitchCompat swNotifPreview, swMentionAlert;

    // Views — Media
    private SwitchCompat swAutoDlImages, swAutoDlVideos, swSaveGallery;

    // Views — Permissions (admin)
    private View    labelPermissions, cardPermissions;
    private TextView tvSendPerm, tvEditPerm;
    private SwitchCompat swApprovalRequired, swAdminAddOnly;

    // Views — Privacy
    private TextView    tvDisappearing;
    private SwitchCompat swScreenshotLock;

    // Ringtone picker
    private ActivityResultLauncher<Void> ringtonePicker;
    private String selectedToneUri = null;

    // ── Lifecycle ─────────────────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_group_settings);

        groupId   = getIntent().getStringExtra(EXTRA_GROUP_ID);
        groupName = getIntent().getStringExtra(EXTRA_GROUP_NAME);

        if (groupId == null || FirebaseAuth.getInstance().getCurrentUser() == null) {
            finish();
            return;
        }
        currentUid = FirebaseAuth.getInstance().getCurrentUser().getUid();
        prefs = getSharedPreferences(PREF_PREFIX + groupId, MODE_PRIVATE);

        bindViews();
        setupToolbar();
        setupRingtonePicker();
        loadSavedSettings();
        setupClickListeners();
        setupSwitchListeners();
        loadFirebaseSettings();
        checkAdminStatus();
    }

    // ── View Binding ──────────────────────────────────────────────────────
    private void bindViews() {
        tvMuteStatus        = findViewById(R.id.tv_mute_status);
        tvNotifTone         = findViewById(R.id.tv_notif_tone);
        tvDNDStatus         = findViewById(R.id.tv_dnd_status);
        swNotifPreview      = findViewById(R.id.sw_notif_preview);
        swMentionAlert      = findViewById(R.id.sw_mention_alert);
        swAutoDlImages      = findViewById(R.id.sw_auto_dl_images);
        swAutoDlVideos      = findViewById(R.id.sw_auto_dl_videos);
        swSaveGallery       = findViewById(R.id.sw_save_gallery);
        labelPermissions    = findViewById(R.id.label_permissions);
        cardPermissions     = findViewById(R.id.card_permissions);
        tvSendPerm          = findViewById(R.id.tv_send_perm);
        tvEditPerm          = findViewById(R.id.tv_edit_perm);
        swApprovalRequired  = findViewById(R.id.sw_approval_required);
        swAdminAddOnly      = findViewById(R.id.sw_admin_add_only);
        tvDisappearing      = findViewById(R.id.tv_disappearing);
        swScreenshotLock    = findViewById(R.id.sw_screenshot_lock);
    }

    // ── Toolbar ───────────────────────────────────────────────────────────
    private void setupToolbar() {
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(groupName != null ? groupName + " Settings" : "Group Settings");
        }
        toolbar.setNavigationOnClickListener(v -> finish());
    }

    // ── Ringtone Picker ───────────────────────────────────────────────────
    private void setupRingtonePicker() {
        // We use Intent for ringtone picking (ACTION_RINGTONE_PICKER)
        ringtonePicker = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Uri uri = result.getData().getParcelableExtra(
                                RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
                        if (uri != null) {
                            selectedToneUri = uri.toString();
                            String name = RingtoneManager.getRingtone(this, uri)
                                    .getTitle(this);
                            tvNotifTone.setText(name != null ? name : "Custom");
                            prefs.edit().putString("notif_tone", selectedToneUri).apply();
                            saveUserSetting("notifTone", selectedToneUri);
                        } else {
                            // "None" selected
                            tvNotifTone.setText("None");
                            prefs.edit().remove("notif_tone").apply();
                            saveUserSetting("notifTone", "none");
                        }
                    }
                }
        );
    }

    // ── Load saved settings from SharedPreferences ─────────────────────────
    private void loadSavedSettings() {
        // Mute status
        long muteUntil = prefs.getLong("mute_until", 0);
        updateMuteStatusView(muteUntil);

        // Tone
        String tone = prefs.getString("notif_tone", null);
        tvNotifTone.setText(tone == null ? "Default" : getToneNameFromUri(tone));

        // DND
        String dndFrom = prefs.getString("dnd_from", null);
        String dndTo   = prefs.getString("dnd_to", null);
        if (dndFrom != null && dndTo != null)
            tvDNDStatus.setText(dndFrom + " – " + dndTo);
        else
            tvDNDStatus.setText("Off");

        // Switches
        swNotifPreview.setChecked(prefs.getBoolean("notif_preview", true));
        swMentionAlert.setChecked(prefs.getBoolean("mention_alert", true));
        swAutoDlImages.setChecked(prefs.getBoolean("auto_dl_images", true));
        swAutoDlVideos.setChecked(prefs.getBoolean("auto_dl_videos", false));
        swSaveGallery.setChecked(prefs.getBoolean("save_gallery", false));
        swScreenshotLock.setChecked(prefs.getBoolean("screenshot_lock", false));
    }

    // ── Click listeners ───────────────────────────────────────────────────
    private void setupClickListeners() {
        // Mute
        findViewById(R.id.row_mute).setOnClickListener(v -> showMuteDialog());

        // Notification tone
        findViewById(R.id.row_notif_tone).setOnClickListener(v -> openRingtonePicker());

        // DND schedule
        findViewById(R.id.row_dnd_schedule).setOnClickListener(v -> showDNDDialog());

        // Disappearing messages
        findViewById(R.id.row_disappearing).setOnClickListener(v -> showDisappearingDialog());

        // Encryption info
        findViewById(R.id.row_encryption).setOnClickListener(v -> showEncryptionInfo());

        // Permissions (admin only)
        if (cardPermissions != null) {
            View rowSend = cardPermissions.findViewById(R.id.row_send_messages);
            View rowEdit = cardPermissions.findViewById(R.id.row_edit_info);
            if (rowSend != null) rowSend.setOnClickListener(v -> showSendPermDialog());
            if (rowEdit != null) rowEdit.setOnClickListener(v -> showEditPermDialog());
        }
    }

    // ── Switch listeners ──────────────────────────────────────────────────
    private void setupSwitchListeners() {
        swNotifPreview.setOnCheckedChangeListener((btn, checked) -> {
            prefs.edit().putBoolean("notif_preview", checked).apply();
            saveUserSetting("notifPreview", checked ? "1" : "0");
        });

        swMentionAlert.setOnCheckedChangeListener((btn, checked) -> {
            prefs.edit().putBoolean("mention_alert", checked).apply();
            saveUserSetting("mentionAlert", checked ? "1" : "0");
        });

        swAutoDlImages.setOnCheckedChangeListener((btn, checked) -> {
            prefs.edit().putBoolean("auto_dl_images", checked).apply();
            saveUserSetting("autoDlImages", checked ? "1" : "0");
        });

        swAutoDlVideos.setOnCheckedChangeListener((btn, checked) -> {
            prefs.edit().putBoolean("auto_dl_videos", checked).apply();
            saveUserSetting("autoDlVideos", checked ? "1" : "0");
        });

        swSaveGallery.setOnCheckedChangeListener((btn, checked) -> {
            prefs.edit().putBoolean("save_gallery", checked).apply();
            saveUserSetting("saveGallery", checked ? "1" : "0");
        });

        swScreenshotLock.setOnCheckedChangeListener((btn, checked) -> {
            prefs.edit().putBoolean("screenshot_lock", checked).apply();
            saveUserSetting("screenshotLock", checked ? "1" : "0");
            if (checked)
                Toast.makeText(this, "Screenshots disabled for this group", Toast.LENGTH_SHORT).show();
        });

        // Admin-only switches
        swApprovalRequired.setOnCheckedChangeListener((btn, checked) -> {
            if (!isAdmin) { swApprovalRequired.setChecked(!checked); return; }
            saveGroupSetting("approvalRequired", checked ? "1" : "0");
        });

        swAdminAddOnly.setOnCheckedChangeListener((btn, checked) -> {
            if (!isAdmin) { swAdminAddOnly.setChecked(!checked); return; }
            saveGroupSetting("adminAddOnly", checked ? "1" : "0");
        });
    }

    // ── Mute Dialog ───────────────────────────────────────────────────────
    private void showMuteDialog() {
        String[] options = {"Not muted", "1 hour", "8 hours", "1 week", "Always"};
        long[] durMs = {0, 3_600_000L, 28_800_000L, 604_800_000L, Long.MAX_VALUE};

        // Current state
        long muteUntil = prefs.getLong("mute_until", 0);
        int currentSel = 0;
        if (muteUntil == Long.MAX_VALUE) currentSel = 4;
        else if (muteUntil > System.currentTimeMillis()) {
            long remaining = muteUntil - System.currentTimeMillis();
            if (remaining > 300_000_000L) currentSel = 3;
            else if (remaining > 20_000_000L) currentSel = 2;
            else currentSel = 1;
        }

        new AlertDialog.Builder(this)
                .setTitle("Mute Notifications")
                .setSingleChoiceItems(options, currentSel, null)
                .setPositiveButton("OK", (d, w) -> {
                    int sel = ((AlertDialog) d).getListView().getCheckedItemPosition();
                    if (sel < 0) sel = 0;
                    long until;
                    if (sel == 0) {
                        until = 0; // unmute
                    } else if (sel == 4) {
                        until = Long.MAX_VALUE;
                    } else {
                        until = System.currentTimeMillis() + durMs[sel];
                    }
                    prefs.edit().putLong("mute_until", until).apply();
                    updateMuteStatusView(until);
                    saveUserSetting("muteUntil", String.valueOf(until));
                    // Also update Firebase group mutedBy
                    if (until > 0) {
                        FirebaseUtils.getGroupsRef().child(groupId)
                                .child("mutedBy").child(currentUid).setValue(true);
                    } else {
                        FirebaseUtils.getGroupsRef().child(groupId)
                                .child("mutedBy").child(currentUid).removeValue();
                    }
                    Toast.makeText(this, sel == 0 ? "Unmuted" : "Muted: " + options[sel],
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void updateMuteStatusView(long muteUntil) {
        if (muteUntil == 0) {
            tvMuteStatus.setText("Not muted");
        } else if (muteUntil == Long.MAX_VALUE) {
            tvMuteStatus.setText("Always muted");
        } else {
            long remaining = muteUntil - System.currentTimeMillis();
            if (remaining <= 0) {
                tvMuteStatus.setText("Not muted");
                prefs.edit().putLong("mute_until", 0).apply();
            } else {
                long hours = remaining / 3_600_000L;
                if (hours >= 24) {
                    tvMuteStatus.setText("Muted for " + (hours / 24) + " day(s)");
                } else if (hours >= 1) {
                    tvMuteStatus.setText("Muted for " + hours + " hour(s)");
                } else {
                    tvMuteStatus.setText("Muted for " + (remaining / 60_000L) + " min(s)");
                }
            }
        }
    }

    // ── Ringtone picker ───────────────────────────────────────────────────
    private void openRingtonePicker() {
        android.content.Intent intent = new android.content.Intent(
                RingtoneManager.ACTION_RINGTONE_PICKER);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Select Notification Tone");
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true);
        String savedUri = prefs.getString("notif_tone", null);
        if (savedUri != null)
            intent.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                    Uri.parse(savedUri));
        ringtonePicker.launch(null);
    }

    private String getToneNameFromUri(String uriStr) {
        if ("none".equals(uriStr)) return "None";
        try {
            android.media.Ringtone r = RingtoneManager.getRingtone(this, Uri.parse(uriStr));
            if (r != null) return r.getTitle(this);
        } catch (Exception ignored) {}
        return "Custom";
    }

    // ── DND Schedule Dialog ───────────────────────────────────────────────
    private void showDNDDialog() {
        String[] opts = {"Off", "10pm – 7am", "11pm – 8am", "Custom…"};
        new AlertDialog.Builder(this)
                .setTitle("DND Schedule (this group)")
                .setItems(opts, (d, which) -> {
                    switch (which) {
                        case 0:
                            tvDNDStatus.setText("Off");
                            prefs.edit().remove("dnd_from").remove("dnd_to").apply();
                            saveUserSetting("dndFrom", "");
                            saveUserSetting("dndTo", "");
                            break;
                        case 1:
                            setDND("22:00", "07:00");
                            break;
                        case 2:
                            setDND("23:00", "08:00");
                            break;
                        case 3:
                            showCustomDNDDialog();
                            break;
                    }
                })
                .show();
    }

    private void setDND(String from, String to) {
        tvDNDStatus.setText(from + " – " + to);
        prefs.edit().putString("dnd_from", from).putString("dnd_to", to).apply();
        saveUserSetting("dndFrom", from);
        saveUserSetting("dndTo", to);
        Toast.makeText(this, "DND set: " + from + " – " + to, Toast.LENGTH_SHORT).show();
    }

    private void showCustomDNDDialog() {
        android.app.TimePickerDialog fromPicker = new android.app.TimePickerDialog(this,
                (view, hour, min) -> {
                    final String from = String.format(java.util.Locale.getDefault(),
                            "%02d:%02d", hour, min);
                    android.app.TimePickerDialog toPicker = new android.app.TimePickerDialog(this,
                            (v2, h2, m2) -> {
                                String to = String.format(java.util.Locale.getDefault(),
                                        "%02d:%02d", h2, m2);
                                setDND(from, to);
                            }, 7, 0, true);
                    toPicker.setTitle("DND End Time");
                    toPicker.show();
                }, 22, 0, true);
        fromPicker.setTitle("DND Start Time");
        fromPicker.show();
    }

    // ── Disappearing Messages Dialog ──────────────────────────────────────
    private void showDisappearingDialog() {
        String[] opts = {"Off", "24 hours", "7 days", "30 days", "90 days"};
        long[] msOpts = {0, 86_400_000L, 604_800_000L, 2_592_000_000L, 7_776_000_000L};

        long currentMs = prefs.getLong("disappearing_ms", 0);
        int currentSel = 0;
        for (int i = 0; i < msOpts.length; i++)
            if (msOpts[i] == currentMs) { currentSel = i; break; }

        new AlertDialog.Builder(this)
                .setTitle("Disappearing Messages")
                .setSingleChoiceItems(opts, currentSel, null)
                .setPositiveButton("Set", (d, w) -> {
                    int sel = ((AlertDialog) d).getListView().getCheckedItemPosition();
                    if (sel < 0) sel = 0;
                    long ms = msOpts[sel];
                    prefs.edit().putLong("disappearing_ms", ms).apply();
                    tvDisappearing.setText(opts[sel]);
                    // Persist to Firebase (group-level, admin controlled or per-user)
                    if (isAdmin) {
                        FirebaseUtils.getGroupsRef().child(groupId)
                                .child("disappearingMs").setValue(ms == 0 ? null : ms);
                    }
                    saveUserSetting("disappearingMs", String.valueOf(ms));
                    Toast.makeText(this,
                            ms == 0 ? "Disappearing messages off"
                                    : "Messages disappear after " + opts[sel],
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── Encryption Info ───────────────────────────────────────────────────
    private void showEncryptionInfo() {
        new AlertDialog.Builder(this)
                .setTitle("End-to-End Encryption")
                .setMessage("Messages and calls in this group are secured with end-to-end encryption. "
                        + "Only members of this group can read or hear them. "
                        + "Not even CallX can access your messages.\n\n"
                        + "Group ID: " + groupId)
                .setPositiveButton("OK", null)
                .show();
    }

    // ── Permission dialogs (admin) ─────────────────────────────────────────
    private void showSendPermDialog() {
        if (!isAdmin) {
            Toast.makeText(this, "Admin permissions required", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] opts = {"All Members", "Admins Only"};
        String current = tvSendPerm.getText().toString();
        int sel = "Admins Only".equals(current) ? 1 : 0;
        new AlertDialog.Builder(this)
                .setTitle("Who Can Send Messages")
                .setSingleChoiceItems(opts, sel, null)
                .setPositiveButton("OK", (d, w) -> {
                    int s = ((AlertDialog) d).getListView().getCheckedItemPosition();
                    if (s < 0) s = 0;
                    tvSendPerm.setText(opts[s]);
                    saveGroupSetting("sendPermission", s == 1 ? "admins" : "all");
                    Toast.makeText(this, "Permission updated", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showEditPermDialog() {
        if (!isAdmin) {
            Toast.makeText(this, "Admin permissions required", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] opts = {"All Members", "Admins Only"};
        String current = tvEditPerm.getText().toString();
        int sel = "All Members".equals(current) ? 0 : 1;
        new AlertDialog.Builder(this)
                .setTitle("Who Can Edit Group Info")
                .setSingleChoiceItems(opts, sel, null)
                .setPositiveButton("OK", (d, w) -> {
                    int s = ((AlertDialog) d).getListView().getCheckedItemPosition();
                    if (s < 0) s = 1;
                    tvEditPerm.setText(opts[s]);
                    saveGroupSetting("editPermission", s == 0 ? "all" : "admins");
                    Toast.makeText(this, "Permission updated", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── Firebase helpers ──────────────────────────────────────────────────
    /**
     * Save per-user setting to Firebase:
     * groups/{groupId}/userSettings/{uid}/{key} = value
     */
    private void saveUserSetting(String key, Object value) {
        FirebaseUtils.getGroupsRef()
                .child(groupId)
                .child("userSettings")
                .child(currentUid)
                .child(key)
                .setValue(value);
    }

    /**
     * Save group-level setting (admin only) to Firebase:
     * groups/{groupId}/groupSettings/{key} = value
     */
    private void saveGroupSetting(String key, Object value) {
        FirebaseUtils.getGroupsRef()
                .child(groupId)
                .child("groupSettings")
                .child(key)
                .setValue(value);
    }

    /**
     * Load settings from Firebase on startup.
     */
    private void loadFirebaseSettings() {
        // Load user-specific settings from Firebase
        FirebaseUtils.getGroupsRef()
                .child(groupId)
                .child("userSettings")
                .child(currentUid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot snap) {
                        // Mute
                        String muteStr = snap.child("muteUntil").getValue(String.class);
                        if (muteStr != null) {
                            try {
                                long until = Long.parseLong(muteStr);
                                prefs.edit().putLong("mute_until", until).apply();
                                updateMuteStatusView(until);
                            } catch (NumberFormatException ignored) {}
                        }
                        // Disappearing
                        String dispStr = snap.child("disappearingMs").getValue(String.class);
                        if (dispStr != null) {
                            try {
                                long ms = Long.parseLong(dispStr);
                                prefs.edit().putLong("disappearing_ms", ms).apply();
                                String[] labels = {"Off","24 hours","7 days","30 days","90 days"};
                                long[] vals = {0,86400000L,604800000L,2592000000L,7776000000L};
                                for (int i = 0; i < vals.length; i++)
                                    if (vals[i] == ms) { tvDisappearing.setText(labels[i]); break; }
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                    @Override public void onCancelled(DatabaseError e) {}
                });

        // Load group-level settings
        FirebaseUtils.getGroupsRef()
                .child(groupId)
                .child("groupSettings")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot snap) {
                        String sendPerm = snap.child("sendPermission").getValue(String.class);
                        if ("admins".equals(sendPerm)) tvSendPerm.setText("Admins Only");
                        else tvSendPerm.setText("All Members");

                        String editPerm = snap.child("editPermission").getValue(String.class);
                        if ("all".equals(editPerm)) tvEditPerm.setText("All Members");
                        else tvEditPerm.setText("Admins Only");

                        String approvalStr = snap.child("approvalRequired").getValue(String.class);
                        swApprovalRequired.setChecked("1".equals(approvalStr));

                        String adminAddStr = snap.child("adminAddOnly").getValue(String.class);
                        swAdminAddOnly.setChecked("1".equals(adminAddStr));
                    }
                    @Override public void onCancelled(DatabaseError e) {}
                });
    }

    private void checkAdminStatus() {
        FirebaseUtils.getGroupMembersRef(groupId).child(currentUid).child("role")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot snap) {
                        isAdmin = "admin".equals(snap.getValue(String.class));
                        if (isAdmin) {
                            labelPermissions.setVisibility(View.VISIBLE);
                            cardPermissions.setVisibility(View.VISIBLE);
                        }
                    }
                    @Override public void onCancelled(DatabaseError e) {}
                });
        // Also check admins map
        FirebaseUtils.getGroupsRef().child(groupId).child("admins").child(currentUid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot snap) {
                        if (Boolean.TRUE.equals(snap.getValue(Boolean.class))) {
                            isAdmin = true;
                            labelPermissions.setVisibility(View.VISIBLE);
                            cardPermissions.setVisibility(View.VISIBLE);
                        }
                    }
                    @Override public void onCancelled(DatabaseError e) {}
                });
    }
}
