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
        com.google.firebase.auth.FirebaseUser user =
                FirebaseAuth.getInstance().getCurrentUser();
        return user != null ? user.getUid() : null;
    }

    public static String getCurrentName() {
        com.google.firebase.auth.FirebaseUser user =
                FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return "CallX User";
        String n = user.getDisplayName();
        return (n == null || n.isEmpty()) ? "CallX User" : n;
    }

    // ── User ──────────────────────────────────────────────────────────────

    public static DatabaseReference getUserRef(String uid) {
        return db().getReference("users").child(uid);
    }

    // ── Messages ──────────────────────────────────────────────────────────

    public static DatabaseReference getMessagesRef(String chatId) {
        return db().getReference("messages").child(chatId);
    }

    // ── Contacts ──────────────────────────────────────────────────────────

    public static DatabaseReference getContactsRef(String uid) {
        return db().getReference("contacts").child(uid);
    }

    // ── Requests ──────────────────────────────────────────────────────────

    public static DatabaseReference getRequestsRef(String uid) {
        return db().getReference("requests").child(uid);
    }

    // ── Calls ─────────────────────────────────────────────────────────────

    public static DatabaseReference getCallsRef(String uid) {
        return db().getReference("calls").child(uid);
    }

    // ── Groups ────────────────────────────────────────────────────────────

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

    // ── Status ────────────────────────────────────────────────────────────

    /**
     * Root status node: status/{ownerUid}/{statusId}
     */
    public static DatabaseReference getStatusRef() {
        return db().getReference("status");
    }

    /**
     * Per-user status node: status/{ownerUid}
     */
    public static DatabaseReference getUserStatusRef(String ownerUid) {
        return db().getReference("status").child(ownerUid);
    }

    /**
     * Per-item seenBy sub-map: status/{ownerUid}/{statusId}/seenBy
     */
    public static DatabaseReference getStatusSeenByRef(String ownerUid, String statusId) {
        return db().getReference("status")
                   .child(ownerUid)
                   .child(statusId)
                   .child("seenBy");
    }

    /**
     * Dedicated seen-tracking node used by StatusFragment to avoid loading
     * the entire seenBy sub-map for every status item in the list:
     *
     *   statusSeen/{viewerUid}/{ownerUid}/{statusId} = timestamp
     *
     * This is written by StatusSeenTracker when the viewer exits the viewer.
     */
    public static DatabaseReference getStatusSeenRef(String viewerUid) {
        return db().getReference("statusSeen").child(viewerUid);
    }

    /**
     * Per-reaction node: status/{ownerUid}/{statusId}/reactions/{reactorUid} = emoji
     */
    public static DatabaseReference getStatusReactionRef(String ownerUid, String statusId,
                                                          String reactorUid) {
        return db().getReference("status")
                   .child(ownerUid)
                   .child(statusId)
                   .child("reactions")
                   .child(reactorUid);
    }

    /**
     * Status highlights: statusHighlights/{ownerUid}/{albumId}/
     */
    public static DatabaseReference getStatusHighlightsRef(String ownerUid) {
        return db().getReference("statusHighlights").child(ownerUid);
    }

    // ── Chat helpers ──────────────────────────────────────────────────────

    public static String getChatId(String uid1, String uid2) {
        String[] ids = {uid1, uid2};
        Arrays.sort(ids);
        return ids[0] + "_" + ids[1];
    }
}
