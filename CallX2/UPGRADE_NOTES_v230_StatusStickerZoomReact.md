# v230 — Status Sticker "Tap to Zoom, React to Return"

## Feature
In the status VIEWER, tapping any interactive sticker (🎵 music, ⏳ countdown,
🧠 quiz, 💬 question, 🗳️ poll, 🎚️ slider, 👤 mention, #️⃣ hashtag, 🔗 link,
➕ add-yours) now:

1. Enlarges the sticker and brings it to the front, centered on screen, with
   a dim scrim behind it so it's easy to read/answer.
2. Pauses the story (progress bar + video) for as long as it's enlarged.
3. Once the viewer reacts to it — picks a quiz option, casts a poll vote,
   releases the slider, toggles the countdown reminder, or hands off to an
   external sheet/profile/browser/composer — it shrinks back to the *exact*
   spot the poster placed it at, and the story resumes.

Tapping the dim scrim outside the sticker also dismisses the zoom early.

## Files changed
- `core/src/main/java/com/callx/app/stickers/StatusStickerOverlayView.java`
  - New: `armViewerZoomGate(Runnable)`, `zoomToFront(ViewGroup, Runnable)`,
    `restoreFromZoom(Runnable)`, `isZoomedIn()`.
  - Overrides `onInterceptTouchEvent`/`onTouchEvent` so the *first* tap on a
    sticker is always captured for the zoom (never lands directly on an
    inner quiz/poll option), while a *second* tap — once zoomed in — reaches
    the real control normally. Only active when the gate is armed (viewer),
    never affects the composer's existing drag/pinch behavior.
- `feature-status/src/main/java/com/callx/app/viewer/StatusViewerActivity.java`
  - New helpers: `armStickerZoomGate(...)`, `settleStickerReaction(...)`,
    `showStickerZoomScrim()` / `hideStickerZoomScrim()`, `findZoomedSticker()`.
  - Every sticker type in `renderStickers()` now arms the gate instead of
    reacting to a plain tap directly.
  - `onResume()` now settles (shrinks back + resumes) any sticker left
    enlarged if the viewer navigated away (mention/hashtag/link/add-yours)
    and returned.

## Notes
- No Firebase schema changes — this is purely a viewer-side interaction/
  animation change.
- minSdk is 23, so `setElevation()`/property animations used here are safe
  (already used elsewhere in the codebase, e.g. `StoryReshareCardView`).
