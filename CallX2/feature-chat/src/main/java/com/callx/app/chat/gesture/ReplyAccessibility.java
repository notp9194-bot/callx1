package com.callx.app.chat.gesture;

import android.content.Context;
import android.view.View;
import android.view.accessibility.AccessibilityManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.chat.R;
import com.callx.app.models.Message;

import java.util.WeakHashMap;

/**
 * v819: TalkBack / switch-access alternative to swipe-to-reply.
 *
 * Swiping a bubble is not possible with an accessibility service, so every replyable message row
 * gets a custom accessibility action "Reply" (shown in the TalkBack actions menu). The action
 * goes through exactly the same host callback as the swipe, so every rule (blocked chat,
 * admins-only group, recording, multi-select) applies equally.
 *
 * Zero cost when no accessibility service is running: rows are only decorated while
 * AccessibilityManager reports enabled.
 */
public final class ReplyAccessibility {

    public interface Host {
        @Nullable Message messageAt(int adapterPosition);
        /** Same rules as the swipe gate (blocked / locked / recording / multi-select). */
        boolean canReply();
        void reply(@NonNull Message message);
    }

    private ReplyAccessibility() { }

    public static void attach(@NonNull final RecyclerView rv, @NonNull final Host host) {
        final Context ctx = rv.getContext();
        final AccessibilityManager am =
                (AccessibilityManager) ctx.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am == null) return;
        final CharSequence label = ctx.getString(R.string.chat_reply_a11y_action);
        final WeakHashMap<View, Integer> ids = new WeakHashMap<>();

        rv.addOnChildAttachStateChangeListener(new RecyclerView.OnChildAttachStateChangeListener() {
            @Override public void onChildViewAttachedToWindow(@NonNull View v) {
                if (!am.isEnabled()) return;
                Message m = host.messageAt(rv.getChildAdapterPosition(v));
                Integer old = ids.get(v);
                if (SwipeReplyHandler.isReplyable(m)) {
                    if (old != null) return;                       // already decorated
                    int id = ViewCompat.addAccessibilityAction(v, label, (view, args) -> {
                        if (!host.canReply()) return false;
                        Message mm = host.messageAt(rv.getChildAdapterPosition(view));
                        if (!SwipeReplyHandler.isReplyable(mm)) return false;
                        host.reply(mm);
                        return true;
                    });
                    ids.put(v, id);
                } else if (old != null) {                          // recycled onto a system row
                    ViewCompat.removeAccessibilityAction(v, old);
                    ids.remove(v);
                }
            }

            @Override public void onChildViewDetachedFromWindow(@NonNull View v) {
                Integer old = ids.remove(v);
                if (old != null) ViewCompat.removeAccessibilityAction(v, old);
            }
        });
    }
}
