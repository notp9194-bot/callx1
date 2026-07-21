package com.callx.app.conversation.controllers;

import android.app.Activity;
import com.callx.app.chat.databinding.ActivityChatBinding;
import com.callx.app.conversation.MessagePagingAdapter;
import com.callx.app.db.AppDatabase;
import com.callx.app.models.Message;
import com.google.firebase.database.DatabaseReference;
import java.util.concurrent.Executor;

/**
 * Delegate interface exposed by ChatActivity to all controller classes.
 * Controllers call back into the Activity via this interface instead of
 * holding a direct hard reference to ChatActivity.
 *
 * Extends {@link ChatSearchController.SearchDelegate} so that any object
 * already implementing ChatActivityDelegate automatically satisfies the
 * minimal interface needed by ChatSearchController — no extra wiring needed.
 */
public interface ChatActivityDelegate extends ChatSearchController.SearchDelegate {

    // ── Binding ────────────────────────────────────────────────────────────
    ActivityChatBinding getBinding();

    // ── Identity ──────────────────────────────────────────────────────────
    String getChatId();
    String getPartnerUid();
    String getPartnerName();
    String getPartnerPhoto();
    String getPartnerThumb();
    String getCurrentUid();
    String getCurrentName();

    // ── Database & executor ───────────────────────────────────────────────
    AppDatabase getDb();
    Executor getIoExecutor();

    // ── Firebase ──────────────────────────────────────────────────────────
    DatabaseReference getMessagesRef();

    // ── Network ───────────────────────────────────────────────────────────
    boolean isOnline();

    // ── Mute state ────────────────────────────────────────────────────────
    boolean isMuted();
    void setMuted(boolean muted);

    // ── Block state ───────────────────────────────────────────────────────
    boolean isBlocked();
    void setBlocked(boolean blocked);
    boolean isPartnerPermaBlockedMe();
    void setPartnerPermaBlockedMe(boolean val);
    boolean isIPermaBlockedPartner();
    void setIPermaBlockedPartner(boolean val);

    // ── Recording state ───────────────────────────────────────────────────
    boolean isRecording();
    void setRecording(boolean recording);

    // ── UI helpers ────────────────────────────────────────────────────────
    void runOnMain(Runnable r);
    void showToast(String msg);
    void invalidateMenu();

    // ── Message operations ────────────────────────────────────────────────
    Message buildOutgoing();
    void pushMessage(Message m, String previewText);
    void firebasePushMessage(Message m, String key, String previewText);

    /**
     * WhatsApp-style local-first media send — see ChatMessageSender's
     * corresponding methods for the full 3-step flow explanation.
     */
    String insertLocalPendingMedia(Message m);
    void finalizeMediaMessage(Message m, String previewText);
    void markMediaFailed(String messageId);
    void clearReply();
    void startReply(Message m);
    void activateReplyDirect(Message m);
    void navigateToOriginal(String messageId);
    /** messageId currently shown in the reply bar (user is composing a
     *  reply to it), or null if no reply is active. Used to publish the
     *  per-message "someone is replying to this" highlight alongside the
     *  typing indicator — see ChatPresenceController#publishTypingReplyTarget. */
    String getCurrentReplyTargetId();

    // ── Adapter ───────────────────────────────────────────────────────────
    MessagePagingAdapter getPagingAdapter();

    // ── Fragment manager ──────────────────────────────────────────────────
    androidx.fragment.app.FragmentManager getSupportFragmentManager();

    // ── Activity context ──────────────────────────────────────────────────
    Activity getActivity();

    // ── Theme / wallpaper refresh ─────────────────────────────────────────
    void refreshScreenTheme();
    void refreshWallpaper();

    // ── Wallpaper picker launcher ─────────────────────────────────────────
    void launchWallpaperPicker();

    // ── Poll creation ─────────────────────────────────────────────────────
    void launchPollCreator();

    // ── Contact / Location share ──────────────────────────────────────────
    void launchContactSharePicker();
    void launchLocationSharePicker();

    /**
     * PERF FIX: queues a Room "mark read" write instead of writing it
     * immediately. Coalesced with other buffered Firebase events into a
     * single transaction — see ChatActivity#flushPendingRoomWrites() and
     * MessageDao#applyBufferedChanges(). Stops every historical unread
     * message from triggering its own PagingSource invalidation when a
     * chat is opened.
     */
    void queueMarkRead(String messageId);

    /**
     * Live-write race fix: call on the MAIN thread before any direct
     * messages-table write outside the buffered Firebase flush path (e.g.
     * ChatMessageSender's local-first insertMessage()/updateStatus()).
     * Returns true if the caller must call reanchorPagingToBottom() once
     * the write commits. See ChatActivity#severPagingIfAtBottom() for the
     * full explanation of why this two-step sever/reanchor is needed.
     */
    boolean severPagingIfAtBottom();

    /** Call from any thread after a write for which severPagingIfAtBottom() returned true. */
    void reanchorPagingToBottom();
}
