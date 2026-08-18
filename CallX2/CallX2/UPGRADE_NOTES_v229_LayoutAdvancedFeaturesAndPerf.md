# v229 — Layout Feature: Advanced Features + Performance Optimization

## Advanced features

**1. Two new layout styles — Grid 5 & Grid 6**

Previously the largest style was a 4-slot 2×2 grid, but "Start layout"
allows picking up to 6 photos (`MAX_SELECTION = 6`). Picking 5 or 6 photos
meant the extras never showed in the preview — silently invisible, yet
still forwarded in the final result (see bug fix #3 below for the other
half of this).

- `StatusLayoutPreviewView.STYLE_GRID_5` — 2 equal top cells + 3 equal
  bottom cells.
- `StatusLayoutPreviewView.STYLE_GRID_6` — 3×2 equal grid.
- New icons: `ic_layout_grid_5.xml`, `ic_layout_grid_6.xml`, added to
  `LayoutStyleAdapter`'s style row (now 8 styles instead of 6).

**2. Smart default style based on selection count**

`StatusLayoutAdjustActivity` used to always default to the 2×2 grid
regardless of how many photos were picked. Now: 5 photos → defaults to
Grid 5, 6 photos → defaults to Grid 6, so the user doesn't have to
manually notice and switch styles just to see everything they picked.

**3. Bug fix: result no longer forwards more photos than the layout shows**

`finishWithResult()` now trims to `previewView.getSlotCount()` before
building the result — a safety net independent of style choice, so a
downstream count mismatch (N photos selected, layout shows fewer, but all
N still got forwarded) can't happen regardless of which style the user
picks. Shows a toast ("Only the first N photos fit this layout") only
when trimming actually drops something.

**4. Double-tap a photo to reset zoom/pan**

Long-press already opens the replace/delete menu (v228); double-tap on
the same cell now resets that photo's pinch-zoom/pan back to default
cover-fit — a quick way out after over-zooming, fed through the same
`GestureDetector` already handling long-press, so it shares the same
touch-slop cancellation logic (a real drag/pinch cancels both).

**5. Undo on delete**

Long-press → Delete now shows a Snackbar with an "UNDO" action (4s) that
re-inserts the removed photo at its original position, instead of
silently dropping it — long-press → Delete is an easy accidental tap.

## Performance

**1. Thumbnail downsampling (biggest win)**

Neither the gallery grid (`LayoutMediaGridAdapter`, up to 300 items) nor
the collage preview (`StatusLayoutPreviewView`) capped Glide's decode
size — every camera photo (often 12MP+) was being fully decoded just to
show a small thumbnail. Both now use `.override()` (720px cap for the
preview, actual cell size for the gallery grid) + `.diskCacheStrategy(ALL)`.
This is a preview-only change — the original Uris are untouched and used
as-is for whatever actually exports/sends the final collage.

**2. Targeted RecyclerView updates instead of full rebinds**

`StatusLayoutPickerActivity.onMediaItemToggled()` called
`gridAdapter.notifyDataSetChanged()` on every single tap — on a
few-hundred-item folder, that rebinds (and potentially re-decodes) every
visible cell for a change that only ever touches the tapped item plus
however many already-selected items need their order badge renumbered.
Now only those specific positions get `notifyItemChanged()`.
`gridRecycler.setHasFixedSize(true)` also added — cell size never changes
with content (fixed 3-column, fixed square `cellPx`), so RecyclerView can
skip re-measuring the whole layout on every adapter change.

**3. Memoized video/photo MIME lookups**

`StatusLayoutPreviewView.isVideoUri()` and
`StatusLayoutAdjustActivity.isVideoUri()` query
`ContentResolver.getType(uri)` to tell photos from videos. The preview's
copy is now memoized per Uri (`videoUriCache`) since `rebuildCells()` runs
on every style switch / reorder / replace and would otherwise re-query the
same Uris repeatedly.

**4. Skip redundant full rebuilds**

`StatusLayoutPreviewView.setMediaUris()` now short-circuits (`List.equals()`
check) when called with the exact same list it already has — the composer
re-pushes the whole list on every change (`refreshPreview()`) rather than
diffing itself, so this avoids a full cell teardown + re-decode on calls
that didn't actually change anything.

## Files touched

- `StatusLayoutPreviewView.java` — new styles + layouts, Glide override,
  video-MIME cache, setMediaUris fast-path, double-tap reset.
- `LayoutStyleAdapter.java` — 2 new style entries.
- `ic_layout_grid_5.xml`, `ic_layout_grid_6.xml` — new icons.
- `StatusLayoutAdjustActivity.java` — smart default style, result
  trimming + toast, undo-delete Snackbar.
- `LayoutMediaGridAdapter.java` — Glide override + disk cache.
- `StatusLayoutPickerActivity.java` — targeted notifyItemChanged(),
  setHasFixedSize(true).
