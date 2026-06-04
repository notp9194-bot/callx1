// ═══════════════════════════════════════════════════════════════════
// ChatActivity.java mein ye changes karo for Search integration
// ═══════════════════════════════════════════════════════════════════

// ── Step 1: Add constant at top of ChatActivity ──────────────────────
private static final int REQ_SEARCH = 501;

// ── Step 2: In onOptionsItemSelected(), add search case ──────────────

@Override
public boolean onOptionsItemSelected(@NonNull MenuItem item) {
    int id = item.getItemId();

    if (id == R.id.action_search) {
        // ← ADD THIS CASE
        Intent searchIntent = new Intent(this, SearchInChatActivity.class);
        searchIntent.putExtra("chatId",      chatId);
        searchIntent.putExtra("currentUid",  currentUid);
        searchIntent.putExtra("partnerName", partnerName);
        startActivityForResult(searchIntent, REQ_SEARCH);
        return true;
    }

    // ... existing cases ...
    return super.onOptionsItemSelected(item);
}

// ── Step 3: Handle search result (scroll to tapped message) ──────────

@Override
protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);

    if (requestCode == REQ_SEARCH && resultCode == RESULT_OK && data != null) {
        String msgId = data.getStringExtra("messageId");
        long   ts    = data.getLongExtra("timestamp", -1L);

        if (msgId != null && ts > 0) {
            scrollToMessage(msgId, ts);
        }
    }

    // ... existing onActivityResult handling ...
}

// ── Step 4: Add scrollToMessage() helper ─────────────────────────────

/**
 * Scroll to a message given its ID and timestamp.
 *
 * Strategy:
 *   1. Count how many messages are newer than this timestamp in Room DB
 *   2. Total count - newer count = approximate position from bottom
 *   3. Use LinearLayoutManager.scrollToPositionWithOffset() for smooth jump
 *   4. Optionally flash/highlight the row via MessageHighlightAnimator
 */
private void scrollToMessage(String messageId, long timestamp) {
    ioExecutor.execute(() -> {
        AppDatabase db = AppDatabase.getInstance(this);

        // Count messages newer than target (for position estimate)
        int newerCount = db.messageDao().countMessagesAfterTimestamp(chatId, timestamp);
        int totalCount = db.messageDao().getMessageCount(chatId);
        int position   = Math.max(0, totalCount - newerCount - 1);

        runOnUiThread(() -> {
            LinearLayoutManager llm =
                    (LinearLayoutManager) binding.rvMessages.getLayoutManager();
            if (llm != null) {
                llm.scrollToPositionWithOffset(position, 0);
            }

            // Highlight the message row after scroll settles
            binding.rvMessages.postDelayed(() -> {
                RecyclerView.ViewHolder vh =
                        binding.rvMessages.findViewHolderForAdapterPosition(position);
                if (vh != null) {
                    MessageHighlightAnimator.flash(vh.itemView);
                }
            }, 350);
        });
    });
}

// ═══════════════════════════════════════════════════════════════════
// AndroidManifest.xml — Add SearchInChatActivity declaration
// (in feature-chat/src/main/AndroidManifest.xml)
// ═══════════════════════════════════════════════════════════════════

/*
<activity
    android:name="com.callx.app.activities.SearchInChatActivity"
    android:theme="@style/AppTheme"
    android:windowSoftInputMode="stateVisible|adjustResize"
    android:exported="false" />
*/
