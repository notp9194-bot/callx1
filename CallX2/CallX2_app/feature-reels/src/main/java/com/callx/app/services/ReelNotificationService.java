package com.callx.app.services;

import com.callx.app.notifications.ReelFCMNotificationHandler;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import androidx.annotation.Nullable;

/**
 * ReelNotificationService — DEPRECATED in v15.
 *
 * Previously used as a foreground service to show reel notifications in
 * background/killed state. Replaced by direct Executor approach in
 * ReelFCMNotificationHandler (same pattern as CallxMessagingService.showMessage).
 *
 * Kept as a stub to avoid manifest/build errors from existing references.
 * Safe to delete in a future cleanup pass once all references are removed.
 */
public class ReelNotificationService extends Service {

    public static final String EXTRA_TYPE         = "reel_notif_type";
    public static final String EXTRA_SENDER_NAME  = "sender_name";
    public static final String EXTRA_SENDER_PHOTO = "sender_photo";
    public static final String EXTRA_REEL_ID      = "reel_id";
    public static final String EXTRA_REEL_THUMB   = "reel_thumb";
    public static final String EXTRA_COMMENT_TEXT = "comment_text";
    public static final String EXTRA_COMMENT_ID   = "comment_id";
    public static final String EXTRA_LIKE_COUNT   = "like_count";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // No-op — notifications are now shown directly from ReelFCMNotificationHandler
        stopSelf(startId);
        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }
}
