package com.callx.app.utils;

  import androidx.annotation.NonNull;
  import com.google.firebase.auth.FirebaseAuth;
  import com.google.firebase.database.*;
  import java.util.HashMap;
  import java.util.Map;

  /**
   * NotificationFirebaseStore — Firebase-backed notification log.
   *
   * Replaces SharedPreferences-only storage so notifications are:
   *  ✅ Synced across devices
   *  ✅ Persistent after reinstall
   *  ✅ Deletable individually or in bulk
   *  ✅ Searchable (by category / keyword)
   *
   * DB path: notification_log/{uid}/{pushKey}
   * Fields : type, title, body, senderUid, senderName, senderPhoto,
   *          reelId, chatId, groupId, timestamp, read, deliveryState
   */
  public class NotificationFirebaseStore {

      public static final String TYPE_MESSAGE = "message";
      public static final String TYPE_GROUP   = "group";
      public static final String TYPE_CALL    = "call";
      public static final String TYPE_REEL    = "reel";
      public static final String TYPE_STATUS  = "status";
      public static final String TYPE_SYSTEM  = "system";

      public static final String DELIVERY_FOREGROUND  = "foreground";
      public static final String DELIVERY_BACKGROUND  = "background";
      public static final String DELIVERY_KILLED      = "killed";

      private static DatabaseReference ref() {
          String uid = uid();
          if (uid == null) return null;
          return FirebaseUtils.db().getReference("notification_log").child(uid);
      }

      private static String uid() {
          try { return FirebaseAuth.getInstance().getCurrentUser().getUid(); }
          catch (Exception e) { return null; }
      }

      /** Save a notification entry to Firebase. */
      public static void save(String type, String title, String body,
                              String senderUid, String senderName, String senderPhoto,
                              String reelId, String chatId, String groupId,
                              String deliveryState) {
          DatabaseReference r = ref();
          if (r == null) return;
          Map<String, Object> m = new HashMap<>();
          m.put("type",          type          != null ? type          : TYPE_SYSTEM);
          m.put("title",         title         != null ? title         : "");
          m.put("body",          body          != null ? body          : "");
          m.put("senderUid",     senderUid     != null ? senderUid     : "");
          m.put("senderName",    senderName    != null ? senderName    : "");
          m.put("senderPhoto",   senderPhoto   != null ? senderPhoto   : "");
          m.put("reelId",        reelId        != null ? reelId        : "");
          m.put("chatId",        chatId        != null ? chatId        : "");
          m.put("groupId",       groupId       != null ? groupId       : "");
          m.put("deliveryState", deliveryState != null ? deliveryState : DELIVERY_BACKGROUND);
          m.put("timestamp",     System.currentTimeMillis());
          m.put("read",          false);
          r.push().setValue(m);
      }

      /** Mark a single notification as read. */
      public static void markRead(String key) {
          DatabaseReference r = ref();
          if (r == null || key == null) return;
          r.child(key).child("read").setValue(true);
      }

      /** Mark all notifications as read. */
      public static void markAllRead() {
          DatabaseReference r = ref();
          if (r == null) return;
          r.addListenerForSingleValueEvent(new ValueEventListener() {
              @Override public void onDataChange(@NonNull DataSnapshot snap) {
                  for (DataSnapshot child : snap.getChildren()) {
                      Boolean read = child.child("read").getValue(Boolean.class);
                      if (read == null || !read) {
                          child.getRef().child("read").setValue(true);
                      }
                  }
              }
              @Override public void onCancelled(@NonNull DatabaseError e) {}
          });
      }

      /** Delete a single notification by key. */
      public static void delete(String key) {
          DatabaseReference r = ref();
          if (r == null || key == null) return;
          r.child(key).removeValue();
      }

      /** Delete all notifications. */
      public static void deleteAll() {
          DatabaseReference r = ref();
          if (r == null) return;
          r.removeValue();
      }

      /** Delete all notifications of a specific type. */
      public static void deleteByType(String type) {
          DatabaseReference r = ref();
          if (r == null) return;
          r.orderByChild("type").equalTo(type)
              .addListenerForSingleValueEvent(new ValueEventListener() {
                  @Override public void onDataChange(@NonNull DataSnapshot snap) {
                      for (DataSnapshot child : snap.getChildren()) child.getRef().removeValue();
                  }
                  @Override public void onCancelled(@NonNull DatabaseError e) {}
              });
      }

      /** Keep only the latest N notifications, delete older ones. */
      public static void pruneToLimit(int limit) {
          DatabaseReference r = ref();
          if (r == null) return;
          r.orderByChild("timestamp").addListenerForSingleValueEvent(new ValueEventListener() {
              @Override public void onDataChange(@NonNull DataSnapshot snap) {
                  long count = snap.getChildrenCount();
                  if (count <= limit) return;
                  long toDelete = count - limit;
                  long deleted = 0;
                  for (DataSnapshot child : snap.getChildren()) {
                      if (deleted++ >= toDelete) break;
                      child.getRef().removeValue();
                  }
              }
              @Override public void onCancelled(@NonNull DatabaseError e) {}
          });
      }
  }
  