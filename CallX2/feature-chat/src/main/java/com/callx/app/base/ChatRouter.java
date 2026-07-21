package com.callx.app.base;

import android.content.Context;
import android.content.Intent;

/**
 * Single entry-point for launching any chat screen.
 * Replaces scattered new Intent(ctx, ChatActivity.class) / GroupChatActivity.class calls.
 *
 * Usage:
 *   ChatRouter.open(context, chatId, ChatType.PERSONAL, displayName, avatarUrl);
 *   ChatRouter.open(context, groupId, ChatType.GROUP, groupName, groupAvatarUrl);
 *   ChatRouter.open(context, broadcastId, ChatType.BROADCAST, listName, null);
 */
public class ChatRouter {

    public static final String EXTRA_CHAT_ID    = "chat_id";
    public static final String EXTRA_CHAT_TYPE  = "chat_type";
    public static final String EXTRA_DISPLAY_NAME = "display_name";
    public static final String EXTRA_AVATAR_URL   = "avatar_url";

    /** Launch the correct Activity for the given ChatType */
    public static void open(Context context, String chatId, ChatType type,
                            String displayName, String avatarUrl) {
        Intent intent;
        switch (type) {
            case GROUP:
            case GROUP_TOPIC:
                intent = new Intent();
                intent.setClassName(context.getPackageName(), "com.callx.app.group.GroupChatActivity");
                break;
            case BROADCAST:
                intent = new Intent();
                intent.setClassName(context.getPackageName(), "com.callx.app.broadcast.BroadcastChatActivity");
                break;
            case PERSONAL:
            default:
                intent = new Intent();
                intent.setClassName(context.getPackageName(), "com.callx.app.conversation.ChatActivity");
                break;
        }
        intent.putExtra(EXTRA_CHAT_ID, chatId);
        intent.putExtra(EXTRA_CHAT_TYPE, type.name());
        intent.putExtra(EXTRA_DISPLAY_NAME, displayName);
        if (avatarUrl != null) intent.putExtra(EXTRA_AVATAR_URL, avatarUrl);
        context.startActivity(intent);
    }

    /** Convenience: open a personal 1:1 chat */
    public static void openPersonal(Context context, String userId,
                                    String displayName, String avatarUrl) {
        open(context, userId, ChatType.PERSONAL, displayName, avatarUrl);
    }

    /** Convenience: open a group chat */
    public static void openGroup(Context context, String groupId,
                                 String groupName, String avatarUrl) {
        open(context, groupId, ChatType.GROUP, groupName, avatarUrl);
    }

    /** Convenience: open a broadcast list */
    public static void openBroadcast(Context context, String broadcastId,
                                     String listName) {
        open(context, broadcastId, ChatType.BROADCAST, listName, null);
    }
}
