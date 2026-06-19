package com.callx.app.conversation.delegates;

import android.app.Activity;
import android.app.AlertDialog;
import android.net.Uri;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.callx.app.chat.databinding.ActivityChatBinding;
import com.callx.app.conversation.MessagePagingAdapter;
import com.callx.app.models.Message;
import com.callx.app.utils.ChatPrivacyManager;
import com.callx.app.utils.ChatThemeManager;
import com.callx.app.utils.ChatWallpaperManager;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.TypingStyleManager;
import com.callx.app.utils.UnicodeStyler;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

/**
 * ChatCustomizeDelegate — Theme, wallpaper, typing style, font size, privacy settings,
 *                          pinned messages.
 */
public class ChatCustomizeDelegate {

    public interface Callback {
        MessagePagingAdapter getAdapter();
        void applyWallpaper();
    }

    /** Activity must implement to allow wallpaper picker launch from delegate. */
    public interface ChatCustomizationCallback {
        void launchWallpaperPicker();
    }

    private final Activity            activity;
    private final ActivityChatBinding binding;
    private final String              chatId;
    private final String              currentUid;
    private final String              partnerName;
    private final Callback            callback;

    private ValueEventListener pinnedListener;

    public ChatCustomizeDelegate(Activity activity, ActivityChatBinding binding, String chatId,
                                 String currentUid, String partnerName, Callback callback) {
        this.activity    = activity;
        this.binding     = binding;
        this.chatId      = chatId;
        this.currentUid  = currentUid;
        this.partnerName = partnerName;
        this.callback    = callback;
    }

    // ── Screen Theme ──────────────────────────────────────────────────────

    public void applyScreenTheme() {
        ChatThemeManager mgr = ChatThemeManager.get(activity);
        if (binding.tvReplyBarName != null) binding.tvReplyBarName.setTextColor(mgr.getPrimaryColor());
        mgr.applyScreenTheme(binding.toolbar, binding.getRoot(), binding.llInputRow,
                binding.btnSend, binding.btnMic, binding.fabBackToLatest, binding.viewReplyAccent);
        TypingStyleManager.get(activity).applyToInput(binding.etMessage);
        applyWallpaper();
    }

    // ── Wallpaper ─────────────────────────────────────────────────────────

    public void applyWallpaper() {
        android.widget.ImageView ivWall = binding.ivChatWallpaper;
        if (ivWall == null) return;
        String uriStr = ChatWallpaperManager.get(activity).getEffectiveWallpaper(chatId);
        if (uriStr == null) {
            ivWall.setVisibility(View.GONE); ivWall.setImageDrawable(null);
        } else {
            ivWall.setVisibility(View.VISIBLE);
            Glide.with(activity).load(Uri.parse(uriStr))
                    .diskCacheStrategy(DiskCacheStrategy.ALL).centerCrop().into(ivWall);
        }
    }

    public void showWallpaperScopeDialog(Uri uri) {
        ChatWallpaperManager wm = ChatWallpaperManager.get(activity);
        String[] opts = {"🙋 This chat only", "🌐 All chats (Global)", "❌ Remove wallpaper"};
        new AlertDialog.Builder(activity).setTitle("🖼️ Set Wallpaper")
                .setItems(opts, (d, which) -> {
                    if (which == 0) { wm.setWallpaper(chatId, uri); applyWallpaper(); }
                    else if (which == 1) { wm.setGlobalWallpaper(uri); applyWallpaper(); }
                    else { wm.clearWallpaper(chatId); wm.clearGlobalWallpaper(); applyWallpaper(); }
                }).setNegativeButton("Cancel", null).show();
    }

    // ── Typing Style ──────────────────────────────────────────────────────

    public void showTypingStylePicker() {
        com.callx.app.chat.ui.TypingStyleBottomSheet sheet =
                com.callx.app.chat.ui.TypingStyleBottomSheet.newInstance();
        sheet.setOnStyleSelectedListener(which -> {
            if (which == -1) { showSamsungStyleSubmenu(TypingStyleManager.get(activity)); return; }
            binding.etMessage.post(() -> TypingStyleManager.get(activity).applyToInput(binding.etMessage));
        });
        sheet.show(((FragmentActivity) activity).getSupportFragmentManager(),
                com.callx.app.chat.ui.TypingStyleBottomSheet.TAG);
    }

    private void showSamsungStyleSubmenu(TypingStyleManager mgr) {
        String[] opts = {"🅢 Samsung One (Font)", UnicodeStyler.toScript("Samsung Style") + " (Script ✨)"};
        new AlertDialog.Builder(activity).setTitle("🅢 Samsung Style — Choose")
                .setItems(opts, (d, which) -> {
                    if (which == 0) mgr.setStyle(TypingStyleManager.STYLE_SAMSUNG);
                    else mgr.setStyle(TypingStyleManager.STYLE_SAMSUNG_SCRIPT);
                    binding.etMessage.post(() -> mgr.applyToInput(binding.etMessage));
                }).setNegativeButton("Back", (d, w) -> showTypingStylePicker()).show();
    }

