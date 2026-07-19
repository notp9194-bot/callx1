package com.callx.app.repository;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import com.callx.app.db.AppDatabase;
import com.callx.app.db.dao.UserDao;
import com.callx.app.db.entity.UserEntity;
import com.callx.app.models.User;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * UserRepository — Single source of truth for User/Profile data.
 *
 * Architecture:
 *   Any feature → [Feature]ViewModel → UserRepository → (Room + Firebase)
 *
 * Shared across: feature-chat, feature-status, feature-calls, feature-reels.
 * Centralizes all user profile reads/writes — no duplicated Firebase user calls.
 */
public class UserRepository {

    private static volatile UserRepository sInstance;

    private final UserDao         dao;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final Handler         mainHandler = new Handler(Looper.getMainLooper());

    private UserRepository(Context ctx) {
        this.dao = AppDatabase.getInstance(ctx).userDao();
    }

    public static UserRepository getInstance(Context ctx) {
        if (sInstance == null) {
            synchronized (UserRepository.class) {
                if (sInstance == null)
                    sInstance = new UserRepository(ctx.getApplicationContext());
            }
        }
        return sInstance;
    }

    // ── READ — Room LiveData ───────────────────────────────────────────────

    /** Get a single user's profile — Room LiveData (auto-updates). */
    public LiveData<UserEntity> getUser(String uid) {
        return dao.getUserLive(uid);
    }

    /** Get current user's own profile. */
    public LiveData<UserEntity> getMyProfile() {
        return dao.getUserLive(FirebaseUtils.getCurrentUid());
    }

    /** All contacts — for chat list, call list, etc. */
    public LiveData<java.util.List<UserEntity>> getContacts() {
        return dao.getAllUsersLive();
    }

    // ── SYNC — Firebase → Room ────────────────────────────────────────────

    /**
     * Fetch a user's profile from Firebase and cache it in Room.
     * Returns immediately (Room LiveData already serves cached data).
     */
    public void syncUser(String uid) {
        if (uid == null || uid.isEmpty()) return;
        FirebaseUtils.getUserRef(uid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    User u = snap.getValue(User.class);
                    if (u == null) return;
                    u.uid = uid;
                    executor.execute(() -> dao.insertUser(userToEntity(u)));
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    /**
     * Attach a live listener to a user's profile — call from a chat screen
     * to keep the header (name, avatar, online status) up to date.
     * Returns the listener so the caller can remove it in onStop.
     */
    public ValueEventListener attachLiveUser(String uid, Runnable onUpdate) {
        ValueEventListener listener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                User u = snap.getValue(User.class);
                if (u == null) return;
                u.uid = uid;
                executor.execute(() -> {
                    dao.insertUser(userToEntity(u));
                    if (onUpdate != null) mainHandler.post(onUpdate);
                });
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        FirebaseUtils.getUserRef(uid).addValueEventListener(listener);
        return listener;
    }

    public void removeLiveUser(String uid, ValueEventListener listener) {
        if (listener != null)
            FirebaseUtils.getUserRef(uid).removeEventListener(listener);
    }

    // ── WRITE — Update own profile ────────────────────────────────────────

    public interface Result { void onDone(boolean success); }

    public void updateProfile(String uid, String name, String bio,
                              String photoUrl, String emoji, Result cb) {
        java.util.Map<String, Object> upd = new java.util.HashMap<>();
        if (name     != null) upd.put("name",     name);
        if (bio      != null) upd.put("bio",      bio);
        if (photoUrl != null) upd.put("photoUrl", photoUrl);
        if (emoji    != null) upd.put("statusEmoji", emoji);
        FirebaseUtils.getUserRef(uid).updateChildren(upd,
            (e, ref) -> {
                if (e == null) syncUser(uid); // refresh Room
                if (cb != null) mainHandler.post(() -> cb.onDone(e == null));
            });
    }

    public void updatePresence(String uid, boolean online) {
        FirebaseUtils.getUserRef(uid).child("online").setValue(online);
        if (!online)
            FirebaseUtils.getUserRef(uid).child("lastSeen")
                .setValue(System.currentTimeMillis());
    }

    // ── WRITE — Contacts ──────────────────────────────────────────────────

    public void saveContact(User user) {
        executor.execute(() -> dao.insertUser(userToEntity(user)));
    }

    // ── Converter ─────────────────────────────────────────────────────────

    private UserEntity userToEntity(User u) {
        UserEntity e = new UserEntity();
        e.uid         = u.uid != null ? u.uid : "";
        e.name        = u.name;
        e.photoUrl    = u.photoUrl;
        e.bio         = u.bio;
        e.statusEmoji = u.statusEmoji;
        e.callxId     = u.callxId;
        e.online      = u.online != null && u.online;
        e.lastSeen    = u.lastSeen;
        e.syncedAt    = System.currentTimeMillis();
        return e;
    }

    public User entityToModel(UserEntity e) {
        User u = new User();
        u.uid         = e.uid;
        u.name        = e.name;
        u.photoUrl    = e.photoUrl;
        u.bio         = e.bio;
        u.statusEmoji = e.statusEmoji;
        u.callxId     = e.callxId;
        u.online      = e.online;
        u.lastSeen    = e.lastSeen;
        return u;
    }
}
