package com.callx.app.utils;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.Query;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.Map;

/**
 * GlobalDeliveryAckManager — advancement #1.
 *
 * WHY: previously "delivered" (outside of the FCM-push fallback) only got
 * marked from ChatActivity's own RTDB listener — i.e. only for the ONE chat
 * the user currently has open. A message arriving while the recipient is on
 * the chat list, a different chat, Reels, etc. had to wait for a push.
 *
 * WHAT: while the app is in the foreground (hooked from PresenceManager's
 * goOnline/goOffline, which already track exactly this), attach a light
 * ChildEventListener per contact's 1:1 chat — scoped to just the last few
 * messages, same cost profile as the existing per-chat "status sync"
 * listener — so a message shows up as delivered the instant it reaches this
 * device over the open Firebase connection, no matter which screen is open,
 * without waiting for a push notification at all.
 *
 * Also owns the ".info/connected" listener: the moment this device
 * reconnects after a network blip, it flushes PendingAckQueue so any
 * delivered/read ack that failed while offline gets retried (advancement #2).
 */
public final class GlobalDeliveryAckManager {

    private static final String TAG = "GlobalDeliveryAckMgr";
    private static final int STATUS_WINDOW = 15; // last N messages per chat is enough

    private static GlobalDeliveryAckManager sInstance;

    public static GlobalDeliveryAckManager getInstance() {
        if (sInstance == null) sInstance = new GlobalDeliveryAckManager();
        return sInstance;
    }

    private GlobalDeliveryAckManager() {}

    private boolean running = false;
    private Context appCtx;
    private String myUid;

    private ChildEventListener contactsListener;
    private ValueEventListener connectedListener;
    private final Map<String, ChildEventListener> perChatListeners = new HashMap<>();

    /** Call from PresenceManager.goOnline() — app just came to foreground. */
    public synchronized void start(@NonNull Context context) {
        if (running) return;
        String uid = FirebaseAuth.getInstance().getCurrentUser() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;
        if (uid == null || uid.isEmpty()) return;

        appCtx = context.getApplicationContext();
        myUid = uid;
        running = true;

        // Retry anything queued from before this session, then keep retrying
        // on every reconnect (advancement #2).
        PendingAckQueue.retryAll(appCtx);
        DatabaseReference connectedRef = FirebaseUtils.db().getReference(".info/connected");
        connectedListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snapshot) {
                Boolean connected = snapshot.getValue(Boolean.class);
                if (Boolean.TRUE.equals(connected) && appCtx != null) {
                    PendingAckQueue.retryAll(appCtx);
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError error) {}
        };
        connectedRef.addValueEventListener(connectedListener);

        // Watch the contact list; for each contact, attach a small per-chat
        // delivery listener. New contacts (new chats) get picked up live via
        // onChildAdded; we intentionally don't bother detaching per-contact
        // listeners on onChildRemoved — Firebase keeps this cheap and a
        // removed contact just means a dormant listener on an old chat.
        DatabaseReference contactsRef = FirebaseUtils.getContactsRef(myUid);
        contactsListener = new ChildEventListener() {
            @Override public void onChildAdded(@NonNull DataSnapshot snapshot, String prev) {
                String partnerUid = snapshot.getKey();
                attachChatListener(partnerUid);
            }
            @Override public void onChildChanged(@NonNull DataSnapshot snapshot, String prev) {}
            @Override public void onChildRemoved(@NonNull DataSnapshot snapshot) {}
            @Override public void onChildMoved(@NonNull DataSnapshot s, String p) {}
            @Override public void onCancelled(@NonNull DatabaseError error) {}
        };
        contactsRef.addChildEventListener(contactsListener);
    }

    private void attachChatListener(String partnerUid) {
        if (partnerUid == null || myUid == null) return;
        String chatId = FirebaseUtils.getChatId(myUid, partnerUid);
        if (chatId == null || perChatListeners.containsKey(chatId)) return;

        DatabaseReference messagesRef = FirebaseUtils.getMessagesRef(chatId);
        Query recentQuery = messagesRef.orderByChild("timestamp").limitToLast(STATUS_WINDOW);

        ChildEventListener listener = new ChildEventListener() {
            @Override public void onChildAdded(@NonNull DataSnapshot snapshot, String prev) {
                ackIfNeeded(snapshot, chatId, messagesRef);
            }
            @Override public void onChildChanged(@NonNull DataSnapshot snapshot, String prev) {
                ackIfNeeded(snapshot, chatId, messagesRef);
            }
            @Override public void onChildRemoved(@NonNull DataSnapshot snapshot) {}
            @Override public void onChildMoved(@NonNull DataSnapshot s, String p) {}
            @Override public void onCancelled(@NonNull DatabaseError error) {
                Log.w(TAG, "chat listener cancelled for " + chatId + ": " + error.getMessage());
            }
        };
        recentQuery.addChildEventListener(listener);
        perChatListeners.put(chatId, listener);
    }

    private void ackIfNeeded(DataSnapshot snapshot, String chatId, DatabaseReference messagesRef) {
        try {
            String senderId = snapshot.child("senderId").getValue(String.class);
            String status = snapshot.child("status").getValue(String.class);
            if (senderId == null || myUid.equals(senderId)) return; // our own message
            if (!"sent".equals(status)) return; // already delivered/read, or not sent yet
            MessageStatusSync.upgradeStatus(appCtx, messagesRef, chatId, snapshot.getKey(), "delivered");
        } catch (Exception ignored) {}
    }

    /** Call from PresenceManager.goOffline() — app going to background. */
    public synchronized void stop() {
        if (!running) return;
        running = false;

        if (contactsListener != null && myUid != null) {
            FirebaseUtils.getContactsRef(myUid).removeEventListener(contactsListener);
        }
        if (connectedListener != null) {
            FirebaseUtils.db().getReference(".info/connected").removeEventListener(connectedListener);
        }
        for (Map.Entry<String, ChildEventListener> e : perChatListeners.entrySet()) {
            FirebaseUtils.getMessagesRef(e.getKey())
                    .orderByChild("timestamp").limitToLast(STATUS_WINDOW)
                    .removeEventListener(e.getValue());
        }
        perChatListeners.clear();
        contactsListener = null;
        connectedListener = null;
    }
}
