# v51 — Canvas Message States (Deleted / Edited / Forwarded / Broadcast / Disappearing)

Closes the 5 gaps `isCanvasEligible()` used to punt to the old
`item_message_sent/received.xml` path. All five now render inside
`MessageBubbleCanvasView` (Phase 1 Canvas rendering), same as text/image/
media-group messages already did.

## What changed

### `core/.../models/Message.java`
No changes — every field these features need (`deleted`, `edited`,
`editedAt`, `forwardedFrom`, `broadcast`, `expiresAt`) already existed.

### `feature-chat/.../conversation/canvas/MessageBubbleCanvasView.java`
New setters, each documented in place:
- `setDeletedStyle(boolean)` — italic + 60% alpha on the plain-text bubble,
  matching `bindMessage()`'s `tvMessage.setAlpha(0.6f)`. Caller substitutes
  the placeholder text ("This message was deleted" / "You deleted this
  message") before calling this.
- `setForwardedFrom(String)` / `clearForwarded()` — "↪ Forwarded from X",
  11sp italic #888888, stacked directly below the pinned-label/
  group-sender row (mirrors `tv_forwarded`'s `constraintTop_toBottomOf
  tv_sender_name`).
- `setExpiryText(String)` / `clearExpiry()` — "⏳ mm:ss" drawn in the
  footer row just before the timestamp (mirrors `tv_expiry`). Only wired
  for the plain-text bubble so far — see Known gaps below.
- `setGroupSender(String)` is now also the broadcast-badge path: pass
  `"📢 " + name` for a broadcast in a group, or `"📢 Broadcast"` for a 1:1
  broadcast — same row the group sender-name already used.

`onMeasure`/`onDraw` reshuffled so the "above the bubble" area now stacks
**two** rows (row 1: pinned/group-sender · row 2: forwarded) instead of
one, with per-row baselines cached from `onMeasure` instead of recomputed
in `onDraw`.

### `feature-chat/.../conversation/MessagePagingAdapter.java`
- `isCanvasEligible()` — removed the deleted/edited/forwarded/broadcast
  exclusions entirely (all four are now modeled). `expiresAt` is still
  excluded, but **only** for non-text types (image/multi_media), since
  their translucent timestamp pill doesn't have room for the countdown
  yet — plain-text disappearing messages are fully eligible.
- `bindCanvasMessage()`:
  - Computes the `"  ✏️ edited"` suffix once, up front, so it flows into
    whichever `bind*()` call fires below (text/image/multi_media/
    deleted-placeholder) — same string bindMessage() appends to `tv_time`.
  - A deleted message short-circuits straight to `cv.bind(placeholder, …)`
    + `setDeletedStyle(true)`, regardless of its original `type` — mirrors
    `bindMessage()`'s early return (reply/reactions/pinned/forwarded/
    sender-name still bind normally either way, same as legacy).
  - Broadcast badge and forwarded label wired into the existing
    group-sender-name section and a new dedicated block, respectively.
  - Disappearing countdown wired through the same shared
    `ExpiryTickManager` the legacy path already uses (`onViewRecycled`
    already unregistered generically by holder — no change needed there).
- `DIFF.areContentsTheSame()` — added `forwardedFrom` and `expiresAt` so a
  forward-only or expiry-only change actually triggers a rebind (this was
  a latent gap on the legacy path too, not Canvas-specific).

## Known gaps / deliberately out of scope this pass
- Disappearing countdown pill for image/multi_media canvas bubbles — still
  routes to the legacy path when `expiresAt > 0` (see above).
- Tapping the "✏️ edited" suffix to open edit history (works on the legacy
  path via `MessageEditHistoryController`) isn't wired for the canvas
  footer yet — text-only, no click target.
- Deleted canvas bubbles still draw the sent-side tick (legacy hides the
  whole `tv_status`/tick row entirely once deleted) — cosmetic-only diff.
