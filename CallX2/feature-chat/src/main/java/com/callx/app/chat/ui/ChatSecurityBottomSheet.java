package com.callx.app.chat.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;

import com.callx.app.chat.R;
import com.callx.app.utils.SecurityManager;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/**
 * ChatSecurityBottomSheet — Privacy quick-controls from Chat 3-dot → Security.
 *
 * Sections:
 *  1. Toggles  : Read Receipts, Incognito, Ghost, Screenshot Lock, Silence Unknown Callers
 *  2. Visibility: Last Seen, Profile Photo, Status Updates, About/Bio, Who Can Call, Who Can Add
 *  3. Timer     : Disappearing Messages
 *  4. Button    : Open Full Privacy & Security
 */
public class ChatSecurityBottomSheet extends BottomSheetDialogFragment {

    public static final String TAG = "ChatSecurityBottomSheet";

    private SecurityManager secMgr;

    public static ChatSecurityBottomSheet newInstance() {
        return new ChatSecurityBottomSheet();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_chat_security, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        secMgr = new SecurityManager(requireContext());

        setupToggles(v);
        setupVisibilityRows(v);
        setupDisappearingMessages(v);
        setupFullSettingsButton(v);
    }

    // ── 1. Toggles ────────────────────────────────────────────────────────

    private void setupToggles(View v) {

        // Read Receipts
        SwitchCompat swReceipts = v.findViewById(R.id.sw_read_receipts);
        swReceipts.setChecked(secMgr.isReadReceiptsEnabled());
        v.findViewById(R.id.row_read_receipts).setOnClickListener(x -> swReceipts.toggle());
        swReceipts.setOnCheckedChangeListener((b, on) -> {
            secMgr.setReadReceipts(on);
            toast(on ? "Read receipts ON" : "Read receipts OFF");
        });

        // Incognito
        SwitchCompat swIncog = v.findViewById(R.id.sw_incognito);
        swIncog.setChecked(secMgr.isIncognitoMode());
        v.findViewById(R.id.row_incognito).setOnClickListener(x -> swIncog.toggle());
        swIncog.setOnCheckedChangeListener((b, on) -> {
            secMgr.setIncognitoMode(on);
            if (on && secMgr.isGhostMode()) {
                secMgr.setGhostMode(false);
                SwitchCompat sw = v.findViewById(R.id.sw_ghost);
                if (sw != null) sw.setChecked(false);
            }
            toast(on ? "Incognito ON" : "Incognito OFF");
        });

        // Ghost Mode
        SwitchCompat swGhost = v.findViewById(R.id.sw_ghost);
        swGhost.setChecked(secMgr.isGhostMode());
        v.findViewById(R.id.row_ghost).setOnClickListener(x -> swGhost.toggle());
        swGhost.setOnCheckedChangeListener((b, on) -> {
            secMgr.setGhostMode(on);
            if (on) {
                secMgr.setIncognitoMode(false);
                SwitchCompat sw = v.findViewById(R.id.sw_incognito);
                if (sw != null) sw.setChecked(false);
                toast("Ghost Mode ON — completely invisible");
            } else {
                toast("Ghost Mode OFF");
            }
        });

        // Screenshot Lock
        SwitchCompat swShot = v.findViewById(R.id.sw_screenshot);
        swShot.setChecked(secMgr.isScreenshotLockEnabled());
        v.findViewById(R.id.row_screenshot).setOnClickListener(x -> swShot.toggle());
        swShot.setOnCheckedChangeListener((b, on) -> {
            secMgr.setScreenshotLock(on);
            Activity act = getActivity();
            if (act != null) {
                if (on) act.getWindow().setFlags(
                        WindowManager.LayoutParams.FLAG_SECURE,
                        WindowManager.LayoutParams.FLAG_SECURE);
                else    act.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
            }
            toast(on ? "Screenshot lock ON" : "Screenshot lock OFF");
        });

        // Silence Unknown Callers
        SwitchCompat swSilence = v.findViewById(R.id.sw_silence_unknown);
        swSilence.setChecked(secMgr.isSilenceUnknownCallers());
        v.findViewById(R.id.row_silence_unknown).setOnClickListener(x -> swSilence.toggle());
        swSilence.setOnCheckedChangeListener((b, on) -> {
            secMgr.setSilenceUnknownCallers(on);
            toast(on ? "Unknown callers will be silently rejected"
                     : "All callers allowed");
        });
    }

    // ── 2. Visibility rows ────────────────────────────────────────────────

