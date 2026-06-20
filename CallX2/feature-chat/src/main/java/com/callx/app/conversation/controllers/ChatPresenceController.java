package com.callx.app.conversation.controllers;

import android.view.View;

import androidx.annotation.NonNull;

import com.callx.app.chat.databinding.ActivityChatBinding;
import com.callx.app.models.Message;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.SecurityManager;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Handles typing indicator, online/last-seen status, mute, mark-read logic,
 * and "in-chat-screen" presence (whether the partner currently has THIS
 * chat screen open and in foreground, vs having left it for another
 * screen/app).
 */
public class ChatPresenceController {

    /** Firebase node: chatPresence/{chatId}/{uid} = true while that user has this chat screen open & foregrounded. */
    private static final String CHAT_PRESENCE_NODE = "chatPresence";

    private final ChatActivityDelegate delegate;

    private ValueEventListener typingListener;
    private ValueEventListener onlineListener;
    private ValueEventListener inChatListener;

    // Header-line render state — combined to decide what tv_status / tv_typing / tv_in_chat show.
    private boolean partnerTyping     = false;
    private boolean partnerInChat     = false;
    private String  lastStatusText    = "";

    public ChatPresenceController(ChatActivityDelegate delegate) {
        this.delegate = delegate;
    }

    // ── Init ──────────────────────────────────────────────────────────────

    public void init() {
        watchPartnerStatus();
        watchTyping();
        watchPartnerInChatScreen();
        watchMute();
        markMessagesRead();
    }

    // ── Typing ────────────────────────────────────────────────────────────

    public void setOurTypingStatus(boolean typing) {
        FirebaseUtils.db().getReference("typing")
                .child(delegate.getChatId()).child(delegate.getCurrentUid()).setValue(typing);
    }

    public void clearOurTypingStatus() {
        FirebaseUtils.db().getReference("typing")
                .child(delegate.getChatId()).child(delegate.getCurrentUid()).setValue(false);
    }

    private void watchTyping() {
        typingListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                boolean typing = false;
                for (DataSnapshot child : s.getChildren()) {
                    if (child.getKey() != null
                            && !child.getKey().equals(delegate.getCurrentUid())
                            && Boolean.TRUE.equals(child.getValue(Boolean.class))) {
                        typing = true;
                        break;
                    }
                }
                partnerTyping = typing;
                renderHeaderStatus();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        FirebaseUtils.db().getReference("typing").child(delegate.getChatId())
                .addValueEventListener(typingListener);
    }

    // ── In-chat-screen presence ──────────────────────────────────────────
    // Tracks whether the partner currently has THIS chat screen open & in
    // foreground (as opposed to having navigated to another screen in the
    // app, or having left the app entirely).

    /** Called by the Activity on resume/pause to publish our own in-chat presence. */
    public void setOurInChatScreen(boolean active) {
        String chatId = delegate.getChatId();
        String uid = delegate.getCurrentUid();
        if (chatId == null || uid == null) return;

        DatabaseReference ref = FirebaseUtils.db().getReference(CHAT_PRESENCE_NODE)
                .child(chatId).child(uid);
        ref.setValue(active);
        if (active) {
            // Safety net: if the app/connection dies without onPause firing
            // (process killed, network drop), Firebase clears this for us.
            ref.onDisconnect().setValue(false);
        } else {
            ref.onDisconnect().cancel();
        }
    }

    private void watchPartnerInChatScreen() {
        String chatId = delegate.getChatId();
        String partnerUid = delegate.getPartnerUid();
        if (chatId == null || partnerUid == null || partnerUid.isEmpty()) return;

        inChatListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                partnerInChat = Boolean.TRUE.equals(s.getValue(Boolean.class));
                renderHeaderStatus();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        FirebaseUtils.db().getReference(CHAT_PRESENCE_NODE)
                .child(chatId).child(partnerUid)
                .addValueEventListener(inChatListener);
    }

    // ── Header line renderer ──────────────────────────────────────────────
    // Priority shown in the chat header (top to bottom wins):
    //   1) "typing…"                (partnerTyping)
    //   2) "active in this chat"    (partnerInChat, partner has this screen open)
    //   3) "online" / "last seen…"  (lastStatusText, from watchPartnerStatus)

    private void renderHeaderStatus() {
        ActivityChatBinding binding = delegate.getBinding();
        if (binding.tvStatus == null || binding.tvTyping == null || binding.tvInChat == null) return;

        if (partnerTyping) {
            binding.tvTyping.setVisibility(View.VISIBLE);
            binding.tvInChat.setVisibility(View.GONE);
            binding.tvStatus.setVisibility(View.GONE);
        } else if (partnerInChat) {
            binding.tvTyping.setVisibility(View.GONE);
            binding.tvInChat.setVisibility(View.VISIBLE);
            binding.tvStatus.setVisibility(View.GONE);
        } else {
            binding.tvTyping.setVisibility(View.GONE);
            binding.tvInChat.setVisibility(View.GONE);
            binding.tvStatus.setVisibility(lastStatusText.length() > 0 ? View.VISIBLE : View.GONE);
        }
    }

    // ── Online / last-seen status ─────────────────────────────────────────

