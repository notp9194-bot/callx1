package com.callx.app.notifications;

  import android.content.Context;
  import androidx.annotation.NonNull;
  import androidx.work.Constraints;
  import androidx.work.ExistingPeriodicWorkPolicy;
  import androidx.work.NetworkType;
  import androidx.work.PeriodicWorkRequest;
  import androidx.work.WorkManager;
  import androidx.work.Worker;
  import androidx.work.WorkerParameters;
  import com.callx.app.utils.XFirebaseUtils;
  import com.google.firebase.auth.FirebaseAuth;
  import com.google.firebase.database.*;
  import java.util.concurrent.TimeUnit;

  /**
   * Background WorkManager worker that polls for new X notifications every 15 minutes.
   *
   * Runs even when the app is killed (WorkManager survives process death).
   * Falls back to Firebase one-shot reads so no persistent connection is needed.
   */
  public class XNotificationWorker extends Worker {

      private static final String WORK_TAG = "x_notification_poll";

      public XNotificationWorker(@NonNull Context ctx, @NonNull WorkerParameters params) {
          super(ctx, params);
      }

      public static void schedule(Context ctx) {
          Constraints constraints = new Constraints.Builder()
              .setRequiredNetworkType(NetworkType.CONNECTED)
              .build();

          PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(
                  XNotificationWorker.class, 15, TimeUnit.MINUTES)
              .setConstraints(constraints)
              .addTag(WORK_TAG)
              .build();

          WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
              WORK_TAG,
              ExistingPeriodicWorkPolicy.KEEP,
              req);
      }

      @NonNull @Override
      public Result doWork() {
          String uid = FirebaseAuth.getInstance().getCurrentUser() != null
              ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;
          if (uid == null) return Result.success();

          // Read unread notification count
          final Object lock = new Object();
          final boolean[] done = {false};

          XFirebaseUtils.xNotificationsRef(uid)
              .orderByChild("read")
              .equalTo(false)
              .limitToLast(10)
              .addListenerForSingleValueEvent(new ValueEventListener() {
                  @Override public void onDataChange(@NonNull DataSnapshot snap) {
                      for (DataSnapshot ds : snap.getChildren()) {
                          String type     = ds.child("type").getValue(String.class);
                          String fromUid  = ds.child("fromUid").getValue(String.class);
                          String fromName = ds.child("fromName").getValue(String.class);
                          String fromPhoto= ds.child("fromPhotoUrl").getValue(String.class);
                          String tweetId  = ds.child("tweetId").getValue(String.class);

                          if (fromName == null) fromName = "Someone";

                          if ("dm".equals(type)) {
                              String convId = ds.child("conversationId").getValue(String.class);
                              XNotificationHelper.postDM(getApplicationContext(),
                                  fromName, fromPhoto, convId, fromUid, "", "", "New message");
                          } else {
                              XNotificationHelper.postTweetInteraction(getApplicationContext(),
                                  type, fromName, fromPhoto, tweetId);
                          }

                          // Mark as notified
                          ds.getRef().child("notified").setValue(true);
                      }
                      synchronized (lock) { done[0] = true; lock.notifyAll(); }
                  }
                  @Override public void onCancelled(@NonNull DatabaseError e) {
                      synchronized (lock) { done[0] = true; lock.notifyAll(); }
                  }
              });

          // Wait up to 10 seconds for Firebase response (Worker thread is safe to block)
          synchronized (lock) {
              if (!done[0]) {
                  try { lock.wait(10_000); } catch (InterruptedException ignored) {}
              }
          }

          return Result.success();
      }
  }