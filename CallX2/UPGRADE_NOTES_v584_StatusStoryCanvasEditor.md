# v584 — Instagram-style Status Story Canvas Editor

## What changed
- Picking a photo now opens the existing Add Status wizard's Edit step.
- The media preview is a responsive 9:16 story canvas with a blurred, darkened
  copy of the selected photo filling the background and the sharp photo centered
  in front.
- Pinch zoom and one-finger pan affect only the foreground photo. Text and
  finger-drawn marks are shown live over the canvas.
- Added a right-edge tool rail for Text, Stickers, Music, Restyle, Effects,
  Mention, Draw, Save, Label AI and More.
- Photo posting captures the canvas as a single story-ratio image before the
  existing compression and Cloudinary upload pipeline. Interactive status
  stickers remain in the existing sticker JSON so the viewer does not draw
  them twice.
- Save writes the current story preview to `Pictures/CallX/Statuses`.
- Video text/effect/draw actions continue through the existing Reel editor;
  the new canvas export path is for still photos.

## Files
- `feature-status/src/main/java/com/callx/app/compose/NewStatusActivity.java`
- `feature-status/src/main/java/com/callx/app/compose/StatusDrawOverlayView.java` (new)
- `feature-status/src/main/res/layout/activity_new_status.xml`

## Verification
- App build and tests were intentionally not run, per request.
