package com.callx.app.conversation.controllers;

import android.app.AlertDialog;
import android.net.Uri;
import android.view.View;

import com.callx.app.chat.databinding.ActivityChatBinding;
import com.callx.app.chat.ui.ChatCustomizationBottomSheet;
import com.callx.app.chat.ui.ChatPrivacyBottomSheet;
import com.callx.app.chat.ui.ChatSecurityBottomSheet;
import com.callx.app.utils.ChatPrivacyManager;
import com.callx.app.utils.ChatThemeManager;
import com.callx.app.utils.ChatWallpaperManager;

import android.widget.Toast;

/**
 * Handles chat screen theme (static), wallpaper, and privacy/security dialogs.
 * Theme/bubble/font/typing customization removed — clean fast chat system.
 */
public class ChatThemeController {

    private final ChatActivityDelegate delegate;

    public ChatThemeController(ChatActivityDelegate delegate) {
        this.delegate = delegate;
    }

    // ── Screen theme (static, no picker) ─────────────────────────────────

    public void applyScreenTheme() {
        ActivityChatBinding binding = delegate.getBinding();
        ChatThemeManager mgr = ChatThemeManager.get(delegate.getActivity());

        if (binding.tvReplyBarName != null) {
            binding.tvReplyBarName.setTextColor(mgr.getPrimaryColor());
        }

        mgr.applyScreenTheme(
                binding.toolbar,
                binding.getRoot(),
                binding.llInputRow,
                binding.btnSend,
                binding.btnMic,
                binding.fabBackToLatest,
                binding.viewReplyAccent);

        applyWallpaper();
    }

    // ── Wallpaper ─────────────────────────────────────────────────────────

    public void applyWallpaper() {
        ActivityChatBinding binding = delegate.getBinding();
        android.widget.ImageView ivWall = binding.ivChatWallpaper;
        if (ivWall == null) return;

        String uriStr = ChatWallpaperManager.get(delegate.getActivity())
                .getEffectiveWallpaper(delegate.getChatId());
        if (uriStr == null) {
            ivWall.setVisibility(View.GONE);
            ivWall.setImageDrawable(null);
        } else {
            ivWall.setVisibility(View.VISIBLE);
            com.bumptech.glide.Glide.with(delegate.getActivity())
                    .load(Uri.parse(uriStr))
                    .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                    .centerCrop()
                    .into(ivWall);
        }
    }

    public void showWallpaperPicker() {
        delegate.launchWallpaperPicker();
    }

