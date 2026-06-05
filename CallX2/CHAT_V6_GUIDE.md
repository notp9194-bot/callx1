# Chat System v2 — Integration Guide
  **CallX Production Chat Update**

  ## New Features Added

  ### ✅ 1. Typing Indicator (Real-time)
  **Files:**
  - `TypingIndicatorManager.java` — 1:1 chat
  - `GroupTypingIndicatorManager.java` — Group chat

  **ChatActivity mein add karein:**
  ```java
  // onCreate mein:
  typingManager = new TypingIndicatorManager(chatId, partnerUid, partnerName);
  typingManager.observe((isTyping, name) -> {
      if (isTyping) {
          tvStatus.setText(name + " is typing...");
      } else {
          tvStatus.setText(isOnline ? "Online" : lastSeenText);
      }
  });

  // TextWatcher mein:
  etMessage.addTextChangedListener(new TextWatcher() {
      public void onTextChanged(CharSequence s, ...) {
          typingManager.onTextChanged(s);
      }
  });

  // onDestroy mein:
  typingManager.cleanup();
  ```

  ---

  ### ✅ 2. Disappearing Messages
  **Files:**
  - `DisappearingMessageManager.java`
  - `DisappearingMessageWorker.java`
  - `bottom_sheet_disappearing_timer.xml`

  **Setup WorkManager (Application class mein):**
  ```java
  PeriodicWorkRequest work = new PeriodicWorkRequest.Builder(
      DisappearingMessageWorker.class, 1, TimeUnit.HOURS).build();
  WorkManager.getInstance(this).enqueueUniquePeriodicWork(
      "disappearing_check", ExistingPeriodicWorkPolicy.KEEP, work);
  ```

  **MessageDao mein add karein:**
  ```java
  @Query("SELECT * FROM messages WHERE expiresAt > 0 AND expiresAt <= :now")
  List<MessageEntity> getExpiredMessages(long now);

  @Query("DELETE FROM messages WHERE id = :id")
  void deleteById(String id);
  ```

  ---

  ### ✅ 3. Draft Auto-save
  **Files:** `ChatDraftManager.java`

  **ChatActivity mein:**
  ```java
  // onPause mein:
  ChatDraftManager.save(this, chatId, etMessage.getText().toString());

  // onCreate mein (after setContentView):
  String draft = ChatDraftManager.get(this, chatId);
  if (!draft.isEmpty()) etMessage.setText(draft);

  // Message send ke baad:
  ChatDraftManager.clear(this, chatId);
  ```

  ---

  ### ✅ 4. @Mention in Group Chat
  **Files:**
  - `MentionSuggestAdapter.java`
  - `MentionController.java`
  - `item_mention_suggest.xml`

  **GroupChatActivity mein:**
  ```java
  // layout mein RecyclerView add karein (above input bar):
  // android:id="@+id/rv_mentions" android:visibility="gone"

  MentionSuggestAdapter mentionAdapter = new MentionSuggestAdapter(user -> {
      mentionController.onMentionSelected(user);
  });
  mentionAdapter.setMembers(groupMembersList);
  rvMentions.setAdapter(mentionAdapter);

  MentionController mentionController = new MentionController(etMessage, rvMentions, mentionAdapter);

  // TextWatcher mein:
  public void afterTextChanged(Editable s) {
      mentionController.onTextChanged(s);
  }
  ```

  ---

  ### ✅ 5. Message Search in Chat
  **Files:**
  - `ChatSearchActivity.java`
  - `ChatSearchAdapter.java`
  - `activity_chat_search.xml`
  - `item_chat_search_result.xml`

  **ChatActivity toolbar mein search icon add karein:**
  ```java
  // chat_menu.xml mein:
  // <item android:id="@+id/action_search" android:title="Search"
  //       android:icon="@drawable/ic_search" app:showAsAction="always"/>

  // onOptionsItemSelected mein:
  case R.id.action_search:
      Intent i = new Intent(this, ChatSearchActivity.class);
      i.putExtra("chatId", chatId);
      startActivity(i);
      return true;
  ```

  **MessageDao mein add karein:**
  ```java
  @Query("SELECT * FROM messages WHERE chatId = :chatId AND text LIKE :query ORDER BY timestamp DESC LIMIT 100")
  List<MessageEntity> searchMessages(String chatId, String query);
  ```

  ---

  ### ✅ 6. Location Sharing
  **Files:**
  - `LocationShareHelper.java`
  - `bottom_sheet_location_share.xml`
  - `item_message_location.xml`

  **Permissions (AndroidManifest):**
  ```xml
  <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"/>
  <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION"/>
  ```

  **Dependencies (build.gradle):**
  ```
  implementation 'com.google.android.gms:play-services-location:21.3.0'
  ```

  **Attach sheet mein "Location" option click par:**
  ```java
  LocationShareHelper helper = new LocationShareHelper(context);
  helper.getCurrentLocation((lat, lng) -> {
      LocationShareHelper.sendLocation(chatId, lat, lng, "Current Location");
  });
  ```

  ---

  ### ✅ 7. Sticker & GIF
  **Files:**
  - `StickerManager.java`
  - `bottom_sheet_sticker_picker.xml`
  - `bottom_sheet_gif_picker.xml`
  - `item_message_sticker.xml`

  **Tenor GIF Search:**
  ```java
  String url = StickerManager.getTenorSearchUrl("funny");
  // OkHttp se fetch karein, Gson se parse karein
  // Each result mein: result.media_formats.gif.url
  ```

  **Dependencies (build.gradle):**
  ```
  implementation 'com.github.bumptech.glide:glide:4.16.0'  // already present
  // GIF loading: Glide already supports WebP/GIF natively
  ```

  ---

  ### ✅ 8. Seen By (Group Messages)
  **Files:**
  - `SeenByManager.java`
  - `SeenByAdapter.java`
  - `bottom_sheet_seen_by.xml`
  - `item_seen_by_user.xml`

  **GroupChatActivity mein — message receive pe:**
  ```java
  // Jab group message screen par dikhta hai:
  SeenByManager.markSeen(groupId, msgId, myUid);

  // MessageAdapter long-press mein "Info" tap par:
  SeenByManager.loadSeenBy(groupId, msgId, entries -> {
      // BottomSheet show karein with SeenByAdapter
  });
  ```

  ---

  ### ✅ 9. Poll in Chat
  **Files:**
  - `ChatPollManager.java`
  - `bottom_sheet_poll_create.xml`
  - `item_message_poll.xml`
  - `item_poll_option.xml`

  **Attach sheet mein "Poll" option:**
  ```java
  // bottom_sheet_poll_create show karein
  // "Create Poll" button click par:
  List<String> options = Arrays.asList(opt1, opt2, opt3);
  ChatPollManager.create(chatId, question, options, isMultiple, isAnonymous);

  // Vote karne par (item_message_poll mein option click):
  ChatPollManager.vote(chatId, msgId, optionId);
  ```

  ---

  ### ✅ 10. Broadcast Lists
  **Files:**
  - `BroadcastListActivity.java`
  - `NewBroadcastActivity.java`
  - `SendBroadcastActivity.java`
  - `BroadcastListAdapter.java`
  - `BroadcastMemberSelectAdapter.java`
  - `BroadcastList.java`
  - Activity layouts (3)

  **AndroidManifest mein add karein:**
  ```xml
  <activity android:name=".broadcast.BroadcastListActivity"/>
  <activity android:name=".broadcast.NewBroadcastActivity"/>
  <activity android:name=".broadcast.SendBroadcastActivity"/>
  ```

  ---

  ### ✅ 11. Scheduled Messages
  **Files:**
  - `ScheduleMessageManager.java`
  - `ScheduledMessageWorker.java`
  - `bottom_sheet_schedule_message.xml`

  **Long-press send button par:**
  ```java
  // DatePickerDialog + TimePickerDialog show karein
  // Schedule confirm par:
  ScheduleMessageManager.schedule(ctx, chatId, text, myUid, sendAtMs);
  ```

  **AndroidManifest mein:**
  ```xml
  <receiver android:name="androidx.work.impl.background.systemalarm.RescheduleReceiver"/>
  ```

  ---

  ### ✅ 12. Slow Mode (Groups — Admin Only)
  **Files:**
  - `SlowModeManager.java`
  - `bottom_sheet_slow_mode.xml`

  **GroupSettingsActivity mein:**
  ```java
  // Admin check ke baad:
  SlowModeManager.setSlowMode(groupId, 60); // 1 minute

  // GroupChatActivity mein send button click par:
  if (!SlowModeManager.canSend(ctx, groupId, slowModeSec)) {
      long remaining = SlowModeManager.remainingSeconds(ctx, groupId, slowModeSec);
      Toast.makeText(ctx, remaining + "s baad bhej sakte hain", Toast.LENGTH_SHORT).show();
      return;
  }
  SlowModeManager.recordSend(ctx, groupId);
  ```

  ---

  ### ✅ 13. Message Translation (ML Kit)
  **Files:** `MessageTranslationHelper.java`

  **Dependencies (build.gradle):**
  ```
  implementation 'com.google.mlkit:translate:17.0.2'
  ```

  **MessageAdapter long-press mein "Translate" option:**
  ```java
  MessageTranslationHelper.translate(message.text, TranslateLanguage.HINDI, new MessageTranslationHelper.TranslationCallback() {
      public void onSuccess(String translated) {
          tvMessage.setText(translated);
          tvTranslated.setVisibility(View.VISIBLE);
      }
      public void onFailure(String error) { /* show error */ }
  });
  ```

  ---

  ### ✅ 14. Individual Chat Lock
  **Files:**
  - `ChatLockManager.java`
  - `bottom_sheet_chat_lock.xml`

  **ChatListAdapter mein:**
  ```java
  if (ChatLockManager.isLocked(ctx, chat.chatId)) {
      ivLock.setVisibility(View.VISIBLE);
      tvPreview.setText("🔒 Locked");
  }
  ```

  **ChatActivity onCreate mein (agar locked hai):**
  ```java
  if (ChatLockManager.isLocked(this, chatId)) {
      // BiometricPrompt show karein — pass hone par continue
      // Fail hone par finish()
  }
  ```

  ---

  ## Updated Firebase Rules
  `firebase_rules/firebase_chat_rules_v2.json`

  Naye paths added:
  - `/typing/{chatId}/{uid}` — 1:1 typing indicator
  - `/groupTyping/{groupId}/{uid}` — group typing
  - `/groups/{groupId}/messages/{msgId}/seenBy/{uid}` — seen by
  - `/groups/{groupId}/slowModeSeconds` — slow mode
  - `/broadcasts/{uid}/{broadcastId}` — broadcast lists
  - `chats/{chatId}/disappearTimer` — disappearing timer
  - New message fields: `expiresAt`, `locationLat/Lng`, `pollOptions`, `scheduled`

  ## Updated Room DB (MessageEntity)
  See `MessageEntity_v2_additions.java` for fields to add.
  Run schema migration or set `fallbackToDestructiveMigration()` in dev.