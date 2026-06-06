package com.callx.app.utils;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ServerValue;

import java.util.HashMap;
import java.util.Map;

/**
 * PresenceManager — Firebase pe online/offline + lastSeen accurately set karta hai.
 *
 * Pattern:
 *   - App foreground  → online = true
 *   - App background  → online = false, lastSeen = ServerValue.TIMESTAMP
 *   - App killed      → onDisconnect handler automatically online=false + lastSeen set karta hai
 *
 * Usage:
 *   CallxApp.onCreate()       → PresenceManager.init(this)
 *   Activity.onResume()       → PresenceManager.getInstance().goOnline()
 *   Activity.onStop() (last)  → PresenceManager.getInstance().goOffline()
 *
 * Thread safety: main thread pe call karo.
 */
public class PresenceManager {

    private static final String TAG = "PresenceManager";
    private static PresenceManager sInstance;

    private boolean isOnline = false;
    private String  cachedUid = null;

    private PresenceManager() {}

    public static PresenceManager getInstance() {
        if (sInstance == null) sInstance = new PresenceManager();
        return sInstance;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Called when any activity comes to foreground (sActivityRefs: 0→1)
    // ──────────────────────────────────────────────────────────────────────
    public void goOnline() {
        String uid = getUid();
        if (uid == null) return;
        if (isOnline) return; // already online, avoid duplicate writes

        isOnline = true;
        cachedUid = uid;

        DatabaseReference userRef = FirebaseUtils.getUserRef(uid);

        // Register onDisconnect FIRST — agar network cut ho ya app force-kill ho
        // toh Firebase server khud online=false + lastSeen write karega
        try {
            Map<String, Object> offlineUpdate = new HashMap<>();
            offlineUpdate.put("online",   false);
            offlineUpdate.put("lastSeen", ServerValue.TIMESTAMP);
            userRef.onDisconnect().updateChildren(offlineUpdate);
        } catch (Exception e) {
            Log.w(TAG, "onDisconnect register failed: " + e.getMessage());
        }

        // Now set online = true
        Map<String, Object> onlineUpdate = new HashMap<>();
        onlineUpdate.put("online", true);
        userRef.updateChildren(onlineUpdate)
            .addOnFailureListener(ex -> Log.w(TAG, "goOnline write failed: " + ex.getMessage()));

        Log.d(TAG, "goOnline: " + uid);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Called when all activities go to background (sActivityRefs: 1→0)
    // ──────────────────────────────────────────────────────────────────────
    public void goOffline() {
        String uid = cachedUid != null ? cachedUid : getUid();
        if (uid == null) return;

        isOnline = false;

        // Cancel the onDisconnect handler (we're writing explicitly)
        DatabaseReference userRef = FirebaseUtils.getUserRef(uid);
        try {
            userRef.onDisconnect().cancel();
        } catch (Exception ignored) {}

        // Write offline + lastSeen
        Map<String, Object> offlineUpdate = new HashMap<>();
        offlineUpdate.put("online",   false);
        offlineUpdate.put("lastSeen", ServerValue.TIMESTAMP);
        userRef.updateChildren(offlineUpdate)
            .addOnFailureListener(ex -> Log.w(TAG, "goOffline write failed: " + ex.getMessage()));

        Log.d(TAG, "goOffline: " + uid);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Call after login/registration to immediately mark online
    // ──────────────────────────────────────────────────────────────────────
    public void onLogin() {
        cachedUid = null; // force fresh UID read
        isOnline = false;
        goOnline();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Call on logout — clear state
    // ──────────────────────────────────────────────────────────────────────
    public void onLogout() {
        goOffline();
        cachedUid = null;
        isOnline  = false;
    }

    private String getUid() {
        try {
            com.google.firebase.auth.FirebaseUser u =
                    FirebaseAuth.getInstance().getCurrentUser();
            return u != null ? u.getUid() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
