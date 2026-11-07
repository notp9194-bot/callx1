package com.callx.app.feed;

import com.callx.app.models.StatusItem;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * v731: in-process snapshot of the Status tab (what the carousel shows) that outlives the StatusFragment instance.
 *
 * ViewPager2's FragmentStateAdapter destroys the Status fragment when the user is 2+ tabs away and builds a NEW instance on return;
 * every field of the fragment (statusMap / myStatuses / seenMap / adapter / carousel scroll) starts empty again, which showed as skeleton
 * cards + a carousel that "builds again" from the left. The fragment saves here in onStop and restores in onCreateView, so the first
 * frame already has the last known cards, the right seen/unseen split and the old scroll position (WhatsApp behaviour).
 * Expired items are dropped by the normal rebuild (isLive filter); a different uid (account switch) discards everything.
 */
final class StatusTabState {
    private StatusTabState() {}

    private static String uid;
    private static final Map<String, List<StatusItem>> STATUS = new LinkedHashMap<>();
    private static final List<StatusItem> MINE = new ArrayList<>();
    private static final Map<String, Set<String>> SEEN = new LinkedHashMap<>();
    private static final Set<String> BLOCKED = new HashSet<>();
    private static int scrollPos = -1, scrollOff;
    private static boolean has;

    /** Main thread only. {@code scroll} may be null. */
    static synchronized void save(String myUid, Map<String, List<StatusItem>> status, List<StatusItem> mine,
                                  Map<String, Set<String>> seen, Set<String> blocked, int[] scroll) {
        if (myUid == null || myUid.isEmpty()) return;
        if (status.isEmpty() && mine.isEmpty()) return;   // never overwrite a good snapshot with a not-yet-loaded (empty) one
        uid = myUid;
        STATUS.clear();
        for (Map.Entry<String, List<StatusItem>> e : status.entrySet()) STATUS.put(e.getKey(), new ArrayList<>(e.getValue()));
        MINE.clear(); MINE.addAll(mine);
        SEEN.clear();
        for (Map.Entry<String, Set<String>> e : seen.entrySet()) SEEN.put(e.getKey(), new HashSet<>(e.getValue()));
        BLOCKED.clear(); BLOCKED.addAll(blocked);
        if (scroll != null) { scrollPos = scroll[0]; scrollOff = scroll[1]; }
        has = true;
    }

    /** Fills the (empty) fragment collections. Returns true when something was restored. */
    static synchronized boolean restore(String myUid, Map<String, List<StatusItem>> status, List<StatusItem> mine,
                                        Map<String, Set<String>> seen, Set<String> blocked) {
        if (!has) return false;
        if (myUid == null || !myUid.equals(uid)) { clear(); return false; }
        for (Map.Entry<String, List<StatusItem>> e : STATUS.entrySet()) status.put(e.getKey(), new ArrayList<>(e.getValue()));
        mine.addAll(MINE);
        for (Map.Entry<String, Set<String>> e : SEEN.entrySet()) seen.put(e.getKey(), new HashSet<>(e.getValue()));
        blocked.addAll(BLOCKED);
        return true;
    }

    /** {pos, offset} of the first visible carousel card, or null. */
    static synchronized int[] scroll() { return has && scrollPos >= 0 ? new int[]{scrollPos, scrollOff} : null; }

    static synchronized void clear() {
        uid = null; STATUS.clear(); MINE.clear(); SEEN.clear(); BLOCKED.clear(); scrollPos = -1; scrollOff = 0; has = false;
    }
}
