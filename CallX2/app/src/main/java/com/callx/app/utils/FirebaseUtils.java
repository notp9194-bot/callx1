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
    // Per-group ephemeral typing map: groups/{groupId}/typing/{uid} = displayName
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
}
