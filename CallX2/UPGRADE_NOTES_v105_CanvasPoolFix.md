# v105 — Canvas RecycledViewPool Fix

## What changed
`TYPE_CANVAS_SENT` (11) and `TYPE_CANVAS_RECEIVED` (12) were missing from the
`RecycledViewPool` sizing in both `ChatActivity.java` and `GroupChatActivity.java`.
Every other view type (TYPE_SENT, TYPE_RECEIVED, TYPE_STATUS_SEEN, etc.) had an
explicit pool size; canvas types silently fell back to RecyclerView's default
of 5 — despite `isCanvasEligible()` now covering text/image/video/gif/file/
audio/poll/contact/location/multi_media/reel_share, i.e. almost every bubble
in the chat.

On a fast fling, the pool of 5 canvas views was exhausted almost immediately,
forcing fresh `MessageBubbleCanvasView` allocation (+ full Paint/StaticLayout
setup) instead of reuse — the exact failure mode the existing comment for
TYPE_SENT/RECEIVED already described, just not extended to the newer canvas
types that replaced them.

- `ChatActivity.java`: pool sizes for types 11/12 set to 18 (same as
  TYPE_SENT/RECEIVED).
- `GroupChatActivity.java`: pool sizes for types 11/12 set to 10 (same as
  sent/received there).

## What was NOT changed, and why

**Background/async StaticLayout precompute** — this was considered but not
implemented. The codebase already tried an equivalent optimization
(`PrecomputedTextCompat`, sync and async) and reverted it — see the
`asyncTextEnabled` field's javadoc in `MessagePagingAdapter.java`. It caused a
confirmed bug: the bubble's first `setText()` and a later swapped-in layout
could disagree on line count if the swap landed before the view's width was
final, producing bubbles that render only partially inside the viewport. The
fix at the time was to guarantee exactly one layout pass per bind. Re-adding
a background-precomputed-layout cache would risk reintroducing that same
class of bug, and this can't be verified without a device/emulator to test
against — flagging instead of blindly re-adding it.

**Picture/bitmap caching for poll & media-group bubbles** — same category of
risk as above (a cached render diverging from the live bind state), and same
reason it's not included here without the ability to build/run and verify.

Both remain real options if you want to revisit them with a way to test on
a device — happy to take a more careful, incremental pass at either.
