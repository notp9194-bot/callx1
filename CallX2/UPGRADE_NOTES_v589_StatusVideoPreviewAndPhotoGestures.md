# v589 — Status editor: live video preview + better photo gestures

## Video (9)
- Picked video now plays on the story canvas (VideoView, muted, looping) instead of a static
  thumbnail. Thumbnail stays underneath as fallback and cross-fades to the live frame.
  Tap the canvas to pause/play (play icon shows while paused).
- Plays only while the screen is in front and on the Edit step; released on discard / destroy.
- Photo-only tools (Save, Label AI) are dimmed while a video is loaded.
- One-time hint that Text/Draw/Effects on video open the Reel editor (they bake into video there).
- Not changed: video overlays are still produced by the Reel editor — baking text/drawing into
  the video from this canvas would need the Reel export engine.

## Photo gestures (10)
- Drag now tracks the finger 1:1 (was `dx * 0.98`, so the photo trailed the finger).
- Two-finger twist rotates the photo (snaps to 0/90/180/270° within 4° with a haptic tick);
  scale/rotation stay anchored on the fingers' midpoint. Export bakes the rotation.
- Double-tap resets scale/position/rotation (animated). "Reset photo position" also resets rotation.
- Tapping empty canvas now dismisses the sticker S/M/L bar (it never did: the full-canvas gesture
  layer swallowed the frame's click).
- Not changed: mention text baked into the photo is still not tappable in the viewer.

## Files
- feature-status/.../compose/NewStatusActivity.java

## Verification
- Android build/tests not run (no SDK here). Test: video play/pause + background/foreground,
  photo twist+pinch+drag, double-tap reset, Save/post with a rotated photo, size-bar dismiss.
