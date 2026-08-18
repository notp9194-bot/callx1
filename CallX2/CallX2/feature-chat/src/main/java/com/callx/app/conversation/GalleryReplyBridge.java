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
    // Which item inside the group was on-screen when the user swiped up to
    // reply (-1 = unknown/not a grouped-media gallery → reply quotes the
    // whole message like before).
    private static int pendingItemIndex = -1;

    private GalleryReplyBridge() {}

    /** Called by MediaViewerActivity right before finish() on swipe-up. */
    public static synchronized void requestReply(String chatId, String messageId) {
        requestReply(chatId, messageId, -1);
    }

    /** Same as above but also records which gallery item was active, so the
     *  reply preview can quote that specific image/video instead of the
     *  generic "📷 Photos" group label. */
    public static synchronized void requestReply(String chatId, String messageId, int itemIndex) {
        if (chatId == null || messageId == null) return;
        pendingChatId = chatId;
        pendingMessageId = messageId;
        pendingItemIndex = itemIndex;
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
        pendingItemIndex = -1;
        return messageId;
    }

    /** Peeks the item index without clearing anything — call this BEFORE
     *  consumeIfMatches() if you need both the messageId and the index. */
    public static synchronized int peekItemIndex(String chatId) {
        if (chatId == null || pendingChatId == null || !chatId.equals(pendingChatId)) return -1;
        return pendingItemIndex;
    }
}
