package com.callx.app.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import com.callx.app.utils.StatusExpiryManager;
import com.callx.app.utils.StatusNotificationHelper;

/**
 * StatusExpiryReceiver — AlarmManager broadcast receiver.
 *
 * Handles:
 *  ACTION_STATUS_EXPIRY_REMINDER → show local notification 2h before expiry
 *  ACTION_EXTEND_STATUS          → extend TTL by another 24h
 */
public class StatusExpiryReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();

        switch (action) {
            case "com.callx.app.ACTION_STATUS_EXPIRY_REMINDER": {
                String statusId   = intent.getStringExtra("statusId");
                String statusText = intent.getStringExtra("statusText");
                String statusType = intent.getStringExtra("statusType");
                if (statusId != null) {
                    StatusNotificationHelper.showExpiryReminder(
                        context, statusId,
                        statusText != null ? statusText : "",
                        statusType != null ? statusType : "text");
                }
                break;
            }
            case "com.callx.app.ACTION_EXTEND_STATUS": {
                String statusId  = intent.getStringExtra("statusId");
                String ownerUid  = intent.getStringExtra("ownerUid");
                if (statusId != null && ownerUid != null) {
                    StatusExpiryManager.extendStatusTtl(ownerUid, statusId, context);
                }
                break;
            }
        }
    }
}
