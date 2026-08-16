# v262 — Chat Media Editor: Flip / Mirror (Horizontal + Vertical)

Fixes the remaining gap in `MediaEditActivity`'s top toolbar (`feature-chat`):

❌ Flip/mirror — only 90° rotate existed → ✅ **Flip added (horizontal + vertical)**

## What changed

- `EditState` gained `flipHorizontal` / `flipVertical` booleans (independent
  of `rotationDeg`, so any combination of rotate + flip is possible), and
  `hasEdits()` now accounts for them.
- New **Flip** toolbar button (`btnEditFlip`, next to Rotate):
  - **Tap** → toggles horizontal flip (mirror left/right).
  - **Long-press** → toggles vertical flip (mirror top/bottom), with a
    short Toast confirming the state change.
  - Faded/disabled for videos, same as Rotate (video pixel flip isn't
    wired into `VideoOverlayBaker` — out of scope here, matches Rotate's
    existing video behavior).
- `applyFlipToPreview()` — mirrors `applyRotationToPreview()`: animates
  `scaleX`/`scaleY` on `ivPreview`, and keeps `drawOverlay` +
  `stickerLayer` in sync so strokes/stickers still line up live while
  the user draws or places overlays on a flipped image.
- `showCurrentItem()` resets `scaleX`/`scaleY` from the item's saved flip
  state on every item switch, mirroring the existing rotation reset.
- `loadImageWithFilter()` (live preview decode) and `bakeBitmap()` (final
  send export) both bake flip into the same `Matrix` already used for
  rotation (`postScale` before `postRotate`), so the exported image
  always matches what was shown in the editor — same pattern the
  existing rotation baking already used, and the preview resets
  `scaleX/scaleY` to `1` afterwards since it's now baked into the pixels
  (exactly like it already resets `rotation` to `0`).
- Overlay/stroke positions (`paintOverlaysAndStrokes`, `DrawOverlayView`)
  needed no changes — they already record touch coordinates in the
  view's local (post-transform) space, which Android correctly
  reverse-maps for any view transform (rotation OR scale), so the same
  mechanism that already made rotated strokes/stickers bake correctly
  carries over to flipped ones without modification.
- New icon: `ic_media_edit_flip.xml`.

## Files touched

- `feature-chat/.../MediaEditActivity.java`
- `feature-chat/src/main/res/layout/activity_media_edit.xml`
- `feature-chat/src/main/res/drawable/ic_media_edit_flip.xml` (new)

No other module touched. Backward compatible — `flipHorizontal`/
`flipVertical` default to `false`, so existing un-flipped items behave
exactly as before.