    public void showWallpaperScopeDialog(Uri uri) {
        ChatWallpaperManager wm = ChatWallpaperManager.get(delegate.getActivity());
        String[] options = {"🙋 This chat only", "🌐 All chats (Global)", "❌ Remove wallpaper"};
        new AlertDialog.Builder(delegate.getActivity())
                .setTitle("🖼️ Set Wallpaper")
                .setItems(options, (d, which) -> {
                    if (which == 0) {
                        wm.setWallpaper(delegate.getChatId(), uri);
                        applyWallpaper();
                    } else if (which == 1) {
                        wm.setGlobalWallpaper(uri);
                        applyWallpaper();
                    } else {
                        wm.clearWallpaper(delegate.getChatId());
                        wm.clearGlobalWallpaper();
                        applyWallpaper();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── Customization menu (wallpaper only) ───────────────────────────────

    public void showChatCustomizationMenu() {
        ChatCustomizationBottomSheet sheet = ChatCustomizationBottomSheet.newInstance();
        sheet.setOnOptionSelectedListener(option -> {
            if (option == ChatCustomizationBottomSheet.OPTION_WALLPAPER) {
                showWallpaperPicker();
            }
        });
        sheet.show(delegate.getSupportFragmentManager(), ChatCustomizationBottomSheet.TAG);
    }

    // ── Security / privacy sheets ─────────────────────────────────────────

    public void showChatSecuritySheet() {
        ChatSecurityBottomSheet sheet = ChatSecurityBottomSheet.newInstance();
        sheet.show(delegate.getSupportFragmentManager(), ChatSecurityBottomSheet.TAG);
    }

    public void showChatPrivacySheet() {
        String displayName = delegate.getPartnerName() != null ? delegate.getPartnerName() : "Chat";
        ChatPrivacyManager pm = new ChatPrivacyManager(delegate.getActivity(), delegate.getChatId(), false);

        String[] options = {
                "⏳ Disappearing Messages  [" + pm.getDisappearingLabel() + "]",
                "⏱ Message Timer  [" + pm.getMsgTimerLabel() + "]",
                "🗑 Auto-Delete Old Messages  [" + pm.getAutoDeleteLabel() + "]"
        };
        new AlertDialog.Builder(delegate.getActivity())
                .setTitle("🛡 Chat Privacy — " + displayName)
                .setItems(options, (d, which) -> {
                    if (which == 0) showDisappearingDialog(pm);
                    else if (which == 1) showMsgTimerDialog(pm);
                    else showAutoDeleteDialog(pm);
                })
                .setNegativeButton("Close", null)
                .show();
    }

    public void showDisappearingDialog(ChatPrivacyManager pm) {
        String[] labels = {"Off", "24 hours", "7 days", "30 days"};
        long[] values = {
                ChatPrivacyManager.DISAPPEAR_OFF,
                ChatPrivacyManager.DISAPPEAR_24H,
                ChatPrivacyManager.DISAPPEAR_7D,
                ChatPrivacyManager.DISAPPEAR_30D
        };
        long cur = pm.getDisappearingMs();
        int checked = 0;
        for (int i = 0; i < values.length; i++) if (values[i] == cur) { checked = i; break; }
        final int[] sel = {checked};
        new AlertDialog.Builder(delegate.getActivity())
                .setTitle("⏳ Disappearing Messages")
                .setSingleChoiceItems(labels, checked, (d, w) -> sel[0] = w)
                .setPositiveButton("Set", (d, w) -> {
                    pm.setDisappearingMs(values[sel[0]]);
                    Toast.makeText(delegate.getActivity(),
                            "Disappearing: " + labels[sel[0]], Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    public void showMsgTimerDialog(ChatPrivacyManager pm) {
        String[] labels = {"Off", "10 seconds", "30 seconds", "1 minute", "5 minutes", "1 hour"};
        long[] values = {
                ChatPrivacyManager.MSG_TIMER_OFF,
                ChatPrivacyManager.MSG_TIMER_10S,
                ChatPrivacyManager.MSG_TIMER_30S,
                ChatPrivacyManager.MSG_TIMER_1M,
                ChatPrivacyManager.MSG_TIMER_5M,
                ChatPrivacyManager.MSG_TIMER_1H
        };
        long cur = pm.getMsgTimerMs();
        int checked = 0;
        for (int i = 0; i < values.length; i++) if (values[i] == cur) { checked = i; break; }
        final int[] sel = {checked};
        new AlertDialog.Builder(delegate.getActivity())
                .setTitle("⏱ Message Timer")
                .setSingleChoiceItems(labels, checked, (d, w) -> sel[0] = w)
                .setPositiveButton("Set", (d, w) -> {
                    pm.setMsgTimerMs(values[sel[0]]);
                    Toast.makeText(delegate.getActivity(),
                            "Timer: " + labels[sel[0]], Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    public void showAutoDeleteDialog(ChatPrivacyManager pm) {
        String[] labels = {"Never", "After 7 days", "After 30 days", "After 90 days", "After 6 months"};
        long[] values = {
                ChatPrivacyManager.AUTO_DELETE_OFF,
                ChatPrivacyManager.AUTO_DELETE_7D,
                ChatPrivacyManager.AUTO_DELETE_30D,
                ChatPrivacyManager.AUTO_DELETE_90D,
                ChatPrivacyManager.AUTO_DELETE_180D
        };
        long cur = pm.getAutoDeleteDays();
        int checked = 0;
        for (int i = 0; i < values.length; i++) if (values[i] == cur) { checked = i; break; }
        final int[] sel = {checked};
        new AlertDialog.Builder(delegate.getActivity())
                .setTitle("🗑 Auto-Delete Old Messages")
                .setSingleChoiceItems(labels, checked, (d, w) -> sel[0] = w)
                .setPositiveButton("Set", (d, w) -> {
                    pm.setAutoDeleteDays(values[sel[0]]);
                    Toast.makeText(delegate.getActivity(),
                            "Auto-delete: " + labels[sel[0]], Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
