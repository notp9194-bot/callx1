# v219 — Status Layout Picker: preview render fix + double-upload guard

## Bug 1 — "adjust layout" screen showed a blank/empty preview
`StatusLayoutPreviewView.placeView()` positioned each cell with a manual
`child.layout(l, t, r, b)` call but never measured it first. Each cell
`FrameLayout` was added with default `WRAP_CONTENT` params, and its inner
photo `ImageView` is `MATCH_PARENT` — with no measure pass giving the cell a
real size, Android measured that wrap-content/match-parent-child
combination as 0×0, so the photo `ImageView` got laid out at zero size no
matter what rect we placed the outer cell at. Same bug class as the
already-fixed `MediaGridAdapter` 0dp-height cell issue in
`StatusLayoutPickerActivity`.

**Fix:** `placeView()` now calls `child.measure(EXACTLY w, EXACTLY h)`
before `child.layout(...)`, so the cell's own `FrameLayout.onMeasure()`
correctly hands the exact size down to the `MATCH_PARENT` photo
`ImageView` instead of collapsing it to zero. The "Choose layout" preview
at the top of the picker now actually renders the selected photos in the
chosen 2×2 / big-left / columns / big-top / big-right / 3-grid style.

## Bug 2 — double upload on Status
`StatusLayoutPickerActivity`'s Done button and `MediaEditActivity`'s Send
button had no tap-debounce. A fast double-tap (easy to trigger since
baking/editing isn't instant) could kick off the finish/bake chain twice,
delivering the ActivityResult twice and uploading the same edited photo(s)
to Status twice.

**Fix — three layers of guard, all one-shot per screen:**
- `StatusLayoutPickerActivity`: Done button ignores taps after the first.
- `MediaEditActivity`: Send button disables itself and ignores taps after
  the first, so `bakeAndSend()`/`processItemForSend()` can only run once.
- `NewStatusActivity.postStatusBatch()`: idempotent guard so even if a
  duplicate result somehow still arrived, the batch upload only runs once.

Net effect: only whatever was actually edited (or left untouched) in the
editing screen gets uploaded — once, not twice.
