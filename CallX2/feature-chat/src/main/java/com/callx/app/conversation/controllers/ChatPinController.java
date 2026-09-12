package com.callx.app.conversation.controllers;

import android.view.View;

import androidx.annotation.NonNull;

import com.callx.app.chat.databinding.ActivityChatBinding;
import com.callx.app.chat.databinding.LayoutPinnedBannerBinding;
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

    // PERF: ll_pinned_banner is behind a ViewStub in activity_chat.xml
    // (see layout_pinned_banner.xml) — only inflated the first time a
    // message is actually pinned in this chat, instead of on every chat
    // screen open (most chats never pin anything). Cached here once
    // inflated; null until then — use pinnedBanner(binding) to get it,
    // never reference the field directly.
    private LayoutPinnedBannerBinding pinnedBannerBinding;

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

                        ActivityChatBinding binding = delegate.getBinding();
                        if (binding == null) return;

                        if (pinnedMsgId != null) {
                            // PERF: only inflates the banner the first time
                            // a chat actually has a pinned message.
                            LayoutPinnedBannerBinding pb = pinnedBanner(binding);
                            pb.getRoot().setVisibility(View.VISIBLE);
                            pb.tvPinnedPreview.setText(
                                    pinnedMsgText != null ? pinnedMsgText : "Pinned message");
                            final String msgId = pinnedMsgId;
                            pb.getRoot().setOnClickListener(v -> delegate.navigateToOriginal(msgId));
                        } else if (pinnedBannerBinding != null) {
                            // Don't force-inflate just to hide it — if it
                            // was never shown yet, it's already gone.
                            pinnedBannerBinding.getRoot().setVisibility(View.GONE);
                            pinnedBannerBinding.getRoot().setOnClickListener(null);
                        }
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {}
                });
    }

    /** Lazily inflates the pinned-banner ViewStub on first use, wiring the
     *  static unpin click just once. Safe to call repeatedly — only
     *  actually inflates once per activity lifetime. */
    private LayoutPinnedBannerBinding pinnedBanner(ActivityChatBinding binding) {
        if (pinnedBannerBinding == null) {
            View inflated = binding.stubPinnedBanner.inflate();
            pinnedBannerBinding = LayoutPinnedBannerBinding.bind(inflated);
            pinnedBannerBinding.btnUnpin.setOnClickListener(v -> unpinMessage());
        }
        return pinnedBannerBinding;
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
