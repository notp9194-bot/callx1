package com.callx.app.utils;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.firebase.database.*;
import java.util.*;

/** StatusMuteManager v26 — FIX: Added Firebase sync (was local-only). */
public final class StatusMuteManager {
    private static final String PREFS = "status_mute_prefs";
    private static final String KEY   = "muted_uids";
    private static ValueEventListener liveListener;
    private StatusMuteManager() {}

    public static boolean isMuted(Context ctx, String uid) {
        return uid != null && getMutedSet(ctx).contains(uid);
    }
    public static void mute(Context ctx, String myUid, String uid) {
        if (uid == null || myUid == null) return;
        Set<String> s = getMutedSet(ctx); s.add(uid); save(ctx, s);
        FirebaseUtils.db().getReference("statusMutes").child(myUid).child(uid).setValue(true);
    }
    public static void unmute(Context ctx, String myUid, String uid) {
        if (uid == null || myUid == null) return;
        Set<String> s = getMutedSet(ctx); s.remove(uid); save(ctx, s);
        FirebaseUtils.db().getReference("statusMutes").child(myUid).child(uid).removeValue();
    }
    public static void toggle(Context ctx, String uid) {
        try { String myUid = FirebaseUtils.getCurrentUid();
            if (isMuted(ctx, uid)) unmute(ctx, myUid, uid); else mute(ctx, myUid, uid);
        } catch (Exception e) { /* fallback local */ }
    }
    public static Set<String> getMutedSet(Context ctx) {
        return new HashSet<>(prefs(ctx).getStringSet(KEY, new HashSet<>()));
    }
    /** FIX: Real-time Firebase sync → works across devices/reinstall */
    public static void startRealtimeSync(Context ctx, String myUid) {
        if (myUid == null) return;
        DatabaseReference ref = FirebaseUtils.db().getReference("statusMutes").child(myUid);
        if (liveListener != null) ref.removeEventListener(liveListener);
        liveListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                Set<String> s = new HashSet<>();
                for (DataSnapshot c : snap.getChildren()) if (c.getKey() != null) s.add(c.getKey());
                save(ctx, s);
            }
            @Override public void onCancelled(DatabaseError e) {}
        };
        ref.addValueEventListener(liveListener);
    }
    public static void stopRealtimeSync(String myUid) {
        if (liveListener != null && myUid != null) {
            FirebaseUtils.db().getReference("statusMutes").child(myUid).removeEventListener(liveListener);
            liveListener = null;
        }
    }
    private static void save(Context ctx, Set<String> s) { prefs(ctx).edit().putStringSet(KEY, s).apply(); }
    private static SharedPreferences prefs(Context ctx) { return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
}
