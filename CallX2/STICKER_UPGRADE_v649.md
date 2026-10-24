# v649 — Status sticker "ultra advanced" upgrade

Files changed
- core/.../stickers/StatusStickerOverlayView.java  (gesture engine rewritten)
- feature-status/.../compose/NewStatusActivity.java (sticker toolbar, undo/redo, duplicate, layers, lock, canvas gesture)
- feature-status/.../compose/StorySafeAreaView.java (nudgeIntoSafe)

What's new
1. Rotation bug fixed: twist angle is now computed in parent space (was view-local → half-speed / jitter).
2. Pinch + twist + 2-finger pan = one similarity transform around the fingers' midpoint.
3. Rotation magnets every 45° (+ guide line + degree read-out), scale magnet at 1.0x, haptic ticks.
4. Scale range 0.3x – 5x (was 0.5x – 2x).
5. Centre can't leave the frame; soft magnet back into the safe area (<=44dp) on release.
6. Snap to other stickers' centre / edges (yellow guides) in addition to frame centre (pink).
7. Selected sticker: dotted border + bottom-right handle = one-finger resize + rotate.
8. Selected sticker: pinch/twist/pan anywhere on the canvas (photo is not touched).
9. Toolbar: S/M/L + fine slider, undo/redo, Copy, bring-front, send-back, lock (+ music row for music stickers).
10. Spring release animation, trash "suck-in" removal, bar follows the sticker.
Reels editors get the new gesture engine automatically (same attachDragToParent API).
