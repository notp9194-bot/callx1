package com.callx.app.lock;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import com.google.firebase.auth.FirebaseAuth;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * ChatLockManager — per-chat lock (WhatsApp "Chat Lock" style).
 *
 * Separate from AppLockManager (app-wide PIN/pattern/fingerprint gate on
 * :app) on purpose: AppLockManager lives in the :app module and
 * :feature-chat can't depend on :app (:app depends on :feature-chat, not
 * the other way around — a reverse dependency would be circular). Rather
 * than move AppLockManager, ChatLockManager lives here in :core (which both
 * :app and :feature-chat already depend on) and authenticates using the
 * device's own biometric/PIN/pattern credential (BiometricPrompt with
 * BIOMETRIC_STRONG | DEVICE_CREDENTIAL — see ChatLockGate), the same way
 * WhatsApp's Chat Lock does. No separate secret to set up or remember.
 *
 * Storage is keyed per Firebase uid so locks never leak across accounts on
 * a shared device, and per-chat "unlocked this session" state resets the
 * moment the chat/activity stops (see ChatLockGate) — leaving a locked
 * chat re-locks it, matching WhatsApp.
 */
public class ChatLockManager {

    private static final String PREFS_PREFIX = "callx_chat_lock_";

    private static ChatLockManager sInstance;
    private final Context appCtx;
    private SharedPreferences prefs;
    private String cachedUid;

    // In-memory only — cleared on process death and whenever the gate's
    // host Activity stops. Never persisted: "unlocked" must never survive
    // leaving the chat.
    private static final Set<String> sSessionUnlocked = Collections.synchronizedSet(new HashSet<>());

    public static synchronized ChatLockManager getInstance(@NonNull Context ctx) {
        if (sInstance == null) sInstance = new ChatLockManager(ctx.getApplicationContext());
        return sInstance;
    }

    /** Returns the existing singleton without creating one — null if getInstance() was never called yet. */
    @androidx.annotation.Nullable
    public static synchronized ChatLockManager peekInstance() {
        return sInstance;
    }

    private ChatLockManager(@NonNull Context ctx) {
        this.appCtx = ctx;
    }

    private synchronized SharedPreferences prefs() {
        String uid = currentUid();
        if (prefs == null || !uid.equals(cachedUid)) {
            cachedUid = uid;
            try {
                MasterKey masterKey = new MasterKey.Builder(appCtx)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .build();
                prefs = EncryptedSharedPreferences.create(
                        appCtx, PREFS_PREFIX + uid, masterKey,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
            } catch (Exception e) {
                prefs = appCtx.getSharedPreferences(PREFS_PREFIX + uid + "_plain", Context.MODE_PRIVATE);
            }
        }
        return prefs;
    }

    private static String currentUid() {
        try {
            com.google.firebase.auth.FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
            return u != null ? u.getUid() : "anon";
        } catch (Exception e) {
            return "anon";
        }
    }

    // ── Locked-chat set ──────────────────────────────────────────────────

    public boolean isLocked(@NonNull String chatId) {
        return prefs().getStringSet("locked_chat_ids", Collections.emptySet()).contains(chatId);
    }

    public void setLocked(@NonNull String chatId, boolean locked) {
        Set<String> set = new HashSet<>(prefs().getStringSet("locked_chat_ids", Collections.emptySet()));
        if (locked) set.add(chatId); else set.remove(chatId);
        prefs().edit().putStringSet("locked_chat_ids", set).apply();
        if (!locked) sSessionUnlocked.remove(chatId);
    }

    @NonNull
    public Set<String> getLockedChatIds() {
        return new HashSet<>(prefs().getStringSet("locked_chat_ids", Collections.emptySet()));
    }

    // ── Session unlock state (in-memory, never persisted) ───────────────

    public boolean isUnlockedThisSession(@NonNull String chatId) {
        return sSessionUnlocked.contains(chatId);
    }

    public void markUnlockedThisSession(@NonNull String chatId) {
        sSessionUnlocked.add(chatId);
    }

    /** Call when leaving the chat screen (onStop) so re-entry demands auth again. */
    public void clearSessionUnlock(@NonNull String chatId) {
        sSessionUnlocked.remove(chatId);
    }
}
