package com.callx.app.chatlist;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.GroupEntity;
import com.callx.app.models.Group;
import com.callx.app.models.User;
import com.callx.app.utils.AppBgExecutor;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Feeds the user's groups into the unified Chats list.
 *
 * This is the data layer of the old Groups tab, moved out of the Fragment so
 * ChatsFragment can merge group rows with 1:1 rows in a single list:
 *   • Room first (instant, offline) → then Firebase membership ChildEventListener
 *     + one narrow ValueEventListener per group (only that row is replaced when
 *     a group changes — same as before).
 *   • Pin / archive stay viewer-local in the SAME SharedPreferences file the
 *     Groups tab used ("group_list_settings"), so existing pins/archives survive.
 *   • Each group is exposed as a User-shaped row (uid = "g:" + groupId,
 *     isGroup = true) so ChatListAdapter's canvas row, selection, pin, search
 *     and diffing are reused as-is. Real user uids can never collide with the
 *     "g:" prefix.
 *
 * All public methods must be called on the main thread. The listener is always
 * invoked on the main thread.
 */
public final class GroupChatListSource {

    public static final String UID_PREFIX = "g:";

    public interface Listener {
        /** Fresh, fully-built row list (new User instances — safe for DiffUtil). */
        void onGroupRowsChanged(@NonNull List<User> rows);
    }

    private static final String PREFS = "group_list_settings";
    private static final String KEY_PINNED = "pinned";
    private static final String KEY_ARCHIVED = "archived";

    private final Context appContext;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;

    private final Map<String, Group> groups = new HashMap<>();
    private final Set<String> activeGroupIds = new HashSet<>();
    private final Map<String, DatabaseReference> groupRefs = new HashMap<>();
    private final Map<String, ValueEventListener> groupListeners = new HashMap<>();

    private ChildEventListener membershipListener;
    private DatabaseReference userGroupsRef;
    private boolean started;
    private boolean stopped;
    private boolean roomLoaded;

    // Coalesces bursts (e.g. N groups all firing their first value event at
    // app start) into a single list rebuild + single DiffUtil pass.
    private static final long EMIT_DEBOUNCE_MS = 60L;
    private final Runnable emitRunnable = this::emitNow;
    private boolean emitScheduled;

