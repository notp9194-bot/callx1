package com.callx.app.cache;

import androidx.annotation.NonNull;

import com.callx.app.models.User;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.List;

/**
 * ReelShareContactsCache — app-wide singleton, short-TTL cache for
 * ReelShareSheetFragment's ("Share Reel" bottom sheet) "Send to" contacts grid.
 *
 * WHY THIS EXISTS (PERF):
 * Before this cache, every single time a user opened the reel share sheet —
 * even opening→closing→reopening it a few seconds apart on the SAME reel —
 * ReelShareSheetFragment#loadContacts() did, from scratch:
 *   1. A full Firebase read of contacts/{myUid} (a full-node download)
 *   2. Deserializing every child snapshot into a new User object
 *   3. Rebuilding the adapter's backing list + online-status snapshot
 * That list is IDENTICAL across opens within the same session (it only
 * changes when a contact is added/removed) — same redundant-refetch problem
 * MutualFollowersCache already solved for the reel bio's mutual-followers row,
 * solved here the same way.
 *
 * A cache hit costs ZERO Firebase reads and ZERO User re-deserialization —
 * the sheet's contact grid can paint on the very next frame instead of
 * waiting on a network round trip. A cold/expired entry still costs the same
 * single read as before; nothing gets slower, only repeat-opens get faster.
 *
 * NOTE: only the raw contacts list is cached here — each contact's online/
 * offline status is time-sensitive and is (correctly) recomputed fresh on
 * every open by ReelContactShareAdapter#refreshOnlineSnapshot(), against
 * whatever `lastSeen` this cached list currently holds.
 */
public final class ReelShareContactsCache {

    private static ReelShareContactsCache sInstance;

    // Contacts list changes rarely (add/remove a contact) — a couple of
    // minutes of staleness is invisible for a "send to" picker and saves a
    // full-node re-fetch + re-parse on almost every share-sheet open.
    private static final long TTL_MS = 2 * 60_000L;

    private static final class Entry {
        final List<User> contacts;
        final long fetchedAtMs;
        Entry(List<User> contacts) { this.contacts = contacts; this.fetchedAtMs = System.currentTimeMillis(); }
        boolean isFresh() { return System.currentTimeMillis() - fetchedAtMs < TTL_MS; }
    }

    private Entry cached;
    private String cachedForUid;
    // Collapses concurrent identical in-flight requests (sheet opened twice
    // in quick succession before the first fetch even returns) into a
    // single Firebase round trip.
    private List<Callback> inFlightWaiters;

    public interface Callback {
        void onReady(@NonNull List<User> contacts);
    }

    private ReelShareContactsCache() {}

    public static synchronized ReelShareContactsCache getInstance() {
        if (sInstance == null) sInstance = new ReelShareContactsCache();
        return sInstance;
    }

    /** Call after a contact is added/removed so the next open reflects it instead of serving a stale list for up to TTL_MS. */
    public void invalidate() {
        cached = null;
        cachedForUid = null;
    }

    public void getContacts(@NonNull String myUid, @NonNull Callback callback) {
        if (cached != null && myUid.equals(cachedForUid) && cached.isFresh()) {
            callback.onReady(cached.contacts);
            return;
        }

        if (inFlightWaiters != null) {
            inFlightWaiters.add(callback);
            return;
        }
        inFlightWaiters = new ArrayList<>();
        inFlightWaiters.add(callback);

        FirebaseUtils.getContactsRef(myUid).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                List<User> contacts = new ArrayList<>();
                for (DataSnapshot child : snap.getChildren()) {
                    User u = child.getValue(User.class);
                    if (u != null) {
                        if (u.uid == null) u.uid = child.getKey();
                        contacts.add(u);
                    }
                }
                cached = new Entry(contacts);
                cachedForUid = myUid;
                deliver(contacts);
            }

            @Override public void onCancelled(@NonNull DatabaseError error) {
                // FIX (no silent-hang): a cancelled read used to leave every
                // waiter (including this one) hanging forever with no
                // callback and the sheet's progress spinner stuck on-screen.
                // Deliver an empty list instead — same "just show nothing"
                // outcome the old inline onCancelled path already had.
                deliver(new ArrayList<>());
            }

            private void deliver(List<User> contacts) {
                List<Callback> waiters = inFlightWaiters;
                inFlightWaiters = null;
                if (waiters != null) {
                    for (Callback cb : waiters) cb.onReady(contacts);
                }
            }
        });
    }
}
