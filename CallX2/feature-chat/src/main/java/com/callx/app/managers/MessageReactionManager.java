package com.callx.app.managers;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import androidx.annotation.NonNull;

/**
 * MessageReactionManager
 *
 * Manages emoji reactions on chat messages, synced to Firebase Realtime DB.
 *
 * Firebase data structure:
 *   chats/{chatId}/reactions/{messageId}/{emoji}/{uid} = true
 *
 * Design:
 *   ✅ Each user can react with ONE emoji per message (toggle: same emoji = remove)
 *   ✅ Multiple DIFFERENT users can use the same emoji → count aggregated
 *   ✅ User switches emoji: old reaction removed, new one added (atomic)
 *   ✅ Real-time listener → UI updates instantly for both sender + receiver
 *   ✅ Reactions shown as bubble row below message: 👍 2  ❤️ 1  😂 1
 *   ✅ Your own reaction highlighted (brand_primary background)
 *
 * Reaction summary format (for adapter):
 *   List<ReactionSummary> — each entry: emoji, count, didCurrentUserReact
 *
 * Usage:
 *   MessageReactionManager mgr = new MessageReactionManager(chatId);
 *   mgr.toggleReaction(messageId, "👍");
 *   mgr.listenReactions(messageId, summaries -> updateReactionBubbles(summaries));
 *   mgr.stopListening(messageId);
 */
public class MessageReactionManager {

    // ── Quick-pick emoji set (shown in picker row) ─────────────────────────
    public static final String[] QUICK_EMOJIS = {
            "👍", "❤️", "😂", "😮", "😢", "🙏", "🔥", "👏"
    };

    // ── Firebase paths ─────────────────────────────────────────────────────
    private static final String PATH_CHATS     = "chats";
    private static final String PATH_REACTIONS = "reactions";

    // ── State ──────────────────────────────────────────────────────────────
    private final String            chatId;
    private final DatabaseReference reactionsRef;
    private final String            currentUid;

    // Active listeners keyed by messageId
    private final Map<String, ValueEventListener> listeners = new HashMap<>();

    // ─────────────────────────────────────────────────────────────────────
    // DATA MODEL
    // ─────────────────────────────────────────────────────────────────────

    public static class ReactionSummary {
        public final String  emoji;
        public final int     count;
        public final boolean currentUserReacted;
        public final List<String> reactorUids;  // for tooltip "Alice, Bob liked"

        public ReactionSummary(String emoji, int count,
                               boolean currentUserReacted, List<String> reactorUids) {
            this.emoji             = emoji;
            this.count             = count;
            this.currentUserReacted = currentUserReacted;
            this.reactorUids       = reactorUids;
        }
    }

    public interface OnReactionsUpdated {
        void onUpdated(String messageId, List<ReactionSummary> summaries);
    }

    // ─────────────────────────────────────────────────────────────────────
    // CONSTRUCTOR
    // ─────────────────────────────────────────────────────────────────────

    public MessageReactionManager(String chatId) {
        this.chatId       = chatId;
        this.currentUid   = FirebaseAuth.getInstance().getCurrentUser() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";
        this.reactionsRef = FirebaseDatabase.getInstance()
                .getReference(PATH_CHATS).child(chatId).child(PATH_REACTIONS);
    }