    public GroupChatListSource(@NonNull Context context, @NonNull Listener listener) {
        this.appContext = context.getApplicationContext();
        this.listener = listener;
        this.prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────

    /** Idempotent. Loads Room cache, then attaches the live Firebase listeners. */
    public void start() {
        if (started || stopped) return;
        String uid = FirebaseUtils.getCurrentUid();
        if (uid == null || uid.isEmpty()) return;
        started = true;
        loadFromRoom();
        attachMembershipListener(uid);
    }

    public void stop() {
        stopped = true;
        started = false;
        main.removeCallbacks(emitRunnable);
        emitScheduled = false;
        if (userGroupsRef != null && membershipListener != null) {
            userGroupsRef.removeEventListener(membershipListener);
        }
        userGroupsRef = null;
        membershipListener = null;
        for (String id : new ArrayList<>(groupRefs.keySet())) detachGroup(id);
        groupRefs.clear();
        groupListeners.clear();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Room (cold-start paint)
    // ─────────────────────────────────────────────────────────────────────

    private void loadFromRoom() {
        AppBgExecutor.execute(() -> {
            List<GroupEntity> cached;
            try {
                cached = AppDatabase.getInstance(appContext).groupDao().getAllGroupsSync();
            } catch (Exception e) {
                return;
            }
            if (cached == null || cached.isEmpty()) {
                main.post(() -> roomLoaded = true);
                return;
            }
            final List<Group> restored = new ArrayList<>();
            for (GroupEntity e : cached) {
                if (e.id == null || e.id.isEmpty()) continue;
                Group g = new Group();
                g.id = e.id;
                g.name = e.name;
                g.description = e.description;
                g.iconUrl = e.iconUrl;
                g.createdBy = e.createdBy;
                g.lastMessage = e.lastMessage;
                g.lastSenderName = e.lastSenderName;
                g.lastMessageAt = e.lastMessageAt;
                g.lastMessageType = e.lastMessageType;
                g.lastMessageStatus = e.lastMessageStatus;
                g.lastMessageSenderUid = e.lastMessageSenderUid;
                g.lastMessageId = e.lastMessageId;
                String me = FirebaseUtils.getCurrentUid();
                if (e.unread != null && me != null) g.unread.put(me, e.unread);
                g.localMuted = Boolean.TRUE.equals(e.muted);
                restored.add(g);
            }
            main.post(() -> {
                if (stopped) return;
                roomLoaded = true;
                // Live data that already arrived wins over the (older) Room copy.
                for (Group g : restored) {
                    if (!groups.containsKey(g.id)) groups.put(g.id, g);
                }
                scheduleEmit();
            });
        });
    }

    private void saveGroup(Group g) {
        if (g == null || g.id == null) return;
        final GroupEntity e = new GroupEntity();
        e.id = g.id;
        e.name = g.name;
        e.description = g.description;
        e.iconUrl = g.iconUrl;
        e.createdBy = g.createdBy;
        e.lastMessage = g.lastMessage;
        e.lastSenderName = g.lastSenderName;
        e.lastMessageAt = g.lastMessageAt;
        e.lastMessageType = g.lastMessageType;
        e.lastMessageStatus = g.lastMessageStatus;
        e.lastMessageSenderUid = g.lastMessageSenderUid;
        e.lastMessageId = g.lastMessageId;
        String uid = FirebaseUtils.getCurrentUid();
        Long u = (g.unread == null || uid == null) ? null : g.unread.get(uid);
        e.unread = u == null ? 0L : u;
        e.muted = g.localMuted;
        AppBgExecutor.execute(() -> {
            try { AppDatabase.getInstance(appContext).groupDao().insertGroup(e); }
            catch (Exception ignored) { }
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // Firebase
    // ─────────────────────────────────────────────────────────────────────

    private void attachMembershipListener(String uid) {
        userGroupsRef = FirebaseUtils.getUserGroupsRef(uid);
        membershipListener = new ChildEventListener() {
            @Override public void onChildAdded(@NonNull DataSnapshot snap, String prev) {
                String id = snap.getKey();
                if (id == null || id.isEmpty()) return;
                activeGroupIds.add(id);
                attachGroup(id);
            }
            @Override public void onChildChanged(@NonNull DataSnapshot snap, String prev) {
                String id = snap.getKey();
                if (id == null || id.isEmpty()) return;
                activeGroupIds.add(id);
                attachGroup(id);
            }
            @Override public void onChildRemoved(@NonNull DataSnapshot snap) {
                String id = snap.getKey();
                if (id == null) return;
                activeGroupIds.remove(id);
                detachGroup(id);
                removeLocal(id, true);
            }
            @Override public void onChildMoved(@NonNull DataSnapshot snap, String prev) { }
            @Override public void onCancelled(@NonNull DatabaseError error) { }
        };
        userGroupsRef.addChildEventListener(membershipListener);

        // One small membership snapshot prunes stale Room-restored rows
        // (groups the user was removed from while the app was closed).
        userGroupsRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (stopped) return;
                activeGroupIds.clear();
                for (DataSnapshot child : snap.getChildren()) {
                    if (child.getKey() != null) activeGroupIds.add(child.getKey());
                }
                boolean changed = groups.keySet().removeIf(id -> !activeGroupIds.contains(id));
                if (changed) scheduleEmit();
            }
            @Override public void onCancelled(@NonNull DatabaseError error) { }
        });
    }

    private void attachGroup(String groupId) {
        if (groupRefs.containsKey(groupId)) return;
        DatabaseReference ref = FirebaseUtils.getGroupsRef().child(groupId);
        ValueEventListener l = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (stopped || !activeGroupIds.contains(groupId)) return;
                Group g = Group.fromSnapshot(snap);
                if (g == null) return;
                if (g.id == null || g.id.isEmpty()) g.id = groupId;
                String me = FirebaseUtils.getCurrentUid();
                g.localMuted = me != null && g.mutedBy != null
                        && Boolean.TRUE.equals(g.mutedBy.get(me));
                groups.put(g.id, g);
                saveGroup(g);
                scheduleEmit();
            }
            @Override public void onCancelled(@NonNull DatabaseError error) { }
        };
        groupRefs.put(groupId, ref);
        groupListeners.put(groupId, l);
        ref.addValueEventListener(l);
    }

