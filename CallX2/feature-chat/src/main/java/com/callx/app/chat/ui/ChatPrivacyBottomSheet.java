package com.callx.app.chat.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.callx.app.chat.R;
import com.callx.app.utils.ChatPrivacyManager;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/**
 * ChatPrivacyBottomSheet — Per-chat privacy controls.
 *
 * Launched from ChatActivity / GroupChatActivity menu → "🛡 Chat Privacy"
 *
 * Contains 3 sections:
 *   1. ⏳ Disappearing Messages  (24h / 7d / 30d)
 *   2. ⏱  Message Timer          (self-destruct after reading)
 *   3. 🗑  Auto-Delete Old Msgs   (after 7d / 30d / 90d / 6mo)
 *
 * Uses ChatPrivacyManager for per-chatId storage + Firebase sync.
 */
public class ChatPrivacyBottomSheet extends BottomSheetDialogFragment {

    public static final String TAG = "ChatPrivacyBottomSheet";

    private static final String ARG_CHAT_ID  = "chatId";
    private static final String ARG_IS_GROUP = "isGroup";
    private static final String ARG_CHAT_NAME = "chatName";

    private ChatPrivacyManager privacyMgr;

    // ── Factory ───────────────────────────────────────────────────────────

    public static ChatPrivacyBottomSheet newInstance(String chatId, boolean isGroup, String chatName) {
        ChatPrivacyBottomSheet sheet = new ChatPrivacyBottomSheet();
        Bundle args = new Bundle();
        args.putString(ARG_CHAT_ID, chatId);
        args.putBoolean(ARG_IS_GROUP, isGroup);
        args.putString(ARG_CHAT_NAME, chatName);
        sheet.setArguments(args);
        return sheet;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_chat_privacy, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);

        Bundle args = getArguments();
        String chatId   = args != null ? args.getString(ARG_CHAT_ID, "") : "";
        boolean isGroup = args != null && args.getBoolean(ARG_IS_GROUP, false);
        String chatName = args != null ? args.getString(ARG_CHAT_NAME, "Chat") : "Chat";

        privacyMgr = new ChatPrivacyManager(requireContext(), chatId, isGroup);

        // Header subtitle
        TextView tvSubtitle = v.findViewById(R.id.tv_privacy_subtitle);
        if (tvSubtitle != null) tvSubtitle.setText(chatName);

        setupDisappearing(v);
        setupMsgTimer(v);
        setupAutoDelete(v);

        // Drag handle / close
        View dragHandle = v.findViewById(R.id.drag_handle);
        if (dragHandle != null) dragHandle.setOnClickListener(x -> dismiss());
    }

    // ── 1. Disappearing Messages ──────────────────────────────────────────

    private void setupDisappearing(View v) {
        TextView tvVal = v.findViewById(R.id.tv_disappearing_val);
        tvVal.setText(privacyMgr.getDisappearingLabel());

        v.findViewById(R.id.row_disappearing).setOnClickListener(x -> {
            String[] labels = {"Off", "24 hours", "7 days", "30 days"};
            long[]   values = {
                ChatPrivacyManager.DISAPPEAR_OFF,
                ChatPrivacyManager.DISAPPEAR_24H,
                ChatPrivacyManager.DISAPPEAR_7D,
                ChatPrivacyManager.DISAPPEAR_30D
            };

            long current = privacyMgr.getDisappearingMs();
            int checkedIdx = 0;
            for (int i = 0; i < values.length; i++) {
                if (values[i] == current) { checkedIdx = i; break; }
            }
            final int[] sel = {checkedIdx};

            new AlertDialog.Builder(requireContext())
                .setTitle("⏳ Disappearing Messages")
                .setMessage("New messages in this chat will disappear after the selected time.")
                .setSingleChoiceItems(labels, checkedIdx, (d, which) -> sel[0] = which)
                .setPositiveButton("Set", (d, w) -> {
                    privacyMgr.setDisappearingMs(values[sel[0]]);
                    tvVal.setText(labels[sel[0]]);
                    toast("Disappearing messages: " + labels[sel[0]]);
                })
                .setNegativeButton("Cancel", null)
                .show();
        });
    }

    // ── 2. Message Timer ──────────────────────────────────────────────────

    private void setupMsgTimer(View v) {
        TextView tvVal = v.findViewById(R.id.tv_msg_timer_val);
        tvVal.setText(privacyMgr.getMsgTimerLabel());

        v.findViewById(R.id.row_msg_timer).setOnClickListener(x -> {
            String[] labels = {"Off", "10 seconds", "30 seconds", "1 minute", "5 minutes", "1 hour"};
            long[]   values = {
                ChatPrivacyManager.MSG_TIMER_OFF,
                ChatPrivacyManager.MSG_TIMER_10S,
                ChatPrivacyManager.MSG_TIMER_30S,
                ChatPrivacyManager.MSG_TIMER_1M,
                ChatPrivacyManager.MSG_TIMER_5M,
                ChatPrivacyManager.MSG_TIMER_1H
            };

            long current = privacyMgr.getMsgTimerMs();
            int checkedIdx = 0;
            for (int i = 0; i < values.length; i++) {
                if (values[i] == current) { checkedIdx = i; break; }
            }
            final int[] sel = {checkedIdx};

            new AlertDialog.Builder(requireContext())
                .setTitle("⏱ Message Timer")
                .setMessage("Messages will be automatically deleted after this time once seen by the recipient.")
                .setSingleChoiceItems(labels, checkedIdx, (d, which) -> sel[0] = which)
                .setPositiveButton("Set", (d, w) -> {
                    privacyMgr.setMsgTimerMs(values[sel[0]]);
                    tvVal.setText(labels[sel[0]]);
                    toast("Message timer: " + labels[sel[0]]);
                })
                .setNegativeButton("Cancel", null)
                .show();
        });
    }

    // ── 3. Auto-Delete Old Messages ───────────────────────────────────────

    private void setupAutoDelete(View v) {
        TextView tvVal = v.findViewById(R.id.tv_auto_delete_val);
        tvVal.setText(privacyMgr.getAutoDeleteLabel());

        v.findViewById(R.id.row_auto_delete).setOnClickListener(x -> {
            String[] labels = {"Never", "After 7 days", "After 30 days", "After 90 days", "After 6 months"};
            long[]   values = {
                ChatPrivacyManager.AUTO_DELETE_OFF,
                ChatPrivacyManager.AUTO_DELETE_7D,
                ChatPrivacyManager.AUTO_DELETE_30D,
                ChatPrivacyManager.AUTO_DELETE_90D,
                ChatPrivacyManager.AUTO_DELETE_180D
            };

            long current = privacyMgr.getAutoDeleteDays();
            int checkedIdx = 0;
            for (int i = 0; i < values.length; i++) {
                if (values[i] == current) { checkedIdx = i; break; }
            }
            final int[] sel = {checkedIdx};

            new AlertDialog.Builder(requireContext())
                .setTitle("🗑 Auto-Delete Old Messages")
                .setMessage("Messages older than the selected period will be automatically removed from this chat on your device.")
                .setSingleChoiceItems(labels, checkedIdx, (d, which) -> sel[0] = which)
                .setPositiveButton("Set", (d, w) -> {
                    privacyMgr.setAutoDeleteDays(values[sel[0]]);
                    tvVal.setText(labels[sel[0]]);
                    toast("Auto-delete: " + labels[sel[0]]);
                })
                .setNegativeButton("Cancel", null)
                .show();
        });
    }

    // ── Helper ────────────────────────────────────────────────────────────

    private void toast(String msg) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
    }
}
