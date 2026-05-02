package com.callx.app.utils;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import java.util.Arrays;

public class FirebaseUtils {
    public static FirebaseDatabase db() {
        return FirebaseDatabase.getInstance(Constants.DB_URL);
    }
    public static String getCurrentUid() {
        return FirebaseAuth.getInstance().getCurrentUser().getUid();
    }
    public static String getCurrentName() {
        String n = FirebaseAuth.getInstance().getCurrentUser().getDisplayName();
        return (n == null || n.isEmpty()) ? "CallX User" : n;
    }
    public static DatabaseReference getUserRef(String uid) {
        return db().getReference("users").child(uid);
    }
    public static DatabaseReference getMessagesRef(String chatId) {
        return db().getReference("messages").child(chatId);
    }
    public static DatabaseReference getContactsRef(String uid) {
        return db().getReference("contacts").child(uid);
    }
    public static DatabaseReference getRequestsRef(String uid) {
        return db().getReference("requests").child(uid);
    }
    public static DatabaseReference getCallsRef(String uid) {
        return db().getReference("calls").child(uid);
    }
    public static DatabaseReference getGroupsRef() {
        return db().getReference("groups");
    }
    public static DatabaseReference getGroupMessagesRef(String groupId) {
        return db().getReference("groupMessages").child(groupId);
    }
    public static DatabaseReference getUserGroupsRef(String uid) {
        return db().getReference("userGroups").child(uid);
    }
    public static DatabaseReference getGroupTypingRef(String groupId) {
        return db().getReference("groups").child(groupId).child("typing");
    }
    public static DatabaseReference getGroupMembersRef(String groupId) {
        return db().getReference("groups").child(groupId).child("members");
    }
    public static DatabaseReference getStatusRef() {
        return db().getReference("status");
    }
    public static String getChatId(String uid1, String uid2) {
        String[] ids = {uid1, uid2};
        Arrays.sort(ids);
        return ids[0] + "_" + ids[1];
    }

    // ── NEW: Feature 1 — In-chat search (no extra ref needed; uses existing) ──

    // ── NEW: Feature 8 — Disappearing message timer per chat ──────────────
    /** stores disappear timer: chatMeta/{chatId}/disappearTimer = durationMs */
    public static DatabaseReference getChatMetaRef(String chatId) {
        return db().getReference("chatMeta").child(chatId);
    }

    // ── NEW: Feature 9 — Broadcast lists ─────────────────────────────────
    public static DatabaseReference getBroadcastsRef(String uid) {
        return db().getReference("broadcasts").child(uid);
    }

    // ── NEW: Feature 10 — Seen by (group) ────────────────────────────────
    /** Mark message as seen: groupMessages/{groupId}/{msgId}/seenBy/{uid} = ts */
    public static void markGroupMessageSeen(String groupId, String msgId, String uid) {
        getGroupMessagesRef(groupId)
                .child(msgId).child("seenBy").child(uid)
                .setValue(System.currentTimeMillis());
    }

    // ── NEW: Feature 14 — Wallpaper (stored locally, no Firebase ref needed) ─

    // ── NEW: Feature 15 — E2E key exchange ───────────────────────────────
    /** users/{uid}/publicKey = base64(rsaPublicKey) */
    public static DatabaseReference getUserPublicKeyRef(String uid) {
        return getUserRef(uid).child("publicKey");
    }
    /** e2eKeys/{chatId}/{uid} = base64(encryptedSessionKey) */
    public static DatabaseReference getE2EKeyRef(String chatId, String uid) {
        return db().getReference("e2eKeys").child(chatId).child(uid);
    }

    // ── NEW: GIF/Sticker — no extra Firebase path (stored inline in message) ─
    // ── NEW: Location   — stored inline in message ────────────────────────
    // ── NEW: Contact    — stored inline in message ────────────────────────
    // ── NEW: Link Preview — stored inline in message ──────────────────────
    // ── NEW: Poll       — stored inline in message ────────────────────────
}
