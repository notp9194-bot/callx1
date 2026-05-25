package com.callx.app.notifications;

import com.callx.app.reels.R;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.*;
import com.callx.app.utils.FirebaseUtils;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.concurrent.TimeUnit;

/**
 * ReelNotificationWorker — WorkManager periodic worker for reel notifications.
 *
 * Runs every 15 minutes (minimum WorkManager interval) even when app is killed.
 * Polls Firebase for unread/pending notifications that may have been missed
 * while the app was in background/killed state (FCM delivery not guaranteed).
 *
 * Checks:
 *  ✅ New likes since last check
 *  ✅ New comments since last check
 *  ✅ New mentions since last check
 *  ✅ Pending scheduled reels due in next 30 mins (sends reminder)
 *  ✅ Upload failures (retry prompt)
 *  ✅ Creator fund balance crossed withdrawal threshold
 *  ✅ Follower count milestones
 *  ✅ View count milestones on top reel
 *  ✅ Pending collab requests (re-reminds after 24h)
 */
public class ReelNotificationWorker extends Worker {

    private static final String TAG = "ReelNotifWorker";
    private static final long   POLL_INTERVAL_MINS = 15;
    private static final String PREF_LAST_CHECKED  = "reel_notif_last_checked";
    private static final long   MIN_WITHDRAW_COINS = 50_000L;

    public ReelNotificationWorker(@NonNull Context ctx, @NonNull WorkerParameters params) {
        super(ctx, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            FirebaseAuth auth = FirebaseAuth.getInstance();
            if (auth.getCurrentUser() == null) return Result.success();
            String uid = auth.getCurrentUser().getUid();

            ReelNotificationChannelManager.ensureChannels(getApplicationContext());

            long lastChecked = getApplicationContext()
                .getSharedPreferences("reel_notif_prefs", Context.MODE_PRIVATE)
                .getLong(PREF_LAST_CHECKED, System.currentTimeMillis() - 15 * 60000L);

            checkNewLikes(uid, lastChecked);
            checkNewComments(uid, lastChecked);
            checkNewMentions(uid, lastChecked);
            checkScheduledReels(uid);
            checkCreatorFundBalance(uid);
            checkViewMilestones(uid);
            checkFollowerMilestones(uid);
            checkPendingCollabs(uid, lastChecked);
            checkUploadFailures(uid);

            getApplicationContext()
                .getSharedPreferences("reel_notif_prefs", Context.MODE_PRIVATE)
                .edit().putLong(PREF_LAST_CHECKED, System.currentTimeMillis()).apply();

            return Result.success();
        } catch (Exception e) {
            return Result.retry();
        }
    }

