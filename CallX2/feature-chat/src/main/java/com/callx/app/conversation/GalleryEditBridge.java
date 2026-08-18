package com.callx.app.conversation;

/**
 * GalleryEditBridge — in-memory handoff used when the user taps the new
 * "Edit" action inside MediaViewerActivity (viewing a single or grouped
 * media message), edits the photo in MediaEditActivity, and hits Send.
 *
 * Mirrors GalleryReplyBridge / GalleryForwardBridge: MediaViewerActivity
 * has no direct reference to ChatMediaController / GroupChatActivity's
 * send pipeline, so it drops the already-baked result here and finishes;
 * ChatActivity / GroupChatActivity pick it up in onResume() and push it
 * through their existing uploadSequentially(...) pipeline as a brand-new
 * message — same as WhatsApp's "edit a photo you're viewing → sends as a
 * new message" behavior.
 */
public final class GalleryEditBridge {

    /** One pending edited-photo send. */
    public static final class Pending {
        public final String uri;
        public final String caption;
        public final boolean isHD;

        Pending(String uri, String caption, boolean isHD) {
            this.uri = uri;
            this.caption = caption;
            this.isHD = isHD;
        }
    }

    private static String pendingChatId;
    private static String pendingUri;
    private static String pendingCaption;
    private static boolean pendingHD;

    private GalleryEditBridge() {}

    /** Called by MediaViewerActivity right before finish(), once MediaEditActivity returns RESULT_OK. */
    public static synchronized void requestSend(String chatId, String uri, String caption, boolean isHD) {
        if (chatId == null || uri == null) return;
        pendingChatId = chatId;
        pendingUri = uri;
        pendingCaption = caption;
        pendingHD = isHD;
    }

    /**
     * Called from onResume() of the chat screen. Returns the pending edit
     * only if it was requested for THIS chatId, and clears it either way —
     * one-shot, same as the other Gallery*Bridge classes.
     */
    public static synchronized Pending consumeIfMatches(String chatId) {
        if (chatId == null || pendingChatId == null || !chatId.equals(pendingChatId)) {
            return null;
        }
        Pending p = new Pending(pendingUri, pendingCaption, pendingHD);
        pendingChatId = null;
        pendingUri = null;
        pendingCaption = null;
        pendingHD = false;
        return p;
    }
}
