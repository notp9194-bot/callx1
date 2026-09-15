# v384 — Media viewer top-bar tap-to-toggle fix

## Bug (user report)
In the chat media viewer (open an image/video from a chat bubble): tap once
→ top bar hides. Tap again to bring it back → doesn't reliably reappear.

## Root cause
Two separate problems in how the tap was wired, both in
`MediaViewerActivity`/`GalleryPagerAdapter`:

1. **Single-image mode** toggled via PhotoView's `setOnViewTapListener`.
   That callback is gated behind Android's own double-tap disambiguation
   delay (~300ms) internally, since PhotoView needs to tell a plain tap
   apart from the first half of a double-tap-to-zoom gesture. A second,
   deliberate tap landing close enough in time to the first can get
   swallowed as a double-tap instead of registering as its own single tap
   — the bar hides fine on tap 1, but tap 2 doesn't reliably fire.

2. **Gallery/grouped-media mode** toggled via a plain
   `root.setOnClickListener()` — but PhotoView's own internal touch
   handling on the photo itself generally consumes the touch sequence for
   its own gesture detection, so that click on the *parent* view often
   never fires at all for a tap on an image page (only video pages worked,
   since those explicitly forwarded their click to the root).

## Fix
Replaced both with one `GestureDetector` at the `dispatchTouchEvent` level
in `MediaViewerActivity`, fed every touch event for the whole screen.
`onSingleTapUp` fires immediately on finger-lift — no double-tap wait —
and never consumes the event, so PhotoView's own pinch/double-tap-zoom and
ViewPager2's paging are completely unaffected. This is now the single
place `toggleUI()` gets called from, for both single-media and gallery
mode alike. Skipped during gallery multi-select (a tap there should only
toggle that item's checkbox).

Removed the old wiring so the bar doesn't double-toggle (fire twice on one
tap, netting out to no visible change):
- `binding.ivFull.setOnViewTapListener(...)` in `MediaViewerActivity`
- `binding.player.setOnClickListener(...)` in `MediaViewerActivity`
- the `tapListener.onTap()` branch in `GalleryPagerAdapter`'s root click
  (select-mode checkbox toggling there is untouched)

## Files touched
- `app/src/main/java/com/callx/app/activities/MediaViewerActivity.java`
- `app/src/main/java/com/callx/app/activities/GalleryPagerAdapter.java`
