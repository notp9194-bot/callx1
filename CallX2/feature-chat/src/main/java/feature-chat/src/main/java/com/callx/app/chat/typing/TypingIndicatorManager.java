package com.callx.app.chat.typing;

  import android.os.Handler;
  import android.os.Looper;
  import com.google.firebase.auth.FirebaseAuth;
  import com.google.firebase.database.DataSnapshot;
  import com.google.firebase.database.DatabaseError;
  import com.google.firebase.database.DatabaseReference;
  import com.google.firebase.database.FirebaseDatabase;
  import com.google.firebase.database.ValueEventListener;

  /**
   * TypingIndicatorManager — Real-time typing indicator via Firebase.
   *
   * Firebase path: typing/{chatId}/{uid} = true/false
   *
   * Usage:
   *   manager.startTyping()   — call on text change
   *   manager.stopTyping()    — call on send / focus lost
   *   manager.observe(listener) — call in onCreate to get callbacks
   */
  public class TypingIndicatorManager {

      public interface TypingListener {
          void onPartnerTyping(boolean isTyping, String partnerName);
      }

      private static final long TYPING_TIMEOUT_MS = 3000; // 3 seconds

      private final String chatId;
      private final String myUid;
      private final String partnerUid;
      private final String partnerName;
      private final DatabaseReference typingRef;
      private final Handler handler = new Handler(Looper.getMainLooper());
      private ValueEventListener partnerListener;
      private TypingListener typingListener;
      private boolean isCurrentlyTyping = false;

      private final Runnable stopTypingRunnable = () -> {
          if (isCurrentlyTyping) {
              isCurrentlyTyping = false;
              typingRef.child(myUid).setValue(false);
          }
      };

      public TypingIndicatorManager(String chatId, String partnerUid, String partnerName) {
          this.chatId = chatId;
          this.myUid = FirebaseAuth.getInstance().getCurrentUser().getUid();
          this.partnerUid = partnerUid;
          this.partnerName = partnerName;
          this.typingRef = FirebaseDatabase.getInstance()
                  .getReference("typing").child(chatId);
      }

      /** Call from TextWatcher.onTextChanged */
      public void onTextChanged(CharSequence s) {
          if (s == null || s.length() == 0) {
              stopTyping();
              return;
          }
          if (!isCurrentlyTyping) {
              isCurrentlyTyping = true;
              typingRef.child(myUid).setValue(true);
          }
          // Reset auto-stop timer
          handler.removeCallbacks(stopTypingRunnable);
          handler.postDelayed(stopTypingRunnable, TYPING_TIMEOUT_MS);
      }

      /** Call on message sent or input focus lost */
      public void stopTyping() {
          handler.removeCallbacks(stopTypingRunnable);
          if (isCurrentlyTyping) {
              isCurrentlyTyping = false;
              typingRef.child(myUid).setValue(false);
          }
      }

      /** Register partner typing listener — call in onCreate */
      public void observe(TypingListener listener) {
          this.typingListener = listener;
          partnerListener = new ValueEventListener() {
              @Override public void onDataChange(DataSnapshot snapshot) {
                  Boolean isTyping = snapshot.getValue(Boolean.class);
                  if (typingListener != null) {
                      typingListener.onPartnerTyping(
                          Boolean.TRUE.equals(isTyping), partnerName);
                  }
              }
              @Override public void onCancelled(DatabaseError error) {}
          };
          typingRef.child(partnerUid).addValueEventListener(partnerListener);
      }

      /** Call in onDestroy — cleans up listeners and sets offline */
      public void cleanup() {
          stopTyping();
          handler.removeCallbacksAndMessages(null);
          if (partnerListener != null) {
              typingRef.child(partnerUid).removeEventListener(partnerListener);
          }
      }
  }