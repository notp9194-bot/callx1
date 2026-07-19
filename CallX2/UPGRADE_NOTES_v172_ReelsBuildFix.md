# v172 — Reels Module Build Fix

Fixed `feature-reels:compileDebugJavaWithJavac` failure (13 errors).

## Fixes — `HomeFragment.java`

1. **`reelId` / `isLiked` used before declaration** in the photo-slideshow
   double-tap-to-like listener (video-frame double-tap already worked since
   it's declared after). Moved the `reelId` and `isLiked` declarations up,
   right after the likes/comments/reposts counters are set, so both the
   slideshow and video-frame double-tap listeners can see them. Removed the
   now-duplicate declarations further down in the method.

2. **`ReelShareSheetFragment.newInstance(...)` arg mismatch** — the method
   requires 8 args (`reelId, videoUrl, thumbUrl, caption, ownerUid,
   ownerUsername, ownerPhoto, allowRepost`) but both call sites (Quote
   Repost sheet and Send/Share sheet) were only passing 4, and in the wrong
   order. Fixed both calls to pass all 8 arguments correctly.

## Fixes — `ReelsFragment.java`

3. **Undefined symbol `currentReels`** in `notifyReelWatched(...)` — no such
   field exists. Replaced with the existing pattern used elsewhere in the
   class: `isFypMode ? allReels : followingReels`.

No behavior changes beyond making the code compile — the two-arg photo
double-tap-like flow and the share/quote-repost sheets now use the same
data the rest of the card already had in scope.
