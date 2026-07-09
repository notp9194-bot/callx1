# v71 — status-reply/reaction: open the exact status + make bubble recognizable

Two follow-up gaps from v70:

## 1. Tap opened the viewer but not "that" status
`navigateToOriginalMsg()` opened `StatusViewerActivity` for the right owner,
but the viewer had no idea *which* status to show — `load()` always started
at `idx = 0` (the owner's oldest active status), regardless of which one was
actually replied/reacted to.

**Fix:**
- `replyToId` already encodes the exact status id as `"status_" + statusId`
  — `ChatActivity.navigateToOriginalMsg()` now strips that prefix and passes
  it as a new `targetStatusId` intent extra.
- `StatusViewerActivity.load()` now searches the loaded items for that id
  and jumps `idx` straight to it. If that specific status has since expired
  but the owner still has others, it shows a toast ("That status is no
  longer available") and opens at the first one instead of failing silently.

## 2. Reply/reaction bubble looked like a normal quoted reply
The quote box's sender-name line just showed the owner's plain name — same
as it would for a normal "replying to this chat message" quote, so there was
no way to tell at a glance that a bubble was actually about a status.

**Fix:** added `StatusReplyBottomSheet.statusReplyLabel(ownerName)` —
`"📷 <Name>'s Status"` — and switched all three status-originated message
paths (full reply sheet, quick inline reply, status reaction) to use it
instead of the bare name. Now the quote box itself signals "this is a status
reply," independent of the tap-to-open behavior.

## Files touched
- `feature-chat/src/main/java/com/callx/app/conversation/ChatActivity.java`
- `feature-status/src/main/java/com/callx/app/viewer/StatusViewerActivity.java`
- `feature-status/src/main/java/com/callx/app/interactions/StatusReplyBottomSheet.java`
