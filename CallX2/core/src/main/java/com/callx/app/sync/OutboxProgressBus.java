package com.callx.app.sync;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Upload progress for media that the background outbox worker is sending (the chat screen may be
 * open while the worker, not the foreground controller, owns the upload). Listeners are always
 * called on the main thread.
 */
public final class OutboxProgressBus {
    public interface Listener {
        void onProgress(String messageId, int percent);
        void onFinished(String messageId);
    }

    private static final CopyOnWriteArraySet<Listener> LISTENERS = new CopyOnWriteArraySet<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private OutboxProgressBus() {}

    public static void add(Listener l)    { if (l != null) LISTENERS.add(l); }
    public static void remove(Listener l) { if (l != null) LISTENERS.remove(l); }

    public static void publish(String messageId, int percent) {
        if (messageId == null || LISTENERS.isEmpty()) return;
        MAIN.post(() -> { for (Listener l : LISTENERS) l.onProgress(messageId, percent); });
    }

    public static void finish(String messageId) {
        if (messageId == null || LISTENERS.isEmpty()) return;
        MAIN.post(() -> { for (Listener l : LISTENERS) l.onFinished(messageId); });
    }
}
