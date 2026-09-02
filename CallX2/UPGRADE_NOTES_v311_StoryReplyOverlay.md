# v311 — Instagram-style latest-reply overlay on own story

## What changed
Story replies used to be entirely private: a text reply sent from
`StatusReplyBottomSheet` only ever landed as a quoted chat DM
(`messages/{chatId}/{msgId}`). The story owner had no way to see "someone
just commented" unless they happened to open that chat — nothing about it
showed on the status itself, unlike Instagram, where reopening your own
story shows the latest replier's avatar + comment as a small overlay.

Added a public-on-the-status-node mirror of every reply, and a
bottom-left overlay + full-list bottom sheet in the viewer that read it —
same idea as Instagram, same privacy boundary as the existing
"seen by" count (owner-only, never shown to other viewers).

### Data model
- `StatusItem.ReplyPreview` (new nested class): `uid`, `name`,
  `avatarUrl`, `text`, `timestamp`.
- `StatusItem.replies`: `Map<pushId, ReplyPreview>` — keyed by push id
  (not uid) since one viewer can send several separate comments and each
  should be kept, not overwritten by the next (unlike `reactions`, which
  is one emoji per uid).
- `StatusItem.getLatestReply()` — convenience getter, mirrors the
  `getReaction()`/`hasReaction()` style already on this model.
- `FirebaseUtils.getStatusRepliesRef(ownerUid, statusId)` →
  `status/{ownerUid}/{statusId}/replies`.

### Write path
- `StatusReplyBottomSheet.sendReply()` already looked up the sender's
  `name`/`thumbUrl` (for the push notification) in a `users/{myUid}`
  read; that same callback now also pushes a `ReplyPreview` entry to
  `getStatusRepliesRef(ownerUid, item.id)`. No new read added — reused
  the one already in flight.
- Scope: only the plain-text reply path (`StatusReplyBottomSheet.show()`
  / `sendReply()`). Sticker-response paths (poll/quiz/slider/countdown/
  question) still only send the chat DM, same as before.

### Read / display path — `StatusViewerActivity`
- New `updateLastReplyOverlay(StatusItem s)`, called right after
  `updateSeenByInfo(s)` on every segment render. Owner-only, same
  `myUid.equals(ownerUid)` check as the seen-by count.
- Attaches a live `ValueEventListener` on the current segment's
  `replies` node (so a reply landing while the owner is re-watching
  their own story shows up immediately) and detaches the previous
  segment's listener first — one listener per currently-shown segment,
  not one per swipe.
- **Stacked avatars (up to 3):** `bindLastReplyBubble()` sorts all
  replies newest-first and fills `iv_last_reply_avatar_1/2/3` — newest
  commenter frontmost/rightmost (`_3`), older ones filling leftward —
  overlapping via negative-equivalent `layout_marginStart` inside a
  `FrameLayout`, same visual idea as Instagram's recent-repliers
  cluster. Unused slots (fewer than 3 replies) are hidden. The text row
  always shows the newest comment.
- **Auto-hide:** 4s after showing (`REPLY_OVERLAY_AUTO_HIDE_MS`), the
  bubble fades out (300ms alpha animation) and goes `GONE` — mirrors
  Instagram's own reply indicator not sitting on screen forever. Tapping
  it before then cancels the pending auto-hide and opens the full list
  immediately; a new reply arriving restarts the 4s timer. The
  auto-hide `Runnable` is posted on the existing shared `handler` but
  removed by reference (`handler.removeCallbacks(replyOverlayAutoHide)`)
  so it never clashes with the unrelated progress-bar timer already
  using that same `Handler`.
- `StatusItem.replies` on the in-memory segment is kept in sync with
  every listener update, so tapping the bubble opens
  `StatusRepliesBottomSheet` off the same data with no extra fetch.
- Tapping the bubble pauses the story and opens the new
  `StatusRepliesBottomSheet` (full list, newest first, avatar + text +
  time per row) — tapping a row jumps straight into the DM thread with
  that commenter (`ChatActivity`, same `partnerUid/Name/Photo/Thumb`
  extras `ChatListAdapter` already uses).

## Files touched
- `core/src/main/java/com/callx/app/models/StatusItem.java`
- `core/src/main/java/com/callx/app/utils/FirebaseUtils.java`
- `feature-status/src/main/java/com/callx/app/interactions/StatusReplyBottomSheet.java`
- `feature-status/src/main/java/com/callx/app/interactions/StatusRepliesBottomSheet.java` (new)
- `feature-status/src/main/java/com/callx/app/viewer/StatusViewerActivity.java`
- `feature-status/src/main/res/layout/activity_status_viewer.xml`
