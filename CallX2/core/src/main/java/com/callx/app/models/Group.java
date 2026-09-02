package com.callx.app.models;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.Exclude;
import java.util.HashMap;
import java.util.Map;
public class Group {
    public String id;
    public String name;
    public String description;
    public String iconUrl;
    public String createdBy;
    public String adminUid;          // primary admin (creator by default)
    public Long createdAt;
    public String lastMessage;
    public String lastSenderName;    // last message ka sender display name
    public Long lastMessageAt;
    // Group-list v24: read receipts (ticks) + media label support — group
    // analogue of the same fields on User (1:1 chat list). lastMessageStatus
    // here reflects the AGGREGATE status from GroupMessageStatusSync
    // (delivered = every other member has it, read = every other member has
    // seen it), matching WhatsApp's group-tick semantics. Only rendered as
    // ticks when lastMessageSenderUid == the viewer's own uid.
    public String lastMessageType;
    public String lastMessageStatus;
    public String lastMessageSenderUid;
    public String lastMessageId;
    public Map<String, Boolean> members  = new HashMap<>();
    public Map<String, Boolean> admins   = new HashMap<>();
    // mutedBy/{uid} = true => us user ke liye group muted hai (silent push)
    public Map<String, Boolean> mutedBy  = new HashMap<>();
    // unread/{uid} = count for that user (server-incremented)
    public Map<String, Long>    unread   = new HashMap<>();

    /**
     * Viewer-local list state. These are deliberately excluded from Firebase:
     * pin/archive are device preferences, while muted is hydrated from the
     * viewer's mutedBy entry by GroupsFragment.
     */
    @Exclude public boolean localPinned;
    @Exclude public boolean localArchived;
    @Exclude public boolean localMuted;
    public Group() {}

    // ── Group Topics (Telegram-style threads) ────────────────────────────
    // topicsEnabled = admin-toggled; hides Topics UI when false
    public boolean topicsEnabled;

    // ── Anonymous Posting ────────────────────────────────────────────────
    // anonymousPostingEnabled = true means non-admin members can tick
    // "Post anonymously" before sending.
    // Stored at: groups/{groupId}/groupSettings/anonymousPostingEnabled

    // ── Slow Mode ────────────────────────────────────────────────────────
    // slowModeSecs stored at: groups/{groupId}/groupSettings/slowModeSecs
    // Enforced client-side in GroupChatActivity.

    // CRASH FIX: ds.getValue(Group.class) throws DatabaseException
    // ("Failed to convert value of type java.util.HashMap to boolean")
    // whenever ANY primitive boolean field on this class (currently just
    // `topicsEnabled`) holds legacy/malformed data on some existing group
    // node — e.g. an old app version once wrote it as a nested object.
    // Firebase's automatic bean mapper has no way to recover from that; it
    // fails the entire deserialization, not just that one field, which is
    // what was crashing GroupsFragment/GroupInfoActivity/ContactsActivity.
    //
    // Fix: parse defensively. Try the fast path first (works for the
    // overwhelming majority of well-formed groups); if it throws, fall back
    // to reading every field by hand so one bad flag can't take down the
    // whole object. Existing storage paths/keys are unchanged — this only
    // changes how we read, so it's safe against whatever is already in the
    // database.
    public static Group fromSnapshot(DataSnapshot ds) {
        try {
            Group g = ds.getValue(Group.class);
            if (g != null && g.id == null) g.id = ds.getKey();
            return g;
        } catch (Exception e) {
            return fromSnapshotSafe(ds);
        }
    }

    private static Group fromSnapshotSafe(DataSnapshot ds) {
        Group g = new Group();
        g.id = safeStr(ds, "id");
        if (g.id == null || g.id.isEmpty()) g.id = ds.getKey();
        g.name               = safeStr(ds, "name");
        g.description        = safeStr(ds, "description");
        g.iconUrl             = safeStr(ds, "iconUrl");
        g.createdBy           = safeStr(ds, "createdBy");
        g.adminUid            = safeStr(ds, "adminUid");
        g.createdAt           = safeLong(ds, "createdAt");
        g.lastMessage         = safeStr(ds, "lastMessage");
        g.lastSenderName      = safeStr(ds, "lastSenderName");
        g.lastMessageAt       = safeLong(ds, "lastMessageAt");
        g.lastMessageType     = safeStr(ds, "lastMessageType");
        g.lastMessageStatus   = safeStr(ds, "lastMessageStatus");
        g.lastMessageSenderUid = safeStr(ds, "lastMessageSenderUid");
        g.lastMessageId       = safeStr(ds, "lastMessageId");
        g.members = safeBoolMap(ds, "members");
        g.admins  = safeBoolMap(ds, "admins");
        g.mutedBy = safeBoolMap(ds, "mutedBy");
        g.unread  = new HashMap<>();
        DataSnapshot unreadSnap = ds.child("unread");
        if (unreadSnap.exists()) {
            for (DataSnapshot child : unreadSnap.getChildren()) {
                Long v = safeLong(child);
                if (v != null) g.unread.put(child.getKey(), v);
            }
        }
        // The field this crash was actually about: only accept a real
        // boolean, ignore it (default false) if it's some other shape.
        Object rawTopics = ds.child("topicsEnabled").getValue();
        g.topicsEnabled = (rawTopics instanceof Boolean) && (Boolean) rawTopics;
        return g;
    }

    private static String safeStr(DataSnapshot ds, String key) {
        try { return ds.child(key).getValue(String.class); }
        catch (Exception e) { return null; }
    }

    private static Long safeLong(DataSnapshot ds, String key) {
        return safeLong(ds.child(key));
    }

    private static Long safeLong(DataSnapshot ds) {
        try {
            Object v = ds.getValue();
            if (v instanceof Long) return (Long) v;
            if (v instanceof Number) return ((Number) v).longValue();
            return null;
        } catch (Exception e) { return null; }
    }

    private static Map<String, Boolean> safeBoolMap(DataSnapshot ds, String key) {
        Map<String, Boolean> map = new HashMap<>();
        DataSnapshot node = ds.child(key);
        if (!node.exists()) return map;
        for (DataSnapshot child : node.getChildren()) {
            Object v = child.getValue();
            if (v instanceof Boolean) map.put(child.getKey(), (Boolean) v);
        }
        return map;
    }
}

