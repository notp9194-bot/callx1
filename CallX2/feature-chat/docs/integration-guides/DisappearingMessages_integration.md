// ═══════════════════════════════════════════════════════════════════
// Disappearing Messages — Complete Integration Guide
// ═══════════════════════════════════════════════════════════════════

// ── PART 1: ChatActivity.java — full wiring ───────────────────────────

// Step A: Fields
private DisappearingMessagesManager disappearingManager;

// Step B: In onCreate(), after chatId is set
@Override
protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    // ... existing init ...

    disappearingManager = new DisappearingMessagesManager(chatId);
    disappearingManager.listen(this::updateDisappearHeader);

    // Include layout_disappear_header.xml in activity_chat.xml below toolbar.
    // The ViewBinding reference is binding.disappearHeader (or similar).
    binding.disappearHeader.setOnClickListener(v ->
            DisappearingTimerDialog.show(getSupportFragmentManager(),
                    disappearingManager.getCurrentDuration(),
                    duration -> disappearingManager.setTimer(duration)));
}

// Step C: Header update method
private void updateDisappearHeader(long durationMs) {
    runOnUiThread(() -> {
        boolean active = durationMs > DisappearingMessagesManager.DURATION_OFF;
        binding.disappearHeader.setVisibility(active ? View.VISIBLE : View.GONE);
        if (active) {
            binding.tvDisappearLabel.setText(
                    "Disappearing messages: "
                    + DisappearingMessagesManager.formatDuration(durationMs));
        }
    });
}

// Step D: Menu item in chat overflow menu (menu_chat.xml)
/*
<item
    android:id="@+id/action_disappearing"
    android:title="⏳ Disappearing Messages"
    android:showAsAction="never"/>
*/

// In onOptionsItemSelected():
case R.id.action_disappearing:
    DisappearingTimerDialog.show(getSupportFragmentManager(),
            disappearingManager.getCurrentDuration(),
            duration -> disappearingManager.setTimer(duration));
    return true;

// Step E: Schedule delete when message is marked "read"
//         In your existing message status listener (where you set double-tick):
private void onMessageStatusChanged(String messageId, String status, long deliveredAt) {
    if ("read".equals(status)) {
        disappearingManager.scheduleDelete(messageId, deliveredAt, () -> {
            // Remove from local Room DB
            messageViewModel.deleteMessage(messageId);
            // RecyclerView auto-updates via LiveData/PagingData
        });
    }
}

// Step F: Re-schedule on resume (for messages that expired while app was closed)
@Override
protected void onResume() {
    super.onResume();
    messageViewModel.getAllMessages(chatId).observe(this, messages -> {
        disappearingManager.rescheduleOnResume(messages,
                expiredId -> messageViewModel.deleteMessage(expiredId));
    });
}

// Step G: Clean up in onDestroy()
@Override
protected void onDestroy() {
    super.onDestroy();
    if (disappearingManager != null) disappearingManager.stopListening();
    // ... existing cleanup ...
}

// ── PART 2: Message.java — add expiresAt field ────────────────────────
public long expiresAt = 0L;  // timestamp (ms) when message auto-deletes (0 = no expiry)

// ── PART 3: MessageEntity.java — add expiresAt column ────────────────
@ColumnInfo(name = "expires_at")
public long expiresAt = 0L;

// ── PART 4: MessageDao.java — add deleteMessage ───────────────────────
@Query("DELETE FROM messages WHERE message_id = :messageId")
void deleteMessage(String messageId);

@Query("UPDATE messages SET deleted = 1, text = NULL, image_url = NULL, "
     + "media_url = NULL, thumbnail_url = NULL WHERE message_id = :messageId")
void tombstoneMessage(String messageId);

// ── PART 5: MessageAdapter — render deleted/tombstoned messages ───────
// In bindViewHolder() — check msg.deleted:
if (msg.deleted) {
    holder.tvMessageText.setText("⏳ This message has disappeared");
    holder.tvMessageText.setTextColor(0xFFAAAAAA);
    holder.tvMessageText.setTypeface(null, android.graphics.Typeface.ITALIC);
    // Hide image/media views
    holder.ivImage.setVisibility(View.GONE);
    return;
}

// ── PART 6: Countdown timer in bubble (optional visual) ───────────────
// For each visible message with expiresAt set, show countdown:
//
// In MessageAdapter.onBindViewHolder():
if (msg.expiresAt > 0 && !msg.deleted) {
    long remaining = msg.expiresAt - System.currentTimeMillis();
    if (remaining > 0 && remaining < 3_600_000) {  // Show only if < 1hr left
        holder.tvTimer.setVisibility(View.VISIBLE);
        holder.tvTimer.setText(DisappearingMessagesManager.formatDurationShort(remaining));
        // For live countdown, use a Handler/Coroutine to refresh every second
    } else {
        holder.tvTimer.setVisibility(View.GONE);
    }
}

// ── PART 7: activity_chat.xml — add header banner ────────────────────
/*
<!-- Below your main toolbar LinearLayout: -->
<include
    android:id="@+id/disappear_header"
    layout="@layout/layout_disappear_header"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:visibility="gone"/>
*/

// ── PART 8: Firebase data structure ──────────────────────────────────
/*
chats/{chatId}:
{
  "disappearingTimer": 86400000,   // ms (null = off)
  ...
}

chats/{chatId}/messages/{msgId}:
{
  "text":      "Hello!",
  "expiresAt": 1717516800000,     // set when status → "read"
  "deleted":   false,             // true when expired
  ...
}
*/

// ── PART 9: AndroidManifest.xml (no changes needed) ──────────────────
// DisappearingTimerDialog is a DialogFragment — no manifest entry required.
