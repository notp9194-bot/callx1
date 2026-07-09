package com.callx.app.conversation.info;

/**
 * MessageInfoBridge — tiny in-memory handoff used to open the lightweight
 * Message Info bottom sheet (MessageInfoBottomSheet) from either
 * ChatActivity (1:1) or GroupChatActivity (group).
 *
 * MessageInfoData isn't Parcelable (per-member receipt lists, Message
 * refs) and doesn't need to be — mirrors GalleryReplyBridge /
 * GalleryForwardBridge: drop the precomputed data here right before
 * showing the sheet, MessageInfoBottomSheet takes() it in onViewCreated().
 * One-shot, cleared on take so a stale value never leaks into a later launch.
 */
public final class MessageInfoBridge {

    private static MessageInfoData pending;

    private MessageInfoBridge() {}

    /** Called by ChatActivity/GroupChatActivity right before showing MessageInfoBottomSheet. */
    public static synchronized void set(MessageInfoData data) {
        pending = data;
    }

    /** Called by MessageInfoBottomSheet in onViewCreated(). Clears itself either way. */
    public static synchronized MessageInfoData take() {
        MessageInfoData data = pending;
        pending = null;
        return data;
    }
}
