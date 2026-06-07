package com.callx.app.chat.ui;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;

import com.callx.app.chat.R;
import com.callx.app.utils.SecurityManager;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/**
 * ChatSecurityBottomSheet
 *
 * Colorful privacy quick-controls shown from Chat 3-dot menu → Security.
 * Shows: Read Receipts, Incognito, Ghost Mode, Screenshot Lock, Last Seen, Profile Photo.
 * "Open Full Privacy & Security" button → launches PrivacySecurityActivity via setClassName.
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

        // ── Toggle: Read Receipts ─────────────────────────────────────────
        SwitchCompat swReceipts = v.findViewById(R.id.sw_read_receipts);
        swReceipts.setChecked(secMgr.isReadReceiptsEnabled());
        v.findViewById(R.id.row_read_receipts).setOnClickListener(x -> swReceipts.toggle());
        swReceipts.setOnCheckedChangeListener((btn, on) -> {
            secMgr.setReadReceipts(on);
            toast(on ? "Read receipts ON" : "Read receipts OFF");
        });

        // ── Toggle: Incognito Mode ────────────────────────────────────────
        SwitchCompat swIncog = v.findViewById(R.id.sw_incognito);
        swIncog.setChecked(secMgr.isIncognitoMode());
        v.findViewById(R.id.row_incognito).setOnClickListener(x -> swIncog.toggle());
        swIncog.setOnCheckedChangeListener((btn, on) -> {
            secMgr.setIncognitoMode(on);
            if (on && secMgr.isGhostMode()) {
                secMgr.setGhostMode(false);
                SwitchCompat swGhost = v.findViewById(R.id.sw_ghost);
                if (swGhost != null) swGhost.setChecked(false);
            }
            toast(on ? "Incognito ON" : "Incognito OFF");
        });

        // ── Toggle: Ghost Mode ────────────────────────────────────────────
        SwitchCompat swGhost = v.findViewById(R.id.sw_ghost);
        swGhost.setChecked(secMgr.isGhostMode());
        v.findViewById(R.id.row_ghost).setOnClickListener(x -> swGhost.toggle());
        swGhost.setOnCheckedChangeListener((btn, on) -> {
            secMgr.setGhostMode(on);
            if (on) {
                secMgr.setIncognitoMode(false);
                SwitchCompat swIncogInner = v.findViewById(R.id.sw_incognito);
                if (swIncogInner != null) swIncogInner.setChecked(false);
                toast("Ghost Mode ON — completely invisible");
            } else {
                toast("Ghost Mode OFF");
            }
        });

        // ── Toggle: Screenshot Lock ───────────────────────────────────────
        SwitchCompat swShot = v.findViewById(R.id.sw_screenshot);
        swShot.setChecked(secMgr.isScreenshotLockEnabled());
        v.findViewById(R.id.row_screenshot).setOnClickListener(x -> swShot.toggle());
        swShot.setOnCheckedChangeListener((btn, on) -> {
            secMgr.setScreenshotLock(on);
            // Apply to host activity window
            Activity act = getActivity();
            if (act != null) {
                if (on) {
                    act.getWindow().setFlags(
                        WindowManager.LayoutParams.FLAG_SECURE,
                        WindowManager.LayoutParams.FLAG_SECURE);
                } else {
                    act.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
                }
            }
            toast(on ? "Screenshot lock ON" : "Screenshot lock OFF");
        });

        // ── Last Seen (tappable → chooser) ───────────────────────────────
        TextView tvLastSeen = v.findViewById(R.id.tv_last_seen_val);
        tvLastSeen.setText(secMgr.getLastSeenVisibility());
        v.findViewById(R.id.row_last_seen).setOnClickListener(x ->
            showVisibilityChooser("Last Seen", secMgr.getLastSeenVisibility(), val -> {
                secMgr.setLastSeenVisibility(val);
                tvLastSeen.setText(val);
            }));

        // ── Profile Photo (tappable → chooser) ───────────────────────────
        TextView tvPhoto = v.findViewById(R.id.tv_profile_photo_val);
        tvPhoto.setText(secMgr.getProfilePhotoVisibility());
        v.findViewById(R.id.row_profile_photo).setOnClickListener(x ->
            showVisibilityChooser("Profile Photo", secMgr.getProfilePhotoVisibility(), val -> {
                secMgr.setProfilePhotoVisibility(val);
                tvPhoto.setText(val);
            }));

        // ── Open Full Privacy & Security ──────────────────────────────────
        v.findViewById(R.id.btn_open_full_settings).setOnClickListener(x -> {
            dismiss();
            try {
                Intent i = new Intent();
                i.setClassName(requireContext().getPackageName(),
                    "com.callx.app.activities.PrivacySecurityActivity");
                startActivity(i);
            } catch (Exception e) {
                toast("Privacy & Security settings not available");
            }
        });
    }

    // ── Visibility chooser dialog ─────────────────────────────────────────

    interface VisibilityCallback { void onSelected(String value); }

    private void showVisibilityChooser(String title, String current, VisibilityCallback cb) {
        String[] options = {
            SecurityManager.VIS_EVERYONE,
            SecurityManager.VIS_CONTACTS,
            SecurityManager.VIS_NOBODY
        };
        int checked = 0;
        for (int i = 0; i < options.length; i++) {
            if (options[i].equals(current)) { checked = i; break; }
        }
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setSingleChoiceItems(options, checked, null)
            .setPositiveButton("OK", (dialog, w) -> {
                int sel = ((androidx.appcompat.app.AlertDialog) dialog).getListView().getCheckedItemPosition();
                if (sel >= 0) cb.onSelected(options[sel]);
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void toast(String msg) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
    }
}
