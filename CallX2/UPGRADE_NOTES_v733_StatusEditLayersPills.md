# v733 — Status Edit screen: media layer strip (+) and caption in ONE row, rounded preview, Location / Mention pills

Screen: `NewStatusActivity` → step 2 "Edit" (photo / media).

## 1. Media layer strip (screenshot 1)
- New row under the preview: **[current media thumbnail] [added photos…] [+]**. The active layer's tile has a blue→violet gradient ring.
- **+** opens the gallery (multi-select). Each picked photo lands **on top of the current media** as an adjustable layer
  (drag, pinch-resize, twist-rotate, lock, front/back, undo/redo, trash — the same sticker engine the text layer uses).
- Tap a tile = select that layer; tap the first tile = deselect (back to the base media). Long-press a layer tile = remove (undoable).
- Max 8 added photos. Photo only (hidden for video — video overlays still go through the Reel editor).
- Layers are **baked into the story image** at post time (`renderStoryCanvas(false)` no longer hides them), excluded from
  sticker JSON + draft save. Layers get soft rounded corners baked into their pixels (WYSIWYG).
- Layers are cleared when the base media is discarded / replaced (except the Advance-Editing round-trip, same rule as the text layer).

## 2. Rounded preview corners
- The Edit-step canvas (`media_preview_frame`) is clipped to a 22dp rounded outline. Hardware-render only, so the exported
  story image keeps square corners (export draws on a software Canvas).

## 3. Pills under the preview (screenshot 2)
- **Add location** — opens `ReelLocationTagActivity` (class-name intent, no module dependency). Chosen place replaces the pill
  text; tap again to change / remove. Saved to `StatusItem.locationName/Lat/Lng` (single + batch posts); the viewer already
  shows it via `showLocationTag`.
- **Mention people** — same flow as the rail's "@" tool (`showEditorTextDialog(true)`, @username autocomplete).

## Files
- `feature-status/.../compose/NewStatusActivity.java`
- `feature-status/src/main/res/layout/activity_new_status.xml`
- new drawables: `bg_layer_thumb`, `bg_layer_thumb_selected`, `bg_layer_plus`, `ic_layer_plus`, `bg_status_meta_pill`, `ic_pill_location`, `ic_pill_people`

## Not built / not run on a device (no Android SDK here). Java syntax + XML well-formedness checked only.
Test: Edit step → tap + → pick 2 photos → drag/pinch each → switch via tiles → Post → open story: layers must be in the image;
rounded corners only in the editor; Add location → viewer shows tag; Mention people → @name works.

## v733 change: strip + caption in one row
- New `media_caption_row` (horizontal): layer strip on the left (width = its tiles, capped at ~46% of the row, scrolls beyond that),
  caption box fills the remaining width. Video: strip hidden, caption takes the full row. Pills sit below this row.
- `NewStatusActivity.capLayerStripWidth()` + wizard loop branch `media_caption_row`.
