package com.callx.app.conversation.controllers;

import android.app.AlertDialog;
import android.net.Uri;
import android.view.View;
import android.widget.Toast;

import com.callx.app.chat.databinding.ActivityChatBinding;
import com.callx.app.chat.ui.BubbleShapeBottomSheet;
import com.callx.app.chat.ui.ChatCustomizationBottomSheet;
import com.callx.app.chat.ui.ChatPrivacyBottomSheet;
import com.callx.app.chat.ui.ChatSecurityBottomSheet;
import com.callx.app.chat.ui.ChatThemeBottomSheet;
import com.callx.app.chat.ui.MessageFontSizeBottomSheet;
import com.callx.app.chat.ui.TypingStyleBottomSheet;
import com.callx.app.utils.ChatPrivacyManager;
import com.callx.app.utils.ChatThemeManager;
import com.callx.app.utils.ChatWallpaperManager;
import com.callx.app.utils.TypingStyleManager;
import com.callx.app.utils.UnicodeStyler;

/**
 * Handles chat screen theme, wallpaper, typing style, customization
 * and privacy/security dialog logic.
 */
public class ChatThemeController {

    private final ChatActivityDelegate delegate;

    public ChatThemeController(ChatActivityDelegate delegate) {
        this.delegate = delegate;
    }

    // ── Screen theme ──────────────────────────────────────────────────────

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

        TypingStyleManager.get(delegate.getActivity()).applyToInput(binding.etMessage);
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

    // ── Wallpaper picker ──────────────────────────────────────────────────

    public void showWallpaperPicker() {
        delegate.launchWallpaperPicker();
    }

    public void showWallpaperScopeDialog(Uri uri) {
        ChatWallpaperManager wm = ChatWallpaperManager.get(delegate.getActivity());
        String[] options = {"\uD83D\uDE4B This chat only", "\uD83C\uDF10 All chats (Global)", "\u274C Remove wallpaper"};
        new AlertDialog.Builder(delegate.getActivity())
                .setTitle("\uD83D\uDDBC\uFE0F Set Wallpaper")
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

    // ── Typing style ──────────────────────────────────────────────────────

    public void showTypingStylePicker() {
        TypingStyleBottomSheet sheet = TypingStyleBottomSheet.newInstance();
        sheet.setOnStyleSelectedListener(which -> {
            if (which == -1) {
                showSamsungStyleSubmenu(TypingStyleManager.get(delegate.getActivity()));
                return;
            }
            delegate.getBinding().etMessage.post(() ->
                    TypingStyleManager.get(delegate.getActivity())
                            .applyToInput(delegate.getBinding().etMessage));
        });
        sheet.show(delegate.getSupportFragmentManager(), TypingStyleBottomSheet.TAG);
    }

    public void showSamsungStyleSubmenu(TypingStyleManager mgr) {
        String scriptPreview = UnicodeStyler.toScript("Samsung Style");
        String[] options = {"\uD83C\uDD38 Samsung One (Font)", scriptPreview + " (Script \u2728)"};
        new AlertDialog.Builder(delegate.getActivity())
                .setTitle("\uD83C\uDD38 Samsung Style \u2014 Choose")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        mgr.setStyle(TypingStyleManager.STYLE_SAMSUNG);
                    } else {
                        mgr.setStyle(TypingStyleManager.STYLE_SAMSUNG_SCRIPT);
                    }
                    delegate.getBinding().etMessage.post(() ->
                            mgr.applyToInput(delegate.getBinding().etMessage));
                })
                .setNegativeButton("Back", (d, w) -> showTypingStylePicker())
                .show();
    }

    // ── Theme / bubble / font size pickers ────────────────────────────────

    public void showThemePicker() {
        ChatThemeBottomSheet sheet = ChatThemeBottomSheet.newInstance();
        sheet.setOnThemeSelectedListener(which -> {
            if (delegate.getPagingAdapter() != null)
                delegate.getPagingAdapter().notifyDataSetChanged();
            applyScreenTheme();
        });
        sheet.show(delegate.getSupportFragmentManager(), ChatThemeBottomSheet.TAG);
    }

    public void showBubbleShapePicker() {
        BubbleShapeBottomSheet sheet = BubbleShapeBottomSheet.newInstance();
        sheet.setOnShapeSelectedListener(which -> {
            if (delegate.getPagingAdapter() != null)
                delegate.getPagingAdapter().notifyDataSetChanged();
        });
        sheet.show(delegate.getSupportFragmentManager(), BubbleShapeBottomSheet.TAG);
    }

    public void showFontSizePicker() {
        MessageFontSizeBottomSheet sheet = MessageFontSizeBottomSheet.newInstance();
        sheet.setOnSizeSelectedListener(which -> {
            if (delegate.getPagingAdapter() != null)
                delegate.getPagingAdapter().notifyDataSetChanged();
        });
        sheet.show(delegate.getSupportFragmentManager(), MessageFontSizeBottomSheet.TAG);
    }

    // ── Customization menu ────────────────────────────────────────────────

    public void showChatCustomizationMenu() {
        ChatCustomizationBottomSheet sheet = ChatCustomizationBottomSheet.newInstance();
        sheet.setOnOptionSelectedListener(option -> {
            switch (option) {
                case ChatCustomizationBottomSheet.OPTION_WALLPAPER: showWallpaperPicker();   break;
                case ChatCustomizationBottomSheet.OPTION_THEME:     showThemePicker();       break;
                case ChatCustomizationBottomSheet.OPTION_BUBBLE:    showBubbleShapePicker(); break;
                case ChatCustomizationBottomSheet.OPTION_TYPING:    showTypingStylePicker(); break;
                case ChatCustomizationBottomSheet.OPTION_FONT_SIZE: showFontSizePicker();    break;
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
                "\u23F3 Disappearing Messages  [" + pm.getDisappearingLabel() + "]",
                "\u23F1 Message Timer  [" + pm.getMsgTimerLabel() + "]",
                "\uD83D\uDDD1 Auto-Delete Old Messages  [" + pm.getAutoDeleteLabel() + "]"
        };
        new AlertDialog.Builder(delegate.getActivity())
                .setTitle("\uD83D\uDEE1 Chat Privacy \u2014 " + displayName)
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
                .setTitle("\u23F3 Disappearing Messages")
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
                .setTitle("\u23F1 Message Timer")
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
                .setTitle("\uD83D\uDDD1 Auto-Delete Old Messages")
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
