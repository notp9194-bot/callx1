package com.callx.app.conversation.controllers;

import android.os.Handler;
import android.os.Looper;
import com.google.firebase.database.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;

/**
 * ChatPresenceController — Online/last-seen + typing + read receipts.
 *
 * KEY CHANGE: markRead() routes through delegate.queueMarkRead()
 * instead of its own direct Room write — zero extra PagingSource invalidations.
 */
public class ChatPresenceController {

    public interface PresenceCallback {
        void onPartnerOnline(boolean online);
        void onPartnerLastSeen(String lastSeenText);
        void onPartnerTyping(boolean typing);
    }

    private static final long TYPING_STOP_MS = 2_000L;

    private final String               chatId;
    private final String               myUid;
    private final String               partnerUid;
    private final ChatActivityDelegate delegate;
    private final ExecutorService      ioExecutor;
    private final PresenceCallback     callback;
    private final Handler              mainHandler = new Handler(Looper.getMainLooper());

    private final DatabaseReference onlineRef;
    private final DatabaseReference lastSeenRef;
    private final DatabaseReference typingRef;
    private final DatabaseReference myTypingRef;

    private ValueEventListener onlineListener;
    private ValueEventListener typingListener;
    private boolean isTypingActive = false;

    private final Runnable stopTypingRunnable = () -> {
        myTypingRef.removeValue();
        isTypingActive = false;
    };

    public ChatPresenceController(String chatId, String myUid, String partnerUid,
            ChatActivityDelegate delegate, ExecutorService ioExecutor, PresenceCallback callback) {
        this.chatId     = chatId;
        this.myUid      = myUid;
        this.partnerUid = partnerUid;
        this.delegate   = delegate;
        this.ioExecutor = ioExecutor;
        this.callback   = callback;

        DatabaseReference root = FirebaseDatabase.getInstance().getReference();
        this.onlineRef   = root.child("users").child(partnerUid).child("online");
        this.lastSeenRef = root.child("users").child(partnerUid).child("lastSeen");
        this.typingRef   = root.child("typing").child(chatId).child(partnerUid);
        this.myTypingRef = root.child("typing").child(chatId).child(myUid);
    }

    public void start()   { attachOnlineListener(); attachTypingListener(); setMyPresence(true);  }
    public void stop()    { detachListeners(); setMyPresence(false);
                            mainHandler.removeCallbacks(stopTypingRunnable); myTypingRef.removeValue(); isTypingActive = false; }
    public void release() { stop(); }

    private void attachOnlineListener() {
        onlineListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                Boolean online = snap.getValue(Boolean.class);
                if (Boolean.TRUE.equals(online)) {
                    callback.onPartnerOnline(true);
                } else {
                    lastSeenRef.addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override public void onDataChange(DataSnapshot ls) {
                            Long ts = ls.getValue(Long.class);
                            callback.onPartnerOnline(false);
                            callback.onPartnerLastSeen(ts != null && ts > 0 ? formatLastSeen(ts) : "last seen recently");
                        }
                        @Override public void onCancelled(DatabaseError e) {}
                    });
                }
            }
            @Override public void onCancelled(DatabaseError e) {}
        };
        onlineRef.addValueEventListener(onlineListener);
    }

    private void attachTypingListener() {
        typingListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                Long ts = snap.getValue(Long.class);
                callback.onPartnerTyping(ts != null && (System.currentTimeMillis() - ts) < TYPING_STOP_MS);
            }
            @Override public void onCancelled(DatabaseError e) {}
        };
        typingRef.addValueEventListener(typingListener);
    }

    private void detachListeners() {
        if (onlineListener != null) { onlineRef.removeEventListener(onlineListener);  onlineListener  = null; }
        if (typingListener != null) { typingRef.removeEventListener(typingListener);  typingListener  = null; }
    }

    private void setMyPresence(boolean online) {
        DatabaseReference myOnline   = FirebaseDatabase.getInstance().getReference("users").child(myUid).child("online");
        DatabaseReference myLastSeen = FirebaseDatabase.getInstance().getReference("users").child(myUid).child("lastSeen");
        myOnline.setValue(online);
        if (!online) myLastSeen.setValue(ServerValue.TIMESTAMP);
    }

    public void onUserTyping() {
        if (!isTypingActive) { myTypingRef.setValue(ServerValue.TIMESTAMP); isTypingActive = true; }
        mainHandler.removeCallbacks(stopTypingRunnable);
        mainHandler.postDelayed(stopTypingRunnable, TYPING_STOP_MS);
    }

    public void onUserStoppedTyping() {
        mainHandler.removeCallbacks(stopTypingRunnable);
        if (isTypingActive) { myTypingRef.removeValue(); isTypingActive = false; }
    }

    /** Routes through delegate — zero extra PagingSource invalidations. */
    public void markRead(String msgId) {
        if (msgId == null || msgId.isEmpty()) return;
        delegate.queueMarkRead(msgId);
        FirebaseDatabase.getInstance().getReference()
                .child("messages").child(chatId).child(msgId).child("status").setValue("read");
    }

    public void markReadBulk(List<String> msgIds) {
        if (msgIds == null || msgIds.isEmpty()) return;
        delegate.queueMarkReadBulk(msgIds);
        ioExecutor.execute(() -> {
            DatabaseReference r = FirebaseDatabase.getInstance().getReference().child("messages").child(chatId);
            for (String id : msgIds) r.child(id).child("status").setValue("read");
        });
    }

    private static String formatLastSeen(long ms) {
        long diff = System.currentTimeMillis() - ms;
        if (diff < 60_000L)      return "last seen just now";
        if (diff < 3_600_000L)   return "last seen " + (diff / 60_000L) + " min ago";
        if (diff < 86_400_000L)  return "last seen today at " + new SimpleDateFormat("h:mm a", Locale.getDefault()).format(new Date(ms));
        if (diff < 172_800_000L) return "last seen yesterday at " + new SimpleDateFormat("h:mm a", Locale.getDefault()).format(new Date(ms));
        return "last seen " + new SimpleDateFormat("dd MMM 'at' h:mm a", Locale.getDefault()).format(new Date(ms));
    }
}
