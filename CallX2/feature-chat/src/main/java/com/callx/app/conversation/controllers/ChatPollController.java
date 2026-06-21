package com.callx.app.conversation.controllers;

import com.callx.app.chat.ui.CreatePollDialog;
import com.callx.app.db.entity.MessageEntity;
import com.callx.app.models.Message;
import com.callx.app.utils.PollJsonUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles the full poll lifecycle in a 1:1 chat — creation, voting
 * (single- and multi-choice), and close/reopen by the poll's creator.
 *
 * Was previously four standalone private methods directly on ChatActivity
 * (showCreatePollDialog / sendPollMessage / castPollVote / togglePollClosed),
 * pulled out here to match the rest of the controller set (ChatPinController,
 * ChatReactionController, etc.) — same delegate pattern, no behaviour change.
 *
 * Data shape (see Message#pollQuestion/pollOptions/pollVotes/pollAnonymous/
 * pollClosed/pollMultiChoice and PollJsonUtil for the Room JSON encoding):
 *   messages/{id}/pollVotes/{uid} → [optionIndex, ...]   (Firebase)
 *   messages.pollVotesJson         → same map, JSON-encoded (Room cache)
 */
public class ChatPollController {

    private final ChatActivityDelegate delegate;

    public ChatPollController(ChatActivityDelegate delegate) {
        this.delegate = delegate;
    }

    // ── Create ────────────────────────────────────────────────────────────

    /** Opens the poll-creation bottom sheet (see ChatActivityDelegate
     *  #launchPollCreator — wired to the attach-sheet "Poll" option). */
    public void showCreatePollDialog() {
        if (delegate.isBlocked()) {
            delegate.showToast("Can't send messages to this contact");
            return;
        }
        CreatePollDialog.show(delegate.getActivity(),
                (question, options, anonymous, multiChoice) ->
                        sendPollMessage(question, options, anonymous, multiChoice));
    }

    /** Builds and sends a poll message via the standard outgoing pipeline. */
    private void sendPollMessage(String question, List<String> options,
                                  boolean anonymous, boolean multiChoice) {
        Message m = delegate.buildOutgoing();
        m.type = "poll";
        m.pollQuestion = question;
        m.pollOptions = options;
        m.pollVotes = new HashMap<>();
        m.pollAnonymous = anonymous;
        m.pollClosed = false;
        m.pollMultiChoice = multiChoice;
        // text mirrors the question so chat-list previews and search show something sensible
        m.text = "\uD83D\uDCCA " + question;
        delegate.pushMessage(m, "\uD83D\uDCCA Poll: " + question);
    }

    // ── Vote ──────────────────────────────────────────────────────────────

    /**
     * Casts/toggles the current user's vote(s) on a poll message.
     * Single-choice polls: tapping any option replaces the previous vote.
     * Multi-choice polls: tapping an option ticks/un-ticks just that option,
     * leaving the rest of the voter's selections untouched.
     * Writes directly to Firebase (messages/{id}/pollVotes/{uid}) — the
     * existing real-time listener + Room sync pipeline picks up the change
     * and refreshes the UI, same as reactions.
     */
    public void castVote(Message m, int optionIndex) {
        if (m == null) return;
        String id = m.messageId != null ? m.messageId : m.id;
        if (id == null || id.isEmpty()) return;
        if (Boolean.TRUE.equals(m.pollClosed)) {
            delegate.showToast("This poll is closed");
            return;
        }
        if (!delegate.isOnline()) {
            delegate.showToast("You're offline — vote will sync once you're back online");
        }

        String uid = delegate.getCurrentUid();
        boolean multiChoice = Boolean.TRUE.equals(m.pollMultiChoice);
        Map<String, List<Integer>> votes = m.pollVotes != null
                ? new HashMap<>(m.pollVotes) : new HashMap<>();
        List<Integer> mine = votes.get(uid);
        List<Integer> updatedMine = new ArrayList<>(mine != null ? mine : Collections.emptyList());

        if (multiChoice) {
            // Tick/un-tick just this option, keeping any other ticks intact.
            if (updatedMine.contains(optionIndex)) {
                updatedMine.remove(Integer.valueOf(optionIndex));
            } else {
                updatedMine.add(optionIndex);
            }
        } else {
            // Single-choice: this option becomes the only vote.
            updatedMine.clear();
            updatedMine.add(optionIndex);
        }

        if (updatedMine.isEmpty()) {
            votes.remove(uid);
            delegate.getMessagesRef().child(id).child("pollVotes").child(uid).removeValue();
        } else {
            votes.put(uid, updatedMine);
            delegate.getMessagesRef().child(id).child("pollVotes").child(uid).setValue(updatedMine);
        }

        // Optimistic local update so the bar/tick reflects immediately even before
        // the Firebase round-trip / Room sync completes.
        m.pollVotes = votes;
        delegate.getIoExecutor().execute(() -> {
            try {
                MessageEntity e = delegate.getDb().messageDao().getMessageById(id);
                if (e != null) {
                    e.pollVotesJson = PollJsonUtil.votesToJson(votes);
                    delegate.getDb().messageDao().updateMessage(e);
                }
            } catch (Exception ignored) {}
        });
    }

    // ── Close / reopen ────────────────────────────────────────────────────

    /** Poll creator closes or reopens voting on their own poll. */
    public void toggleClosed(Message m) {
        if (m == null) return;
        String id = m.messageId != null ? m.messageId : m.id;
        if (id == null || id.isEmpty()) return;
        boolean newClosed = !Boolean.TRUE.equals(m.pollClosed);
        delegate.getMessagesRef().child(id).child("pollClosed").setValue(newClosed);
        delegate.getIoExecutor().execute(() -> delegate.getDb().messageDao().updatePollClosed(id, newClosed));
        delegate.showToast(newClosed ? "Poll closed" : "Poll reopened");
    }
}
