package com.callx.app.conversation;

/**
 * GalleryItemActionBridge — in-memory handoff for per-item actions taken
 * inside the grouped-media gallery (MediaViewerActivity): deleting a
 * single image/video out of a multi_media group, starring a single item,
 * or editing that item's own caption. Same one-shot pattern as
 * {@link GalleryReplyBridge} / {@link GalleryForwardBridge}.
 *
 * ChatActivity / GroupChatActivity consume this in onResume(), resolve the
 * Message via MessagePagingAdapter#findMessageById(...), mutate its
 * mediaItems list, and push the updated list to Firebase
 * (messages/{chatId}/{messageId}/mediaItems) + local Room cache.
 */
public final class GalleryItemActionBridge {

    public static final String ACTION_DELETE_ITEM   = "delete_item";
    public static final String ACTION_STAR_ITEM      = "star_item";
    public static final String ACTION_UNSTAR_ITEM    = "unstar_item";
    public static final String ACTION_EDIT_CAPTION   = "edit_caption";

    private static String pendingChatId;
    private static String pendingMessageId;
    private static int    pendingItemIndex = -1;
    private static String pendingAction;
    private static String pendingCaption; // only used for ACTION_EDIT_CAPTION

    private GalleryItemActionBridge() {}

    public static synchronized void request(String chatId, String messageId, int itemIndex,
                                              String action, String caption) {
        if (chatId == null || messageId == null || action == null) return;
        pendingChatId    = chatId;
        pendingMessageId = messageId;
        pendingItemIndex = itemIndex;
        pendingAction    = action;
        pendingCaption   = caption;
    }

    /** Simple POJO returned to the consumer — avoids juggling 4 statics at the call site. */
    public static final class PendingAction {
        public final String messageId;
        public final int itemIndex;
        public final String action;
        public final String caption;
        PendingAction(String messageId, int itemIndex, String action, String caption) {
            this.messageId = messageId; this.itemIndex = itemIndex;
            this.action = action; this.caption = caption;
        }
    }

    public static synchronized PendingAction consumeIfMatches(String chatId) {
        if (chatId == null || pendingChatId == null || !chatId.equals(pendingChatId)) {
            return null;
        }
        PendingAction result = new PendingAction(pendingMessageId, pendingItemIndex, pendingAction, pendingCaption);
        pendingChatId = null;
        pendingMessageId = null;
        pendingItemIndex = -1;
        pendingAction = null;
        pendingCaption = null;
        return result;
    }
}
