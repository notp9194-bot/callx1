# v313 — Sound Detail: Premium Visual Redesign

Scope: `fragment_sound_detail.xml` (the real screen — reels → tap sound name
→ `SoundDetailActivity`/`SoundDetailSheetFragment` → `SoundDetailFragment`,
single source of truth) + its supporting drawables and `SoundWaveformView`.
`SoundDetailBottomSheet`/`bottom_sheet_sound_detail.xml` (separate, unwired
Instagram-mock layout) left untouched — out of scope.

## Flat design (no elevation/shadows)
- `bg_sound_detail_box`, `bg_sound_stat_chip`, `bg_sound_detail_action_btn`:
  removed `<stroke>` + all `android:elevation="1dp"` call sites. Fill is now
  a new subtle brand-tinted color (`sound_detail_box_tint` — light + night)
  instead of `colorSurface` + a border-as-shadow-edge.
- Removed `android:elevation="8dp"` from the mini-player and floating
  action bar too.

## Spacing
- Waveform card: padding 10dp→16dp, marginTop 14dp→20dp.
- Creator row: minHeight 56dp→62dp, marginTop 12dp→16dp, paddingHorizontal
  12dp→14dp.
- Action button row (Camera/Video/Add to profile): height 30dp→34dp,
  marginTop 16dp→22dp, inter-button margin 6dp→8dp.
- Chips row marginTop 8dp→10dp. Info panel paddingBottom 8dp→14dp.

## Waveform / seekbar refine
- `SoundWaveformView`: bar-width ratio 0.4→0.28 (thinner), maxHPx brought
  down to fit the view's own 26dp height (was 38dp — bars were previously
  clipping past the container), idle color dimmed, playing color moved off
  a loud neon red to the app's own brand purple.
- New thin custom seekbar: `bg_seekbar_track_sound.xml` (3dp track) +
  `ic_seekbar_thumb_sound.xml` (10dp thumb), replacing the default chunky
  Material SeekBar drawable/thumb.

## Icons → outline
- New `ic_sound_camera_outline.xml` / `ic_sound_video_outline.xml`, scoped
  to `feature-reels` only — the shared app-wide `ic_camera`/`ic_video`
  (filled, used elsewhere) are untouched.

## Cover image — rounder + glow
- `iv_sound_cover` is now a `ShapeableImageView` (18dp corner radius via
  new `ShapeAppearance.SoundDetail.Cover` style) instead of a plain
  unclipped `ImageView`.
- Added `bg_sound_cover_glow.xml` — a soft low-alpha radial gradient behind
  the cover (container grown 99×136dp → 111×148dp, cover re-centered at
  its original size) instead of a hard-edge shadow.

## Follow button
- `styleFollowBtn()`: swapped default/active states — "Follow" (not yet
  following) is now outline (transparent fill + brand stroke); "Following ✓"
  (actively followed) is the only filled-solid state. Previously the
  opposite.

## Micro-animations
- New `bounceView()` helper (scale 0.85→1 with an OvershootInterpolator,
  220ms) wired into the play/pause button, the follow toggle, and the save
  toggle.

## Typography
- Only two weights left on this screen: default (regular) and
  `sans-serif-medium`. The two `sans-serif-bold` occurrences (sound title,
  creator name) switched to `sans-serif-medium`.

## Dark mode
- Already had full `values-night` support (unchanged) — new
  `sound_detail_box_tint` / `sound_drag_handle` tokens added for both
  light and night so the flat-tint redesign works in both.