    // ─────────────────────────────────────────────────────────────────────
    // TOGGLE REACTION
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Toggle a reaction on a message.
     *
     * Rules:
     *   - If user hasn't reacted: add this emoji
     *   - If user reacted with SAME emoji: remove it (toggle off)
     *   - If user reacted with DIFFERENT emoji: remove old, add new (switch)
     */
    public void toggleReaction(String messageId, String emoji) {
        if (currentUid.isEmpty()) return;
        DatabaseReference msgReactRef = reactionsRef.child(messageId);

        // Read current state first to handle the switch case
        msgReactRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Map<String, Object> updates = new LinkedHashMap<>();

                // Find if user has an existing reaction (any emoji)
                String existingEmoji = null;
                for (DataSnapshot emojiSnap : snapshot.getChildren()) {
                    String e = emojiSnap.getKey();
                    if (emojiSnap.child(currentUid).exists()) {
                        existingEmoji = e;
                        break;
                    }
                }

                if (emoji.equals(existingEmoji)) {
                    // Same emoji → remove (toggle off)
                    updates.put(emoji + "/" + currentUid, null);
                } else {
                    // Different or no emoji → add new, remove old if any
                    if (existingEmoji != null) {
                        updates.put(existingEmoji + "/" + currentUid, null);
                    }
                    updates.put(emoji + "/" + currentUid, true);
                }

                msgReactRef.updateChildren(updates);
            }
            @Override public void onCancelled(@NonNull DatabaseError error) {}
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // LISTEN FOR REACTIONS
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Start listening for reaction changes on a specific message.
     * Callback fires immediately with current state, then on every change.
     *
     * @param messageId  The message to watch
     * @param callback   Called on UI thread with reaction summaries
     */
    public void listenReactions(String messageId, OnReactionsUpdated callback) {
        // Remove previous listener for this message if any
        stopListening(messageId);

        ValueEventListener listener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                List<ReactionSummary> summaries = new ArrayList<>();

                for (DataSnapshot emojiSnap : snapshot.getChildren()) {
                    String emoji = emojiSnap.getKey();
                    if (emoji == null) continue;

                    List<String> uids = new ArrayList<>();
                    boolean myReaction = false;

                    for (DataSnapshot uidSnap : emojiSnap.getChildren()) {
                        String uid = uidSnap.getKey();
                        if (uid != null) {
                            uids.add(uid);
                            if (uid.equals(currentUid)) myReaction = true;
                        }
                    }

                    if (!uids.isEmpty()) {
                        summaries.add(new ReactionSummary(emoji, uids.size(), myReaction, uids));
                    }
                }

                // Sort: most reacted first, then emoji order
                summaries.sort((a, b) -> Integer.compare(b.count, a.count));

                if (callback != null) callback.onUpdated(messageId, summaries);
            }
            @Override public void onCancelled(@NonNull DatabaseError error) {}
        };

        listeners.put(messageId, listener);
        reactionsRef.child(messageId).addValueEventListener(listener);
    }

    // ─────────────────────────────────────────────────────────────────────
    // STOP LISTENING
    // ─────────────────────────────────────────────────────────────────────

    public void stopListening(String messageId) {
        ValueEventListener l = listeners.remove(messageId);
        if (l != null) reactionsRef.child(messageId).removeEventListener(l);
    }

    public void stopAllListeners() {
        for (Map.Entry<String, ValueEventListener> e : listeners.entrySet()) {
            reactionsRef.child(e.getKey()).removeEventListener(e.getValue());
        }
        listeners.clear();
    }

    // ─────────────────────────────────────────────────────────────────────
    // FETCH ONCE (for notification or summary without ongoing listener)
    // ─────────────────────────────────────────────────────────────────────

    public void fetchReactionsOnce(String messageId, OnReactionsUpdated callback) {
        reactionsRef.child(messageId).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                List<ReactionSummary> summaries = new ArrayList<>();
                for (DataSnapshot emojiSnap : snapshot.getChildren()) {
                    String emoji = emojiSnap.getKey();
                    if (emoji == null) continue;
                    List<String> uids = new ArrayList<>();
                    boolean myReaction = false;
                    for (DataSnapshot uidSnap : emojiSnap.getChildren()) {
                        String uid = uidSnap.getKey();
                        if (uid != null) { uids.add(uid); if (uid.equals(currentUid)) myReaction = true; }
                    }
                    if (!uids.isEmpty()) summaries.add(new ReactionSummary(emoji, uids.size(), myReaction, uids));
                }
                summaries.sort((a, b) -> Integer.compare(b.count, a.count));
                if (callback != null) callback.onUpdated(messageId, summaries);
            }
            @Override public void onCancelled(@NonNull DatabaseError error) {}
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────────

    /** Format reaction summaries into compact display string e.g. "👍 2  ❤️ 1" */
    public static String formatSummary(List<ReactionSummary> summaries) {
        if (summaries == null || summaries.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (ReactionSummary s : summaries) {
            if (sb.length() > 0) sb.append("  ");
            sb.append(s.emoji);
            if (s.count > 1) sb.append(" ").append(s.count);
        }
        return sb.toString();
    }

    /** Total reaction count across all emojis */
    public static int totalCount(List<ReactionSummary> summaries) {
        if (summaries == null) return 0;
        int total = 0;
        for (ReactionSummary s : summaries) total += s.count;
        return total;
    }
}
