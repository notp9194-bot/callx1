package com.callx.app.chatv2;

import android.content.Context;

import com.callx.app.db.AppDatabase;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.ReactionJsonUtil;
import com.google.firebase.database.DatabaseReference;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Emoji reactions for the native fast-chat screen. Deliberately its own
 * small helper rather than reusing :feature-chat's ChatReactionController
 * — that controller is wired to ChatActivityDelegate (paging adapter,
 * ChatActivity's own executor, push-notify plumbing), and this module's
 * whole premise is not depending on :feature-chat. Same wire format
 * though (Firebase messages/{id}/reactions/{uid}, mirrored into Room's
 * reactionsJson column) so a reaction placed here shows up correctly in
 * the existing canvas chat screen too, and vice versa.
 *
 * Not covered here (kept out of scope for this pass): the reaction
 * push-notification to the message's original sender that
 * ChatReactionController fires — this only needs the local read/write
 * round trip to work.
 */
public final class FastChatReactionHelper {

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private FastChatReactionHelper() {}

    /** Tapping the same emoji again removes it (WhatsApp-style toggle);
     *  any other emoji replaces whatever this user had reacted with. */
    public static void toggleReaction(Context context, String chatId, String messageId,
                                       String currentReactionsJson, String emoji, String uid) {
        if (chatId == null || messageId == null || emoji == null || uid == null) return;

        Map<String, String> current = ReactionJsonUtil.reactionsFromJson(currentReactionsJson);
        boolean removing = emoji.equals(current.get(uid));

        DatabaseReference reactionRef =
                FirebaseUtils.getMessagesRef(chatId).child(messageId).child("reactions").child(uid);
        if (removing) {
            reactionRef.removeValue();
        } else {
            reactionRef.setValue(emoji);
        }

        Context appContext = context.getApplicationContext();
        IO.execute(() -> {
            String json = AppDatabase.getInstance(appContext).messageDao().getReactionsJson(messageId);
            Map<String, String> latest = ReactionJsonUtil.reactionsFromJson(json);
            if (removing) {
                latest.remove(uid);
            } else {
                latest.put(uid, emoji);
            }
            AppDatabase.getInstance(appContext).messageDao()
                    .updateReactions(messageId, ReactionJsonUtil.reactionsToJson(latest));
        });
    }
}
