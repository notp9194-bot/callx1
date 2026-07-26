# v217 — Status "Add status" sheet + Chat attach sheet → merged combo

## The bug this fixes
`NewStatusActivity` actually had **two** attach sheets, and neither one was
fully working:

1. `showStatusAddSheet()` — the one really wired to the Upload button.
   Used its own `bottom_sheet_status_add.xml` (header + 5 action chips:
   Text/Music/Layout/Voice/AI images + a 3-column `rv_status_add_recents`
   RecyclerView). Its Recents grid was **dead** — it called
   `AttachSheetRecentMediaBinder.bind()` on that layout, but the binder
   looks for `R.id.recents_grid` / `R.id.top_content` / etc. (see its
   `if (grid == null || topContent == null) return;` guard), none of which
   existed in `bottom_sheet_status_add.xml`, so it silently no-op'd every
   time the sheet opened.

2. `openStatusAttachSheet()` — a fully correct integration of feature-chat's
   real `bottom_sheet_attach.xml` (recent-media strip, expandable 4-col
   grid, folder picker, HD toggle, multi-select send bar, Edit-before-post)
   with Status's own posting/edit flow — but it was **never called from
   anywhere**. Dead code.

## What changed
Merged the two into one working sheet, kept under the same
`showStatusAddSheet()` name (still the only thing the Upload button calls):

- Now inflates feature-chat's `bottom_sheet_attach.xml` (the layout the
  binder actually supports) instead of the old status-only layout.
- Hides the chat-only icon-grid chips (Document/Poll/Contact/Location/
  Payment/Event/AI-images) — Status doesn't need those, and its own
  "AI images" entry point already lives in the chip row below.
- `bottom_sheet_status_add.xml` was slimmed down to **just** the 5-chip
  action row (Text/Music/Layout/Voice/AI images) — the old header + dead
  Recents grid were removed. That row is now inflated separately and
  inserted as the 2nd child of `top_content` (right after the drag
  handle), so it sits above the real, working Recents strip/grid.
- All 5 chip click listeners (Text → focus caption field, Music → sticker
  picker, Layout → `StatusLayoutPickerActivity`, Voice → system audio
  recorder, AI images → prompt dialog) are unchanged in behavior — only
  re-pointed at views inside the newly-injected row.
- `AttachSheetRecentMediaBinder.bind(..., supportsViewOnce=false, ...)` now
  drives real send/edit/camera/gallery/"more apps"/"see more" flows:
  `onMediaSend` → `postStatusBatch(...)`, `onMediaEdit` → hands the
  selection to `MediaEditActivity` via the existing
  `statusMediaEditLauncher`, same as before in the (now-removed)
  `openStatusAttachSheet()`.
- Removed the now-redundant `openStatusAttachSheet()` method entirely —
  its logic lives in `showStatusAddSheet()` now, so there's a single
  source of truth instead of two sheets drifting apart.

## Net effect
Tapping "Upload" on the Status tab now opens ONE sheet that has both:
- the WhatsApp-style Text/Music/Layout/Voice/AI-images action row, AND
- the full chat-grade Recents strip + expandable multi-select grid +
  folder picker + HD toggle + Edit, actually working (unlike before).

## Files touched
- `feature-status/src/main/res/layout/bottom_sheet_status_add.xml`
  (slimmed to just the 5-chip row; old header/Recents-grid markup removed)
- `feature-status/src/main/java/com/callx/app/compose/NewStatusActivity.java`
  (`showStatusAddSheet()` rewritten to inflate + wire the combo sheet;
  dead `openStatusAttachSheet()` and the broken `setupRecentsInStatusAddSheet()`
  removed)

## Not touched
- `AttachSheetRecentMediaBinder`, `RecentMediaLoader`, `RecentMediaGridAdapter`,
  `AttachSheetFolderPicker` — all reused as-is from feature-chat, no changes.
- `feature-chat/src/main/res/layout/bottom_sheet_attach.xml` — unchanged;
  Status just inflates it like ChatMediaController/GroupChatActivity do.
