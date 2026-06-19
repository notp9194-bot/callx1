package com.callx.app.conversation.controllers;

import android.view.View;

import androidx.annotation.NonNull;

import com.callx.app.models.Message;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.Map;

/**
 * Handles watch / pin / unpin message logic.
 */
public class ChatPinController {

    private final ChatActivityDelegate delegate;

    private String pinnedMsgId   = null;
    private String pinnedMsgText = null;

    public ChatPinController(ChatActivityDelegate delegate) {
        this.delegate = delegate;
    }

    // ── Init ──────────────────────────────────────────────────────────────

    public void init() {
        watchPinnedMessage();
    }

    // ── Watch ─────────────────────────────────────────────────────────────

    private void watchPinnedMessage() {
        FirebaseUtils.db().getReference("pinnedMessages").child(delegate.getChatId())
                .addValueEventListener(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot s) {
                        pinnedMsgId   = s.child("id").getValue(String.class);
                        pinnedMsgText = s.child("text").getValue(String.class);

                        com.callx.app.chat.databinding.ActivityChatBinding binding = delegate.getBinding();
                        if (binding.llPinnedBanner == null) return;

                        if (pinnedMsgId != null) {
                            binding.llPinnedBanner.setVisibility(View.VISIBLE);
                            if (binding.tvPinnedPreview != null)
                                binding.tvPinnedPreview.setText(
                                        pinnedMsgText != null ? pinnedMsgText : "Pinned message");
                            final String msgId = pinnedMsgId;
                            binding.llPinnedBanner.setOnClickListener(v -> delegate.navigateToOriginal(msgId));
                            if (binding.btnUnpin != null)
                                binding.btnUnpin.setOnClickListener(v -> unpinMessage());
                        } else {
                            binding.llPinnedBanner.setVisibility(View.GONE);
                            binding.llPinnedBanner.setOnClickListener(null);
                        }
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {}
                });
    }

    // ── Unpin ─────────────────────────────────────────────────────────────

    public void unpinMessage() {
        FirebaseUtils.db().getReference("pinnedMessages").child(delegate.getChatId()).removeValue();
    }

    // ── Pin ───────────────────────────────────────────────────────────────

    public void pinMessage(Message m) {
        if (m == null || m.id == null) return;
        DatabaseReference pinRef =
                FirebaseUtils.db().getReference("pinnedMessages").child(delegate.getChatId());
        if (Boolean.TRUE.equals(m.pinned)) {
            pinRef.removeValue();
            delegate.getMessagesRef().child(m.id).child("pinned").setValue(false);
        } else {
            String preview = (m.text != null && !m.text.isEmpty())
                    ? m.text
                    : (m.type != null ? "[" + m.type + "]" : "Pinned message");
            Map<String, Object> pinData = new HashMap<>();
            pinData.put("id",   m.id);
            pinData.put("text", preview);
            pinRef.setValue(pinData);
            delegate.getMessagesRef().child(m.id).child("pinned").setValue(true);
        }
    }
}
