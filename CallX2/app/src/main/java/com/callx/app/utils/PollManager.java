package com.callx.app.utils;

import com.callx.app.models.Message;
import com.google.firebase.database.DatabaseReference;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Feature 6 (NEW): Polls in Chat
 * Handles creating, voting, and computing poll results.
 *
 * Firebase schema (stored inline in the message):
 *   messages/{chatId}/{msgId}/pollQuestion = "What's for lunch?"
 *   messages/{chatId}/{msgId}/pollOptions  = ["Pizza","Pasta","Salad"]
 *   messages/{chatId}/{msgId}/pollVotes/{uid} = 1   (option index)
 *   messages/{chatId}/{msgId}/pollMultiChoice = false
 *   messages/{chatId}/{msgId}/pollExpiresAt   = 1712345678000
 */
public class PollManager {

    /** Build a poll Message ready to push to Firebase. */
    public static Message buildPoll(String senderId, String senderName,
                                    String question, List<String> options,
                                    boolean multiChoice, long durationMs) {
        Message m = new Message();
        m.senderId       = senderId;
        m.senderName     = senderName;
        m.type           = "poll";
        m.pollQuestion   = question;
        m.pollOptions    = options;
        m.pollMultiChoice = multiChoice;
        m.pollVotes      = new HashMap<>();
        m.timestamp      = System.currentTimeMillis();
        m.status         = "sent";
        if (durationMs > 0) m.pollExpiresAt = m.timestamp + durationMs;
        return m;
    }

    /** Cast or change a vote. uid → option index. */
    public static void vote(DatabaseReference msgRef, String uid, int optionIndex) {
        msgRef.child("pollVotes").child(uid).setValue(optionIndex);
    }

    /** Remove a vote (for multi-choice toggle). */
    public static void removeVote(DatabaseReference msgRef, String uid) {
        msgRef.child("pollVotes").child(uid).removeValue();
    }

    /** Compute vote counts per option index. */
    public static int[] computeCounts(Message msg) {
        if (msg.pollOptions == null) return new int[0];
        int[] counts = new int[msg.pollOptions.size()];
        if (msg.pollVotes == null) return counts;
        for (Integer idx : msg.pollVotes.values()) {
            if (idx != null && idx >= 0 && idx < counts.length) counts[idx]++;
        }
        return counts;
    }

    public static int totalVotes(Message msg) {
        return msg.pollVotes != null ? msg.pollVotes.size() : 0;
    }

    public static boolean hasExpired(Message msg) {
        return msg.pollExpiresAt != null
                && msg.pollExpiresAt > 0
                && System.currentTimeMillis() > msg.pollExpiresAt;
    }

    /** Percentage string for option, e.g. "67%" */
    public static String percentLabel(int optionVotes, int total) {
        if (total == 0) return "0%";
        return Math.round(100f * optionVotes / total) + "%";
    }

    /** Winner option index; -1 if tie or no votes. */
    public static int winnerIndex(int[] counts) {
        int max = 0, winner = -1;
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] > max) { max = counts[i]; winner = i; }
        }
        return winner;
    }
}
