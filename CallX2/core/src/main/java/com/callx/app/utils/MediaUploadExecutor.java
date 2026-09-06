package com.callx.app.utils;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * MediaUploadExecutor — one shared, app-lifetime, never-shut-down bounded
 * pool for CloudinaryUploader#upload() bodies (image/video/audio/gif/
 * sticker/file uploads, from chat, reels, status, and elsewhere).
 *
 * PERF FIX: CloudinaryUploader previously did `new Thread(() -> {...}).start()`
 * per call. That's fine for a single send, but a multi-select group send
 * (see ChatMediaController#rawUploadGroupItem) fires one upload per picked
 * item essentially at once — 15-20 photos selected means 15-20 raw threads
 * spun up simultaneously, each holding a full compressed image/video byte[]
 * in memory AND competing for the same radio/CPU at once. Unbounded thread
 * creation under that burst is exactly the kind of thing that causes GC
 * pressure and jank on mid-range devices, without actually uploading any
 * faster (uplink bandwidth is the real bottleneck, not thread count).
 *
 * Fix: one shared, bounded pool — same "shared, app-lifetime, never
 * shutdown" idiom as ChatIoExecutor/AppBgExecutor/E2eeDecryptExecutor.
 * Sized at 4: matches WhatsApp's own observed concurrent-upload cap and
 * leaves headroom for other network work (message sync, presence) to not
 * starve behind a big media batch.
 */
public final class MediaUploadExecutor {

    private static final Executor INSTANCE = Executors.newFixedThreadPool(4);

    private MediaUploadExecutor() {}

    public static Executor get() {
        return INSTANCE;
    }

    public static void execute(Runnable task) {
        INSTANCE.execute(task);
    }
}