    // ── Theme / Bubble ────────────────────────────────────────────────────

    public void showThemePicker() {
        com.callx.app.chat.ui.ChatThemeBottomSheet sheet =
                com.callx.app.chat.ui.ChatThemeBottomSheet.newInstance();
        sheet.setOnThemeSelectedListener(which -> {
            MessagePagingAdapter a = callback.getAdapter();
            if (a != null) a.notifyDataSetChanged();
            applyScreenTheme();
        });
        sheet.show(((FragmentActivity) activity).getSupportFragmentManager(),
                com.callx.app.chat.ui.ChatThemeBottomSheet.TAG);
    }

    public void showBubbleShapePicker() {
        com.callx.app.chat.ui.BubbleShapeBottomSheet sheet =
                com.callx.app.chat.ui.BubbleShapeBottomSheet.newInstance();
        sheet.setOnShapeSelectedListener(which -> {
            MessagePagingAdapter a = callback.getAdapter();
            if (a != null) a.notifyDataSetChanged();
        });
        sheet.show(((FragmentActivity) activity).getSupportFragmentManager(),
                com.callx.app.chat.ui.BubbleShapeBottomSheet.TAG);
    }

    // ── Customization menu ────────────────────────────────────────────────

    public void showChatCustomizationMenu() {
        com.callx.app.chat.ui.ChatCustomizationBottomSheet sheet =
                com.callx.app.chat.ui.ChatCustomizationBottomSheet.newInstance();
        sheet.setOnOptionSelectedListener(option -> {
            switch (option) {
                case com.callx.app.chat.ui.ChatCustomizationBottomSheet.OPTION_WALLPAPER:
                    if (activity instanceof ChatCustomizationCallback)
                        ((ChatCustomizationCallback) activity).launchWallpaperPicker();
                    break;
                case com.callx.app.chat.ui.ChatCustomizationBottomSheet.OPTION_THEME:   showThemePicker();       break;
                case com.callx.app.chat.ui.ChatCustomizationBottomSheet.OPTION_BUBBLE:  showBubbleShapePicker(); break;
                case com.callx.app.chat.ui.ChatCustomizationBottomSheet.OPTION_TYPING:  showTypingStylePicker(); break;
                case com.callx.app.chat.ui.ChatCustomizationBottomSheet.OPTION_FONT_SIZE: showFontSizePicker();  break;
            }
        });
        sheet.show(((FragmentActivity) activity).getSupportFragmentManager(),
                com.callx.app.chat.ui.ChatCustomizationBottomSheet.TAG);
    }

    public void showFontSizePicker() {
        com.callx.app.chat.ui.MessageFontSizeBottomSheet sheet =
                com.callx.app.chat.ui.MessageFontSizeBottomSheet.newInstance();
        sheet.setOnSizeSelectedListener(which -> {
            MessagePagingAdapter a = callback.getAdapter();
            if (a != null) a.notifyDataSetChanged();
        });
        sheet.show(((FragmentActivity) activity).getSupportFragmentManager(),
                com.callx.app.chat.ui.MessageFontSizeBottomSheet.TAG);
    }

    // ── Security / Privacy ────────────────────────────────────────────────

    public void showChatSecuritySheet() {
        com.callx.app.chat.ui.ChatSecurityBottomSheet sheet =
                com.callx.app.chat.ui.ChatSecurityBottomSheet.newInstance();
        sheet.show(((FragmentActivity) activity).getSupportFragmentManager(),
                com.callx.app.chat.ui.ChatSecurityBottomSheet.TAG);
    }

    public void showChatPrivacySheet() {
        ChatPrivacyManager pm = new ChatPrivacyManager(activity, chatId, false);
        String dn = partnerName != null ? partnerName : "Chat";
        String[] opts = {
            "⏳ Disappearing Messages  [" + pm.getDisappearingLabel() + "]",
            "⏱ Message Timer  [" + pm.getMsgTimerLabel() + "]",
            "🗑 Auto-Delete Old Messages  [" + pm.getAutoDeleteLabel() + "]"
        };
        new AlertDialog.Builder(activity).setTitle("🛡 Chat Privacy — " + dn)
                .setItems(opts, (d, which) -> {
                    if (which == 0) showDisappearingDialog(pm);
                    else if (which == 1) showMsgTimerDialog(pm);
                    else showAutoDeleteDialog(pm);
                }).setNegativeButton("Close", null).show();
    }

