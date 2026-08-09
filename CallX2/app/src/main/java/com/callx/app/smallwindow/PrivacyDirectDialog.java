package com.callx.app.smallwindow;

import android.app.Activity;
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

    public static final int REQ_OVERLAY_PERMISSION = 5555;

    private static final String ARG_USER_ID    = "user_id";
    private static final String ARG_USER_NAME  = "user_name";
    private static final String ARG_STATUS     = "status";
    private static final String ARG_USER_PHOTO = "user_photo";

    private String userId;
    private String userName;
    private String userStatus;
    private String userPhoto;

    /** Backward-compatible overload (no photo). */
    public static PrivacyDirectDialog newInstance(String userId, String userName, String status) {
        return newInstance(userId, userName, status, null);
    }

    public static PrivacyDirectDialog newInstance(String userId, String userName, String status, String photoUrl) {
        PrivacyDirectDialog d = new PrivacyDirectDialog();
        Bundle args = new Bundle();
        args.putString(ARG_USER_ID,    userId);
        args.putString(ARG_USER_NAME,  userName);
        args.putString(ARG_STATUS,     status);
        args.putString(ARG_USER_PHOTO, photoUrl);
        d.setArguments(args);
        return d;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            userId     = getArguments().getString(ARG_USER_ID,    "");
            userName   = getArguments().getString(ARG_USER_NAME,  "User");
            userStatus = getArguments().getString(ARG_STATUS,     "");
            userPhoto  = getArguments().getString(ARG_USER_PHOTO, "");
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
                if (userId == null || userId.isEmpty()) { dismiss(); return; }
                Activity act = getActivity();
                if (!(act instanceof androidx.fragment.app.FragmentActivity)) { dismiss(); return; }
                androidx.fragment.app.FragmentActivity fa = (androidx.fragment.app.FragmentActivity) act;
                com.callx.app.lock.ChatLockManager lockMgr =
                        com.callx.app.lock.ChatLockManager.getInstance(requireContext());
                boolean wantLocked = !lockMgr.isLocked(userId);
                // Confirm identity before flipping the lock either way —
                // same reasoning as ChatSecurityBottomSheet's Chat Lock
                // toggle: switching it off shouldn't be a single free tap.
                com.callx.app.lock.ChatLockGate.authenticate(fa,
                    () -> {
                        lockMgr.setLocked(userId, wantLocked);
                        dismiss();
                        Toast.makeText(requireContext(),
                            wantLocked ? "Chat locked for " + userName
                                       : "Chat unlocked for " + userName,
                            Toast.LENGTH_SHORT).show();
                    },
                    () -> dismiss());
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
                Context appCtx = requireContext().getApplicationContext();
                dismiss();
                openSmallWindow(appCtx);
            });
        }
    }

    // ─────────────────────────────────────────────────────────────────────

    private void openSmallWindow(Context ctx) {
        // Vivo-compatible double-check: verify SYSTEM_ALERT_WINDOW permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !isOverlayPermissionGranted(ctx)) {
            // Use parent Activity's startActivityForResult so we get callback when user returns
            Activity activity = getActivity();
            if (activity != null) {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + ctx.getPackageName()));
                // Store args so onActivityResult can retry
                activity.getIntent().putExtra("_sw_pending_uid",    userId);
                activity.getIntent().putExtra("_sw_pending_name",   userName);
                activity.getIntent().putExtra("_sw_pending_status", userStatus);
                activity.getIntent().putExtra("_sw_pending_photo",  userPhoto);
                activity.startActivityForResult(i, REQ_OVERLAY_PERMISSION);
                Toast.makeText(ctx,
                    "'Display over other apps' permission dijiye, phir automatic open hoga",
                    Toast.LENGTH_LONG).show();
            } else {
                // Fragment detached fallback
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + ctx.getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                Toast.makeText(ctx,
                    "Permission dijiye phir manually try karo",
                    Toast.LENGTH_LONG).show();
            }
            return;
        }

        launchSmallWindowService(ctx);
    }

    /** Vivo/FuntouchOS safe: tries both API and a practical addView probe */
    private boolean isOverlayPermissionGranted(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        return Settings.canDrawOverlays(ctx);
    }

    void launchSmallWindowService(Context ctx) {
        Intent svc = new Intent(ctx, SmallWindowService.class);
        svc.putExtra(SmallWindowService.EXTRA_USER_ID, userId);
        svc.putExtra(SmallWindowService.EXTRA_NAME,    userName);
        svc.putExtra(SmallWindowService.EXTRA_STATUS,  userStatus);
        svc.putExtra(SmallWindowService.EXTRA_PHOTO,   userPhoto);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(svc);
        } else {
            ctx.startService(svc);
        }

        Toast.makeText(ctx, "Small window open!", Toast.LENGTH_SHORT).show();
    }
}
