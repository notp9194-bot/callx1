package com.callx.app.notifications;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Re-schedules YouTubeNotificationWorker after device reboot.
 * WorkManager normally survives reboots on its own, but this receiver
 * ensures the schedule is restored even on devices that wipe work on reboot.
 */
public class YouTubeNotificationBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
            Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            YouTubeNotificationWorker.schedule(ctx);
        }
    }
}
