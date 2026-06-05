package com.callx.app.chat.disappearing;

  import android.content.Context;
  import com.google.firebase.database.DatabaseReference;
  import com.google.firebase.database.FirebaseDatabase;
  import java.util.HashMap;
  import java.util.Map;

  /**
   * DisappearingMessageManager — Auto-delete messages after set timer.
   *
   * Timers: OFF | 24h | 7d | 90d
   * Firebase path: chats/{chatId}/disappearTimer = duration_ms (0 = off)
   *
   * On send: message.expiresAt = System.currentTimeMillis() + timerMs
   * Worker checks periodically and deletes expired messages.
   */
  public class DisappearingMessageManager {

      public static final long TIMER_OFF  = 0L;
      public static final long TIMER_24H  = 86_400_000L;
      public static final long TIMER_7D   = 604_800_000L;
      public static final long TIMER_90D  = 7_776_000_000L;

      public static final String[] LABELS = {"Off", "24 hours", "7 days", "90 days"};
      public static final long[]   VALUES = {TIMER_OFF, TIMER_24H, TIMER_7D, TIMER_90D};

      private final String chatId;
      private final DatabaseReference chatRef;
      private long currentTimer = TIMER_OFF;

      public DisappearingMessageManager(String chatId) {
          this.chatId = chatId;
          this.chatRef = FirebaseDatabase.getInstance()
                  .getReference("chats").child(chatId);
      }

      /** Set timer for both participants */
      public void setTimer(long durationMs) {
          this.currentTimer = durationMs;
          Map<String, Object> update = new HashMap<>();
          update.put("disappearTimer", durationMs);
          update.put("disappearUpdatedAt", System.currentTimeMillis());
          chatRef.updateChildren(update);
      }

      /** Returns expiresAt timestamp to attach to a new message. 0 = never */
      public long getExpiresAt() {
          if (currentTimer == TIMER_OFF) return 0L;
          return System.currentTimeMillis() + currentTimer;
      }

      public long getCurrentTimer() { return currentTimer; }
      public void setCurrentTimer(long t) { this.currentTimer = t; }

      public static String formatLabel(long ms) {
          for (int i = 0; i < VALUES.length; i++) {
              if (VALUES[i] == ms) return LABELS[i];
          }
          return "Off";
      }
  }