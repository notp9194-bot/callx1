package com.callx.app.cache;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.callx.app.models.Message;
import com.callx.app.utils.AppBgExecutor;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Small, best-effort disk snapshot for the last messages of recently-used
 * chats.
 *
 * Room is still the source of truth. This cache exists only to bridge the
 * process-death gap: after Android kills the app, the first chat frame can be
 * painted from this snapshot while SQLCipher/Room and Firebase reconnect in
 * the background.
 *
 * The snapshot is deliberately account-scoped. Chat ids alone are not enough
 * protection when a device changes accounts, so every preference key includes
 * the Firebase uid. It is also bounded to avoid turning SharedPreferences
 * into an unbounded message store.
 */
public final class LastMessagesDiskCache {

    private static final String TAG = "LastMessagesDiskCache";
    private static final String PREFS_NAME = "last_messages_disk_cache_v1";
    private static final String INDEX_KEY = "__chat_keys__";
    private static final int MAX_PER_CHAT = 20;
    private static final int MAX_DISK_CHATS = 50;
    private static final Gson GSON = new Gson();
    private static final Type MESSAGE_LIST_TYPE =
            new TypeToken<List<Message>>() {}.getType();
    private static final Object WRITE_LOCK = new Object();
    private static final ConcurrentHashMap<String, AtomicLong> WRITE_VERSIONS =
            new ConcurrentHashMap<>();

    private LastMessagesDiskCache() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static String key(String accountUid, String chatId) {
        return "m_" + safe(accountUid) + "_" + safe(chatId);
    }

    private static String safe(String value) {
        if (value == null || value.isEmpty()) return "_";
        return value.replace('\\', '_').replace('"', '_');
    }

    /**
     * Loads one chat synchronously. The payload is intentionally tiny
     * (maximum 20 messages), making this suitable for the first frame of a
     * chat Activity. Returns true only when a usable snapshot was restored.
     */
    public static boolean loadIntoMemory(Context context, String accountUid, String chatId) {
        if (context == null || accountUid == null || accountUid.isEmpty()
                || chatId == null || chatId.isEmpty()
                || LastMessagesCache.getInstance().has(chatId)) {
            return LastMessagesCache.getInstance().has(chatId);
        }
        try {
            String json = prefs(context).getString(key(accountUid, chatId), null);
            if (json == null || json.isEmpty()) return false;
            List<Message> messages = GSON.fromJson(json, MESSAGE_LIST_TYPE);
            if (messages == null || messages.isEmpty()) return false;
            LastMessagesCache.getInstance().seed(chatId, messages);
            return LastMessagesCache.getInstance().has(chatId);
        } catch (Exception e) {
            Log.w(TAG, "load failed: " + e.getMessage());
            return false;
        }
    }

    /** Cheap existence check used only by background prefetch code. */
    public static boolean has(Context context, String accountUid, String chatId) {
        if (context == null || accountUid == null || chatId == null) return false;
        try {
            String json = prefs(context).getString(key(accountUid, chatId), null);
            return json != null && !json.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Persists a defensive copy off the UI thread. Versioning prevents a
     * slower older write from overwriting a newer Firebase/Room snapshot.
     */
    public static void saveAsync(Context context, String accountUid, String chatId,
                                 List<Message> messages) {
        if (context == null || accountUid == null || accountUid.isEmpty()
                || chatId == null || chatId.isEmpty() || messages == null
                || messages.isEmpty()) return;

        List<Message> copy = new ArrayList<>(messages);
        if (copy.size() > MAX_PER_CHAT) {
            copy = new ArrayList<>(copy.subList(copy.size() - MAX_PER_CHAT, copy.size()));
        }
        String prefKey = key(accountUid, chatId);
        AtomicLong version = WRITE_VERSIONS.computeIfAbsent(prefKey, ignored -> new AtomicLong());
        long writeVersion = version.incrementAndGet();
        Context appContext = context.getApplicationContext();
        List<Message> finalCopy = copy;

        AppBgExecutor.execute(() -> {
            if (version.get() != writeVersion) return;
            try {
                synchronized (WRITE_LOCK) {
                    if (version.get() != writeVersion) return;
                    SharedPreferences p = prefs(appContext);
                    Set<String> keys = new LinkedHashSet<>(
                            p.getStringSet(INDEX_KEY, Collections.emptySet()));
                    keys.remove(prefKey);
                    keys.add(prefKey);
                    SharedPreferences.Editor editor = p.edit()
                            .putString(prefKey, GSON.toJson(finalCopy, MESSAGE_LIST_TYPE));
                    while (keys.size() > MAX_DISK_CHATS) {
                        String eldest = keys.iterator().next();
                        keys.remove(eldest);
                        editor.remove(eldest);
                    }
                    editor.putStringSet(INDEX_KEY, keys).apply();
                }
            } catch (Exception e) {
                Log.w(TAG, "save failed: " + e.getMessage());
            }
        });
    }

    /** Clears all snapshots belonging to one Firebase account. */
    public static void clearForAccountAsync(Context context, String accountUid) {
        if (context == null || accountUid == null || accountUid.isEmpty()) return;
        Context appContext = context.getApplicationContext();
        AppBgExecutor.execute(() -> {
            synchronized (WRITE_LOCK) {
                try {
                    SharedPreferences p = prefs(appContext);
                    Set<String> old = p.getStringSet(INDEX_KEY, Collections.emptySet());
                    SharedPreferences.Editor editor = p.edit();
                    Set<String> keep = new LinkedHashSet<>();
                    String prefix = "m_" + safe(accountUid) + "_";
                    for (String k : old) {
                        if (k.startsWith(prefix)) editor.remove(k);
                        else keep.add(k);
                    }
                    editor.putStringSet(INDEX_KEY, keep).apply();
                } catch (Exception e) {
                    Log.w(TAG, "clear failed: " + e.getMessage());
                }
            }
        });
    }
}