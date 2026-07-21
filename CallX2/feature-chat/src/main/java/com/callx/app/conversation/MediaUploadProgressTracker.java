package com.callx.app.conversation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory live-percentage store for outgoing media uploads, keyed by
 * message id. Exists purely so a bubble's upload spinner shows the correct
 * percentage after the user scrolls it off-screen and back — MessagePagingAdapter
 * rebinds ViewHolders from scratch on every re-bind, so without this the
 * spinner would reset to indeterminate every time a row is recycled and
 * reused during an in-flight upload.
 *
 * Not persisted — an in-flight upload's progress doesn't need to survive a
 * process death, only a scroll (the row itself, including its "uploading"
 * status and local file path, IS persisted via Room — see
 * ChatMessageSender#insertLocalPendingMedia).
 */
public final class MediaUploadProgressTracker {

    private final Map<String, Integer> progressByMessageId = new ConcurrentHashMap<>();

    /** @param percent 0-100 for a live percentage, or -1 for indeterminate. */
    public void setProgress(String messageId, int percent) {
        if (messageId == null) return;
        progressByMessageId.put(messageId, percent);
    }

    /** @return the last known percentage, or -1 if none is tracked (indeterminate / not uploading). */
    public int getProgress(String messageId) {
        if (messageId == null) return -1;
        Integer p = progressByMessageId.get(messageId);
        return p != null ? p : -1;
    }

    /** Call once an upload finishes (success or failure) so the entry doesn't linger forever. */
    public void clear(String messageId) {
        if (messageId != null) progressByMessageId.remove(messageId);
    }
}
