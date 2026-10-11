package com.callx.app.utils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Username search for @mentions (status mention sticker picker + composer autocomplete).
 *  - empty prefix  → my contacts (suggestions)
 *  - non-empty     → prefix match on the usernames/{name} → uid index (key range query, max 10 reads)
 * Each hit is enriched with users/{uid} (name, photo, username). Results arrive on the main thread.
 */
public final class MentionUserSearch {
    private MentionUserSearch() {}

    public static final class Result {
        public final String uid, username, name, photo;
        Result(String uid, String username, String name, String photo) {
            this.uid = uid; this.username = username; this.name = name; this.photo = photo;
        }
    }

    public interface Callback { void onResults(@NonNull List<Result> results); }

    private static final int LIMIT = 10;

    public static void search(@Nullable String rawPrefix, @NonNull Callback cb) {
        final String prefix = rawPrefix == null ? "" : rawPrefix.trim().toLowerCase();
        final String me = FirebaseUtils.getCurrentUid();
        if (prefix.isEmpty()) {
            if (me == null) { cb.onResults(new ArrayList<>()); return; }
            FirebaseUtils.getContactsRef(me).limitToFirst(LIMIT * 2)
                    .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    List<String> uids = new ArrayList<>();
                    for (DataSnapshot c : snap.getChildren()) if (c.getKey() != null) uids.add(c.getKey());
                    enrich(uids, null, me, cb);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) { cb.onResults(new ArrayList<>()); }
            });
            return;
        }
        FirebaseUtils.db().getReference("usernames").orderByKey()
                .startAt(prefix).endAt(prefix + "").limitToFirst(LIMIT)
                .addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                List<String> uids = new ArrayList<>();
                List<String> names = new ArrayList<>();
                for (DataSnapshot c : snap.getChildren()) {
                    String uid = c.getValue(String.class);
                    if (uid != null && c.getKey() != null) { uids.add(uid); names.add(c.getKey()); }
                }
                enrich(uids, names, me, cb);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { cb.onResults(new ArrayList<>()); }
        });
    }

    /** Exact check used before posting: does usernames/{name} exist? */
    public static void exists(@NonNull String username, @NonNull java.util.function.Consumer<Boolean> cb) {
        FirebaseUtils.db().getReference("usernames").child(username.toLowerCase())
                .addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) { cb.accept(s.exists()); }
            @Override public void onCancelled(@NonNull DatabaseError e) { cb.accept(true); } // can't tell → don't warn
        });
    }

    private static void enrich(List<String> uids, @Nullable List<String> knownUsernames,
                               @Nullable String me, Callback cb) {
        if (uids.isEmpty()) { cb.onResults(new ArrayList<>()); return; }
        final Result[] out = new Result[uids.size()];
        final AtomicInteger left = new AtomicInteger(uids.size());
        for (int i = 0; i < uids.size(); i++) {
            final int idx = i;
            final String uid = uids.get(i);
            FirebaseUtils.getUserRef(uid).addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot u) {
                    String un = knownUsernames != null ? knownUsernames.get(idx)
                            : u.child("username").getValue(String.class);
                    String name = u.child("name").getValue(String.class);
                    String photo = u.child("profileImage").getValue(String.class);
                    if (photo == null || photo.isEmpty()) photo = u.child("photoUrl").getValue(String.class);
                    // contacts without a username can't be @mentioned; never suggest myself
                    if (un != null && !un.isEmpty() && !uid.equals(me))
                        out[idx] = new Result(uid, un, name == null || name.isEmpty() ? un : name, photo);
                    done();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) { done(); }
                private void done() {
                    if (left.decrementAndGet() != 0) return;
                    List<Result> list = new ArrayList<>();
                    for (Result r : out) if (r != null) list.add(r);
                    cb.onResults(list);
                }
            });
        }
    }
}
