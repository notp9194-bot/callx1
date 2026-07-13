# v146 — Attach sheet: real grid borders + eased crossfade motion

## Grid/strip border bug fix
Root cause: the border was painted on the SAME ImageView that Glide loads
the centerCrop thumbnail into, so the image fully covered its own
background — border was there in code but literally invisible on screen
("media chipak rahe the").

Fix: border now lives on the cell's ROOT FrameLayout; the ImageView is
inset by 1dp on all sides so the root's background peeks through as an
actual visible line between adjacent cells.
- `item_attach_grid_media.xml` — root bg `#33FFFFFF` (line color), inset
  ImageView bg `#2A2A2A` (fill while loading). Square, no radius — reads
  as a continuous grid like the reference screenshots.
- `item_attach_strip_media.xml` — root now uses new
  `bg_media_thumb_border_rounded.xml` (transparent fill, 10dp rounded
  stroke), inset ImageView keeps `bg_media_thumb_camera` for its fill.
- `bg_media_thumb_camera.xml` — dropped its own (now-redundant, would've
  been hidden anyway) stroke.

## Eased crossfade (was linear alpha, now has actual motion)
`AttachSheetRecentMediaBinder`'s `BottomSheetBehavior.BottomSheetCallback
#onSlide()`:
- Progress through `DecelerateInterpolator(1.6f)` instead of raw
  `slideOffset` — starts fast, settles gently instead of a flat linear
  dissolve.
- `icon_grid_section` + `bottom_media_row`: fade out AND drift up
  (`translationY` up to -14dp) AND shrink very slightly (scale 1→0.96) as
  they leave — reads as "lifting away" rather than just dissolving.
- `expanded_header`: fades in while sliding down into place from -14dp,
  same interpolator, so the two layers' motion mirrors each other.

## Files touched
- `feature-chat/src/main/res/layout/item_attach_grid_media.xml`
- `feature-chat/src/main/res/layout/item_attach_strip_media.xml`
- `feature-chat/src/main/res/drawable/bg_media_thumb_camera.xml`
- `feature-chat/src/main/res/drawable/bg_media_thumb_border_rounded.xml` (new)
- `feature-chat/src/main/res/drawable/bg_media_thumb_grid.xml` (removed — superseded by inline root background)
- `feature-chat/src/main/java/.../controllers/AttachSheetRecentMediaBinder.java`
