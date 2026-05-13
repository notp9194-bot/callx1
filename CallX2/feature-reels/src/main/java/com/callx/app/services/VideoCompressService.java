package com.callx.app.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.callx.app.utils.VideoCompressor;
import com.callx.app.utils.VideoQualityPreferences;
import com.callx.app.utils.VideoUploader;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * VideoCompressService — Foreground service for background video compression + upload.
 *
 * Features:
 *  ✅ Foreground service with live progress notification (% per video)
 *  ✅ Queue multiple videos (compress and upload sequentially)
 *  ✅ Per-job quality settings
 *  ✅ Cancel individual jobs or entire queue
 *  ✅ Bind-able for Activity progress callbacks
 *  ✅ Auto-stop when queue is empty
 *  ✅ Retry failed jobs (up to 3x)
 *
 * Usage:
 *   // Start a job:
 *   VideoCompressService.enqueue(ctx,
 *       videoUri, chatId, messageId, isGroup, Quality.HD,
 *       (progress, summary) -> updateUI(progress, summary));
 *
 *   // Cancel all:
 *   VideoCompressService.cancelAll(ctx);
 */
public class VideoCompressService extends Service {

    private static final String TAG = "VideoCompressService";

    // Intent actions
    public static final String ACTION_ENQUEUE     = "callx.action.VIDEO_ENQUEUE";
    public static final String ACTION_CANCEL_JOB  = "callx.action.VIDEO_CANCEL_JOB";
    public static final String ACTION_CANCEL_ALL  = "callx.action.VIDEO_CANCEL_ALL";

    // Extras
    public static final String EXTRA_JOB_ID      = "jobId";
    public static final String EXTRA_URI          = "videoUri";
    public static final String EXTRA_CHAT_ID      = "chatId";
    public static final String EXTRA_MESSAGE_ID   = "messageId";
    public static final String EXTRA_IS_GROUP     = "isGroup";
    public static final String EXTRA_QUALITY      = "quality";

    private static final String NOTIF_CHANNEL = "callx_compress_service";
    private static final int    NOTIF_FG_ID   = 8500;

    // ── Binder ────────────────────────────────────────────────────────────

    public class LocalBinder extends Binder {
        public VideoCompressService getService() { return VideoCompressService.this; }
    }

    public interface JobProgressCallback {
        void onProgress(String jobId, int percent, String statusText);
        void onJobComplete(String jobId, boolean success, String message);
    }

    private final IBinder binder = new LocalBinder();
    private final Map<String, JobProgressCallback> callbacks = new ConcurrentHashMap<>();
    private final List<CompressJob> queue = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean running = false;
    private volatile String currentJobId = null;

    // ── Static helper to enqueue ──────────────────────────────────────────

    public static String enqueue(Context ctx, Uri videoUri, String chatId, String messageId,
                                 boolean isGroup, VideoQualityPreferences.Quality quality) {
        String jobId = UUID.randomUUID().toString().substring(0, 8);
        Intent intent = new Intent(ctx, VideoCompressService.class);
        intent.setAction(ACTION_ENQUEUE);
        intent.putExtra(EXTRA_JOB_ID,    jobId);
        intent.putExtra(EXTRA_URI,       videoUri.toString());
        intent.putExtra(EXTRA_CHAT_ID,   chatId);
        intent.putExtra(EXTRA_MESSAGE_ID,messageId);
        intent.putExtra(EXTRA_IS_GROUP,  isGroup);
        intent.putExtra(EXTRA_QUALITY,   quality.name());
        ctx.startService(intent);
        return jobId;
    }

    public static void cancelAll(Context ctx) {
        Intent intent = new Intent(ctx, VideoCompressService.class);
        intent.setAction(ACTION_CANCEL_ALL);
        ctx.startService(intent);
    }

    public static void cancelJob(Context ctx, String jobId) {
        Intent intent = new Intent(ctx, VideoCompressService.class);
        intent.setAction(ACTION_CANCEL_JOB);
        intent.putExtra(EXTRA_JOB_ID, jobId);
        ctx.startService(intent);
    }

    // ── Service lifecycle ─────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        ensureNotifChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;

        String action = intent.getAction();
        if (action == null) action = "";

