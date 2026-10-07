# v465 — Reel comments: delete with Undo

Delete comment / delete reply (after the existing confirm dialog) now hides the item
immediately and shows a Snackbar "Comment/Reply deleted · Undo" for 4s
(UNDO_WINDOW_MS), anchored above the input field. Firebase is only touched when the
window closes without Undo.

- State: pendingDeletes (key "c:<commentId>" / "r:<replyId>" -> commit Runnable).
- Comments: applyFilterAndSort() skips pending ids; header count subtracts them.
  Undo = remove from pending + applyFilterAndSort(). Commit = existing deleteComment().
- Replies: row tagged with R.id.reel_reply_row_id; hideReplyLocally() removes the row +
  cache entry (no rebuild/network), refreshes the "View N more" row, hides the thread
  when it was the last reply. loadRepliesInto() filters pending ids so a refresh
  (e.g. a like) cannot bring them back. Undo = loadRepliesInto(). Commit = deleteReply();
  a local sending/failed copy is also dropped from pendingRepliesByParent.
- deleteReply() refresh now only runs if that thread is still the active one
  (commit can happen seconds later; avoids rebuilding a recycled row).
- Any dismissal other than the Undo tap (timeout, swipe-away, replaced by a newer
  snackbar) commits. onDestroyView() commits everything pending.
- If a Snackbar can't be shown (no view / theme), the delete happens immediately as before.
- New: strings reel_c_undo / reel_c_comment_deleted / reel_c_reply_deleted, id reel_reply_row_id.
- Known limit: process death inside the 4s window leaves the item un-deleted (safe side).
