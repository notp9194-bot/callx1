# v379 — Removed the "tap media → bottom sheet" step; Forward/Star/Delete moved into the viewer's ℹ menu

## What changed
Previously, tapping an image/video bubble in a 1:1 or group chat showed a
BottomSheetDialog with **View/Play, Edit, Save, Share, Forward, Star, Delete**
before the actual full-screen media viewer opened (`MessagePagingAdapter
#showMediaActionSheet`).

That sheet is now removed. Tapping media opens `MediaViewerActivity` directly
— WhatsApp-style, no intermediate step.

- **View/Edit/Save/Share** were already top-bar icons inside the viewer — no
  change needed there.
- **Forward / Star / Delete** (the three actions that only existed in the
  removed sheet) now live inside the viewer's own "more options" (ℹ) menu —
  `MediaViewerActivity#showMoreOptionsMenu()`. New "Forward" entry added;
  "Remove this item from group" renamed to plain "Delete" for clarity; both
  gated the same way the old sheet gated them (Delete only for your own
  messages).

## Files touched
- `feature-chat/.../conversation/MessagePagingAdapter.java`
  - `showMediaActionSheet(...)` no longer builds a `BottomSheetDialog` — it
    now directly calls `openChatMediaViewer(...)`, same as the sheet's old
    "View" action.
  - `openChatMediaViewer(...)` gained an `isOwnMessage` boolean param, passed
    through as an `"isOwnMessage"` intent extra so the viewer knows whether
    to offer Delete. All existing call sites updated (video canvas direct-tap
    paths now also pass this correctly, computed from `currentUid`).
- `app/.../activities/MediaViewerActivity.java`
  - Reads the new `"isOwnMessage"` extra into a field.
  - `showMoreOptionsMenu()`: added "Forward" (new `forwardSingleActiveItem()`,
    reuses `GalleryForwardBridge` the same way multi-select forward already
    does, just with a single-item index), kept "Star this item", renamed
    "Remove this item from group" → "Delete", and gated Delete behind
    `isOwnMessage`.

## Long-press: single-press now does select + toolbar + menu together (WhatsApp-style)
Previously a message row's long-press was two-step: 1st long-press only entered
multi-select mode (row highlighted, top toolbar shown); the reaction/action
menu (`showActionBottomSheet`) only opened on a **second** long-press once
already selecting. Fixed across all 6 long-press sites (text bubble, canvas
bubble, single-image, media-group, video-with-fl_video, video-fallback-image)
so **one** long-press now does all three at once: selects the row, shows the
top selection toolbar, and opens the reaction/action menu — matching current
WhatsApp behavior. A long-press on a *different* row while already selecting
now just toggles that row's selection (forwards to the row's normal tap
handler) instead of re-opening the menu.

## Not touched
- The long-press context menu (`showActionBottomSheet` — Reply/Copy/Star/
  Pin/Forward/Edit/Delete) is unrelated and untouched.
- `GroupMediaViewerActivity` (the "All media" grid screen) just launches
  `MediaViewerActivity` and needed no changes — it inherits this behavior
  automatically since both 1:1 and group chat share `MessagePagingAdapter`.