    private void showDisappearingDialog(ChatPrivacyManager pm) {
        String[] lbl = {"Off", "24 hours", "7 days", "30 days"};
        long[] val = {ChatPrivacyManager.DISAPPEAR_OFF, ChatPrivacyManager.DISAPPEAR_24H,
                      ChatPrivacyManager.DISAPPEAR_7D, ChatPrivacyManager.DISAPPEAR_30D};
        long cur = pm.getDisappearingMs(); int chk = 0;
        for (int i = 0; i < val.length; i++) if (val[i] == cur) { chk = i; break; }
        final int[] sel = {chk};
        new AlertDialog.Builder(activity).setTitle("⏳ Disappearing Messages")
                .setSingleChoiceItems(lbl, chk, (d, w) -> sel[0] = w)
                .setPositiveButton("Set", (d, w) -> {
                    pm.setDisappearingMs(val[sel[0]]);
                    Toast.makeText(activity, "Disappearing: " + lbl[sel[0]], Toast.LENGTH_SHORT).show();
                }).setNegativeButton("Cancel", null).show();
    }

    private void showMsgTimerDialog(ChatPrivacyManager pm) {
        String[] lbl = {"Off","10 seconds","30 seconds","1 minute","5 minutes","1 hour"};
        long[] val = {ChatPrivacyManager.MSG_TIMER_OFF, ChatPrivacyManager.MSG_TIMER_10S,
                      ChatPrivacyManager.MSG_TIMER_30S, ChatPrivacyManager.MSG_TIMER_1M,
                      ChatPrivacyManager.MSG_TIMER_5M,  ChatPrivacyManager.MSG_TIMER_1H};
        long cur = pm.getMsgTimerMs(); int chk = 0;
        for (int i = 0; i < val.length; i++) if (val[i] == cur) { chk = i; break; }
        final int[] sel = {chk};
        new AlertDialog.Builder(activity).setTitle("⏱ Message Timer")
                .setSingleChoiceItems(lbl, chk, (d, w) -> sel[0] = w)
                .setPositiveButton("Set", (d, w) -> {
                    pm.setMsgTimerMs(val[sel[0]]);
                    Toast.makeText(activity, "Timer: " + lbl[sel[0]], Toast.LENGTH_SHORT).show();
                }).setNegativeButton("Cancel", null).show();
    }

    private void showAutoDeleteDialog(ChatPrivacyManager pm) {
        String[] lbl = {"Never","After 7 days","After 30 days","After 90 days","After 6 months"};
        long[] val = {ChatPrivacyManager.AUTO_DELETE_OFF, ChatPrivacyManager.AUTO_DELETE_7D,
                      ChatPrivacyManager.AUTO_DELETE_30D, ChatPrivacyManager.AUTO_DELETE_90D,
                      ChatPrivacyManager.AUTO_DELETE_180D};
        long cur = pm.getAutoDeleteDays(); int chk = 0;
        for (int i = 0; i < val.length; i++) if (val[i] == cur) { chk = i; break; }
        final int[] sel = {chk};
        new AlertDialog.Builder(activity).setTitle("🗑 Auto-Delete Old Messages")
                .setSingleChoiceItems(lbl, chk, (d, w) -> sel[0] = w)
                .setPositiveButton("Set", (d, w) -> {
                    pm.setAutoDeleteDays(val[sel[0]]);
                    Toast.makeText(activity, "Auto-delete: " + lbl[sel[0]], Toast.LENGTH_SHORT).show();
                }).setNegativeButton("Cancel", null).show();
    }

    // ── Pinned Message ────────────────────────────────────────────────────

    public void watchPinnedMessage() {
        pinnedListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                String pinnedId = s.getValue(String.class);
                if (binding.llPinnedMessage == null) return;
                if (pinnedId == null || pinnedId.isEmpty()) { binding.llPinnedMessage.setVisibility(View.GONE); return; }
                FirebaseUtils.db().getReference("messages").child(chatId).child(pinnedId).get()
                        .addOnSuccessListener(ms -> {
                            Message m = ms.getValue(Message.class);
                            if (m == null) { binding.llPinnedMessage.setVisibility(View.GONE); return; }
                            String prev = (m.text != null && !m.text.isEmpty()) ? m.text : (m.type != null ? "📎 " + m.type : "Pinned message");
                            binding.tvPinnedText.setText(prev);
                            binding.llPinnedMessage.setVisibility(View.VISIBLE);
                            binding.ivUnpin.setOnClickListener(v -> unpinMessage(pinnedId, chatId));
                        });
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        FirebaseUtils.db().getReference("pinned").child(chatId).addValueEventListener(pinnedListener);
    }

    public void pinMessage(String messageId, String chatId) {
        FirebaseUtils.db().getReference("pinned").child(chatId).setValue(messageId);
    }

    public void unpinMessage(String messageId, String chatId) {
        FirebaseUtils.db().getReference("pinned").child(chatId).removeValue();
    }

    public void detach() {
        if (pinnedListener != null && chatId != null)
            FirebaseUtils.db().getReference("pinned").child(chatId).removeEventListener(pinnedListener);
    }
}
