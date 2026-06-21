package com.callx.app.conversation.popup;

import androidx.annotation.NonNull;

import com.callx.app.models.Message;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.Query;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.List;

/**
 * Drives the small popup chat window (MiniChatPopupActivity) — loads the
 * last few messages of a chat, keeps listening live so both sides can chat
 * right there in the popup, and pushes new outgoing messages. Deliberately
 * standalone (doesn't depend on ChatActivityDelegate) since the popup can
 * be opened from ANY screen in the app, not just from inside chat.
 */
public class ChatPopupController {

    private static final int LAST_N_MESSAGES = 25;

    public interface Listener {
        void onMessagesLoaded(List<Message> messages);
        void onMessageAdded(Message message);
    }

    private final String chatId;
    private final String partnerUid;
    private final String currentUid;
    private final Listener listener;

    private ChildEventListener liveListener;
    private DatabaseReference messagesRef;
    private long lastSeenTs = 0L;

    public ChatPopupController(String partnerUid, Listener listener) {
        this.partnerUid = partnerUid;
        this.listener = listener;
        com.google.firebase.auth.FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        this.currentUid = u != null ? u.getUid() : null;
        this.chatId = (currentUid != null && partnerUid != null)
                ? FirebaseUtils.getChatId(currentUid, partnerUid) : null;
    }

    public String getChatId() { return chatId; }
    public String getCurrentUid() { return currentUid; }

    // ── Load + live sync ────────────────────────────────────────────────

    public void start() {
        if (chatId == null) return;
        messagesRef = FirebaseUtils.getMessagesRef(chatId);

        Query initial = messagesRef.orderByChild("timestamp").limitToLast(LAST_N_MESSAGES);
        initial.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snapshot) {
                List<Message> loaded = new ArrayList<>();
                for (DataSnapshot child : snapshot.getChildren()) {
                    Message m = child.getValue(Message.class);
                    if (m == null) continue;
                    m.id = child.getKey();
                    loaded.add(m);
                    if (m.timestamp != null && m.timestamp > lastSeenTs) lastSeenTs = m.timestamp;
                }
                if (listener != null) listener.onMessagesLoaded(loaded);
                attachLiveListener();
            }
            @Override public void onCancelled(@NonNull com.google.firebase.database.DatabaseError error) { }
        });
    }

    private void attachLiveListener() {
        liveListener = new ChildEventListener() {
            @Override public void onChildAdded(@NonNull DataSnapshot snapshot, String prev) {
                Message m = snapshot.getValue(Message.class);
                if (m == null) return;
                m.id = snapshot.getKey();
                if (m.timestamp != null) lastSeenTs = Math.max(lastSeenTs, m.timestamp);
                if (listener != null) listener.onMessageAdded(m);
            }
            @Override public void onChildChanged(@NonNull DataSnapshot snapshot, String prev) { }
            @Override public void onChildRemoved(@NonNull DataSnapshot snapshot) { }
            @Override public void onChildMoved(@NonNull DataSnapshot snapshot, String prev) { }
            @Override public void onCancelled(@NonNull com.google.firebase.database.DatabaseError error) { }
        };
        messagesRef.orderByChild("timestamp").startAfter((double) lastSeenTs)
                .addChildEventListener(liveListener);
    }

    // ── Send ─────────────────────────────────────────────────────────────

    public void sendMessage(String text) {
        if (chatId == null || currentUid == null || text == null || text.trim().isEmpty()) return;
        String key = messagesRef.push().getKey();
        if (key == null) return;

        Message m = new Message();
        m.id = key;
        m.senderId = currentUid;
        m.text = text.trim();
        m.type = "text";
        m.status = "sent";
        m.timestamp = System.currentTimeMillis();

        messagesRef.child(key).setValue(m);

        updateLastMessagePreview(text.trim(), m.timestamp);
    }

    private void updateLastMessagePreview(String preview, long ts) {
        if (currentUid == null || partnerUid == null) return;
        java.util.Map<String, Object> myUpd = new java.util.HashMap<>();
        myUpd.put("lastMessage", preview);
        myUpd.put("lastTs", ts);
        FirebaseUtils.getContactsRef(currentUid).child(partnerUid).updateChildren(myUpd);

        java.util.Map<String, Object> theirUpd = new java.util.HashMap<>();
        theirUpd.put("lastMessage", preview);
        theirUpd.put("lastTs", ts);
        FirebaseUtils.getContactsRef(partnerUid).child(currentUid).updateChildren(theirUpd);
    }

    public void stop() {
        if (messagesRef != null && liveListener != null) {
            messagesRef.removeEventListener(liveListener);
        }
    }
}
