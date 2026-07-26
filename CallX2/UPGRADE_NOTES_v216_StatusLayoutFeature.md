# Upgrade Notes — v216: Status Layout Feature + Add Status Sheet

## Summary
Full WhatsApp-level Status Layout feature implementation. When tapping the Upload
button in New Status, users now see a proper "Add status" bottom sheet with action
buttons (Text, Music, Layout, Voice, AI Images) — exactly matching WhatsApp's story
composer sheet. The Layout option opens a full multi-photo layout picker.

---

## New Files

### `feature-status/.../compose/StatusLayoutPickerActivity.java`
Full Activity implementing the multi-photo layout picker:
- "Start layout" header with subtitle "Choose up to 6 photos for your layout."
- 3-column gallery grid with circle-checkbox multi-select (up to 6 photos)
- Done button in toolbar (enabled once ≥1 photo selected)
- Real-time layout preview panel at top (shown/hidden based on selection)
- Bottom horizontal row of 6 layout-style buttons — tap any to switch instantly
- Empty preview cells show "+" tap targets to add more photos from gallery
- On Done → launches `MediaEditActivity` with all selected URIs → status post flow

### `feature-status/.../compose/StatusLayoutPreviewView.java`
Custom `FrameLayout`-based view rendering 6 different WhatsApp-style layout styles:
- `STYLE_GRID_2X2`   — 4-cell equal 2×2 grid (default)
- `STYLE_BIG_LEFT`   — 1 large left cell + 2 stacked right
- `STYLE_COLUMNS_2`  — 2 equal vertical columns
- `STYLE_BIG_TOP`    — 1 large top + 2 equal bottom cells
- `STYLE_BIG_RIGHT`  — 2 stacked left + 1 large right cell
- `STYLE_GRID_3`     — 1 wide top + 2 equal bottom (3-cell collage)

Cells are populated via Glide. Empty slots show "+" for adding more media.

### Layout XMLs
- `activity_status_layout_picker.xml` — Full layout picker activity layout
- `item_layout_picker_media.xml`      — Grid cell with thumbnail + circle checkbox
- `item_layout_style_btn.xml`         — Bottom row layout style button
- `bottom_sheet_status_add.xml`       — "Add status" sheet with 5 action buttons

### Drawables
- `ic_layout_grid_2x2.xml` + `ic_layout_big_left.xml` + `ic_layout_columns_2.xml`
  + `ic_layout_big_top.xml` + `ic_layout_big_right.xml` + `ic_layout_grid_3.xml`
  → 6 layout style icons (vector drawables)
- `ic_status_btn_text.xml` + `ic_status_btn_music.xml` + `ic_status_btn_layout.xml`
  + `ic_status_btn_voice.xml` + `ic_status_btn_ai_images.xml`
  → 5 status add sheet action icons
- `bg_layout_check_selected.xml` — Green circle with white border (selected state)
- `bg_layout_check_empty.xml`    — Transparent circle with white border (unselected)
- `bg_layout_done_btn.xml`       — WhatsApp green rounded pill for Done button
- `bg_status_add_chip_dark.xml`  — Dark circular background for action chips

---

## Modified Files

### `feature-status/.../compose/NewStatusActivity.java`
- **Replaced** `showMediaSourceDialog()` alert dialog with `showStatusAddSheet()` —
  a proper bottom sheet (using `bottom_sheet_status_add.xml`) with:
  - Text → opens text-only status mode (existing behavior)
  - Music → opens `StatusStickerPickerSheet` in music mode (existing sticker system)
  - Layout → launches `StatusLayoutPickerActivity`
  - Voice → opens audio recorder intent
  - AI Images → opens AI image prompt dialog (opt-in)
  - Recents grid → reuses `AttachSheetRecentMediaBinder` for media picking
- **Added** `layoutPickerLauncher` — `ActivityResultLauncher<Intent>` that receives
  selected URIs + layout style from `StatusLayoutPickerActivity`, then launches
  `MediaEditActivity` with those URIs for editing before posting.

### `feature-status/src/main/AndroidManifest.xml`
- Registered `StatusLayoutPickerActivity` with `screenOrientation="portrait"` and
  `windowSoftInputMode="adjustResize"`.

---

## Flow Diagram

```
Status tab → "+" button
    ↓
showStatusAddSheet() [bottom_sheet_status_add.xml]
    ├── [Text]      → NewStatusActivity text mode
    ├── [Music]     → StatusStickerPickerSheet (music)
    ├── [Layout]    → StatusLayoutPickerActivity
    │       ↓
    │   Select 1-6 photos + choose layout style
    │       ↓
    │   Done → returns URIs + layoutStyle
    │       ↓
    │   MediaEditActivity (edit, crop, filters, draw)
    │       ↓
    │   postStatusBatch() → Firebase
    ├── [Voice]     → audio recorder
    ├── [AI Images] → prompt dialog
    └── [Recents]   → multi-select → MediaEditActivity → postStatusBatch()
```

---

## Screenshot Reference
- Screenshot 1 → `bottom_sheet_status_add.xml` (Add status sheet with 5 buttons)
- Screenshot 2 → `activity_status_layout_picker.xml` START state (gallery grid, circle checks)
- Screenshots 3,4,5 → layout picker with preview + bottom 6 layout style buttons
