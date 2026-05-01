package com.callx.app;
import android.app.Activity;
import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import com.callx.app.activities.AuthActivity;
import com.callx.app.activities.LockScreenActivity;
import com.callx.app.utils.AppLockManager;
import com.callx.app.utils.Constants;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
public class CallxApp extends Application {
    private static final String TAG = "CallxApp";
    private static int sActivityRefs = 0;
    private static String sMyPhotoUrl = "";
    /** True when at least one activity is in started state (app visible). */
    public static boolean isAppInForeground() { return sActivityRefs > 0; }
    public static String getMyPhotoUrlCached()  { return sMyPhotoUrl; }
    @Override
    public void onCreate() {
        super.onCreate();
        try {
            FirebaseDatabase.getInstance(Constants.DB_URL)
                .setPersistenceEnabled(true);
        } catch (Exception e) {
            Log.w(TAG, "Persistence already enabled: " + e.getMessage());
        }
        createChannels();
        registerForegroundTracking();
        cacheMyPhotoUrl();
    }
    /** Track foreground state + trigger app lock on foreground return. */
    private void registerForegroundTracking() {
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity a, Bundle s) {}
            @Override public void onActivityStarted(Activity a)  { sActivityRefs++; }
            @Override public void onActivityResumed(Activity a) {
                // Show lock screen when app comes to foreground
                // (but not for the lock/auth screens themselves)
                if (!(a instanceof LockScreenActivity)
                    && !(a instanceof AuthActivity)) {
                    AppLockManager lm = new AppLockManager(a);
                    if (lm.isLockEnabled() && !LockScreenActivity.isUnlocked()) {
                        a.startActivity(
                            new Intent(a, LockScreenActivity.class));
                    }
                }
            }
            @Override public void onActivityPaused(Activity a) {}
            @Override public void onActivityStopped(Activity a) {
                if (sActivityRefs > 0) sActivityRefs--;
                // Reset unlock state when app goes fully to background
                if (sActivityRefs == 0) LockScreenActivity.resetUnlock();
            }
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            @Override public void onActivityDestroyed(Activity a) {}
        });
    }
    private void cacheMyPhotoUrl() {
        try {
            if (FirebaseAuth.getInstance().getCurrentUser() == null) return;
            String uid = FirebaseAuth.getInstance().getCurrentUser().getUid();
            FirebaseDatabase.getInstance(Constants.DB_URL)
                .getReference("users").child(uid).child("photoUrl")
                .addValueEventListener(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot s) {
                        Object v = s.getValue();
                        sMyPhotoUrl = v == null ? "" : String.valueOf(v);
                    }
                    @Override public void onCancelled(DatabaseError e) {}
                });
        } catch (Exception ignored) {}
    }
    private void createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm =
            (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel calls = new NotificationChannel(
            Constants.CHANNEL_CALLS, "Incoming Calls",
            NotificationManager.IMPORTANCE_HIGH);
        AudioAttributes attrs = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build();
        calls.setSound(
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), attrs);
        calls.enableVibration(true);
        calls.setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(calls);
        NotificationChannel incomingChan = new NotificationChannel(
            Constants.CHANNEL_CALLS_INCOMING, "Incoming Ring",
            NotificationManager.IMPORTANCE_HIGH);
        incomingChan.setSound(
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), attrs);
        incomingChan.enableVibration(true);
        incomingChan.setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(incomingChan);
        NotificationChannel msgs = new NotificationChannel(
            Constants.CHANNEL_MESSAGES, "Messages",
            NotificationManager.IMPORTANCE_HIGH);
        msgs.enableVibration(true);
        nm.createNotificationChannel(msgs);
        NotificationChannel groups = new NotificationChannel(
            Constants.CHANNEL_GROUPS, "Group Messages",
            NotificationManager.IMPORTANCE_HIGH);
        groups.enableVibration(true);
        groups.enableLights(true);
        groups.setShowBadge(true);
        groups.setLockscreenVisibility(android.app.Notification.VISIBILITY_PRIVATE);
        nm.createNotificationChannel(groups);
        NotificationChannel groupsMuted = new NotificationChannel(
            Constants.CHANNEL_GROUPS_MUTED, "Group Messages (Muted)",
            NotificationManager.IMPORTANCE_LOW);
        groupsMuted.enableVibration(false);
        groupsMuted.setSound(null, null);
        groupsMuted.setShowBadge(false);
        groupsMuted.setLockscreenVisibility(android.app.Notification.VISIBILITY_PRIVATE);
        nm.createNotificationChannel(groupsMuted);
        NotificationChannel status = new NotificationChannel(
            Constants.CHANNEL_STATUS, "Status / Story",
            NotificationManager.IMPORTANCE_DEFAULT);
        nm.createNotificationChannel(status);
        NotificationChannel reqs = new NotificationChannel(
            Constants.CHANNEL_REQUESTS, "Contact Requests",
            NotificationManager.IMPORTANCE_HIGH);
        reqs.enableVibration(true);
        reqs.setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(reqs);
        NotificationChannel block = new NotificationChannel(
            Constants.CHANNEL_BLOCK, "Blocked Senders",
            NotificationManager.IMPORTANCE_HIGH);
        block.enableVibration(false);
        block.setSound(null, null);
        block.setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(block);
        NotificationChannel muted = new NotificationChannel(
            Constants.CHANNEL_MUTED, "Muted Conversations",
            NotificationManager.IMPORTANCE_LOW);
        muted.enableVibration(false);
        muted.setSound(null, null);
        muted.setLockscreenVisibility(android.app.Notification.VISIBILITY_PRIVATE);
        nm.createNotificationChannel(muted);
    }
}
