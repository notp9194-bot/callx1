package com.callx.app.conversation.controllers;

import android.widget.Toast;

import androidx.annotation.NonNull;

import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.MessageEntity;
import com.callx.app.models.Message;
import com.callx.app.utils.ChatPrivacyManager;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.PushNotify;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Handles text-message sending, Firebase push, local-first Room insert,
 * and retry of pending messages on reconnect.
 */
public class ChatMessageSender {

    private final ChatActivityDelegate delegate;

    public ChatMessageSender(ChatActivityDelegate delegate) {
        this.delegate = delegate;
    }

    // ── Push message (local-first) ────────────────────────────────────────

    /**
     * Saves message to Room as "pending", then pushes to Firebase if online.
     * If offline, the message stays pending until retryPendingMessages() is called.
     */
    public void pushMessage(Message m, String previewText) {
        String key = delegate.getMessagesRef().push().getKey();
        if (key == null) return;
        m.id = key;

        ChatPrivacyManager privMgr =
                new ChatPrivacyManager(delegate.getActivity(), delegate.getChatId(), false);
        long disappearMs = privMgr.getDisappearingMs();
        if (disappearMs > 0) m.expiresAt = m.timestamp + disappearMs;

        // Feature 13: View Once — if caller tagged this message as view-once,
        // ensure state is "sent". ChatViewOnceController.tagMessageAsViewOnce()
        // sets viewOnce=true before pushMessage() is called.
        if (Boolean.TRUE.equals(m.viewOnce) && m.viewOnceState == null) {
            m.viewOnceState = com.callx.app.conversation.controllers.ChatViewOnceController.STATE_SENT;
        }

        // Feature 2: schedule expiry WorkManager job now that we have the real key.
        // m.viewOnceExpiresAt is already set by ChatActivity.pushMessage() if sender
        // picked a duration. We schedule here (not in ChatActivity) because this is
        // the first place the Firebase key (m.id) is known.
        if (Boolean.TRUE.equals(m.viewOnce)
                && m.viewOnceExpiresAt != null && m.viewOnceExpiresAt > 0) {
            long delayMs = m.viewOnceExpiresAt - System.currentTimeMillis();
            if (delayMs > 0) {
                String chatId = delegate.getChatId();
                androidx.work.Data inputData = new androidx.work.Data.Builder()
                        .putString("messageId", key)
                        .putString("chatId", chatId)
                        .build();
                androidx.work.OneTimeWorkRequest req =
                        new androidx.work.OneTimeWorkRequest.Builder(
                                com.callx.app.conversation.workers.ViewOnceExpiryWorker.class)
                        .setInitialDelay(delayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                        .setInputData(inputData)
                        .addTag("view_once_expiry_" + key)
                        .build();
                androidx.work.WorkManager.getInstance(delegate.getActivity()).enqueue(req);
            }
        }

        MessageEntity entity = messageToEntity(m, "pending");

        // BUG FIX: this insertMessage() write happens IMMEDIATELY on send —
        // before any Firebase round-trip — and bypasses ChatActivity's
        // buffered flushPendingRoomWrites() entirely (this is a separate,
        // direct write on its own executor). It needs the SAME sever-
        // before-write / reanchor-after-write protection, or sending a
        // message still triggers the old top-jump bug even though
        // receiving is now fixed. See ChatActivity#severPagingIfAtBottom().
        boolean willReanchor = delegate.severPagingIfAtBottom();
        Executors.newSingleThreadExecutor().execute(() -> {
            AppDatabase.getInstance(delegate.getActivity()).messageDao().insertMessage(entity);
            if (willReanchor) delegate.reanchorPagingToBottom();
        });

        if (delegate.isOnline()) {
            firebasePushMessage(m, key, previewText);
        } else {
            Toast.makeText(delegate.getActivity(),
                    "No connection — message queued", Toast.LENGTH_SHORT).show();
        }
    }

    /** Push to Firebase and update Room status to "sent". */
    public void firebasePushMessage(Message m, String key, String previewText) {
        delegate.getMessagesRef().child(key).setValue(m)
                .addOnSuccessListener(unused -> {
                    // TICK ADVANCE #3: drop a small index entry the backend
                    // cron can scan as a fallback for messages that never get
                    // a client-side delivered/read ACK at all (see index.js).
                    try {
                        com.callx.app.utils.MessageStatusSync.markPendingDelivery(
                                delegate.getChatId(), key, delegate.getPartnerUid());
                    } catch (Exception ignored) {}

                    // BUG FIX: same direct-write issue as insertMessage()
                    // above — this status update bypasses the buffered
                    // flush path too.
                    boolean willReanchor = delegate.severPagingIfAtBottom();
                    Executors.newSingleThreadExecutor().execute(() -> {
                        AppDatabase.getInstance(delegate.getActivity())
                                .messageDao().updateStatus(key, "sent");
                        if (willReanchor) delegate.reanchorPagingToBottom();
                    });
                })
                .addOnFailureListener(e -> {
                    // Firebase rejected — stays pending, retry on reconnect
                });

        long ts = m.timestamp;
        String currentUid = delegate.getCurrentUid();
        String partnerUid = delegate.getPartnerUid();

        Map<String, Object> myUpd = new HashMap<>();
        myUpd.put("lastMessage", previewText);
        myUpd.put("lastTs", ts);
        // v22: chat-list read receipts (ticks) + media label support — see
        // User.lastMessageType/Status/SenderUid/Id and ChatListAdapter.
        myUpd.put("lastMessageType", m.type != null ? m.type : "text");
        myUpd.put("lastMessageSenderUid", currentUid);
        myUpd.put("lastMessageStatus", "sent");
        myUpd.put("lastMessageId", key);
        FirebaseUtils.getContactsRef(currentUid).child(partnerUid).updateChildren(myUpd);

        Map<String, Object> theirUpd = new HashMap<>();
        theirUpd.put("lastMessage", previewText);
        theirUpd.put("lastTs", ts);
        theirUpd.put("lastMessageType", m.type != null ? m.type : "text");
        theirUpd.put("lastMessageSenderUid", currentUid);
        theirUpd.put("lastMessageStatus", "sent");
        theirUpd.put("lastMessageId", key);
        FirebaseUtils.getContactsRef(partnerUid).child(currentUid).updateChildren(theirUpd);

        FirebaseUtils.getContactsRef(partnerUid).child(currentUid).child("unread")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot s) {
                        Long cur = s.getValue(Long.class);
                        s.getRef().setValue((cur != null ? cur : 0) + 1);
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {}
                });

