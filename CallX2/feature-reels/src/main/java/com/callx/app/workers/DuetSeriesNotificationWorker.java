package com.callx.app.workers;

  import android.content.Context;
  import android.util.Log;
  import androidx.annotation.NonNull;
  import androidx.work.*;

  import com.callx.app.utils.Constants;
  import com.callx.app.utils.PushNotify;
  import com.google.firebase.database.*;

  import java.util.ArrayList;
  import java.util.HashMap;
  import java.util.List;
  import java.util.Map;
  import java.util.concurrent.TimeUnit;

  /**
   * DuetSeriesNotificationWorker — Notifies ALL subscribers of a Duet Series
   * when the creator publishes a new episode.
   *
   * Architecture (follows DuetNotificationWorker pattern exactly):
   *
   * Step 1 — Reads subscriber list from:
   *   duetSeriesSubscriptions/{seriesId}/{uid} = true
   *
   * Step 2 — For each subscriber (excluding the creator themselves):
   *   → PushNotify.notifyDuetSeriesEpisode(subscriberUid, ...)
   *     → POST Constants.SERVER_URL/notify/reel  {type:"duet_series_episode", ...}
   *     → Server sends FCM payload reel_notif_type:"duet_series_episode"
   *     → CallxMessagingService → ReelFCMNotificationHandler → TYPE_DUET_SERIES_EPISODE
   *
   * Step 3 — In-app notification entry for each subscriber:
   *   reel_notifications/{subscriberUid}/{pushKey}
   *
   * Step 4 — Increments series episodeCount + updates coverThumbUrl:
   *   duetSeries/{seriesId}/episodeCount += 1
   *   duetSeries/{seriesId}/coverThumbUrl = latest episode thumb
   */
  public class DuetSeriesNotificationWorker extends Worker {

      private static final String TAG = "DuetSeriesNotifWorker";

      public static final String KEY_SERIES_ID       = "series_id";
      public static final String KEY_SERIES_TITLE    = "series_title";
      public static final String KEY_EPISODE_NUMBER  = "episode_number";
      public static final String KEY_REEL_ID         = "reel_id";
      public static final String KEY_REEL_THUMB      = "reel_thumb";
      public static final String KEY_CREATOR_UID     = "creator_uid";
      public static final String KEY_CREATOR_NAME    = "creator_name";
      public static final String KEY_CREATOR_PHOTO   = "creator_photo";

      public DuetSeriesNotificationWorker(@NonNull Context ctx, @NonNull WorkerParameters params) {
          super(ctx, params);
      }

      @NonNull
      @Override
      public Result doWork() {
          String seriesId      = getInputData().getString(KEY_SERIES_ID);
          String seriesTitle   = getInputData().getString(KEY_SERIES_TITLE);
          int    episodeNum    = getInputData().getInt(KEY_EPISODE_NUMBER, 1);
          String reelId        = getInputData().getString(KEY_REEL_ID);
          String reelThumb     = getInputData().getString(KEY_REEL_THUMB);
          String creatorUid    = getInputData().getString(KEY_CREATOR_UID);
          String creatorName   = getInputData().getString(KEY_CREATOR_NAME);
          String creatorPhoto  = getInputData().getString(KEY_CREATOR_PHOTO);

          if (seriesId == null || reelId == null || creatorUid == null) {
              Log.w(TAG, "Missing required data — skipping");
              return Result.failure();
          }

          String title  = seriesTitle  != null ? seriesTitle  : "Duet Series";
          String name   = creatorName  != null ? creatorName  : "Someone";
          String photo  = creatorPhoto != null ? creatorPhoto : "";
          String thumb  = reelThumb    != null ? reelThumb    : "";

          try {
              FirebaseDatabase db = FirebaseDatabase.getInstance(Constants.DB_URL);
              long now = System.currentTimeMillis();

              // ── Step 1: Read all subscribers ──────────────────────────────
              List<String> subscribers = new ArrayList<>();
              DatabaseReference subsRef = db.getReference("duetSeriesSubscriptions").child(seriesId);

              // Synchronous-style read using a CountDownLatch
              java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
              subsRef.addListenerForSingleValueEvent(new ValueEventListener() {
                  @Override public void onDataChange(@NonNull DataSnapshot snap) {
                      for (DataSnapshot s : snap.getChildren()) {
                          String uid = s.getKey();
                          if (uid != null && !uid.equals(creatorUid)) subscribers.add(uid);
                      }
                      latch.countDown();
                  }
                  @Override public void onCancelled(@NonNull DatabaseError e) { latch.countDown(); }
              });
              latch.await(10, TimeUnit.SECONDS);

              Log.d(TAG, "Notifying " + subscribers.size() + " subscribers for series " + seriesId);

              // ── Step 2 + 3: FCM push + in-app notification per subscriber ─
              String message = name + " posted Part " + episodeNum + " of \"" + title + "\"";
              for (String subUid : subscribers) {
                  // FCM push
                  PushNotify.notifyDuetSeriesEpisode(
                      subUid, creatorUid, name, photo, reelId, thumb,
                      seriesId, title, episodeNum
                  );

                  // In-app notification entry
                  Map<String, Object> inApp = new HashMap<>();
                  inApp.put("type",           "duet_series_episode");
                  inApp.put("senderUid",      creatorUid);
                  inApp.put("senderName",     name);
                  inApp.put("senderPhoto",    photo);
                  inApp.put("reel_id",        reelId);
                  inApp.put("reel_thumb",     thumb);
                  inApp.put("series_id",      seriesId);
                  inApp.put("series_title",   title);
                  inApp.put("episode_number", episodeNum);
                  inApp.put("message",        message);
                  inApp.put("timestamp",      now);
                  inApp.put("read",           false);

                  db.getReference("reel_notifications").child(subUid)
                    .push().setValue(inApp);

                  // Queue fallback
                  Map<String, Object> queue = new HashMap<>();
                  queue.put("type",          "duet_series_episode");
                  queue.put("reel_id",       reelId);
                  queue.put("series_id",     seriesId);
                  queue.put("episodeNumber", episodeNum);
                  queue.put("senderUid",     creatorUid);
                  queue.put("senderName",    name);
                  queue.put("timestamp",     now);
                  db.getReference("reelNotifQueue").child(subUid)
                    .child("series_episodes")
                    .push().setValue(queue);
              }

              // ── Step 4: Update series metadata ────────────────────────────
              Map<String, Object> seriesUpdate = new HashMap<>();
              seriesUpdate.put("episodeCount",   episodeNum);
              if (!thumb.isEmpty()) seriesUpdate.put("coverThumbUrl", thumb);
              db.getReference("duetSeries").child(seriesId).updateChildren(seriesUpdate);

              Log.d(TAG, "DuetSeriesNotifWorker done — " + subscribers.size() + " notified");
              return Result.success();

          } catch (Exception e) {
              Log.e(TAG, "DuetSeriesNotifWorker error: " + e.getMessage(), e);
              return Result.retry();
          }
      }

      /** Convenience builder — enqueues this worker with the given episode data. */
      public static void enqueue(Context ctx,
                                 String seriesId, String seriesTitle, int episodeNumber,
                                 String reelId, String reelThumb,
                                 String creatorUid, String creatorName, String creatorPhoto) {
          Data input = new Data.Builder()
              .putString(KEY_SERIES_ID,      seriesId)
              .putString(KEY_SERIES_TITLE,   seriesTitle)
              .putInt(KEY_EPISODE_NUMBER,    episodeNumber)
              .putString(KEY_REEL_ID,        reelId)
              .putString(KEY_REEL_THUMB,     reelThumb)
              .putString(KEY_CREATOR_UID,    creatorUid)
              .putString(KEY_CREATOR_NAME,   creatorName)
              .putString(KEY_CREATOR_PHOTO,  creatorPhoto)
              .build();

          OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(DuetSeriesNotificationWorker.class)
              .setInputData(input)
              .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
              .setConstraints(new Constraints.Builder()
                  .setRequiredNetworkType(NetworkType.CONNECTED)
                  .build())
              .build();

          WorkManager.getInstance(ctx).enqueue(req);
      }
  }
  