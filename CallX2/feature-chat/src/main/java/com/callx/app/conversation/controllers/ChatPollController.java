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
        final String uid = delegate.getCurrentUid();
        if (uid == null) return;
        final PollVoteHelper.Result r = PollVoteHelper.apply(m, uid, id, optionIndex);
        if (r == null) return; // double-tap / redundant single-choice re-tap
        if (!delegate.isOnline()) { // shown once per real vote (not per double-tap); UI already updated optimistically
            delegate.showToast("You're offline — vote will sync once you're back online");
        }

        final Map<String, List<Integer>> prev = m.pollVotes != null ? new HashMap<>(m.pollVotes) : new HashMap<>();
        applyVotes(m, id, r.votes);
        PollVoteHelper.write(delegate.getMessagesRef(), id, uid, r.mine, () -> {
            // Firebase rejected the write: put the UI + Room cache back.
            applyVotes(m, id, prev);
            delegate.showToast(delegate.getActivity().getString(com.callx.app.chat.R.string.poll_vote_failed));
        });
    }

    /** Optimistic local state: message object, bubble (instant PAYLOAD_POLL rebind) and Room cache. */
    private void applyVotes(Message m, String id, Map<String, List<Integer>> votes) {
        m.pollVotes = votes;
        com.callx.app.conversation.MessagePagingAdapter ad = delegate.getPagingAdapter();
        if (ad != null) ad.applyLocalPollVotes(id, votes);
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
