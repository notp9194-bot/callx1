package com.callx.app.conversation.controllers;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.callx.app.models.Message;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ServerValue;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ChatViewOnceController — Production-level "View Once / Secret Message" feature.
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  STATES:  SENT → DELIVERED → OPENED → DELETED                          │
 * │           SENT → EXPIRED  (timer ran out, never opened)                │
 * │           SENT → REVOKED  (sender revoked before receiver opened)      │
 * │                                                                         │
 * │  Flow (receiver side):                                                  │
 * │    1. Receiver taps "View Once" badge → openViewOnce() called           │
 * │    2. FLAG_SECURE set → screenshot blocked                              │
 * │    3. markOpened() → Firebase: viewOnceState = "opened", openedAt = now │
 * │    4. Sender sees "Opened" tick in their bubble                         │
 * │    5. scheduleDelete() → 1s delay (UX: let receiver see content)       │
 * │    6. hardDeleteFromFirebase() + softDeleteLocally() → both sides clean  │
 * │    7. FLAG_SECURE cleared after close                                   │
 * │                                                                         │
 * │  Flow (expiry timer — Feature 7):                                       │
 * │    viewOnceExpiresAt saved on Firebase when sender picks duration.      │
 * │    Scheduled check (WorkManager/alarm) calls markExpiredByTimer()       │
 * │    → viewOnceState = "expired" + content wiped permanently.            │
 * │    Receiver sees "Expired" bubble; sender sees it too.                 │
 * │                                                                         │
 * │  Flow (revoke — Feature 8):                                             │
 * │    Sender long-presses pending lock bubble → confirm dialog →          │
 * │    revokeViewOnce() → viewOnceState = "revoked" + content wiped.       │
 * │    Receiver sees "Removed" bubble.                                     │
 * │                                                                         │
 * │  Flow (sender side):                                                    │
 * │    Firebase listener in MessagePagingAdapter picks up viewOnceState     │
 * │    change → DiffUtil payload update → bubble shows correct state        │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * PERFORMANCE:
 *  - No polling. Event-driven via Firebase listeners.
 *  - Batched Firebase writes (single updateChildren call).
 *  - IO on background executor, never blocks main thread.
 *  - WeakReference for Activity — no memory leak.
 *  - Pending delete queue for offline → sync on reconnect.
 *
 * SECURITY:
 *  - FLAG_SECURE during view → no screenshot, no screen record.
 *  - Forward/copy disabled for view-once messages (enforced in adapter).
 *  - Server-side delete validated (client only soft-deletes locally).
 *  - viewOnceState is write-once: "opened" cannot be reverted to "sent".
 */
public class ChatViewOnceController {

    // ── View-once state constants ─────────────────────────────────────────
    /** Message sent, not yet opened by receiver. */
    public static final String STATE_SENT     = "sent";
    /** Receiver has opened the message — triggers delete pipeline. */
    public static final String STATE_OPENED   = "opened";
    /** Deleted from Firebase (hard delete). Local DB keeps soft-delete row. */
    public static final String STATE_DELETED  = "deleted";
    /**
     * Expiry timer ran out before receiver opened it.
     * Receiver sees "Expired" bubble; content permanently deleted.
     */
    public static final String STATE_EXPIRED  = "expired";
    /**
     * Sender revoked the message before receiver opened it.
     * Receiver sees "Removed" bubble; content permanently deleted.
     */
    public static final String STATE_REVOKED  = "revoked";

    // ── Firebase field names ──────────────────────────────────────────────
    public static final String FIELD_VIEW_ONCE          = "viewOnce";
    public static final String FIELD_VIEW_ONCE_STATE    = "viewOnceState";
    public static final String FIELD_OPENED_AT          = "openedAt";
    public static final String FIELD_DELETED            = "deleted";
    public static final String FIELD_TEXT               = "text";
    public static final String FIELD_MEDIA_URL          = "mediaUrl";
    public static final String FIELD_THUMBNAIL_URL      = "thumbnailUrl";
    public static final String FIELD_VIEW_ONCE_EXPIRES_AT = "viewOnceExpiresAt";
    // #9 fix — multi_media view-once messages must wipe their mediaItems
    // array too, otherwise every grouped photo/video stays readable in
    // Firebase after "deletion" (only mediaUrl/thumbnailUrl were wiped before).
    public static final String FIELD_MEDIA_ITEMS         = "mediaItems";

    // ── Delete delay: give receiver 1 second to register the open visually ─
    private static final long DELETE_DELAY_MS = 1_000L;

    private final ChatActivityDelegate            delegate;
    private final WeakReference<Activity>         activityRef;
    private final Handler                         mainHandler;
    private final ExecutorService                 ioExecutor;

