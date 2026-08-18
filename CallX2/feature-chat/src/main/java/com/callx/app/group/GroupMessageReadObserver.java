package com.callx.app.group;

import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;
import java.util.HashMap;
import java.util.Map;

/**
 * GroupMessageReadObserver — attaches a real-time Firebase listener to a
 * single group message's readBy + deliveredBy maps.
 *
 * Used by:
 *  1. GroupChatActivity — updates the inline "Seen by X" strip on the bubble
 *     as new readBy entries arrive while the user is in chat.
 *  2. GroupReadByActivity — keeps its three tabs (Read/Delivered/Pending)
 *     live as members open the group chat.
 *
 * Firebase path listened: groupMessages/{groupId}/{msgId}
 *   watches readBy and deliveredBy child nodes.
 *
 * Call detach() when no longer needed (Activity/Fragment onDestroy).
 */
public class GroupMessageReadObserver {

    public interface Callback {
        /**
         * Called on the main thread whenever readBy or deliveredBy changes.
         * @param readBy       uid → read-timestamp (null map = no reads yet)
         * @param deliveredBy  uid → delivered-timestamp
         */
        void onUpdate(Map<String, Long> readBy, Map<String, Long> deliveredBy);
    }

    private final String groupId;
    private final String msgId;
    private final Callback callback;
    private ValueEventListener listener;

    public GroupMessageReadObserver(String groupId, String msgId, Callback callback) {
        this.groupId  = groupId;
        this.msgId    = msgId;
        this.callback = callback;
    }

    /** Start listening. Safe to call multiple times (idempotent). */
    public void attach() {
        if (listener != null) return;
        listener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                Map<String, Long> readBy      = parseTimestampMap(snap.child("readBy"));
                Map<String, Long> deliveredBy = parseTimestampMap(snap.child("deliveredBy"));
                if (callback != null) callback.onUpdate(readBy, deliveredBy);
            }
            @Override public void onCancelled(DatabaseError e) {}
        };
        FirebaseUtils.getGroupMessagesRef(groupId).child(msgId).addValueEventListener(listener);
    }

    /** Stop listening and release Firebase resources. */
    public void detach() {
        if (listener != null) {
            FirebaseUtils.getGroupMessagesRef(groupId).child(msgId).removeEventListener(listener);
            listener = null;
        }
    }

    private static Map<String, Long> parseTimestampMap(DataSnapshot snap) {
        Map<String, Long> map = new HashMap<>();
        if (!snap.exists()) return map;
        for (DataSnapshot child : snap.getChildren()) {
            Object val = child.getValue();
            if (val instanceof Long)   map.put(child.getKey(), (Long) val);
            if (val instanceof Double) map.put(child.getKey(), ((Double) val).longValue());
        }
        return map;
    }
}