    private void checkNewLikes(String uid, long since) {
        FirebaseUtils.db().getReference("reelNotifQueue").child(uid).child("likes")
            .orderByChild("timestamp").startAt(since)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    long count = snap.getChildrenCount();
                    if (count == 0) return;
                    String name = "Someone"; String photo = "";
                    for (DataSnapshot s : snap.getChildren()) {
                        String n = s.child("name").getValue(String.class);
                        String p = s.child("photo").getValue(String.class);
                        String r = s.child("reelId").getValue(String.class);
                        if (n != null) name = n;
                        if (p != null) photo = p;
                        if (r != null) ReelNotificationHelper.showLikeNotification(getApplicationContext(), name, photo, r, "", (int) count);
                        break;
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void checkNewComments(String uid, long since) {
        FirebaseUtils.db().getReference("reelNotifQueue").child(uid).child("comments")
            .orderByChild("timestamp").startAt(since)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    for (DataSnapshot s : snap.getChildren()) {
                        String name    = s.child("commenterName").getValue(String.class);
                        String photo   = s.child("commenterPhoto").getValue(String.class);
                        String reelId  = s.child("reelId").getValue(String.class);
                        String text    = s.child("text").getValue(String.class);
                        String commId  = s.getKey();
                        if (name != null && reelId != null && text != null)
                            ReelNotificationHelper.showCommentNotification(getApplicationContext(), name, photo != null ? photo : "", reelId, text, commId != null ? commId : "");
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void checkNewMentions(String uid, long since) {
        FirebaseUtils.db().getReference("reelMentions").child(uid)
            .orderByChild("timestamp").startAt(since)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    for (DataSnapshot s : snap.getChildren()) {
                        Boolean read = s.child("read").getValue(Boolean.class);
                        if (read != null && read) continue;
                        String name   = s.child("mentionerName").getValue(String.class);
                        String photo  = s.child("mentionerPhoto").getValue(String.class);
                        String reelId = s.child("reelId").getValue(String.class);
                        String cap    = s.child("caption").getValue(String.class);
                        if (name != null && reelId != null)
                            ReelNotificationHelper.showMentionNotification(getApplicationContext(), name, photo != null ? photo : "", reelId, cap != null ? cap : "", "caption");
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void checkScheduledReels(String uid) {
        long now      = System.currentTimeMillis();
        long in30mins = now + 30 * 60000L;
        FirebaseUtils.getScheduledReelsRef(uid)
            .orderByChild("scheduledAt").startAt(now).endAt(in30mins)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    for (DataSnapshot s : snap.getChildren()) {
                        String status = s.child("status").getValue(String.class);
                        if (!"pending".equals(status)) continue;
                        Long schedAt = s.child("scheduledAt").getValue(Long.class);
                        if (schedAt == null) continue;
                        String time = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                            .format(new java.util.Date(schedAt));
                        ReelNotificationHelper.showScheduledReminderNotification(getApplicationContext(), time);
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void checkCreatorFundBalance(String uid) {
        FirebaseUtils.db().getReference("reelCreatorFund").child(uid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    Long balance = snap.child("balance").getValue(Long.class);
                    Boolean notified = snap.child("withdrawalNotified").getValue(Boolean.class);
                    if (balance != null && balance >= MIN_WITHDRAW_COINS && (notified == null || !notified)) {
                        ReelNotificationHelper.showCreatorFundPayoutNotification(
                            getApplicationContext(), balance, balance * 0.001);
                        snap.getRef().child("withdrawalNotified").setValue(true);
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void checkViewMilestones(String uid) {
        long[] milestones = {1_000L, 10_000L, 100_000L, 1_000_000L};
        FirebaseUtils.getReelsByUserRef(uid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    for (DataSnapshot rs : snap.getChildren()) {
                        String reelId = rs.getKey();
                        FirebaseUtils.getReelsRef().child(reelId != null ? reelId : "")
                            .child("viewCount")
                            .addListenerForSingleValueEvent(new ValueEventListener() {
                                @Override public void onDataChange(@NonNull DataSnapshot vs) {
                                    Long views = vs.getValue(Long.class);
                                    if (views == null || reelId == null) return;
                                    for (long m : milestones) {
                                        if (views >= m) {
                                            String milesKey = "viewMile_" + reelId + "_" + m;
                                            boolean already = getApplicationContext()
                                                .getSharedPreferences("reel_notif_prefs", Context.MODE_PRIVATE)
                                                .getBoolean(milesKey, false);
                                            if (!already) {
                                                ReelNotificationHelper.showViewMilestoneNotification(getApplicationContext(), reelId, m);
                                                getApplicationContext()
                                                    .getSharedPreferences("reel_notif_prefs", Context.MODE_PRIVATE)
                                                    .edit().putBoolean(milesKey, true).apply();
                                            }
                                        }
                                    }
                                }
                                @Override public void onCancelled(@NonNull DatabaseError e) {}
                            });
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void checkFollowerMilestones(String uid) {
        long[] milestones = {100L, 1_000L, 10_000L, 100_000L};
        FirebaseUtils.getReelFollowersRef(uid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    long count = snap.getChildrenCount();
                    for (long m : milestones) {
                        if (count >= m) {
                            String key = "folMile_" + uid + "_" + m;
                            boolean already = getApplicationContext()
                                .getSharedPreferences("reel_notif_prefs", Context.MODE_PRIVATE)
                                .getBoolean(key, false);
                            if (!already) {
                                ReelNotificationHelper.showFollowerMilestoneNotification(getApplicationContext(), m);
                                getApplicationContext()
                                    .getSharedPreferences("reel_notif_prefs", Context.MODE_PRIVATE)
                                    .edit().putBoolean(key, true).apply();
                            }
                        }
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void checkPendingCollabs(String uid, long since) {
        long remindAfterMs = 24 * 60 * 60 * 1000L;
        FirebaseUtils.db().getReference("reelCollabs")
            .orderByChild("receiverUid").equalTo(uid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    long now = System.currentTimeMillis();
                    for (DataSnapshot s : snap.getChildren()) {
                        String status   = s.child("status").getValue(String.class);
                        Long   sentAt   = s.child("sentAt").getValue(Long.class);
                        String collabId = s.getKey();
                        if (!"pending".equals(status) || sentAt == null) continue;
                        if ((now - sentAt) < remindAfterMs) continue;
                        String name  = s.child("senderName").getValue(String.class);
                        String photo = s.child("senderPhoto").getValue(String.class);
                        String reelId= s.child("reelId").getValue(String.class);
                        if (name != null && reelId != null)
                            ReelNotificationHelper.showCollabRequestNotification(getApplicationContext(), name, photo != null ? photo : "", reelId, collabId != null ? collabId : "");
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void checkUploadFailures(String uid) {
        FirebaseUtils.db().getReference("reelUploadFailures").child(uid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    for (DataSnapshot s : snap.getChildren()) {
                        Boolean notified = s.child("workerNotified").getValue(Boolean.class);
                        if (notified != null && notified) continue;
                        String draftId = s.getKey();
                        String reason  = s.child("reason").getValue(String.class);
                        if (draftId != null)
                            ReelNotificationHelper.showUploadFailedNotification(getApplicationContext(), draftId, reason != null ? reason : "Unknown error");
                        s.getRef().child("workerNotified").setValue(true);
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    // ── Static schedule helper ─────────────────────────────────────────────

    public static void schedule(Context ctx) {
        PeriodicWorkRequest work = new PeriodicWorkRequest.Builder(
            ReelNotificationWorker.class, POLL_INTERVAL_MINS, TimeUnit.MINUTES)
            .setConstraints(new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build())
            .addTag(TAG)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build();
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            TAG, ExistingPeriodicWorkPolicy.KEEP, work);
    }

    public static void cancel(Context ctx) {
        WorkManager.getInstance(ctx).cancelAllWorkByTag(TAG);
    }

      // ─────────────────────────────────────────────────────────────────────
      // CHECK AND FIRE SCHEDULED POST REMINDERS
      // ─────────────────────────────────────────────────────────────────────
      private void checkScheduledPostReminders() {
          try {
              String uid = com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser().getUid();
              com.callx.app.utils.FirebaseUtils.db()
                  .getReference("scheduled_reels")
                  .child(uid)
                  .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                      @Override
                      public void onDataChange(@androidx.annotation.NonNull com.google.firebase.database.DataSnapshot snap) {
                          long now = System.currentTimeMillis();
                          for (com.google.firebase.database.DataSnapshot child : snap.getChildren()) {
                              Long scheduledAt = child.child("scheduledAt").getValue(Long.class);
                              Boolean reminded = child.child("reminded").getValue(Boolean.class);
                              if (scheduledAt == null) continue;
                              if (reminded != null && reminded) continue;

                              // Fire reminder 30 min before scheduled time
                              long reminderAt = scheduledAt - (30 * 60 * 1000L);
                              if (now >= reminderAt && now < scheduledAt) {
                                  String reelId   = child.getKey();
                                  String caption  = child.child("caption").getValue(String.class);
                                  fireScheduledReminder(reelId, scheduledAt, caption);
                                  // Mark as reminded
                                  child.getRef().child("reminded").setValue(true);
                              }
                          }
                      }
                      @Override public void onCancelled(@androidx.annotation.NonNull com.google.firebase.database.DatabaseError e) {}
                  });
          } catch (Exception e) {
              android.util.Log.w("ReelNotifWorker", "checkScheduledPostReminders: " + e.getMessage());
          }
      }

      private void fireScheduledReminder(String reelId, long scheduledAt, String caption) {
          String timeStr = new java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
              .format(new java.util.Date(scheduledAt));
          String body = caption != null && !caption.isEmpty()
              ? "\"" + caption + "\"  →  going live at " + timeStr
              : "Your reel is scheduled to post at " + timeStr;

          android.content.Intent intent = new android.content.Intent(getApplicationContext(),
              com.callx.app.activities.ReelCreatorDashboardActivity.class)
              .putExtra("reel_id", reelId)
              .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
          android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
              getApplicationContext(), reelId.hashCode(), intent,
              android.app.PendingIntent.FLAG_UPDATE_CURRENT |
              android.app.PendingIntent.FLAG_IMMUTABLE);

          androidx.core.app.NotificationCompat.Builder b =
              new androidx.core.app.NotificationCompat.Builder(getApplicationContext(),
                  ReelNotificationChannelManager.CHANNEL_REEL_SCHEDULED_REMINDER)
              .setSmallIcon(R.drawable.ic_reels)
              .setContentTitle("⏰ Scheduled reel posting soon!")
              .setContentText(body)
              .setStyle(new androidx.core.app.NotificationCompat.BigTextStyle().bigText(body))
              .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
              .setAutoCancel(true)
              .setContentIntent(pi)
              .addAction(R.drawable.ic_reels, "Edit Reel", pi);

          android.app.NotificationManager nm = (android.app.NotificationManager)
              getApplicationContext().getSystemService(android.content.Context.NOTIFICATION_SERVICE);
          if (nm != null) nm.notify(("sched_" + reelId).hashCode(), b.build());
      }
  
}