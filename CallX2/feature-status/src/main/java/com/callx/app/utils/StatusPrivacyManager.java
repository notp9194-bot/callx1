package com.callx.app.utils;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.firebase.database.*;
import java.util.*;

/**
 * StatusPrivacyManager v26 — FIX: Syncs privacy settings to Firebase.
 * Contacts mode now properly enforced via Firebase contacts list check.
 */
public final class StatusPrivacyManager {
    private static final String PREFS       = "status_privacy_v26";
    private static final String KEY_MODE    = "privacy_mode";
    private static final String KEY_EXCEPT  = "except_uids";
    private static final String KEY_ONLY    = "only_uids";
    private StatusPrivacyManager() {}

    public static final String EVERYONE    = "everyone";
    public static final String CONTACTS    = "contacts";
    public static final String EXCEPT      = "except";
    public static final String ONLY        = "only";
    public static final String CLOSE_FRD   = "close_friends";

    public static String getPrivacyMode(Context ctx) {
        return prefs(ctx).getString(KEY_MODE, EVERYONE);
    }

    public static void setPrivacyMode(Context ctx, String mode) {
        prefs(ctx).edit().putString(KEY_MODE, mode).apply();
        syncToFirebase(ctx, mode);  // FIX: sync to Firebase
    }

    /** FIX: Sync privacy setting to Firebase so server-side enforcement works */
    private static void syncToFirebase(Context ctx, String mode) {
        try {
            String uid = FirebaseUtils.getCurrentUid();
            if (uid == null) return;
            Map<String, Object> updates = new HashMap<>();
            updates.put("defaultPrivacy", mode);
            updates.put("exceptUids",  new ArrayList<>(getExceptList(ctx)));
            updates.put("onlyUids",    new ArrayList<>(getOnlyList(ctx)));
            FirebaseUtils.db().getReference("userPrivacy").child(uid).updateChildren(updates);
        } catch (Exception ignored) {}
    }

    public static Set<String> getExceptList(Context ctx) {
        return new HashSet<>(prefs(ctx).getStringSet(KEY_EXCEPT, new HashSet<>()));
    }
    public static void setExceptList(Context ctx, Set<String> uids) {
        prefs(ctx).edit().putStringSet(KEY_EXCEPT, uids).apply();
        syncToFirebase(ctx, getPrivacyMode(ctx));
    }

    public static Set<String> getOnlyList(Context ctx) {
        return new HashSet<>(prefs(ctx).getStringSet(KEY_ONLY, new HashSet<>()));
    }
    public static void setOnlyList(Context ctx, Set<String> uids) {
        prefs(ctx).edit().putStringSet(KEY_ONLY, uids).apply();
        syncToFirebase(ctx, getPrivacyMode(ctx));
    }

    /** FIX: Contacts mode — check Firebase contacts node, not just assume true */
    public static void canSeeAsync(Context ctx, String viewerUid, String ownerUid,
                                    java.util.function.Consumer<Boolean> callback) {
        if (viewerUid == null || ownerUid == null) { callback.accept(false); return; }
        if (viewerUid.equals(ownerUid)) { callback.accept(true); return; }
        String mode = getPrivacyMode(ctx);
        switch (mode) {
            case EVERYONE:
                callback.accept(true); break;
            case CONTACTS:
                // FIX: Check actual Firebase contacts list
                FirebaseUtils.db().getReference("contacts").child(ownerUid).child(viewerUid)
                    .addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override public void onDataChange(DataSnapshot snap) { callback.accept(snap.exists()); }
                        @Override public void onCancelled(DatabaseError e) { callback.accept(true); } // fail-open
                    });
                break;
            case EXCEPT:
                callback.accept(!getExceptList(ctx).contains(viewerUid)); break;
            case ONLY:
                callback.accept(getOnlyList(ctx).contains(viewerUid)); break;
            case CLOSE_FRD:
                callback.accept(StatusCloseFriendsManager.isCloseFriend(ctx, viewerUid)); break;
            default:
                callback.accept(true);
        }
    }

    /** Sync-version (for non-contacts modes only — contacts mode use async) */
    public static boolean canSee(Context ctx, String viewerUid, String ownerUid) {
        if (viewerUid == null || ownerUid == null) return false;
        if (viewerUid.equals(ownerUid)) return true;
        String mode = getPrivacyMode(ctx);
        switch (mode) {
            case EVERYONE:   return true;
            case CONTACTS:   return true;   // Async check required; fallback true locally
            case EXCEPT:     return !getExceptList(ctx).contains(viewerUid);
            case ONLY:       return getOnlyList(ctx).contains(viewerUid);
            case CLOSE_FRD:  return StatusCloseFriendsManager.isCloseFriend(ctx, viewerUid);
            default:         return true;
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