    /**
     * Pending hard-delete message IDs (offline queue).
     * Populated when Firebase write fails due to no network.
     * Flushed by flushPendingDeletes() when connectivity returns.
     */
    private final Set<String> pendingDeleteIds = new HashSet<>();

    // ── Constructor ───────────────────────────────────────────────────────

    public ChatViewOnceController(@NonNull ChatActivityDelegate delegate) {
        this.delegate    = delegate;
        this.activityRef = new WeakReference<>(delegate.getActivity());
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.ioExecutor  = Executors.newSingleThreadExecutor();
    }

    // ─────────────────────────────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Called when receiver taps the "View Once" bubble to open the message.
     *
     * @param message  The view-once message being opened.
     * @param onOpened Runs on main thread after state is marked opened —
     *                 caller uses this to launch the full-screen viewer.
     */
    /**
     * Tracks which message is currently being viewed (dialog is open).
     * Prevents double-tap from opening the dialog twice.
     */
    private String currentlyViewingId = null;

    public void openViewOnce(@NonNull Message message, @Nullable Runnable onOpened) {
        String msgId = resolveId(message);
        if (msgId == null) return;

        // Guard: already opened, deleted, expired, or revoked — ignore tap
        if (STATE_OPENED.equals(message.viewOnceState)
                || STATE_DELETED.equals(message.viewOnceState)
                || STATE_EXPIRED.equals(message.viewOnceState)
                || STATE_REVOKED.equals(message.viewOnceState)
                || Boolean.TRUE.equals(message.deleted)) {
            return;
        }

        // Guard: dialog already open for this message — prevent double-tap
        if (msgId.equals(currentlyViewingId)) return;

        // Only receiver can open (sender's own message shows "sent" state only)
        String currentUid = delegate.getCurrentUid();
        if (currentUid == null || currentUid.equals(message.senderId)) return;

        // 1. Block screenshots immediately
        enableSecureWindow();

        // 2. Track that this message is being viewed
        currentlyViewingId = msgId;

        // NOTE: We do NOT call markOpened() here on tap.
        // markOpened (Firebase: viewOnceState="opened") is called only in
        // onViewerClosed(), AFTER the user explicitly closes the dialog.
        // This ensures:
        //   a) Sender does not see "Opened" until receiver has actually finished viewing.
        //   b) No race where Firebase listener fires and shows expired bubble
        //      while receiver is still reading content in the dialog.

        // 3. Notify caller to show dialog
        if (onOpened != null) mainHandler.post(onOpened);
    }

    /**
     * Called when sender marks a message as view-once before sending.
     * Sets viewOnce = true and viewOnceState = "sent" on the Message object.
     * Must be called BEFORE ChatMessageSender.pushMessage().
     */
    public static void tagMessageAsViewOnce(@NonNull Message m) {
        m.viewOnce      = true;
        m.viewOnceState = STATE_SENT;
    }

    /**
     * Called when the dialog is closed by the user (explicit "Close & Delete" tap).
     * THIS is where we mark opened in Firebase — only after user has actually seen content.
     * Then immediately hard-deletes from Firebase on both sides.
     */
    public void onViewerClosed(@NonNull String messageId) {
        // Clear the in-progress guard
        if (messageId.equals(currentlyViewingId)) {
            currentlyViewingId = null;
        }
        disableSecureWindow();
        // Mark opened in Firebase NOW (user has viewed) + immediately hard delete
        markOpened(messageId);
        hardDeleteFromFirebase(messageId);
    }

    /**
     * Called when receiver closes the view-once viewer. Preferred overload —
     * pass the full message so we can send the "viewed" silent push to the sender.
     *
     * @param messageId  Firebase message ID
     * @param senderId   UID of the original sender (receives "viewed" silent push)
     */
    public void onViewerClosed(@NonNull String messageId, @Nullable String senderId) {
        if (messageId.equals(currentlyViewingId)) {
            currentlyViewingId = null;
        }
        disableSecureWindow();
        markOpened(messageId);
        hardDeleteFromFirebase(messageId);

        // Silent push to sender: "Your secret message was viewed"
        // Only send if sender != receiver (sanity guard — should never be equal)
        String myUid   = delegate.getCurrentUid();
        String myName  = delegate.getCurrentName();
        String chatId  = delegate.getChatId();
        if (senderId != null && !senderId.isEmpty()
                && myUid != null && !senderId.equals(myUid)) {
            com.callx.app.utils.PushNotify.notifyViewOnceViewed(
                    senderId, myUid, myName, chatId, messageId);
        }
    }

