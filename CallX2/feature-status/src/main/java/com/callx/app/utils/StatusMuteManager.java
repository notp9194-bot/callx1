package com.callx.app.utils;

import android.content.Context;
import android.content.SharedPreferences;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import androidx.annotation.NonNull;

/**
 * StatusMuteManager — Production-grade mute system for status contacts.
 *
 * Storage: dual-layer
 *   1. SharedPreferences (local, instant reads — no network latency)
 *   2. Firebase RTDB: statusMuted/{myUid}/{contactUid} = true
 *      (syncs across devices on login)
 *
 * Usage:
 *   StatusMuteManager.mute(ctx, contactUid)   — mute a contact
 *   StatusMuteManager.unmute(ctx, contactUid) — unmute a contact
 *   StatusMuteManager.isMuted(ctx, uid)       — instant local check
 *   StatusMuteManager.syncFromFirebase(ctx)   — call on app start to refresh
 *   StatusMuteManager.getMutedUids(ctx)       — get full muted set
 *
 * Integration points (already called for you in the revised files):
 *   - StatusFragment.rebuildAdapter() — filters muted contacts from both sections
 *   - StatusBackgroundService.postNotificationIfNew() — skips muted contacts
 *   - StatusViewerActivity.showViewerMoreMenu() — real mute/unmute toggle
 */
public final class StatusMuteManager {

    private static final String PREFS_NAME  = "status_mute_prefs";
    private static final String KEY_MUTED   = "muted_uids";
    private static final String FB_NODE     = "statusMuted";

    private StatusMuteManager() {}

    // ── Mute / Unmute ────────────────────────────────────────────────────

    /**
     * Mute a contact's statuses.
     * Writes locally (instant) + to Firebase (cross-device sync).
     */
    public static void mute(Context ctx, String contactUid) {
        if (contactUid == null) return;
        // Local
        Set<String> muted = getMutableMuted(ctx);
        muted.add(contactUid);
        saveMuted(ctx, muted);
        // Firebase
        String myUid = safeUid();
        if (myUid != null) {
            FirebaseUtils.db()
                .getReference(FB_NODE)
                .child(myUid)
                .child(contactUid)
                .setValue(true);
        }
    }

    /**
     * Unmute a contact's statuses.
     */
    public static void unmute(Context ctx, String contactUid) {
        if (contactUid == null) return;
        // Local
        Set<String> muted = getMutableMuted(ctx);
        muted.remove(contactUid);
        saveMuted(ctx, muted);
        // Firebase
        String myUid = safeUid();
        if (myUid != null) {
            FirebaseUtils.db()
                .getReference(FB_NODE)
                .child(myUid)
                .child(contactUid)
                .removeValue();
        }
    }

    /**
     * Toggle mute state. Returns true if now muted, false if now unmuted.
     */
    public static boolean toggleMute(Context ctx, String contactUid) {
        if (isMuted(ctx, contactUid)) {
            unmute(ctx, contactUid);
            return false;
        } else {
            mute(ctx, contactUid);
            return true;
        }
    }

    // ── Read ─────────────────────────────────────────────────────────────

    /**
     * Instant local check. Use this in RecyclerView, Service, etc.
     */
    public static boolean isMuted(Context ctx, String uid) {
        if (uid == null || ctx == null) return false;
        return getMutedUids(ctx).contains(uid);
    }

    /**
     * Get the full set of muted UIDs (local copy).
     */
    public static Set<String> getMutedUids(Context ctx) {
        return Collections.unmodifiableSet(
            new HashSet<>(prefs(ctx).getStringSet(KEY_MUTED, Collections.emptySet())));
    }

    // ── Firebase sync ─────────────────────────────────────────────────────

    /**
     * Sync muted list from Firebase to local SharedPrefs.
     * Call once on app start (e.g., in StatusFragment.onStart).
     * Updates local cache silently in background.
     */
    public static void syncFromFirebase(Context ctx) {
        String myUid = safeUid();
        if (myUid == null || ctx == null) return;
        FirebaseUtils.db()
            .getReference(FB_NODE)
            .child(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snap) {
                    Set<String> muted = new HashSet<>();
                    for (DataSnapshot child : snap.getChildren()) {
                        if (child.getKey() != null) muted.add(child.getKey());
                    }
                    saveMuted(ctx, muted);
                }
                @Override
                public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static Set<String> getMutableMuted(Context ctx) {
        return new HashSet<>(prefs(ctx).getStringSet(KEY_MUTED, Collections.emptySet()));
    }

    private static void saveMuted(Context ctx, Set<String> muted) {
        prefs(ctx).edit().putStringSet(KEY_MUTED, muted).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static String safeUid() {
        try { return FirebaseUtils.getCurrentUid(); } catch (Exception e) { return null; }
    }
}
