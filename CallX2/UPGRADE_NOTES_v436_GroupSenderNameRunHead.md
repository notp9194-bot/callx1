# v436 — Group sender name: only on the first bubble of a same-sender run

WhatsApp behaviour: name on the FIRST bubble of a run, avatar on the LAST (v434).
Before this, every received group bubble drew its name.

## Rule (`MessagePagingAdapter.isGroupNameRunHead`)
Name shows when the row is the oldest loaded row, or the row above is a different sender /
a system row (`isNonGroupingRow`, incl. date separator) / a broadcast / has no sender name.
Broadcast messages always show their name (the 📢 badge is per-message) and break a run
on both sides. `peek()` only — no Paging load hints; an unloaded row above counts as "head".

## Why this is NOT the same as the avatar change
- Avatar gate (v434) is draw-only: column stays reserved, flipping = invalidate.
- Name gate is layout-affecting: a hidden name frees its row, so the bubble gets shorter/narrower
  (the compact-run look; `applyGroupedSpacing` already used the tight gap for same-sender rows).
  `setGroupSender()/clearGroupSender()` go through the existing size-signature relayout, so a row
  only re-measures when its name visibility actually changed.
- Avatar column is reserved by `hasGroupSenderAvatar`, independent of `hasGroupSender` (verified in
  onMeasure) — hiding the name does not move the bubble.

## Staleness (the part that needs care)
Name depends on the PREVIOUS row, avatar on the NEXT; DiffUtil rebinds neither when only a
neighbor changes. The `AdapterDataObserver` (group chats only) now refreshes BOTH sides of every
change point: insert → row above (`start-1`) and row below (`start+count`); remove → `start-1` and `start`.
Covers: older page prepended (oldest row's name must hide), message deleted, message inserted in the middle.
Refresh goes through the payload, renamed `PAYLOAD_MEMBER_AVATAR` → `PAYLOAD_GROUP_SENDER`,
handler `bindGroupSenderOnly()` (was `...AvatarOnly`): re-evaluates name, spacing, avatar tail, avatar bitmap.

## Also
- `groupSenderLabel()` helper shared by full bind and payload path (📢 prefix).
- Payload path now also calls `applyGroupedSpacing()` (was only run on full bind, so a prepend could
  leave a stale gap above a row whose name just hid).
- Legacy (non-canvas) received bubbles still show the name on every message — untouched. A canvas
  bubble right after a same-sender legacy bubble hides its name (the legacy one already shows it).
- Field doc in MessageBubbleCanvasView corrected (avatar column is not gated on the name).

Files: MessagePagingAdapter.java, canvas/MessageBubbleCanvasView.java
Not compile-tested here (no Android SDK/Gradle) — run a normal build.
