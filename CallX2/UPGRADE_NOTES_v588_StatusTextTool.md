# v588 — Status editor: text tool upgrade + exact-dp editor chrome

## Text tool (Add text / Mention)
- Dialog now has live preview + 7 colours, font cycle (Classic/Bold/Serif/Mono/Italic),
  alignment (Center/Left/Right), background (No bg/Solid/Dim) and size slider (16–56sp).
  Cancel/back restores the previous style; "Remove" shown when editing existing text.
- Tap the text on the canvas to edit it.
- Drag to move; two-finger pinch resizes and twist rotates (maths done in canvas
  coordinates so rotate/scale don't feed back into themselves).
- Position clamp: text centre stays on the canvas and out of the right tool-icon column.
- Default style is unchanged (white, bold, 30sp, shadow). Still one text overlay per status.

## dp fix (5)
- Sticker size bar (S/M/L) and the composer's sticker stacking gap/top margin used
  `(int) density` (2.625 -> 2), shrinking them ~24%. Now exact `dp()`.
- NOT changed on purpose: sticker card width (`dp*280`) in NewStatusActivity,
  StatusViewerActivity, StatusStickerOverlayView and StatusStickerPickerSheet still use the
  truncated density so composer and viewer render identical sizes. Fixing those must be
  done together as one visual migration.

## Files
- feature-status/.../compose/NewStatusActivity.java

## Verification
- Android build/tests not run (no SDK). Test: text styles, tap-to-edit, pinch+twist,
  Save/post (rotated text exported), size bar on a 2.625-density device.
