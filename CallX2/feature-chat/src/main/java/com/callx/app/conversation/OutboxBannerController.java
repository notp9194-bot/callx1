package com.callx.app.conversation;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewStub;
import android.widget.TextView;

import androidx.lifecycle.LifecycleOwner;

import com.callx.app.chat.R;
import com.callx.app.db.AppDatabase;

/**
 * One banner for the whole offline-send story (shared by ChatActivity and GroupChatActivity):
 *  - offline, nothing queued   -> "No internet — showing cached messages"
 *  - offline, N queued         -> "No internet — N message(s) waiting to send"
 *  - online but N still queued for >3s (stuck / slow) -> "Sending N message(s)…"
 * A normal online send finishes well inside 3s, so the banner never flickers for it.
 * The per-bubble clock icon (status pending/uploading) stays the primary per-message indicator.
 */
public final class OutboxBannerController {
    private static final long SENDING_DELAY_MS = 3000L;

    private final View root;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean offline;
    private int active;
    private boolean showSending;
    private final Runnable sendingRunnable = () -> { showSending = true; render(); };

    public OutboxBannerController(View root, LifecycleOwner owner, String chatId) {
        this.root = root;
        if (chatId != null && owner != null) {
            AppDatabase.getInstance(root.getContext().getApplicationContext())
                    .outboxOperationDao().observeActiveCountForChat(chatId)
                    .observe(owner, n -> { active = n == null ? 0 : n; armSendingTimer(); render(); });
        }
    }

    public void setOffline(boolean offline) {
        this.offline = offline;
        armSendingTimer();
        render();
    }

    private void armSendingTimer() {
        handler.removeCallbacks(sendingRunnable);
        showSending = false;
        if (active > 0 && !offline) handler.postDelayed(sendingRunnable, SENDING_DELAY_MS);
    }

    private void render() {
        boolean show = offline || (showSending && active > 0);
        TextView banner = root.findViewById(R.id.tv_offline_banner);
        if (!show) {
            // Don't force-inflate just to hide it — if it was never shown yet, it's already gone.
            if (banner != null) banner.setVisibility(View.GONE);
            return;
        }
        if (banner == null) {
            View stub = root.findViewById(R.id.stub_offline_banner);
            if (stub instanceof ViewStub) banner = (TextView) ((ViewStub) stub).inflate();
        }
        if (banner == null) return;
        android.content.Context c = banner.getContext();
        if (offline) {
            banner.setText(active > 0
                    ? c.getString(R.string.outbox_banner_offline_waiting, active)
                    : c.getString(R.string.outbox_banner_offline));
        } else {
            banner.setText(c.getString(R.string.outbox_banner_sending, active));
        }
        banner.setVisibility(View.VISIBLE);
    }
}
