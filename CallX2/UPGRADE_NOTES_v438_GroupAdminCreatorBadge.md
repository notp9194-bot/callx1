# v438 — Group admin / creator badge next to the sender name

Small pill after the sender name in a received group bubble's name row: **Creator** or **Admin**
(same labels/roles the member list already uses — `GroupMemberAdapter`). Plain members get nothing.
Pill = the member's own name color (per-member palette) at ~18% fill, solid text, 9sp bold.

## Data
Roles live in `groups/{id}/members/{uid}/role` = `"creator" | "admin" | "member"` (`NewGroupActivity` writes `creator`).
`GroupChatActivity.memberRoles` is already the live map its members listener mutates in place, so it is wired the
same way as `memberPhotos`: `pagingAdapter.setGroupMemberRoles(memberRoles)` once — bind reads through it, no extra reads.

## Where it shows
- Only where the NAME row shows: first bubble of a same-sender run (v436 run-head gate). Broadcast rows never carry a pill.
- Never on my own (sent) bubbles — they have no name row.

## Canvas view
- `setGroupSenderBadge(label)`; `clearGroupSender()` and the 1-arg `setGroupSender(name)` (1:1 broadcast label) reset it.
- Draw-only, zero layout impact: pill height is clamped to the name text height and it sits AFTER the name in a row that
  already exists, so no size-signature key / re-measure. Skipped if it wouldn't fit inside the view width (name row isn't
  width-constrained, so a very long name could otherwise push it off-screen and clip it).

## Live updates
`GroupChatActivity` members listener diffs `role` (`memberRoles.put()` returns the previous) and calls
`onMemberRolesChanged(uids)`: re-binds only on-screen (± buffer) run-head rows of those senders through the existing
`PAYLOAD_GROUP_SENDER` path (no bubble re-measure, no Glide reload). First arrival counts as a change, so rows bound before the
members snapshot landed pick up their pill.

## Also fixed (from v437)
Seen-by strip: a plain `invalidate()` marks the whole cached bubble stale (see the `invalidate()` override), and the strip's
right-anchored rect was only laid out in `onMeasure`, so a changed circle COUNT drew from a stale left edge. Now
`invalidateSeenByRegion()` re-derives the rect and dirties only old ∪ new strip area.

Files: canvas/MessageBubbleCanvasView.java, MessagePagingAdapter.java, group/GroupChatActivity.java
Not compile-tested here (no Android SDK/Gradle) — Java parse-checked only; run a normal build.
