package com.callx.app.conversation.controllers;

import android.os.SystemClock;

import com.callx.app.models.Message;
import com.google.firebase.database.DatabaseReference;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared poll-vote logic for 1:1 (ChatPollController) and group (GroupChatActivity) so the two
 * can't drift apart again: next-selection maths, tap debounce, and the Firebase write with a
 * failure callback (used to roll the optimistic UI back).
 */
public final class PollVoteHelper {

    private PollVoteHelper() {}

    /** Same message+option tapped again inside this window is treated as a double-tap and ignored. */
    private static final long DEBOUNCE_MS = 300L;
    private static final int MAX_TRACKED = 32;

    private static final Map<String, Long> LAST_TAP = new java.util.LinkedHashMap<String, Long>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > MAX_TRACKED;
        }
    };

    public static final class Result {
        /** Full votes map after this tap (a fresh copy — the message's own map is not touched). */
        public final Map<String, List<Integer>> votes;
        /** This user's selection after the tap; empty = vote removed. */
        public final List<Integer> mine;
        Result(Map<String, List<Integer>> votes, List<Integer> mine) { this.votes = votes; this.mine = mine; }
    }

    /**
     * Computes the result of {@code uid} tapping {@code optionIndex}, or null when nothing should
     * happen (double-tap debounce, or single-choice re-tap of the option that is already the only vote).
     * Main thread only.
     */
    public static Result apply(Message m, String uid, String messageId, int optionIndex) {
        if (m == null || uid == null || messageId == null) return null;
        long now = SystemClock.uptimeMillis();
        String key = messageId + '#' + optionIndex;
        Long last = LAST_TAP.get(key);
        if (last != null && now - last < DEBOUNCE_MS) return null;
        LAST_TAP.put(key, now);

        boolean multi = Boolean.TRUE.equals(m.pollMultiChoice);
        Map<String, List<Integer>> votes = m.pollVotes != null ? new HashMap<>(m.pollVotes) : new HashMap<>();
        List<Integer> current = votes.get(uid);
        List<Integer> mine = new ArrayList<>(current != null ? current : Collections.<Integer>emptyList());

        if (multi) {
            if (!mine.remove(Integer.valueOf(optionIndex))) mine.add(optionIndex);
        } else {
            if (mine.size() == 1 && mine.get(0) == optionIndex) return null; // already the only vote: no write
            mine.clear();
            mine.add(optionIndex);
        }
        if (mine.isEmpty()) votes.remove(uid); else votes.put(uid, mine);
        return new Result(votes, mine);
    }

    /** Writes (or removes) this user's vote; {@code onFailure} runs if Firebase rejects it. */
    public static void write(DatabaseReference messagesRef, String messageId, String uid,
                             List<Integer> mine, Runnable onFailure) {
        if (messagesRef == null) return;
        DatabaseReference ref = messagesRef.child(messageId).child("pollVotes").child(uid);
        if (onFailure == null) {
            if (mine.isEmpty()) ref.removeValue(); else ref.setValue(mine);
            return;
        }
        (mine.isEmpty() ? ref.removeValue() : ref.setValue(mine)).addOnFailureListener(e -> onFailure.run());
    }
}
