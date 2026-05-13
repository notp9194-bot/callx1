package com.callx.app.services;

  import android.content.BroadcastReceiver;
  import android.content.Context;
  import android.content.Intent;
  import android.app.NotificationManager;
  import android.os.Build;
  import com.callx.app.utils.QuietHoursManager;

  /**
   * QuietHoursReceiver — Triggered by AlarmManager at DND start/end times.
   *
   * On DND_START: optionally enables system DND if user granted Do Not Disturb access.
   * On DND_END:   lifts DND restriction.
   * Also self-reschedules for next day.
   */
  public class QuietHoursReceiver extends BroadcastReceiver {

      @Override
      public void onReceive(Context context, Intent intent) {
          if (intent == null || intent.getAction() == null) return;
          String action = intent.getAction();
          QuietHoursManager mgr = new QuietHoursManager(context);

          if (QuietHoursManager.ACTION_DND_START.equals(action)) {
              applySystemDnd(context, true);
              // Reschedule for next day
              if (mgr.isEnabled()) {
                  mgr.setStartTime(mgr.getStartHour(), mgr.getStartMinute());
              }
          } else if (QuietHoursManager.ACTION_DND_END.equals(action)) {
              applySystemDnd(context, false);
              if (mgr.isEnabled()) {
                  mgr.setEndTime(mgr.getEndHour(), mgr.getEndMinute());
              }
          } else if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
              // Reschedule after reboot
              if (mgr.isEnabled()) {
                  mgr.setEnabled(true); // triggers reschedule
              }
          }
      }

      private void applySystemDnd(Context ctx, boolean enable) {
          if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
          NotificationManager nm = (NotificationManager)
              ctx.getSystemService(Context.NOTIFICATION_SERVICE);
          if (nm == null || !nm.isNotificationPolicyAccessGranted()) return;
          int filter = enable
              ? NotificationManager.INTERRUPTION_FILTER_PRIORITY
              : NotificationManager.INTERRUPTION_FILTER_ALL;
          try { nm.setInterruptionFilter(filter); } catch (Exception ignored) {}
      }
  }
  