    private void detachGroup(String id) {
        DatabaseReference ref = groupRefs.remove(id);
        ValueEventListener l = groupListeners.remove(id);
        if (ref != null && l != null) ref.removeEventListener(l);
    }

    private void removeLocal(String id, boolean deleteFromRoom) {
        groups.remove(id);
        if (deleteFromRoom) {
            AppBgExecutor.execute(() -> {
                try { AppDatabase.getInstance(appContext).groupDao().deleteGroup(id); }
                catch (Exception ignored) { }
            });
        }
        scheduleEmit();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Emit
    // ─────────────────────────────────────────────────────────────────────

    private void scheduleEmit() {
        if (stopped || emitScheduled) return;
        emitScheduled = true;
        main.postDelayed(emitRunnable, EMIT_DEBOUNCE_MS);
    }

    /** Force a rebuild now (after a local pin/archive/mute change). */
    private void emitNow() {
        emitScheduled = false;
        if (stopped) return;
        listener.onGroupRowsChanged(buildRows());
    }

    /** Current rows without waiting for the debounce. */
    public List<User> snapshotRows() { return buildRows(); }

    private List<User> buildRows() {
        Set<String> pinned = prefs.getStringSet(KEY_PINNED, Collections.emptySet());
        Set<String> archived = prefs.getStringSet(KEY_ARCHIVED, Collections.emptySet());
        String me = FirebaseUtils.getCurrentUid();
        List<User> rows = new ArrayList<>(groups.size());
        for (Group g : groups.values()) {
            if (g == null || g.id == null) continue;
            User u = new User();
            u.isGroup = true;
            u.groupId = g.id;
            u.uid = UID_PREFIX + g.id;
            u.name = (g.name == null || g.name.isEmpty()) ? "Group" : g.name;
            u.photoUrl = g.iconUrl;
            u.thumbUrl = g.iconUrl;
            u.lastMessage = g.lastMessage;
            u.lastMessageType = g.lastMessageType;
            u.lastMessageStatus = g.lastMessageStatus;
            u.lastMessageSenderUid = g.lastMessageSenderUid;
            u.lastMessageId = g.lastMessageId;
            u.lastSenderName = g.lastSenderName;
            // Empty groups sort by creation time, exactly like the old tab.
            u.lastMessageAt = (g.lastMessageAt != null && g.lastMessageAt > 0)
                    ? g.lastMessageAt : g.createdAt;
            Long unread = (g.unread == null || me == null) ? null : g.unread.get(me);
            u.unread = unread == null ? 0L : Math.max(0L, unread);
            u.memberCount = g.members == null ? 0 : g.members.size();
            u.localPinned = pinned.contains(g.id);
            u.localMuted = g.localMuted;
            // Archived groups are filtered by the caller via isArchived(); keep
            // a marker in `emoji` is avoided on purpose — see isArchived().
            rows.add(u);
        }
        return rows;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Local state (pin / archive / mute / leave) — called by ChatsFragment
    // ─────────────────────────────────────────────────────────────────────

    public boolean isArchived(@Nullable String groupId) {
        return groupId != null
                && prefs.getStringSet(KEY_ARCHIVED, Collections.emptySet()).contains(groupId);
    }

    public int archivedCount() {
        Set<String> archived = prefs.getStringSet(KEY_ARCHIVED, Collections.emptySet());
        int n = 0;
        for (String id : archived) if (groups.containsKey(id)) n++;
        return n;
    }

    public boolean isPinned(@Nullable String groupId) {
        return groupId != null
                && prefs.getStringSet(KEY_PINNED, Collections.emptySet()).contains(groupId);
    }

    /** Pins every id if any is unpinned, otherwise unpins all (WhatsApp toggle). */
    public void togglePinned(Collection<String> groupIds) {
        if (groupIds.isEmpty()) return;
        Set<String> pinned = new HashSet<>(prefs.getStringSet(KEY_PINNED, Collections.emptySet()));
        boolean pin = false;
        for (String id : groupIds) if (!pinned.contains(id)) { pin = true; break; }
        for (String id : groupIds) { if (pin) pinned.add(id); else pinned.remove(id); }
        prefs.edit().putStringSet(KEY_PINNED, pinned).apply();
        emitNow();
    }

    /** Explicit pin/unpin of exactly these ids (used for mixed chat+group selections). */
    public void setPinned(Collection<String> groupIds, boolean pin) {
        if (groupIds.isEmpty()) return;
        Set<String> pinned = new HashSet<>(prefs.getStringSet(KEY_PINNED, Collections.emptySet()));
        for (String id : groupIds) { if (pin) pinned.add(id); else pinned.remove(id); }
        prefs.edit().putStringSet(KEY_PINNED, pinned).apply();
        emitNow();
    }

    /** Archives every id if any is not archived, otherwise unarchives all. */
    public boolean toggleArchived(Collection<String> groupIds) {
        if (groupIds.isEmpty()) return false;
        Set<String> archived = new HashSet<>(prefs.getStringSet(KEY_ARCHIVED, Collections.emptySet()));
        boolean archive = false;
        for (String id : groupIds) if (!archived.contains(id)) { archive = true; break; }
        for (String id : groupIds) { if (archive) archived.add(id); else archived.remove(id); }
        prefs.edit().putStringSet(KEY_ARCHIVED, archived).apply();
        emitNow();
        return archive;
    }

    /** Mutes every id if any is unmuted, otherwise unmutes all. Writes mutedBy/{me}. */
    public boolean toggleMuted(Collection<String> groupIds) {
        String me = FirebaseUtils.getCurrentUid();
        if (me == null || groupIds.isEmpty()) return false;
        boolean mute = false;
        for (String id : groupIds) {
            Group g = groups.get(id);
            if (g != null && !g.localMuted) { mute = true; break; }
        }
        for (String id : groupIds) {
            Group g = groups.get(id);
            if (g == null) continue;
            g.localMuted = mute;
            if (g.mutedBy == null) g.mutedBy = new HashMap<>();
            DatabaseReference ref = FirebaseUtils.getGroupsRef().child(id).child("mutedBy").child(me);
            if (mute) { g.mutedBy.put(me, true); ref.setValue(true); }
            else { g.mutedBy.remove(me); ref.removeValue(); }
            saveGroup(g);
        }
        emitNow();
        return mute;
    }

    /** Leave + remove from list (same effect as the old Groups tab's delete). */
    public void leave(Collection<String> groupIds) {
        String me = FirebaseUtils.getCurrentUid();
        if (me == null || groupIds.isEmpty()) return;
        Set<String> pinned = new HashSet<>(prefs.getStringSet(KEY_PINNED, Collections.emptySet()));
        Set<String> archived = new HashSet<>(prefs.getStringSet(KEY_ARCHIVED, Collections.emptySet()));
        for (String id : groupIds) {
            FirebaseUtils.getUserGroupsRef(me).child(id).removeValue();
            activeGroupIds.remove(id);
            detachGroup(id);
            groups.remove(id);
            pinned.remove(id);
            archived.remove(id);
            AppBgExecutor.execute(() -> {
                try { AppDatabase.getInstance(appContext).groupDao().deleteGroup(id); }
                catch (Exception ignored) { }
            });
        }
        prefs.edit().putStringSet(KEY_PINNED, pinned).putStringSet(KEY_ARCHIVED, archived).apply();
        emitNow();
    }
}