        if (!delegate.isMuted()) {
            PushNotify.notifyMessage(partnerUid, currentUid, delegate.getCurrentName(),
                    delegate.getChatId(), m.id, previewText,
                    m.type != null ? m.type : "text",
                    m.mediaUrl != null ? m.mediaUrl : "");
        }
    }

    // ── Send button state ─────────────────────────────────────────────────

    public void updateSendButtonState(boolean online) {
        com.callx.app.chat.databinding.ActivityChatBinding binding = delegate.getBinding();
        if (binding == null) return;
        binding.btnSend.setEnabled(online);
        binding.btnSend.setAlpha(online ? 1.0f : 0.4f);
        binding.btnMic.setEnabled(online);
        binding.btnMic.setAlpha(online ? 1.0f : 0.4f);
    }

    // ── Retry pending on reconnect ─────────────────────────────────────────

    public void retryPendingMessages() {
        Executors.newSingleThreadExecutor().execute(() -> {
            AppDatabase db = AppDatabase.getInstance(delegate.getActivity());
            List<MessageEntity> pending = db.messageDao().getPendingMessages(delegate.getChatId());
            if (pending == null || pending.isEmpty()) return;
            for (MessageEntity pe : pending) {
                Message m = new Message();
                m.id              = pe.id;
                m.senderId        = pe.senderId;
                m.senderName      = pe.senderName;
                m.text            = pe.text;
                m.type            = pe.type;
                m.mediaUrl        = pe.mediaUrl;
                m.thumbnailUrl    = pe.thumbnailUrl;
                m.fileName        = pe.fileName;
                m.fileSize        = pe.fileSize;
                m.duration        = pe.duration;
                m.timestamp       = pe.timestamp;
                m.status          = "sent";
                m.replyToId       = pe.replyToId;
                m.replyToText     = pe.replyToText;
                m.replyToSenderName = pe.replyToSenderName;
                m.fontStyle       = pe.fontStyle;
                m.pollQuestion    = pe.pollQuestion;
                m.pollOptions     = com.callx.app.utils.PollJsonUtil.optionsFromJson(pe.pollOptionsJson);
                m.pollVotes       = com.callx.app.utils.PollJsonUtil.votesFromJson(pe.pollVotesJson);
                m.pollAnonymous       = pe.pollAnonymous;
                m.pollClosed          = pe.pollClosed;
                m.pollMultiChoice     = pe.pollMultiChoice;
                m.reelShareUrl        = pe.reelShareUrl;
                m.reelShareThumb      = pe.reelShareThumb;
                m.reelShareCaption    = pe.reelShareCaption;
                m.reelShareUsername   = pe.reelShareUsername;
                m.reelShareOwnerPhoto = pe.reelShareOwnerPhoto;
                String preview = "reel_share".equals(pe.type) ? "📹 Reel"
                               : pe.text != null ? pe.text : "[" + pe.type + "]";
                delegate.runOnMain(() -> firebasePushMessage(m, pe.id, preview));
            }
        });
    }

    // ── Entity builder ────────────────────────────────────────────────────

    public MessageEntity messageToEntity(Message m, String status) {
        MessageEntity e         = new MessageEntity();
        e.id                    = m.id;
        e.chatId                = delegate.getChatId();
        e.senderId              = m.senderId;
        e.senderName            = m.senderName;
        e.text                  = m.text;
        e.type                  = m.type;
        e.mediaUrl              = m.mediaUrl;
        e.thumbnailUrl          = m.thumbnailUrl;
        e.fileName              = m.fileName;
        e.fileSize              = m.fileSize;
        e.duration              = m.duration;
        e.timestamp             = m.timestamp;
        e.status                = status;
        e.replyToId             = m.replyToId;
        e.replyToText           = m.replyToText;
        e.replyToSenderName     = m.replyToSenderName;
        e.replyToType           = m.replyToType;
        e.replyToMediaUrl       = m.replyToMediaUrl;
        e.isGroup               = false;
        e.syncedAt              = System.currentTimeMillis();
        e.fontStyle             = m.fontStyle;
        e.expiresAt             = m.expiresAt;
        // Feature 13: View Once
        e.viewOnce              = m.viewOnce;
        e.viewOnceState         = m.viewOnceState;
        e.openedAt              = m.openedAt;
        e.viewOnceExpiresAt     = m.viewOnceExpiresAt;
        e.pollQuestion          = m.pollQuestion;
        e.pollOptionsJson       = com.callx.app.utils.PollJsonUtil.optionsToJson(m.pollOptions);
        e.pollVotesJson         = com.callx.app.utils.PollJsonUtil.votesToJson(m.pollVotes);
        e.pollAnonymous         = m.pollAnonymous;
        e.pollClosed            = m.pollClosed;
        e.pollMultiChoice       = m.pollMultiChoice;
        e.reelId              = m.reelId;
        e.reelThumbUrl        = m.reelThumbUrl;
        e.reelOwnerUid        = m.reelOwnerUid;
        e.statusOwnerUid      = m.statusOwnerUid;
        e.statusOwnerName     = m.statusOwnerName;
        e.statusThumbUrl      = m.statusThumbUrl;
        e.reelShareUrl        = m.reelShareUrl;
        e.reelShareThumb      = m.reelShareThumb;
        e.reelShareCaption    = m.reelShareCaption;
        e.reelShareUsername   = m.reelShareUsername;
        e.reelShareOwnerPhoto = m.reelShareOwnerPhoto;
        e.mediaItemsJson      = com.callx.app.utils.MediaItemsJsonUtil.mediaItemsToJson(m.mediaItems);
        e.caption             = m.caption;
        e.contactName         = m.contactName;
        e.contactPhone        = m.contactPhone;
        e.contactPhone2       = m.contactPhone2;
        e.contactPhotoUrl     = m.contactPhotoUrl;
        e.locationLat         = m.locationLat;
        e.locationLng         = m.locationLng;
        e.locationAddress     = m.locationAddress;
        return e;
    }
}
