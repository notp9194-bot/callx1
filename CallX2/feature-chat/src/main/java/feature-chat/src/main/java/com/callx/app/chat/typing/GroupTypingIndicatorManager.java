package com.callx.app.chat.typing;

  import com.google.firebase.auth.FirebaseAuth;
  import com.google.firebase.database.DataSnapshot;
  import com.google.firebase.database.DatabaseError;
  import com.google.firebase.database.DatabaseReference;
  import com.google.firebase.database.FirebaseDatabase;
  import com.google.firebase.database.ValueEventListener;
  import android.os.Handler;
  import android.os.Looper;
  import java.util.ArrayList;
  import java.util.HashMap;
  import java.util.List;
  import java.util.Map;

  /**
   * GroupTypingIndicatorManager — Tracks multiple people typing in group.
   * Shows "Alice, Bob are typing..." or "3 people are typing..."
   */
  public class GroupTypingIndicatorManager {

      public interface GroupTypingListener {
          void onTypingChanged(String displayText); // e.g. "Alice is typing…"
      }

      private static final long TYPING_TIMEOUT_MS = 3000;

      private final String groupId;
      private final String myUid;
      private final DatabaseReference typingRef;
      private final Handler handler = new Handler(Looper.getMainLooper());
      private ValueEventListener groupListener;
      private GroupTypingListener listener;
      private boolean isCurrentlyTyping = false;

      // Map uid → display name for currently typing members
      private final Map<String, String> typingUsers = new HashMap<>();

      private final Runnable stopTypingRunnable = () -> {
          isCurrentlyTyping = false;
          typingRef.child(myUid).removeValue();
      };

      public GroupTypingIndicatorManager(String groupId, String myDisplayName) {
          this.groupId = groupId;
          this.myUid = FirebaseAuth.getInstance().getCurrentUser().getUid();
          this.typingRef = FirebaseDatabase.getInstance()
                  .getReference("groupTyping").child(groupId);
      }

      public void onTextChanged(CharSequence s, String myDisplayName) {
          if (s == null || s.length() == 0) { stopTyping(); return; }
          if (!isCurrentlyTyping) {
              isCurrentlyTyping = true;
              typingRef.child(myUid).setValue(myDisplayName);
          }
          handler.removeCallbacks(stopTypingRunnable);
          handler.postDelayed(stopTypingRunnable, TYPING_TIMEOUT_MS);
      }

      public void stopTyping() {
          handler.removeCallbacks(stopTypingRunnable);
          if (isCurrentlyTyping) {
              isCurrentlyTyping = false;
              typingRef.child(myUid).removeValue();
          }
      }

      public void observe(GroupTypingListener listener) {
          this.listener = listener;
          groupListener = new ValueEventListener() {
              @Override public void onDataChange(DataSnapshot snapshot) {
                  typingUsers.clear();
                  for (DataSnapshot child : snapshot.getChildren()) {
                      String uid = child.getKey();
                      String name = child.getValue(String.class);
                      if (!myUid.equals(uid) && name != null) {
                          typingUsers.put(uid, name);
                      }
                  }
                  if (listener != null) listener.onTypingChanged(buildDisplayText());
              }
              @Override public void onCancelled(DatabaseError error) {}
          };
          typingRef.addValueEventListener(groupListener);
      }

      private String buildDisplayText() {
          if (typingUsers.isEmpty()) return "";
          List<String> names = new ArrayList<>(typingUsers.values());
          if (names.size() == 1) return names.get(0) + " is typing…";
          if (names.size() == 2) return names.get(0) + " and " + names.get(1) + " are typing…";
          return names.size() + " people are typing…";
      }

      public void cleanup() {
          stopTyping();
          handler.removeCallbacksAndMessages(null);
          if (groupListener != null) typingRef.removeEventListener(groupListener);
      }
  }