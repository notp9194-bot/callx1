# v154 — Attach sheet drag-overshoot fix

## Bug
Chat/Group attach sheet ("Recents" grid): dragging the sheet up sent it
climbing way past the correct expanded position (up under/above the status
bar). Scrolling the Recents grid afterward would snap it back down to the
correct position.

## Root cause
`bottom_sheet_attach.xml`'s content (`top_content` icon grid + a **fixed
560dp** `recents_grid` RecyclerView) is taller than the screen on most
phones. Nothing ever told `BottomSheetBehavior` a ceiling for
`STATE_EXPANDED`, so it derived the expanded offset purely from the
measured content height — which can be negative once content height
exceeds parent height, letting the sheet's top go above y=0 while dragging.

It looked like scrolling "fixed" it because a nested-scroll pass forces
CoordinatorLayout to re-settle the sheet against its real, parent-clamped
bounds — but the drag gesture itself never had that clamp.

## Fix
`AttachSheetRecentMediaBinder.bind()` now computes the real available
screen height (screen height − status bar height − a small 24dp gap so a
sliver of the chat stays visible, matching the reference design) and calls
`behavior.setMaxHeight(...)` **before** `sheet.show()` (required by the
Material docs for the height to take effect). This is called from both
`ChatMediaController#showAttachSheet` (1-1 chat) and
`GroupChatActivity`'s attach sheet, since both funnel through the shared
binder — one fix, both surfaces.

Files touched:
- `feature-chat/.../controllers/AttachSheetRecentMediaBinder.java`
