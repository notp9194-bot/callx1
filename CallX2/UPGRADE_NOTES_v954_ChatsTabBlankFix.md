# v954 — Chats tab blank after switching to another tab and back

## Symptom
Chats tab -> any other tab -> back to Chats: the chat list area is empty (no rows, no empty-state). It only
came back after some unrelated data change (new message, Firebase echo).

## Root cause (introduced by v943)
`ChatListLayoutManager` calls `setRecycleChildrenOnDetach(true)` so rows are handed to the Activity-scoped
`RecycledViewPool` when the Chats view goes away. But ViewPager2 detaches the Chats page from the window on
EVERY tab switch (page goes to its cache) **without destroying the Fragment view**. So:

1. tab switch  -> RV `onDetachedFromWindow` -> LayoutManager recycles ALL children (layout now empty)
2. tab return  -> the SAME RV is re-attached. `RecyclerView.onAttachedToWindow` keeps `mFirstLayoutComplete=true`
   and the parent re-uses its cached measure, so **no layout pass is requested** -> RV stays with 0 children.

The v943 design only considered "view destroyed + recreated" (new RV, new layout pass), not "same view detached
+ re-attached".

## Fix (surgical, perf-neutral)
- `ChatListLayoutManager`: remembers it emptied the layout on detach; in `onAttachedToWindow` it restores the
  scroll anchor (`scrollToPositionWithOffset(detachAnchorPos, detachAnchorOffset)`) and requests the layout.
  Rows are rebuilt from the Activity pool (no inflate, same adapter, same frame as the page appearing), and the
  scroll position the user left is preserved. v943 pool hand-off benefit is fully kept.
- `ChatsFragment.onResume`: O(1) safety net — if the adapter has items but the RV has 0 children and no layout is
  pending, request one layout pass.

Files: feature-chat/.../chatlist/ChatListLayoutManager.java, ChatsFragment.java

## Not touched
Adapter reuse (v943/v944), pool sizes, header/chips (v949), data/Firebase paths — unchanged.

Not build-tested here (no Gradle/SDK). Verify: Chats -> Reels/Status/Calls -> Chats (adjacent and 2+ tabs away,
also via back-press tab history): list must show immediately at the same scroll position; scroll a bit, switch,
return -> position preserved; rotate device on Chats; open a chat and come back.
