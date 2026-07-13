# v145 — Attach sheet expand crossfade + infinite scroll + grid borders

## 1. Icon grid/strip fade out → "Recents" header fades in
`bottom_sheet_attach.xml`: wrapped Row1+Row2 in `icon_grid_section`, added
a new `expanded_header` (close X / "Recents" title / HD badge) right above it,
initially `gone`/`alpha=0`.

`AttachSheetRecentMediaBinder.bind()` now adds a `BottomSheetBehavior
.BottomSheetCallback#onSlide()` that, over the first 35% of the
collapsed→expanded drag (`FADE_FRACTION`):
- fades `icon_grid_section` + `bottom_media_row` (strip) alpha 1→0, then
  sets them `GONE` (reclaims space instead of just becoming invisible)
- fades `expanded_header` alpha 0→1, `VISIBLE` once it's actually showing

This rides the same `slideOffset` the sheet's own drag physics already use,
so it's 1:1 with the finger — no separate animation to tune.

## 2. Infinite scroll on the expanded Recents grid
`RecentMediaLoader.loadRecentPage(context, offset, limit)` — pages through
the same date-sorted merge `loadRecent()` used, just sliced. Re-queries both
MediaStore tables up to `offset+limit` each call (cheap; only runs while the
sheet is open and the user is actively scrolling).

`RecentMediaGridAdapter.append()` — appends a page without touching already-
bound cells. `AttachSheetRecentMediaBinder` adds a scroll listener on
`recents_grid` that fires the next page ~2 rows before the end
(`GRID_PAGE_SIZE = 60`), guarded against overlapping loads and against
paging past the end of the device's media.

## 3. Grid cell borders
New `bg_media_thumb_grid.xml` (square, hairline `1dp #33FFFFFF` stroke, no
corner radius) used by `item_attach_grid_media.xml` cells so adjacent cells
read as a continuous bordered grid (matches reference screenshots) instead
of blending together. Left `bg_media_thumb_camera.xml` (rounded, also
bordered now) for the compact strip's 76dp tiles — that one still wants
rounded corners since tiles there aren't edge-to-edge.

## Files touched
- `feature-chat/src/main/res/layout/bottom_sheet_attach.xml`
- `feature-chat/src/main/res/layout/item_attach_grid_media.xml`
- `feature-chat/src/main/res/drawable/bg_media_thumb_camera.xml`
- `feature-chat/src/main/res/drawable/bg_media_thumb_grid.xml` (new)
- `feature-chat/src/main/java/.../controllers/RecentMediaLoader.java`
- `feature-chat/src/main/java/.../controllers/RecentMediaGridAdapter.java`
- `feature-chat/src/main/java/.../controllers/AttachSheetRecentMediaBinder.java`

## Known follow-ups
- Camera tile currently only lives in the compact strip, not as a cell in
  the expanded grid itself (screenshots' expanded state still shows it as
  first cell there too) — not done in this pass, flag if you want it moved.
- `expanded_header`'s "Recents ▾" dropdown is static (no album picker menu
  wired up) — placeholder text/icon only for now.
