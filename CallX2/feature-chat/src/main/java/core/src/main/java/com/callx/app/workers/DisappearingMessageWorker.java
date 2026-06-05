package com.callx.app.workers;

  import android.content.Context;
  import androidx.annotation.NonNull;
  import androidx.work.Worker;
  import androidx.work.WorkerParameters;
  import com.callx.app.db.AppDatabase;
  import com.callx.app.db.entity.MessageEntity;
  import com.google.firebase.database.FirebaseDatabase;
  import java.util.List;

  /**
   * DisappearingMessageWorker — Runs every hour via WorkManager.
   * Deletes messages whose expiresAt <= now from Room DB + Firebase.
   *
   * Schedule:
   *   PeriodicWorkRequest.Builder(DisappearingMessageWorker.class, 1, TimeUnit.HOURS)
   */
  public class DisappearingMessageWorker extends Worker {

      public DisappearingMessageWorker(@NonNull Context ctx, @NonNull WorkerParameters params) {
          super(ctx, params);
      }

      @NonNull @Override
      public Result doWork() {
          try {
              AppDatabase db = AppDatabase.getInstance(getApplicationContext());
              long now = System.currentTimeMillis();

              // Get all expired messages from Room
              List<MessageEntity> expired = db.messageDao().getExpiredMessages(now);
              if (expired.isEmpty()) return Result.success();

              for (MessageEntity msg : expired) {
                  // Delete from Firebase
                  if (msg.chatId != null && msg.id != null) {
                      FirebaseDatabase.getInstance()
                          .getReference("chats")
                          .child(msg.chatId)
                          .child("messages")
                          .child(msg.id)
                          .removeValue();
                  }
                  // Delete from Room
                  db.messageDao().deleteById(msg.id);
              }
              return Result.success();
          } catch (Exception e) {
              return Result.retry();
          }
      }
  }