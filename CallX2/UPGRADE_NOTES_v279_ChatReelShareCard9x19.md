# v279 (part 3) — Chat screen: reel-share card resized to 9:19

## What changed

The reel-share card in the chat screen (the compact bubbleless card a shared
reel shows up as, before the 3s auto-peek even opens) is now **9:19**
aspect ratio instead of its old fixed 165×237dp size.

Width stays the same (165dp, unchanged — same as before). Height is now
derived from the 9:19 ratio: `165 * 19/9 ≈ 348dp` (was 237dp) — a taller
card that shows more of the vertical reel.

## Why it's scoped to chat only

Both the canvas-rendered path and the legacy XML fallback for this card
live entirely inside `feature-chat` and are only referenced from
`item_message_sent.xml`/`item_message_received.xml`/
`MessagePagingAdapter.java` — i.e. only the chat message list. Nothing in
`feature-reels` (the Reels grid, the reel player itself, the long-press
peek popup) touches this card, so this change cannot affect anything
outside chat.

## Files touched

- `feature-chat/.../canvas/MessageBubbleCanvasView.java`
  - `REEL_CARD_HEIGHT_DP` (previously a fixed `237f`) is now computed from
    two new constants, `REEL_CARD_ASPECT_W = 9f` / `REEL_CARD_ASPECT_H =
    19f`, against the unchanged `REEL_CARD_WIDTH_DP = 165f`.
  - Every place that already reads `REEL_CARD_WIDTH_DP`/`REEL_CARD_HEIGHT_DP`
    to lay out the card (measure pass + draw pass, both existing) picks up
    the new height automatically — no other logic changed.
- `feature-chat/.../layout/layout_msg_reel_share.xml`
  - The legacy (non-canvas-eligible-device) fallback layout's fixed
    `237dp` frame height updated to `348dp` to match, so both rendering
    paths stay in sync at the same card size.

## Not changed

- Card width (165dp), corner radius, header/avatar/caption/pill sizing and
  padding — all unchanged, still positioned the same way inside the taller
  card.
- The reel-peek mini player (still 40% smaller, anchored above the card, no
  blur backdrop — from the previous pass).
- UserReelsActivity's reel grid, SoundDetailFragment, or any other reel
  surface — none of them reference this card at all.
