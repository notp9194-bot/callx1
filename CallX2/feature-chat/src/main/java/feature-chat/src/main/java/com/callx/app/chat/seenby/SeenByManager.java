package com.callx.app.chat.seenby;

  import com.google.firebase.database.DataSnapshot;
  import com.google.firebase.database.DatabaseError;
  import com.google.firebase.database.FirebaseDatabase;
  import com.google.firebase.database.ValueEventListener;
  import java.util.ArrayList;
  import java.util.List;

  /**
   * SeenByManager — Tracks who has seen a group message.
   *
   * Firebase path: groups/{groupId}/messages/{msgId}/seenBy/{uid} = timestamp
   *
   * Usage:
   *   SeenByManager.markSeen(groupId, msgId, myUid)
   *   SeenByManager.loadSeenBy(groupId, msgId, callback)
   */
  public class SeenByManager {

      public interface SeenByCallback {
          void onLoaded(List<SeenByEntry> entries);
      }

      public static class SeenByEntry {
          public String uid;
          public String name;
          public String photoUrl;
          public long seenAt;
          public SeenByEntry(String uid, String name, String photoUrl, long seenAt) {
              this.uid = uid; this.name = name;
              this.photoUrl = photoUrl; this.seenAt = seenAt;
          }
      }

      /** Mark message as seen by current user */
      public static void markSeen(String groupId, String msgId, String myUid) {
          FirebaseDatabase.getInstance()
              .getReference("groups")
              .child(groupId).child("messages")
              .child(msgId).child("seenBy")
              .child(myUid)
              .setValue(System.currentTimeMillis());
      }

      /** Load all seen-by entries for a message */
      public static void loadSeenBy(String groupId, String msgId, SeenByCallback callback) {
          FirebaseDatabase.getInstance()
              .getReference("groups")
              .child(groupId).child("messages")
              .child(msgId).child("seenBy")
              .addListenerForSingleValueEvent(new ValueEventListener() {
                  @Override public void onDataChange(DataSnapshot snapshot) {
                      List<SeenByEntry> list = new ArrayList<>();
                      for (DataSnapshot child : snapshot.getChildren()) {
                          String uid = child.getKey();
                          Long ts  = child.getValue(Long.class);
                          // Name/photo resolved in BottomSheet via UserRepository
                          list.add(new SeenByEntry(uid, uid, null,
                                  ts != null ? ts : 0L));
                      }
                      if (callback != null) callback.onLoaded(list);
                  }
                  @Override public void onCancelled(DatabaseError error) {
                      if (callback != null) callback.onLoaded(new ArrayList<>());
                  }
              });
      }
  }