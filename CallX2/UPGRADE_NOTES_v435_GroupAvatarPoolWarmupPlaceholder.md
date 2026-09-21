# v435 — RecycledViewPool warm-up: pre-set the group-avatar placeholder

Last item of the group-avatar list (follows v433 payload+placeholder bitmap, v434 batch prefetch + run-tail skip).

`MessagePagingAdapter.warmUpRecycledViewPool()` (group chats only — `isGroup`):
- calls `MessageBubbleCanvasView.prewarmGroupAvatarPlaceholder(ctx)` first — builds the shared
  flat-gray placeholder Bitmap on the pre-warm frame. Runs even when the shared pool was already
  full and no holder is created below, so the first unresolved-avatar draw() of the first scroll
  never pays createBitmap + drawCircle.
- every TYPE_CANVAS_RECEIVED holder it creates gets `presetGroupSenderAvatarPlaceholder()`, which
  pre-sets the view's memoized placeholder reference → first draw = plain `drawBitmap`, no
  synchronized lookup.
- 1:1 chat (`isGroup == false`) skips all of it.

`MessageBubbleCanvasView`: placeholder pixel size now comes from one formula,
`groupAvatarPlaceholderPx(density) = round(GROUP_AVATAR_SIZE_DP * density)`, shared by draw-time,
the per-view preset and the static pre-warm. (Draw-time previously used the float rect width, which
can round differently at a .5px boundary, e.g. 20dp @ 2.625 density, and would have defeated the
pre-warm with a second cache entry.)

Files: MessagePagingAdapter.java, canvas/MessageBubbleCanvasView.java

Not compile-tested here (no Android SDK/Gradle in sandbox) — run a normal build.
