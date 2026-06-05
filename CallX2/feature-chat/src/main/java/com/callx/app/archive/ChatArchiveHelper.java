package com.callx.app.archive;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.callx.app.db.AppDatabase;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ChatArchiveHelper — Archive/unarchive chats.
 *
 * Archived chats:
 *   • Hidden from main chat list
 *   • Visible in "Archived" section (bottom of chat list or separate screen)
 *   • Still receive messages and notifications (unless muted)
 *   • Auto-unarchive when new message arrives (WhatsApp behaviour, configurable)
 *
 * Usage:
 *   ChatArchiveHelper.archive(ctx, chatId, true);    // archive
 *   ChatArchiveHelper.archive(ctx, chatId, false);   // unarchive
 *
 *   // In ChatsFragment, filter:
 *   db.chatDao().getActiveChatsSorted()    // shows non-archived
 *   db.chatDao().getArchivedChats()        // shows archived
 */
public class ChatArchiveHelper {

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final Handler         mainHandler = new Handler(Looper.getMainLooper());

    public static void archive(Context ctx, String chatId, boolean archive) {
        executor.execute(() -> {
            AppDatabase.getInstance(ctx).chatDao().updateArchived(chatId, archive);
            mainHandler.post(() ->
                Toast.makeText(ctx,
                    archive ? "Chat archived" : "Chat unarchived",
                    Toast.LENGTH_SHORT).show());
        });
    }

    /**
     * Call this when a new message arrives in an archived chat.
     * Per WhatsApp behaviour: auto-unarchive on new message.
     */
    public static void autoUnarchiveOnMessage(Context ctx, String chatId) {
        executor.execute(() ->
            AppDatabase.getInstance(ctx).chatDao().updateArchived(chatId, false));
    }

    /**
     * Returns count of archived chats — for badge on archive entry in chat list.
     */
    public static void getArchivedCount(Context ctx, CountCallback cb) {
        executor.execute(() -> {
            int count = AppDatabase.getInstance(ctx).chatDao().getArchivedCount();
            mainHandler.post(() -> cb.onCount(count));
        });
    }

    public interface CountCallback {
        void onCount(int count);
    }
}
