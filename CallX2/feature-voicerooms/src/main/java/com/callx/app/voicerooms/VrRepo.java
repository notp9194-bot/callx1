package com.callx.app.voicerooms;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.callx.app.voicerooms.VrModel.Member;
import com.callx.app.voicerooms.VrModel.Msg;
import com.callx.app.voicerooms.VrModel.Poll;
import com.callx.app.voicerooms.VrModel.Room;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.Query;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * VrRepo — Voice Rooms ka saara Firebase data layer.
 *
 *   voiceRoomList/{roomId}                      Home list (sirf LIVE rooms, server likhta hai)
 *   voiceRooms/{roomId}/meta                    title, host, status, audienceCount ...
 *   voiceRooms/{roomId}/speakers/{uid}          host + speakers (server likhta hai; selfMuted khud)
 *   voiceRooms/{roomId}/audience/{uid}          listeners (har user apni row khud likhta hai)
 *   voiceRooms/{roomId}/handRaises/{uid}        raise-hand requests
 *   voiceRooms/{roomId}/chat/{id}               chat (limitToLast(50))
 *   voiceRooms/{roomId}/pinned, polls/{id}      host controlled
 *
 * Saare callbacks main thread par aate hain (Firebase Android default).
 */
final class VrRepo {
    private VrRepo() {}

    static final int CHAT_LIMIT = 50;
    static final int AUDIENCE_LIMIT = 60;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static FirebaseDatabase db() { return FirebaseDatabase.getInstance(); }
    static DatabaseReference room(String roomId) { return db().getReference("voiceRooms").child(roomId); }

    @Nullable static String myUid() {
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        return u == null ? null : u.getUid();
    }

    // ───────────────────────── me ─────────────────────────
    static final class Me {
        String uid = "", name = "User", photoUrl = "";
        boolean verified;
    }
    private static Me meCache;

    interface MeCb { void onMe(Me me); }

