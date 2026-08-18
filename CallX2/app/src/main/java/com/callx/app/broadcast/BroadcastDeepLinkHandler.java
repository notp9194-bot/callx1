package com.callx.app.broadcast;

import android.content.Context;
import android.content.Intent;

/**
 * BroadcastDeepLinkHandler — utility to open broadcast screens from anywhere.
 *
 * Usage from ChatsFragment / notification tap:
 *
 *   BroadcastDeepLinkHandler.openBroadcastLists(context);
 *   BroadcastDeepLinkHandler.openBroadcastChat(context, listId, listName);
 */
public class BroadcastDeepLinkHandler {

    /** Open the main broadcast lists screen. */
    public static void openBroadcastLists(Context ctx) {
        ctx.startActivity(
                new Intent(ctx, BroadcastListsActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    /** Open the chat screen for a specific broadcast list. */
    public static void openBroadcastChat(Context ctx, String listId, String listName) {
        if (listId == null || listId.isEmpty()) return;
        Intent i = new Intent(ctx, BroadcastChatActivity.class);
        i.putExtra(BroadcastListsActivity.EXTRA_LIST_ID,   listId);
        i.putExtra(BroadcastListsActivity.EXTRA_LIST_NAME, listName != null ? listName : "Broadcast");
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
    }
}
