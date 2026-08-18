# v218 — Layout picker: gallery grid showed no media

## Bug
Tapping "Layout" on the combo Add-status sheet opens
`StatusLayoutPickerActivity`, but its gallery grid appeared empty — no
crash, permission was fine, the MediaStore query was fine, Glide load was
fine. Nothing showed up anyway.

## Root cause
`item_layout_picker_media.xml` (one grid cell) has its root `FrameLayout`
set to `android:layout_height="0dp"` with no weight. That only resolves to
something visible inside a `LinearLayout` with `layout_weight` — a plain
RecyclerView cell under `GridLayoutManager` has no such mechanism, so
`0dp` just means **0 pixels tall**. Every cell rendered at zero height, so
every thumbnail was invisible regardless of whether it loaded correctly.

The file's own comment even claimed *"Use a hardcoded height of 120dp here
as default"* — but the actual attribute was `0dp`, not `120dp`. That
override never actually existed anywhere in code, so the comment was
describing a fix that was never applied.

## Fix
`MediaGridAdapter#onCreateViewHolder` (in `StatusLayoutPickerActivity.java`)
now sets an explicit square pixel height on each cell right after inflate —
`screenWidth / 3`, matching the 3-column `GridLayoutManager` — the same
fixed-cellPx approach `RecentMediaGridAdapter` already uses for the 4-column
chat/status attach-sheet grid. Updated `item_layout_picker_media.xml`'s
comment to describe this accurately instead of the stale 120dp claim.

## Files touched
- `feature-status/src/main/java/com/callx/app/compose/StatusLayoutPickerActivity.java`
  (`MediaGridAdapter#onCreateViewHolder`)
- `feature-status/src/main/res/layout/item_layout_picker_media.xml` (comment only)

## Not touched
- Permission handling, MediaStore query, Glide loading — all were already
  correct; the cells just had zero visible height.
