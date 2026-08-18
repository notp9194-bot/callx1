# v147 — Attach sheet: tap-to-select + auto-expand (WhatsApp/Telegram style)

## What changed
Previously tapping any thumbnail in the attach sheet (strip OR grid) sent it
immediately and closed the sheet. Now tapping **selects** it (numbered green
badge, matches the reference screenshot) instead:

1. Tap a thumbnail in the compact bottom strip (visible at peek/collapsed
   state) → it's marked selected, and the sheet smoothly animates up to the
   full "Recents" grid — same eased crossfade the drag gesture already used,
   just driven programmatically (`BottomSheetBehavior.setState(EXPANDED)`
   animates identically whether triggered by a drag release or by code).
2. The item stays selected across that transition — selection lives in a
   single shared `MediaSelectionState`, not per-adapter state, so the strip
   and grid always agree on what's picked.
3. Tapping more thumbnails in the grid keeps adding to the same selection
   (numbered 1, 2, 3…). Tapping a selected thumbnail again deselects it.
4. A floating caption/send bar (`selection_bar`) fades in over the grid as
   soon as 1+ items are selected, with a caption field and a green send FAB
   showing the count — tapping it dismisses the sheet and sends the whole
   batch through the same grouped-upload pipeline the system Gallery
   multi-picker already used (`uploadSequentially` → one `multi_media`
   message, single item included).

## Files touched
- `feature-chat/.../controllers/MediaSelectionState.java` (new) — shared
  ordered `Uri → Item` selection set + change listener.
- `feature-chat/.../controllers/RecentMediaStripAdapter.java` — takes the
  shared selection, renders scrim+badge, tap now toggles instead of firing
  `onMediaTapped`.
- `feature-chat/.../controllers/RecentMediaGridAdapter.java` — same.
- `feature-chat/.../controllers/AttachSheetRecentMediaBinder.java` — owns
  the `MediaSelectionState`, drives the auto-expand-on-strip-tap, wires the
  selection bar's show/hide animation + send button; `Callbacks` interface
  swapped `onMediaTapped(Item)` → `onMediaSend(List<Item>, String caption)`.
- `feature-chat/.../controllers/ChatMediaController.java` /
  `feature-chat/.../group/GroupChatActivity.java` — updated to the new
  `Callbacks` shape, route `onMediaSend` through the existing
  `uploadSequentially` grouped-upload path instead of `uploadAndSend`.
- `item_attach_grid_media.xml` / `item_attach_strip_media.xml` — added
  `selection_scrim` + numbered `selection_badge` (GONE at rest, so
  unselected cells are pixel-identical to before).
- `bottom_sheet_attach.xml` — root wrapped in a `FrameLayout` (old content
  moved into `sheet_scroll_content`) so the new `selection_bar` can float
  pinned to the bottom, over the Recents grid, instead of living inline at
  the end of a `wrap_content` column.
- `bg_selection_badge.xml`, `bg_selection_caption_pill.xml`,
  `bg_selection_bar_card.xml` (new drawables).
