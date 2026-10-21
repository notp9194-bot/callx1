package com.callx.app.conversation;

import androidx.annotation.NonNull;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.LifecycleOwner;

import com.callx.app.sync.OutboxProgressBus;

/**
 * Forwards upload progress of media sent by the background outbox worker to the chat adapter's
 * existing bubble progress ring (onMediaUploadProgress / onMediaUploadFinished) while the screen is started.
 */
public final class OutboxProgressBinder {
    public interface AdapterSource { MessagePagingAdapter get(); }

    private OutboxProgressBinder() {}

    public static void bind(@NonNull LifecycleOwner owner, @NonNull AdapterSource source) {
        final OutboxProgressBus.Listener listener = new OutboxProgressBus.Listener() {
            @Override public void onProgress(String messageId, int percent) {
                MessagePagingAdapter a = source.get();
                if (a != null) a.onMediaUploadProgress(messageId, percent,
                        MediaUploadProgressTracker.STAGE_UPLOAD, percent);
            }
            @Override public void onFinished(String messageId) {
                MessagePagingAdapter a = source.get();
                if (a != null) a.onMediaUploadFinished(messageId);
            }
        };
        owner.getLifecycle().addObserver((LifecycleEventObserver) (src, event) -> {
            if (event == Lifecycle.Event.ON_START) OutboxProgressBus.add(listener);
            else if (event == Lifecycle.Event.ON_STOP) OutboxProgressBus.remove(listener);
        });
    }
}
