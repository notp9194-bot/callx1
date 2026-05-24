package com.callx.app.notifications;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * ReelNotificationBootReceiver — Reschedules reel notification polling after device reboot.
 *
 * WorkManager periodic tasks survive process death but NOT a cold device reboot.
 * This receiver catches BOOT_COMPLETED and MY_PACKAGE_REPLACED to re-enqueue the
 * periodic worker so reel notifications resume automatically after reboot.
 *
 * Registered in AndroidManifest with:
 *   <action android:name="android.intent.action.BOOT_COMPLETED"/>
 *   <action android:name="android.intent.action.MY_PACKAGE_REPLACED"/>
 *   <action android:name="android.intent.action.QUICKBOOT_POWERON"/>  (HTC)
 *   <action android:name="com.htc.intent.action.QUICKBOOT_POWERON"/> (HTC)
 */
public class ReelNotificationBootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;
        switch (action) {
            case Intent.ACTION_BOOT_COMPLETED:
            case Intent.ACTION_MY_PACKAGE_REPLACED:
            case "android.intent.action.QUICKBOOT_POWERON":
            case "com.htc.intent.action.QUICKBOOT_POWERON":
                ReelNotificationChannelManager.ensureChannels(ctx);
                ReelNotificationWorker.schedule(ctx);
                break;
        }
    }
}
