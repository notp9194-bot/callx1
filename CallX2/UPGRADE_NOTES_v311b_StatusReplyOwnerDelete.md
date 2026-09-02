# v311b — StatusRepliesBottomSheet: owner delete/hide a reply (long-press)

## The feature

`StatusRepliesBottomSheet` only ever opens for the story owner (from the
bottom-left "who just commented" overlay in `StatusViewerActivity`, which is
itself already owner-only/seen-by-style private). Every row in the sheet is
now long-press-able for the owner:

- Long-press a reply row → confirm dialog ("Delete reply?").
- Confirm → removes just that one entry from the public
  `status/{ownerUid}/{statusId}/replies/{pushId}` node via the existing
  `FirebaseUtils#getStatusRepliesRef(ownerUid, statusId)` ref (same ref
  `StatusReplyBottomSheet#sendReply` writes into).
- The row + its divider are pulled out of the open sheet immediately (no
  full re-show/re-query), and the "Replies (N)" header count updates to
  match.
- `StatusViewerActivity`'s existing live `repliesListenerRef` (attached in
  the owner-only branch) picks up the Firebase removal on its own, so the
  bottom-left overlay / latest-reply state stays in sync even if the sheet
  is reopened later.

## Implementation notes

- `item.replies` is a `Map<pushId, ReplyPreview>`; the sheet previously
  iterated `.values()` only, discarding the pushId needed to target a
  single entry for deletion. It now sorts `entrySet()` (still newest-first
  by `timestamp`) and threads the pushId (`replyKey`) and `item.id`
  (`statusId`) through to `addReplyRow()`.
- Long-press is gated by `isOwner = myUid != null && myUid.equals(ownerUid)`
  computed once in `show()` — belt-and-suspenders in case this static
  method is ever called from a non-owner-only entry point later; today it's
  always true given the one call site.
- Delete only ever removes the public preview under `status/.../replies/`;
  it does not touch the private chat DM the replier already received
  (`messages/{chatId}/{msgId}`) — that's a separate delete-for-me/everyone
  flow already handled by the chat message-delete system, out of scope here.
- No new files, no layout/manifest changes — pure logic addition inside
  `StatusRepliesBottomSheet.java`.

Files touched: `StatusRepliesBottomSheet.java`.
