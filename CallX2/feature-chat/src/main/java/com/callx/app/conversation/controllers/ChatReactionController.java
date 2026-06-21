package com.callx.app.conversation.controllers;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.callx.app.models.Message;
import com.callx.app.utils.ReactionJsonUtil;
import com.google.firebase.database.DatabaseReference;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Handles tap-and-hold emoji reactions on individual chat messages.
 *
 * Message#reactions (Map<uid, emoji>) already existed as a model field and
 * was being written straight to Firebase from ChatActivity#sendReaction —
 * but that write never survived the round-trip through Room: MessageEntity
 * had no column for it, so the moment the paging adapter re-read a message
 * from the local DB (which is what MessagePagingAdapter's PagingSource
 * actually displays, not the live Firebase snapshot), the reaction vanished.
 * This controller fixes that full pipeline, not just the write:
 *
 *   tap emoji → Firebase messages/{id}/reactions/{uid}  (live, for the
 *               partner's device + ChatActivity's ChildEventListener)
 *             → Room messages.reactionsJson              (so the
 *               Room-backed paging adapter shows it immediately and it
 *               survives app restart / offline viewing)
 *
 * Same-emoji-again now REMOVES the reaction (WhatsApp-style toggle) instead
 * of just re-writing the same value — previously there was no way to
 * un-react at all.
 *
 * Tapping the reactions chip itself (the small "❤️2 👍1" row under a
 * bubble) opens a "who reacted" dialog — see MessagePagingAdapter
 * .ActionListener#onReactionTap.
 */
public class ChatReactionController {

    private final ChatActivityDelegate delegate;

    public ChatReactionController(ChatActivityDelegate delegate) {
        this.delegate = delegate;
    }

    // ── React / un-react ─────────────────────────────────────────────────

    /** Tap an emoji on a message from the quick-react row or full picker.
     *  Tapping the SAME emoji the user already reacted with removes it;
     *  any other emoji replaces whatever they had before (only one
     *  reaction per user per message, same as WhatsApp/Telegram). */
    public void toggleReaction(Message m, String emoji) {
        if (m == null || m.id == null || m.id.isEmpty() || emoji == null) return;
        String uid = delegate.getCurrentUid();
        if (uid == null) return;

        String existing = m.reactions != null ? m.reactions.get(uid) : null;
        boolean removing = emoji.equals(existing);

        DatabaseReference reactionRef =
                delegate.getMessagesRef().child(m.id).child("reactions").child(uid);
        if (removing) {
            reactionRef.removeValue();
        } else {
            reactionRef.setValue(emoji);
        }

        // Mirror into Room right away — the live Firebase ChildEventListener
        // will confirm this a moment later, but the paging adapter reads from
        // Room, so without this the tap wouldn't visibly do anything until
        // that round-trip completes.
        delegate.getIoExecutor().execute(() -> {
            String json = delegate.getDb().messageDao().getReactionsJson(m.id);
            Map<String, String> current = ReactionJsonUtil.reactionsFromJson(json);
            if (removing) {
                current.remove(uid);
            } else {
                current.put(uid, emoji);
            }
            delegate.getDb().messageDao()
                    .updateReactions(m.id, ReactionJsonUtil.reactionsToJson(current));
        });
    }

    // ── "Who reacted" dialog ──────────────────────────────────────────────

    /** Opens when the user taps the reactions chip under a bubble. 1:1 chat
     *  only has two possible reactors, so this resolves names straight from
     *  the delegate — no extra Firebase lookups needed. */
    public void showReactedUsers(Message m) {
        if (m == null || m.reactions == null || m.reactions.isEmpty()) return;
        Context ctx = delegate.getActivity();
        if (ctx == null) return;

        // uid → display name, newest-known-participant-first (me, then partner).
        Map<String, String> names = new LinkedHashMap<>();
        String myUid = delegate.getCurrentUid();
        String partnerUid = delegate.getPartnerUid();
        if (myUid != null) names.put(myUid, "You");
        if (partnerUid != null) {
            String partnerName = delegate.getPartnerName();
            names.put(partnerUid, partnerName != null ? partnerName : "Them");
        }

        ScrollView scroll = new ScrollView(ctx);
        LinearLayout container = new LinearLayout(ctx);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(ctx, 20);
        container.setPadding(pad, dp(ctx, 8), pad, dp(ctx, 8));
        scroll.addView(container);

        for (Map.Entry<String, String> e : m.reactions.entrySet()) {
            String uid = e.getKey();
            String emoji = e.getValue();
            if (uid == null || emoji == null) continue;
            container.addView(buildReactorRow(ctx, emoji, names.get(uid)));
        }

        new AlertDialog.Builder(ctx)
                .setTitle("Reactions")
                .setView(scroll)
                .setPositiveButton("Close", null)
                .show();
    }

    private android.view.View buildReactorRow(Context ctx, String emoji, String name) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(ctx, 10), 0, dp(ctx, 10));

        TextView tvEmoji = new TextView(ctx);
        tvEmoji.setText(emoji);
        tvEmoji.setTextSize(22);
        tvEmoji.setPadding(0, 0, dp(ctx, 16), 0);
        row.addView(tvEmoji);

        TextView tvName = new TextView(ctx);
        tvName.setText(name != null ? name : "Someone");
        tvName.setTextSize(15);
        tvName.setTypeface(tvName.getTypeface(), Typeface.NORMAL);
        tvName.setTextColor(0xFF222222);
        row.addView(tvName);

        return row;
    }

    private int dp(Context ctx, int value) {
        return (int) (value * ctx.getResources().getDisplayMetrics().density);
    }
}
