# v261 — Suggested reels long-press → reused mini video player (bigger size)

Files touched:
- `feature-reels/src/main/java/com/callx/app/profile/ReelPeekPreviewController.java`
- `feature-reels/src/main/res/layout/popup_reel_peek.xml`
- `feature-reels/src/main/java/com/callx/app/feed/HomeFragment.java`

## What this adds
Long-pressing a tile in the v260 "Suggested reels" row now opens the exact
same mini video player ("peek") that `UserReelsActivity`'s profile grid and
`SoundDetailFragment` already share — blurred backdrop, muted looping
preview, swipe-down-to-close, "Watch Reel" → full player — instead of a
new component. Only the **card size** differs here, per the requested
screenshot reference (a near-full-width card), everything else (gestures,
dock-to-source close animation, mute badge, duration/views/likes overlay)
is identical to the existing peek.

## How it works
- **`ReelPeekPreviewController`**: added a 6-arg `show(reel, options,
  callback, sourceView, cardWidthPx, videoHeightPx)` overload alongside the
  original 4-arg one. The 4-arg version just delegates with `null, null` —
  every existing caller (`UserReelsActivity`, `SoundDetailFragment`) is
  byte-for-byte unaffected, still gets the shared XML default (331×475dp).
  New `applySizeOverride()` only touches `card_peek` /
  `card_peek_actions` / `card_peek_options`' width and `frame_peek_video`'s
  height when overrides are non-null, and is called from both
  `buildAndShow()` (first open) and `switchTo()` (fast re-long-press onto
  another tile), so sizing stays correct across fast-switches too.
- **`popup_reel_peek.xml`**: the previously-anonymous video `FrameLayout`
  now has `android:id="@+id/frame_peek_video"` so it can be targeted for
  the height override — no visual/behavioral change for existing callers.
- **`HomeFragment`**: new `showSuggestedReelPeek()`, wired to
  `setOnLongClickListener` on each suggested-reel tile. Card width =
  screen width − 24dp (near-full-width, small side margins); video height
  = width × 16⁄9 — same aspect ratio the Home feed's own post cards use, so
  it reads as a "real" reel preview instead of a stretched/cropped one.
  "Watch Reel" opens `SingleReelPlayerActivity` on that single reel (not
  the whole suggested pool, since a peek from a long-press is about that
  one reel — the row's plain **tap** still opens the full pool for
  swipe-through, unchanged from v260).
- `suggestedReelsPeekController` is dismissed and cleared in
  `onDestroyView()`, same lifecycle-safety pattern the feed's other
  players already follow.

## Known limitations
- Authored and hand-reviewed for syntax (brace/paren balance confirmed on
  both files) but **not compiled** — no Android SDK/Gradle network access
  in this sandbox. Build locally
  (`./gradlew :feature-reels:assembleDebug`) before shipping.
