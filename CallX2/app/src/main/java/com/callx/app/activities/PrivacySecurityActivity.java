package com.callx.app.activities;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import com.callx.app.R;
import com.callx.app.databinding.ActivityPrivacySecurityBinding;
import com.callx.app.utils.AppLockManager;
import com.callx.app.utils.SecurityManager;
import com.google.firebase.auth.FirebaseAuth;

/**
 * Production Privacy & Security Activity.
 *
 * Sections:
 *  1. App Lock       — PIN / Pattern / Fingerprint / Auto-lock delay / Biometric fallback
 *  2. Privacy        — Read receipts, Last seen, Profile photo, Status, About,
 *                      Group add permission, Call permission, Screenshot lock,
 *                      Incognito mode, Ghost mode
 *  3. Security       — Two-step verification, Secure notifications, Login activity log,
 *                      IP address protection, Auto-delete messages, Security alerts,
 *                      Blocked contacts
 */
public class PrivacySecurityActivity extends AppCompatActivity {

    private static final int REQ_APP_LOCK  = 200;
    private static final int REQ_TWO_STEP  = 201;
    private static final int REQ_LOGIN_LOG = 202;

    private ActivityPrivacySecurityBinding binding;
    private AppLockManager  lockMgr;
    private SecurityManager secMgr;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityPrivacySecurityBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.toolbar.setNavigationOnClickListener(v -> finish());

        lockMgr = new AppLockManager(this);
        secMgr  = new SecurityManager(this);

        applyScreenshotLock(secMgr.isScreenshotLockEnabled());

        setupAppLockSection();
        setupPrivacySection();
        setupSecuritySection();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 1. APP LOCK
    // ═══════════════════════════════════════════════════════════════════════

    private void setupAppLockSection() {
        // ── App Lock chooser ──────────────────────────────────────────────
        configRow(binding.rowAppLock.getRoot(), R.drawable.ic_phone,
            "App Lock",
            "Current: " + lockMgr.getLockTypeLabel());
        binding.rowAppLock.getRoot().setOnClickListener(v -> showLockTypeChooser());

        // ── Fingerprint ───────────────────────────────────────────────────
        boolean fpActive = AppLockManager.FINGERPRINT.equals(lockMgr.getLockType());
        configRow(binding.rowFingerprint.getRoot(), R.drawable.ic_person,
            "Fingerprint / Face Unlock", fpActive ? "Active" : "Tap to enable");
        binding.rowFingerprint.getRoot().setOnClickListener(v ->
            launchAppLock(AppLockActivity.MODE_FINGERPRINT));

        // ── PIN lock ──────────────────────────────────────────────────────
        boolean pinActive = AppLockManager.PIN.equals(lockMgr.getLockType());
        configRow(binding.rowPinLock.getRoot(), R.drawable.ic_person_add,
            "PIN Lock",
            pinActive ? "Active (" + AppLockManager.PIN_LENGTH + "-digit)" : "Tap to set");
        binding.rowPinLock.getRoot().setOnClickListener(v ->
            launchAppLock(AppLockActivity.MODE_PIN));

        // ── Pattern lock ──────────────────────────────────────────────────
        boolean patActive = AppLockManager.PATTERN.equals(lockMgr.getLockType());
        configRow(binding.rowPatternLock.getRoot(), R.drawable.ic_group,
            "Pattern Lock", patActive ? "Active" : "Tap to set");
        binding.rowPatternLock.getRoot().setOnClickListener(v ->
            launchAppLock(AppLockActivity.MODE_PATTERN));

        // ── Auto-lock delay ───────────────────────────────────────────────
        configRow(binding.rowAutoLockDelay.getRoot(), R.drawable.ic_timer,
            "Auto-Lock Delay", lockMgr.getAutoLockDelayLabel());
        binding.rowAutoLockDelay.getRoot().setOnClickListener(v -> showAutoLockDelayChooser());

        // ── Biometric fallback ────────────────────────────────────────────
        configToggleRow(binding.rowBiometricFallback.getRoot(), R.drawable.ic_shield,
            "Biometric Fallback to PIN",
            "Use PIN if fingerprint/face fails");
        SwitchCompat swFallback = binding.rowBiometricFallback.getRoot().findViewById(R.id.sw_toggle);
        swFallback.setChecked(lockMgr.isBiometricFallbackEnabled());
        swFallback.setOnCheckedChangeListener((btn, on) -> lockMgr.setBiometricFallback(on));
    }

