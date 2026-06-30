package com.callx.app.conversation;

/**
 * GalleryReplyBridge — tiny in-memory handoff used when the user swipes
 * UP inside the grouped-media gallery (MediaViewerActivity) to reply to
 * that message.
 *
 * MediaViewerActivity has no Message object (it only ever receives the
 * already-flattened mediaItems JSON), so it can't call startReply()
 * directly. Instead it drops a {chatId, messageId} pair here and
 * finishes; ChatActivity / GroupChatActivity check this bridge in
 * onResume(), resolve the actual Message via
 * MessagePagingAdapter#findMessageById(...), and call their existing
 * startReply(Message) flow — no new plumbing, no Parcelable needed.
 */
public final class GalleryReplyBridge {

    private static String pendingChatId;
    private static String pendingMessageId;

    private GalleryReplyBridge() {}

    /** Called by MediaViewerActivity right before finish() on swipe-up. */
    public static synchronized void requestReply(String chatId, String messageId) {
        if (chatId == null || messageId == null) return;
        pendingChatId = chatId;
        pendingMessageId = messageId;
    }

    /**
     * Called from onResume() of the chat screen. Returns the pending
     * messageId only if it was requested for THIS chatId, and clears it
     * either way it matched (one-shot — avoids re-triggering on later
     * resumes, e.g. after the app is backgrounded/foregrounded again).
     */
    public static synchronized String consumeIfMatches(String chatId) {
        if (chatId == null || pendingChatId == null || !chatId.equals(pendingChatId)) {
            return null;
        }
        String messageId = pendingMessageId;
        pendingChatId = null;
        pendingMessageId = null;
        return messageId;
    }
}
