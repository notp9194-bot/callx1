# v69 — Status Reply + Reaction, WhatsApp-level fix

## What was already working (audited, no change needed)
- Emoji reactions: `Message.reactions` (uid→emoji) already renders as a
  floating badge on every bubble type via `MessageBubbleCanvasView.setReactions()`
  / `MessagePagingAdapter.bindReactionsOnly()` — type-agnostic, always bound.
- Reply quote box: `replyToSenderName/replyToText/replyToType/replyToMediaUrl`
  already render generically via `MessageBubbleCanvasView.setReply()` for
  every message type — including status replies, since
  `StatusReplyBottomSheet.sendReply()` just stamps a normal `type:"text"`
  chat message with those replyTo* fields (`replyToId = "status_" + statusId`).

So a reply-to-status already showed up in the chat thread as a quoted
preview (owner name + status preview text/thumbnail), same as any other
reply — that part was already WhatsApp-style.

## The actual bug (fixed)
Tapping that quote box called `navigateToOriginalMsg(replyToId)`, which
only knows how to search for a **real** message by id — `"status_..."`
is a synthetic id, so the lookup always failed (pagingAdapter miss + Room
DB miss) and surfaced a confusing **"Original message not found"** toast.
WhatsApp instead reopens the status itself.

**Fix — `ChatActivity.navigateToOriginalMsg()`:**
- Detects the `"status_"` prefix up front.
- Fires the same `ACTION_OPEN_STATUS` deep-link the `status_seen` bubble
  already uses, targeting `partnerUid`/`partnerName` (the chat partner in
  a status-reply thread is always the status owner, since
  `StatusReplyBottomSheet` always sends into the `myUid_ownerUid` 1:1 chat).
- Falls back to a toast if `partnerUid` is somehow unset, or if the
  status-viewer activity can't be resolved.

**Fix — `StatusViewerActivity.load()`:**
- If the owner's status has since expired/been deleted (e.g. you tap an
  old status-reply quote a day later), the viewer used to just silently
  `finish()`. Now shows **"This status is no longer available"** before
  closing, matching WhatsApp's behavior.

## Files touched
- `feature-chat/src/main/java/com/callx/app/conversation/ChatActivity.java`
- `feature-status/src/main/java/com/callx/app/viewer/StatusViewerActivity.java`
