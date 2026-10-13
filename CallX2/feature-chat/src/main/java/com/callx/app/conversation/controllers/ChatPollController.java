package com.callx.app.conversation.controllers;

import com.callx.app.chat.ui.CreatePollDialog;
import com.callx.app.db.entity.MessageEntity;
import com.callx.app.models.Message;
import com.callx.app.utils.PollJsonUtil;

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

    /**
     * The few things the poll lifecycle needs from its screen. {@link ChatActivityDelegate}
     * extends this (1:1 chat), and GroupChatActivity supplies its own small implementation, so
     * 1:1 and group share this one controller instead of keeping two copies of the vote/close code.
     */
    public interface Host {
        String getCurrentUid();
        com.callx.app.db.AppDatabase getDb();
        java.util.concurrent.Executor getIoExecutor();
        com.google.firebase.database.DatabaseReference getMessagesRef();
        boolean isOnline();
        boolean isBlocked();
        void showToast(String msg);
        Message buildOutgoing();
        void pushMessage(Message m, String previewText);
        com.callx.app.conversation.MessagePagingAdapter getPagingAdapter();
        android.app.Activity getActivity();
        /** Called after a poll has been handed to pushMessage (e.g. group clears its reply bar). */
        default void onPollSent() {}
        /** Display name for a voter uid (group member names); null = unknown. */
        default String voterName(String uid) { return null; }
        /** Custom row for a voter (group: avatar row); null = plain text row. */
        default android.view.View voterRow(String uid, String name) { return null; }
    }

    private final Host delegate;

    public ChatPollController(Host delegate) {
        this.delegate = delegate;
    }

    private String str(int resId) { return delegate.getActivity().getString(resId); }

    /** Light tick on the whole screen; no-op if haptics are off/unavailable. */
    private void haptic(boolean confirm) {
        try {
            android.view.View v = delegate.getActivity().getWindow().getDecorView();
            int c = (confirm && android.os.Build.VERSION.SDK_INT >= 30)
                    ? android.view.HapticFeedbackConstants.CONFIRM
                    : android.view.HapticFeedbackConstants.KEYBOARD_TAP;
            v.performHapticFeedback(c);
        } catch (Exception ignored) {}
    }

    // ── Create ────────────────────────────────────────────────────────────

    /** Opens the poll-creation bottom sheet (see ChatActivityDelegate
     *  #launchPollCreator — wired to the attach-sheet "Poll" option). */
    public void showCreatePollDialog() {
        if (delegate.isBlocked()) {
            delegate.showToast(str(com.callx.app.chat.R.string.poll_blocked));
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
        haptic(true);
        delegate.onPollSent();
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
            delegate.showToast(str(com.callx.app.chat.R.string.poll_closed_toast));
            return;
        }
        final String uid = delegate.getCurrentUid();
        if (uid == null) return;
        final PollVoteHelper.Result r = PollVoteHelper.apply(m, uid, id, optionIndex);
        if (r == null) return; // double-tap / redundant single-choice re-tap
        if (!delegate.isOnline()) { // shown once per real vote (not per double-tap); UI already updated optimistically
            delegate.showToast(str(com.callx.app.chat.R.string.poll_offline_vote));
        }
        haptic(false);

        final Map<String, List<Integer>> prev = m.pollVotes != null ? new HashMap<>(m.pollVotes) : new HashMap<>();
        applyVotes(m, id, r.votes);
        PollVoteHelper.write(delegate.getMessagesRef(), id, uid, r.mine, () -> {
            // Firebase rejected the write: put the UI + Room cache back.
            applyVotes(m, id, prev);
            delegate.showToast(str(com.callx.app.chat.R.string.poll_vote_failed));
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

    /** Poll creator closes or reopens voting. Closing asks first (reopening is harmless, so it doesn't). */
    public void toggleClosed(Message m) {
        if (m == null) return;
        String id = m.messageId != null ? m.messageId : m.id;
        if (id == null || id.isEmpty()) return;
        final boolean newClosed = !Boolean.TRUE.equals(m.pollClosed);
        if (!newClosed) { applyClosed(id, false); return; }
        android.app.Activity a = delegate.getActivity();
        com.callx.app.utils.AlertDialogStyler.showRounded(
                new androidx.appcompat.app.AlertDialog.Builder(a)
                        .setTitle(com.callx.app.chat.R.string.poll_close_confirm_title)
                        .setMessage(com.callx.app.chat.R.string.poll_close_confirm_msg)
                        .setNegativeButton(com.callx.app.chat.R.string.poll_cancel, null)
                        .setPositiveButton(com.callx.app.chat.R.string.poll_close_confirm_yes,
                                (d, w) -> applyClosed(id, true))
                        .create());
    }

    private void applyClosed(String id, boolean newClosed) {
        delegate.getMessagesRef().child(id).child("pollClosed").setValue(newClosed);
        delegate.getIoExecutor().execute(() -> delegate.getDb().messageDao().updatePollClosed(id, newClosed));
        haptic(false);
        delegate.showToast(str(newClosed ? com.callx.app.chat.R.string.poll_closed_done : com.callx.app.chat.R.string.poll_reopened));
    }

    // ── Who voted for what ────────────────────────────────────────────────

    /** Non-anonymous polls only (1:1 footer tap, group avatar-strip tap). */
    public void showVoters(Message m) {
        if (m == null || m.pollVotes == null || m.pollVotes.isEmpty()
                || m.pollOptions == null || m.pollOptions.isEmpty()
                || Boolean.TRUE.equals(m.pollAnonymous)) return;
        final android.app.Activity a = delegate.getActivity();
        final String me = delegate.getCurrentUid();
        com.callx.app.chat.ui.PollVotersDialog.show(a, m, uid -> {
            if (uid.equals(me)) return a.getString(com.callx.app.chat.R.string.poll_you);
            String n = delegate.voterName(uid);
            if (n == null && delegate instanceof ChatActivityDelegate) n = ((ChatActivityDelegate) delegate).getPartnerName();
            return (n == null || n.isEmpty()) ? a.getString(com.callx.app.chat.R.string.poll_member) : n;
        }, delegate::voterRow);
    }
}