    private void setupVisibilityRows(View v) {
        String[] vis = {SecurityManager.VIS_EVERYONE,
                        SecurityManager.VIS_CONTACTS,
                        SecurityManager.VIS_NOBODY};

        // Last Seen
        TextView tvLastSeen = v.findViewById(R.id.tv_last_seen_val);
        tvLastSeen.setText(secMgr.getLastSeenVisibility());
        v.findViewById(R.id.row_last_seen).setOnClickListener(x ->
            showVisibilityChooser("Last Seen", vis, secMgr.getLastSeenVisibility(), val -> {
                secMgr.setLastSeenVisibility(val);
                tvLastSeen.setText(val);
            }));

        // Profile Photo
        TextView tvPhoto = v.findViewById(R.id.tv_profile_photo_val);
        tvPhoto.setText(secMgr.getProfilePhotoVisibility());
        v.findViewById(R.id.row_profile_photo).setOnClickListener(x ->
            showVisibilityChooser("Profile Photo", vis, secMgr.getProfilePhotoVisibility(), val -> {
                secMgr.setProfilePhotoVisibility(val);
                tvPhoto.setText(val);
            }));

        // Status Updates
        TextView tvStatus = v.findViewById(R.id.tv_status_privacy_val);
        tvStatus.setText(secMgr.getStatusPrivacy());
        v.findViewById(R.id.row_status_privacy).setOnClickListener(x ->
            showVisibilityChooser("Status Updates", vis, secMgr.getStatusPrivacy(), val -> {
                secMgr.setStatusPrivacy(val);
                tvStatus.setText(val);
            }));

        // About / Bio
        TextView tvAbout = v.findViewById(R.id.tv_about_privacy_val);
        tvAbout.setText(secMgr.getAboutPrivacy());
        v.findViewById(R.id.row_about_privacy).setOnClickListener(x ->
            showVisibilityChooser("About / Bio", vis, secMgr.getAboutPrivacy(), val -> {
                secMgr.setAboutPrivacy(val);
                tvAbout.setText(val);
            }));

        // Who Can Call Me
        TextView tvCall = v.findViewById(R.id.tv_call_permission_val);
        tvCall.setText(secMgr.getCallPermission());
        v.findViewById(R.id.row_call_permission).setOnClickListener(x ->
            showVisibilityChooser("Who Can Call Me", vis, secMgr.getCallPermission(), val -> {
                secMgr.setCallPermission(val);
                tvCall.setText(val);
            }));

        // Who Can Add Me to Groups
        TextView tvGroup = v.findViewById(R.id.tv_group_add_val);
        tvGroup.setText(secMgr.getGroupAddPermission());
        v.findViewById(R.id.row_group_add).setOnClickListener(x ->
            showVisibilityChooser("Who Can Add Me to Groups", vis,
                    secMgr.getGroupAddPermission(), val -> {
                secMgr.setGroupAddPermission(val);
                tvGroup.setText(val);
            }));
    }

    // ── 3. Disappearing Messages ──────────────────────────────────────────

    private void setupDisappearingMessages(View v) {
        TextView tvDisappear = v.findViewById(R.id.tv_disappearing_val);
        tvDisappear.setText(secMgr.getAutoDeleteLabel());

        v.findViewById(R.id.row_disappearing).setOnClickListener(x -> {
            String[] labels = {"Off", "24 hours", "7 days", "30 days", "90 days"};
            long[]   values = {
                SecurityManager.AUTO_DELETE_OFF,
                SecurityManager.AUTO_DELETE_24H,
                SecurityManager.AUTO_DELETE_7D,
                SecurityManager.AUTO_DELETE_30D,
                SecurityManager.AUTO_DELETE_90D
            };
            long current = secMgr.getAutoDeleteMessagesMs();
            int checkedIdx = 0;
            for (int i = 0; i < values.length; i++) {
                if (values[i] == current) { checkedIdx = i; break; }
            }
            final int[] sel = {checkedIdx};
            new AlertDialog.Builder(requireContext())
                .setTitle("⏳ Disappearing Messages")
                .setSingleChoiceItems(labels, checkedIdx, (d, which) -> sel[0] = which)
                .setPositiveButton("Set", (d, w) -> {
                    secMgr.setAutoDeleteMessagesMs(values[sel[0]]);
                    tvDisappear.setText(labels[sel[0]]);
                    toast("Disappearing messages: " + labels[sel[0]]);
                })
                .setNegativeButton("Cancel", null)
                .show();
        });
    }

    // ── 4. Full Settings Button ───────────────────────────────────────────

    private void setupFullSettingsButton(View v) {
        v.findViewById(R.id.btn_open_full_settings).setOnClickListener(x -> {
            dismiss();
            try {
                Intent i = new Intent();
                i.setClassName(requireContext().getPackageName(),
                        "com.callx.app.activities.PrivacySecurityActivity");
                startActivity(i);
            } catch (Exception e) {
                toast("Privacy & Security not available");
            }
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    interface VisibilityCallback { void onSelected(String value); }

    private void showVisibilityChooser(String title, String[] options,
                                       String current, VisibilityCallback cb) {
        int checked = 0;
        for (int i = 0; i < options.length; i++) {
            if (options[i].equals(current)) { checked = i; break; }
        }
        final int[] sel = {checked};
        new AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setSingleChoiceItems(options, checked, (d, which) -> sel[0] = which)
            .setPositiveButton("OK", (d, w) -> cb.onSelected(options[sel[0]]))
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void toast(String msg) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
    }
}
