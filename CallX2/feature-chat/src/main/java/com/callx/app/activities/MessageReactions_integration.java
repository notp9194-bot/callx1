// ═══════════════════════════════════════════════════════════════════
// Message Reactions — Complete Integration Guide
// ═══════════════════════════════════════════════════════════════════

// ── PART 1: MessagePagingAdapter.java — bind reactions ────────────────

// Step A: In VH (ViewHolder) — add reaction row reference
LinearLayout reactionRow;   // container in item_message_*.xml

// Step B: In item_message_sent.xml and item_message_received.xml — add:
/*
  Below the bubble FrameLayout, add:
  <include
      android:id="@+id/reaction_scroll"
      layout="@layout/item_reaction_bubble"
      android:layout_width="wrap_content"
      android:layout_height="wrap_content"
      android:visibility="gone"/>
*/

// Step C: In onBindViewHolder() — bind reactions for each message:
private void bindReactions(VH holder, Message msg) {
    // Listen for live reaction updates
    reactionManager.listenReactions(msg.messageId, (msgId, summaries) -> {
        // Run on main thread (Firebase listener already delivers on main thread)
        holder.reactionRow.removeAllViews();

        if (summaries.isEmpty()) {
            holder.reactionScroll.setVisibility(View.GONE);
            return;
        }
        holder.reactionScroll.setVisibility(View.VISIBLE);

        LayoutInflater inflater = LayoutInflater.from(holder.itemView.getContext());
        Context ctx = holder.itemView.getContext();

        for (MessageReactionManager.ReactionSummary r : summaries) {
            View chip = inflater.inflate(R.layout.item_reaction_chip,
                    holder.reactionRow, false);

            TextView tvEmoji = chip.findViewById(R.id.tv_emoji);
            TextView tvCount = chip.findViewById(R.id.tv_count);
            View     chipRoot = chip.findViewById(R.id.chip_root);

            tvEmoji.setText(r.emoji);

            // Show count only if > 1
            if (r.count > 1) {
                tvCount.setText(String.valueOf(r.count));
                tvCount.setVisibility(View.VISIBLE);
            } else {
                tvCount.setVisibility(View.GONE);
            }

            // Highlight if current user reacted with this emoji
            chipRoot.setBackgroundResource(r.currentUserReacted
                    ? R.drawable.bg_reaction_chip_active
                    : R.drawable.bg_reaction_chip);
            if (r.currentUserReacted) {
                tvCount.setTextColor(0xFF6C63FF); // brand_primary
            }

            // Tap chip → toggle this reaction
            chipRoot.setOnClickListener(v ->
                    reactionManager.toggleReaction(msg.messageId, r.emoji));

            // Long-tap chip → show who reacted (tooltip or bottom sheet)
            chipRoot.setOnLongClickListener(v -> {
                showReactorNames(ctx, r);
                return true;
            });

            holder.reactionRow.addView(chip);
        }
    });
}

// Step D: Long-press on bubble → show emoji picker
holder.bubble.setOnLongClickListener(v -> {
    // Find current user's existing reaction for this message (if any)
    String currentReaction = getCurrentUserReaction(msg.messageId);

    EmojiReactionPickerView.showAt(
            holder.itemView.getContext(),
            holder.bubble,
            currentReaction,
            emoji -> reactionManager.toggleReaction(msg.messageId, emoji)
    );
    return true;  // consume long press (don't trigger context menu)
});

// ── PART 2: ChatActivity.java — init + cleanup ────────────────────────

// Step A: Field
private MessageReactionManager reactionManager;

// Step B: In onCreate() after chatId is set
reactionManager = new MessageReactionManager(chatId);

// Step C: Pass reactionManager to adapter
adapter = new MessagePagingAdapter(currentUid, reactionManager);

// Step D: In onDestroy()
if (reactionManager != null) reactionManager.stopAllListeners();

// ── PART 3: showReactorNames() — tooltip showing who reacted ──────────

private void showReactorNames(Context ctx,
                               MessageReactionManager.ReactionSummary r) {
    // Resolve UIDs to display names (from your existing UserRepository)
    // Simple fallback: show count
    String text = r.emoji + "  " + r.count + " people reacted";

    new com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle(r.emoji + " " + r.count)
            .setMessage(text)
            .setPositiveButton("OK", null)
            .show();
}

// ── PART 4: getCurrentUserReaction() — helper ─────────────────────────

// Cache current user's reactions in a Map<messageId, emoji>
// Updated by the reaction listener in bindReactions()
private final java.util.Map<String, String> myReactions = new java.util.HashMap<>();

private String getCurrentUserReaction(String messageId) {
    return myReactions.get(messageId); // null if no reaction
}

// In bindReactions() listener — update myReactions:
// for (ReactionSummary r : summaries) {
//     if (r.currentUserReacted) myReactions.put(msgId, r.emoji);
// }

// ── PART 5: Firebase security rules ──────────────────────────────────
/*
"chats": {
  "$chatId": {
    "reactions": {
      "$messageId": {
        "$emoji": {
          "$uid": {
            ".write": "auth.uid === $uid",  // only write your own reaction
            ".read":  "auth != null"
          }
        }
      }
    }
  }
}
*/

// ── PART 6: Firebase data structure ──────────────────────────────────
/*
chats/{chatId}/reactions/{messageId}:
{
  "👍": {
    "uid_alice": true,
    "uid_bob":   true
  },
  "❤️": {
    "uid_carol": true
  },
  "😂": {
    "uid_alice": true   ← Alice switched from 👍 to 😂 (old removed, new added)
  }
}
*/

// ── PART 7: item_message_sent.xml and item_message_received.xml ───────
/*
Add after the main bubble container:

<include
    android:id="@+id/reaction_scroll"
    layout="@layout/item_reaction_bubble"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:layout_gravity="start"
    android:visibility="gone"/>
*/

// ── PART 8: VH constructor — find reaction views ──────────────────────
/*
reactionScroll = v.findViewById(R.id.reaction_scroll);
reactionRow    = reactionScroll.findViewById(R.id.reaction_row);
*/
