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
import com.callx.app.utils.AppLockManager;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/**
 * PrivacyDirectDialog — Chat ya Contact ke liye quick-action bottom sheet.
 *
 * Screenshot mein dikhta hai:
 *   Lock | App info | Split-screen | Small window
 *
 * v32 upgrade: "Lock" row ab AppLockManager.setLockedForChat() call karta hai.
 *   - Agar app lock enabled hai (PIN/pattern/fingerprint set hai) → chat
 *     immediately lock ho jata hai; next open pe auth screen dikhega.
 *   - Agar app lock setup nahi hai → user ko setup screen ki taraf guide
 *     karta hai via ChatLockSetupActivity (fallback: toast with instructions).
 *
 * Usage:
 *   PrivacyDirectDialog dialog = PrivacyDirectDialog.newInstance(
 *       chatId, userId, userName, userStatus);
 *   dialog.show(getSupportFragmentManager(), "privacy_direct");
 */
public class PrivacyDirectDialog extends BottomSheetDialogFragment {

    public static final int REQ_OVERLAY_PERMISSION = 5555;

    private static final String ARG_CHAT_ID   = "chat_id";
    private static final String ARG_USER_ID   = "user_id";
    private static final String ARG_USER_NAME = "user_name";
    private static final String ARG_STATUS    = "status";

    private String chatId;
    private String userId;
    private String userName;
    private String userStatus;

    // ── Factory ───────────────────────────────────────────────────────────

    /** Legacy factory — chatId derived from userId for 1:1 chats. */
    public static PrivacyDirectDialog newInstance(String userId, String userName, String status) {
        return newInstance(userId, userId, userName, status);
    }

    /** Full factory — prefer this when a specific chatId is known. */
    public static PrivacyDirectDialog newInstance(String chatId, String userId,
                                                   String userName, String status) {
        PrivacyDirectDialog d = new PrivacyDirectDialog();
        Bundle args = new Bundle();
        args.putString(ARG_CHAT_ID,   chatId   != null ? chatId   : userId);
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
            chatId     = getArguments().getString(ARG_CHAT_ID,   "");
            userId     = getArguments().getString(ARG_USER_ID,   "");
            userName   = getArguments().getString(ARG_USER_NAME, "User");
            userStatus = getArguments().getString(ARG_STATUS,    "");
        }
        // Fallback: derive chatId from userId for 1:1 chats
        if ((chatId == null || chatId.isEmpty()) && userId != null) chatId = userId;
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
    // Action bindings
    // ─────────────────────────────────────────────────────────────────────

    private void bindActions(View root) {

        // ── Lock — v32: AppLockManager per-chat lock ──────────────────────
        LinearLayout rowLock = root.findViewById(R.id.row_pd_lock);
        if (rowLock != null) {
            rowLock.setOnClickListener(v -> {
                dismiss();
                toggleChatLock();
            });
        }

        // ── App Info ─────────────────────────────────────────────────────
        LinearLayout rowAppInfo = root.findViewById(R.id.row_pd_app_info);
        if (rowAppInfo != null) {
            rowAppInfo.setOnClickListener(v -> {
                dismiss();
                try {
                    Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + requireContext().getPackageName()));
                    startActivity(i);
                } catch (Exception e) {
                    Toast.makeText(requireContext(),
                            "Settings khuljayenge abhi", Toast.LENGTH_SHORT).show();
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
                    startActivity(new Intent(requireContext(), cls));
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
    // v32: Per-chat lock — AppLockManager integration
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Toggle the per-chat lock for this conversation.
     *
     * Logic:
     *  1. If AppLock is enabled (user has set a PIN/pattern/fingerprint):
     *     - Toggle isLockedForChat(chatId).
     *     - Show confirmation toast.
     *  2. If AppLock is NOT set up:
     *     - Navigate to AppLock setup screen (Security Settings)
     *       so the user can enable a PIN first.
     *     - Explain why in a toast.
     *
     * The lock state is read by the chat opening flow (ConversationActivity /
     * GroupChatActivity) which calls AppLockManager.isLockedForChat(chatId)
     * before rendering messages and shows the auth screen if locked.
     */
    private void toggleChatLock() {
        if (!isAdded() || getContext() == null) return;

        AppLockManager alm = AppLockManager.getInstance(requireContext());

        if (!alm.isEnabled()) {
            // App lock not set up — guide the user to set it up first
            Toast.makeText(requireContext(),
                    "Pehle App Lock enable karo: Settings → Privacy → App Lock",
                    Toast.LENGTH_LONG).show();
            // Try to open the security/privacy settings screen
            try {
                Class<?> cls = Class.forName("com.callx.app.activities.PrivacySecurityActivity");
                Intent i = new Intent(requireContext(), cls);
                i.putExtra("openAppLock", true);
                startActivity(i);
            } catch (ClassNotFoundException ex) {
                // Fallback: open system app settings
                try {
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + requireContext().getPackageName())));
                } catch (Exception ignored) {}
            }
            return;
        }

        // App lock is enabled — toggle the per-chat lock
        boolean currentlyLocked = alm.isLockedForChat(chatId);
        boolean nowLocked       = !currentlyLocked;
        alm.setLockedForChat(chatId, nowLocked);

        String msg = nowLocked
                ? "\"" + userName + "\" chat lock ho gaya \uD83D\uDD12"
                : "\"" + userName + "\" chat unlock ho gaya \uD83D\uDD13";
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Small window
    // ─────────────────────────────────────────────────────────────────────

    private void openSmallWindow(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !Settings.canDrawOverlays(ctx)) {
            Activity activity = getActivity();
            if (activity != null) {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + ctx.getPackageName()));
                activity.getIntent().putExtra("_sw_pending_uid",    userId);
                activity.getIntent().putExtra("_sw_pending_name",   userName);
                activity.getIntent().putExtra("_sw_pending_status", userStatus);
                activity.startActivityForResult(i, REQ_OVERLAY_PERMISSION);
                Toast.makeText(ctx,
                        "'Display over other apps' permission dijiye, phir automatic open hoga",
                        Toast.LENGTH_LONG).show();
            } else {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + ctx.getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                Toast.makeText(ctx, "Permission dijiye phir manually try karo",
                        Toast.LENGTH_LONG).show();
            }
            return;
        }
        launchSmallWindowService(ctx);
    }

    void launchSmallWindowService(Context ctx) {
        Intent svc = new Intent(ctx, SmallWindowService.class);
        svc.putExtra(SmallWindowService.EXTRA_USER_ID, userId);
        svc.putExtra(SmallWindowService.EXTRA_NAME,    userName);
        svc.putExtra(SmallWindowService.EXTRA_STATUS,  userStatus);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(svc);
        } else {
            ctx.startService(svc);
        }
        Toast.makeText(ctx, "Small window open!", Toast.LENGTH_SHORT).show();
    }
}