    private void showLockTypeChooser() {
        String[] labels = {"None", "PIN", "Pattern", "Fingerprint / Face"};
        String[] types  = {AppLockManager.NONE, AppLockManager.PIN,
                           AppLockManager.PATTERN, AppLockManager.FINGERPRINT};
        int cur = indexOfStr(lockMgr.getLockType(), types);
        new AlertDialog.Builder(this)
            .setTitle("Choose App Lock")
            .setSingleChoiceItems(labels, cur, (d, w) -> {
                d.dismiss();
                if (w == 0) { lockMgr.clearLock(); refreshAppLockSection(); }
                else          launchAppLock(types[w]);
            }).show();
    }

    private void showAutoLockDelayChooser() {
        String[] labels = {"Immediately", "After 1 minute", "After 5 minutes",
                           "After 15 minutes", "After 1 hour"};
        long[]   vals   = {AppLockManager.DELAY_IMMEDIATELY, AppLockManager.DELAY_1MIN,
                           AppLockManager.DELAY_5MIN, AppLockManager.DELAY_15MIN,
                           AppLockManager.DELAY_1HR};
        long cur = lockMgr.getAutoLockDelayMs();
        int curIdx = 0;
        for (int i = 0; i < vals.length; i++) if (vals[i] == cur) { curIdx = i; break; }
        new AlertDialog.Builder(this)
            .setTitle("Auto-Lock Delay")
            .setSingleChoiceItems(labels, curIdx, (d, w) -> {
                d.dismiss();
                lockMgr.setAutoLockDelayMs(vals[w]);
                updateSubtitle(binding.rowAutoLockDelay.getRoot(), labels[w]);
            }).show();
    }

    private void launchAppLock(String mode) {
        Intent i = new Intent(this, AppLockActivity.class);
        i.putExtra(AppLockActivity.EXTRA_MODE, mode);
        i.putExtra(AppLockActivity.EXTRA_SETUP, true);
        startActivityForResult(i, REQ_APP_LOCK);
    }

