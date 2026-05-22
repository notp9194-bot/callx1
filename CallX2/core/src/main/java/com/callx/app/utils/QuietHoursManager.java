package com.callx.app.utils;

  import android.app.AlarmManager;
  import com.callx.app.services.QuietHoursReceiver;
  import android.app.NotificationManager;
  import android.app.PendingIntent;
  import android.content.Context;
  import android.content.Intent;
  import android.content.SharedPreferences;
  import android.os.Build;
  import java.util.Calendar;

  /**
   * QuietHoursManager — Production DND / Quiet Hours implementation.
   *
   * Features:
   *  ✅ Enable/disable quiet hours globally
   *  ✅ Custom start + end time (hour:minute)
   *  ✅ Per-category overrides (calls always break DND, etc.)
   *  ✅ Check if currently in quiet hours
   *  ✅ Schedule AlarmManager to enforce DND transitions
   *  ✅ Persistent via SharedPreferences
   *  ✅ Works across app restarts and device reboots
   */
  public class QuietHoursManager {

      private static final String PREFS              = "callx_quiet_hours";
      private static final String KEY_ENABLED        = "quiet_hours_enabled";
      private static final String KEY_START_HOUR     = "quiet_start_hour";
      private static final String KEY_START_MIN      = "quiet_start_min";
      private static final String KEY_END_HOUR       = "quiet_end_hour";
      private static final String KEY_END_MIN        = "quiet_end_min";
      private static final String KEY_ALLOW_CALLS    = "quiet_allow_calls";
      private static final String KEY_ALLOW_MESSAGES = "quiet_allow_messages";
      private static final String KEY_ALLOW_GROUPS   = "quiet_allow_groups";
      private static final String KEY_ALLOW_REELS    = "quiet_allow_reels";

      public static final String ACTION_DND_START = "com.callx.app.ACTION_DND_START";
      public static final String ACTION_DND_END   = "com.callx.app.ACTION_DND_END";

      private final SharedPreferences prefs;
      private final Context ctx;

      public QuietHoursManager(Context ctx) {
          this.ctx   = ctx.getApplicationContext();
          this.prefs = this.ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
      }

      // ── Getters / Setters ─────────────────────────────────────────────────

      public boolean isEnabled()        { return prefs.getBoolean(KEY_ENABLED, false); }
      public int  getStartHour()        { return prefs.getInt(KEY_START_HOUR, 22); }
      public int  getStartMinute()      { return prefs.getInt(KEY_START_MIN, 0); }
      public int  getEndHour()          { return prefs.getInt(KEY_END_HOUR, 7); }
      public int  getEndMinute()        { return prefs.getInt(KEY_END_MIN, 0); }
      public boolean isCallsAllowed()   { return prefs.getBoolean(KEY_ALLOW_CALLS, true); }
      public boolean isMessagesAllowed(){ return prefs.getBoolean(KEY_ALLOW_MESSAGES, false); }
      public boolean isGroupsAllowed()  { return prefs.getBoolean(KEY_ALLOW_GROUPS, false); }
      public boolean isReelsAllowed()   { return prefs.getBoolean(KEY_ALLOW_REELS, false); }

      public void setEnabled(boolean v)         { prefs.edit().putBoolean(KEY_ENABLED, v).apply(); reschedule(); }
      public void setStartTime(int h, int m)    { prefs.edit().putInt(KEY_START_HOUR, h).putInt(KEY_START_MIN, m).apply(); reschedule(); }
      public void setEndTime(int h, int m)      { prefs.edit().putInt(KEY_END_HOUR, h).putInt(KEY_END_MIN, m).apply(); reschedule(); }
      public void setCallsAllowed(boolean v)    { prefs.edit().putBoolean(KEY_ALLOW_CALLS, v).apply(); }
      public void setMessagesAllowed(boolean v) { prefs.edit().putBoolean(KEY_ALLOW_MESSAGES, v).apply(); }
      public void setGroupsAllowed(boolean v)   { prefs.edit().putBoolean(KEY_ALLOW_GROUPS, v).apply(); }
      public void setReelsAllowed(boolean v)    { prefs.edit().putBoolean(KEY_ALLOW_REELS, v).apply(); }

      /**
       * Returns true if current time falls within quiet hours.
       */
      public boolean isQuietNow() {
          if (!isEnabled()) return false;
          Calendar now = Calendar.getInstance();
          int nowMins   = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
          int startMins = getStartHour() * 60 + getStartMinute();
          int endMins   = getEndHour()   * 60 + getEndMinute();
          if (startMins <= endMins) {
              // Same-day window: e.g. 09:00–17:00
              return nowMins >= startMins && nowMins < endMins;
          } else {
              // Overnight window: e.g. 22:00–07:00
              return nowMins >= startMins || nowMins < endMins;
          }
      }

      /**
       * Returns true if a notification of the given category should be suppressed right now.
       */
      public boolean shouldSuppress(String category) {
          if (!isQuietNow()) return false;
          switch (category) {
              case "call":    return !isCallsAllowed();
              case "message": return !isMessagesAllowed();
              case "group":   return !isGroupsAllowed();
              case "reel":    return !isReelsAllowed();
              default:        return true; // suppress unknown categories
          }
      }

      /** Human-readable summary, e.g. "10:00 PM – 7:00 AM" */
      public String getSummary() {
          return fmt(getStartHour(), getStartMinute()) + " – " + fmt(getEndHour(), getEndMinute());
      }

      private String fmt(int h, int m) {
          String ampm = h >= 12 ? "PM" : "AM";
          int h12 = h % 12;
          if (h12 == 0) h12 = 12;
          return String.format("%d:%02d %s", h12, m, ampm);
      }

      // ── AlarmManager scheduling ───────────────────────────────────────────

      private void reschedule() {
          cancelAlarms();
          if (!isEnabled()) return;
          scheduleAlarm(ACTION_DND_START, getStartHour(), getStartMinute());
          scheduleAlarm(ACTION_DND_END,   getEndHour(),   getEndMinute());
      }

      private void scheduleAlarm(String action, int hour, int minute) {
          AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
          if (am == null) return;
          Calendar cal = Calendar.getInstance();
          cal.set(Calendar.HOUR_OF_DAY, hour);
          cal.set(Calendar.MINUTE,      minute);
          cal.set(Calendar.SECOND,      0);
          cal.set(Calendar.MILLISECOND, 0);
          if (cal.getTimeInMillis() <= System.currentTimeMillis()) {
              cal.add(Calendar.DAY_OF_YEAR, 1);
          }
          Intent intent = new Intent(ctx, QuietHoursReceiver.class).setAction(action);
          PendingIntent pi = PendingIntent.getBroadcast(ctx,
              action.hashCode(), intent,
              PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
              am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi);
          } else {
              am.setExact(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi);
          }
      }

      private void cancelAlarms() {
          AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
          if (am == null) return;
          for (String action : new String[]{ACTION_DND_START, ACTION_DND_END}) {
              Intent intent = new Intent(ctx, QuietHoursReceiver.class).setAction(action);
              PendingIntent pi = PendingIntent.getBroadcast(ctx,
                  action.hashCode(), intent,
                  PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
              if (pi != null) am.cancel(pi);
          }
      }
  }
  