    private void watchPartnerStatus() {
        String partnerUid = delegate.getPartnerUid();
        if (partnerUid == null || partnerUid.isEmpty()) return;

        onlineListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                ActivityChatBinding binding = delegate.getBinding();
                if (binding.tvStatus == null) return;

                Boolean partnerGhost = s.child("privacy").child("ghost").getValue(Boolean.class);
                if (Boolean.TRUE.equals(partnerGhost)) {
                    lastStatusText = "";
                    binding.tvStatus.setText("");
                    renderHeaderStatus();
                    return;
                }

                String lastSeenVis = s.child("privacy").child("lastSeenVisibility").getValue(String.class);
                boolean hideLastSeen = SecurityManager.VIS_NOBODY.equals(lastSeenVis);

                Boolean online   = s.child("online").getValue(Boolean.class);
                Long lastSeen    = s.child("lastSeen").getValue(Long.class);

                String statusText;
                if (Boolean.TRUE.equals(online)) {
                    Boolean partnerIncognito = s.child("privacy").child("incognito").getValue(Boolean.class);
                    statusText = Boolean.TRUE.equals(partnerIncognito) ? "" : "online";
                } else if (!hideLastSeen && lastSeen != null && lastSeen > 0) {
                    statusText = formatLastSeenRelative(lastSeen);
                } else {
                    statusText = "";
                }

                lastStatusText = statusText;
                binding.tvStatus.setText(statusText);
                renderHeaderStatus();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        FirebaseUtils.getUserRef(partnerUid).addValueEventListener(onlineListener);
    }

    private String formatLastSeenRelative(long ts) {
        long diff = System.currentTimeMillis() - ts;
        if (diff < 0) diff = 0;
        if (diff < 60_000L) {
            return "last seen just now";
        } else if (diff < 3_600_000L) {
            long mins = diff / 60_000L;
            return "last seen " + mins + " min" + (mins == 1 ? "" : "s") + " ago";
        } else if (diff < 86_400_000L) {
            return "last seen at " + new SimpleDateFormat("hh:mm a", Locale.getDefault()).format(new Date(ts));
        } else if (diff < 7 * 86_400_000L) {
            return "last seen " + new SimpleDateFormat("EEE, hh:mm a", Locale.getDefault()).format(new Date(ts));
        } else {
            return "last seen " + new SimpleDateFormat("dd MMM", Locale.getDefault()).format(new Date(ts));
        }
    }

    // ── Mute ─────────────────────────────────────────────────────────────

    private void watchMute() {
        String currentUid = delegate.getCurrentUid();
        String partnerUid = delegate.getPartnerUid();
        if (currentUid == null || currentUid.isEmpty() || partnerUid == null || partnerUid.isEmpty()) return;

        FirebaseUtils.db().getReference("muted")
                .child(currentUid).child(partnerUid)
                .addValueEventListener(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot s) {
                        delegate.setMuted(Boolean.TRUE.equals(s.getValue(Boolean.class)));
                        delegate.invalidateMenu();
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {}
                });
    }

    public void toggleMute() {
        FirebaseUtils.db().getReference("muted")
                .child(delegate.getCurrentUid()).child(delegate.getPartnerUid())
                .setValue(!delegate.isMuted());
    }

    // ── Mark read ─────────────────────────────────────────────────────────

    public void markMessagesRead() {
        delegate.getIoExecutor().execute(() -> {
            if (delegate.getDb() != null && delegate.getChatId() != null) {
                delegate.getDb().chatDao().updateUnread(delegate.getChatId(), 0);
            }
        });
        if (delegate.isOnline()) {
            FirebaseUtils.getContactsRef(delegate.getCurrentUid())
                    .child(delegate.getPartnerUid()).child("unread").setValue(0);
        } else {
            delegate.getIoExecutor().execute(() -> {
                if (delegate.getDb() != null && delegate.getChatId() != null) {
                    delegate.getDb().chatDao().queueMarkRead(delegate.getChatId());
                }
            });
        }
    }

    public void markRead(Message m) {
        if (m == null || m.id == null) return;
        if (!delegate.getCurrentUid().equals(m.senderId) && !"read".equals(m.status)) {
            SecurityManager secMgr = new SecurityManager(delegate.getActivity());
            if (!secMgr.isReadReceiptsEnabled()) return;
            delegate.getMessagesRef().child(m.id).child("status").setValue("read");
            delegate.getIoExecutor().execute(() ->
                    delegate.getDb().messageDao().updateStatus(m.id, "read"));
        }
    }

    // ── Cleanup ───────────────────────────────────────────────────────────

    public void release() {
        if (typingListener != null && delegate.getChatId() != null) {
            FirebaseUtils.db().getReference("typing").child(delegate.getChatId())
                    .removeEventListener(typingListener);
        }
        if (onlineListener != null && delegate.getPartnerUid() != null) {
            FirebaseUtils.getUserRef(delegate.getPartnerUid())
                    .removeEventListener(onlineListener);
        }
        if (inChatListener != null && delegate.getChatId() != null && delegate.getPartnerUid() != null) {
            FirebaseUtils.db().getReference(CHAT_PRESENCE_NODE)
                    .child(delegate.getChatId()).child(delegate.getPartnerUid())
                    .removeEventListener(inChatListener);
        }
        clearOurTypingStatus();
        setOurInChatScreen(false);
    }
}
