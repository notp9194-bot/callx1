package com.callx.app.conversation;

import java.util.ArrayList;
import java.util.List;

/**
 * GalleryForwardBridge — in-memory handoff used when the user selects one
 * or more items inside the grouped-media gallery (MediaViewerActivity) and
 * taps "Forward". Mirrors {@link GalleryReplyBridge}'s pattern since
 * MediaViewerActivity has no Message object of its own — it only ever
 * receives the flattened mediaItems JSON.
 *
 * MediaViewerActivity drops {chatId, messageId, selectedIndices} here and
 * finishes; ChatActivity / GroupChatActivity check this bridge in
 * onResume(), resolve the original Message via
 * MessagePagingAdapter#findMessageById(...), build a subset Message that
 * contains only the selected mediaItems, and forward that.
 *
 * If selectedIndices is null/empty, the WHOLE group should be forwarded
 * (used by the toolbar "Forward" action when nothing is explicitly
 * selected — same as WhatsApp behavior).
 */
public final class GalleryForwardBridge {

    private static String pendingChatId;
    private static String pendingMessageId;
    private static List<Integer> pendingIndices;

    private GalleryForwardBridge() {}

    public static synchronized void requestForward(String chatId, String messageId, List<Integer> selectedIndices) {
        if (chatId == null || messageId == null) return;
        pendingChatId    = chatId;
        pendingMessageId = messageId;
        pendingIndices   = selectedIndices != null ? new ArrayList<>(selectedIndices) : null;
    }

    /**
     * Returns the pending selected indices (or null = "forward whole group")
     * only if it was requested for THIS chatId, and clears it either way —
     * one-shot, same as GalleryReplyBridge.
     */
    public static synchronized List<Integer> consumeIfMatches(String chatId, String[] outMessageId) {
        if (chatId == null || pendingChatId == null || !chatId.equals(pendingChatId)) {
            return null;
        }
        if (outMessageId != null && outMessageId.length > 0) outMessageId[0] = pendingMessageId;
        List<Integer> indices = pendingIndices;
        pendingChatId = null;
        pendingMessageId = null;
        pendingIndices = null;
        return indices != null ? indices : new ArrayList<>(); // empty (non-null) list = "whole group"
    }
}