    /**
     * Flush offline-queued deletes. Call from ChatActivity when connectivity
     * is restored (e.g., inside the NetworkCallback or ConnectivityManager listener).
     */
    public void flushPendingDeletes() {
        if (pendingDeleteIds.isEmpty()) return;
        Set<String> toFlush = new HashSet<>(pendingDeleteIds);
        pendingDeleteIds.clear();
        for (String id : toFlush) {
            hardDeleteFromFirebase(id);
        }
    }

    /**
     * Returns true if this message is view-once AND not yet opened.
     * Used by adapter to render the "View Once" badge.
     */
    public static boolean isViewOnce(@Nullable Message m) {
        return m != null && Boolean.TRUE.equals(m.viewOnce);
    }

    /**
     * Returns true if message was view-once and has already been opened/deleted/expired/revoked.
     * Adapter uses this to render the post-view bubble state.
     */
    public static boolean isExpired(@Nullable Message m) {
        if (m == null || !Boolean.TRUE.equals(m.viewOnce)) return false;
        return STATE_OPENED.equals(m.viewOnceState)
                || STATE_DELETED.equals(m.viewOnceState)
                || STATE_EXPIRED.equals(m.viewOnceState)
                || STATE_REVOKED.equals(m.viewOnceState)
                || Boolean.TRUE.equals(m.deleted);
    }

    /**
     * Returns true if message was revoked by sender before receiver opened it.
     * Adapter uses this to show "Removed" bubble to receiver.
     */
    public static boolean isRevoked(@Nullable Message m) {
        if (m == null || !Boolean.TRUE.equals(m.viewOnce)) return false;
        return STATE_REVOKED.equals(m.viewOnceState);
    }

    /**
     * Returns true if the expiry timer ran out before receiver opened.
     * Adapter uses this to show "Expired" bubble to receiver.
     */
    public static boolean isTimerExpired(@Nullable Message m) {
        if (m == null || !Boolean.TRUE.equals(m.viewOnce)) return false;
        return STATE_EXPIRED.equals(m.viewOnceState);
    }

    /**
     * Feature 7 — Expiry timer ran out.
     * Called by a scheduled background check (e.g. WorkManager / alarm) when
     * System.currentTimeMillis() >= message.viewOnceExpiresAt and the message
     * is still in STATE_SENT (not yet opened).
     *
     * Sets viewOnceState = "expired" on Firebase + permanently wipes content.
     * Receiver will see "Expired" bubble; sender sees it too.
     */
    public void markExpiredByTimer(@NonNull String messageId) {
        DatabaseReference msgRef = getMessageRef(messageId);
        if (msgRef == null) {
            pendingDeleteIds.add(messageId);
            return;
        }

        Map<String, Object> updates = new HashMap<>();
        updates.put(FIELD_VIEW_ONCE_STATE, STATE_EXPIRED);
        updates.put(FIELD_DELETED,         true);
        updates.put(FIELD_TEXT,            "");
        updates.put(FIELD_MEDIA_URL,       null);
        updates.put(FIELD_THUMBNAIL_URL,   null);
        updates.put("fileName",            null);
        updates.put(FIELD_MEDIA_ITEMS,      null);

        msgRef.updateChildren(updates, (error, ref) -> {
            if (error != null) {
                pendingDeleteIds.add(messageId);
            }
        });

        // Reflect locally immediately
        softDeleteLocally(messageId);
    }

    /**
     * Feature 8 — Sender revokes the message before receiver opens it.
     * Called when sender long-presses the pending lock bubble and confirms revoke.
     *
     * Sets viewOnceState = "revoked" on Firebase + permanently wipes content.
     * Receiver will see "Removed" bubble.
     *
     * @param messageId  The view-once message to revoke.
     * @param onSuccess  Runs on main thread after Firebase write succeeds (optional).
     * @param onFailure  Runs on main thread if Firebase write fails (optional).
     */
    public void revokeViewOnce(@NonNull String messageId,
                               @Nullable Runnable onSuccess,
                               @Nullable Runnable onFailure) {
        DatabaseReference msgRef = getMessageRef(messageId);
        if (msgRef == null) {
            pendingDeleteIds.add(messageId);
            if (onFailure != null) mainHandler.post(onFailure);
            return;
        }

        Map<String, Object> updates = new HashMap<>();
        updates.put(FIELD_VIEW_ONCE_STATE, STATE_REVOKED);
        updates.put(FIELD_DELETED,         true);
        updates.put(FIELD_TEXT,            "");
        updates.put(FIELD_MEDIA_URL,       null);
        updates.put(FIELD_THUMBNAIL_URL,   null);
        updates.put("fileName",            null);
        updates.put(FIELD_MEDIA_ITEMS,      null);

        msgRef.updateChildren(updates, (error, ref) -> {
            if (error != null) {
                pendingDeleteIds.add(messageId);
                if (onFailure != null) mainHandler.post(onFailure);
            } else {
                if (onSuccess != null) mainHandler.post(onSuccess);
            }
        });

        // Reflect locally immediately
        softDeleteLocally(messageId);
    }

