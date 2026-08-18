# v181 — Photo Reel Swipe Fix (Instagram-Level Gesture)

## What changed

Two bugs fixed in `ReelPhotoSlideshowController.java`:

---

## Bug 1 — Left/right swipe was changing the tab instead of the photo

**Root cause:** The touch listener on `vpPhotos` (inner ViewPager2) was returning
`false`, which allowed the **parent tab ViewPager2** to intercept horizontal swipe
events and navigate to the next tab instead of swiping the photo inside the reel.

**Fix:** On `ACTION_DOWN`, call
`v.getParent().requestDisallowInterceptTouchEvent(true)` so the parent can never
steal the touch when a multi-photo reel is active.  
On `ACTION_MOVE`, we detect the dominant direction:
- **Horizontal** (dx ≥ dy) → keep blocking parent → photo swipe wins
- **Clearly vertical** (dy > 1.5× dx) → restore parent intercept → reel up/down
  feed scroll still works normally

---

## Bug 2 — Swipe required too much finger movement (not Instagram-like)

**Root cause:** ViewPager2 internally uses a `RecyclerView` whose `mTouchSlop`
defaults to the system touch slop (~24 dp).  On Instagram, even a very short
flick triggers the carousel transition.

**Fix:** After `vpPhotos.setAdapter()`, we use reflection to read
`RecyclerView.mTouchSlop` on the inner child and set it to **half the original
value**.  This matches the sensitivity threshold Instagram uses for its carousels.

---

## Files changed

| File | Change |
|------|--------|
| `feature-reels/.../ReelPhotoSlideshowController.java` | Added `touchDownX`, `touchDownY` fields; updated `setOnTouchListener` with direction-aware parent intercept control; added touch slop reduction via reflection after adapter attach |

## No breaking changes

All view IDs, method signatures, and contracts are unchanged.
Only touch-event routing and sensitivity changed.
