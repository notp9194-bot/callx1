package com.callx.app.utils;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.firebase.database.DatabaseReference;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * StatusCloseFriendsManager — Instagram-style Close Friends list.
 * Firebase: closeFriends/{myUid}/{friendUid} = true
 * Also cached locally in SharedPreferences for offline checks.
 */
public final class StatusCloseFriendsManager {

    private static final String PREFS = "close_friends_prefs";
    private static final String KEY   = "cf_uids";

    private StatusCloseFriendsManager() {}

    public static boolean isCloseFriend(Context ctx, String uid) {
        if (uid == null) return false;
        return getLocalList(ctx).contains(uid);
    }

    public static Set<String> getLocalList(Context ctx) {
        return new HashSet<>(prefs(ctx).getStringSet(KEY, new HashSet<>()));
    }

    public static void addCloseFriend(Context ctx, String myUid, String uid) {
        if (uid == null || myUid == null) return;
        Set<String> s = getLocalList(ctx);
        s.add(uid);
        prefs(ctx).edit().putStringSet(KEY, s).apply();
        getRef(myUid).child(uid).setValue(true);
    }

    public static void removeCloseFriend(Context ctx, String myUid, String uid) {
        if (uid == null || myUid == null) return;
        Set<String> s = getLocalList(ctx);
        s.remove(uid);
        prefs(ctx).edit().putStringSet(KEY, s).apply();
        getRef(myUid).child(uid).removeValue();
    }

    public static void toggle(Context ctx, String myUid, String uid) {
        if (isCloseFriend(ctx, uid)) removeCloseFriend(ctx, myUid, uid);
        else                          addCloseFriend(ctx, myUid, uid);
    }

    public static void syncFromFirebase(Context ctx, String myUid) {
        getRef(myUid).get().addOnSuccessListener(snap -> {
            Set<String> s = new HashSet<>();
            if (snap.exists()) {
                for (com.google.firebase.database.DataSnapshot child : snap.getChildren()) {
                    if (child.getKey() != null) s.add(child.getKey());
                }
            }
            prefs(ctx).edit().putStringSet(KEY, s).apply();
        });
    }

    private static DatabaseReference getRef(String myUid) {
        return FirebaseUtils.db().getReference("closeFriends").child(myUid);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
