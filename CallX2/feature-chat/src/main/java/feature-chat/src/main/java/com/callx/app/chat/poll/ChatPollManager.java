package com.callx.app.chat.poll;

  import com.google.firebase.auth.FirebaseAuth;
  import com.google.firebase.database.DatabaseReference;
  import com.google.firebase.database.FirebaseDatabase;
  import java.util.HashMap;
  import java.util.List;
  import java.util.Map;
  import java.util.UUID;

  /**
   * ChatPollManager — Create and vote on polls inside chat/group.
   *
   * Firebase path: chats/{chatId}/messages/{msgId}
   *   type: "poll"
   *   pollQuestion: "..."
   *   pollOptions: {optId: {text, votes: {uid: true}}}
   *   pollMultiple: boolean
   *   pollAnonymous: boolean
   *
   * Usage:
   *   ChatPollManager.create(chatId, question, options, multipleChoice)
   *   ChatPollManager.vote(chatId, msgId, optionId)
   */
  public class ChatPollManager {

      private ChatPollManager() {}

      public static void create(String chatId, String question, List<String> options,
                                boolean multipleChoice, boolean anonymous) {
          String myUid = FirebaseAuth.getInstance().getCurrentUser().getUid();
          String msgId = UUID.randomUUID().toString().replace("-", "");
          DatabaseReference ref = FirebaseDatabase.getInstance()
                  .getReference("chats").child(chatId).child("messages").child(msgId);

          Map<String, Object> optionsMap = new HashMap<>();
          for (String opt : options) {
              String optId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
              Map<String, Object> optData = new HashMap<>();
              optData.put("text", opt);
              optData.put("votes", new HashMap<>());
              optionsMap.put(optId, optData);
          }

          Map<String, Object> msg = new HashMap<>();
          msg.put("id", msgId);
          msg.put("senderId", myUid);
          msg.put("type", "poll");
          msg.put("pollQuestion", question);
          msg.put("pollOptions", optionsMap);
          msg.put("pollMultiple", multipleChoice);
          msg.put("pollAnonymous", anonymous);
          msg.put("timestamp", System.currentTimeMillis());
          msg.put("status", "sent");

          ref.setValue(msg);
      }

      public static void vote(String chatId, String msgId, String optionId) {
          String myUid = FirebaseAuth.getInstance().getCurrentUser().getUid();
          FirebaseDatabase.getInstance()
              .getReference("chats").child(chatId)
              .child("messages").child(msgId)
              .child("pollOptions").child(optionId)
              .child("votes").child(myUid)
              .setValue(true);
      }

      public static void removeVote(String chatId, String msgId, String optionId) {
          String myUid = FirebaseAuth.getInstance().getCurrentUser().getUid();
          FirebaseDatabase.getInstance()
              .getReference("chats").child(chatId)
              .child("messages").child(msgId)
              .child("pollOptions").child(optionId)
              .child("votes").child(myUid)
              .removeValue();
      }
  }