    static void loadMe(MeCb cb) {
        String uid = myUid();
        if (uid == null) { Me m = new Me(); cb.onMe(m); return; }
        if (meCache != null && uid.equals(meCache.uid)) { cb.onMe(meCache); return; }
        db().getReference("users").child(uid).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                Me m = new Me();
                m.uid = uid;
                m.name = str(s, "name", "User");
                m.photoUrl = str(s, "photoUrl", "");
                m.verified = bool(s, "isVerified") || bool(s, "verified") || bool(s, "blueBadge");
                meCache = m;
                cb.onMe(m);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                Me m = new Me(); m.uid = uid; cb.onMe(m);
            }
        });
    }

    // ───────────────────────── Home: live rooms list ─────────────────────────
    interface RoomsCb { void onRooms(List<Room> rooms); void onError(String message); }

    static final class RoomsWatch {
        private final Query q; private final ValueEventListener l;
        RoomsWatch(Query q, ValueEventListener l) { this.q = q; this.l = l; }
        void stop() { q.removeEventListener(l); }
    }

    static RoomsWatch watchRooms(RoomsCb cb) {
        Query q = db().getReference("voiceRoomList").orderByChild("createdAt").limitToLast(60);
        ValueEventListener l = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                List<Room> out = new ArrayList<>();
                for (DataSnapshot c : snap.getChildren()) {
                    Room r = parseListRow(c);
                    if (r != null) out.add(r);
                }
                Collections.sort(out, (a, b) -> Integer.compare(b.listeners, a.listeners));
                cb.onRooms(out);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { cb.onError(e.getMessage()); }
        };
        q.addValueEventListener(l);
        return new RoomsWatch(q, l);
    }

    private static Room parseListRow(DataSnapshot c) {
        String title = str(c, "title", "");
        if (title.isEmpty()) return null;
        Room r = new Room();
        r.id = c.getKey();
        r.title = title;
        r.topic = str(c, "topic", "");
        r.category = str(c, "category", "Talk");
        r.hostUid = str(c, "hostUid", "");
        r.hostName = str(c, "hostName", "Host");
        r.hostPhoto = str(c, "hostPhoto", "");
        r.hostVerified = bool(c, "hostVerified");
        r.listeners = (int) lng(c, "listeners", 1);
        r.createdAt = lng(c, "createdAt", 0);
        r.description = "";
        // Home card: host (photo ke saath) + baaki speakers (initials)
        r.speakers.add(hostMember(r));
        DataSnapshot names = c.child("speakerNames");
        int added = 1;
        for (DataSnapshot n : names.getChildren()) {
            String nm = n.getValue(String.class);
            if (nm == null || nm.equals(r.hostName)) continue;
            if (added >= 3) break;
            r.speakers.add(new Member("n_" + added + "_" + nm, nm, VrModel.ROLE_SPEAKER, true, false));
            added++;
        }
        return r;
    }

    private static Member hostMember(Room r) {
        Member h = new Member(r.hostUid == null ? "host" : r.hostUid, r.hostName,
                VrModel.ROLE_HOST, true, r.hostVerified);
        h.photoUrl = r.hostPhoto;
        return h;
    }

    // ───────────────────────── Room watcher ─────────────────────────
    interface RoomListener {
        void onMeta(Room room);                 // meta badla (title/topic/audienceCount ...)
        void onRoomEnded(String reason);        // status != live ya meta delete
        void onBanned();                        // host ne remove kiya
        void onSpeakers(List<Member> speakers);
        void onAudience(List<Member> audience); // preview (limitToFirst(AUDIENCE_LIMIT))
        void onHandRaises(List<Member> raised); // host ke liye pending requests
        void onChatAdded(Msg m);
        void onChatChanged(Msg m);
        void onChatRemoved(String id);
        void onPinned(@Nullable String by, @Nullable String text);
        void onPoll(@Nullable Poll poll);
    }

    static final class RoomWatch {
        private final String roomId;
        private final String myUid;
        private final RoomListener cb;
        private final List<Object[]> attached = new ArrayList<>(); // {Query, listener}
        private final long openedAt = System.currentTimeMillis();
        private boolean stopped;

        private final Set<String> raised = new HashSet<>();
        private List<Member> lastAudience = new ArrayList<>();

        RoomWatch(String roomId, RoomListener cb) {
            this.roomId = roomId;
            this.cb = cb;
            this.myUid = myUid();
        }

        long openedAt() { return openedAt; }

        private void add(Query q, Object l) { attached.add(new Object[]{q, l}); }

        void start() {
            DatabaseReference r = room(roomId);

            ValueEventListener meta = new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot s) {
                    if (stopped) return;
                    if (!s.exists()) { cb.onRoomEnded("gone"); return; }
                    String status = str(s, "status", "live");
                    Room room = new Room();
                    room.id = roomId;
                    room.title = str(s, "title", "Voice room");
                    room.topic = str(s, "topic", "");
                    room.description = str(s, "description", "");
                    room.category = str(s, "category", "Talk");
                    room.hostUid = str(s, "hostUid", "");
                    room.hostName = str(s, "hostName", "Host");
                    room.hostPhoto = str(s, "hostPhoto", "");
                    room.hostVerified = bool(s, "hostVerified");
                    room.allowRequests = !s.hasChild("allowRequests") || bool(s, "allowRequests");
                    room.audienceCount = (int) lng(s, "audienceCount", 0);
                    room.live = "live".equals(status);
                    cb.onMeta(room);
                    if (!room.live) cb.onRoomEnded(str(s, "endReason", "ended"));
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            };
            r.child("meta").addValueEventListener(meta); add(r.child("meta"), meta);

            ValueEventListener speakers = new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot s) {
                    if (stopped) return;
                    List<Member> out = new ArrayList<>();
                    for (DataSnapshot c : s.getChildren()) {
                        Member m = new Member(c.getKey(), str(c, "name", "User"),
                                "host".equals(str(c, "role", "")) ? VrModel.ROLE_HOST : VrModel.ROLE_SPEAKER,
                                true, bool(c, "verified"));
                        m.photoUrl = str(c, "photoUrl", "");
                        m.hostMuted = bool(c, "hostMuted");
                        m.selfMuted = !c.hasChild("selfMuted") || bool(c, "selfMuted");
                        m.muted = m.hostMuted || m.selfMuted;
                        out.add(m);
                    }
                    // host sabse pehle
                    Collections.sort(out, (a, b) -> Boolean.compare(
                            VrModel.ROLE_HOST.equals(b.role), VrModel.ROLE_HOST.equals(a.role)));
                    cb.onSpeakers(out);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            };
            r.child("speakers").addValueEventListener(speakers); add(r.child("speakers"), speakers);

            Query aq = r.child("audience").limitToFirst(AUDIENCE_LIMIT);
            ValueEventListener audience = new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot s) {
                    if (stopped) return;
                    List<Member> out = new ArrayList<>();
                    for (DataSnapshot c : s.getChildren()) {
                        Member m = new Member(c.getKey(), str(c, "name", "User"),
                                VrModel.ROLE_LISTENER, true, bool(c, "verified"));
                        m.photoUrl = str(c, "photoUrl", "");
                        m.handRaised = raised.contains(m.uid);
                        out.add(m);
                    }
                    lastAudience = out;
                    cb.onAudience(out);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            };
            aq.addValueEventListener(audience); add(aq, audience);

            ValueEventListener hands = new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot s) {
                    if (stopped) return;
                    raised.clear();
                    List<Member> out = new ArrayList<>();
                    for (DataSnapshot c : s.getChildren()) {
                        raised.add(c.getKey());
                        Member m = new Member(c.getKey(), str(c, "name", "User"),
                                VrModel.ROLE_LISTENER, true, false);
                        m.photoUrl = str(c, "photoUrl", "");
                        m.handRaised = true;
                        out.add(m);
                    }
                    for (Member m : lastAudience) m.handRaised = raised.contains(m.uid);
                    cb.onHandRaises(out);
                    cb.onAudience(lastAudience);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            };
            r.child("handRaises").addValueEventListener(hands); add(r.child("handRaises"), hands);

            Query cq = r.child("chat").orderByChild("ts").limitToLast(CHAT_LIMIT);
            ChildEventListener chat = new ChildEventListener() {
                @Override public void onChildAdded(@NonNull DataSnapshot s, String prev) {
                    if (!stopped) cb.onChatAdded(parseMsg(s, myUid));
                }
                @Override public void onChildChanged(@NonNull DataSnapshot s, String prev) {
                    if (!stopped) cb.onChatChanged(parseMsg(s, myUid));
                }
                @Override public void onChildRemoved(@NonNull DataSnapshot s) {
                    if (!stopped) cb.onChatRemoved(s.getKey());
                }
                @Override public void onChildMoved(@NonNull DataSnapshot s, String prev) {}
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            };
            cq.addChildEventListener(chat); add(cq, chat);

            ValueEventListener pinned = new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot s) {
                    if (stopped) return;
                    if (!s.exists()) cb.onPinned(null, null);
                    else cb.onPinned(str(s, "by", ""), str(s, "text", ""));
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            };
            r.child("pinned").addValueEventListener(pinned); add(r.child("pinned"), pinned);

            Query pq = r.child("polls").orderByKey().limitToLast(1);
            ValueEventListener polls = new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot s) {
                    if (stopped) return;
                    Poll p = null;
                    for (DataSnapshot c : s.getChildren()) p = parsePoll(c, myUid);
                    cb.onPoll(p);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            };
            pq.addValueEventListener(polls); add(pq, polls);

            if (myUid != null) {
                ValueEventListener banned = new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot s) {
                        if (!stopped && s.exists()) cb.onBanned();
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {}
                };
                r.child("banned").child(myUid).addValueEventListener(banned);
                add(r.child("banned").child(myUid), banned);
            }
        }

        void stop() {
            stopped = true;
            for (Object[] a : attached) {
                Query q = (Query) a[0];
                if (a[1] instanceof ChildEventListener) q.removeEventListener((ChildEventListener) a[1]);
                else q.removeEventListener((ValueEventListener) a[1]);
            }
            attached.clear();
        }
    }

    private static Msg parseMsg(DataSnapshot s, String myUid) {
        long ts = lng(s, "ts", System.currentTimeMillis());
        Msg m = new Msg(str(s, "name", "User"),
                new SimpleDateFormat("h:mm a", Locale.getDefault()).format(new Date(ts)),
                str(s, "text", ""), (int) s.child("likes").getChildrenCount());
        m.id = s.getKey();
        m.uid = str(s, "uid", "");
        m.photoUrl = str(s, "photoUrl", "");
        m.type = str(s, "type", "text");
        m.giftEmoji = str(s, "giftEmoji", "");
        m.ts = ts;
        m.liked = myUid != null && s.child("likes").hasChild(myUid);
        return m;
    }

    private static Poll parsePoll(DataSnapshot c, String myUid) {
        Poll p = new Poll();
        p.id = c.getKey();
        p.question = str(c, "q", "");
        for (DataSnapshot o : c.child("options").getChildren()) {
            String v = o.getValue(String.class);
            if (v != null) p.options.add(v);
        }
        if (p.options.size() < 2) return null;
        p.endsAt = lng(c, "endsAt", 0);
        p.counts = new int[p.options.size()];
        for (DataSnapshot v : c.child("votes").getChildren()) {
            Long idx = v.getValue(Long.class);
            if (idx == null || idx < 0 || idx >= p.counts.length) continue;
            p.counts[idx.intValue()]++;
            if (v.getKey() != null && v.getKey().equals(myUid)) p.myVote = idx.intValue();
        }
        return p;
    }

    // ───────────────────────── presence (audience) ─────────────────────────
    /** Listener row likho + connection tootne par auto-remove. Speaker/host ho to skip. */
    static void joinPresence(String roomId) {
        loadMe(me -> {
            if (me.uid.isEmpty()) return;
            DatabaseReference r = room(roomId);
            r.child("speakers").child(me.uid).addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot s) {
                    if (s.exists()) return;   // already host/speaker
                    DatabaseReference row = r.child("audience").child(me.uid);
                    Map<String, Object> v = new HashMap<>();
                    v.put("name", me.name);
                    v.put("photoUrl", me.photoUrl == null ? "" : me.photoUrl);
                    v.put("verified", me.verified);
                    v.put("ts", ServerValue.TIMESTAMP);
                    row.setValue(v);
                    row.onDisconnect().removeValue();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
        });
    }

    static void leavePresence(String roomId) {
        String uid = myUid();
        if (uid == null) return;
        DatabaseReference r = room(roomId);
        r.child("audience").child(uid).removeValue();
        r.child("handRaises").child(uid).removeValue();
    }

    // ───────────────────────── chat / likes / hands / mute ─────────────────────────
    interface Result { void onResult(boolean ok, String error); }

    static void sendChat(String roomId, String text, Result cb) {
        loadMe(me -> {
            DatabaseReference ref = room(roomId).child("chat").push();
            Map<String, Object> v = new HashMap<>();
            v.put("uid", me.uid);
            v.put("name", me.name);
            v.put("photoUrl", me.photoUrl == null ? "" : me.photoUrl);
            v.put("text", text);
            v.put("ts", ServerValue.TIMESTAMP);
            ref.setValue(v).addOnCompleteListener(t -> {
                if (cb != null) cb.onResult(t.isSuccessful(),
                        t.getException() == null ? "" : t.getException().getMessage());
            });
        });
    }

    static void setLike(String roomId, String msgId, boolean liked) {
        String uid = myUid();
        if (uid == null || msgId == null) return;
        DatabaseReference r = room(roomId).child("chat").child(msgId).child("likes").child(uid);
        if (liked) r.setValue(true); else r.removeValue();
    }

    static void deleteChat(String roomId, String msgId) {
        if (msgId != null) room(roomId).child("chat").child(msgId).removeValue();
    }

    static void setHand(String roomId, boolean raised, Result cb) {
        loadMe(me -> {
            DatabaseReference r = room(roomId).child("handRaises").child(me.uid);
            if (!raised) {
                r.removeValue().addOnCompleteListener(t -> { if (cb != null) cb.onResult(t.isSuccessful(), ""); });
                return;
            }
            Map<String, Object> v = new HashMap<>();
            v.put("name", me.name);
            v.put("photoUrl", me.photoUrl == null ? "" : me.photoUrl);
            v.put("ts", ServerValue.TIMESTAMP);
            r.setValue(v).addOnCompleteListener(t -> {
                if (cb != null) cb.onResult(t.isSuccessful(),
                        t.getException() == null ? "" : t.getException().getMessage());
            });
            r.onDisconnect().removeValue();
        });
    }

    static void declineHand(String roomId, String uid) {
        room(roomId).child("handRaises").child(uid).removeValue();
    }

    static void setSelfMuted(String roomId, boolean muted) {
        String uid = myUid();
        if (uid != null) room(roomId).child("speakers").child(uid).child("selfMuted").setValue(muted);
    }

    // ───────────────────────── host edits ─────────────────────────
    static void setDescription(String roomId, String text) {
        room(roomId).child("meta").child("description").setValue(text);
    }

    static void heartbeat(String roomId) {
        room(roomId).child("meta").child("lastActive").setValue(ServerValue.TIMESTAMP);
    }

    static void pin(String roomId, String by, String text) {
        Map<String, Object> v = new HashMap<>();
        v.put("by", by);
        v.put("text", text);
        v.put("ts", ServerValue.TIMESTAMP);
        room(roomId).child("pinned").setValue(v);
    }

    static void unpin(String roomId) { room(roomId).child("pinned").removeValue(); }

    static void createPoll(String roomId, String q, List<String> options, long durationMs, Result cb) {
        String uid = myUid();
        if (uid == null) return;
        DatabaseReference ref = room(roomId).child("polls").push();
        Map<String, Object> v = new HashMap<>();
        v.put("q", q);
        v.put("options", options);
        v.put("createdBy", uid);
        v.put("createdAt", ServerValue.TIMESTAMP);
        v.put("endsAt", System.currentTimeMillis() + durationMs);
        ref.setValue(v).addOnCompleteListener(t -> {
            if (cb != null) cb.onResult(t.isSuccessful(),
                    t.getException() == null ? "" : t.getException().getMessage());
        });
    }

    static void vote(String roomId, String pollId, int option, Result cb) {
        String uid = myUid();
        if (uid == null) return;
        room(roomId).child("polls").child(pollId).child("votes").child(uid).setValue(option)
                .addOnCompleteListener(t -> {
                    if (cb != null) cb.onResult(t.isSuccessful(),
                            t.getException() == null ? "" : t.getException().getMessage());
                });
    }

    // ───────────────────────── wallet ─────────────────────────
    interface CoinsCb { void onCoins(long coins); }

    static void loadCoins(CoinsCb cb) {
        String uid = myUid();
        if (uid == null) { cb.onCoins(0); return; }
        db().getReference("wallets").child(uid).child("coins")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot s) {
                        Long v = s.getValue(Long.class);
                        cb.onCoins(v == null ? 0 : v);
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) { cb.onCoins(0); }
                });
    }

    // ───────────────────────── tiny parse helpers ─────────────────────────
    static String str(DataSnapshot s, String k, String def) {
        Object o = s.child(k).getValue();
        return o == null ? def : String.valueOf(o);
    }
    static boolean bool(DataSnapshot s, String k) {
        Boolean b = s.child(k).getValue(Boolean.class);
        return b != null && b;
    }
    static long lng(DataSnapshot s, String k, long def) {
        Object o = s.child(k).getValue();
        return (o instanceof Number) ? ((Number) o).longValue() : def;
    }
}