    private void refreshAppLockSection() {
        setupAppLockSection();
        Toast.makeText(this, "App lock updated", Toast.LENGTH_SHORT).show();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 2. PRIVACY
    // ═══════════════════════════════════════════════════════════════════════

    private void setupPrivacySection() {
        String[] vis = {SecurityManager.VIS_EVERYONE, SecurityManager.VIS_CONTACTS,
                        SecurityManager.VIS_NOBODY};

        // ── Read receipts ─────────────────────────────────────────────────
        configToggleRow(binding.rowReadReceipts.getRoot(), R.drawable.ic_send,
            "Read Receipts", "Blue tick when your message is read");
        SwitchCompat swReceipts = binding.rowReadReceipts.getRoot().findViewById(R.id.sw_toggle);
        swReceipts.setChecked(secMgr.isReadReceiptsEnabled());
        swReceipts.setOnCheckedChangeListener((b, on) -> secMgr.setReadReceipts(on));

        // ── Last seen ─────────────────────────────────────────────────────
        configRow(binding.rowLastSeen.getRoot(), R.drawable.ic_status_notification,
            "Last Seen", secMgr.getLastSeenVisibility());
        binding.rowLastSeen.getRoot().setOnClickListener(v ->
            showVisibilityChooser("Last Seen", vis,
                secMgr.getLastSeenVisibility(),
                val -> { secMgr.setLastSeenVisibility(val); updateSubtitle(binding.rowLastSeen.getRoot(), val); }));

        // ── Profile photo ─────────────────────────────────────────────────
        configRow(binding.rowProfilePhoto.getRoot(), R.drawable.ic_gallery,
            "Profile Photo", secMgr.getProfilePhotoVisibility());
        binding.rowProfilePhoto.getRoot().setOnClickListener(v ->
            showVisibilityChooser("Profile Photo", vis,
                secMgr.getProfilePhotoVisibility(),
                val -> { secMgr.setProfilePhotoVisibility(val); updateSubtitle(binding.rowProfilePhoto.getRoot(), val); }));

        // ── Status privacy ────────────────────────────────────────────────
        configRow(binding.rowStatusPrivacy.getRoot(), R.drawable.ic_status_notification,
            "Status Updates", secMgr.getStatusPrivacy());
        binding.rowStatusPrivacy.getRoot().setOnClickListener(v ->
            showVisibilityChooser("Status Updates", vis,
                secMgr.getStatusPrivacy(),
                val -> { secMgr.setStatusPrivacy(val); updateSubtitle(binding.rowStatusPrivacy.getRoot(), val); }));

        // ── About privacy ─────────────────────────────────────────────────
        configRow(binding.rowAboutPrivacy.getRoot(), R.drawable.ic_person,
            "About (Bio)", secMgr.getAboutPrivacy());
        binding.rowAboutPrivacy.getRoot().setOnClickListener(v ->
            showVisibilityChooser("About / Bio", vis,
                secMgr.getAboutPrivacy(),
                val -> { secMgr.setAboutPrivacy(val); updateSubtitle(binding.rowAboutPrivacy.getRoot(), val); }));

        // ── Group add permission ──────────────────────────────────────────
        configRow(binding.rowGroupAdd.getRoot(), R.drawable.ic_group,
            "Who Can Add Me to Groups", secMgr.getGroupAddPermission());
        binding.rowGroupAdd.getRoot().setOnClickListener(v ->
            showVisibilityChooser("Who Can Add Me to Groups", vis,
                secMgr.getGroupAddPermission(),
                val -> { secMgr.setGroupAddPermission(val); updateSubtitle(binding.rowGroupAdd.getRoot(), val); }));

        // ── Call permission ───────────────────────────────────────────────
        configRow(binding.rowCallPermission.getRoot(), R.drawable.ic_phone,
            "Who Can Call Me", secMgr.getCallPermission());
        binding.rowCallPermission.getRoot().setOnClickListener(v ->
            showVisibilityChooser("Who Can Call Me", vis,
                secMgr.getCallPermission(),
                val -> { secMgr.setCallPermission(val); updateSubtitle(binding.rowCallPermission.getRoot(), val); }));

        // ── Screenshot lock ───────────────────────────────────────────────
        configToggleRow(binding.rowScreenshotLock.getRoot(), R.drawable.ic_camera,
            "Screenshot Lock", "Block screenshots inside CallX");
        SwitchCompat swShot = binding.rowScreenshotLock.getRoot().findViewById(R.id.sw_toggle);
        swShot.setChecked(secMgr.isScreenshotLockEnabled());
        swShot.setOnCheckedChangeListener((b, on) -> {
            secMgr.setScreenshotLock(on);
            applyScreenshotLock(on);
        });

        // ── Incognito mode ────────────────────────────────────────────────
        configToggleRow(binding.rowIncognito.getRoot(), R.drawable.ic_eye_off,
            "Incognito Mode",
            "Hide typing indicator and online status");
        SwitchCompat swIncog = binding.rowIncognito.getRoot().findViewById(R.id.sw_toggle);
        swIncog.setChecked(secMgr.isIncognitoMode());
        swIncog.setOnCheckedChangeListener((b, on) -> {
            secMgr.setIncognitoMode(on);
            if (on && secMgr.isGhostMode()) {
                secMgr.setGhostMode(false);
                SwitchCompat swGhost = binding.rowGhostMode.getRoot().findViewById(R.id.sw_toggle);
                swGhost.setChecked(false);
            }
        });

        // ── Ghost mode ────────────────────────────────────────────────────
        configToggleRow(binding.rowGhostMode.getRoot(), R.drawable.ic_ghost,
            "Ghost Mode",
            "Complete invisibility — no last seen, online, typing, or read receipts");
        SwitchCompat swGhost = binding.rowGhostMode.getRoot().findViewById(R.id.sw_toggle);
        swGhost.setChecked(secMgr.isGhostMode());
        swGhost.setOnCheckedChangeListener((b, on) -> {
            secMgr.setGhostMode(on);
            if (on) {
                // Ghost mode overrides incognito
                secMgr.setIncognitoMode(false);
                SwitchCompat swIncogInner = binding.rowIncognito.getRoot().findViewById(R.id.sw_toggle);
                swIncogInner.setChecked(false);
                Toast.makeText(this,
                    "Ghost Mode on — you are now completely invisible", Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 3. SECURITY
    // ═══════════════════════════════════════════════════════════════════════

    private void setupSecuritySection() {
        // ── Two-step verification ─────────────────────────────────────────
        boolean tsvOn = secMgr.isTwoStepEnabled();
        configRow(binding.rowTwoStep.getRoot(), R.drawable.ic_shield,
            "Two-Step Verification",
            tsvOn ? "Enabled — extra PIN required on login" : "Disabled");
        binding.rowTwoStep.getRoot().setOnClickListener(v -> {
            Intent i = new Intent(this, TwoStepVerificationActivity.class);
            startActivityForResult(i, REQ_TWO_STEP);
        });

        // ── Secure notifications ──────────────────────────────────────────
        configToggleRow(binding.rowSecureNotifs.getRoot(), R.drawable.ic_message_notification,
            "Secure Notifications",
            "Hide message preview and sender in notification bar");
        SwitchCompat swSecNotif = binding.rowSecureNotifs.getRoot().findViewById(R.id.sw_toggle);
        swSecNotif.setChecked(secMgr.isSecureNotificationsEnabled());
        swSecNotif.setOnCheckedChangeListener((b, on) -> secMgr.setSecureNotifications(on));

        // ── IP address protection ─────────────────────────────────────────
        configToggleRow(binding.rowIpProtection.getRoot(), R.drawable.ic_video_call,
            "IP Address Protection",
            "Route calls through server to hide your real IP");
        SwitchCompat swIp = binding.rowIpProtection.getRoot().findViewById(R.id.sw_toggle);
        swIp.setChecked(secMgr.isIpProtectionEnabled());
        swIp.setOnCheckedChangeListener((b, on) -> {
            secMgr.setIpProtection(on);
            if (on) Toast.makeText(this,
                "Calls will be relayed — may slightly affect quality", Toast.LENGTH_SHORT).show();
        });

        // ── Auto-delete messages ──────────────────────────────────────────
        configRow(binding.rowAutoDelete.getRoot(), R.drawable.ic_timer,
            "Auto-Delete Messages", secMgr.getAutoDeleteLabel());
        binding.rowAutoDelete.getRoot().setOnClickListener(v -> showAutoDeleteChooser());

        // ── Security alert notifications ──────────────────────────────────
        configToggleRow(binding.rowSecurityAlerts.getRoot(), R.drawable.ic_send_fill,
            "Security Alert Notifications",
            "Get notified when a new device accesses your account");
        SwitchCompat swAlerts = binding.rowSecurityAlerts.getRoot().findViewById(R.id.sw_toggle);
        swAlerts.setChecked(secMgr.isSecurityAlertsEnabled());
        swAlerts.setOnCheckedChangeListener((b, on) -> secMgr.setSecurityAlerts(on));

        // ── Login activity log ────────────────────────────────────────────
        configRow(binding.rowLoginLog.getRoot(), R.drawable.ic_phone,
            "Login Activity", "View recent device logins");
        binding.rowLoginLog.getRoot().setOnClickListener(v ->
            startActivityForResult(new Intent(this, LoginActivityLogActivity.class), REQ_LOGIN_LOG));

        // ── Blocked contacts ──────────────────────────────────────────────
        configRow(binding.rowBlocked.getRoot(), R.drawable.ic_phone_off,
            "Blocked Contacts", "Manage blocked users");
        binding.rowBlocked.getRoot().setOnClickListener(v ->
            startActivity(new Intent(this, RequestsActivity.class)));
    }

    private void showAutoDeleteChooser() {
        String[] labels = {"Off", "24 hours", "7 days", "30 days", "90 days"};
        long[]   vals   = {SecurityManager.AUTO_DELETE_OFF, SecurityManager.AUTO_DELETE_24H,
                           SecurityManager.AUTO_DELETE_7D,  SecurityManager.AUTO_DELETE_30D,
                           SecurityManager.AUTO_DELETE_90D};
        long cur = secMgr.getAutoDeleteMessagesMs();
        int curIdx = 0;
        for (int i = 0; i < vals.length; i++) if (vals[i] == cur) { curIdx = i; break; }
        new AlertDialog.Builder(this)
            .setTitle("Auto-Delete Messages")
            .setMessage("Messages in all chats will be deleted after the selected period.")
            .setSingleChoiceItems(labels, curIdx, (d, w) -> {
                d.dismiss();
                secMgr.setAutoDeleteMessagesMs(vals[w]);
                updateSubtitle(binding.rowAutoDelete.getRoot(), labels[w]);
                if (vals[w] != SecurityManager.AUTO_DELETE_OFF)
                    Toast.makeText(this, "Messages will auto-delete after " + labels[w],
                        Toast.LENGTH_SHORT).show();
            }).show();
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_APP_LOCK) refreshAppLockSection();
        if (req == REQ_TWO_STEP) setupSecuritySection();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private void applyScreenshotLock(boolean on) {
        if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        else    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }

    interface StringCallback { void onValue(String val); }

    private void showVisibilityChooser(String title, String[] opts, String current, StringCallback cb) {
        int cur = indexOfStr(current, opts);
        new AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(opts, cur, (d, w) -> { d.dismiss(); cb.onValue(opts[w]); })
            .show();
    }

    private void configRow(View row, int iconRes, String title, String subtitle) {
        ((ImageView) row.findViewById(R.id.iv_menu_icon)).setImageResource(iconRes);
        ((TextView)  row.findViewById(R.id.tv_menu_title)).setText(title);
        TextView sub = row.findViewById(R.id.tv_menu_subtitle);
        if (subtitle != null && !subtitle.isEmpty()) {
            sub.setText(subtitle); sub.setVisibility(View.VISIBLE);
        } else {
            sub.setVisibility(View.GONE);
        }
    }

    private void configToggleRow(View row, int iconRes, String title, String subtitle) {
        configRow(row, iconRes, title, subtitle);
    }

    private void updateSubtitle(View row, String text) {
        TextView sub = row.findViewById(R.id.tv_menu_subtitle);
        sub.setText(text); sub.setVisibility(View.VISIBLE);
    }

    private int indexOfStr(String s, String[] arr) {
        for (int i = 0; i < arr.length; i++) if (arr[i].equals(s)) return i;
        return 0;
    }
}
