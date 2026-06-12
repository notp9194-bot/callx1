package com.callx.app.smallwindow;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.callx.app.R;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/**
 * PrivacyDirectDialog — Chat ya Contact ke liye quick-action bottom sheet.
 *
 * Screenshot mein dikhta hai:
 *   Lock | App info | Split-screen | Small window
 *
 * Usage:
 *   PrivacyDirectDialog dialog = PrivacyDirectDialog.newInstance(
 *       userId, userName, userStatus);
 *   dialog.show(getSupportFragmentManager(), "privacy_direct");
 */
public class PrivacyDirectDialog extends BottomSheetDialogFragment {

    private static final String ARG_USER_ID   = "user_id";
    private static final String ARG_USER_NAME = "user_name";
    private static final String ARG_STATUS    = "status";

    private String userId;
    private String userName;
    private String userStatus;

    public static PrivacyDirectDialog newInstance(String userId, String userName, String status) {
        PrivacyDirectDialog d = new PrivacyDirectDialog();
        Bundle args = new Bundle();
        args.putString(ARG_USER_ID,   userId);
        args.putString(ARG_USER_NAME, userName);
        args.putString(ARG_STATUS,    status);
        d.setArguments(args);
        return d;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            userId     = getArguments().getString(ARG_USER_ID,   "");
            userName   = getArguments().getString(ARG_USER_NAME, "User");
            userStatus = getArguments().getString(ARG_STATUS,    "");
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.bottom_sheet_privacy_direct, container, false);
        bindActions(root);
        return root;
    }

    // ─────────────────────────────────────────────────────────────────────

    private void bindActions(View root) {
        // ── Lock ─────────────────────────────────────────────────────────
        LinearLayout rowLock = root.findViewById(R.id.row_pd_lock);
        if (rowLock != null) {
            rowLock.setOnClickListener(v -> {
                dismiss();
                Toast.makeText(requireContext(),
                    "Chat locked for " + userName, Toast.LENGTH_SHORT).show();
                // TODO: integrate AppLockManager per-chat lock
            });
        }

        // ── App Info ─────────────────────────────────────────────────────
        LinearLayout rowAppInfo = root.findViewById(R.id.row_pd_app_info);
        if (rowAppInfo != null) {
            rowAppInfo.setOnClickListener(v -> {
                dismiss();
                // Open CallX app info in system settings
                try {
                    Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + requireContext().getPackageName()));
                    startActivity(i);
                } catch (Exception e) {
                    Toast.makeText(requireContext(), "Settings khuljayenge abhi", Toast.LENGTH_SHORT).show();
                }
            });
        }

        // ── Privacy Settings ──────────────────────────────────────────────
        LinearLayout rowPrivacy = root.findViewById(R.id.row_pd_privacy);
        if (rowPrivacy != null) {
            rowPrivacy.setOnClickListener(v -> {
                dismiss();
                try {
                    Class<?> cls = Class.forName("com.callx.app.activities.PrivacySecurityActivity");
                    Intent i = new Intent(requireContext(), cls);
                    startActivity(i);
                } catch (Exception e) {
                    Toast.makeText(requireContext(), "Privacy settings", Toast.LENGTH_SHORT).show();
                }
            });
        }

        // ── Small Window ──────────────────────────────────────────────────
        LinearLayout rowSmallWindow = root.findViewById(R.id.row_pd_small_window);
        if (rowSmallWindow != null) {
            rowSmallWindow.setOnClickListener(v -> {
                dismiss();
                openSmallWindow();
            });
        }
    }

    // ─────────────────────────────────────────────────────────────────────

    private void openSmallWindow() {
        Context ctx = requireContext().getApplicationContext();

        // Check SYSTEM_ALERT_WINDOW permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !Settings.canDrawOverlays(ctx)) {
            // Ask user to grant permission
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + ctx.getPackageName()));
            startActivity(i);
            Toast.makeText(ctx,
                "'Display over other apps' permission dijiye phir try karo",
                Toast.LENGTH_LONG).show();
            return;
        }

        // Start SmallWindowService (foreground service)
        Intent svc = new Intent(ctx, SmallWindowService.class);
        svc.putExtra(SmallWindowService.EXTRA_NAME,   userName);
        svc.putExtra(SmallWindowService.EXTRA_STATUS, userStatus);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(svc);
        } else {
            ctx.startService(svc);
        }

        Toast.makeText(ctx, "Small window open!", Toast.LENGTH_SHORT).show();
    }
}
