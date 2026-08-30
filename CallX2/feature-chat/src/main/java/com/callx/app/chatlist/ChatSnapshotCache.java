package com.callx.app.chatlist;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.callx.app.models.User;
import com.callx.app.utils.AppBgExecutor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * ChatSnapshotCache — v210 PERF FIX: "cold open after a long time / fresh
 * from recent-apps is slow" root cause.
 *
 * ROOT CAUSE: this app's Room DB is SQLCipher-encrypted (see
 * CallxApp#onCreate's db-warmup thread doc: "SQLCipher loadLibs() +
 * Android Keystore key retrieval + Room schema check — 500ms to 3sec").
 * That's real, unavoidable work for an encrypted DB and isn't something to
 * silently strip out (it's there for a reason — chat data at rest). Even
 * with the db-warmup thread racing it in the background, a genuinely cold
 * process (killed by the OS after being backgrounded a long time, or force-
 * stopped from Recents) pays that tax again before ChatsFragment.loadFromRoom()
 * can return anything — that's the visible "blank list for a second or two"
 * WhatsApp doesn't have (its local DB isn't encrypted the same way).
 *
 * WHATSAPP-LEVEL FIX: don't wait on the encrypted DB for the FIRST frame at
 * all. Keep a tiny, plaintext, un-encrypted snapshot of just the top
 * SNAPSHOT_SIZE chats (name/thumb/last-message/time/unread — nothing
 * sensitive beyond what's already visible on the lock-screen notification
 * shade) in plain SharedPreferences. That file is small (a few KB) and NOT
 * behind SQLCipher, so reading it back costs low-single-digit milliseconds
 * even stone cold — no Keystore call, no migration check, no 500ms-3sec
 * wait. ChatsFragment renders this INSTANTLY as the very first frame, then
 * loadFromRoom()/loadContacts() transparently replace it with real,
 * decrypted data the moment the DB/Firebase are actually ready — same
 * "flash of a screenshot, then it becomes real" trick WhatsApp itself uses
 * on cold start.
 *
 * Written opportunistically (fire-and-forget, background thread) every time
 * ChatsFragment renders a real, non-empty contacts list — see
 * ChatsFragment#diffUpdateContacts. Intentionally lossy/best-effort: if the
 * write is skipped or stale, worst case is the old fallback behavior
 * (blank until Room loads), never wrong/corrupt data — the real Room load
 * always overwrites it within the same frame or two.
 */
public final class ChatSnapshotCache {

    private static final String PREFS_NAME   = "chat_list_snapshot";
    private static final String KEY_SNAPSHOT = "top_chats_json";
    /** Only the first screenful — this is a first-paint placeholder, not a cache replacement. */
    private static final int SNAPSHOT_SIZE = 15;

    private ChatSnapshotCache() {}

    /**
     * Synchronous, main-thread-safe read — SharedPreferences.getString() on
     * an already-small file is effectively instant (Android keeps the
     * XML/binary prefs file parsed in memory after the first access per
     * process; even the first cold access of a few-KB file is a fraction of
     * SQLCipher's DB-open cost). Safe to call directly from
     * ChatsFragment#onCreateView before loadFromRoom() fires.
     */
    public static List<User> loadInstantSnapshot(Context ctx) {
        List<User> result = new ArrayList<>();
        try {
            SharedPreferences prefs = ctx.getApplicationContext()
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String json = prefs.getString(KEY_SNAPSHOT, null);
            if (json == null || json.isEmpty()) return result;

            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                User u = new User();
                u.uid                   = o.optString("uid", null);
                u.name                  = o.optString("name", null);
                u.thumbUrl              = o.optString("thumbUrl", null);
                u.photoUrl              = o.optString("photoUrl", null);
                u.avatarVersion         = o.optLong("avatarVersion", 0L);
                u.lastMessage           = o.optString("lastMessage", null);
                u.lastMessageAt         = o.has("lastMessageAt") ? o.optLong("lastMessageAt") : null;
                u.unread                = o.has("unread") ? o.optLong("unread") : null;
                u.lastMessageType       = o.optString("lastMessageType", null);
                u.lastMessageStatus     = o.optString("lastMessageStatus", null);
                u.lastMessageSenderUid  = o.optString("lastMessageSenderUid", null);
                if (u.uid != null && !u.uid.isEmpty()) result.add(u);
            }
        } catch (Exception e) {
            // Corrupt/old-format snapshot — just skip it, real data loads normally.
            Log.w("ChatSnapshotCache", "loadInstantSnapshot failed: " + e.getMessage());
        }
        return result;
    }

    /**
     * Fire-and-forget write, always off the main thread (AppBgExecutor —
     * this is exactly the "small, low-frequency background write" idiom
     * that pool exists for). Called every time the Chat List renders a
     * real non-empty list, so the snapshot is always close to what the
     * user actually saw last, for next cold start.
     */
    public static void saveSnapshotAsync(Context ctx, List<User> topContacts) {
        if (topContacts == null || topContacts.isEmpty()) return;
        Context appCtx = ctx.getApplicationContext();
        // Copy the small slice we need on the calling (main) thread — cheap,
        // just references — so the background task never touches a list
        // that might be mutated concurrently.
        List<User> slice = new ArrayList<>(
                topContacts.subList(0, Math.min(SNAPSHOT_SIZE, topContacts.size())));

        AppBgExecutor.execute(() -> {
            try {
                JSONArray arr = new JSONArray();
                for (User u : slice) {
                    if (u.uid == null || u.uid.isEmpty()) continue;
                    JSONObject o = new JSONObject();
                    o.put("uid", u.uid);
                    if (u.name != null) o.put("name", u.name);
                    if (u.thumbUrl != null) o.put("thumbUrl", u.thumbUrl);
                    if (u.photoUrl != null) o.put("photoUrl", u.photoUrl);
                    if (u.avatarVersion > 0) o.put("avatarVersion", u.avatarVersion);
                    if (u.lastMessage != null) o.put("lastMessage", u.lastMessage);
                    if (u.lastMessageAt != null) o.put("lastMessageAt", u.lastMessageAt);
                    if (u.unread != null) o.put("unread", u.unread);
                    if (u.lastMessageType != null) o.put("lastMessageType", u.lastMessageType);
                    if (u.lastMessageStatus != null) o.put("lastMessageStatus", u.lastMessageStatus);
                    if (u.lastMessageSenderUid != null) o.put("lastMessageSenderUid", u.lastMessageSenderUid);
                    arr.put(o);
                }
                appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putString(KEY_SNAPSHOT, arr.toString())
                        .apply();
            } catch (Exception e) {
                Log.w("ChatSnapshotCache", "saveSnapshotAsync failed: " + e.getMessage());
            }
        });
    }

    /**
     * WHATSAPP-LEVEL FIX: clear the placeholder on logout / account switch /
     * account deletion. Without this, User A's top-15 chat preview (names,
     * last-message text, thumbnails) would still be sitting in plaintext
     * SharedPreferences and would flash on screen as the very first frame
     * the next time ChatsFragment opens — even for a different account that
     * just logged in on the same device. Fire-and-forget, background thread;
     * safe to call even if no snapshot was ever written.
     */
    public static void clearSnapshotAsync(Context ctx) {
        Context appCtx = ctx.getApplicationContext();
        AppBgExecutor.execute(() -> {
            try {
                appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .remove(KEY_SNAPSHOT)
                        .apply();
            } catch (Exception e) {
                Log.w("ChatSnapshotCache", "clearSnapshotAsync failed: " + e.getMessage());
            }
        });
    }
}
