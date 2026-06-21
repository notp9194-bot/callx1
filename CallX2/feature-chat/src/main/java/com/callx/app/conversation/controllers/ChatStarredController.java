package com.callx.app.conversation.controllers;

import android.app.Activity;
import android.content.Intent;

import com.callx.app.models.Message;
import com.callx.app.starred.StarredMessagesActivity;

/**
 * Handles starring/unstarring individual messages and opening the
 * dedicated starred-messages manage-list for this chat.
 *
 * Message#starred / MessageEntity#starred already existed and were kept in
 * sync correctly (unlike reactions, this round-trip was never broken) — this
 * controller just pulls the previously-inline ChatActivity#toggleStar and
 * the menu's StarredMessagesActivity launch out into their own class, same
 * as ChatPinController / ChatReactionController / ChatPollController.
 *
 * The manage-list screen itself (StarredMessagesActivity) is untouched —
 * it already does its own offline-first Room load + Firebase sync for the
 * given chatId and lets the user unstar from there too.
 */
public class ChatStarredController {

    private final ChatActivityDelegate delegate;

    public ChatStarredController(ChatActivityDelegate delegate) {
        this.delegate = delegate;
    }

    // ── Toggle ────────────────────────────────────────────────────────────

    /** Stars/unstars a message — writes through to both Firebase
     *  (messages/{id}/starred) and the Room cache so the change shows up
     *  immediately in this chat AND in the manage-list, online or off. */
    public void toggleStar(Message m) {
        if (m == null || m.id == null || m.id.isEmpty()) return;
        boolean nowStarred = !Boolean.TRUE.equals(m.starred);
        delegate.getIoExecutor().execute(() ->
                delegate.getDb().messageDao().updateStarred(m.id, nowStarred));
        delegate.getMessagesRef().child(m.id).child("starred").setValue(nowStarred);
    }

    // ── Manage-list ───────────────────────────────────────────────────────

    /** Opens the starred-messages manage-list, scoped to this chat
     *  (see action_starred in the chat options menu). */
    public void openManageList() {
        Activity activity = delegate.getActivity();
        String chatId = delegate.getChatId();
        if (activity == null || chatId == null || chatId.isEmpty()) return;

        Intent i = new Intent(activity, StarredMessagesActivity.class);
        i.putExtra("chatId", chatId);
        i.putExtra("isGroup", false);
        activity.startActivity(i);
    }
}
