package com.callx.app.community.worker;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.Data;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.callx.app.chat.R;
import com.callx.app.community.CommunityActivity;

import java.util.concurrent.TimeUnit;

/**
 * v34: WorkManager worker that fires a local notification before a community event.
 *
 * Usage:
 *   EventReminderWorker.schedule(context, communityId, eventId, eventTitle, startTimeMs);
 *
 * The worker fires ~1 hour before the event (or immediately if < 1 hour away).
 * Cancel with: EventReminderWorker.cancel(context, eventId);
 */
public class EventReminderWorker extends Worker {

    public static final String KEY_COMMUNITY_ID = "communityId";
    public static final String KEY_EVENT_ID     = "eventId";
    public static final String KEY_EVENT_TITLE  = "eventTitle";
    public static final String KEY_START_TIME   = "startTimeMs";

    private static final String CHANNEL_ID   = "community_events";
    private static final String CHANNEL_NAME = "Community Events";
    private static final int    NOTIF_BASE   = 9000;
    private static final long   REMIND_MS    = 60 * 60 * 1000L; // 1 hour before

    public EventReminderWorker(@NonNull Context ctx, @NonNull WorkerParameters params) {
        super(ctx, params);
    }

    @NonNull @Override
    public Result doWork() {
        String communityId = getInputData().getString(KEY_COMMUNITY_ID);
        String eventId     = getInputData().getString(KEY_EVENT_ID);
        String title       = getInputData().getString(KEY_EVENT_TITLE);
        long   startTime   = getInputData().getLong(KEY_START_TIME, 0L);

        if (title == null)  title     = "Community Event";
        if (eventId == null) eventId  = "event";

        // Build intent to open the community
        Intent intent = new Intent(getApplicationContext(), CommunityActivity.class);
        intent.putExtra(CommunityActivity.EXTRA_COMMUNITY_ID, communityId);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent pi = PendingIntent.getActivity(
                getApplicationContext(), 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationManager nm = (NotificationManager)
                getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return Result.failure();

        // Create channel (Android 8+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Reminders for upcoming community events");
            nm.createNotificationChannel(ch);
        }

        long remaining = startTime - System.currentTimeMillis();
        String body;
        if (remaining > 0) {
            long mins = remaining / (60 * 1000L);
            if (mins >= 60) body = "Starts in " + (mins / 60) + " hour(s)";
            else            body = "Starts in " + mins + " minute(s)";
        } else {
            body = "Starting now!";
        }

        NotificationCompat.Builder nb = new NotificationCompat.Builder(getApplicationContext(), CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notifications)
                .setContentTitle("🗓 " + title)
                .setContentText(body)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pi);

        nm.notify(NOTIF_BASE + eventId.hashCode(), nb.build());
        return Result.success();
    }

    /**
     * Schedule a reminder ~1 hour before the event.
     * If the event is less than 1 hour away, fire immediately.
     */
    public static void schedule(Context ctx, String communityId, String eventId,
                                String eventTitle, long startTimeMs) {
        long now   = System.currentTimeMillis();
        long delay = startTimeMs - now - REMIND_MS;
        if (delay < 0) delay = 0; // fire immediately if we're already within 1 hour

        Data data = new Data.Builder()
                .putString(KEY_COMMUNITY_ID, communityId)
                .putString(KEY_EVENT_ID, eventId)
                .putString(KEY_EVENT_TITLE, eventTitle)
                .putLong(KEY_START_TIME, startTimeMs)
                .build();

        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(EventReminderWorker.class)
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setInputData(data)
                .addTag(tagFor(eventId))
                .build();

        WorkManager.getInstance(ctx).enqueue(req);
    }

    /** Cancel a previously scheduled reminder. */
    public static void cancel(Context ctx, String eventId) {
        WorkManager.getInstance(ctx).cancelAllWorkByTag(tagFor(eventId));
    }

    private static String tagFor(String eventId) {
        return "event_reminder_" + eventId;
    }
}
