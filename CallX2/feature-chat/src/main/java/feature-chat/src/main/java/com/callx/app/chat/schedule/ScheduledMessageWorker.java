package com.callx.app.chat.schedule;

  import android.content.Context;
  import androidx.annotation.NonNull;
  import androidx.work.Worker;
  import androidx.work.WorkerParameters;
  import com.google.firebase.database.FirebaseDatabase;
  import java.util.HashMap;
  import java.util.Map;
  import java.util.UUID;

  /**
   * ScheduledMessageWorker — Fires when WorkManager delay completes.
   * Sends the scheduled message to Firebase.
   */
  public class ScheduledMessageWorker extends Worker {

      public ScheduledMessageWorker(@NonNull Context ctx, @NonNull WorkerParameters p) {
          super(ctx, p);
      }

      @NonNull @Override public Result doWork() {
          try {
              String chatId = getInputData().getString("chatId");
              String text   = getInputData().getString("text");
              String myUid  = getInputData().getString("myUid");
              if (chatId == null || text == null || myUid == null) return Result.failure();

              String msgId = UUID.randomUUID().toString().replace("-", "");
              Map<String, Object> msg = new HashMap<>();
              msg.put("id", msgId); msg.put("senderId", myUid);
              msg.put("text", text); msg.put("type", "text");
              msg.put("timestamp", System.currentTimeMillis());
              msg.put("status", "sent"); msg.put("scheduled", true);

              FirebaseDatabase.getInstance()
                  .getReference("chats").child(chatId).child("messages").child(msgId)
                  .setValue(msg);
              return Result.success();
          } catch (Exception e) { return Result.retry(); }
      }
  }