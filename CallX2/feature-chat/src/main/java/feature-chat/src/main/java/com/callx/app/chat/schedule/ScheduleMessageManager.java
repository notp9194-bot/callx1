package com.callx.app.chat.schedule;

  import android.content.Context;
  import androidx.work.Data;
  import androidx.work.OneTimeWorkRequest;
  import androidx.work.WorkManager;
  import java.util.concurrent.TimeUnit;

  /**
   * ScheduleMessageManager — Schedule a chat message for future delivery.
   *
   * Uses WorkManager OneTimeWorkRequest with initial delay.
   * Max schedule: 7 days in future.
   *
   * Usage:
   *   ScheduleMessageManager.schedule(ctx, chatId, text, sendAtMs)
   */
  public class ScheduleMessageManager {

      public static void schedule(Context ctx, String chatId, String text,
                                  String myUid, long sendAtMs) {
          long delayMs = sendAtMs - System.currentTimeMillis();
          if (delayMs < 0) delayMs = 0;

          Data inputData = new Data.Builder()
              .putString("chatId", chatId)
              .putString("text", text)
              .putString("myUid", myUid)
              .build();

          OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(ScheduledMessageWorker.class)
              .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
              .setInputData(inputData)
              .addTag("scheduled_msg_" + chatId)
              .build();

          WorkManager.getInstance(ctx).enqueue(work);
      }

      public static void cancelAll(Context ctx, String chatId) {
          WorkManager.getInstance(ctx).cancelAllWorkByTag("scheduled_msg_" + chatId);
      }
  }