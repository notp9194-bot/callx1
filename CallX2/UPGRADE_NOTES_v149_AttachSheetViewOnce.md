# v149 — Attach sheet: working View Once toggle in the selection bar

## What changed
The chat's View Once feature (ChatViewOnceController) previously could only
be armed from the input bar's `btn_view_once` button. The attach sheet's
Recents multi-select flow (v147) had no way to send picked media as
view-once. Now it does — same "1-in-a-circle" icon, same purple/grey tint
behavior as the input bar button, sitting in the floating selection bar next
to the caption field.

1. Select 1+ photos/videos in the attach sheet (strip or Recents grid).
2. Tap the view-once icon next to the caption field — it tints purple (ON).
3. Tap Send — the resulting message (single item or grouped `multi_media`
   for a multi-select batch) is tagged view-once before it's pushed, exactly
   like the input bar's flow: opens once, then wipes on both sides.

One flag per batch (can't mix normal + view-once items in one send), and it
auto-resets after each send — same one-shot UX as the existing input-bar
toggle.

Group chat doesn't have a ChatViewOnceController-equivalent yet, so the
toggle is hidden there instead of being a dead control.

## Files touched
- `bottom_sheet_attach.xml` — added `btn_selection_view_once` ImageView
  between the caption input and the send FAB in `selection_bar`.
- `AttachSheetRecentMediaBinder.java` — new `supportsViewOnce` param on
  `bind()` (defaults true via an overload); owns the toggle's on/off state +
  tint swap; `Callbacks#onMediaSend` gained an `isViewOnce` boolean.
- `ChatMediaController.java` (1-1 chat) — `onMediaSend` stashes the flag in
  `pendingMultiSendViewOnce`; `finishMultiUpload()` reads it once, calls
  `ChatViewOnceController.tagMessageAsViewOnce(m)` and swaps the preview
  text to "🔒 View Once" before pushing, same as the input-bar path.
- `GroupChatActivity.java` — updated to the new `Callbacks` shape, passes
  `supportsViewOnce=false` so the toggle stays hidden (feature not
  implemented for groups).

No changes needed on the receive/render side — `MessagePagingAdapter` /
`MessageBubbleCanvasView` / `ChatViewOnceController` already key off
`Message.viewOnce` regardless of `type`, so a view-once `multi_media`
message (single or grouped) renders and expires exactly like a view-once
`image`/`video` message.
