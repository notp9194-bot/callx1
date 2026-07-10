# v23 — Chat List: Canvas rendering for ticks + typing indicator

## What changed

`item_chat.xml`'s last-message row was a nested `LinearLayout` holding:
- `iv_read_status` — an `ImageView` showing the ✓ / ✓✓ / blue ✓✓ tick as a
  bitmap drawable + `colorFilter` switch
- `tv_last_message` — a plain `TextView` for the last message text (or the
  live "typing..." indicator)

That's now a single `ChatListLastMessageView`
(`feature-chat/.../chatlist/canvas/ChatListLastMessageView.java`) — a plain
`View` whose `onDraw()` paints both the tick strokes and the text directly
on the `Canvas`, the same technique `MessageBubbleCanvasView` already uses
for message bubbles in the chat screen:

- Ticks: two `canvas.drawLine()` strokes per check mark (doubled/offset for
  delivered/read), same as `MessageBubbleCanvasView#drawTick()` /
  `#drawSingleTick()` — no bitmap, no drawable, no `colorFilter` switch.
- Text: `TextUtils.ellipsize(..., TruncateAt.END)` + `canvas.drawText()` —
  no `StaticLayout`/`TextView` needed since a chat-list row is always a
  single line.

`ChatListAdapter` (v23) now drives this through two setters instead of
touching a `TextView`/`ImageView` pair:
- `setMessageText(text, color, italic)` — last-message text or "typing..."
- `setTicks(state, color)` — `TICK_NONE` / `TICK_SENT` / `TICK_DELIVERED` /
  `TICK_READ`

Both setters no-op (skip `invalidate()` entirely) when the new value
matches what's already drawn, so a scroll-driven rebind of an unchanged row
(e.g. a payload-only selection-mode toggle) does zero text-measurement or
draw work for this part of the row. Text is only re-ellipsized when the
raw string or the available width actually changes, not on every
`onDraw()`.

## Why

One less view to inflate, and one less measure/layout pass per row on
every bind — on a chat list that's rebinding rows constantly during fast
scrolling, collapsing 3 objects (a `LinearLayout` + `ImageView` +
`TextView`) into 1 plain `View` reduces per-row overhead the same way
`MessageBubbleCanvasView` already does for the chat screen's message
bubbles.

## Files touched

- `feature-chat/src/main/java/com/callx/app/chatlist/canvas/ChatListLastMessageView.java` (new)
- `feature-chat/src/main/res/layout/item_chat.xml`
- `feature-chat/src/main/java/com/callx/app/chatlist/ChatListAdapter.java`
