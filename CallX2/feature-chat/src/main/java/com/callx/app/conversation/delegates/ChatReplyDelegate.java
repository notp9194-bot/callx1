package com.callx.app.conversation.delegates;

import android.app.Activity;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.callx.app.chat.analytics.ReplyAnalyticsTracker;
import com.callx.app.chat.databinding.ActivityChatBinding;
import com.callx.app.chat.gesture.SwipeReplyHandler;
import com.callx.app.chat.reply.ReplyController;
import com.callx.app.chat.reply.ReplyDataMapper;
import com.callx.app.chat.ui.MessageHighlightAnimator;
import com.callx.app.conversation.MessagePagingAdapter;
import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.MessageEntity;
import com.callx.app.models.Message;
import com.google.android.material.snackbar.Snackbar;

import java.util.concurrent.Executor;

/**
 * ChatReplyDelegate — Reply bar UI, swipe-to-reply, navigate to original message.
 */
public class ChatReplyDelegate {

    public interface Callback {
        void runOnUiThread(Runnable r);
        String getCurrentUid();
        MessagePagingAdapter getAdapter();
    }

    private final Activity            activity;
    private final ActivityChatBinding binding;
    private final AppDatabase         db;
    private final Executor            ioExecutor;
    private final Callback            callback;

    public  Message         replyingTo;
    private ReplyController replyController;
    private ItemTouchHelper swipeHelper;

    public ChatReplyDelegate(Activity activity, ActivityChatBinding binding,
                             AppDatabase db, Executor ioExecutor, Callback callback) {
        this.activity   = activity;
        this.binding    = binding;
        this.db         = db;
        this.ioExecutor = ioExecutor;
        this.callback   = callback;
    }

    public void setupSwipeToReply(String currentUid) {
        MessagePagingAdapter adapter = callback.getAdapter();
        SwipeReplyHandler handler = new SwipeReplyHandler(activity,
                new java.util.AbstractList<Message>() {
                    @Override public Message get(int i) { return adapter.peek(i); }
                    @Override public int size() { return adapter.getItemCount(); }
                }, currentUid, (message, pos) -> {
                    ReplyAnalyticsTracker.get().onSwipeAttempt(100f);
                    startReply(message);
                });
        swipeHelper = new ItemTouchHelper(handler);
        swipeHelper.attachToRecyclerView(binding.rvMessages);

        replyController = new ReplyController(new ReplyController.Callback() {
            @Override public void onReplyActivated(Message m) { activateReplyDirect(m); }
            @Override public void onReplyCancelled() {}
            @Override public void onPendingUndo(Message m, Runnable cancel) {
                String sn = (currentUid != null && currentUid.equals(m.senderId))
                        ? "You" : (m.senderName != null ? m.senderName : "Unknown");
                Snackbar.make(binding.getRoot(), "Replying to " + sn + "…", Snackbar.LENGTH_SHORT)
                        .setAction("UNDO", v -> { cancel.run(); ReplyAnalyticsTracker.get().onUndoUsed(); })
                        .show();
            }
            @Override public void onNavigateToOriginal(String msgId) { navigateToOriginal(msgId); }
            @Override public void onUndoConfirmed() {}
        });
    }

    public void startReply(Message m) {
        if (replyController != null) replyController.onSwipeReply(m);
        else activateReplyDirect(m);
    }

    public void activateReplyDirect(Message m) {
        replyingTo = m;
        if (binding.llReplyBar == null) return;
        String uid = callback.getCurrentUid();
        String sn  = (uid != null && uid.equals(m.senderId)) ? "You" : (m.senderName != null ? m.senderName : "");
        String prev;
        if (Boolean.TRUE.equals(m.deleted)) prev = "🚫  Original message unavailable";
        else if (m.text != null && !m.text.isEmpty()) prev = m.text;
        else prev = buildTypePreview(m);

        if (binding.tvReplyBarName != null) binding.tvReplyBarName.setText(sn);
        if (binding.tvReplyBarText != null) binding.tvReplyBarText.setText(prev);
        if (binding.ivReplyBarThumb != null) {
            String thumb = "image".equals(m.type) ? m.mediaUrl
                         : "video".equals(m.type) ? m.thumbnailUrl : null;
            if (thumb != null && !thumb.isEmpty()) {
                binding.ivReplyBarThumb.setVisibility(View.VISIBLE);
                Glide.with(activity).load(thumb).centerCrop().into(binding.ivReplyBarThumb);
            } else {
                binding.ivReplyBarThumb.setVisibility(View.GONE);
            }
        }
        binding.llReplyBar.setVisibility(View.VISIBLE);
        binding.llReplyBar.setAlpha(0f); binding.llReplyBar.setTranslationY(40f);
        binding.llReplyBar.animate().alpha(1f).translationY(0f).setDuration(200).start();
        binding.etMessage.requestFocus();
        ReplyAnalyticsTracker.get().onSwipeTriggered();
    }

