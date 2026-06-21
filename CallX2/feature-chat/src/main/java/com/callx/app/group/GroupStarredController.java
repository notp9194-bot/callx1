package com.callx.app.group;

import android.app.Activity;
import android.content.Intent;

import com.callx.app.db.AppDatabase;
import com.callx.app.models.Message;
import com.callx.app.starred.StarredMessagesActivity;
import com.google.firebase.database.DatabaseReference;

import java.util.concurrent.Executor;

/**
 * Group-chat counterpart to ChatStarredController — same toggle + manage-
 * list behaviour, just pointed at groupMessagesRef instead of messagesRef
 * and passing isGroup=true to StarredMessagesActivity.
 *
 * Kept as its own class with its own small Delegate (rather than reusing
 * ChatActivityDelegate) because GroupChatActivity doesn't implement that
 * interface — it has its own GroupWatchingController.Delegate. Mirrors that
 * controller's "small per-feature Delegate" shape rather than forcing one
 * giant shared interface across both chat types.
 */
public class GroupStarredController {

    public interface Delegate {
        Activity getActivity();
        String getGroupId();
        AppDatabase getDb();
        Executor getIoExecutor();
        DatabaseReference getGroupMessagesRef();
    }

    private final Delegate delegate;

    public GroupStarredController(Delegate delegate) {
        this.delegate = delegate;
    }

    // ── Toggle ────────────────────────────────────────────────────────────

    /** Stars/unstars a message — writes through to both Firebase
     *  (groupMessages/{id}/starred) and the Room cache, same as the 1:1
     *  chat version. */
    public void toggleStar(Message m) {
        if (m == null || m.id == null || m.id.isEmpty()) return;
        boolean nowStarred = !Boolean.TRUE.equals(m.starred);
        delegate.getIoExecutor().execute(() ->
                delegate.getDb().messageDao().updateStarred(m.id, nowStarred));
        delegate.getGroupMessagesRef().child(m.id).child("starred").setValue(nowStarred);
    }

    // ── Manage-list ───────────────────────────────────────────────────────

    /** Opens the starred-messages manage-list, scoped to this group
     *  (see menu_starred — both the toolbar overflow entry and the
     *  "More options" popup route here). */
    public void openManageList() {
        Activity activity = delegate.getActivity();
        String groupId = delegate.getGroupId();
        if (activity == null || groupId == null || groupId.isEmpty()) return;

        Intent i = new Intent(activity, StarredMessagesActivity.class);
        i.putExtra("chatId", groupId);
        i.putExtra("isGroup", true);
        activity.startActivity(i);
    }
}
