package com.callx.app.chat.slowmode;

  import android.content.Context;
  import android.content.SharedPreferences;
  import com.google.firebase.database.FirebaseDatabase;
  import java.util.HashMap;
  import java.util.Map;

  /**
   * SlowModeManager — Limits how often group members can send messages.
   *
   * Firebase: groups/{groupId}/slowModeSeconds = 0/30/60/300/3600 (0 = off)
   * Locally tracks lastSentAt per group to enforce cooldown.
   *
   * Usage:
   *   SlowModeManager.setSlowMode(groupId, 60)  // admin only
   *   SlowModeManager.canSend(ctx, groupId, slowModeSec) // returns true/false
   *   SlowModeManager.recordSend(ctx, groupId)
   */
  public class SlowModeManager {

      public static final int[] OPTIONS_SECONDS = {0, 30, 60, 300, 3600};
      public static final String[] OPTIONS_LABELS = {"Off","30s","1 min","5 min","1 hour"};
      private static final String PREF = "slow_mode_last_sent";

      public static void setSlowMode(String groupId, int seconds) {
          Map<String, Object> update = new HashMap<>();
          update.put("slowModeSeconds", seconds);
          FirebaseDatabase.getInstance()
              .getReference("groups").child(groupId).updateChildren(update);
      }

      public static boolean canSend(Context ctx, String groupId, int slowModeSec) {
          if (slowModeSec == 0) return true;
          SharedPreferences prefs = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
          long lastSent = prefs.getLong(groupId, 0L);
          long elapsed  = (System.currentTimeMillis() - lastSent) / 1000;
          return elapsed >= slowModeSec;
      }

      public static long remainingSeconds(Context ctx, String groupId, int slowModeSec) {
          if (slowModeSec == 0) return 0;
          SharedPreferences prefs = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
          long lastSent = prefs.getLong(groupId, 0L);
          long elapsed  = (System.currentTimeMillis() - lastSent) / 1000;
          return Math.max(0, slowModeSec - elapsed);
      }

      public static void recordSend(Context ctx, String groupId) {
          ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
             .edit().putLong(groupId, System.currentTimeMillis()).apply();
      }
  }