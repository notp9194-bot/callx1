package com.callx.app.activities;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import com.callx.app.R;
import com.callx.app.databinding.ActivityPrivacySecurityBinding;
import com.callx.app.utils.AppLockManager;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.HashMap;
import java.util.Map;

public class PrivacySecurityActivity extends AppCompatActivity {

    private ActivityPrivacySecurityBinding binding;
    private AppLockManager lockManager;
    private String myUid;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityPrivacySecurityBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.toolbar.setNavigationOnClickListener(v -> finish());

        lockManager = new AppLockManager(this);
        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;

        setupAppLock();
        setupPrivacy();
        setupSecurity();
    }

    // ── App Lock ──────────────────────────────────────────────────────────

    private void setupAppLock() {
        String currentLock = lockManager.getLockType();

        // Row: App Lock (shows current mode)
        configureRow(binding.rowAppLock.getRoot(), R.drawable.ic_phone,
            "App Lock",
            "Current: " + lockManager.getLockTypeLabel());
        binding.rowAppLock.getRoot().setOnClickListener(v -> showLockTypeChooser());

        // Row: Fingerprint
        boolean fingerprintSet = "fingerprint".equals(currentLock);
        configureRow(binding.rowFingerprint.getRoot(), R.drawable.ic_person,
            "Fingerprint / Face Unlock",
            fingerprintSet ? "Active" : "Tap to enable");
        binding.rowFingerprint.getRoot().setOnClickListener(v -> launchAppLock(AppLockActivity.MODE_FINGERPRINT));

        // Row: PIN Lock
        boolean pinSet = "pin".equals(currentLock);
        configureRow(binding.rowPinLock.getRoot(), R.drawable.ic_person_add,
            "PIN Lock",
            pinSet ? "Active (" + AppLockManager.PIN_LENGTH + "-digit)" : "Tap to set PIN");
        binding.rowPinLock.getRoot().setOnClickListener(v -> launchAppLock(AppLockActivity.MODE_PIN));

        // Row: Pattern Lock
        boolean patternSet = "pattern".equals(currentLock);
        configureRow(binding.rowPatternLock.getRoot(), R.drawable.ic_group,
            "Pattern Lock",
            patternSet ? "Active" : "Tap to set pattern");
        binding.rowPatternLock.getRoot().setOnClickListener(v -> launchAppLock(AppLockActivity.MODE_PATTERN));
    }

    private void showLockTypeChooser() {
        String[] options = {"None", "PIN", "Pattern", "Fingerprint / Face"};
        String[] types   = {AppLockManager.NONE, AppLockManager.PIN,
                            AppLockManager.PATTERN, AppLockManager.FINGERPRINT};
        int current = indexOfType(lockManager.getLockType(), types);

        new AlertDialog.Builder(this)
            .setTitle("App Lock")
            .setSingleChoiceItems(options, current, (d, which) -> {
                d.dismiss();
                if (which == 0) {
                    lockManager.clearLock();
                    refreshAppLockSection();
                    Toast.makeText(this, "App lock disabled", Toast.LENGTH_SHORT).show();
                } else {
                    launchAppLock(types[which]);
                }
            })
            .show();
    }

    private int indexOfType(String type, String[] types) {
        for (int i = 0; i < types.length; i++) if (types[i].equals(type)) return i;
        return 0;
    }

    private void launchAppLock(String mode) {
        Intent i = new Intent(this, AppLockActivity.class);
        i.putExtra(AppLockActivity.EXTRA_MODE, mode);
        i.putExtra(AppLockActivity.EXTRA_SETUP, true);
        startActivityForResult(i, 200);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 200) refreshAppLockSection();
    }

    private void refreshAppLockSection() {
        setupAppLock(); // reload UI after lock change
    }

    // ── Privacy ───────────────────────────────────────────────────────────

    private void setupPrivacy() {
        // Read receipts toggle
        configureToggleRow(binding.rowReadReceipts.getRoot(), R.drawable.ic_send,
            "Read Receipts", "Blue double-tick when message is read");
        SwitchCompat swReceipts = binding.rowReadReceipts.getRoot().findViewById(R.id.sw_toggle);
        loadBoolPref("readReceipts", true, swReceipts);
        swReceipts.setOnCheckedChangeListener((btn, on) -> saveBoolPref("readReceipts", on));

        // Last seen
        String[] lsOpts  = {"Everyone", "My Contacts", "Nobody"};
        configureRow(binding.rowLastSeen.getRoot(), R.drawable.ic_status_notification,
            "Last Seen", "Everyone");
        loadStringPrefAsSubtitle("lastSeen", "Everyone", binding.rowLastSeen.getRoot());
        binding.rowLastSeen.getRoot().setOnClickListener(v -> {
            String cur = loadStringPref("lastSeen", "Everyone");
            int curIdx = indexOfStr(cur, lsOpts);
            new AlertDialog.Builder(this)
                .setTitle("Last Seen")
                .setSingleChoiceItems(lsOpts, curIdx, (d, w) -> {
                    d.dismiss();
                    saveStringPref("lastSeen", lsOpts[w]);
                    updateSubtitle(binding.rowLastSeen.getRoot(), lsOpts[w]);
                }).show();
        });

        // Profile photo visibility
        String[] ppOpts = {"Everyone", "My Contacts", "Nobody"};
        configureRow(binding.rowProfilePhoto.getRoot(), R.drawable.ic_gallery,
            "Profile Photo", "Everyone");
        loadStringPrefAsSubtitle("profilePhoto", "Everyone", binding.rowProfilePhoto.getRoot());
        binding.rowProfilePhoto.getRoot().setOnClickListener(v -> {
            String cur = loadStringPref("profilePhoto", "Everyone");
            int curIdx = indexOfStr(cur, ppOpts);
            new AlertDialog.Builder(this)
                .setTitle("Profile Photo")
                .setSingleChoiceItems(ppOpts, curIdx, (d, w) -> {
                    d.dismiss();
                    saveStringPref("profilePhoto", ppOpts[w]);
                    updateSubtitle(binding.rowProfilePhoto.getRoot(), ppOpts[w]);
                }).show();
        });

        // Screenshot lock
        configureToggleRow(binding.rowScreenshotLock.getRoot(), R.drawable.ic_camera,
            "Screenshot Lock", "Block screenshots in app");
        SwitchCompat swScreenshot = binding.rowScreenshotLock.getRoot().findViewById(R.id.sw_toggle);
        loadBoolPref("screenshotLock", false, swScreenshot);
        swScreenshot.setOnCheckedChangeListener((btn, on) -> {
            saveBoolPref("screenshotLock", on);
            applyScreenshotLock(on);
        });
        // Apply immediately on load
        applyScreenshotLock(loadBoolPref("screenshotLock", false));
    }

    private void applyScreenshotLock(boolean on) {
        if (on) {
            getWindow().addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE);
        } else {
            getWindow().clearFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE);
        }
    }

    // ── Security ──────────────────────────────────────────────────────────

    private void setupSecurity() {
        // Two-step verification
        configureRow(binding.rowTwoStep.getRoot(), R.drawable.ic_send_fill,
            "Two-Step Verification", "Extra PIN at registration");
        binding.rowTwoStep.getRoot().setOnClickListener(v ->
            Toast.makeText(this,
                "Two-step verification — coming soon", Toast.LENGTH_SHORT).show());

        // Blocked contacts
        configureRow(binding.rowBlocked.getRoot(), R.drawable.ic_phone_off,
            "Blocked Contacts", "Manage blocked users");
        binding.rowBlocked.getRoot().setOnClickListener(v ->
            startActivity(new Intent(this, RequestsActivity.class)));
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private void configureRow(View row, int iconRes, String title, String subtitle) {
        ((ImageView) row.findViewById(R.id.iv_menu_icon)).setImageResource(iconRes);
        ((TextView)  row.findViewById(R.id.tv_menu_title)).setText(title);
        TextView sub = row.findViewById(R.id.tv_menu_subtitle);
        if (subtitle != null && !subtitle.isEmpty()) {
            sub.setText(subtitle); sub.setVisibility(View.VISIBLE);
        } else {
            sub.setVisibility(View.GONE);
        }
    }

    private void configureToggleRow(View row, int iconRes, String title, String subtitle) {
        configureRow(row, iconRes, title, subtitle);
    }

    private void updateSubtitle(View row, String text) {
        TextView sub = row.findViewById(R.id.tv_menu_subtitle);
        sub.setText(text); sub.setVisibility(View.VISIBLE);
    }

    private int indexOfStr(String s, String[] arr) {
        for (int i = 0; i < arr.length; i++) if (arr[i].equals(s)) return i;
        return 0;
    }

    // SharedPreferences helpers
    private android.content.SharedPreferences prefs() {
        return getSharedPreferences("callx_privacy", MODE_PRIVATE);
    }
    private void saveBoolPref(String key, boolean val) {
        prefs().edit().putBoolean(key, val).apply();
    }
    private boolean loadBoolPref(String key, boolean def) {
        return prefs().getBoolean(key, def);
    }
    private void loadBoolPref(String key, boolean def, SwitchCompat sw) {
        sw.setChecked(prefs().getBoolean(key, def));
    }
    private void saveStringPref(String key, String val) {
        prefs().edit().putString(key, val).apply();
    }
    private String loadStringPref(String key, String def) {
        return prefs().getString(key, def);
    }
    private void loadStringPrefAsSubtitle(String key, String def, View row) {
        updateSubtitle(row, prefs().getString(key, def));
    }
}