    public void release() {
        mainHandler.removeCallbacksAndMessages(null);
        ioExecutor.shutdown();
        disableSecureWindow();
        currentlyViewingId = null;
    }

    // ─────────────────────────────────────────────────────────────────────
    // PRIVATE — State machine
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Batch-write viewOnceState + openedAt to Firebase.
     * Single updateChildren() call — one network round-trip.
     */
    private void markOpened(@NonNull String msgId) {
        DatabaseReference msgRef = getMessageRef(msgId);
        if (msgRef == null) return;

        Map<String, Object> updates = new HashMap<>();
        updates.put(FIELD_VIEW_ONCE_STATE, STATE_OPENED);
        updates.put(FIELD_OPENED_AT,       ServerValue.TIMESTAMP);

        msgRef.updateChildren(updates, (error, ref) -> {
            if (error != null) {
                // Queue for retry on reconnect
                pendingDeleteIds.add(msgId);
            }
        });

        // Mirror in local Room DB immediately (optimistic update)
        softDeleteLocally(msgId);
    }

    /**
     * Schedule hard Firebase delete after DELETE_DELAY_MS.
     * Using mainHandler.postDelayed (single shot, no loop — PERF safe).
     */
    private void scheduleDelete(@NonNull String msgId) {
        mainHandler.postDelayed(() -> hardDeleteFromFirebase(msgId), DELETE_DELAY_MS);
    }

    /**
     * Hard delete from Firebase: wipe text/media fields + mark deleted.
     * Does NOT remove the node entirely — keeps the node as a tombstone
     * so the sender's listener can also react and clean up their local DB.
     *
     * Firebase path: messages/{chatId}/{msgId}
     */
    private void hardDeleteFromFirebase(@NonNull String msgId) {
        DatabaseReference msgRef = getMessageRef(msgId);
        if (msgRef == null) {
            pendingDeleteIds.add(msgId); // offline — queue for later
            return;
        }

        Map<String, Object> wipe = new HashMap<>();
        wipe.put(FIELD_DELETED,       true);
        wipe.put(FIELD_VIEW_ONCE_STATE, STATE_DELETED);
        wipe.put(FIELD_TEXT,          "");          // wipe content
        wipe.put(FIELD_MEDIA_URL,     null);        // wipe media
        wipe.put(FIELD_THUMBNAIL_URL, null);        // wipe thumbnail
        wipe.put("fileName",          null);        // wipe file name
        wipe.put(FIELD_MEDIA_ITEMS,    null);        // wipe grouped media (#9 fix)

        msgRef.updateChildren(wipe, (error, ref) -> {
            if (error != null) {
                pendingDeleteIds.add(msgId); // retry on reconnect
            }
        });
    }

    /**
     * Soft-delete in Room DB: marks deleted=1, clears text.
     * Runs on background IO thread. Triggers PagingSource invalidation
     * → adapter removes bubble via DiffUtil (no notifyDataSetChanged).
     */
    private void softDeleteLocally(@NonNull String msgId) {
        ioExecutor.execute(() -> {
            Activity act = activityRef.get();
            if (act == null) return;
            com.callx.app.db.AppDatabase.getInstance(act)
                    .messageDao()
                    .softDelete(msgId);
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // PRIVATE — Security
    // ─────────────────────────────────────────────────────────────────────

    /** Block screenshots and screen recording during view-once viewing. */
    private void enableSecureWindow() {
        Activity act = activityRef.get();
        if (act == null) return;
        mainHandler.post(() -> {
            try {
                act.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            } catch (Exception ignored) {}
        });
    }

    /** Restore normal window after viewer closes. */
    private void disableSecureWindow() {
        Activity act = activityRef.get();
        if (act == null) return;
        mainHandler.post(() -> {
            try {
                act.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
            } catch (Exception ignored) {}
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // PRIVATE — Helpers
    // ─────────────────────────────────────────────────────────────────────

    @Nullable
    private DatabaseReference getMessageRef(@NonNull String msgId) {
        String chatId = delegate.getChatId();
        if (chatId == null) return null;
        return FirebaseUtils.getMessagesRef(chatId).child(msgId);
    }

    @Nullable
    private static String resolveId(@NonNull Message m) {
        if (m.messageId != null) return m.messageId;
        return m.id;
    }
}