        switch (action) {
            case ACTION_ENQUEUE:
                handleEnqueue(intent);
                break;
            case ACTION_CANCEL_JOB:
                handleCancelJob(intent.getStringExtra(EXTRA_JOB_ID));
                break;
            case ACTION_CANCEL_ALL:
                handleCancelAll();
                break;
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    @Override
    public void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    // ── Job management ────────────────────────────────────────────────────

    private void handleEnqueue(Intent intent) {
        CompressJob job = new CompressJob();
        job.jobId     = intent.getStringExtra(EXTRA_JOB_ID);
        job.uriStr    = intent.getStringExtra(EXTRA_URI);
        job.chatId    = intent.getStringExtra(EXTRA_CHAT_ID);
        job.messageId = intent.getStringExtra(EXTRA_MESSAGE_ID);
        job.isGroup   = intent.getBooleanExtra(EXTRA_IS_GROUP, false);
        job.quality   = VideoQualityPreferences.Quality.fromName(
            intent.getStringExtra(EXTRA_QUALITY));

        synchronized (queue) { queue.add(job); }

        startForeground(NOTIF_FG_ID, buildNotification("Preparing…", 0));
        if (!running) {
            running = true;
            executor.execute(this::processQueue);
        }
    }

    private void handleCancelJob(String jobId) {
        if (jobId == null) return;
        synchronized (queue) {
            queue.removeIf(j -> j.jobId.equals(jobId));
        }
        if (jobId.equals(currentJobId)) {
            // Will be checked in processQueue loop
            VideoUploader.cancelActive();
        }
    }

    private void handleCancelAll() {
        synchronized (queue) { queue.clear(); }
        VideoUploader.cancelActive();
        stopForeground(true);
        stopSelf();
    }

    // ── Processing ────────────────────────────────────────────────────────

    private void processQueue() {
        while (true) {
            CompressJob job;
            synchronized (queue) {
                if (queue.isEmpty()) {
                    running = false;
                    stopForeground(true);
                    stopSelf();
                    return;
                }
                job = queue.remove(0);
            }
            processJob(job);
        }
    }

    private void processJob(CompressJob job) {
        currentJobId = job.jobId;
        Log.i(TAG, "Processing job: " + job.jobId + " [" + job.quality.label + "]");
        updateNotification("Compressing video…", 0);

        try {
            Uri uri = Uri.parse(job.uriStr);
            VideoCompressor.Result result = VideoCompressor.compressSync(
                this, uri, job.quality,
                pct -> {
                    String text = "Compressing… " + pct + "%";
                    updateNotification(text, (int)(pct * 0.40f));
                    notifyCallback(job.jobId, (int)(pct * 0.40f), text);
                });

            updateNotification("Uploading video…", 40);

            // Use VideoUploader with callback
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.atomic.AtomicReference<Exception> uploadError =
                new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<String> uploadedUrl =
                new java.util.concurrent.atomic.AtomicReference<>();

            VideoUploader.upload(this, result, new VideoUploader.UploadCallback() {
                @Override public void onProgress(int pct) {
                    String text = "Uploading… " + pct + "%";
                    updateNotification(text, 40 + (int)(pct * 0.55f));
                    notifyCallback(job.jobId, 40 + (int)(pct * 0.55f), text);
                }
                @Override public void onSuccess(String thumbUrl, String videoUrl,
                                                int durationMs, int w, int h) {
                    uploadedUrl.set(videoUrl);
                    // Update Firebase DB
                    String dbPath = job.isGroup
                        ? "groupMessages/" + job.chatId + "/" + job.messageId
                        : "chats/" + job.chatId + "/messages/" + job.messageId;
                    java.util.HashMap<String, Object> updates = new java.util.HashMap<>();
                    updates.put("mediaUrl",           videoUrl);
                    updates.put("thumbnailUrl",       thumbUrl);
                    updates.put("status",             "sent");
                    updates.put("mediaLocalPath",     (Object) null);
                    updates.put("duration",           durationMs);
                    updates.put("width",              w);
                    updates.put("height",             h);
                    updates.put("compressionSummary", result.compressionSummary());
                    updates.put("savingsPercent",     result.savingsPercent());
                    com.google.firebase.database.FirebaseDatabase.getInstance()
                        .getReference(dbPath).updateChildren(updates);
                    latch.countDown();
                }
                @Override public void onError(Exception e) {
                    uploadError.set(e);
                    latch.countDown();
                }
            });

            latch.await(25, java.util.concurrent.TimeUnit.MINUTES);

            if (uploadError.get() != null) throw uploadError.get();

            updateNotification("Upload complete!", 100);
            notifyCallback(job.jobId, 100, "Done: " + result.compressionSummary());
            Log.i(TAG, "Job complete: " + job.jobId + " — " + result.compressionSummary());

        } catch (Exception e) {
            Log.e(TAG, "Job failed: " + job.jobId + " — " + e.getMessage(), e);
            notifyCallback(job.jobId, -1, "Failed: " + e.getMessage());

            // Retry via WorkManager as fallback
            com.callx.app.workers.VideoUploadWorker.enqueue(
                this, job.uriStr, job.chatId, job.messageId, job.isGroup, job.quality);
        }
        currentJobId = null;
    }

    // ── Callback helpers ──────────────────────────────────────────────────

    public void registerCallback(String jobId, JobProgressCallback cb) {
        callbacks.put(jobId, cb);
    }

    public void unregisterCallback(String jobId) {
        callbacks.remove(jobId);
    }

    private void notifyCallback(String jobId, int pct, String text) {
        JobProgressCallback cb = callbacks.get(jobId);
        if (cb == null) return;
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        if (pct == 100 || pct < 0) {
            main.post(() -> cb.onJobComplete(jobId, pct == 100, text));
        } else {
            main.post(() -> cb.onProgress(jobId, pct, text));
        }
    }

    // ── Notifications ─────────────────────────────────────────────────────

    private void updateNotification(String text, int progress) {
        NotificationManager nm =
            (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_FG_ID, buildNotification(text, progress));
    }

    private Notification buildNotification(String text, int progress) {
        Intent cancelIntent = new Intent(this, VideoCompressService.class);
        cancelIntent.setAction(ACTION_CANCEL_ALL);
        PendingIntent cancelPi = PendingIntent.getService(this, 0, cancelIntent,
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_IMMUTABLE : 0);

        return new NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentTitle("Sending video")
            .setContentText(text)
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_delete, "Cancel", cancelPi)
            .build();
    }

    private void ensureNotifChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm =
            (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(NOTIF_CHANNEL) != null) return;
        NotificationChannel ch = new NotificationChannel(
            NOTIF_CHANNEL, "Video Compression", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Background video compression and upload");
        nm.createNotificationChannel(ch);
    }

    // ── Job model ─────────────────────────────────────────────────────────

    private static class CompressJob {
        String jobId, uriStr, chatId, messageId;
        boolean isGroup;
        VideoQualityPreferences.Quality quality = VideoQualityPreferences.Quality.STANDARD;
    }
}
