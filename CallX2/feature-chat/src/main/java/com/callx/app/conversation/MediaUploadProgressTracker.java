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

    /** What the bar is doing right now — drives the "Compressing…" label. */
    public static final int STAGE_UNKNOWN = 0, STAGE_COMPRESS = 1, STAGE_UPLOAD = 2;

    private final Map<String, Integer> progressByMessageId = new ConcurrentHashMap<>();
    private final Map<String, int[]> detailByMessageId = new ConcurrentHashMap<>();   // {stage, stagePercent}
    private final Map<String, Long> totalBytesByMessageId = new ConcurrentHashMap<>();

    public void setDetail(String messageId, int stage, int stagePercent) {
        if (messageId == null) return;
        detailByMessageId.put(messageId, new int[]{stage, stagePercent});
    }
    public int getStage(String messageId) {
        int[] d = messageId == null ? null : detailByMessageId.get(messageId);
        return d != null ? d[0] : STAGE_UNKNOWN;
    }
    /** Progress of the CURRENT stage (0-100), or -1 if unknown. */
    public int getStagePercent(String messageId) {
        int[] d = messageId == null ? null : detailByMessageId.get(messageId);
        return d != null ? d[1] : -1;
    }
    /** Bytes that will actually go over the wire (compressed size), once known. */
    public void setTotalBytes(String messageId, long bytes) {
        if (messageId == null || bytes <= 0) return;
        totalBytesByMessageId.put(messageId, bytes);
    }
    public long getTotalBytes(String messageId) {
        Long b = messageId == null ? null : totalBytesByMessageId.get(messageId);
        return b != null ? b : 0L;
    }

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
        if (messageId == null) return;
        progressByMessageId.remove(messageId);
        detailByMessageId.remove(messageId);
        totalBytesByMessageId.remove(messageId);
    }
}