    public void clearReply() {
        replyingTo = null;
        if (replyController != null) replyController.cancel();
        if (binding.llReplyBar == null) return;
        binding.llReplyBar.animate().alpha(0f).translationY(20f).setDuration(150)
                .withEndAction(() -> {
                    binding.llReplyBar.setVisibility(View.GONE);
                    binding.llReplyBar.setAlpha(1f); binding.llReplyBar.setTranslationY(0f);
                }).start();
        if (binding.ivReplyBarThumb != null) binding.ivReplyBarThumb.setVisibility(View.GONE);
    }

    private String buildTypePreview(Message m) {
        if (m.type == null) return "[message]";
        switch (m.type) {
            case "image": return "📷 Photo";
            case "gif":   return "🎞️ GIF";
            case "video": return "🎬 Video";
            case "audio": return "🎤 Voice message";
            case "file":  return "📎 " + (m.fileName != null ? m.fileName : "File");
            default:      return "[" + m.type + "]";
        }
    }

    public void setupFabBackToLatest() {
        if (binding.fabBackToLatest == null) return;
        MessagePagingAdapter adapter = callback.getAdapter();
        binding.fabBackToLatest.setOnClickListener(v -> {
            int last = adapter.getItemCount() - 1;
            if (last >= 0) binding.rvMessages.smoothScrollToPosition(last);
            MessageHighlightAnimator.hideFab(binding.fabBackToLatest);
        });
        binding.rvMessages.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                androidx.recyclerview.widget.LinearLayoutManager lm =
                        (androidx.recyclerview.widget.LinearLayoutManager) rv.getLayoutManager();
                if (lm == null) return;
                if (lm.findLastVisibleItemPosition() >= adapter.getItemCount() - 3)
                    MessageHighlightAnimator.hideFab(binding.fabBackToLatest);
            }
        });
    }

    public void navigateToOriginal(String messageId) {
        if (messageId == null || messageId.isEmpty()) return;
        MessagePagingAdapter adapter = callback.getAdapter();
        int pos = -1;
        for (int i = 0; i < adapter.getItemCount(); i++) {
            Message m = adapter.peek(i);
            if (m != null && (messageId.equals(m.id) || messageId.equals(m.messageId))) { pos = i; break; }
        }
        if (pos >= 0) {
            MessageHighlightAnimator.scrollAndHighlight(binding.rvMessages, pos, binding.fabBackToLatest);
        } else {
            ioExecutor.execute(() -> {
                if (db == null) {
                    callback.runOnUiThread(() -> Toast.makeText(activity, "Message not in view — scroll up", Toast.LENGTH_SHORT).show());
                    return;
                }
                MessageEntity target = db.messageDao().getMessageById(messageId);
                if (target == null || target.timestamp == null) {
                    callback.runOnUiThread(() -> Toast.makeText(activity, "Original message not found", Toast.LENGTH_SHORT).show());
                    return;
                }
                int fromBottom = db.messageDao().countMessagesAfterTimestamp(target.chatId, target.timestamp);
                int safePos = Math.max(0, adapter.getItemCount() - fromBottom - 1);
                callback.runOnUiThread(() -> {
                    if (binding.fabBackToLatest != null) {
                        binding.fabBackToLatest.setVisibility(View.VISIBLE);
                        binding.fabBackToLatest.animate().alpha(1f).setDuration(200).start();
                    }
                    binding.rvMessages.scrollToPosition(safePos);
                    binding.rvMessages.postDelayed(() -> {
                        RecyclerView.ViewHolder vh = binding.rvMessages.findViewHolderForAdapterPosition(safePos);
                        if (vh != null) MessageHighlightAnimator.flashHighlight(vh.itemView);
                    }, 500);
                });
            });
        }
    }

    public void applyReplyFieldsToMessage(Message outgoing, String currentUid) {
        if (replyingTo != null) ReplyDataMapper.applyReplyFields(outgoing, replyingTo, currentUid);
    }

    public void release() {
        if (replyController != null) replyController.release();
    }
}
