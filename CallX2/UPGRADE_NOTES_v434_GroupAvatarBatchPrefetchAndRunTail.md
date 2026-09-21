# v434 — Group sender avatar: batch prefetch on open + same-uid consecutive skip

Follows v433 (payload refresh + cached placeholder). Items: batch prefetch, and
WhatsApp-style "avatar only on the last bubble of a sender run".

## A. Same-uid consecutive skip (biggest win)

In a run of consecutive messages from one sender only the LAST bubble binds and
draws the 20dp avatar; earlier ones skip `bindBitmap()` entirely and hold no bitmap.

**Deliberate deviation from "setGroupSenderAvatarVisible(false) on the rest":**
that flag also drives the layout (it reserves the avatar column and shifts
`bubbleLeft` / `maxTextWidth`). Turning it off on earlier bubbles would pull them
left and misalign the run, and every last-ness flip would trigger a re-measure.
So the column stays reserved on ALL received group bubbles (run stays aligned,
same as WhatsApp) and a new draw-only flag decides who draws the circle:
- `MessageBubbleCanvasView.setGroupSenderAvatarShown(boolean)` — invalidate only, never re-measure
- draw gate: `hasGroupSenderAvatar && groupSenderAvatarShown`
- late async bitmap for a row that stopped being tail is dropped, not pinned

**Tail rule** (`MessagePagingAdapter.isGroupAvatarRunTail`): tail if newest row, or next
row is a different sender / system row (`isNonGroupingRow`) / a row that won't draw a
group avatar itself (legacy non-canvas bubble → the canvas row before it keeps the
avatar, otherwise the run would have none). Uses `peek()`, no Paging load hints.
`getItemViewType()` body extracted into `viewTypeOf(Message)` (pure move) so the
neighbor's view type can be checked from a peeked Message.

**Staleness (the trap):** tail-ness depends on the NEXT row, and DiffUtil only rebinds
a row whose own content changed — a new same-sender message would leave the previous
bubble still drawing its avatar (two avatars in one run). An `AdapterDataObserver`
(group chats only) re-evaluates the row just above every insert/remove/move through the
existing `PAYLOAD_MEMBER_AVATAR` path (`bindGroupSenderAvatarOnly` now recomputes tail
first). Deferred one frame if RecyclerView is mid-layout.

## B. Batch prefetch on open

`ChatAvatarBinder.prefetchBatch(ctx, photos)` + `MessagePagingAdapter.prefetchSenderAvatars()`.
- Warms distinct senders of the visible window (viewport ±16 rows; newest 40 rows if nothing
  laid out yet — list is stackFromEnd), max 24, newest first, self excluded.
- Triggers: first non-empty page (one-shot listener in `GroupChatActivity`) and every
  member-photo change (`onMemberPhotosChanged`). Coalesced 100ms so trickling per-member
  profile reads still form one batch; idempotent per photo URL.

**Deliberate deviation from "ChatAvatarBinder.prefetch() with DiskCacheStrategy.DATA":**
1. `prefetch()` warms `TIER` (50dp→MEDIUM) URLs; the group avatar reads `TIER_INLINE` (24dp) URLs — different URL, so it would warm entries nothing here requests.
2. A `DATA` (raw bytes) entry is never read by a `DiskCacheStrategy.RESOURCE` request (Glide skips the DATA_CACHE stage for RESOURCE), and `bindBitmap()` is RESOURCE — so the later bind would still go to network.
3. `prefetch()` is velocity/metered-gated and depth-limited — meant for list scrolling, not "warm these N members".

So `prefetchBatch` issues the exact request `bindBitmap` will (both now built by one shared
`bitmapRequest()` so they can't drift) → real Glide memory/RESOURCE-disk hit. Normal priority
(tiny ~72px assets; LOW could park behind/under a visible row's own load). Does not touch
L2/L3 (L2 is weak-ref; nothing would keep a preloaded bitmap alive — real bind fills them).

Not fixed here (pre-existing, out of scope): the chat-LIST `prefetch()` has the same
DATA-vs-RESOURCE mismatch against `AvatarBinderCore.bind()` (RESOURCE).

## Files
- feature-chat/.../cache/ChatAvatarBinder.java
- feature-chat/.../conversation/MessagePagingAdapter.java
- feature-chat/.../conversation/canvas/MessageBubbleCanvasView.java
- feature-chat/.../group/GroupChatActivity.java

## Not compile-tested here (no Android SDK/Gradle in sandbox) — run a normal build.
